package com.md.agentplatform.server.channel;

import java.util.List;

/**
 * 网关的运行时快照 —— 页面「Gateway」页要显示的全部状态。
 *
 * @param built           是否装配成功。false 时后面几个字段解释"为什么没装配"
 * @param mainAgentId     库里的主 agent（{@code md_gateway.main_agent_id}）
 * @param agentIds        实际纳管的 agent（HARNESS 且已在运行时注册）的名字，升序
 * @param routerDefault   路由兜底 agent —— 没有任何绑定与渠道兜底命中时消息的落点
 * @param note            人话说明：未装配的原因，或装配完成的一句话；null = 一切正常
 * @param loadedAtMs      本次装配时刻（epoch millis）；null = 从未装配
 */
public record ManagedGateway(
        boolean built,
        String mainAgentId,
        List<String> agentIds,
        String routerDefault,
        String note,
        Long loadedAtMs) {

    public ManagedGateway {
        agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
    }
}
