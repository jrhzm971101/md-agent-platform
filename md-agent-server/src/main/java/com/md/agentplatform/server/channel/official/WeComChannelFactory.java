package com.md.agentplatform.server.channel.official;

import com.md.agentplatform.server.channel.ChannelAdapter;
import com.md.agentplatform.server.channel.ChannelAdapterFactory;
import com.md.agentplatform.server.channel.ChannelCapability;
import com.md.agentplatform.server.channel.ChannelKind;
import com.md.agentplatform.server.channel.ChannelPropertySpec;
import com.md.agentplatform.server.channel.ChannelTypeDescriptor;
import com.md.agentplatform.server.channel.OfficialChannelAdapter;
import io.agentscope.extensions.channel.wecom.WeComChannel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 企微回调式通道工厂（typeId = {@code wecom}）—— 直接包官方 {@link WeComChannel}。
 *
 * <h2>这个类的价值只有三件事</h2>
 * <ol>
 *   <li><b>声明凭据字段</b>：页面据此动态渲染表单。key 必须与官方
 *       {@code WeComChannelProperties.from()} 读的 key 逐字一致 ——
 *       写错的表现是"页面上明明填了、装配却说必填项缺失"；</li>
 *   <li><b>把库里的 Map 原样交给官方工厂</b>：不自己翻译成强类型再翻译回去。
 *       官方 {@code fromProperties(channelId, routing, props)} 自己会校验
 *       （corpId 非空、agentId 为正整数、encodingAesKey 必须 43 位），
 *       它抛的 IllegalArgumentException 由 {@code ChannelRuntime} 翻成页面上的失败原因；</li>
 *   <li><b>包成 {@link OfficialChannelAdapter}</b>：让 {@code ChannelRuntime} 能在 start 之前
 *       统一 bindGateway，并在失败时把原因带回页面。</li>
 * </ol>
 *
 * <h2>回调地址从哪来（这是本类型唯一的"隐性前置条件"）</h2>
 * 官方的 {@code WeComCallbackController}（{@code @RestController}，随依赖自动挂载）
 * 暴露 {@code /api/channels/wecom/{channelId}/callback}，而它按 {@code channelId} 从官方
 * {@code WeComChannelRegistry} 里找实例。也就是说：
 * <ul>
 *   <li>这条通道<b>必须在跑</b>（{@code start()} 才会把实例注册进官方 registry），
 *       否则企微的回调会拿到 404 —— 现象是"机器人不回消息、日志一行 warn"；</li>
 *   <li>企微后台填的回调 URL 里最后那段必须与渠道名一致，
 *       所以有了 {@code callbackPath} 这个可选覆盖项（默认就是上面那条路径）。</li>
 * </ul>
 */
@Component
public class WeComChannelFactory implements ChannelAdapterFactory {

    @Override
    public ChannelTypeDescriptor descriptor() {
        return new ChannelTypeDescriptor(
                WeComChannel.TYPE,
                "企业微信（回调式）",
                ChannelKind.OFFICIAL,
                "企微自建应用 + 回调 URL：需要公网可达的回调地址；消息由官方通道派发给网关，回复由官方 outbound 直发。",
                List.of(
                        ChannelPropertySpec.required("corpId", "企业 ID (corpId)", "ww1234567890abcdef"),
                        ChannelPropertySpec.required("agentId", "自建应用 AgentId", "1000002", "数字，企微后台「应用管理」里"),
                        ChannelPropertySpec.secret("secret", "应用 Secret", "企微后台「应用管理 → Secret」"),
                        ChannelPropertySpec.secret("token", "回调 Token", "企微后台「接收消息」里自己设定的 Token"),
                        ChannelPropertySpec.secret("encodingAesKey", "回调 EncodingAesKey", "43 位，企微后台「接收消息」里生成"),
                        ChannelPropertySpec.optional("callbackPath", "回调路径（可选）", "/api/channels/wecom/{channelId}/callback",
                                "留空即用默认路径；只有反向代理改了前缀才需要填"),
                        ChannelPropertySpec.optional("apiBase", "API Base（可选）", "https://qyapi.weixin.qq.com",
                                "私有化/代理部署才需要改")),
                // 企微回调只产生单聊 peer（WeComInboundMapper 恒为 DIRECT），
                // 所以 dm_scope 是这里唯一能左右"上下文怎么分"的开关。
                ChannelCapability.of(ChannelCapability.DM_SCOPES,
                        "只作用于单聊：MAIN = 所有人与机器人共用一个会话，多用户场景请选 PER_PEER。"),
                // 回复由官方 outbound 直发，不经过本项目的回复渲染器
                ChannelCapability.notApplicable("回复由官方 outbound 发送，不走本项目的回复风格。"));
    }

    @Override
    public ChannelAdapter create(String channelId, ChannelConfig routing, Map<String, Object> properties) {
        // 官方工厂内部会做全部凭据校验（失败抛 IllegalArgumentException，由 ChannelRuntime 记成渠道状态）
        return new OfficialChannelAdapter(WeComChannel.TYPE, WeComChannel.fromProperties(channelId, routing, properties));
    }
}
