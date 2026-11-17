package com.md.agentplatform.server.channel.bot;

import com.md.agentplatform.server.channel.BotChannelAdapter;
import com.md.agentplatform.server.messaging.BotInstance;
import com.md.agentplatform.server.messaging.InboundMessageHandler;
import com.md.agentplatform.server.messaging.config.FeishuBotCredentials;
import com.md.agentplatform.server.messaging.feishu.FeishuBotInstance;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;

/**
 * 飞书长连接机器人的通道适配器 —— 把本项目的 {@link FeishuBotInstance} 接到渠道治理上。
 *
 * <p>与 {@link WeComBotChannelAdapter} 结构完全对称，差别只有两点：
 * 平台凭据类型，以及 {@link #accountId()} 取 {@code appId}（飞书的"哪个机器人"就是哪个应用）。
 */
public final class FeishuBotChannelAdapter implements BotChannelAdapter {

    /** 渠道类型 id（= 库里 {@code md_channel.channel_type} 的取值之一）。 */
    public static final String TYPE_ID = "feishu-bot";

    private final String channelId;
    private final ChannelConfig routing;
    private final FeishuBotCredentials credentials;
    private final FeishuBotInstance bot;

    public FeishuBotChannelAdapter(String channelId, ChannelConfig routing, FeishuBotCredentials credentials,
                                   InboundMessageHandler handler) {
        this.channelId = channelId;
        this.routing = routing;
        this.credentials = credentials;
        this.bot = new FeishuBotInstance(channelId, credentials, handler);
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

    /** 账号标识取 appId（飞书"哪个机器人"= 哪个应用）。 */
    @Override
    public String accountId() {
        return credentials.appId();
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
