package com.md.agentplatform.server.controlplane;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.agent.DbMcpClientFactory;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ResourceType;
import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;
import com.md.agentplatform.server.controlplane.store.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 发布编排 —— 把「落库」和「热装配」串成一个动作。
 *
 * <h2>一、为什么这一层必须存在，而不是塞进 Store 或 Controller</h2>
 * 两个极端都不行：
 * <ul>
 *   <li>塞进 {@link ControlPlaneStore}：存储层就要依赖 {@link AgentManager}（运行时注册表），
 *       于是存储层再也没法单独测，也说不清"事务边界到哪为止"；</li>
 *   <li>塞进 Controller：同一个动作会被复制到四个资源的四个 handler 里，
 *       "发 MCP 要先失效连接"这类顺序约束必然漏掉一处。</li>
 * </ul>
 * 所以中间放一层：<b>Store 只管落库，Controller 只管收参数，这一层只管顺序</b>。
 *
 * <h2>二、顺序是这个类的全部价值</h2>
 * <ol>
 *   <li><b>先落库、后热装配</b>：落库失败就什么都不做；反过来的话，
 *       实例已经换成新内容、库里却还是旧的，而"库里是唯一真相"这条前提就断了；</li>
 *   <li><b>MCP 要先失效连接缓存、再重建 agent</b>：MCP client 是按名缓存的
 *       （一个 client 服务所有引用它的 agent）。不先失效，agent 重建时会复用旧连接，
 *       于是"页面改了端点、也发布了，却还在连老地址"；</li>
 *   <li><b>只重建引用方，不重建全部</b>：改一个 prompt 就重启 14 个 agent 没必要，
 *       而且重建是有代价的（重新建 MCP 连接、重新读技能）。</li>
 * </ol>
 *
 * <h2>三、单个 agent 重建失败不影响其他</h2>
 * 一个 agent 装配失败（比如引用了一个已被停用的 MCP）不该让整次发布回滚：
 * 库里的状态是自洽的（发布成功已提交），失败的是"这个实例这次没换成新的"，
 * 记进结果里让人看见即可。反过来若在这里回滚数据库，就会出现
 * "页面显示没发布、快照却已经落了一份"的不一致。
 *
 * <h2>四、回滚不触发热装配</h2>
 * 回滚的语义是"准备一个旧内容的新版本"，结果置 DRAFT（设计文档 §4.4），
 * 线上仍是原来那一版 —— 所以这里不做任何重建，避免手滑直接影响线上。
 */
@Component
public class ControlPlanePublishService {

    private static final Logger log = LoggerFactory.getLogger(ControlPlanePublishService.class);

    @Autowired
    private ControlPlaneStore store;

    @Autowired
    private AgentManager agentManager;

    @Autowired
    private DbMcpClientFactory dbMcpClientFactory;

    /** 只为"默认模型换端点时补上没声明 model_ref 的那批 agent"这一件事而引入，见 hotReload。 */
    @Autowired
    private ModelMapper modelMapper;

    /** 发布：落库 + 快照 + 热装配引用方。 */
    public PublishResult publish(ResourceType type, String name, String operator, String note) {
        store.publish(type, name, operator, note);
        return hotReload(type, name);
    }

    /** 回滚：只把快照写回草稿，不触发热装配（理由见类注释第四节）。 */
    public PublishResult rollback(ResourceType type, String name, int version, String operator) {
        store.rollback(type, name, version, operator);
        return new PublishResult(List.of(),
                "已回滚到 v" + version + " 并置为草稿；线上仍是原版本，需再点一次「发布」才生效");
    }

    // ==================== 热装配 ====================

    /**
     * 按资源类型定位"该重建谁"，逐个重建。
     *
     * @return 被重建的 agent 名 + 失败详情；两者都为空时说明本次不需要重启任何实例
     */
    private PublishResult hotReload(ResourceType type, String name) {
        if (type == ResourceType.WORKFLOW) {
            // 工作流的发布不改变任何 agent 的装配结果：引擎在执行的那一刻按冻结的 revision
            // 读定义，而不是在发布时把内容"灌"进某个常驻实例。所以这里既没有要重建的实例，
            // 也没有要失效的缓存 —— 直接返回，且给出一句说明，避免页面上出现
            // "没有 agent 引用它，无需热装配"这种看起来像配置错了的提示。
            return new PublishResult(List.of(), "工作流发布后立即对新执行生效（进行中的执行仍按启动时冻结的版本跑完），无需重建 agent");
        }
        List<String> targets = new ArrayList<>(type == ResourceType.AGENT
                ? List.of(name)
                : store.findReferencingAgents(type, name));
        // 模型是特例：除"显式引用了它"的 agent 之外，**没声明 model_ref 的 agent 也在用它**
        //（它们装配时解析到的是平台默认模型）。漏掉这一批的表现是
        //"改了默认模型的端点，一半 agent 生效、一半还在连老地址"，而且看不出规律。
        if (type == ResourceType.MODEL) {
            ModelEntity model = store.findModel(name);
            if (model != null && Boolean.TRUE.equals(model.getIsDefault())) {
                for (String agentName : modelMapper.findAgentsWithoutModelRef()) {
                    if (!targets.contains(agentName)) {
                        targets.add(agentName);
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            return new PublishResult(List.of(), "没有 agent 引用它，无需热装配");
        }
        // MCP：先断旧连接，让下面的重建拿到新端点（顺序说明见类注释）
        if (type == ResourceType.MCP) {
            dbMcpClientFactory.invalidate(name);
        }
        List<String> reloaded = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String agentName : targets) {
            try {
                /*
                 * 这里刻意**不**先用 agentManager.exists(...) 过滤。
                 *
                 * 那个判断的本意是"跳过已停用的"，但新建的 agent 同样不在运行时注册表里 ——
                 * 而"首次发布"恰恰是最需要装配的时刻。于是首次发布被当成"已停用"跳过了：
                 * 库里 PUBLISHED、运行时什么都没有，页面上一点对话就是 404
                 * （前端把 404 统一翻成"agent 不存在或未装配"），只有重启服务或手动点
                 * 「重载」才能救回来 —— 而重启会顺带把所有人都踢下线。
                 *
                 * 正确的判据是"库里有没有可装配的定义"，这件事由 reloadIfPresent 表达：
                 * 返回 null = 定义不存在或已停用 = 本次无需装配（不是错误，不计入 failures）。
                 */
                if (agentManager.reloadIfPresent(agentName) == null) {
                    continue;
                }
                reloaded.add(agentName);
            } catch (Exception e) {
                log.error("[ControlPlane] 发布后重建 agent 失败: {} — {}", agentName, e.getMessage(), e);
                failures.add(agentName + ": " + e.getMessage());
            }
        }
        return new PublishResult(reloaded, failures.isEmpty() ? null
                : "以下 agent 重建失败（库里已生效，实例仍是旧的）：" + String.join("; ", failures));
    }

    /**
     * 发布结果。
     *
     * @param reloadedAgents 被重建的 agent 名
     * @param note           人话说明；null = 无异常
     */
    public record PublishResult(List<String> reloadedAgents, String note) {}
}
