package com.md.agentplatform.server.channel;

import com.md.agentplatform.server.controlplane.store.entity.GatewayEntity;
import com.md.agentplatform.server.controlplane.store.mapper.GatewayMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 网关配置（{@code md_gateway} 单行）的读写。
 *
 * <h2>为什么不塞进 {@code ControlPlaneStore}</h2>
 * 那个门面只服务「受治理资源」（agent / prompt / skill / mcp / channel —— 都有草稿/发布/版本），
 * 而网关是一次装配的输入。把它混进去，会让"受治理资源"这个概念在代码里失效：
 * 以后有人按 ControlPlaneStore 的方法清单去理解平台，会以为网关也能发布和回滚。
 *
 * <h2>容错策略：读不到就当没设置</h2>
 * 表里本该有一行 id=1（DDL 里 INSERT 了）。若被人手工删掉，
 * 这里退回"未设置主 agent"并打 warn —— 场景是"网关不装配、页面顶部提示去设置"，
 * 而不是启动失败。反过来若在这里自动插一行，就掩盖了"有人绕过控制面改库"这件事。
 */
@Component
public class GatewaySettingsStore {

    private static final Logger log = LoggerFactory.getLogger(GatewaySettingsStore.class);

    /** 单行表固定主键。 */
    private static final int SINGLETON_ID = 1;

    private final GatewayMapper gatewayMapper;

    public GatewaySettingsStore(GatewayMapper gatewayMapper) {
        this.gatewayMapper = gatewayMapper;
    }

    /** 读当前配置；行不存在时返回一个"全空"的对象而不是 null，调用方少一处判空。 */
    public GatewayEntity load() {
        GatewayEntity row = gatewayMapper.selectById(SINGLETON_ID);
        if (row == null) {
            log.warn("md_gateway 里没有 id=1 的行（应存在于建表脚本的首批数据），按未配置处理");
            GatewayEntity empty = new GatewayEntity();
            empty.setId(SINGLETON_ID);
            return empty;
        }
        return row;
    }

    /** 保存主 agent；行不存在时补插（运维手工清过库的场景）。 */
    public GatewayEntity save(String mainAgentId, String description, String operator) {
        long now = System.currentTimeMillis();
        GatewayEntity row = load();
        row.setId(SINGLETON_ID);
        row.setMainAgentId(mainAgentId);
        row.setDescription(description);
        row.setUpdatedAtMs(now);
        row.setUpdatedBy(operator);
        if (gatewayMapper.selectById(SINGLETON_ID) == null) {
            gatewayMapper.insert(row);
        } else {
            gatewayMapper.updateById(row);
        }
        log.info("网关主 agent 已改为 {} operator={}", mainAgentId, operator);
        return row;
    }
}
