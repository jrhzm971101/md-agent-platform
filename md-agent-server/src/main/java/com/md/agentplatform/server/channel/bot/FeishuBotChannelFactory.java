package com.md.agentplatform.server.channel.bot;

import com.md.agentplatform.server.channel.ChannelAdapter;
import com.md.agentplatform.server.channel.ChannelAdapterFactory;
import com.md.agentplatform.server.channel.ChannelCapability;
import com.md.agentplatform.server.channel.ChannelKind;
import com.md.agentplatform.server.channel.ChannelPropertySpec;
import com.md.agentplatform.server.channel.ChannelTypeDescriptor;
import com.md.agentplatform.server.messaging.InboundMessageHandler;
import com.md.agentplatform.server.messaging.config.FeishuBotCredentials;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 飞书长连接机器人工厂（typeId = {@code feishu-bot}）。
 *
 * <p>与 {@link WeComBotChannelFactory} 同构；字段清单里的 {@code verificationToken / encryptKey}
 * 与官方回调式同名，但这里填的是"事件订阅"页里的那两个值（WS 模式下的用途是校验事件来源），
 * 所以 labeled 时写清楚，避免用户去"回调地址"页找。
 */
@Component
public class FeishuBotChannelFactory implements ChannelAdapterFactory {

    private final InboundMessageHandler handler;

    public FeishuBotChannelFactory(InboundMessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public ChannelTypeDescriptor descriptor() {
        return new ChannelTypeDescriptor(
                FeishuBotChannelAdapter.TYPE_ID,
                "飞书机器人（长连接）",
                ChannelKind.BOT,
                "飞书自建应用：WS 长连接接入，不需要公网回调地址；回复由本项目的飞书卡片渲染。",
                List.of(
                        ChannelPropertySpec.required("appId", "App ID", "cli_xxxxxxxxxxxxxxxx", "飞书开放平台「凭证与基础信息」"),
                        ChannelPropertySpec.secret("appSecret", "App Secret", "与 App ID 同页"),
                        ChannelPropertySpec.optional("verificationToken", "Verification Token", "v2 事件订阅页",
                                "事件订阅的校验 token；未开启校验可留空"),
                        ChannelPropertySpec.optional("encryptKey", "Encrypt Key", "事件订阅页",
                                "事件加密 key；未开启加密可留空"),
                        ChannelPropertySpec.optional("baseUrl", "Base URL", FeishuBotCredentials.DEFAULT_BASE_URL,
                                "私有化部署或代理时才需要改")),
                // 同企微长连接：会话由本项目渲染器决定（FeishuCardRenderer 用 userId 作 sessionId），
                // 官方 ChannelRouter 的 dm_scope 在这里不参与求值。
                ChannelCapability.notApplicable(
                        "长连接机器人的会话由聊天窗口决定，不受此参数影响。"),
                ChannelCapability.of(List.of("FEISHU_CARD"),
                        "本项目自带的飞书卡片（该平台唯一实现）。"));
    }

    @Override
    public ChannelAdapter create(String channelId, ChannelConfig routing, Map<String, Object> properties) {
        FeishuBotCredentials credentials = FeishuBotCredentials.from(properties);
        return new FeishuBotChannelAdapter(channelId, routing, credentials, handler);
    }
}
