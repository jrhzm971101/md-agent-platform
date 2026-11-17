package com.md.agentplatform.server.channel.bot;

import com.md.agentplatform.server.channel.ChannelAdapter;
import com.md.agentplatform.server.channel.ChannelAdapterFactory;
import com.md.agentplatform.server.channel.ChannelCapability;
import com.md.agentplatform.server.channel.ChannelKind;
import com.md.agentplatform.server.channel.ChannelPropertySpec;
import com.md.agentplatform.server.channel.ChannelTypeDescriptor;
import com.md.agentplatform.server.messaging.BotInstanceManager;
import com.md.agentplatform.server.messaging.InboundMessageHandler;
import com.md.agentplatform.server.messaging.config.WeComBotCredentials;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 企微长连接机器人工厂（typeId = {@code wecom-bot}）。
 *
 * <h2>为什么"长连接"和"回调式"是两个类型，而不是同一个 wecom 的一个开关</h2>
 * 两者的<b>凭据集合、连接方向、回复形态</b>都不同（详见 {@link ChannelKind} 的注释）：
 * 回调式需要自建应用凭据 + 公网回调地址，回复由官方 outbound client 直接发出；
 * 长连接只要 botId/secret，回复由本项目的流式思考卡片渲染。
 * 做成一个类型加一个 mode 开关，页面就得把两套字段混在一个表单里，
 * 而"选错了模式"的表现是"机器人不回消息"—— 那是最难自查的一类问题。
 * 拆成两个类型，用户在类型下拉里做的是一次语义明确的选择。
 *
 * <h2>依赖注入的方向</h2>
 * 本类注入 {@link InboundMessageHandler}（即 {@code MessageRouter}）。
 * 这在改造前是<b>不可能</b>的：那时 MessageRouter 还依赖 {@code BotInstanceManager}，
 * 而后者又要注入本类所需的处理器，形成环。现在依赖是单向的
 * （工厂 → MessageRouter → ChannelRouteTable），环已消失。
 */
@Component
public class WeComBotChannelFactory implements ChannelAdapterFactory {

    private final InboundMessageHandler handler;
    private final BotInstanceManager botRegistry;

    public WeComBotChannelFactory(InboundMessageHandler handler, BotInstanceManager botRegistry) {
        this.handler = handler;
        this.botRegistry = botRegistry;
    }

    @Override
    public ChannelTypeDescriptor descriptor() {
        return new ChannelTypeDescriptor(
                WeComBotChannelAdapter.TYPE_ID,
                "企业微信机器人（长连接）",
                ChannelKind.BOT,
                "企微智能机器人：WebSocket 长连接接入，不需要公网回调地址；回复由本项目的流式思考卡片渲染。",
                List.of(
                        ChannelPropertySpec.required("botId", "机器人 ID", "aib...", "企微管理后台「智能机器人」里的一串 id"),
                        ChannelPropertySpec.secret("secret", "机器人 Secret", "与机器人 ID 一起生成，只显示一次")),
                // 长连接机器人不经过官方 ChannelRouter：MessageRouter 只拿它的 agentId / matchedBy，
                // 会话 id 由 WeComThinkRenderer#resolveSessionId 自己算（群 = 群 id，单聊 = single-<userId>），
                // 所以 dm_scope 在这个类型上只影响一行日志。
                ChannelCapability.notApplicable(
                        "长连接机器人的会话由聊天窗口决定（单聊按人、群按群），不受此参数影响。"),
                // 回复由本项目的渲染器推回。renderer 只实现了企微这一种风格，
                // 换成飞书卡片会在发消息时直接失败，所以这里只给一个可选项。
                ChannelCapability.of(List.of("WECOM_THINK"),
                        "本项目自带的流式思考卡片（该平台唯一实现）。"));
    }

    @Override
    public ChannelAdapter create(String channelId, ChannelConfig routing, Map<String, Object> properties) {
        WeComBotCredentials credentials = WeComBotCredentials.from(properties);
        return new WeComBotChannelAdapter(channelId, routing, credentials, handler, botRegistry::logHealth);
    }
}
