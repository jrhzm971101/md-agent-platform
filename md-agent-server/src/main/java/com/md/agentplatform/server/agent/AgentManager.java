package com.md.agentplatform.server.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
// 子 agent 执行形态的上下文键就定义在这里（CTX_FORCE_SYNC / CTX_FORCE_SYNC_TIMEOUT_SECONDS）。
// 直接引用框架常量而不是抄字符串：抄一遍就多一个可能写错、且写错了不报错（只是静默不生效）的地方。
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 运行时管理 —— 唯一装配入口。
 *
 * <p>初始化数据只从控制面表（MySQL）加载，不存在本地 MD/JSON 兜底。
 * 装配委托 {@link ConfigurableAgentBuilder}（typed + extra 多方式配置）。
 * 与 {@code BotInstanceManager}（管"消息从哪个通道来"）正交，本类只管"谁来回答问题"。
 */
@Component
public class AgentManager {

    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    /**
     * agent 名 → 子 agent 执行形态（装配期快照）。
     *
     * <p>为什么不每次调用都回查 {@code AgentDefinitionResolver}：那是一次数据库往返，而
     * {@code stream()} 是所有入口（页面对话 / 企微 / 飞书 / 工作流节点 / 定时任务）的必经之路，
     * 每次对话都多查一次库毫无必要；而 {@code create()} 本来就已经拿到了完整定义。
     * 与观测中间件的 {@code agentKinds} 是同一个套路：装配期定下来，运行时只读。
     *
     * <p>定义被改后 {@link #create} 会重装实例并覆盖这里，所以页面发布即生效，不需要额外失效逻辑。
     */
    private final Map<String, SubagentPolicy> subagentPolicies = new ConcurrentHashMap<>();

    /**
     * 一个 agent 的子 agent 执行形态。
     *
     * @param forceSync      是否强制子 agent 同步执行（false = 用框架默认，即由模型自己填的
     *                       {@code timeout_seconds} 决定）
     * @param timeoutSeconds 强制同步时的等待秒数；null = 用框架默认（30 秒）
     */
    private record SubagentPolicy(boolean forceSync, Integer timeoutSeconds) {
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ConfigurableAgentBuilder configurableAgentBuilder;

    /** 唯一的定义加载源：控制面表。 */
    @Autowired
    private AgentDefinitionResolver definitionResolver;

    @PostConstruct
    void init() {
        // 定义 / prompt / 技能 / MCP 全部来自控制面表。
        registerAll(definitionResolver.resolveAll());
        log.info("[Agent] 加载完成（source=mysql），共 {} 个: {}", agents.size(), agents.keySet());
    }

    /** 先建普通 REACT agent，再建 HARNESS（引用子 agent）。 */
    private void registerAll(List<AgentDefinition> defs) {
        defs.stream().filter(d -> d.getType() == AgentDefinition.Type.REACT).forEach(this::create);
        defs.stream().filter(d -> d.getType() == AgentDefinition.Type.HARNESS).forEach(this::create);
    }

    // ==================== 装配 ====================

    public Agent create(AgentDefinition def) {
        if (def.getName() == null || def.getName().isBlank()) {
            throw new IllegalArgumentException("Agent name 不能为空");
        }
        // 装配委托通用 builder；subagentFactory 用 agents::get 惰性路由（运行时新建立即可用）
        Agent agent = configurableAgentBuilder.build(def, agents::get);

        // 记下这个 agent 的子 agent 执行形态（见 subagentPolicies 的注释）。
        // 刻意放在 build 之后、与替换实例同一段：build 抛异常时不该用新策略覆盖掉仍在服役的旧实例。
        subagentPolicies.put(def.getName(), new SubagentPolicy(
                Boolean.TRUE.equals(def.getSubagentForceSync()),
                def.getSubagentSyncTimeoutSeconds()));

        // 替换旧实例时关闭
        Agent old = agents.put(def.getName(), agent);
        if (old instanceof AutoCloseable c && old != agent) {
            try { c.close(); } catch (Exception e) { log.warn("关闭旧 agent 失败: {}", def.getName(), e); }
        }
        log.info("[Agent] 已创建 {} (type={}, tools={})", def.getName(), def.getType(), def.getTools());
        return agent;
    }

    // ==================== 运行时管理 API ====================

    /**
     * 只摘掉运行时实例，<b>不动任何持久化</b>。
     *
     * <p>这是控制面发布 / 删除流程要用的那个方法：DB 源下"定义怎么删"归 {@code ControlPlaneStore}，
     * 运行时注册表不应该顺手替它做主 —— 否则会出现"运行时不在了、库里还在"这种半死状态。
     */
    public void unregister(String name) {
        subagentPolicies.remove(name);
        Agent agent = agents.remove(name);
        if (agent instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception e) { log.warn("关闭 agent 失败: {}", name, e); }
        }
        log.info("[Agent] 已摘除运行时实例 {}", name);
    }

    /**
     * 用当前 DB 内容重建一个 agent 的运行时实例（内容不变，只换实例）。
     *
     * <p>两个使用场景：<b>发布后热装配</b>（引用它的资源变了，实例要重建才能生效）、
     * <b>运行期复位</b>（实例跑脏了，重来一份）。
     *
     * @throws IllegalStateException agent 在库里不存在、已停用，或装配过程本身失败
     */
    public Agent reload(String name) {
        Agent agent = reloadIfPresent(name);
        if (agent == null) {
            throw new IllegalStateException("agent 不存在或已停用，无法重建: " + name);
        }
        return agent;
    }

    /**
     * 与 {@link #reload(String)} 同一件事，只是把「定义不存在 / 已停用」从异常降级成返回 null。
     *
     * <p><b>为什么必须有这么个变体</b>：发布后的热装配要遍历一批目标（改默认模型时是<b>全部</b>
     * agent），其中「库里没有可装配的定义」是正常情况、不是故障。用异常表达会逼调用方去 catch
     * 一个不是错误的东西，而"区分它和真正的装配失败"只剩比较异常消息文本这一条路 ——
     * 那种代码没人愿意写，于是调用方干脆自己加个前置判断（{@code agentManager.exists(name)}）
     * 想绕开它，结果就是新建 agent 首次发布永远装配不上（见 ControlPlanePublishService#hotReload）。
     */
    public Agent reloadIfPresent(String name) {
        AgentDefinition def = definitionResolver.resolve(name);
        return def == null ? null : create(def);
    }

    /**
     * 用 DB 内容全量重建运行时注册表：新增的装上、改过的换新、库里已没有的摘掉。
     *
     * <p>与 {@link #init()} 的区别：{@code init()} 只在启动时跑一次且从不摘除，
     * 本方法可以在运行期随时调用（导入完成后立即生效、或页面上的"全量重载"）。
     *
     * @return 重建后的 agent 数量
     */
    public int reloadAll() {
        List<AgentDefinition> defs = definitionResolver.resolveAll();
        registerAll(defs);
        java.util.Set<String> live = new java.util.HashSet<>();
        for (AgentDefinition def : defs) {
            live.add(def.getName());
        }
        // 先复制 key 再遍历：unregister 会改 map，直接遍历 keySet 会 ConcurrentModificationException
        for (String existing : new ArrayList<>(agents.keySet())) {
            if (!live.contains(existing)) {
                log.info("[Agent] {} 已从库中移除（或停用），摘掉运行时实例", existing);
                unregister(existing);
            }
        }
        log.info("[Agent] 全量重载完成，共 {} 个: {}", agents.size(), agents.keySet());
        return agents.size();
    }

    public Agent update(AgentDefinition def) {
        return create(def);
    }

    public Agent get(String name) {
        return agents.get(name);
    }

    public List<Agent> listAll() {
        return new ArrayList<>(agents.values());
    }

    /**
     * 名字 → 实例的只读快照。
     *
     * <p>为什么需要它，而不是复用 {@link #listAll()}：网关装配要按<b>名字</b>注册 agent
     * （官方 {@code GatewayBootstrap.Builder#agent(String, HarnessAgent)} 的第一个参数就是
     * 路由时用的 agentId），而 {@code listAll()} 丢掉了名字。调用方拿不到名字就只能
     * 反过来"猜"哪个实例对应哪个名字，那是不可靠的。
     *
     * <p>返回复制品而不是内部 map：装配期会在遍历过程中重建实例（{@code reloadAll}），
     * 直接暴露 ConcurrentHashMap 会让调用方看到"边遍历边变"的中间状态。
     */
    public Map<String, Agent> snapshot() {
        return new java.util.LinkedHashMap<>(agents);
    }

    public boolean exists(String name) {
        return agents.containsKey(name);
    }

    /**
     * 路由入口：按名字解析 agent 并流式执行（ReActAgent 与 HarnessAgent 均支持）。
     *
     * <p>它同时是<b>唯一</b>一处把「这个 agent 的子 agent 该怎么跑」写进上下文的地方 ——
     * 五个入口（页面对话 / 企微 / 飞书 / 工作流节点 / 定时任务）全都从这里过，写在这里才不用
     * 每加一个入口就记得补一次。见 {@link #applySubagentPolicy}。
     */
    public Flux<AgentEvent> stream(String name, List<Msg> msgs, RuntimeContext ctx) {
        Agent a = agents.get(name);
        if (a == null) {
            throw new IllegalStateException("Agent 不存在: " + name);
        }
        applySubagentPolicy(name, ctx);
        if (a instanceof ReActAgent react) {
            return react.streamEvents(msgs, ctx);
        }
        if (a instanceof HarnessAgent harness) {
            return harness.streamEvents(msgs, ctx);
        }
        throw new IllegalStateException("不支持的 agent 类型: " + a.getClass());
    }

    /**
     * 把「这个 agent 的子 agent 该同步还是异步」写进这次调用的上下文。
     *
     * <p><b>为什么需要这一步</b>：框架默认让<b>模型</b>用 {@code agent_spawn.timeout_seconds}
     * 决定（0=后台、>0=同步），同步超时后还会自动 promote 成后台任务。对"必须拿到结果才能继续"
     * 的编排，这种"看模型心情"的不确定性是致命的。页面把 {@code subagent-force-sync} 打开之后，
     * 这里写下 {@link AgentSpawnTool#CTX_FORCE_SYNC}，框架侧优先级高于模型参数。
     *
     * <p><b>为什么可以就地改传进来的 ctx</b>：五个入口都是"每次调用新建一个 RuntimeContext"，
     * 这个对象在本次调用结束前不会给别人用；子 agent 从它复制出去之后也各自独立，
     * 不存在"改了这里把别的并发调用一起改了"的可能。
     *
     * <p><b>已经写过的键一律不动</b>：调用方（比如将来某个入口想对某次调用单独强制/取消强制）
     * 显式设过的值优先。观测中间件 {@code RUN_ID_ATTR} 也是这个"只是建议、不覆盖"的套路。
     *
     * <p><b>为什么在 stream 里做而不是在装配期做</b>：{@code AgentSpawnTool} 每次调用都从
     * RuntimeContext 现读这两个键，装配期拿不到"这一次调用"的上下文。
     */
    private void applySubagentPolicy(String name, RuntimeContext ctx) {
        if (ctx == null || ctx.get(AgentSpawnTool.CTX_FORCE_SYNC) != null) {
            return;
        }
        SubagentPolicy policy = subagentPolicies.get(name);
        if (policy == null || !policy.forceSync()) {
            return;
        }
        ctx.put(AgentSpawnTool.CTX_FORCE_SYNC, Boolean.TRUE);
        // 超时秒数只在显式配过时才写：框架侧的语义是"写了就完全取代模型给的 timeout_seconds"，
        // 写一个 null 或 0 会把"用默认 30 秒"变成"用 0 秒"（框架对 <=0 的兜底是回到 30，
        // 但那是它的实现细节，不该指望）。
        if (policy.timeoutSeconds() != null) {
            ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, policy.timeoutSeconds());
        }
    }

    // ==================== 工具元数据扫描 ====================

    public List<ToolMeta> listAvailableTools() {
        List<ToolMeta> result = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(beanName);
                for (Method m : bean.getClass().getMethods()) {
                    Tool tool = m.getAnnotation(Tool.class);
                    if (tool == null) {
                        continue;
                    }
                    List<ToolMeta.ParamMeta> params = new ArrayList<>();
                    for (Parameter p : m.getParameters()) {
                        ToolParam tp = p.getAnnotation(ToolParam.class);
                        params.add(new ToolMeta.ParamMeta(
                                tp != null ? tp.name() : p.getName(),
                                tp != null ? tp.description() : ""));
                    }
                    result.add(new ToolMeta(tool.name(), beanName, tool.description(), params));
                }
            } catch (Exception ignored) {
                // 跳过无法实例化的 bean
            }
        }
        return result;
    }
}
