package com.md.agentplatform.server.channel;

import java.util.List;

/**
 * 一种渠道类型对「某个可选行为参数」的支持声明 —— 页面据此决定"这个控件要不要出现、有哪些可选值"。
 *
 * <h2>为什么凭据之外还需要它</h2>
 * 渠道行的字段分两类：
 * <ul>
 *   <li><b>连接凭据</b>（{@link ChannelPropertySpec}）：这个类型必须有，缺了连不上，
 *       所以每个类型各有一份清单；</li>
 *   <li><b>行为参数</b>（{@code dm_scope} / {@code reply_style}）：是全局列，但对某些类型
 *       <b>根本不参与求值</b>：官方回调式的回复走官方 outbound，不看本项目的回复风格；
 *       长连接机器人的会话又由本项目渲染器自己算，不经官方 {@code ChannelRouter} 的 DM 分支。</li>
 * </ul>
 * 行为参数如果对所有类型都摊在页面上，得到的不是"通用"，而是"填了却不生效" ——
 * 而这类困惑在页面上无法自查，只能去读源码。所以让每个类型自己声明：
 * {@link #options} 为空 = 不适用，页面不渲染该控件（但会显示 {@link #note}）。
 *
 * <h2>为什么"不适用"也必须给一句话</h2>
 * 控件消失与控件被藏起来，在用户眼里是同一件事。所以他必须知道"为什么没有这一项"，
 * 否则唯一的结论只能是"页面坏了"。
 *
 * @param options 该类型支持的取值（按页面展示顺序）；空列表 = 不适用
 * @param note    适用时的一句提醒、或不适用时的原因说明；没有就传 null
 */
public record ChannelCapability(List<String> options, String note) {

    /**
     * 官方 {@code ChannelRouter} DM 分支支持的四种收敛范围。
     *
     * <p>取值与官方 {@code io.agentscope.harness.agent.gateway.channel.DmScope} 枚举<b>逐字同名</b>，
     * 这样页面存进库的字符串可以原样交给官方解析，中间不需要一张映射表。
     */
    public static final List<String> DM_SCOPES =
            List.of("MAIN", "PER_PEER", "PER_CHANNEL_PEER", "PER_ACCOUNT_CHANNEL_PEER");

    public ChannelCapability {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 不适用：这个类型不读这个参数；{@code note} 用来解释原因。 */
    public static ChannelCapability notApplicable(String note) {
        return new ChannelCapability(List.of(), note);
    }

    /** 适用。 */
    public static ChannelCapability of(List<String> options, String note) {
        return new ChannelCapability(options, note);
    }

    /** 页面用的判断：有可选值才渲染这个控件。 */
    public boolean applicable() {
        return !options.isEmpty();
    }
}
