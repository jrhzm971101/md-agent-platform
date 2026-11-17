package com.md.agentplatform.server.channel.official;

import com.md.agentplatform.server.channel.ChannelAdapter;
import com.md.agentplatform.server.channel.ChannelAdapterFactory;
import com.md.agentplatform.server.channel.ChannelCapability;
import com.md.agentplatform.server.channel.ChannelKind;
import com.md.agentplatform.server.channel.ChannelPropertySpec;
import com.md.agentplatform.server.channel.ChannelTypeDescriptor;
import com.md.agentplatform.server.channel.OfficialChannelAdapter;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 飞书回调式通道工厂（typeId = {@code feishu}）—— 直接包官方 {@link FeishuChannel}。
 *
 * <p>结构与 {@link WeComChannelFactory} 完全一致（该类的类注释解释了"为什么要这层工厂"、
 * "为什么凭据 key 必须与官方逐字一致"），这里只列本平台的两点差异：
 * <ul>
 *   <li>{@code encryptKey / verificationToken} 在官方 properties 里是<b>可空</b>
 *       （不加密、不校验的内网环境合法），所以这里也标成选填 ——
 *       与官方保持一致，不额外收紧；</li>
 *   <li>回调地址是 {@code /api/channels/feishu/{channelId}/callback}，同一个 controller 按
 *       {@code channelId} 找实例，所以渠道名必须与飞书后台填的 URL 末段一致。</li>
 * </ul>
 */
@Component
public class FeishuChannelFactory implements ChannelAdapterFactory {

    @Override
    public ChannelTypeDescriptor descriptor() {
        return new ChannelTypeDescriptor(
                FeishuChannel.TYPE,
                "飞书（回调式）",
                ChannelKind.OFFICIAL,
                "飞书自建应用 + 回调 URL：需要公网可达的回调地址；消息由官方通道派发给网关，回复由官方 outbound 直发。",
                List.of(
                        ChannelPropertySpec.required("appId", "App ID", "cli_xxxxxxxxxxxxxxxx"),
                        ChannelPropertySpec.secret("appSecret", "App Secret", "飞书开放平台「凭证与基础信息」"),
                        ChannelPropertySpec.optional("verificationToken", "Verification Token", "事件订阅页",
                                "未开启校验可留空"),
                        ChannelPropertySpec.optional("encryptKey", "Encrypt Key", "事件订阅页",
                                "未开启加密可留空"),
                        ChannelPropertySpec.optional("callbackPath", "回调路径（可选）", "/api/channels/feishu/{channelId}/callback",
                                "留空即用默认路径"),
                        ChannelPropertySpec.optional("apiBase", "API Base（可选）", "https://open.feishu.cn",
                                "私有化/代理部署才需要改")),
                // 飞书回调既有单聊也有群聊（FeishuInboundMapper 按 chatType 分 DIRECT / GROUP）：
                // dm_scope 只影响单聊那支，群的会话键永远落在群上。
                ChannelCapability.of(ChannelCapability.DM_SCOPES,
                        "只作用于单聊；群聊的会话键固定落在群上，不受此参数影响。"),
                ChannelCapability.notApplicable("回复由官方 outbound 发送，不走本项目的回复风格。"));
    }

    @Override
    public ChannelAdapter create(String channelId, ChannelConfig routing, Map<String, Object> properties) {
        return new OfficialChannelAdapter(FeishuChannel.TYPE, FeishuChannel.fromProperties(channelId, routing, properties));
    }
}
