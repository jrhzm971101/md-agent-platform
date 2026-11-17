package com.md.agentplatform.server.trace;

/**
 * run 状态的两层词表与翻译 —— 后端词表（{@code RUNNING / DONE / ERROR}）→
 * 前端闭集（{@code running / success / failed}）与状态色（{@code ok / run / err}）。
 *
 * <h2>为什么单独一个类</h2>
 * 做这层翻译的现在有两处：执行链路列表（{@link TraceRunSummary}）与会话详情的执行时间线
 * （{@code server.session} 包）。各写一份 {@code switch} 的后果是"同一次失败在两个页面上
 * 显示成两种颜色"，而且第一处改了第二处不会跟着改 —— 这种漂移没有编译期信号，
 * 只能靠"同一个词表只有一个出口"来避免。
 *
 * <p>两个方法都是纯函数、无状态，所以是静态工具类而不是 Bean：没有依赖可注入，
 * 包一层 Spring 只是让调用方多一次查找。
 */
public final class AgentRunStatus {

    private AgentRunStatus() {}

    /**
     * 后端状态 → 前端闭集。
     *
     * <p>{@code default -> running} 而不是抛错：状态列是 VARCHAR，
     * 将来若新增一个中间态，页面显示成"进行中"比整页 500 好得多。
     */
    public static String wire(String status) {
        return switch (status) {
            case AgentTraceRun.STATUS_DONE -> "success";
            case AgentTraceRun.STATUS_ERROR -> "failed";
            default -> "running";
        };
    }

    /** 后端状态 → 状态色，取值即 {@link AgentRunEvent} 上的 STATUS_* 常量。 */
    public static String tone(String status) {
        return switch (status) {
            case AgentTraceRun.STATUS_DONE -> AgentRunEvent.STATUS_OK;
            case AgentTraceRun.STATUS_ERROR -> AgentRunEvent.STATUS_ERR;
            default -> AgentRunEvent.STATUS_RUN;
        };
    }
}
