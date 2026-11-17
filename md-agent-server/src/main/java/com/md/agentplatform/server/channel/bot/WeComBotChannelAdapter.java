package com.md.agentplatform.server.channel.bot;

import com.md.agentplatform.server.channel.BotChannelAdapter;
import com.md.agentplatform.server.messaging.BotInstance;
import com.md.agentplatform.server.messaging.InboundMessageHandler;
import com.md.agentplatform.server.messaging.config.WeComBotCredentials;
import com.md.agentplatform.server.messaging.wecom.WeComBotInstance;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;

/**
 * 企微长连接机器人的通道适配器 —— 把本项目的 {@link WeComBotInstance} 接到渠道治理上。
 *
 * <h2>为什么构造时就 new 出 BotInstance（而不是延迟到 start）</h2>
 * {@link WeComBotInstance} 的构造器只做"建线程池 + 存配置"，真正连 WS 在 {@code start()} 里。
 * 构造即就绪，于是"凭据翻不动"这类错误会在造适配器的瞬间抛出，
 * 由 {@code ChannelRuntime} 记成该渠道的装配失败原因 —— 而不是等到 start 时再报一个更绕的错。
 *
 * <h2>start / stop 直接转发给 BotInstance</h2>
 * 与官方通道不同，长连接通道没有"先 bindGateway"这一步：它的收发完全走自己的链路
 * （收：WS → {@code InboundMessageHandler}；发：渲染器 → WS push）。
 * 路由配置 {@link #routing()} 在这里只用于"消息该给哪个 agent"这一件事。
 */
public final class WeComBotChannelAdapter implements BotChannelAdapter {

    /** 渠道类型 id（= 库里 {@code md_channel.channel_type} 的取值之一）。 */
    public static final String TYPE_ID = "wecom-bot";

    private final String channelId;
    private final ChannelConfig routing;
    private final WeComBotCredentials credentials;
    private final WeComBotInstance bot;

    public WeComBotChannelAdapter(String channelId, ChannelConfig routing, WeComBotCredentials credentials,
                                  InboundMessageHandler handler, Runnable onHealthChange) {
        this.channelId = channelId;
        this.routing = routing;
        this.credentials = credentials;
        this.bot = new WeComBotInstance(channelId, credentials, handler, onHealthChange);
    }

    @Override
    public String channelId() {
        return channelId;
    }

    @Override
    public String channelType() {
        return TYPE_ID;
    }

    @Override
    public ChannelConfig routing() {
        return routing;
    }

    @Override
    public BotInstance bot() {
        return bot;
    }

    /** 账号标识取 botId —— 同平台配多个机器人时，绑定规则的 account 层靠它区分。 */
    @Override
    public String accountId() {
        return credentials.botId();
    }

    @Override
    public void start() {
        bot.start();
    }

    @Override
    public void stop() {
        bot.stop();
    }

    @Override
    public boolean isAlive() {
        return bot.isAlive();
    }
}
