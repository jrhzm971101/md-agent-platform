package com.md.agentplatform.server.channel;

import com.md.agentplatform.server.controlplane.store.entity.ChannelBindingEntity;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把库里的渠道行 + 绑定行翻成官方 {@link ChannelConfig}。
 *
 * <h2>这一层为什么必须存在</h2>
 * 路由求值交给官方 {@code ChannelRouter}，它只认官方 {@link ChannelConfig} /
 * {@link ChannelBinding}。所以"库表 → 官方路由模型"的翻译必须有且只有一处：
 * 分散到装配、路由、页面三处各翻一遍，就会出现"装配时说 A、路由时按 B"这种
 * 极难定位的问题（表现是"绑定明明配了却不生效"）。
 *
 * <h2>未知 dm_scope 的容错</h2>
 * 存进库的值在写入时已校验（{@code MyBatisControlPlaneStore#validateChannel}），
 * 但库里可能有历史/手工数据。这里遇到不认识的值<b>退回官方默认值并打 warn</b>，
 * 而不是抛异常：抛异常会让"整个渠道装不起来"，而那往往只是因为一个不影响主流程的
 * 会话范围字段写得不对 —— 让消息进得来比严格更重要的场景里，可用的降级更合适。
 * （类型/凭据的错就不同了，那些会让通道根本连不上，必须硬失败，见各工厂。）
 */
public final class ChannelConfigAssembler {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfigAssembler.class);

    private ChannelConfigAssembler() {}

    /** 组装路由配置；绑定按 sort_order 升序（同层内先命中谁的依据）。 */
    public static ChannelConfig assemble(ChannelEntity channel, List<ChannelBindingEntity> bindings) {
        List<ChannelBinding> resolved = new ArrayList<>();
        if (bindings != null) {
            List<ChannelBindingEntity> sorted = new ArrayList<>(bindings);
            // 防御性排序：调用方多半已经排好（store 的读路径按 sort_order 返回），
            // 但装配期不能依赖"调用方记得排序"——顺序错了的表现是"规则命中错了"，
            // 这种 bug 从日志上完全看不出来。
            sorted.sort(Comparator.comparing(ChannelBindingEntity::getSortOrder,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (ChannelBindingEntity row : sorted) {
                if (row == null || row.getAgentId() == null || row.getAgentId().isBlank()) {
                    continue;
                }
                resolved.add(new ChannelBinding(
                        row.getAgentId(),
                        blankToNull(row.getPeer()),
                        blankToNull(row.getParentPeer()),
                        blankToNull(row.getGuild()),
                        row.getRoles() == null ? Set.of() : Set.copyOf(row.getRoles()),
                        blankToNull(row.getTeam()),
                        blankToNull(row.getAccount()),
                        blankToNull(row.getChannel()),
                        parseScope(row.getSessionScope(), "绑定的 session-scope")));
            }
        }
        return ChannelConfig.builder(channel.getChannelId())
                .defaultAgentId(blankToNull(channel.getDefaultAgentId()))
                .dmScope(parseScope(channel.getDmScope(), "渠道的 dm-scope"))
                .bindings(resolved)
                .build();
    }

    /**
     * 解析官方 {@link DmScope}；空值/未知值都退回官方默认值。
     *
     * <p>空值退回默认值是官方自己的语义（{@code ChannelConfig} 的构造器就是这么做的），
     * 所以这里传 null 也不会出错；显式写出来只是为了让"不设置 = 用官方默认"这条约定
     * 在代码里可见，而不是藏在框架的某一行里。
     */
    private static DmScope parseScope(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DmScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("{} 取值无法识别: {}（退回官方默认 {}）", what, raw, DmScope.defaultScope());
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
