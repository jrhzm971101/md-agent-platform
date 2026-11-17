package com.md.agentplatform.server.agent.middleware;

import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 运行过程记录中间件 —— 把每个 stage 的输入输出写进调用链路注册表，供观测页面成树展示。
 *
 * <h2>一、执行模型：一次调用 + 一条事件流</h2>
 * 每个 stage 方法只被调用一次，但看起来像“开始 / 结束两次”，原因是方法返回的不是值而是
 * Flux&lt;AgentEvent&gt;（事件流），且 Reactor 是惰性的：
 * <pre>
 * onXxx(agent, ctx, input, next) {
 *     beginStage(input)          // “开始”：方法体里的同步代码，入参就在 input 这个 record 里
 *     return next.apply(input)   // 这行只是拿到流，此刻一个事件都还没产生
 *                .doFinally(...) // “结束”：事件流终止时触发，不是方法被再调一次
 * }
 * </pre>
 * 由此推出一条铁律：调用前就存在的数据在入参里，调用后才产生的东西只可能在事件流里。
 * <ul>
 *   <li>模型入参、工具名 + 参数 在调用前已知，直接躺在 ModelCallInput / ActingInput 里</li>
 *   <li>token 用量、工具结果 调用后才有，只能从流里的 *Event 拿</li>
 * </ul>
 *
 * <h2>二、五个 stage 的入参 / 出参对照</h2>
 * <pre>
 * onAgent       洋葱型  入参 AgentInput.msgs()                                出参 流里的 AgentResultEvent / ExceedMaxItersEvent，终止靠 doFinally
 * onReasoning   洋葱型  入参 ReasoningInput(messages, tools, options)         出参 攒 TextBlockDeltaEvent / ThinkingBlockDeltaEvent + ToolCallStartEvent
 * onModelCall   洋葱型  入参 ModelCallInput(messages, tools, options, model)  出参 流里的 ModelCallEndEvent.getUsage()
 * onActing      洋葱型  入参 ActingInput.toolCalls()                          出参 攒 ToolResultTextDeltaEvent / ToolResultDataDeltaEvent，在 ToolResultEndEvent 收口
 * onSystemPrompt 管道型 入参 String                                           出参 Mono&lt;String&gt;，只能改值，没有“前后”
 * </pre>
 * 注意 {@link AgentTraceEvent#PHASE_SUBAGENT} <b>不是一个 stage</b>：子 agent 派发走的就是 onActing
 * （框架把 agent_spawn 注册成一个普通工具），只是写入时按工具名分流成了独立阶段 —— 见第三节第 4 条。
 *
 * <h2>三、四个必须知道的坑</h2>
 * <ol>
 *   <li>工具结果不在 ToolResultEndEvent 里。该事件只有 state 没有内容，内容只能靠一个个
 *       ToolResultTextDeltaEvent 攒出来，并用 toolCallId 做 key（并行工具调用时事件会交错）。
 *       框架自带的 AgentTraceMiddleware.onActing 也是这么绕的。</li>
 *   <li>没有 error 事件。AgentEventType 里没有 ERROR，异常只在 Flux 上以 error 信号流过，
 *       只能靠 doOnError / doFinally 捕获，否则静默丢失。</li>
 *   <li>本类会被复制给子 agent。HarnessAgent.Builder#filterCopyableMiddlewares 只排除
 *       HarnessRuntimeMiddleware 与 GracefulShutdownMiddleware，自己写的会被原样复制。
 *       所以实例必须无状态，per-run 状态一律挂 RuntimeContext。</li>
 *   <li><b>子 agent 派发走的也是 onActing。</b>Harness 调度子 agent，在框架里就是一次普通工具调用
 *       （工具名 {@code agent_spawn}），所以它的"开始 / 结果"和普通工具完全同源，只是被记成
 *       {@link AgentTraceEvent#PHASE_SUBAGENT} 这个独立阶段，好让页面把子 agent 与普通工具区分开。
 *       要特别注意的是：<b>一批并行派发的开始时刻取的是同一个值</b>（和同一批并行工具一样，见 onActing），
 *       所以它们跑完后区间会互相重叠 —— 成树方不能只看"区间包含"，必须按"外层开始得更早"判父子
 *       （见 {@code AgentRunEventTranslator.contains}），否则跑得久的那次派发会把它之后的兄弟吞成子节点。</li>
 *   <li><b>子 agent 有两副面孔，写入侧必须都照顾到。</b>
 *       ① 同步（默认 / 强制同步）：父 run 的区间把子 agent 的整棵子树包住，父 run 结束前一切照旧；
 *       ② 异步（{@code timeout_seconds=0}，或同步等待超时后被框架 promote 成后台任务）：
 *       父 run 早就收口了，子 agent 还在跑，它的事件只能落进一条新开的 run（见 onAgent 里
 *       parentRunId 那一段），于是在页面上成了"父链路之外的另一棵树"。
 *       两种形态的区分全靠派发结果文本里的 {@code status}（{@code accepted} /
 *       {@code timeout_promoted} = 后台仍在跑），所以结果回来时多解析一步，见 {@link #lineValue}。</li>
 * </ol>
 *
 * <p>关于顺序：本版框架 MiddlewareBase#order() 默认返回 1 且无任何调用方，链顺序等于注册顺序，
 * MiddlewareChain#build 从列表尾部向前回卷，即列表首元素最外层。
 */
@Component
public class AgentRunTraceMiddleware implements MiddlewareBase {

    /** per-run 状态在 RuntimeContext 里的 key。 */
    private static final String RUN_STATE_KEY = "md.trace.runState";

    /** 文本类字段截断长度：够看清在干什么，又不至于把事件载荷撑爆。 */
    private static final int TEXT_LIMIT = 300;

    /** 工具入参截断长度：工单类工具的 SQL / JSON 参数往往很长。 */
    private static final int ARGS_LIMIT = 500;

    // 阶段名的词表定义在 AgentTraceEvent 上 —— 写入方 / 成树方 / 测试共用同一份，
    // 这里直接用短名引用，避免同一个字符串在项目里出现两遍。
    // MODEL_CALL 被折叠进 REASONING、SYSTEM_PROMPT 挂着 run 上，都不占事件序号
    // （原因见 AgentTraceEvent 的类注释），所以没有它们的常量。
    private static final String PHASE_REASONING = AgentTraceEvent.PHASE_REASONING;
    private static final String PHASE_ACTING = AgentTraceEvent.PHASE_ACTING;
    private static final String PHASE_SUBAGENT = AgentTraceEvent.PHASE_SUBAGENT;

    /**
     * Harness 派发子 agent 用的工具名 —— 由框架的 SubAgentMiddleware 注册成一个普通工具。
     *
     * <p>为什么靠"工具名"来判定这次调用是不是子 agent 派发：这是写入侧在此刻唯一能拿到、
     * 而且确定指向子 agent 的信息（工具名与 args 里的 agent_id / label 同源，见
     * {@code toolCallInput}）。框架的 AgentEvent 流里没有任何"这是子 agent"的标记位，
     * RuntimeContext 又是父子共享的（见类注释第三节），两处都不可用。
     */
    private static final String TOOL_AGENT_SPAWN = "agent_spawn";

    /** 子 agent 派发标题里 label 的截断长度：标题列能装下 512 字符，但列表里太长的标题没法看。 */
    private static final int LABEL_LIMIT = 60;

    /**
     * 调用链路注册表 —— 页面侧的唯一数据来源。
     *
     * <p>本类只观察不改写：把每个 stage 的入参 / 出参按 beginStage / endStage 写进 Registry，
     * 由它去成树、落库。2026-09-22 起不再往 AGENT-TRACE 这个 logger 打过程日志 —— 早期还没有
     * 观测页面时靠它排障，如今页面上能看到的比日志全（含每个节点的耗时、token 与完整载荷）。
     *
     * <p>用构造器注入而非字段注入：本类会被框架复制给子 agent 长期共享，
     * 依赖必须在构造完成时就位且此后不再变化。
     */
    private final AgentTraceRegistry traceRegistry;

    @Autowired
    public AgentRunTraceMiddleware(AgentTraceRegistry traceRegistry) {
        this.traceRegistry = traceRegistry;
    }

    /**
     * agent 名字 → agent 类型（react | harness）的装配期快照。
     *
     * <p>为什么不靠 {@code agent instanceof HarnessAgent} 判断：MiddlewareBase 回调里的 agent 参数
     * 拿到的是<b>内层 ReActAgent</b>，不是外壳。框架侧 HarnessAgent.Builder#middleware(mw) 的实现是
     * {@code middlewares.add(mw); inner.middleware(mw);}，而 inner 就是
     * {@code private final ReActAgent.Builder inner = ReActAgent.builder()}（HarnessAgent.java:1198）。
     * 也就是说业务中间件一律挂在 delegate 上，外壳永远不会出现在回调参数里 ——
     * instanceof 恒为 false，所有 run 都会被记成 react。
     * 这不是理论问题：workwx_batch_agent 的定义是 HARNESS，执行链路页上的徽标却一直是 react。
     *
     * <p>为什么是"装配期登记"而不是"运行期回查 AgentDefinition"：定义是可变数据（页面随时能改），
     * 回查会让"昨天那条 harness 的 run"在改完定义后被重新解释成 react，历史观测数据失去可信度。
     * 快照只影响此后新建的 agent；已经写进 AgentTraceRun 的类型是历史事实，不会被改写。
     */
    private final Map<String, String> agentKinds = new ConcurrentHashMap<>();

    /**
     * 装配期登记某个 agent 的运行类型，由 ConfigurableAgentBuilder 在 build 时调用。
     *
     * <p>同名再次登记即覆盖：定义从 REACT 改成 HARNESS 时 AgentManager 会重建实例并重新登记，
     * 新实例的类型立即生效。被移除的 agent 会留下一条不再命中的老记录，无副作用。
     */
    public void registerAgentKind(String agentName, String agentKind) {
        if (agentName != null && !agentName.isBlank()) {
            agentKinds.put(agentName, agentKind);
        }
    }

    /**
     * 单次 run 的可变累加器。
     *
     * <p>为什么不能放实例字段：middleware 实例会被复制给子 agent 复用，多个 run 会同时跑，
     * 写实例字段必然串数据。只有挂在 RuntimeContext 上才天然隔离。
     */
    private static final class RunState {
        /**
         * Registry 侧的 run 对象。
         *
         * <p>runId 不再由本类自己生成 —— 旧实现两边各生成一个 UUID，
         * 两个号对不上。现在统一由 Registry 分配，调用方想预先知道 id 只能找它拿。
         */
        private final AgentTraceRun traceRun;
        private final long startedAt = System.currentTimeMillis();
        /**
         * agent 名 → 这个 agent 已经跑过的 ReAct 迭代轮次（取号即自增，从 1 开始）。
         *
         * <p>为什么按 agent 名分桶，而不是整轮 run 共用一个计数器：子 agent 与父 agent 共用同一个
         * RunState，而一批子 agent 是<b>并发</b>进入 onReasoning 的，共用计数器必然互相抢号。
         * 现场表现是标题变成"第 20 / 24 / 22 / 25 / 23 轮推理 · workwx_doc_agent"这种乱序轮次
         * （2026-09-23 真库 run mub3rqvg-esxkhw 的 9 个并发子 agent 上实测到）。分桶之后每个 agent
         * 的轮次各自从 1 开始、互不打扰，标题里的轮次才可信。
         *
         * <p>与 {@link #modelCallFolds} 一样只依赖"agent 名"这一确定性输入；同一个 agent 的多轮推理
         * 是串行的（ReAct 循环），所以同一个桶的 +1 不会并发。
         */
        private final Map<String, AtomicInteger> iterationsByAgent = new ConcurrentHashMap<>();
        /** 整个 run 累计的 token，由 onModelCall 逐次累加。 */
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cachedTokens = new AtomicLong();
        /**
         * agent 名 → 模型调用折叠区。MODEL_CALL 不产生事件，它的模型名与用量攒在这里，
         * 等所属的 REASONING 收口时一并带走。
         *
         * <p>为什么按 agent 名分桶：子 agent 与父 agent 共用同一个 RunState，不隔离就会把
         * 子 agent 的模型用量算到父的推理节点上。同一 agent 的多轮推理是串行的（ReAct 循环），
         * 每轮收口时取走并清零，所以不会跨轮串数据。
         */
        private final Map<String, ModelCallFold> modelCallFolds = new ConcurrentHashMap<>();

        /** 各类事件出现次数，收尾时随 run 的 output 一起落进 Registry。 */
        private final Map<String, Integer> eventCounts = new ConcurrentHashMap<>();

        /**
         * 子会话 id → 后台任务号。
         *
         * <p>为什么需要它：异步子 agent（{@code agent_spawn timeout_seconds=0}）会在父 run 收口
         * 之后才开跑，那时中间件只能给它新开一条 run；「这条 run 是哪个 task 跑起来的」这个信息
         * 只有父侧的派发结果里有，而父侧解析到它的时刻与子侧开 run 的时刻没有先后保证 ——
         * 所以先落在<b>父子共享的 RunState</b> 上（子 agent 的 RuntimeContext 是从父复制来的，
         * 里面引用的就是这个对象），开 run 时能读到就用，读不到就留 null。
         *
         * <p>它不需要持久化：只是"开 run 那一刻能不能顺手把 task_id 填上"的尽力而为。
         * 填不上不影响任何业务，父侧派发节点上的 task_id 照常能看。
         */
        private final Map<String, String> asyncTasksByChildSession = new ConcurrentHashMap<>();

        /** 记下"这次派发出去的子会话属于哪个后台任务"。 */
        void rememberAsyncTask(String childSessionId, String taskId) {
            if (childSessionId != null && !childSessionId.isBlank() && taskId != null && !taskId.isBlank()) {
                asyncTasksByChildSession.put(childSessionId, taskId);
            }
        }

        /** 取某个子会话对应的后台任务号；没有记录时返回 null。 */
        String asyncTaskOf(String childSessionId) {
            return childSessionId == null ? null : asyncTasksByChildSession.get(childSessionId);
        }
        /**
         * 是否已经收口 —— 收口只允许一次。
         *
         * <p>doFinally 在 onComplete / onError / cancel 下都会触发；除此之外，
         * 子 agent 的 doFinally 也会走到同一个 RunState。没有这道闸，
         * run 的结束时间会被后到的信号覆盖掉。
         *
         * <p>它同时被 onAgent 用来判断"ctx 上这个 RunState 是不是已经跑完了"，
         * 从而决定本次调用该复用还是新开一个 run。
         */
        private final AtomicBoolean finished = new AtomicBoolean();

        RunState(AgentTraceRun traceRun) {
            this.traceRun = traceRun;
        }

        /**
         * 这个 agent 是不是本 run 的<b>根 agent</b>（名字与建 run 时记下的那个一致）。
         *
         * <p>用来决定谁能写 <b>run 级属性</b>（目前只有系统提示词那一步）。
         *
         * <p>为什么不用"是不是它建的 run"来判：子 agent 与父 agent 共用<b>同一个</b> RunState，
         * 那个标志对子 agent 同样成立 —— 第一版就是拿它当判据，结果子 agent 照样覆盖了父的提示词
         * （被 {@code childAgentSystemPromptDoesNotOverwriteTheRunPrompt} 当场抓出来）。
         * 名字是这条链上<b>唯一</b>能把根与子区分开的确定性输入。
         */
        boolean isRootAgent(Agent agent) {
            if (traceRun == null || agent == null) {
                return false;
            }
            String rootName = traceRun.getAgentName();
            return rootName != null && rootName.equals(agent.getName());
        }

        /**
         * 取某个 agent 的下一轮 ReAct 迭代号（从 1 开始，取用即自增）。
         *
         * <p>拿不到 agent 名时返回 -1：调用方据此把标题写成"第 -1 轮推理"，让人一眼看出是异常数据，
         * 而不是伪装成一个看起来合法的轮次。
         */
        int nextIteration(String agentName) {
            if (agentName == null || agentName.isBlank()) {
                return -1;
            }
            return iterationsByAgent.computeIfAbsent(agentName, key -> new AtomicInteger())
                    .incrementAndGet();
        }

        /**
         * 整轮 run 的推理轮次总数（各 agent 分桶之和）。
         *
         * <p>语义是"这轮 run 一共做了多少次推理"：父 agent 的 ReAct 轮次 + 每个子 agent 各自跑掉的
         * 轮次。旧实现整轮共用一个计数器，这个数同样被并发抢号污染过（而且求和天然容忍并发），
         * 所以改成各桶相加。
         */
        int totalIterations() {
            int total = 0;
            for (AtomicInteger counter : iterationsByAgent.values()) {
                total += counter.get();
            }
            return total;
        }

        /** 取（必要时创建）某个 agent 的模型调用折叠区。 */
        ModelCallFold foldFor(String agentName) {
            return modelCallFolds.computeIfAbsent(agentName, key -> new ModelCallFold());
        }

        /**
         * 取走某个 agent 的折叠内容并清零；没有可折叠的东西时返回 null。
         *
         * <p>"取走 + 清零"而不是"读快照"：下一轮推理不该看到上一轮的用量。
         */
        ModelCallFold claimFold(String agentName) {
            return modelCallFolds.computeIfAbsent(agentName, key -> new ModelCallFold()).drain();
        }
    }

    /**
     * 单个 agent 的模型调用折叠区 —— 把 MODEL_CALL 的信息攒起来，交给所属的 REASONING。
     *
     * <p>为什么需要它：MODEL_CALL 在页面上不是一个独立节点（语义是"这一轮推理里的一次模型请求"），
     * 所以它不产生事件；但模型名与 token 用量是推理节点要展示的（标题旁的小字、用量徽标），
     * 只能由推理阶段在收口时带上。
     *
     * <p>为什么不用 ThreadLocal 或调用栈传递：本 middleware 的 stage 方法何时被调用、在哪个线程被调用，
     * 取决于框架对中间件链的组装方式（onReasoning 返回的 Flux 可能晚于方法体、在另一个线程上才真正
     * 触发下游）。依赖调用栈的时序假设很脆弱；而按 agent 名分桶只依赖"名字"这一确定性输入，
     * 失效方式是退化（拿不到用量）而不是串数据。
     */
    private static final class ModelCallFold {
        private final AtomicReference<String> model = new AtomicReference<>();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cachedTokens = new AtomicLong();

        void record(String modelName, ChatUsage usage) {
            if (modelName != null && !modelName.isBlank()) {
                model.set(modelName);
            }
            if (usage != null) {
                inputTokens.addAndGet(usage.getInputTokens());
                outputTokens.addAndGet(usage.getOutputTokens());
                cachedTokens.addAndGet(usage.getCachedTokens());
            }
        }

        /** 取走当前累加值并清零；一无所获时返回 null（表示这轮没有模型调用可折叠）。 */
        ModelCallFold drain() {
            String takenModel = model.getAndSet(null);
            long in = inputTokens.getAndSet(0L);
            long out = outputTokens.getAndSet(0L);
            long cached = cachedTokens.getAndSet(0L);
            if (takenModel == null && in == 0 && out == 0 && cached == 0) {
                return null;
            }
            ModelCallFold snapshot = new ModelCallFold();
            snapshot.model.set(takenModel);
            snapshot.inputTokens.set(in);
            snapshot.outputTokens.set(out);
            snapshot.cachedTokens.set(cached);
            return snapshot;
        }

        String model() {
            return model.get();
        }

        long inputTokens() {
            return inputTokens.get();
        }

        long outputTokens() {
            return outputTokens.get();
        }

        long cachedTokens() {
            return cachedTokens.get();
        }
    }

    // ==================================================================================
    // ① onAgent —— 整轮 run 的入口与出口，链上最外层（看到的是压缩 / 上下文注入之前的原始输入）
    // ==================================================================================

    /**
     * 入参 AgentInput 只有一个字段 msgs()，就是本轮调用方传进来的消息。
     * 出参没有“返回值”，结果是事件流里的 AgentResultEvent。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        List<Msg> inMsgs = input == null || input.msgs() == null ? List.of() : input.msgs();

        // ---------- run 归属 ----------
        // 旧实现是无条件 ctx.put(RUN_STATE_KEY, new RunState())，而本 middleware 实例会被
        // 框架复制给子 agent、且子 agent 与父 agent 共享同一个 RuntimeContext，
        // 于是子 agent 一进入就把父的 RunState 顶掉 —— 父 agent 此后所有的
        // onReasoning / onModelCall / onActing 都会记到子 run 名下，链路彻底串台。
        //
        // 现在的语义：一次用户调用 = 一个 run、一棵树。最外层 agent 建 run 并负责收口，
        // 子 agent 复用父 run、只补步骤。判定条件是"ctx 上有没有一个还没收口的 RunState"：
        //   - 有且未收口   → 子 agent 进入，复用（这正是修掉 runId 被顶掉的地方）
        //   - 没有 / 已收口 → 本次调用自己开一个 run
        // 后半个条件不是多余的：调用方若复用同一个 ctx 连发两次，第二次必须开新 run，
        // 否则会被并进已经结束的上一个 run，从此永不 close、在页面上一路显示“进行中”。
        // 当前所有调用点都是每次新建 ctx（WeComThinkRenderer / FeishuCardRenderer /
        // DailyReportSendWxJob），但这里刻意不依赖那个前提。
        //
        // 最后一维输入是「调用方建议的 runId」：页面对话要把 id 回执给前端、企微飞书要把 id
        // 写进日志，这些调用方都需要事先知道 id，所以由它们先定、这里照用。没建议值时
        // Registry 自己生成。采纳与否（形状合法、未被占用）由 Registry 判断，本类不重复实现。
        RunState existing = ctx.get(RUN_STATE_KEY);
        boolean owner = existing == null || existing.finished.get();
        // 走到 owner 分支有两层含义，区别必须记清楚，否则"这条 run 从哪来"就丢了：
        //   existing == null          → 一次真正的全新调用（用户/通道/工作流），头上没有父
        //   existing.finished == true → 上一个 run 已经收口，本次是在它的骨架上另起一条 run。
        //                              这只可能是异步子 agent：父 run 结束后，那个被 promote 成
        //                              后台任务的子 agent 才真正开跑（见 AgentTraceRun.parentRunId）。
        // 所以父 run 与后台任务号只在第二种情况下取，第一种情况保持 null。
        String parentRunId = existing == null ? null : existing.traceRun.getRunId();
        String asyncTaskId = existing == null ? null : existing.asyncTaskOf(ctx.getSessionId());
        // 建议的 runId 只在"真正的新调用"时采纳。第二种情况下 ctx 上的那个建议值是从父 run 的上下文
        // 复制过来的（子 agent 的 ctx 由框架 builder(parentCtx) 派生），它属于父 run —— 照用会与父 run
        // 撞主键（Registry 会打一条 warn 再换新 id，能自愈，但每次都误报"调用方有 bug"是噪音）。
        String suggestedRunId = existing == null ? AgentTraceRegistry.peekRunId(ctx) : null;
        RunState state = owner
                ? new RunState(traceRegistry.start(agent.getName(), agentKind(agent), ctx.getSessionId(),
                        ctx.getUserId(), describeMessages(inMsgs), suggestedRunId,
                        AgentTraceRegistry.peekSource(ctx),
                        // 编排归属：只有工作流节点会写这两个值（见 AgentTraceRegistry.attachWorkflow）。
                        // 其余四个入口取到的是 null，落库就是空列 —— 不需要在这里做任何分支判断。
                        AgentTraceRegistry.peekWorkflowRunId(ctx),
                        AgentTraceRegistry.peekWorkflowNodeKey(ctx),
                        parentRunId, asyncTaskId))
                : existing;
        if (owner) {
            ctx.put(RUN_STATE_KEY, state);
        }
        // ---------- 输出 ----------
        // 结果文本与异常都只能在事件流上收，但 doFinally 里要用，所以必须放在 lambda 外面。
        StringBuilder result = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        return next.apply(input)
                .doOnNext(event -> {
                    // 事件计数随 run 的 output 一起落库（见 finishRun），不再打日志。
                    state.eventCounts.merge(event.getType().name(), 1, Integer::sum);
                    collectResultText(event, result);
                })
                // 没有 error 事件，异常只能在这里接。只观察不吞：不要加 onErrorResume。
                .doOnError(failure::set)
                .doFinally(signal -> {
                    if (owner) {
                        finishRun(state, result.toString(), failure.get(), signal);
                    }
                    // 不是 owner 时故意什么都不做：子 agent 与父 agent 共用同一个 RunState，
                    // 它的 doFinally 触发时整轮 run 往往还没结束，
                    // 在这里收口会把 run 提前标成完成。
                });
    }

    // ==================================================================================
    // ② onReasoning —— 每一轮 ReAct 迭代里“想”的那一步
    // ==================================================================================

    /**
     * 入参 ReasoningInput 就是这一轮真正送进模型的上下文（压缩、技能注入之后的结果）。
     * 出参：模型这轮说了什么、想调什么工具，只能从事件流里攒。
     */
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        RunState state = ctx.get(RUN_STATE_KEY);
        // 轮次按 agent 名分桶取号：一批子 agent 是并发进入本方法的，整轮共用一个计数器必然抢号。
        int iteration = state == null ? -1 : state.nextIteration(agent.getName());

        // ---------- 输入 ----------
        // 请求侧的数据此刻已全部已知，直接写进下面的 beginStage —— 页面在"推理进行中"就能
        // 看到这个节点的入参（消息条数、可见工具全集、采样参数），不必等它结束才冒出来。
        List<Msg> msgs = input == null || input.messages() == null ? List.of() : input.messages();
        List<ToolSchema> tools = input == null || input.tools() == null ? List.of() : input.tools();

        // ---------- 输出 ----------
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<String> plannedTools = new ArrayList<>();
        long start = System.currentTimeMillis();

        // 阶段开始：请求侧的数据此刻已全部已知，先把 STARTED 事件写下来 ——
        // 这样页面在"推理进行中"就能看到这个节点，而不是等它结束才冒出来（这正是本次改造解决的事）。
        Map<String, Object> stepInput = new LinkedHashMap<>();
        stepInput.put("iteration", iteration);
        // 子会话标识：父 agent 与子 agent 会被同一个 middleware 实例观察，而事件里没有任何
        // "这是子 agent"的标记位（框架的 AgentEvent 流没有 span id）。唯一从数据上就确定属于
        // 子 agent 的东西是它自己的 sessionId —— 框架给每个子实例发的 ctx 是
        // RuntimeContext.builder(parentCtx).sessionId("sub-…")，子 agent 的所有回调都带这个值。
        // 记下它，成树方就能把"这棵子树"精确地挂到派发它的那个节点下，而不是靠时间区间去猜
        // （异步派发的区间根本不重叠，猜不出来）。
        stepInput.put("agentSession", ctx == null ? null : ctx.getSessionId());
        stepInput.put("messageCount", msgs.size());
        stepInput.put("toolCount", tools.size());
        stepInput.put("visibleTools", toolNames(tools));
        stepInput.put("options", describeOptions(input == null ? null : input.options()));
        // 框架注入的合成消息（后台任务交付的 <system-reminder>，metadata.synthetic=true）。
        // 它们长得像用户消息，实际是框架自己在下一轮推理前拼进上下文的 —— 不标出来，
        // 页面上就会让人误以为"用户突然说了这么一段 XML"。详见 Msg#METADATA_SYNTHETIC。
        List<String> reminders = syntheticReminderKinds(msgs);
        if (!reminders.isEmpty()) {
            stepInput.put("syntheticReminders", reminders);
        }
        int stageSeq = traceRegistry.beginStage(state == null ? null : state.traceRun, PHASE_REASONING,
                "第 " + iteration + " 轮推理 · " + agent.getName(), start, stepInput);

        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                        text.append(delta.getDelta());
                    } else if (event instanceof ThinkingBlockDeltaEvent delta && delta.getDelta() != null) {
                        thinking.append(delta.getDelta());
                    } else if (event instanceof ToolCallStartEvent toolCall) {
                        plannedTools.add(toolCall.getToolCallName() + "#" + shortId(toolCall.getToolCallId()));
                    }
                })
                .doFinally(signal -> {
                    long costMs = System.currentTimeMillis() - start;

                    Map<String, Object> stepOutput = new LinkedHashMap<>();
                    stepOutput.put("text", digest(text.toString(), TEXT_LIMIT));
                    stepOutput.put("thinking", digest(thinking.toString(), TEXT_LIMIT));
                    stepOutput.put("plannedTools", plannedTools);
                    // 把本轮模型调用折叠进来：MODEL_CALL 不单独成节点（页面语义是"这一轮推理里的
                    // 一次模型请求"），但模型名与 token 用量是推理节点要展示的，必须在这里补上。
                    if (state != null) {
                        ModelCallFold fold = state.claimFold(agent.getName());
                        if (fold != null) {
                            stepOutput.put("model", fold.model());
                            stepOutput.put("inputTokens", fold.inputTokens());
                            stepOutput.put("outputTokens", fold.outputTokens());
                            stepOutput.put("cachedTokens", fold.cachedTokens());
                            stepOutput.put("totalTokens", fold.inputTokens() + fold.outputTokens());
                        }
                    }

                    traceRegistry.endStage(state == null ? null : state.traceRun, stageSeq, costMs,
                            stepOutput, null);
                });
    }

    // ==================================================================================
    // ③ onModelCall —— 单次 LLM 调用；唯一能拿到 token 用量的地方（hook 时代没有）
    // ==================================================================================

    /**
     * 入参 ModelCallInput 比 ReasoningInput 多一个 model()，即这次真正发出去的请求体。
     * 出参：ModelCallEndEvent.getUsage() 带 in / out / cached / total token 与上游耗时。
     */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        RunState state = ctx.get(RUN_STATE_KEY);
        // 这次请求用的模型名。MODEL_CALL 刻意不产生事件：模型名与 token 用量折叠进所属的
        // REASONING 节点展示，上下文消息列表由推理节点负责记录，这里不必重复一份。
        String model = input == null || input.model() == null ? "-" : input.model().getModelName();

        // ---------- 输出 ----------
        return next.apply(input)
                .doOnNext(event -> {
                    if (!(event instanceof ModelCallEndEvent end)) {
                        return;
                    }
                    ChatUsage usage = end.getUsage();
                    if (state != null && usage != null) {
                        state.inputTokens.addAndGet(usage.getInputTokens());
                        state.outputTokens.addAndGet(usage.getOutputTokens());
                        state.cachedTokens.addAndGet(usage.getCachedTokens());
                    }

                    // 写进 Registry 的方式是"折叠"而不是独立事件：MODEL_CALL 在页面上属于
                    // 它所在的那一轮 REASONING（单独成节点会变成"推理下再挂一个推理"的同义反复），
                    // 而事件流要求输出与事件一一对应，所以它不占 seq。
                    // 模型名与用量在这里攒下，等 REASONING 收口时一并带走 —— run 级累加用于顶层
                    // 汇总，折叠值用于逐轮核对，两处用途不同，不是重复。
                    if (state != null) {
                        state.foldFor(agent.getName()).record(model, usage);
                    }
                });
    }

    // ==================================================================================
    // ④ onActing —— 工具执行；入参拿请求，出参靠攒 delta，别指望 ToolResultEndEvent 带内容
    // ==================================================================================

    /**
     * 入参 ActingInput 是这一轮要执行的工具调用列表（可能并行多个）。
     * 出参：工具结果内容不在任何单个事件里，必须用 toolCallId 做 key 攒 delta，
     * 在 ToolResultEndEvent（只带 state）到达时收口输出。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        RunState state = ctx.get(RUN_STATE_KEY);
        // ---------- 输入：工具调用请求（工具名 + 完整入参） ----------
        List<ToolUseBlock> calls = input == null || input.toolCalls() == null
                ? List.of() : input.toolCalls();

        // ---------- 输出：按 toolCallId 分桶攒结果（并行调用时事件交错，不能用单个 buffer） ----------
        Map<String, StringBuilder> textById = new ConcurrentHashMap<>();
        Map<String, List<String>> dataById = new ConcurrentHashMap<>();
        long start = System.currentTimeMillis();

        // 阶段开始：请求侧此刻就拿到了每个工具的名字与完整入参，所以"开始"事件在这里一次性写好，
        // 并用 toolCallId 记住各自的阶段句柄，等它自己的结果事件到了再收口。
        // 好处是一批并行工具在页面上会同时出现多个"进行中"的节点，而不是等最慢的那个结束才一起冒出来。
        Map<String, Integer> stageSeqByCallId = new ConcurrentHashMap<>();
        // 这次调用是"普通工具"还是"子 agent 派发" —— 结果回来时要按它决定要不要解析派发头。
        // 与阶段名的分流同源（都在下面那个 if 里定），所以不会出现"阶段名写成 SUBAGENT、
        // 收口时却按 ACTING 处理"的错配。
        Map<String, String> phaseByCallId = new ConcurrentHashMap<>();
        // 子会话标识，同 onReasoning：每个工具调用节点也带上，成树方按它归位。
        String agentSession = ctx == null ? null : ctx.getSessionId();
        for (ToolUseBlock call : calls) {
            // 派发子 agent 在框架里就是一次普通工具调用（工具名 agent_spawn），和普通工具走同一个
            // stage；这里只按工具名分流三件事：叫什么阶段、起什么标题、额外记什么入参。
            Map<String, Object> stageInput = toolCallInput(calls, call.getId());
            stageInput.put("agentSession", agentSession);
            String phase;
            String title;
            if (TOOL_AGENT_SPAWN.equals(call.getName())) {
                phase = PHASE_SUBAGENT;
                // 目标 agent 与 label 直接从请求入参这个 Map 里取 —— 此刻它还是结构化的，
                // 不必回头解析已经 digest 成字符串的 args（那是给页面看的展示形态）。
                String targetAgent = argText(call, "agent_id");
                String label = argText(call, "label");
                // 单独写进 input：成树方要用 targetAgent 当节点的 source（页面上标题旁的小字），
                // 页面细节面板里也能直接看到"这次派发给了谁、是第几批"。
                stageInput.put("targetAgent", targetAgent);
                stageInput.put("label", label);
                title = subagentTitle(targetAgent, label);
            } else {
                phase = PHASE_ACTING;
                title = "工具调用 · " + call.getName() + " · " + agent.getName();
            }
            int callStageSeq = traceRegistry.beginStage(state == null ? null : state.traceRun, phase,
                    title, start, stageInput);
            if (callStageSeq > 0) {
                stageSeqByCallId.put(call.getId(), callStageSeq);
                phaseByCallId.put(call.getId(), phase);
            }
        }

        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof ToolResultStartEvent started) {
                        textById.computeIfAbsent(started.getToolCallId(), key -> new StringBuilder());
                    } else if (event instanceof ToolResultTextDeltaEvent delta) {
                        if (delta.getDelta() != null) {
                            textById.computeIfAbsent(delta.getToolCallId(), key -> new StringBuilder())
                                    .append(delta.getDelta());
                        }
                    } else if (event instanceof ToolResultDataDeltaEvent delta) {
                        if (delta.getData() != null) {
                            dataById.computeIfAbsent(delta.getToolCallId(), key -> new ArrayList<>())
                                    .add(delta.getData().getClass().getSimpleName());
                        }
                    } else if (event instanceof ToolResultEndEvent end) {
                        String callId = end.getToolCallId();
                        // 先还原再解析、再落库：框架内置工具（agent_spawn / wait_async_results）递出来的
                        // delta 是被 JSON 编码过一遍的字符串字面量（带引号、换行是字面量 \n），
                        // 不还原的话按行解析只会得到一整行，派发结论全部解析不出来（见 decodeToolText）。
                        String text = decodeToolText(
                                textById.getOrDefault(callId, new StringBuilder()).toString());
                        List<String> dataBlocks = dataById.getOrDefault(callId, List.of());
                        long costMs = System.currentTimeMillis() - start;

                        // 收口这个工具的阶段：句柄是开始时按 toolCallId 存下的，不需要再回查请求侧。
                        // 拿不到句柄时 stageSeq 传 0，endStage 会直接跳过 —— 观测数据宁可少一条，
                        // 也不要写出没有起点、前端会整段丢弃的孤儿完成事件。
                        Integer callStageSeq = stageSeqByCallId.get(callId);
                        Map<String, Object> resultOutput = toolResultOutput(end, text, dataBlocks);
                        if (PHASE_SUBAGENT.equals(phaseByCallId.get(callId))) {
                            // 这里才拿得到"这次派发到底怎么了"：结果文本由框架拼出来，形如
                            //   agent_key: … / agent_id: … / session_id: sub-… / status: <值> [/ task_id: task_…]
                            //   [reply: <子 agent 的最终回答>]
                            // status 的取值决定页面上这个节点该画成"同步等回来了"还是"丢到后台去了"：
                            //   ok / error          → 同步跑完（或同步失败）
                            //   timeout             → 强制同步下的硬超时，子 agent 已被中断
                            //   accepted            → 丢到后台（timeout_seconds=0），子 agent 还在跑
                            //   timeout_promoted    → 同步等超时，框架把它转成了后台任务，子 agent 还在跑
                            // 后两种在页面上应当显示成"后台运行中"，而不是"成功" —— 这正是本次改造
                            // 要让人一眼看清的东西（此前它们和普通工具成功结果长得一模一样）。
                            String dispatchStatus = lineValue(text, "status");
                            String taskId = lineValue(text, "task_id");
                            String childSession = lineValue(text, "session_id");
                            resultOutput.put("dispatchStatus", dispatchStatus);
                            if (taskId != null) {
                                resultOutput.put("taskId", taskId);
                            }
                            if (childSession != null) {
                                // 子会话 id：成树方按它把子 agent 的那棵子树精确挂到这个派发节点下
                                // （异步派发时时间区间不重叠，靠区间是认不出来的）。
                                resultOutput.put("childSession", childSession);
                            }
                            if (state != null) {
                                // 记进父子共享的 RunState：异步子 agent 随后在父 run 收口之后开 run 时，
                                // 就能把 task_id 一并写进 run 行（尽力而为，取不到就留 null）。
                                state.rememberAsyncTask(childSession, taskId);
                            }
                        }
                        traceRegistry.endStage(state == null ? null : state.traceRun,
                                callStageSeq == null ? 0 : callStageSeq, costMs,
                                resultOutput, null);

                        // 结果已收口，及时释放，避免长对话里 map 无限增长
                        textById.remove(callId);
                        dataById.remove(callId);
                    }
                });
    }

    // ==================================================================================
    // ⑤ onSystemPrompt —— 管道型 stage（不是洋葱型）：只能改值，没有“前后”概念
    // ==================================================================================

    /**
     * 默认实现就是 Mono.just(sysPrompt)。这里原样返回等于只观察不改写；
     * 想追加约束（比如“必须输出 JSON”）就在返回前拼字符串。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String sysPrompt) {
        RunState state = ctx.get(RUN_STATE_KEY);

        // 管道型 stage 没有"前后"之分：进来什么就返回什么，也没有"进行中"这个状态。
        // 所以它不产生事件（不占 seq），而是作为 run 级属性挂上去 —— 系统提示词整轮只生效一个，
        // 放进调用树只会多出一个没有任何兄弟的节点。落库后它是 run 表上的一列。
        //
        // 只有根 agent 能写这一列：本 stage 对树里的每个 agent 都会被调用，而子 agent 与父 agent
        // 共用同一个 RunState，两个都写就是后写的覆盖先写的 —— 现场表现是 deployment_agent 的
        // run 表里躺着 K8s 检查 / Nacos 管理子 agent 的提示词，而 agent_name 还是父 agent。
        // 子 agent 的提示词刻意不留存：这一列的语义是"这个 run 用的系统提示词"，整轮只有一个值。
        if (state != null && state.isRootAgent(agent)) {
            traceRegistry.systemPrompt(state.traceRun, digest(sysPrompt, TEXT_LIMIT));
        }

        return Mono.just(sysPrompt);
    }

    // ==================================================================================
    // Registry 写入 —— 观测页面的唯一数据来源
    // ==================================================================================

    /**
     * 最外层 agent 收口：把 run 的最终状态、token 汇总与结果载荷交给 Registry。
     *
     * <p>根节点的两条事件（run.started / run.completed 或 error.raised）都由 Registry 负责写 ——
     * 起点在 start()、终点在这里。它们是 run 级的，不对应任何阶段，混进阶段写入只会让两套语义纠缠。
     *
     * <p>finished 闸保证只执行一次 —— doFinally 在 onComplete / onError / cancel 下都会触发，
     * 而 run 的结束时间只应被第一个到达的信号决定。
     */
    private void finishRun(RunState state, String result, Throwable failure, SignalType signal) {
        if (!state.finished.compareAndSet(false, true)) {
            return;
        }
        String error = failure == null
                ? null
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", digest(result, TEXT_LIMIT));
        output.put("signal", signal.name());
        // 整轮推理轮次 = 父 agent + 各子 agent 分桶之和（分桶见 RunState.iterationsByAgent）
        output.put("iterations", state.totalIterations());
        output.put("inputTokens", state.inputTokens.get());
        output.put("outputTokens", state.outputTokens.get());
        output.put("cachedTokens", state.cachedTokens.get());
        // 事件计数（各类 AgentEvent 出现了多少次）原本只打在收尾日志里，日志撤掉后改放进 run 的
        // output：页面据此仍能判断"这一步到底有没有走到"，不必再回头翻日志。
        output.put("eventCounts", new LinkedHashMap<>(state.eventCounts));

        // 根节点的 input 刻意留空：消息列表已经挂在 AgentTraceRun.input 上，避免同一份数据存两遍。
        // 状态词表暂时只有 DONE / ERROR。cancel（下游取消）既不是异常，也不能让 run
        // 永远停在 RUNNING，所以同样记为 DONE，真实原因由上面的 signal 字段保留。
        traceRegistry.finish(state.traceRun,
                error == null ? AgentTraceRun.STATUS_DONE : AgentTraceRun.STATUS_ERROR,
                (int) state.inputTokens.get(), (int) state.outputTokens.get(),
                (int) state.cachedTokens.get(), error, output);
    }

    /**
     * 消息列表 → 结构化摘要。
     *
     * <p>存的是 {@code describeMsg} 压出来的字符串而不是原始 Msg 对象：
     * payload 要能直接序列化给前端看，原始 Msg 的字段形态随时可能被框架改。
     */
    private static Map<String, Object> describeMessages(List<Msg> msgs) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("messageCount", msgs.size());
        List<String> described = new ArrayList<>(msgs.size());
        for (Msg msg : msgs) {
            described.add(describeMsg(msg));
        }
        summary.put("messages", described);
        return summary;
    }

    /** 从 AgentResultEvent 里攒最终文本 —— 事件流是唯一来源，doFinally 之前拿不到。 */
    private static void collectResultText(AgentEvent event, StringBuilder sink) {
        if (event instanceof AgentResultEvent resultEvent
                && resultEvent.getResult() != null
                && resultEvent.getResult().getTextContent() != null) {
            sink.append(resultEvent.getResult().getTextContent());
        }
    }

    /**
     * 按 toolCallId 从请求侧回查这次工具调用的完整入参。
     *
     * <p>为什么不在结束事件里取：ToolResultEndEvent 只保证带 toolCallId 与 state，
     * 工具名都可能是 null，更别说入参。入参只有请求侧（ActingInput.toolCalls）才有。
     */
    private static Map<String, Object> toolCallInput(List<ToolUseBlock> calls, String callId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("toolCallId", shortId(callId));
        for (ToolUseBlock call : calls) {
            if (callId != null && callId.equals(call.getId())) {
                summary.put("tool", call.getName());
                summary.put("args", digest(toJson(call.getInput()), ARGS_LIMIT));
                return summary;
            }
        }
        return summary;
    }

    /**
     * 从工具调用的结构化入参里取一个字符串值；拿不到（键不存在 / 不是字符串 / 只有空白）返回 null。
     *
     * <p>为什么读 {@code call.getInput()} 而不是解析 {@code args} 字符串：入参此刻本来就是
     * {@code Map<String, Object>}，直接取值没有任何边界要处理；而去解析字符串要面对转义、
     * 以及 args 已经被 digest 截断（尾部变成长度提示）等一堆情况，纯属自找麻烦。
     */
    private static String argText(ToolUseBlock call, String key) {
        if (call == null || call.getInput() == null) {
            return null;
        }
        Object value = call.getInput().get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    /**
     * 从 {@code agent_spawn} 的结果文本里取一行 {@code key: value} 的值。
     *
     * <p>为什么要解析这段文本：框架的 {@code agent_spawn} 把结果拼成一个固定格式的字符串
     * （{@code agent_key / agent_id / session_id / status / task_id / reply}），
     * 它是 {@link io.agentscope.core.event.ToolResultEndEvent} 里<b>唯一</b>拿得到的内容 ——
     * 该事件本身只有 toolCallId 与 state，没有结构化字段。好在格式是框架写死的，
     * 且这里只取前几行、不做任何猜测。
     *
     * <p>三条实现上的讲究：
     * <ol>
     *   <li><b>遇到 {@code reply:} 就停。</b>后面是子 agent 的原样回答，里面完全可能出现
     *       {@code status: xxx} 这种看起来一模一样的行；不设边界就会把子 agent 正文里的字
     *       当成派发状态，而且这种错误只在"子 agent 恰好输出了这么一行"时才出现，极难排查。</li>
     *   <li><b>取最后一条匹配而不是第一条。</b>框架会先写 spawnInfo（里面可能带一行
     *       {@code status: exposed (…) }），再在末尾追加真正的结论（{@code status: ok} /
     *       {@code accepted} / {@code timeout_promoted}）。取第一条会把"暴露给用户"这种
     *       旁注当成派发结论。</li>
     *   <li><b>取不到就返回 null</b>，绝不编一个默认值 —— 页面上"没有这个字段"与
     *       "字段值是 ok"是两件事。</li>
     * </ol>
     */
    /**
     * 把工具结果文本还原成"人看的形态"。
     *
     * <h2>为什么需要它</h2>
     * 同一张表里两种形态混着放（2026-09-28 真库实测，run muktpso3-257fe2）：MCP 工具给的是原样
     * 文本（{@code { "errcode": 640027, ... }}），而 harness 自己的内置工具
     * （{@code agent_spawn} / {@code wait_async_results}）递出来的 delta 是<b>被 JSON 编码过一遍</b>
     * 的字符串字面量：
     * <pre>"agent_key: agent:xxx\nagent_id: xxx\nstatus: accepted\ntask_id: task_xxx\n..."</pre>
     * 带着一对引号，换行是字面量 {@code \n} 这两个字符。不还原的后果不是报错，而是<b>静默解析失败</b>：
     * {@link #lineValue} 按 {@code \n} 切行只会得到一整行，派发结论（status / task_id / 子会话 id）
     * 全是 null —— 页面上"丢到后台去了"被画成"同步成功"，正是本次改造要消灭的那个误解，
     * 只是换了条路径复现。落库的 result 里也会残留字面量 {@code \n}：{@link #digest} 只把
     * 真正的空白折成空格，这两个字符不是空白，会原样留在页面上的"输出"面板里。
     *
     * <h2>还原规则（刻意只有三条）</h2>
     * <ol>
     *   <li>只在文本以引号开头时才动手 —— 其余一律原样返回，MCP 的 JSON 文本（以花括号开头）
     *       与自由文本都不会被碰到；</li>
     *   <li>去掉首引号与末尾成对的引号（被 digest 截断时末尾可能没有引号，也认）；</li>
     *   <li>只处理 {@code \n \r \t \" \\} 五种转义，别的一律原样保留 —— 不认识的转义宁可留着，
     *       也不猜（猜错会把工具的真实输出改掉）。</li>
     * </ol>
     * <p>第四个安全阀：一个转义都没碰到的（例如正文恰好是 {@code "hello"}）原样返回，不剥引号。
     * 宁可少改一次，也不要改错一次。
     */
    private static String decodeToolText(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"') {
            return text;
        }
        String body = trimmed.substring(1);
        if (body.endsWith("\"")) {
            body = body.substring(0, body.length() - 1);
        }
        StringBuilder out = new StringBuilder(body.length());
        boolean unescaped = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch != '\\' || i + 1 >= body.length()) {
                out.append(ch);
                continue;
            }
            char next = body.charAt(++i);
            switch (next) {
                case 'n' -> {
                    out.append('\n');
                    unescaped = true;
                }
                case 'r' -> {
                    out.append('\r');
                    unescaped = true;
                }
                case 't' -> {
                    out.append('\t');
                    unescaped = true;
                }
                case '"' -> {
                    out.append('"');
                    unescaped = true;
                }
                case '\\' -> {
                    out.append('\\');
                    unescaped = true;
                }
                default -> out.append('\\').append(next);
            }
        }
        return unescaped ? out.toString() : text;
    }

    private static String lineValue(String text, String key) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String prefix = key + ":";
        String found = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if ("reply:".equals(line)) {
                break;
            }
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                found = value.isEmpty() ? null : value;
            }
        }
        return found;
    }

    /**
     * 挑出这一轮推理输入里的<b>合成消息</b>（框架自己拼进去的，不是用户或模型写的），返回它们的类别。
     *
     * <p>典型来源是后台子 agent 的任务交付：{@code SubagentsMiddleware} 在下一轮推理开始前把
     * 已经终结的后台任务拼成一段 {@code <system-reminder>} 追加到对话末尾。它的 role 是 USER、
     * name 是 system、正文是一段 XML —— 在页面的"推理输入"里和用户消息长得几乎一样，
     * 不标出来就会让人误判"用户什么时候发了这段东西"。
     *
     * <p>判据用的是框架自己的元数据键（{@code Msg.METADATA_SYNTHETIC}），而不是"正文里有没有
     * {@code <system-reminder>} 标签"：前者是框架的正式契约，后者是渲染格式，框架换个标签
     * 就会静默失效。值可能是 Boolean 也可能是字符串形式，两种都认。
     */
    private static List<String> syntheticReminderKinds(List<Msg> msgs) {
        List<String> kinds = new ArrayList<>();
        for (Msg msg : msgs) {
            Map<String, Object> metadata = msg == null ? null : msg.getMetadata();
            if (metadata == null) {
                continue;
            }
            Object flag = metadata.get(Msg.METADATA_SYNTHETIC);
            boolean synthetic = Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
            if (!synthetic) {
                continue;
            }
            Object kind = metadata.get(Msg.METADATA_REMINDER_KIND);
            kinds.add(kind instanceof String text && !text.isBlank() ? text : "synthetic");
        }
        return kinds;
    }

    /**
     * 子 agent 派发节点的标题：{@code 子 agent 派发 · <目标agent> · <label>}。
     *
     * <p>为什么标题里不带派发方（父）agent 的名字：派发节点的父辈就是父 agent 的推理节点，
     * 再重复一遍父名只会挤占宽度；而"这次派发给了谁、是第几批"才是页面上一眼要能分辨的信息
     * ——9 个并发派发节点并排时，全靠这两段把彼此区分开。
     *
     * <p>目标 agent 拿不到时写"未知目标"而不是留空：留空会让标题变成"子 agent 派发 · · 第 3 批"，
     * 看起来像是排版出错；写明"未知目标"才能让人立刻意识到是入参缺失。
     */
    private static String subagentTitle(String targetAgent, String label) {
        StringBuilder title = new StringBuilder("子 agent 派发 · ")
                .append(targetAgent == null ? "未知目标" : targetAgent);
        if (label != null) {
            title.append(" · ").append(digest(label, LABEL_LIMIT));
        }
        return title.toString();
    }

    /**
     * 工具结果的落库形态。
     *
     * <p>dataBlocks 只记类型名：原始数据块体积不可控（一份表格可能几 MB），
     * 而这张表是常驻内存的，绝不能把任意大的对象塞进来。
     */
    private static Map<String, Object> toolResultOutput(ToolResultEndEvent end, String text,
                                                        List<String> dataBlocks) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("state", String.valueOf(end.getState()));
        output.put("resultLength", text.length());
        output.put("result", digest(text, TEXT_LIMIT));
        output.put("dataBlocks", dataBlocks);
        return output;
    }

    // ==================================================================================
    // 格式化工具 —— 把原始对象压成可落库、可给前端看的字符串
    // ==================================================================================

    /** 消息摘要：role / name / id / 文本，是排查“上下文里到底有什么”的最小可用信息。 */
    private static String describeMsg(Msg msg) {
        if (msg == null) {
            return "<null>";
        }
        return "role=" + msg.getRole() + " name=" + msg.getName() + " id=" + shortId(msg.getId())
                + " text=\"" + digest(msg.getTextContent(), TEXT_LIMIT) + "\"";
    }

    /** 模型采样参数摘要：排查“为什么输出这么短 / 这么随机”看这里。 */
    private static String describeOptions(GenerateOptions options) {
        if (options == null) {
            return "<null>";
        }
        return "model=" + options.getModelName()
                + " stream=" + options.getStream()
                + " temperature=" + options.getTemperature()
                + " topP=" + options.getTopP()
                + " maxTokens=" + options.getMaxTokens()
                + " maxCompletionTokens=" + options.getMaxCompletionTokens()
                + " reasoningEffort=" + options.getReasoningEffort()
                + " parallelToolCalls=" + options.getParallelToolCalls()
                + " toolChoice=" + options.getToolChoice();
    }

    private static String toolNames(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return "[]";
        }
        return tools.stream()
                .map(ToolSchema::getName)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * 这次 run 是哪种 agent 跑的，取值与前端 TraceRunInfo.agentKind 一致（react | harness）。
     *
     * <p>首选取装配期快照（理由见 {@link #agentKinds}）；没有登记过的 agent 才退回 instanceof，
     * 那条路对外壳无效，只能算兜底，绝不能当主判据。
     */
    private String agentKind(Agent agent) {
        if (agent != null) {
            String registered = agentKinds.get(agent.getName());
            if (registered != null) {
                return registered;
            }
        }
        return agent instanceof HarnessAgent
                ? AgentTraceRun.AGENT_KIND_HARNESS
                : AgentTraceRun.AGENT_KIND_REACT;
    }

    /** 工具入参是 Map，转成 JSON 便于阅读；序列化失败不能影响主流程，所以兜底成 toString。 */
    private static String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtil.stringify(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    /** 压平空白并截断：载荷在页面上是单行展示，过长还会把事件体积撑爆。 */
    private static String digest(String text, int max) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...(+" + (flat.length() - max) + ")";
    }

    /** 只留前 8 位，够区分又不占宽度。 */
    private static String shortId(String id) {
        if (id == null) {
            return "null";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
