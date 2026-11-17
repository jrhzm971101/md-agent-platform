package com.md.agentplatform.server.trace;

/**
 * 单次 run 的元信息 —— {@code GET /api/traces/{runId}} 的返回，页面顶部那块概览用它。
 *
 * <p>字段与前端 {@code src/features/traces/model.ts} 的 {@code TraceRunInfo} 一一对应。
 *
 * <p>为什么单独给一个端点，而不是让页面从事件里凑：这些字段里有一半事件里根本没有
 * （谁跑的、什么会话、什么用户、缓存命中率），硬凑就得让前端去猜事件含义。
 * 后端本来就有这些数据，直接给。
 *
 * @param agentKind     'react' | 'harness'，见 {@link AgentTraceRun#AGENT_KIND_REACT}
 * @param source        触发来源（console / wecom / feishu / schedule），取自
 *                      {@code md_agent_run.source}；入口方没标注时为 null（页面显示为空，不编值）。
 *                      刻意不按 sessionId 前缀去猜：各入口的 sessionId 格式互不相干，猜错比留空更误导
 * @param cacheHitRate  缓存命中率，形如 "61%"；分母是输入 token
 * @param workflowRunId 所属工作流运行 id；null = 不是工作流发起的。页面上有值时
 *                      显示一个"回到工作流运行"的入口 —— 这是从"一条链路"反查"它在哪次编排里"的唯一通道
 * @param workflowNodeKey 所属工作流节点 key；null = 不是工作流发起的
 * @param parentRunId   派发出这条 run 的父 run；null = 用户/通道/工作流直接发起（只在异步子 agent
 *                      于父 run 收口之后才开跑时才有值，详见 {@link AgentTraceRun#getParentRunId()}）。
 *                      页面用它给出一个"回到父链路"的入口，不必让用户自己去猜这条孤立链路是哪来的
 * @param taskId        框架后台任务号（{@code task_<uuid>}）；null = 不是后台任务跑起来的。
 *                      与父侧派发节点事件载荷里的 task_id 是同一个号，两边可以互相对上
 */
public record TraceRunInfo(
        String runId,
        String agentId,
        String agentKind,
        String sessionId,
        String user,
        String source,
        long durationMs,
        int tokens,
        String cacheHitRate,
        String workflowRunId,
        String workflowNodeKey,
        String parentRunId,
        String taskId) {

    public static TraceRunInfo from(AgentTraceRun run) {
        return new TraceRunInfo(
                run.getRunId(),
                run.getAgentName(),
                run.getAgentKind(),
                run.getSessionId(),
                run.getUserId(),
                run.getSource(),
                run.getCostMs(),
                run.getInputTokens() + run.getOutputTokens(),
                cacheHitRateOf(run),
                run.getWorkflowRunId(),
                run.getWorkflowNodeKey(),
                run.getParentRunId(),
                run.getTaskId());
    }

    /**
     * 命中率 = cached / input。
     *
     * <p>用输入 token 做分母：各家的 cached_tokens 都是"输入侧被命中缓存的那部分"。
     * 输入为 0 时返回 0% 而不是除零。
     */
    private static String cacheHitRateOf(AgentTraceRun run) {
        if (run.getInputTokens() <= 0) {
            return "0%";
        }
        long rate = Math.round(run.getCachedTokens() * 100.0 / run.getInputTokens());
        return rate + "%";
    }
}
