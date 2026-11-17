package com.md.agentplatform.server.overview;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 概览页的聚合行模型 —— 字段跟着 {@link OverviewMapper} 的 SQL 走。
 *
 * <h2>为什么这些行模型与输出模型分开</h2>
 * 这一层是"SQL 的产物"，输出模型是"页面的形状"：
 * <ul>
 *   <li>{@link RunStatsRow} 里的 {@code done / failed / running} 是三个计数，页面要的是
 *       {@code {total, success, failed, running}} 这一组（连字段名都不一样：页面说 success，
 *       SQL 说 DONE）；</li>
 *   <li>{@link RunDurationRow} 每行是"一次执行的耗时"，而页面要的是"平均/P95/最慢的那一个" ——
 *       那是聚合，不是映射。</li>
 * </ul>
 * 混在一起，就得在行模型上挂一批计算字段，让"从库里读出来的东西"和"算出来的东西"混为一谈。
 */
public final class OverviewRows {

    private OverviewRows() {
    }

    /** 时间窗内的执行统计（{@code md_agent_run} 的聚合）。 */
    @Getter
    @Setter
    public static class RunStatsRow {
        private long runs;
        /** 后端状态 DONE 的次数（页面上叫"成功"）。 */
        private long done;
        private long failed;
        private long running;
        /** 输入 + 输出 token 合计。 */
        private long tokens;
        /** 输入 token 合计 —— 缓存命中率的分母。 */
        private long inputTokens;
        private long cachedTokens;
    }

    /** 一次执行的 agent 与耗时。 */
    @Getter
    @Setter
    public static class RunDurationRow {
        private String agentName;
        private long durationMs;
    }

    /** 一个 agent 在时间窗内的失败次数。 */
    @Getter
    @Setter
    public static class RunFailureRow {
        private String agentName;
        private long failed;
    }

    /** 便捷：把 agent 名列表收成集合，避免每处都写 {@code new HashSet<>(...)}。 */
    public static java.util.Set<String> toSet(List<String> values) {
        return values == null ? java.util.Set.of() : new java.util.HashSet<>(values);
    }
}