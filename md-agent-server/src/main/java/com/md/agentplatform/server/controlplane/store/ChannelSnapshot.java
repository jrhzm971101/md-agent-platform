package com.md.agentplatform.server.controlplane.store;

import com.md.agentplatform.server.controlplane.store.entity.ChannelBindingEntity;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;

import java.util.List;

/**
 * 渠道的版本快照载荷 —— 落进 {@code md_resource_version.snapshot_json}。
 *
 * <h2>为什么不直接序列化 {@link ChannelDefinitionBundle}</h2>
 * 两者今天字段完全一样，看起来是重复的。分开是因为它们的<b>稳定性要求不同</b>：
 * <ul>
 *   <li>{@code ChannelDefinitionBundle} 是<b>接口形状</b>，可以随页面需要增减字段
 *       （比如给列表页加一个 bindingCount）；</li>
 *   <li>本 record 是<b>持久化格式</b>，一旦落库就要能被将来任意版本的代码读回来。
 *       回滚读的是几个月前写的 JSON，这时候"顺手给接口对象加了个字段"不该影响它。</li>
 * </ul>
 * 类似把 Jackson 配置钉死在 {@code MyBatisControlPlaneStore} 里的理由：
 * 格式的消费者是历史数据，不是当前调用方。
 */
public record ChannelSnapshot(ChannelEntity channel, List<ChannelBindingEntity> bindings) {

    public static ChannelSnapshot of(ChannelDefinitionBundle bundle) {
        return new ChannelSnapshot(bundle.channel(), bundle.bindings());
    }

    public ChannelDefinitionBundle toBundle() {
        return new ChannelDefinitionBundle(channel, bindings);
    }
}
