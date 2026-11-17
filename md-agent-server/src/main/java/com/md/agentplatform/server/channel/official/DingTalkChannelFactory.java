package com.md.agentplatform.server.channel.official;

import com.md.agentplatform.server.channel.ChannelAdapter;
import com.md.agentplatform.server.channel.ChannelAdapterFactory;
import com.md.agentplatform.server.channel.ChannelCapability;
import com.md.agentplatform.server.channel.ChannelKind;
import com.md.agentplatform.server.channel.ChannelPropertySpec;
import com.md.agentplatform.server.channel.ChannelTypeDescriptor;
import com.md.agentplatform.server.channel.OfficialChannelAdapter;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannelProperties;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 钉钉通道工厂（typeId = {@code dingtalk}）—— 直接包官方 {@link DingTalkChannel}。
 *
 * <h2>为什么它归在 OFFICIAL 族（明明没有回调地址）</h2>
 * 已发布的 2.0.3 里，钉钉只有一种收消息方式：<b>反向连接的 WebSocket（stream 模式）</b>，
 * 由 {@code DingTalkStreamClient} 主动连出去，不需要公网回调 URL。
 * 这一点和本项目的「长连接机器人」（{@link ChannelKind#BOT}）在<b>网络形态</b>上很像，
 * 但归族看的不是网络形态，而是<b>回复怎么发出去</b>：
 * <ul>
 *   <li>BOT 族的回复走本项目自己的发送侧（{@code MessageRouter} + {@code WeComBotInstance}）；</li>
 *   <li>官方族的回复走官方 {@code dispatch()} → {@code Gateway.run()} → 官方 outbound 直发，
 *       因此<b>必须有网关</b>才能工作。</li>
 * </ul>
 * 钉钉属于后者，所以是 OFFICIAL，也就跟着受「网关未装配则不允许启用」这条校验约束。
 *
 * <h2>凭据字段按 jar 核对过，不是照着上游源码抄的</h2>
 * 上游源码树里的 {@code DingTalkChannelProperties} 比 2.0.3 多出 {@code mode} / {@code aesKey}
 * 两个字段（用于新增的 http 回调模式），<b>但 2.0.3 的 jar 里没有</b>
 * （已用 {@code javap} 核对 2.0.3 的 record 组件与 {@code from()} 读取的 key）。
 * 在这里多放一个 {@code mode} 输入框，结果是「页面上填了、装配时完全没人读」，
 * 比缺字段更难发现。所以本工厂只声明 2.0.3 真正会读的 6 个 key ——
 * 与 {@code DingTalkChannelProperties.from()} 逐字一致。
 */
@Component
public class DingTalkChannelFactory implements ChannelAdapterFactory {

    @Override
    public ChannelTypeDescriptor descriptor() {
        return new ChannelTypeDescriptor(
                DingTalkChannel.TYPE,
                "钉钉",
                ChannelKind.OFFICIAL,
                "钉钉机器人：反向连接（stream，无需公网回调地址）收消息；回复由官方 outbound 直发，因此必须先装配网关。",
                List.of(
                        ChannelPropertySpec.required("appKey", "AppKey", "ding...", "钉钉开放平台应用的 AppKey"),
                        ChannelPropertySpec.secret("appSecret", "AppSecret", "钉钉开放平台应用的 AppSecret"),
                        ChannelPropertySpec.required("robotCode", "机器人编码 (robotCode)", "ding...", "出站发送时作为机器人标识"),
                        ChannelPropertySpec.optional("apiBase", "API Base（可选）", DingTalkChannelProperties.DEFAULT_API_BASE, "私有化/代理部署才需要改"),
                        ChannelPropertySpec.optional("oapiBase", "OAPI Base（可选）", DingTalkChannelProperties.DEFAULT_OAPI_BASE, "取 token 用的旧接口根地址"),
                        ChannelPropertySpec.optional("streamRegisterUrl", "Stream 注册地址（可选）", DingTalkChannelProperties.DEFAULT_STREAM_REGISTER_URL, "私有化部署才需要改")),
                // 钉钉入站有群和单聊两类（DingTalkInboundMapper 按会话类型分 GROUP / DIRECT），
                // dm_scope 只对单聊那支生效。
                ChannelCapability.of(ChannelCapability.DM_SCOPES,
                        "只作用于单聊；群聊的会话键固定落在群上，不受此参数影响。"),
                ChannelCapability.notApplicable("回复由官方 outbound 发送，不走本项目的回复风格。"));
    }

    @Override
    public ChannelAdapter create(String channelId, ChannelConfig routing, Map<String, Object> properties) {
        return new OfficialChannelAdapter(DingTalkChannel.TYPE,
                DingTalkChannel.fromProperties(channelId, routing, properties));
    }
}
