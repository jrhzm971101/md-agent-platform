package com.md.agentplatform.server.controlplane.store;

import com.md.agentplatform.server.controlplane.store.entity.ChannelBindingEntity;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;

import java.util.List;

/**
 * 一个渠道的完整内容 —— 主表行 + 它的绑定行，按 sort_order 升序。
 *
 * <h2>为什么要有这一层，而不让调用方自己查两次</h2>
 * 与 {@code AgentDefinitionBundle} 同一个理由，这里只说差异：
 * <ul>
 *   <li><b>保存是一次提交</b>：渠道行与绑定行必须一起落库。让调用方查两次，
 *       就会出现"渠道存了、绑定没存"或反过来这种半截状态，而页面上看不出来；</li>
 *   <li><b>发布快照是一个单位</b>：{@code md_resource_version.snapshot_json} 里存的就是
 *       本对象（见 {@link ChannelSnapshot}），于是"回滚到 v3"能一次还原到当时的绑定集合，
 *       而不是只还原渠道行的字段、留下当前这批绑定 —— 后者是回滚里最容易被忽略的错误；</li>
 *   <li><b>装配期只认这一个形状</b>：运行时装配（ChannelRuntime）也从这里取数，
 *       于是"页面存什么"与"装配读什么"共用一份结构。</li>
 * </ul>
 *
 * @param channel   主表行，不可为 null
 * @param bindings  绑定行，按 sort_order 升序；没有绑定时为空列表（不是 null）
 */
public record ChannelDefinitionBundle(ChannelEntity channel, List<ChannelBindingEntity> bindings) {

    public ChannelDefinitionBundle {
        // 统一成"空列表"而不是 null：调用方少一处判空，就少一处漏判。
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }
}
