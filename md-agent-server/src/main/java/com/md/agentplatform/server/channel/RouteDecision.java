package com.md.agentplatform.server.channel;

/**
 * 一条入站消息的路由结论。
 *
 * <h2>为什么要把 matchedBy 带回调用方</h2>
 * 渠道绑定出了问题（"配了规则却没生效"）时，唯一有用的信息是<b>哪一层命中的</b>。
 * 官方 {@code ChannelRouter} 已经把这件事算出来了（explicit / peer / guild+roles /
 * ... / channel-default / global-default），丢掉它就等于把排查方式从
 * "看一行日志" 变成 "猜是不是 peer 写错了 key"。所以它一路带到日志与链路里。
 *
 * @param channelId  来源渠道（= 旧结构的 botName）
 * @param agentId    本次交给哪个 agent
 * @param matchedBy  命中的层级标签（官方 ChannelRouter 给出的取值）
 * @param sessionKey 官方路由算出的会话键（{@code MsgContext#canonicalKey()}）；
 *                   本项目当前的会话仍由各自的 state store 管理，这个值先只做观测
 */
public record RouteDecision(String channelId, String agentId, String matchedBy, String sessionKey) {}
