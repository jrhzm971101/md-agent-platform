package com.md.agentplatform.server.channel;

/**
 * 一个渠道的运行时状态 —— 页面每一行要显示的全部信息（库内容 + 运行态合并后的结果）。
 *
 * @param channelId        渠道名
 * @param channelType      类型（wecom / feishu / wecom-bot ...）
 * @param typeLabel        类型的中文名；类型未注册时为 null（页面显示"未知类型"）
 * @param kind             族的归属；见 {@link ChannelKind}
 * @param enabled          库里是否启用（决定它"该不该"在运行）
 * @param status           库里的生命周期状态：DRAFT / PUBLISHED
 * @param publishedVersion 已发布版本；null = 从未发布
 * @param started          当前是否有活着的实例
 * @param alive            实例自报的可用性（长连接重连期间会短暂 false）
 * @param error            最近一次装配/启动失败的原因；null = 无错误
 * @param bindingCount     绑定规则条数
 * @param appliedAtMs      最近一次成功装配的时刻；null = 从未成功装配
 */
public record ChannelRuntimeStatus(
        String channelId,
        String channelType,
        String typeLabel,
        ChannelKind kind,
        boolean enabled,
        String status,
        Integer publishedVersion,
        boolean started,
        boolean alive,
        String error,
        int bindingCount,
        Long appliedAtMs) {
}
