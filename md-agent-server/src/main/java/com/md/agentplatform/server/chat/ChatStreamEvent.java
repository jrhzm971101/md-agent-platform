package com.md.agentplatform.server.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话流的一帧 —— 页面聊天用的传输单元。
 *
 * <p><b>为什么不复用 AgentRunEvent</b>：那是「观测」语义（一次 run 的阶段边界，
 * 只增不改、带 seq 游标、可以事后回放、落库归档），这里是「传输」语义
 * （一次请求的即时输出，只在订阅期间有效，断了就没了）。
 * 两者的消费方、生命周期、字段集合完全不重叠：链路事件要靠 stage_seq / path 画调用树，
 * 对话帧要的是「这一段文本」。硬合并的结果是两边都长出一堆自己不需要的字段。
 *
 * <p>词表刻意取小，每一种都对应前端一个明确动作：
 * <ul>
 *   <li>{@code run}      —— 首帧，回执本次生效的 agent / session / 开始时刻</li>
 *   <li>{@code delta}    —— 答案正文增量，前端按到达顺序拼接（不重排、不去重）</li>
 *   <li>{@code thinking} —— 思考增量，与答案分开走，页面上折叠或直接忽略</li>
 *   <li>{@code tool}     —— 工具调用的开始 / 结束，用来显示「正在做什么」</li>
 *   <li>{@code done}     —— 收口，带最终状态与耗时；正常与异常都会来这一帧</li>
 *   <li>{@code error}    —— 失败摘要，紧跟着仍然是 {@code done}</li>
 * </ul>
 *
 * <p><b>为什么 data 是 Map 而不是给每种 type 配一个 record</b>：帧是「发给前端的 JSON 片段」，
 * 字段只会小步演进（比如以后 delta 要带引用编号、tool 要带耗时），
 * 用 Map 时改一处工厂方法就行，前端不必同步改类型定义。
 * 用 LinkedHashMap 而不是 Map.of：保序，curl 直读时字段顺序稳定。
 */
public record ChatStreamEvent(String type, Map<String, Object> data) {

    /** 首帧：本次调用的回执。 */
    public static final String TYPE_RUN = "run";
    /** 答案正文增量。 */
    public static final String TYPE_DELTA = "delta";
    /** 思考增量。 */
    public static final String TYPE_THINKING = "thinking";
    /** 工具调用状态。 */
    public static final String TYPE_TOOL = "tool";
    /** 收口。 */
    public static final String TYPE_DONE = "done";
    /** 失败。 */
    public static final String TYPE_ERROR = "error";

    /** 工具阶段：开始。 */
    public static final String TOOL_PHASE_START = "start";
    /** 工具阶段：结束。 */
    public static final String TOOL_PHASE_END = "end";

    /** 收口状态：正常结束。 */
    public static final String STATUS_DONE = "DONE";
    /** 收口状态：异常结束。 */
    public static final String STATUS_ERROR = "ERROR";

    /**
     * 首帧。
     *
     * <p>sessionId 放进来是因为它对前端是<b>输入</b>：请求里没带时由后端现生成一个，
     * 前端必须拿到这个值才能让第 2 轮对话落进同一个会话（harness 的记忆按
     * (userId, sessionId) 分区）。不回传的话，多轮对话会变成每次都是新会话。
     *
     * <p>userId 放进来是<b>回执</b>：它和 sessionId 一起决定"这条对话用哪一份记忆"
     * （状态存储里两者拼成同一个键）。请求没带时前端看不到后端用了什么默认值，
     * 回执一发，页头上显示的就是实际生效的那个值。
     *
     * <p>runId 放进来是为了让页面能<b>从这条回答跳到对应的执行链路</b>：它就是
     * {@code GET /api/traces/{runId}} 里的那个 id，由本层生成、中间件照用，
     * 所以两边天然是同一个值。回执给错（或不给）的后果是链条断在最后一跳。
     */
    public static ChatStreamEvent run(String agentId, String sessionId, String userId, String runId,
                                      long startedAtMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentId", agentId);
        data.put("sessionId", sessionId);
        data.put("userId", userId);
        data.put("runId", runId);
        data.put("startedAtMs", startedAtMs);
        return new ChatStreamEvent(TYPE_RUN, data);
    }

    /** 答案增量。 */
    public static ChatStreamEvent delta(String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        return new ChatStreamEvent(TYPE_DELTA, data);
    }

    /** 思考增量。 */
    public static ChatStreamEvent thinking(String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        return new ChatStreamEvent(TYPE_THINKING, data);
    }

    /**
     * 工具调用开始 / 结束。
     *
     * <p>带上 callId：同一轮里并行调多个工具时，结束事件不保证与开始事件相邻，
     * 页面要靠 id 才能把「结束了哪一个」对上号。
     */
    public static ChatStreamEvent tool(String callId, String name, String phase) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", callId);
        data.put("name", name);
        data.put("phase", phase);
        return new ChatStreamEvent(TYPE_TOOL, data);
    }

    /** 收口。 */
    public static ChatStreamEvent done(String status, long elapsedMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("elapsedMs", elapsedMs);
        return new ChatStreamEvent(TYPE_DONE, data);
    }

    /** 失败摘要。 */
    public static ChatStreamEvent error(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        return new ChatStreamEvent(TYPE_ERROR, data);
    }
}
