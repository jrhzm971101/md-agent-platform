package com.md.agentplatform.server.trace;

import com.md.agentplatform.server.config.AgentPlatformProperties;
import com.md.agentplatform.server.trace.store.TraceStore;
import com.md.agentplatform.server.trace.stream.TraceStreamNotifier;
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 调用链路注册表 —— 进程内保存最近 N 次 run 的完整事件明细，供观测页面查询。
 *
 * <h2>内存与归档的分工（Step 2 起）</h2>
 * 本类同时是<b>热数据 + 读缓存</b>与<b>归档的写入入口</b>：
 * <ul>
 *   <li>写：事件在产生的那一刻先进内存（页面能立刻看到"进行中"的节点），再交给
 *       {@link TraceStore} 异步落库（进程重启后仍然可查）；</li>
 *   <li>读：内存优先，不足或未命中时回库补齐 —— 所以内存上限
 *       （{@code agent-platform.trace-max-runs}）只影响"不用查库能读到多少"，
 *       不再决定"总共能查到多少"。</li>
 * </ul>
 * 存储缺席（未配置归档）时本类退化为纯内存实现，行为与 Step 1 完全一致 ——
 * 中间件只依赖本类的写入方法，从来没感知过存储的存在。
 *
 * <p>写入接口只有五个：{@link #start}（run 开始）/ {@link #beginStage}（阶段开始）/
 * {@link #endStage}（阶段收口）/ {@link #systemPrompt}（run 级属性）/
 * {@link #finish}（run 收口）。中间件只依赖这五个方法，所以"内存热数据 + MySQL 归档"
 * 这套改动在中间件里一行都不需要动。
 *
  * <p><b>seq 在这里分配</b>：事件序号由本类在产生时刻分配并写死，此后任何查询都不再重新编号。
  * 这是前端做断点续传的前提，详见 {@link AgentTraceEvent} 的类注释。
  *
 * <p><b>顺带也是 SSE 的发布点</b>：每条事件写进内存之后，本类会通过 {@link TraceStreamNotifier}
 * 叫醒正在看这个 run 的订阅者（见 {@link #record}）。唤醒信号里<b>不带数据</b>，
 * 被叫醒的一方永远按自己的游标重读本类 —— 所以漏一次、重一次都不影响正确性，
 * 这条通道才不需要任何可靠性保证。
 *
  * <p><b>有界</b>：内存里只保留最近 {@code agent-platform.trace-max-runs} 次 run，超出按最旧淘汰，
 * 避免长跑进程把内存吃满。落库之后这个上限只约束内存，不再约束"能查到多少历史"。
 */
@Component
public class AgentTraceRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceRegistry.class);

    /** run 的 STARTED 事件永远是本 run 的第 1 条事件，根节点收口时用它归位。 */
    private static final int ROOT_STAGE_SEQ = 1;

    @Autowired
    private AgentPlatformProperties platformProperties;

    /**
     * 归档存储（MySQL）。<b>可以没有</b>：{@code agent-platform.trace-archive-enabled=false}
     * 时容器里就没有这个 Bean，本类退化成纯内存实现（Step 1 的行为）。
     *
     * <p>用 {@code required = false} 而不是给一个"空实现"默认值：是否归档是一个部署决策，
     * 用"有没有 Bean"来表达它，比用"一个 field 是不是 NoOpStore"少一层间接。
     *
     * <p>写路径全部经由它异步落库；读路径在内存未命中时用它补齐 —— 详见
     * {@link TraceStore} 的类注释（读同步、写异步）。
     */
    @Autowired(required = false)
    private TraceStore traceStore;

    /**
     * SSE 订阅者的唤醒器。<b>同样可以没有</b>（测试里直接 {@code new AgentTraceRegistry()} 时它就是 null）：
     * 没有它只是"没人被叫醒"，兜底轮询照样会把事件送到页面上（见 {@code TraceEventStream}）。
     *
     * <p>刻意不让本类依赖"有没有人在看"：写入方只负责广播一次信号，
     * 谁在听、听不听得见，都不是它该关心的事。
     */
    @Autowired(required = false)
    private TraceStreamNotifier streamNotifier;

    private final Map<String, AgentTraceRun> runs = new ConcurrentHashMap<>();
    /** 只存 runId，用来维持插入顺序以便淘汰最旧的。 */
    private final Deque<String> order = new ConcurrentLinkedDeque<>();

    /**
     * RuntimeContext 属性键：调用方在发起调用前把「希望这次 run 用的 id」放进上下文，
     * 建 run 时优先采用。用法见 {@link #attachRunId}。
     *
     * <p>为什么用属性袋而不是给 {@code AgentManager.stream} 再加一个参数：
     * runId 只有"观测"这一件事需要，业务链路的每一步（agent → 子 agent → 工具）
     * 都不该知道它的存在。属性袋天生是"带着走、不参与业务语义"的通道，
     * 加参数则会把观测需求写进业务签名，每个调用点都得跟着改。
     */
    public static final String RUN_ID_ATTR = "md.trace.runId";

    /**
     * RuntimeContext 属性键：调用方在发起调用前写下「这次 run 的来源」。用法见 {@link #attachSource}。
     *
     * <p>与 {@link #RUN_ID_ATTR} 同一个通道、同一个理由：来源只有"观测/排查"这件事需要，
     * 业务链路的每一步（agent → 子 agent → 工具）都不该知道它存在。
     *
     * <p>取值见 {@link AgentTraceRun#SOURCE_CONSOLE} 等常量；没写就是 null（页面显示为空）。
     */
    public static final String SOURCE_ATTR = "md.trace.source";

    /**
     * 把「这次调用请用这个 runId」放进上下文。
     *
     * <p><b>谁需要它</b>：凡是"需要事先知道 runId"的调用方 —— 页面对话要把 id 回执给前端，
     * 企微 / 飞书 / 定时任务要把 id 写进自己的日志。这些调用方拿不到中间件里那个随机 id
     * （那是订阅时才产生的），所以只能反过来由调用方先定一个、中间件照用。
     *
     * <p><b>它只是"建议"不是"命令"</b>：中间件采纳与否由 {@link #start} 决定 ——
     * 值不合法或已被占用时会改用现生成的 id 并告警。字符串本身在这里不做校验，
     * 免得同一套规则出现两个出口。
     */
    public static void attachRunId(RuntimeContext ctx, String runId) {
        if (ctx != null && runId != null && !runId.isBlank()) {
            ctx.put(RUN_ID_ATTR, runId);
        }
    }

    /**
     * 读出上下文里建议的 runId；没有则返回 null。
     *
     * <p>命名用 peek 而不是 get：读它不会产生任何副作用，也不保证一定读得到 ——
     * 返回值同样只是"建议"，最终用哪个由 {@link #start} 说了算。
     */
    public static String peekRunId(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(RUN_ID_ATTR);
    }

    /**
     * 把「这次调用是从哪来的」放进上下文。与 {@link #attachRunId} 完全对称。
     *
     * <p>空值直接忽略（等价于"不标注"）：入口方手上是空串时，不该在库里留下一条空来源 ——
     * 空串与 null 在筛选器里会表现成两种值，而它们其实是同一件事。
     * 大小写与长度不在这里处理，统一交给 {@link AgentTraceRun} 的创建口，避免出现第二套规则。
     */
    public static void attachSource(RuntimeContext ctx, String source) {
        if (ctx != null && source != null && !source.isBlank()) {
            ctx.put(SOURCE_ATTR, source);
        }
    }

    /** 读出上下文里的来源；没有则返回 null。与 {@link #peekRunId} 一样，读它没有副作用。 */
    public static String peekSource(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(SOURCE_ATTR);
    }

    /**
     * RuntimeContext 属性键：写下「这次调用属于哪次工作流运行的哪个节点」。
     *
     * <p>与 {@link #RUN_ID_ATTR} / {@link #SOURCE_ATTR} 同一个通道、同一个理由：
     * 编排归属只有"观测与反查"这件事需要 —— 从工作流运行详情点进某条链路，
     * 靠的就是这两个值；而 agent 自己不需要知道它是被谁调起来的。
     */
    public static final String WORKFLOW_RUN_ID_ATTR = "md.trace.workflowRunId";

    /** {@link #WORKFLOW_RUN_ID_ATTR} 的配套键：节点 key。两者必须成对写，单独一个没有意义。 */
    public static final String WORKFLOW_NODE_KEY_ATTR = "md.trace.workflowNodeKey";

    /**
     * 把「这次调用属于哪次工作流运行的哪个节点」放进上下文。
     *
     * <p>两个值都为空时什么都不做（等价于"不属于任何工作流"）：
     * 只写一个会让页面上出现"有编排 id 但没有节点"的半残记录，
     * 而这种情况在排查时会被当成数据损坏，实际上它从一开始就没有意义。
     */
    public static void attachWorkflow(RuntimeContext ctx, String workflowRunId, String workflowNodeKey) {
        if (ctx == null || workflowRunId == null || workflowRunId.isBlank()) {
            return;
        }
        ctx.put(WORKFLOW_RUN_ID_ATTR, workflowRunId);
        if (workflowNodeKey != null && !workflowNodeKey.isBlank()) {
            ctx.put(WORKFLOW_NODE_KEY_ATTR, workflowNodeKey);
        }
    }

    /** 读出上下文里的工作流运行 id；没有则返回 null。与 {@link #peekRunId} 一样，读它没有副作用。 */
    public static String peekWorkflowRunId(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(WORKFLOW_RUN_ID_ATTR);
    }

    /** 读出上下文里的工作流节点 key；没有则返回 null。 */
    public static String peekWorkflowNodeKey(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(WORKFLOW_NODE_KEY_ATTR);
    }

    /**
     * 开始一次 run。返回的对象由 middleware 透传给后续的 beginStage / endStage / finish。
     *
     * @param agentKind 取值见 {@link AgentTraceRun#AGENT_KIND_REACT} / {@link AgentTraceRun#AGENT_KIND_HARNESS}。
     *                  刻意不给默认值：调用方（AgentRunTraceMiddleware）按装配期登记的类型快照判定，
     *                  漏判就会给 harness 的运行贴成 react 标签，而页面是拿它当徽标显示的。
     */
    public AgentTraceRun start(String agentName, String agentKind, String sessionId, String userId,
                               Map<String, Object> input) {
        return start(agentName, agentKind, sessionId, userId, input, null, null);
    }

    /**
     * 开始一次 run，并优先采用调用方建议的 runId。
     *
     * <p>建议值只在<b>两头都不出错</b>时才被采纳：形状合法（见
     * {@link TraceRunIdGenerator#isWellFormed}）且尚未被占用。任何一条不满足就退回现生成，
     * 并打一条 warn —— 两种情况都说明调用方那边有 bug（id 是手写的、或者同一份上下文被
     * 复用给了两次调用），静默接受会直接把两次 run 并成一条，观测数据比没有还糟。
     *
     * @param preferredRunId 调用方希望的 id；为 null 表示"你随意生成"
     */
    public AgentTraceRun start(String agentName, String agentKind, String sessionId, String userId,
                               Map<String, Object> input, String preferredRunId) {
        return start(agentName, agentKind, sessionId, userId, input, preferredRunId, null);
    }

    /**
     * 开始一次 run，并带上「这次调用是从哪来的」。
     *
     * <p>多出来的 {@code source} 不参与任何业务判断，只落成一列供观测页筛选与展示 ——
     * 之所以在这里就写死而不是事后补：run 行会随执行过程被刷三次（开始 / 系统提示词 / 收口），
     * 事后补意味着要在某个时刻回头找到这条 run，而"哪个时刻一定找得到"本身又要一套约定。
     *
     * @param source 取值见 {@link AgentTraceRun#SOURCE_CONSOLE} 等常量；null 表示不标注
     */
    public AgentTraceRun start(String agentName, String agentKind, String sessionId, String userId,
                               Map<String, Object> input, String preferredRunId, String source) {
        return start(agentName, agentKind, sessionId, userId, input, preferredRunId, source, null, null);
    }

    /**
     * 开始一次 run，并带上「这次调用属于哪次工作流运行的哪个节点」。
     *
     * <p>与上一个重载的关系同 {@link AgentTraceRun} 的两个构造器：四个与工作流无关的入口
     * 继续用 7 参版本，不被迫写两个 null。
     *
     * @param workflowRunId  工作流运行 id（{@code md_workflow_run.run_id}）；null = 不是工作流发起的
     * @param workflowNodeKey 工作流节点 key；null = 不是工作流发起的
     */
    public AgentTraceRun start(String agentName, String agentKind, String sessionId, String userId,
                               Map<String, Object> input, String preferredRunId, String source,
                               String workflowRunId, String workflowNodeKey) {
        return start(agentName, agentKind, sessionId, userId, input, preferredRunId, source,
                workflowRunId, workflowNodeKey, null, null);
    }

    /**
     * 最全形状：再带上"是哪个父 run 派发出这条 run 的、对应哪个后台任务"。
     *
     * <p>只有一种调用方会用到这里：{@link com.md.agentplatform.server.agent.middleware.AgentRunTraceMiddleware}
     * 在"上下文里的 RunState 已经收口、只能新开一条 run"时说清楚它是谁的子链路（异步子 agent）。
     * 其余入口继续用上面的短重载，不必写四个 null。
     *
     * @param parentRunId 派发出这条 run 的父 run；null = 用户/通道/工作流直接发起
     * @param taskId      框架 TaskRepository 的后台任务号；null = 不是后台任务跑起来的
     */
    public AgentTraceRun start(String agentName, String agentKind, String sessionId, String userId,
                               Map<String, Object> input, String preferredRunId, String source,
                               String workflowRunId, String workflowNodeKey,
                               String parentRunId, String taskId) {
        // runId 形如 t3k9f2a1-x7b2q9：时间前缀保证字典序等于时间序，落库后可直接按它排序取最近 N 次。
        String runId = resolveRunId(preferredRunId);
        AgentTraceRun run = new AgentTraceRun(runId, agentName, agentKind, sessionId, userId, input, source,
                workflowRunId, workflowNodeKey, parentRunId, taskId);
        runs.put(runId, run);
        order.addLast(runId);
        evictIfNeeded();

        // 先把 run 行落下去，再写根节点事件：事件表虽然没建外键，但"先有 run 再有事件"
        // 是读侧重建时的假设（findRun 先查 run 行，查不到就直接返回 null，事件会被丢掉）。
        archiveRun(run);

        // 立刻写下根节点的 STARTED 事件（seq 恒为 1）。
        // 刻意不等 run 结束才写：页面在"刚开跑"时就应该有根节点可看，而不是一片空白。
        AgentTraceEvent rootStarted = AgentTraceEvent.started(run.nextSeq(), AgentTraceEvent.PHASE_AGENT,
                agentName, run.getStartedAtMs(), input);
        record(run, rootStarted);
        return run;
    }

    /**
     * 定下这次 run 真正要用的 id：能用调用方给的就用，否则现生成。
     *
     * <p>「已被占用」查的是内存里的 runs。极端情况下 id 可能只存在于库里（已被淘汰出内存），
     * 那种情况这里查不到，交给库表唯一键兜底 —— 代价是那条 run 的归档行插不进去，
     * 属于观测数据丢一条，不会污染已有记录。
     */
    private String resolveRunId(String preferredRunId) {
        if (preferredRunId == null || preferredRunId.isBlank()) {
            return TraceRunIdGenerator.next();
        }
        if (!TraceRunIdGenerator.isWellFormed(preferredRunId)) {
            log.warn("[Trace] 调用方建议的 runId 形状不合法，已改为现生成: preferred={}", preferredRunId);
            return TraceRunIdGenerator.next();
        }
        if (runs.containsKey(preferredRunId)) {
            log.warn("[Trace] 调用方建议的 runId 已被占用，已改为现生成: preferred={}", preferredRunId);
            return TraceRunIdGenerator.next();
        }
        return preferredRunId;
    }

    /**
     * 阶段开始：分配 seq、写下 STARTED 事件，返回阶段标识（stageSeq）。
     *
     * <p>返回的这个 int 就是"阶段句柄"：调用方把它交给 {@link #endStage} 收口即可。
     * 不给它包一层对象，是因为它同时也是一行数据的主键组成部分（run_id + seq）——
     * 两种身份天然重合，多包一层只会多出一处可能对不上的地方。
     */
    public int beginStage(AgentTraceRun run, String phase, String title, long stageStartMs,
                          Map<String, Object> input) {
        if (run == null) {
            return 0;
        }
        int seq = run.nextSeq();
        AgentTraceEvent event = AgentTraceEvent.started(seq, phase, title, stageStartMs, input);
        record(run, event);
        return seq;
    }

    /**
     * 阶段收口：再分配一个 seq、写下 COMPLETED 事件。
     *
     * <p>为什么要按 stageSeq 把 STARTED 事件找出来：phase、title、开始时刻三样只存在 STARTED 上
     * （COMPLETED 只承载结果），而这三样是渲染这个节点必需的。一个 run 里事件只有几十条，
     * 线性查找完全够用；引入索引反而多出一份可能失同步的状态。
     */
    public void endStage(AgentTraceRun run, int stageSeq, long costMs,
                         Map<String, Object> output, String error) {
        if (run == null || stageSeq <= 0) {
            return;
        }
        AgentTraceEvent started = findStarted(run, stageSeq);
        if (started == null) {
            return;
        }
        AgentTraceEvent event = AgentTraceEvent.completed(run.nextSeq(), stageSeq, started.phase(),
                started.title(), started.stageStartMs(), costMs, output, error);
        record(run, event);
    }

    /** 记录本轮的系统提示词（run 级属性：不占事件序号、不进调用树）。 */
    public void systemPrompt(AgentTraceRun run, String prompt) {
        if (run != null) {
            run.setSystemPrompt(prompt);
            // 系统提示词是 run 行上的一列，改了就要把 run 行刷一遍，否则重启后页面上这一段是空的。
            archiveRun(run);
        }
    }

    /**
     * 结束一次 run：写下最终状态与 token 汇总，并落下根节点的 COMPLETED 事件。
     *
     * @param output 根节点收口时的结构化结果（result / signal / iterations 等）。
     */
    public void finish(AgentTraceRun run, String status, int inputTokens, int outputTokens,
                       int cachedTokens, String error, Map<String, Object> output) {
        if (run == null) {
            return;
        }
        run.finish(status, inputTokens, outputTokens, cachedTokens, error);
        AgentTraceEvent rootCompleted = AgentTraceEvent.completed(run.nextSeq(), ROOT_STAGE_SEQ,
                AgentTraceEvent.PHASE_AGENT, run.getAgentName(), run.getStartedAtMs(), run.getCostMs(),
                output, error);
        record(run, rootCompleted);
        // 最后再刷一次 run 行：它承载状态、结束时刻、token 汇总与错误摘要 —— 这四样都只在
        // finish 之后才确定，是"重启后列表页仍然正确"的关键。顺序必须在根节点事件之后，
        // 否则读侧可能看到一个"已完成但根节点还没收口"的矛盾状态。
        archiveRun(run);
    }

    // ==================================================================================
    // 事件发布：内存 → 归档 → 唤醒订阅者
    // ==================================================================================

    /**
     * 发布一条事件：进内存、交归档、叫醒正在看的订阅者。
     *
     * <p><b>三件事的顺序不能换，而且只允许在这一个地方发生：</b>
     * <ol>
     *   <li>先 {@link AgentTraceRun#addEvent} —— 订阅者被叫醒后会立刻重读本 run，
     *       事件必须已经能被读到。顺序反了，订阅者读到的还是"这条事件之前"的状态，那次唤醒白叫，
     *       而它只会等<b>下一条</b>事件才醒 —— 页面上表现为"某个节点晚一拍才出现"；</li>
     *   <li>再归档 —— 只影响"重启后还能不能查到"，与页面的实时性无关。
     *       放在唤醒之前，是为了不放大"页面已经显示、库里还没有"这个窗口；</li>
     *   <li>最后唤醒订阅者 —— 非阻塞信号，漏了有兜底轮询兜底，代价最低的放最后。</li>
     * </ol>
     *
     * <p>为什么把三步收进一个方法：以前是 {@code run.addEvent(event); archiveEvent(run, event);}
     * 成对出现四次，将来加写入点时漏掉一处，就会变成"页面上某个阶段永远不出现"——
     * 这是最难查的一类不一致：内存里有、库里也有、日志里也有，只有页面没有。
     */
    private void record(AgentTraceRun run, AgentTraceEvent event) {
        run.addEvent(event);
        archiveEvent(run, event);
        if (streamNotifier != null) {
            streamNotifier.publish(run.getRunId());
        }
    }

    /** 找到 stageSeq 对应的 STARTED 事件。 */
    private static AgentTraceEvent findStarted(AgentTraceRun run, int seq) {
        for (AgentTraceEvent event : run.getEvents()) {
            if (event.seq() == seq && !event.isTerminal()) {
                return event;
            }
        }
        return null;
    }

    /**
     * 最近的 run，新的在前。
     *
     * <p><b>内存优先、不足查库补齐</b>：内存里的就是最近发生的那几条，多数时候够用；
     * 只有两种情况需要回库 —— 进程刚重启（内存是空的）和请求的条数超过内存上限
     * （{@code agent-platform.trace-max-runs}）。
     */
    public List<AgentTraceRun> recent(int limit) {
        List<AgentTraceRun> hot = recentInMemory(limit);
        if (traceStore == null || hot.size() >= limit) {
            return hot;
        }
        return topUpFromStore(hot, limit);
    }

    /** 纯内存的最近 N 条（Step 1 的实现，原样保留）。 */
    private List<AgentTraceRun> recentInMemory(int limit) {
        List<String> ids = new ArrayList<>(order);
        List<AgentTraceRun> result = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 0 && result.size() < limit; i--) {
            AgentTraceRun run = runs.get(ids.get(i));
            if (run != null) {
                result.add(run);
            }
        }
        return result;
    }

    /**
     * 从归档里补齐到 limit 条。
     *
     * <p>拼接规则：内存那几条在最前（它们最新），再把库里"内存里没有的"按库返回的顺序接在后面。
     * 之所以能直接接而不需要重排序：内存是库的一个子集，且只包含最新的那一段
     * （run 是往队尾追加、从队头淘汰的），所以"内存里的都排在库的前面"这个前提成立。
     *
     * <p>返回的是<b>不带事件</b>的 run（库的 findRecent 就不查事件）—— 调用方是列表页，
     * 只要 run 级字段。这是 TraceStore 类注释里写明的约定。
     */
    private List<AgentTraceRun> topUpFromStore(List<AgentTraceRun> hot, int limit) {
        List<AgentTraceRun> archived;
        try {
            archived = traceStore.findRecent(limit);
        } catch (RuntimeException e) {
            // 读库失败不能把整个列表页打挂：内存里的那部分照常返回，页面至少还能看见刚跑过的几次。
            log.warn("trace 归档列表读取失败，本次只返回内存中的 {} 条", hot.size(), e);
            return hot;
        }
        Set<String> seen = new HashSet<>();
        for (AgentTraceRun run : hot) {
            seen.add(run.getRunId());
        }
        List<AgentTraceRun> result = new ArrayList<>(hot);
        for (AgentTraceRun run : archived) {
            if (result.size() >= limit) {
                break;
            }
            if (seen.add(run.getRunId())) {
                result.add(run);
            }
        }
        return result;
    }

    /**
     * 按 id 取一次 run（含全部事件），内存没有就查归档。
     *
     * <p>回库这一步是"重启后还能看历史链路"的实现点：内存是易失的，
     * 而页面上点开某次执行要看的就是完整调用树。
     *
     * <p>刻意不做"负缓存"（记住哪些 id 不存在）：观测页面的请求量是人工点击级别，
     * 一次未命中的主键查询代价可以忽略，换来的是"run 被清理掉之后立刻就能感知"。
     */
    public AgentTraceRun get(String runId) {
        if (runId == null) {
            return null;
        }
        AgentTraceRun hot = runs.get(runId);
        if (hot != null || traceStore == null) {
            return hot;
        }
        try {
            return traceStore.findRun(runId);
        } catch (RuntimeException e) {
            log.warn("trace 归档明细读取失败, runId={}", runId, e);
            return null;
        }
    }

    public int size() {
        return runs.size();
    }

    public void clear() {
        runs.clear();
        order.clear();
        // 归档也要清：否则"点清空 → 刷新又全回来了"，用户会认为按钮坏了。
        if (traceStore != null) {
            try {
                traceStore.clearAll();
            } catch (RuntimeException e) {
                log.warn("trace 归档清空失败（内存已清空）", e);
            }
        }
    }

    // ==================================================================================
    // 归档写入：失败只记日志，绝不影响 agent 执行
    // ==================================================================================
    // 观测是"旁路"能力。任何归档异常如果冒泡回中间件，就等于"日志写不进去把业务搞挂了"。
    // 注意这里 catch 的是 RuntimeException 而不是 Throwable：像 OutOfMemoryError 这类
    // JVM 级错误不该被吞掉，那是系统已经不可用的信号。

    private void archiveRun(AgentTraceRun run) {
        if (traceStore == null) {
            return;
        }
        try {
            traceStore.saveRun(run);
        } catch (RuntimeException e) {
            log.warn("trace run 归档失败, runId={}", run.getRunId(), e);
        }
    }

    private void archiveEvent(AgentTraceRun run, AgentTraceEvent event) {
        if (traceStore == null) {
            return;
        }
        try {
            traceStore.appendEvent(run, event);
        } catch (RuntimeException e) {
            log.warn("trace 事件归档失败, runId={} seq={}", run.getRunId(), event.seq(), e);
        }
    }

    private void evictIfNeeded() {
        int max = platformProperties == null ? 0 : platformProperties.getTraceMaxRuns();
        if (max <= 0) {
            max = 200;
        }
        while (order.size() > max) {
            String oldest = order.pollFirst();
            if (oldest != null) {
                runs.remove(oldest);
            }
        }
    }
}
