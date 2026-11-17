package com.md.agentplatform.server.channel;

import java.util.List;

/**
 * 一种渠道类型的「能力声明」：类型 id、展示名、族的归属、以及它需要哪些连接凭据。
 *
 * <h2>为什么描述跟着工厂走，而不是单独维护一张表</h2>
 * 描述（需要哪些凭据、哪个必填）与工厂（怎么用这些凭据造出通道）是同一件事的两面：
 * 分开写必然出现"描述里说要 secret，工厂其实读的是 appSecret"这种漂移，
 * 而现象是页面上填了却装配失败。让每个工厂自己吐出描述，
 * 「加一个平台」就永远是<b>一处</b>改动。
 *
 * <h2>为什么行为参数也要跟着类型声明</h2>
 * {@code dm_scope} / {@code reply_style} 是渠道表上的全局列，但不是每个类型都会读它们：
 * 官方回调式的回复走官方 outbound，长连接机器人的会话又由本项目渲染器自己算。
 * 把它们对所有类型都摊在页面上，得到的不是"通用"而是"填了却不生效" —— 见 {@link ChannelCapability}。
 *
 * @param typeId      存进 {@code md_channel.channel_type} 的取值（wecom / feishu / wecom-bot …）
 * @param label       页面上的中文名
 * @param kind        族的归属，决定装配期要不要 Gateway（见 {@link ChannelKind}）
 * @param description 一句话说明这个类型适合什么场景；页面在类型选择处展示
 * @param properties  连接凭据字段清单；无凭据的类型传空列表
 * @param dmScope     该类型对 {@code md_channel.dm_scope} 的支持情况（见 {@link ChannelCapability}）
 * @param replyStyle  该类型对 {@code md_channel.reply_style} 的支持情况
 */
public record ChannelTypeDescriptor(
        String typeId, String label, ChannelKind kind, String description,
        List<ChannelPropertySpec> properties,
        ChannelCapability dmScope, ChannelCapability replyStyle) {

    public ChannelTypeDescriptor {
        properties = properties == null ? List.of() : List.copyOf(properties);
        // 缺省 = 不适用：工厂忘了声明时，页面少一个控件（无害）；反过来默认"适用"，
        // 会多出一个填了不生效的控件，而那种困惑在页面上无法自查。
        dmScope = dmScope == null ? ChannelCapability.notApplicable("该类型不使用此参数") : dmScope;
        replyStyle = replyStyle == null ? ChannelCapability.notApplicable("该类型不使用此参数") : replyStyle;
    }

    /** 该类型是否需要一个可用的 Gateway 才能装配（官方回调式通道都需要）。 */
    public boolean requiresGateway() {
        return kind == ChannelKind.OFFICIAL;
    }
}
