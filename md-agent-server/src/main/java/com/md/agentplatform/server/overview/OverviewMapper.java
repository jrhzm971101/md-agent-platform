package com.md.agentplatform.server.overview;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 概览页的只读聚合查询 —— 全部打在 {@code md_agent_run} 这一张表上。
 *
 * <h2>一、为什么概览页不复用 {@code AgentTraceRegistry} 的内存热数据</h2>
 * 注册表只保留最近 {@code trace-max-runs} 条 run（默认 200），它是"执行链路页"的热缓存，
 * 不是统计口径。用它算"今日执行次数""今日 token"会得到"最近 200 条里今天的那些" ——
 * 数字看起来合理，但和 run 表里的真实总量对不上，而且对不上的幅度随流量变化。
 * 统计必须打在表上。
 *
 * <h2>二、为什么 p95 不在 SQL 里算</h2>
 * MySQL 8 没有百分位聚合函数，用窗口函数（{@code PERCENT_RANK} / {@code NTILE}）能算，
 * 但要写成一条带子查询的复杂语句，且"到底取哪个分位"这件事会藏进 SQL 里。
 * 今日的 run 是几百条的量级，取回耗时列表在服务层算分位既便宜又看得懂 ——
 * 代价是这一条查询会返回与今日 run 数等量的行，那个量级完全没有压力。
 *
 * <h2>三、时间窗参数用 epoch millis</h2>
 * 与 run 表的时间列同口径，边界由服务层算（那里才知道"今天"是从服务器时区的几点开始），
 * SQL 里不做任何时区换算。
 */
@Mapper
public interface OverviewMapper {

    /** 时间窗内的执行统计；窗口内没有数据时各项为 0（COALESCE 兜住 SUM 的 NULL）。 */
    @Select("SELECT COUNT(*) AS runs,"
            + " SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS done,"
            + " SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END) AS failed,"
            + " SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END) AS running,"
            + " COALESCE(SUM(input_tokens + output_tokens), 0) AS tokens,"
            + " COALESCE(SUM(input_tokens), 0) AS inputTokens,"
            + " COALESCE(SUM(cached_tokens), 0) AS cachedTokens"
            + " FROM md_agent_run WHERE started_at_ms >= #{fromMs} AND started_at_ms < #{toMs}")
    OverviewRows.RunStatsRow statsBetween(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    /**
     * 时间窗内每次执行的 agent 与耗时 —— 服务层据此算平均耗时、P95 与"最慢的是谁"。
     *
     * <p>{@code COALESCE(ended_at_ms, #{nowMs})}：进行中的 run 耗时算到"现在"，
     * 与链路列表的口径一致（{@code ended_at_ms} 为 NULL 时若当成 0，平均耗时会被拉低，
     * 而事实恰恰相反）。
     */
    @Select("SELECT agent_name AS agentName,"
            + " (COALESCE(ended_at_ms, #{nowMs}) - started_at_ms) AS durationMs"
            + " FROM md_agent_run WHERE started_at_ms >= #{fromMs}")
    List<OverviewRows.RunDurationRow> durationsSince(@Param("fromMs") long fromMs, @Param("nowMs") long nowMs);

    /**
     * 时间窗内有失败执行的 agent 及失败次数，失败最多的在前。
     *
     * <p>{@code LIMIT} 由调用方给：概览页的「需要关注」只放前几条，
     * 让 SQL 决定取舍比取回全量再截断更省事（口径一致地"按失败次数取前 N"）。
     */
    @Select("SELECT agent_name AS agentName, COUNT(*) AS failed"
            + " FROM md_agent_run"
            + " WHERE started_at_ms >= #{fromMs} AND status = 'ERROR' AND agent_name IS NOT NULL"
            + " GROUP BY agent_name ORDER BY failed DESC, agentName ASC LIMIT #{limit}")
    List<OverviewRows.RunFailureRow> failuresSince(@Param("fromMs") long fromMs, @Param("limit") int limit);

    /** 时间窗内跑过的 agent 名（用于反查"哪些 agent 一直没跑"）。 */
    @Select("SELECT DISTINCT agent_name FROM md_agent_run"
            + " WHERE started_at_ms >= #{fromMs} AND agent_name IS NOT NULL")
    List<String> activeAgentsSince(@Param("fromMs") long fromMs);
}
