package com.md.agentplatform.server.trace;

import java.util.List;

/**
 * 事件分页结果 —— {@code GET /api/traces/{runId}/events} 的返回。
 *
 * <p>字段与前端 {@code src/features/traces/model.ts} 的 {@code TraceEventPage} 一致。
 *
 * @param events     按 seq 升序的事件数组
 * @param hasEarlier 本次结果之前是否还有更早的事件（前端用它决定要不要显示"加载更早"）。
 *                   前端目前是自己按 {@code events.length >= limit} 推的，这个字段是给
 *                   以后"按游标精确分页"用的 —— 一旦使用了 before 游标，长度推断就不准了
 */
public record TraceEventPage(List<AgentRunEvent> events, boolean hasEarlier) {
}
