package com.md.agentplatform.server.channel;

/**
 * 渠道适配器的两大族 —— 决定"消息从哪进来、回复怎么出去"。
 *
 * <h2>为什么需要区分，而不是一句"都是渠道"</h2>
 * 官方 {@code Channel} 与官方框架的 {@code Gateway} 是绑定的：它的入站来自
 * Spring 控制器（回调 URL），出站由通道自己的 outbound client 负责，
 * 派发走 {@code Gateway#run}。而本项目原来的两条通道是<b>长连接 + 自渲染</b>的：
 * 企微智能机器人走 WebSocket 订阅、飞书走 WS 长连接，回复由本项目的渲染器
 * （流式思考卡片 / 卡片）直接推回，不经过官方 outbound。
 *
 * <p>两者不是"新旧两版同一件事"，而是<b>同一平台的两套接入方式</b>：
 * <ul>
 *   <li>{@link #OFFICIAL}：自建应用 + 回调 URL。需要 corpId/agentId/token/encodingAesKey 这类
 *       管理后台凭据，且必须有一个「公网可达的回调地址」；</li>
 *   <li>{@link #BOT}：机器人长连接。只需要 botId/secret（或 appId/appSecret），
 *       不需要暴露回调地址，但回复形态受本项目渲染器限制。</li>
 * </ul>
 * 因此类型注册表把它们并列成不同的 type，由页面按需选 ——
 * 而不是替用户决定"现在应该用哪套"，那会直接改掉线上正在跑的机器人行为。
 */
public enum ChannelKind {

    /** 官方 {@code io.agentscope...channel.Channel} 实现，回调式接入，需要 Gateway。 */
    OFFICIAL,

    /** 本项目的长连接机器人实现（{@code BotInstance}），自带回复渲染。 */
    BOT;
}
