package com.md.agentplatform.server.channel;

import io.agentscope.harness.agent.gateway.channel.ChannelConfig;

/**
 * 运行时持有的一个「活着的渠道」—— 类型工厂造出来的东西，{@link ChannelRuntime} 只管它的生命周期。
 *
 * <h2>为什么在官方 Channel 之上还要包一层</h2>
 * 官方 {@code Channel} 接口要求实现 {@code dispatch(InboundMessage)}，
 * 而本项目的长连接机器人是<b>推模式</b>的：消息由 WS 线程推给 {@code InboundMessageHandler}，
 * 不存在"给我一条消息、我返回结果"的调用形态。硬把它们塞进官方接口，
 * 只能造一个"永远不会被调用的 dispatch"，那是给接口看的假实现。
 *
 * <p>所以这一层的语义只保留两者真正共有的部分：<b>身份 + 路由配置 + 生命周期</b>。
 * 具体怎么收发消息由两个子接口各自表达（见 {@code BotChannelAdapter} / {@code OfficialChannelAdapter}）。
 */
public interface ChannelAdapter {

    /** 渠道唯一名，等于 {@code md_channel.channel_id}。 */
    String channelId();

    /** 渠道类型，等于 {@code md_channel.channel_type}（决定由哪个工厂造出来的）。 */
    String channelType();

    /**
     * 路由配置（官方 {@link ChannelConfig}）—— 兜底 agent、单聊会话范围、绑定规则。
     *
     * <p>返回官方类型而不是本项目自己的结构，是为了让路由求值能<b>原样</b>交给官方
     * {@code ChannelRouter}：少一层翻译，就少一处"两边理解不一致"的可能。
     */
    ChannelConfig routing();

    /** 启动并开始接收消息（幂等）。 */
    void start();

    /** 停止并释放连接（幂等）。 */
    void stop();

    /** 当前是否处于可用状态；长连接类通道在重连期间会短暂为 false。 */
    boolean isAlive();
}
