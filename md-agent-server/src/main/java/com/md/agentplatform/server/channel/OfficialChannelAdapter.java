package com.md.agentplatform.server.channel;

import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;

/**
 * 官方回调式通道（{@link ChannelKind#OFFICIAL}）—— 包装官方 {@link Channel} 实现。
 *
 * <h2>为什么必须在启动前 bindGateway</h2>
 * 官方通道的 {@code dispatch} 会调用 {@code Gateway#run(...)} 把消息交给 agent。
 * 没有 gateway 时官方实现会返回一个 error Mono（"channel xxx has no gateway"），
 * 而这条错误发生在<b>回调请求的线程里</b>，表现是"用户发消息、机器人不回"，
 * 日志里只有一行 warn。所以本类把它变成显式的装配前置条件：
 * {@link ChannelRuntime} 在 {@code start()} 之前调用 {@link #bindGateway(Gateway)}，
 * Gateway 不可用时这个渠道根本不会启动，状态里会写明原因。
 *
 * <p>{@link #isAlive()} 是本类自己记的：官方 {@code Channel} 接口没有存活探测
 * （回调式通道没有"连接"这个概念，它的可用性等于"回调地址可达"），
 * 所以这里只能表达"已启动 / 已停止"，由 {@link ChannelRuntimeStatus#error()} 补充失败原因。
 */
public final class OfficialChannelAdapter implements ChannelAdapter {

    private final String channelType;
    private final Channel channel;

    private volatile boolean started;

    public OfficialChannelAdapter(String channelType, Channel channel) {
        this.channelType = channelType;
        this.channel = channel;
    }

    /** 底层官方通道；回调控制器就是通过官方自己的注册表找到它并派发的。 */
    public Channel official() {
        return channel;
    }

    /** 注入 Gateway（官方 {@code Channel#init}），必须在 {@link #start()} 之前调用。 */
    public void bindGateway(Gateway gateway) {
        channel.init(gateway);
    }

    @Override
    public String channelId() {
        return channel.channelId();
    }

    @Override
    public String channelType() {
        return channelType;
    }

    @Override
    public io.agentscope.harness.agent.gateway.channel.ChannelConfig routing() {
        return channel.config();
    }

    @Override
    public void start() {
        channel.start();
        started = true;
    }

    @Override
    public void stop() {
        try {
            channel.stop();
        } finally {
            started = false;
        }
    }

    @Override
    public boolean isAlive() {
        return started;
    }
}
