package com.md.agentplatform.server.trace;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 执行列表里的一行 —— {@code GET /api/traces} 数组里的元素。
 *
 * <p>字段与前端 {@code src/api/types.ts} 的 {@code RunSummary} 一一对应。
 *
 * <p>为什么不直接把 {@link AgentTraceRun} 丢给前端：一是字段名对不上（前端叫 agentId，
 * 后端叫 agentName），二是状态词表不同（后端 DONE / ERROR / RUNNING，
 * 前端 success / failed / running），三是列表页根本不需要 steps 这种重载荷 ——
 * 20 条 run 各带上百个阶段，响应会白白放大几十倍。
 *
 * <p><b>翻译只在这里发生一次</b>：页面不写 if/else 去猜后端状态。
 */
public record TraceRunSummary(
        String runId,
        String agentId,
        String sessionId,
        /** 触发来源（console | wecom | feishu | schedule）；null = 入口方没标注。 */
        String source,
        /** 前端闭集：running | success | failed | pending。 */
        String status,
        /** 状态色：ok | run | err | wait。 */
        String tone,
        long durationMs,
        int tokens,
        /** ISO-8601 / UTC / 毫秒三位，与事件里的 occurredAt 同一格式。 */
        String startedAt) {

    private static final DateTimeFormatter ISO_UTC_MS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /** 后端 run → 列表行。耗时对"进行中"的 run 是"至今耗时"，不是最终耗时。 */
    public static TraceRunSummary from(AgentTraceRun run) {
        return new TraceRunSummary(
                run.getRunId(),
                run.getAgentName(),
                run.getSessionId(),
                run.getSource(),
                AgentRunStatus.wire(run.getStatus()),
                AgentRunStatus.tone(run.getStatus()),
                run.getCostMs(),
                run.getInputTokens() + run.getOutputTokens(),
                ISO_UTC_MS.format(Instant.ofEpochMilli(run.getStartedAtMs())));
    }
}
