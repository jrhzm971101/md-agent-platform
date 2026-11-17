package com.md.agentplatform.server.channel.official;

import com.md.agentplatform.server.channel.ChannelAdapter;
import com.md.agentplatform.server.channel.ChannelAdapterFactory;
import com.md.agentplatform.server.channel.ChannelCapability;
import com.md.agentplatform.server.channel.ChannelKind;
import com.md.agentplatform.server.channel.ChannelPropertySpec;
import com.md.agentplatform.server.channel.ChannelTypeDescriptor;
import com.md.agentplatform.server.channel.OfficialChannelAdapter;
import io.agentscope.extensions.channel.gitlab.GitLabChannel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * GitLab 通道工厂（typeId = {@code gitlab}）—— 直接包官方 {@link GitLabChannel}。
 *
 * <h2>它是四个类型里唯一的"非 IM"通道</h2>
 * 入站是 GitLab 的 webhook（issue / note 事件），出站是往 MR/issue 里回帖。
 * 对本平台的治理模型没有特殊影响：一样是"消息进来 → 网关按绑定规则挑 agent → 回复发回去"，
 * 所以复用同一套渠道表与绑定表，不额外建模。
 *
 * <p>凭据就两个：访问 API 的 {@code token}，以及校验 webhook 来源的 {@code webhookToken}
 * （GitLab 后台建 webhook 时自己设的 secret）。两者都是必填 ——
 * 少了前者发不出回复，少了后者任何人都能往你的 agent 里灌消息。
 */
@Component
public class GitLabChannelFactory implements ChannelAdapterFactory {

    @Override
    public ChannelTypeDescriptor descriptor() {
        return new ChannelTypeDescriptor(
                GitLabChannel.TYPE,
                "GitLab",
                ChannelKind.OFFICIAL,
                "GitLab webhook：issue / 评论事件进来，回复以评论发回；需要公网可达的 webhook 地址。",
                List.of(
                        ChannelPropertySpec.secret("token", "访问 Token", "有 api 权限的个人/项目 access token"),
                        ChannelPropertySpec.secret("webhookToken", "Webhook Secret", "GitLab 后台建 webhook 时设的 secret，用于校验来源"),
                        ChannelPropertySpec.optional("apiBase", "API Base（可选）", "https://gitlab.com/api/v4",
                                "自建 GitLab 填自己的地址（会自动补 /api/v4）"),
                        ChannelPropertySpec.optional("webhookPath", "Webhook 路径（可选）", "/api/channels/gitlab/{channelId}/webhook",
                                "留空即用默认路径")),
                // GitLab 事件只映射成线程 peer（GitLabInboundMapper: PeerKind.THREAD），
                // 而 dm_scope 只在官方 ChannelRouter 的 DIRECT 分支里被读 —— 配了也没有任何效果。
                ChannelCapability.notApplicable("GitLab 事件只有线程（THREAD）peer，没有单聊，该参数不参与求值。"),
                ChannelCapability.notApplicable("回复以 issue / MR 评论回帖，由官方 outbound 发送。"));
    }

    @Override
    public ChannelAdapter create(String channelId, ChannelConfig routing, Map<String, Object> properties) {
        return new OfficialChannelAdapter(GitLabChannel.TYPE,
                GitLabChannel.fromProperties(channelId, routing, properties));
    }
}
