package com.md.agentplatform.server.channel;

import com.md.agentplatform.server.controlplane.store.ChannelDefinitionBundle;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 渠道校验 —— 一次把问题列全（与 {@code MyBatisControlPlaneStore#validateChannel} 同风格，
 * 但职责不同，见下）。
 *
 * <h2>一、与存储层校验的分工</h2>
 * <ul>
 *   <li><b>存储层</b>（{@code validateChannel}）只做"与平台无关"的结构校验：
 *       名称/类型非空、绑定的 agent 真实存在、绑定至少有一个匹配条件、
 *       dm-scope 是官方枚举名。它不认识"wecom 需要 botId"这件事；</li>
 *   <li><b>本类</b>做"类型相关的"校验：类型是否已注册、该类型的凭据是否齐全、
 *       以及官方回调式通道需要的网关当前是否可用。
 *       这些知识来自各类型工厂自带的描述（{@link ChannelTypeDescriptor}），
 *       所以"加一个平台"不需要改这里。</li>
 * </ul>
 *
 * <h2>二、为什么网关检查要在"启动时"而不是"保存时"</h2>
 * 保存一个官方回调式渠道时，网关可能还没配好（用户完全可以先配渠道、再去 Gateway 页设主 agent）。
 * 那时把保存拦下来，等于强迫用户按某个特定顺序操作。所以：
 * <ul>
 *   <li>{@link #problems(ChannelDefinitionBundle)} 只校验类型与凭据，保存时用；</li>
 *   <li>{@link #gatewayBlockReason(ChannelTypeDescriptor)} 判断"能不能启动"，装配时用 ——
 *       拦不住保存，但会明确告诉用户"渠道已保存但未启动，因为网关未装配"。</li>
 * </ul>
 */
@Component
public class ChannelValidator {

    private final ChannelTypeRegistry registry;
    private final AgentGatewayRuntime gatewayRuntime;

    public ChannelValidator(ChannelTypeRegistry registry, AgentGatewayRuntime gatewayRuntime) {
        this.registry = registry;
        this.gatewayRuntime = gatewayRuntime;
    }

    /** 保存前的问题清单；空列表 = 可以保存。 */
    public List<String> problems(ChannelDefinitionBundle bundle) {
        List<String> problems = new ArrayList<>();
        if (bundle == null || bundle.channel() == null) {
            problems.add("渠道内容不能为空");
            return problems;
        }
        ChannelEntity channel = bundle.channel();
        ChannelAdapterFactory factory = registry.find(channel.getChannelType()).orElse(null);
        if (factory == null) {
            problems.add("未知的渠道类型: " + channel.getChannelType()
                    + "（已注册: " + registry.registeredTypes() + "）");
            return problems;
        }
        problems.addAll(credentialProblems(channel, factory.descriptor()));
        return problems;
    }

    /**
     * 按类型描述检查凭据。
     *
     * <p>只看"必填项是否给了非空值"，不做格式校验（比如 aesKey 必须 43 位）——
     * 格式规则由各自工厂或官方 properties record 在构造时判，
     * 它们比这里更清楚每种凭据的规则，报错也更具体（"wecom.encodingAesKey must be 43 characters"）。
     * 这里提前拦的价值是：<b>一次列出所有缺失项</b>，而不是让用户撞一次补一个。
     */
    private List<String> credentialProblems(ChannelEntity channel, ChannelTypeDescriptor descriptor) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> properties = channel.getProperties();
        for (ChannelPropertySpec spec : descriptor.properties()) {
            if (!spec.required()) {
                continue;
            }
            Object value = properties == null ? null : properties.get(spec.key());
            if (value == null || String.valueOf(value).isBlank()) {
                problems.add("缺少必填凭据 " + spec.key() + "（" + spec.label() + "）");
            }
        }
        return problems;
    }

    /**
     * 该类型能否启动；不能时返回原因（人话），能则返回 null。
     *
     * <p>目前只有一条规则：官方回调式通道需要 {@code Gateway} 才能派发消息
     * （见 {@link OfficialChannelAdapter} 的类注释）。长连接通道不需要 ——
     * 它们自带收发的完整链路。
     */
    public String gatewayBlockReason(ChannelTypeDescriptor descriptor) {
        if (descriptor == null || !descriptor.requiresGateway()) {
            return null;
        }
        ManagedGateway gateway = gatewayRuntime.snapshot();
        if (gateway.built()) {
            return null;
        }
        return descriptor.label() + " 需要网关，但网关当前未装配："
                + (gateway.note() == null ? "原因未知" : gateway.note());
    }
}
