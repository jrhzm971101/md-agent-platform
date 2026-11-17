package com.md.agentplatform.server.trace;

import java.util.Map;
import java.util.Set;

/**
 * 观测页面直接消费的一条事件 —— {@code GET /api/traces/{runId}/events} 数组里的元素。
 *
 * <h2>一、为什么不直接把 {@link AgentTraceEvent} 返回给前端</h2>
 * 两者是"原始记录"与"对外契约"的关系，不是重复定义：
 * <ul>
 *   <li>{@link AgentTraceEvent} 是落库单元：一条事件一行，一行只承载"这件事发生在哪一步、
 *       什么时刻、带了什么数据"，字段与列一一对应；</li>
 *   <li>{@link AgentRunEvent} 是事件流单元：一个阶段拆成"开始 / 结束"两条，
 *       因为页面的调用树是"按事件到达顺序长出节点、再由结束事件收口"的
 *       （见前端 {@code features/traces/eventPresentation.ts} 的 standalone：
 *       只有完成事件而没有起点事件时，那个节点会被直接丢掉）。</li>
 * </ul>
 *
 * <p>转换里还有一样东西是"算出来"而不是"抄过来"的：<b>path / parentPath</b>。
 * 层级由时间区间包含关系推导（见 {@link AgentRunEventTranslator}），因为并行工具与并行子 agent
 * 的订阅可能交错，写入时靠共享栈决定父子一定会串台。这里给的是算完的结果。</p>
 *
 * <p><b>字段名与前端 {@code src/features/traces/model.ts} 的 {@code TraceEvent} 一一对应</b>，
 * 前端不做任何重命名 —— 这是硬约束：改名等于把前端页面的建树逻辑打散。字段类型也要对齐，
 * 例如 {@code durationMs} / {@code tokens} 用包装类型而不是 int/long，
 * 因为前端的类型是 {@code number | null}，null（"这个节点没有这个指标"）与 0（"指标就是 0"）
 * 在页面上是两种显示。
 *
 * <h2>二、词表是闭集</h2>
 * {@link #TYPES} / {@link #KINDS} / {@link #STATUSES} 就是全部合法取值。前端的
 * {@code eventPresentation[type]} 是查表，给一个表外的 type 会让页面在渲染时抛
 * "cannot read properties of undefined"；所以宁可在这里被测试拦住，也不能靠约定。
 *
 * @param id         事件 id，同一 run 内唯一（形如 runId-seq）。前端用它去重，重连补洞时靠它认人
 * @param seq        单调递增序号 —— 断点续传的唯一依据。必须从 1 开始且连续：
 *                   前端的 mergeContiguousEvents 只放行"从 cursor+1 起的连续前缀"，
 *                   中间缺一个号，它就会停在那里等，后面的事件全部积压不渲染
 * @param runId      所属 run
 * @param type       11 种取值之一，见 TYPE_* 常量
 * @param occurredAt 事件发生时刻，ISO-8601 / UTC / 毫秒三位（形如 2026-09-20T01:14:02.000Z），
 *                   与 JS {@code new Date(ms).toISOString()} 逐字节一致，
 *                   这样前端 Date.parse 与展示层都不用做兼容分支
 * @param path       调用树路径：根是 "1"，子节点 "1.2"、"1.2.1"。前端只按它建树
 * @param parentPath 父节点路径；根为 null
 * @param kind       节点类型（画图标用），见 KIND_* 常量
 * @param label      一句话标题，列表直接显示
 * @param source     来源：模型名 / 工具名 / 子会话 id；拿不到时为 null（前端显示 null 而不是"未知"）
 * @param status     状态色（前端叫 Tone），见 STATUS_* 常量
 * @param durationMs 节点耗时；开始事件为 null
 * @param tokens     节点 token 用量；只有模型调用有值，其余为 null
 * @param payload    明细载荷；开始事件为 null（那时还没有结果可看）
 */
public record AgentRunEvent(
        String id,
        int seq,
        String runId,
        String type,
        String occurredAt,
        String path,
        String parentPath,
        String kind,
        String label,
        String source,
        String status,
        Long durationMs,
        Integer tokens,
        Payload payload) {

    // ============================ type：11 种 ============================
    // 与前端 TraceEventType 完全同词表，一一对应，不多不少。

    /** run 开始（根节点的起点，由 Registry 在 run 建立时直接产出）。 */
    public static final String TYPE_RUN_STARTED = "run.started";
    /** run 正常结束。 */
    public static final String TYPE_RUN_COMPLETED = "run.completed";
    /** 一轮推理开始。 */
    public static final String TYPE_REASONING_STARTED = "reasoning.started";
    /** 一轮推理结束。 */
    public static final String TYPE_REASONING_COMPLETED = "reasoning.completed";
    /** 动作阶段开始（工具名都拿不到时的兜底类型）。 */
    public static final String TYPE_ACTING_STARTED = "acting.started";
    public static final String TYPE_ACTING_COMPLETED = "acting.completed";
    /** 工具调用开始 / 结束（能拿到工具名时的正常类型）。 */
    public static final String TYPE_TOOL_STARTED = "tool.started";
    public static final String TYPE_TOOL_COMPLETED = "tool.completed";
    /**
     * 子 Agent 派发 / 结束。
     *
     * <p>对应写入侧的 {@link AgentTraceEvent#PHASE_SUBAGENT}：父 agent 把任务交给子 agent。
     * 收口事件的 {@code status} 可能是 {@link #STATUS_WAIT} —— 派发成功但结果还没到
     * （后台异步执行），见 {@link #BACKGROUND_DISPATCH_STATUSES}。
     */
    public static final String TYPE_SUBAGENT_DISPATCHED = "subagent.dispatched";
    public static final String TYPE_SUBAGENT_COMPLETED = "subagent.completed";
    /** 异常：任何阶段的失败收口事件，同时也是 run 失败时的终止事件。 */
    public static final String TYPE_ERROR_RAISED = "error.raised";

    /** type 闭集，测试用它守住词表。 */
    public static final Set<String> TYPES = Set.of(
            TYPE_RUN_STARTED, TYPE_RUN_COMPLETED,
            TYPE_REASONING_STARTED, TYPE_REASONING_COMPLETED,
            TYPE_ACTING_STARTED, TYPE_ACTING_COMPLETED,
            TYPE_TOOL_STARTED, TYPE_TOOL_COMPLETED,
            TYPE_SUBAGENT_DISPATCHED, TYPE_SUBAGENT_COMPLETED,
            TYPE_ERROR_RAISED);

    // ============================ kind：6 种 ============================

    /** Agent 阶段（run 根节点）。 */
    public static final String KIND_AGENT = "agent";
    /** 模型调用。 */
    public static final String KIND_REASONING = "reasoning";
    /** 动作阶段。 */
    public static final String KIND_ACTING = "acting";
    /** 工具调用。 */
    public static final String KIND_TOOL = "tool";
    /** 子 Agent 调用。 */
    public static final String KIND_SUBAGENT = "subagent";
    /** 异常。 */
    public static final String KIND_ERROR = "error";

    public static final Set<String> KINDS = Set.of(
            KIND_AGENT, KIND_REASONING, KIND_ACTING, KIND_TOOL, KIND_SUBAGENT, KIND_ERROR);

    // ============================ status：4 种（前端叫 Tone） ============================

    /** 进行中。 */
    public static final String STATUS_RUN = "run";
    /** 成功。 */
    public static final String STATUS_OK = "ok";
    /** 失败。 */
    public static final String STATUS_ERR = "err";
    /**
     * 等待中。
     *
     * <p>目前唯一的产出点是<b>后台子 agent 派发的收口事件</b>：调用返回了（"这次派发成功了"），
     * 但子 agent 还在后台跑，结果还没到。前端的 {@code projectTrace} 把它当作"仍在进行中"，
     * 节点显示成 pending —— 这正是不把 {@code accepted} / {@code timeout_promoted} 画成
     * "成功"的关键。
     */
    public static final String STATUS_WAIT = "wait";

    public static final Set<String> STATUSES = Set.of(STATUS_RUN, STATUS_OK, STATUS_ERR, STATUS_WAIT);

    // ============ 子 agent 派发结论（agent_spawn 结果文本里的 status 值） ============
    // 它不是我们发明的词，是框架写死在结果文本里的（AgentSpawnTool 的 formatTimeoutPromoted /
    // BG_RESULT_TEMPLATE）。放在这里是因为同一个词有两处消费方：写入侧（中间件从结果文本里抠出来）
    // 与成树侧（翻译器据此决定节点是 ok 还是 wait）。两处各写一遍字面量，迟早会漂移，
    // 而漂移的表现是"后台派发被画成了同步成功"—— 恰好是本次改造要消灭的那个误解。

    /** 派发已提交后台，子 agent 还在跑（{@code timeout_seconds=0}）。 */
    public static final String DISPATCH_ACCEPTED = "accepted";
    /** 同步等待超时，框架把这次执行转成了后台任务，子 agent 还在跑。 */
    public static final String DISPATCH_TIMEOUT_PROMOTED = "timeout_promoted";

    /** 这些结论意味着"调用返回了，但结果还没到" —— 页面上按等待中（wait）渲染。 */
    public static final Set<String> BACKGROUND_DISPATCH_STATUSES =
            Set.of(DISPATCH_ACCEPTED, DISPATCH_TIMEOUT_PROMOTED);

    /**
     * 结构化明细。
     *
     * <p>四个字段各自的作用在页面上是固定的（见前端 adapters.ts#describeTraceNode）：
     * {@code input} 与 {@code output} 渲染成两个可折叠的 JSON 面板，{@code note} 渲染成说明文字，
     * {@code metrics} 里除 inputTokens / outputTokens 之外的键会平铺成一行行字段。
     */
    public record Payload(
            /** 送进模型 / 工具 / 子 Agent 的入参。 */
            Map<String, Object> input,
            /** 拿回来的结果。 */
            Map<String, Object> output,
            /** 人类可读的补充说明，例如失败原因。 */
            String note,
            /** 明细指标，例如 token 分项。 */
            Map<String, Object> metrics) {
    }
}
