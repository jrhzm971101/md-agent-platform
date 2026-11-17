package com.md.agentplatform.server.trace;

import java.util.Map;

/**
 * 调用链路里的一个<b>事件</b> —— 观测数据的原子单位，落库后就是一行。
 *
 * <h2>一、为什么是"事件"而不是"阶段"</h2>
 * 早期模型是"一个阶段一条记录"（{@code AgentTraceStep}：开始时刻 + 耗时 + 进出参，写完即完整）。
 * 它在纯内存场景够用，但一落到"落库 + SSE 增量推送"就不成立了，因为：
 * <b>阶段是可变的</b>（进行中没有耗时与出参），而前端的事件流要求 <b>seq 一旦发出就不能再变</b>
 * —— 前端拿 seq 做断点续传游标（mergeContiguousEvents 只放行从 cursor+1 起的连续前缀）。
 * 一个阶段在"进行中"占 1 个事件号、完成后再占 1 个，会让<b>已经发出去的编号被顶掉</b>：
 * <pre>
 * 并行工具，tool1 还在跑、tool2 已完成：
 *   1 run.started   2 tool1.started   3 tool2.started   4 tool2.completed
 * tool1 随后也完成，同一批数据重新翻译：
 *   1 run.started   2 tool1.started   3 tool1.completed   ← 3 号换了内容，客户端已按旧内容渲染
 *   4 tool2.started 5 tool2.completed
 * </pre>
 *
 * <p>所以改成：<b>阶段开始写一条 STARTED 事件，阶段结束写一条 COMPLETED 事件</b>。两条各自在
 * 产生的那一刻拿到 seq，此后永不改动；查询只做"回放"，不做任何重新编号。代价是行数翻倍、
 * 读时要把两条配对成一个节点；换来的是 seq 稳定、SSE 天然可用，而且进程崩溃时只会留下
 * "有 STARTED 没 COMPLETED"的半截阶段，不会出现永远停在 RUNNING 的僵尸行。
 *
 * <h2>二、seq 必须连续 —— 不输出的东西不许占号</h2>
 * seq 与输出事件一一对应。凡是不在页面上输出的数据，<b>绝不能占 seq</b>，
 * 否则会留下永远补不上的空洞，前端会卡在空洞之前不再渲染。因此：
 * <ul>
 *   <li>{@code MODEL_CALL} 不产生事件：它在页面上被折叠进所属的 REASONING（同一次推理请求），
 *       模型名与 token 用量改由 REASONING 的 COMPLETED 事件携带。</li>
 *   <li>{@code SYSTEM_PROMPT} 不产生事件：系统提示词是 run 级属性（整轮只生效一个），
 *       直接挂在 {@link AgentTraceRun} 上，不占调用树的位置。</li>
 * </ul>
 *
 * @param seq          事件序号：同一 run 内从 1 开始、连续、产生即冻结
 * @param stageSeq     所属阶段标识（= 该阶段 STARTED 事件的 seq）；STARTED 事件就等于自己的 seq
 * @param eventType    {@link #EVENT_STARTED} / {@link #EVENT_COMPLETED}
 * @param phase        阶段名，取值见本类 PHASE_* 常量（除 PHASE_MODEL_CALL 外都会产事件）
 * @param title        一句话摘要，列表直接展示
 * @param status       STARTED 恒为 {@link #STATUS_RUNNING}；COMPLETED 为 {@link #STATUS_OK} 或 {@link #STATUS_ERROR}
 * @param stageStartMs 阶段开始时刻（epoch millis）。<b>两种事件都带它</b>：读时要靠"时间区间包含"
 *                     推算树的父子关系，而配对后的结束时刻 = stageStartMs + costMs，缺了它就算不出来
 * @param costMs       阶段耗时；STARTED 恒为 0
 * @param input        结构化入参（阶段"调用前就已知"的东西）：STARTED 事件携带
 * @param output       结构化出参（阶段"调用后才产生"的东西）：COMPLETED 事件携带
 * @param error        失败时的异常摘要，成功为 null
 */
public record AgentTraceEvent(
        int seq,
        int stageSeq,
        String eventType,
        String phase,
        String title,
        String status,
        long stageStartMs,
        long costMs,
        Map<String, Object> input,
        Map<String, Object> output,
        String error) {

    // -------------------- eventType 词表 --------------------

    /** 阶段开始。 */
    public static final String EVENT_STARTED = "STARTED";
    /** 阶段收口（成功或失败都算收口，失败与否看 {@link #status}）。 */
    public static final String EVENT_COMPLETED = "COMPLETED";

    // -------------------- phase 词表：全项目唯一一份 --------------------
    // 为什么收敛到 record 自己身上：同一批字面量有三个消费方 ——
    // 写入方（AgentRunTraceMiddleware）、成树方（AgentRunEventTranslator）、断言方（测试）。
    // 三份迟早漂移，而漂移的表现是"页面上某个阶段整段消失"（成树方认不出来就跳过），排查成本很高。

    /** 整轮 run 的根节点，只由最外层 agent 产生，对应 run 级事件。 */
    public static final String PHASE_AGENT = "AGENT";
    /** 一轮 ReAct 迭代里"想"的那一步（洋葱型，包住 MODEL_CALL / ACTING）。 */
    public static final String PHASE_REASONING = "REASONING";
    /** 单次 LLM 请求。<b>不产生事件</b>（被折叠进 REASONING），仅作为阶段名参与数据归属。 */
    public static final String PHASE_MODEL_CALL = "MODEL_CALL";
    /** 工具执行，一次工具调用一对事件。 */
    public static final String PHASE_ACTING = "ACTING";
    /**
     * 子 agent 派发 —— 父 agent 把任务交给子 agent 执行，一次派发一对事件。
     *
     * <p>为什么单独成一个 phase、而不是继续借用 ACTING：派发在框架里确实就是一次普通工具调用
     * （工具名 {@code agent_spawn}），但页面上它和普通工具是两类东西 —— 它下面会长出子 agent
     * 自己的推理 / 工具节点，而且它是"并发扇出"的载体（一次派发 N 个）。沿用 ACTING 只会让前端
     * 把它画成一个普通工具节点，子 agent 干的所有活都挤在同一层里。
     *
     * <p>并发派发的节点在成树时保持平级：一批并行派发的开始时刻取的是同一个值，而
     * {@link AgentRunEventTranslator} 判父子时要求"外层开始得更早"，所以它们不会互相收编
     * （详见该类 contains 的注释）。子 agent 的内部节点会挂到"区间包住它的那个派发节点"下，
     * 但并发派发的区间互相重叠，<b>具体挂到哪一个是不精确的</b> —— 框架不提供 span id，
     * 这是已知且已接受的取舍。
     */
    public static final String PHASE_SUBAGENT = "SUBAGENT";

    // -------------------- status 词表 --------------------

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";

    /**
     * 造一条阶段开始事件。
     *
     * <p>stageSeq 取自己的 seq：STARTED 事件是它所属阶段的"锚点"，后续 COMPLETED 事件靠它归位。
     */
    public static AgentTraceEvent started(int seq, String phase, String title, long stageStartMs,
                                          Map<String, Object> input) {
        return new AgentTraceEvent(seq, seq, EVENT_STARTED, phase, title, STATUS_RUNNING,
                stageStartMs, 0L, input, null, null);
    }

    /**
     * 造一条阶段收口事件。
     *
     * <p>失败不走单独的 eventType，而是 {@code COMPLETED + STATUS_ERROR}：事件类型描述"发生了什么事"，
     * 成败是它的属性。成树方据此决定发 {@code *.completed} 还是 {@code error.raised}。
     */
    public static AgentTraceEvent completed(int seq, int stageSeq, String phase, String title,
                                            long stageStartMs, long costMs,
                                            Map<String, Object> output, String error) {
        return new AgentTraceEvent(seq, stageSeq, EVENT_COMPLETED, phase, title,
                error == null ? STATUS_OK : STATUS_ERROR,
                stageStartMs, costMs, null, output, error);
    }

    /** 阶段结束时刻。STARTED 事件上它等于开始时刻（尚未结束）。 */
    public long stageEndMs() {
        return stageStartMs + costMs;
    }

    /** 是否是收口事件（COMPLETED）。 */
    public boolean isTerminal() {
        return EVENT_COMPLETED.equals(eventType);
    }
}
