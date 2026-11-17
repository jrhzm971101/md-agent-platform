package com.md.agentplatform.server.agent.middleware;

import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.AgentTraceEvent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行过程记录中间件测试。
 *
 * <p>断言的是使用者真正看到的东西 —— 落进 Registry 的 run 与事件流，而不是内部字段。
 * 2026-09-22 撤掉过程日志后，判据统一从"读日志"换成"读 Registry"：日志是给人扫的，
 * 断言它等于给日志格式上枷锁；Registry 的形态才是前端真正消费的数据。
 * 重点覆盖四条容易写错、线上会出事的契约：
 * 事件必须原样透传、error 信号不能被吞、per-run 状态不能跨 run 串、
 * 工具结果必须由 delta 拼出来（因为 ToolResultEndEvent 不带内容）。
 */
class AgentRunTraceMiddlewareTest {

    /**
     * 用真实的 Registry（纯内存实现，不需要 Spring）而不是 mock：
     * 这样测试断言的就是"各阶段确实落进了观测表"，与前端消费的是同一份数据。
     */
    private final AgentTraceRegistry traceRegistry = new AgentTraceRegistry();
    private final AgentRunTraceMiddleware middleware = new AgentRunTraceMiddleware(traceRegistry);
    private final Agent agent = mock(Agent.class);

    @BeforeEach
    void stubAgentName() {
        when(agent.getName()).thenReturn("t_agent");
    }

    // ==================== 透传与收口 ====================

    @Test
    void agentEventsPassThroughUnchangedAndRunStateLandsOnRuntimeContext() {
        RuntimeContext ctx = newContext("s1", "u1");
        List<AgentEvent> events = List.of(new AgentResultEvent(null), new ExceedMaxItersEvent("r1", 3, 3));

        List<AgentEvent> seen = middleware
                .onAgent(agent, ctx, new AgentInput(List.of()), in -> Flux.fromIterable(events))
                .collectList()
                .block();

        assertEquals(events, seen, "中间件不应增删或替换事件");
        assertNotNull(ctx.get("md.trace.runState"), "per-run 状态必须挂在 RuntimeContext 上");

        AgentTraceRun run = newestRun();
        assertEquals(AgentTraceRun.STATUS_DONE, run.getStatus());
        assertEquals("t_agent", run.getAgentName());
        assertEquals("s1", run.getSessionId());
        assertEquals("u1", run.getUserId());
        assertEquals(0, run.getInput().get("messageCount"), "顶层输入摘要要记下消息条数");

        // 收尾信息原先只出现在 RUN_END 日志里，现在只落在 run 的收口事件上
        AgentTraceEvent rootCompleted = rootCompletedOf(run);
        assertEquals("ON_COMPLETE", rootCompleted.output().get("signal"), "signal 记的是 Reactor 的终止信号名");
        assertEquals(2, run.getEvents().stream()
                .filter(event -> AgentTraceEvent.PHASE_AGENT.equals(event.phase())).count(),
                "根节点应有一对 STARTED / COMPLETED 事件");
        Map<?, ?> counts = (Map<?, ?>) rootCompleted.output().get("eventCounts");
        assertNotNull(counts, "事件计数必须随 run 落库，撤掉日志后就靠它判断有没有走到某一步");
        assertEquals(2, counts.values().stream().mapToInt(value -> ((Number) value).intValue()).sum(),
                "两个事件各计一次，实际: " + counts);
    }

    @Test
    void errorSignalIsRecordedAndNotSwallowed() {
        RuntimeContext ctx = newContext("s1", "u1");

        assertThrows(RuntimeException.class, () -> middleware
                .onAgent(agent, ctx, new AgentInput(List.of()), in -> Flux.error(new RuntimeException("boom")))
                .blockLast(), "中间件只观察，不得吞掉 error");

        AgentTraceRun run = newestRun();
        assertEquals(AgentTraceRun.STATUS_ERROR, run.getStatus(),
                "异常必须收口成 ERROR，而不是永远停在 RUNNING");
        assertEquals("RuntimeException: boom", run.getError());
        assertEquals("ON_ERROR", rootCompletedOf(run).output().get("signal"), "signal 记的是 Reactor 的终止信号名");
    }

    // ==================== token 计量（hook 时代拿不到） ====================

    @Test
    void modelCallTokenUsageIsAggregatedPerCallAndOntoTheRun() {
        RuntimeContext ctx = newContext("s1", "u1");

        middleware.onAgent(agent, ctx, new AgentInput(List.of()),
                in -> middleware.onModelCall(agent, ctx, modelCallInput(), x -> Flux.just(
                        new ModelCallEndEvent("r1", new ChatUsage(10, 20, 1.0)),
                        new ModelCallEndEvent("r2", new ChatUsage(20, 20, 2.0)))))
                .blockLast();

        AgentTraceRun run = newestRun();
        assertEquals(30, run.getInputTokens(), "两次模型调用的 inputTokens 应累加到 run 上");
        assertEquals(40, run.getOutputTokens(), "两次模型调用的 outputTokens 应累加到 run 上");
        assertEquals(0, run.getCachedTokens());
    }

    @Test
    void runsDoNotShareStateEvenThoughTheMiddlewareInstanceIsReused() {
        RuntimeContext first = newContext("s1", "u1");
        RuntimeContext second = newContext("s2", "u2");

        callModelOnce(first, new ChatUsage(10, 10, 1.0));
        callModelOnce(second, new ChatUsage(999, 999, 1.0));

        assertEquals(10, runBySession("s1").getInputTokens(),
                "第一个 run 只应累加自己的 10，说明状态没有落在实例字段里");
        assertEquals(999, runBySession("s2").getInputTokens(), "第二个 run 只应累加自己的 999");
    }

    // ==================== 调用方建议的 runId ====================

    /**
     * 上下文里建议的 runId 必须被采纳。
     *
     * <p>这条契约的另一端是页面对话：它先把 id 回执给前端（页面上"跳去执行链路"那个链接），
     * 之后才订阅事件流。如果中间件不用这个建议值、另生成一个，页面上那个链接就会指向一个
     * 不存在的详情页 —— 而且只有点下去才发现。
     */
    @Test
    void callerSuppliedRunIdBecomesTheRunId() {
        RuntimeContext ctx = newContext("s1", "u1");
        AgentTraceRegistry.attachRunId(ctx, "manual-abc-1");

        startRun(ctx);

        assertNotNull(traceRegistry.get("manual-abc-1"), "run 应当以建议的 id 落进 Registry");
        assertEquals("s1", traceRegistry.get("manual-abc-1").getSessionId());
    }

    /**
     * 同一个 id 被建议两次时，第二次必须换用现生成的 id。
     *
     * <p>放过它的后果不是"id 重复"这么轻：两条 run 在 Registry 里会互相覆盖，
     * 页面上看到的是两棵树糊成的一棵，比没有观测更误导人。
     */
    @Test
    void duplicateRunIdSuggestionFallsBackToAGeneratedId() {
        RuntimeContext first = newContext("s1", "u1");
        RuntimeContext second = newContext("s2", "u2");
        AgentTraceRegistry.attachRunId(first, "manual-dup");
        AgentTraceRegistry.attachRunId(second, "manual-dup");

        startRun(first);
        startRun(second);

        assertEquals(2, traceRegistry.size(), "两次调用应当是两条独立的 run");
        assertEquals("s1", traceRegistry.get("manual-dup").getSessionId(),
                "先到的那次保留建议的 id");
        AgentTraceRun fallback = runBySession("s2");
        assertNotEquals("manual-dup", fallback.getRunId(), "后到的那次必须换用现生成的 id");
        assertTrue(fallback.getRunId().matches("[0-9a-z]{8}-[0-9a-z]{6}"),
                "换用的 id 应当是生成器产出的形状，实际: " + fallback.getRunId());
    }

    /** 形状不合法的建议值要换掉：它同时会出现在 URL、数据库主键与事件响应里。 */
    @Test
    void illFormedRunIdSuggestionIsReplaced() {
        RuntimeContext ctx = newContext("s1", "u1");
        AgentTraceRegistry.attachRunId(ctx, "bad id/with space");

        startRun(ctx);

        assertEquals(1, traceRegistry.size());
        assertTrue(newestRun().getRunId().matches("[0-9a-z]{8}-[0-9a-z]{6}"),
                "不合法的建议值应当被换成现生成的 id，实际: " + newestRun().getRunId());
    }

    // ==================== 工具结果：End 事件不带内容，只能靠 delta 拼 ====================

    @Test
    void actingCollectsToolResultFromDeltaEventsBecauseEndEventCarriesNoPayload() {
        RuntimeContext ctx = newContext("s1", "u1");
        startRun(ctx);

        ActingInput acting = new ActingInput(List.of(
                new ToolUseBlock("call-1", "smartsheet_records_list", Map.of("sheet", "MD-timesheet-new"))));

        middleware.onActing(agent, ctx, acting, in -> Flux.just(
                        new ToolResultStartEvent("r1", "call-1", "smartsheet_records_list"),
                        new ToolResultTextDeltaEvent("r1", "call-1", "smartsheet_records_list", "{\"rows\":["),
                        new ToolResultTextDeltaEvent("r1", "call-1", "smartsheet_records_list", "{\"name\":\"张三\"}]}"),
                        new ToolResultEndEvent("r1", "call-1", "smartsheet_records_list", ToolResultState.SUCCESS)))
                .collectList()
                .block();

        AgentTraceRun run = newestRun();
        AgentTraceEvent started = startedEvent(run, AgentTraceEvent.PHASE_ACTING);
        // 入参：工具名 + 完整参数。只有请求侧才有 —— End 事件里连工具名都可能是 null
        assertEquals("smartsheet_records_list", started.input().get("tool"));
        assertTrue(String.valueOf(started.input().get("args")).contains("MD-timesheet-new"),
                "工具入参应落在开始事件的 input 上，实际: " + started.input());

        // 出参：内容来自两个 delta 的拼接，End 事件自己只有 state —— 这就是必须攒 delta 的原因
        AgentTraceEvent completed = completedEvent(run, started.seq());
        assertEquals("SUCCESS", completed.output().get("state"));
        assertEquals(24, completed.output().get("resultLength"));
        assertEquals("{\"rows\":[{\"name\":\"张三\"}]}", completed.output().get("result"));
    }

    // ==================== 管道型 stage ====================

    @Test
    void systemPromptIsReturnedUnchanged() {
        RuntimeContext ctx = newContext("s1", "u1");
        startRun(ctx);
        String prompt = "you are a timesheet auditor";

        assertEquals(prompt, middleware.onSystemPrompt(agent, ctx, prompt).block());
        assertEquals(prompt, newestRun().getSystemPrompt(), "系统提示词是 run 级属性，应落在 run 上");
    }

    /**
     * 子 agent 的系统提示词不能覆盖 run 表上那一列。
     *
     * <p>现场（2026-09-21，真库）：`deployment_agent` 的 run 行里，system_prompt 存的是子 agent
     * （Kubernetes 组件检查 / Nacos 配置中心管理）的提示词，而 agent_name 还是父 agent。
     * 原因是这个 stage 对树里的每个 agent 都会被调用，而父与子共用同一个 RunState ——
     * 谁最后说话谁说了算。
     */
    @Test
    void childAgentSystemPromptDoesNotOverwriteTheRunPrompt() {
        RuntimeContext ctx = newContext("s1", "u1");
        Agent child = mock(Agent.class);
        when(child.getName()).thenReturn("t_child");

        // 父 agent 开始但故意不结束：ctx 上留下一个"进行中"的 RunState（子 agent 的真实处境）
        Disposable parent = middleware
                .onAgent(agent, ctx, new AgentInput(List.of()), in -> Flux.never())
                .subscribe();
        middleware.onSystemPrompt(agent, ctx, "father prompt").block();

        // 子 agent 进入同一个 ctx，并带上它自己的系统提示词
        middleware.onAgent(child, ctx, new AgentInput(List.of()), in -> Flux.empty()).blockLast();
        middleware.onSystemPrompt(child, ctx, "child prompt").block();

        assertEquals("father prompt", newestRun().getSystemPrompt(),
                "run 级的系统提示词只能是最外层 agent 的，子 agent 不许覆盖");

        parent.dispose();
    }

    // ==================== 落进观测表（Step 0 接线的核心契约） ====================

    @Test
    void stagesAreRecordedIntoTheRegistryNotJustLogged() {
        RuntimeContext ctx = newContext("s1", "u1");

        middleware.onAgent(agent, ctx, new AgentInput(List.of()),
                        // 本轮迭代里"想"的那一步：刻意把 onModelCall 包进 onReasoning，
                        // 才能验证"MODEL_CALL 折叠进 REASONING"这条契约 —— 用量是在推理收口时被带走的。
                        in -> middleware.onReasoning(agent, ctx, reasoningInput(),
                                        x -> middleware.onModelCall(agent, ctx, modelCallInput(),
                                                y -> Flux.just(new ModelCallEndEvent("r1", new ChatUsage(10, 20, 1.0)))))
                                .thenMany(middleware.onActing(agent, ctx,
                                        new ActingInput(List.of(new ToolUseBlock("c1", "doc_get",
                                                Map.of("id", "x")))),
                                        y -> Flux.just(
                                                new ToolResultStartEvent("r1", "c1", "doc_get"),
                                                new ToolResultTextDeltaEvent("r1", "c1", "doc_get", "hello"),
                                                new ToolResultEndEvent("r1", "c1", "doc_get",
                                                        ToolResultState.SUCCESS))))
                                .thenMany(Flux.empty()))
                .blockLast();

        assertEquals(1, traceRegistry.size(), "一轮调用只应产生一个 run");
        AgentTraceRun run = newestRun();

        // 事件模型下"阶段"不再是一条记录，而是一对事件（STARTED + COMPLETED），
        // 所以这里改成从事件流里取 phase 再 distinct。
        List<String> phases = run.getEvents().stream()
                .map(AgentTraceEvent::phase).distinct().toList();
        assertTrue(phases.contains("AGENT"), "缺少 run 级 AGENT 事件，实际: " + phases);
        assertTrue(phases.contains("REASONING"), "缺少 REASONING 事件，实际: " + phases);
        assertTrue(phases.contains("ACTING"), "缺少 ACTING 事件，实际: " + phases);
        // MODEL_CALL 刻意不产生事件（折叠进 REASONING）：seq 与输出一一对应，占号就必须输出，
        // 否则事件流会留下永远补不上的空洞，前端会卡在空洞之前不再渲染。这条断言是折叠契约的守门人。
        assertFalse(phases.contains("MODEL_CALL"), "MODEL_CALL 不应产生事件，实际: " + phases);

        // token 既累加到 run 上（顶层汇总），也由推理节点在收口事件里带走（逐轮核对），两处用途不同
        assertEquals(10, run.getInputTokens(), "run 级 inputTokens 应由 MODEL_CALL 累加");
        assertEquals(20, run.getOutputTokens(), "run 级 outputTokens 应由 MODEL_CALL 累加");
        assertEquals(AgentTraceRun.STATUS_DONE, run.getStatus(), "正常结束应记为 DONE");

        // 新模型的核心差异：入参只写在 STARTED 事件上、出参只写在 COMPLETED 事件上，两者靠 stageSeq 配对。
        // 工具入参来自请求侧，结果来自 delta 拼接 —— 这两条是 ACTING 必须成立的前提。
        AgentTraceEvent actingStarted = run.getEvents().stream()
                .filter(event -> AgentTraceEvent.PHASE_ACTING.equals(event.phase()))
                .filter(event -> !event.isTerminal())
                .findFirst().orElseThrow();
        assertEquals("doc_get", actingStarted.input().get("tool"));

        AgentTraceEvent actingCompleted = run.getEvents().stream()
                .filter(event -> AgentTraceEvent.PHASE_ACTING.equals(event.phase()))
                .filter(AgentTraceEvent::isTerminal)
                .filter(event -> event.stageSeq() == actingStarted.seq())
                .findFirst().orElseThrow();
        assertEquals("hello", ((Map<?, ?>) actingCompleted.output()).get("result"));

        // 折叠生效的直接证据：推理节点的收口事件里带上了本轮模型调用的模型名与 token 合计。
        AgentTraceEvent reasoningCompleted = run.getEvents().stream()
                .filter(event -> AgentTraceEvent.PHASE_REASONING.equals(event.phase()))
                .filter(AgentTraceEvent::isTerminal)
                .findFirst().orElseThrow();
        assertEquals("-", reasoningCompleted.output().get("model"),
                "入参里没有模型名时应折叠成 -");
        assertEquals(30L, reasoningCompleted.output().get("totalTokens"),
                "推理收口事件应带上本轮模型调用折叠进来的 token 合计");
    }

    @Test
    void childAgentReusesParentRunInsteadOfStealingItsRunId() {
        RuntimeContext ctx = newContext("s1", "u1");
        Agent child = mock(Agent.class);
        when(child.getName()).thenReturn("t_child");

        // 父 agent 开始但故意不结束：ctx 上留下一个"进行中"的 RunState。
        // 这正是旧实现出事的现场 —— 子 agent 进入时父 run 还在跑。
        Disposable parent = middleware
                .onAgent(agent, ctx, new AgentInput(List.of()), in -> Flux.never())
                .subscribe();
        String parentRunId = newestRun().getRunId();

        // 子 agent 进入：同一个 middleware 实例、同一个 RuntimeContext
        middleware.onAgent(child, ctx, new AgentInput(List.of()), in -> Flux.empty()).blockLast();

        assertEquals(1, traceRegistry.size(), "子 agent 不应另开一个 run");
        assertEquals(parentRunId, newestRun().getRunId(), "父 runId 不能被子 agent 顶掉");
        assertEquals(AgentTraceRun.STATUS_RUNNING, newestRun().getStatus(),
                "子 agent 结束不能把整轮 run 标成完成 —— 父 agent 还在跑");

        parent.dispose();
    }

    @Test
    void aFinishedRunOnTheSameContextStartsANewRun() {
        RuntimeContext ctx = newContext("s1", "u1");

        middleware.onAgent(agent, ctx, new AgentInput(List.of()), in -> Flux.empty()).blockLast();
        String firstRunId = newestRun().getRunId();

        // 同一个 ctx 再发起一次。上一次已收口，必须开新 run：
        // 否则第二次会被并进已结束的 run，从此永不 close、页面上一路显示"进行中"。
        middleware.onAgent(agent, ctx, new AgentInput(List.of()), in -> Flux.empty()).blockLast();

        assertEquals(2, traceRegistry.size(), "已收口的 run 不应被复用");
        assertNotEquals(firstRunId, newestRun().getRunId(), "第二次调用应产生新的 runId");
    }

    // ==================== agent 类型判定 ====================

    /**
     * 登记为 harness 的 agent，其 run 必须记成 harness —— 而这件事<b>不可能</b>靠回调参数判断出来。
     *
     * <p>框架侧 HarnessAgent.Builder#middleware(mw) 把业务中间件转发给内层 ReActAgent
     * （{@code inner = ReActAgent.builder()}），所以 onAgent 收到的永远是 ReActAgent，
     * instanceof HarnessAgent 恒为 false。这里刻意用只 mock 了 getName 的普通 Agent
     * 复现同一个现场：类型只能来自装配期登记。
     */
    @Test
    void registeredHarnessKindWinsOverTheInstanceCheck() {
        middleware.registerAgentKind("t_agent", AgentTraceRun.AGENT_KIND_HARNESS);

        startRun(newContext("s1", "u1"));

        assertEquals(AgentTraceRun.AGENT_KIND_HARNESS, newestRun().getAgentKind(),
                "装配期登记为 harness 的 agent，其 run 不能被记成 react");
    }

    /** 没登记过的 agent 退回旧行为（react）：登记表缺失时页面至少要拿得到一个合法值。 */
    @Test
    void unregisteredAgentFallsBackToReact() {
        startRun(newContext("s1", "u1"));

        assertEquals(AgentTraceRun.AGENT_KIND_REACT, newestRun().getAgentKind());
    }

    /** 类型在 run 建立那一刻冻结：之后改登记（等价于在页面上改定义）不能改写已经发生的历史。 */
    @Test
    void kindIsFrozenAtRunStart() {
        middleware.registerAgentKind("t_agent", AgentTraceRun.AGENT_KIND_REACT);
        startRun(newContext("s1", "u1"));

        middleware.registerAgentKind("t_agent", AgentTraceRun.AGENT_KIND_HARNESS);

        assertEquals(AgentTraceRun.AGENT_KIND_REACT, newestRun().getAgentKind(),
                "已建立的 run 上的类型是历史事实，不应随登记变化");
    }

    // ==================== 子 agent 派发（Step 5） ====================

    /**
     * 同一批里混着普通工具与子 agent 派发时，两者必须落到各自的阶段。
     *
     * <p>现场（2026-09-23 真库 run mub3rqvg-esxkhw，528 条事件）：harness 父 agent 用 agent_spawn
     * 扇出 9 个子 agent，库里 1132 条 ACTING 事件里混着 9 条工具名为 agent_spawn 的记录 ——
     * 页面上它们和普通工具长得一模一样，完全看不出这里派发过一层子 agent。派发必须自成一个阶段。
     */
    @Test
    void actingSplitsSubagentDispatchFromOrdinaryToolsInTheSameBatch() {
        RuntimeContext ctx = newContext("s1", "u1");
        startRun(ctx);

        ActingInput acting = new ActingInput(List.of(
                new ToolUseBlock("c1", "doc_get", Map.of("id", "x")),
                new ToolUseBlock("c2", "agent_spawn", Map.of("task", "第 5 片执行者",
                        "label", "roster-reader-3", "agent_id", "workwx_doc_agent"))));

        middleware.onActing(agent, ctx, acting, in -> Flux.just(
                        new ToolResultStartEvent("r1", "c1", "doc_get"),
                        new ToolResultTextDeltaEvent("r1", "c1", "doc_get", "hello"),
                        new ToolResultEndEvent("r1", "c1", "doc_get", ToolResultState.SUCCESS),
                        new ToolResultStartEvent("r1", "c2", "agent_spawn"),
                        new ToolResultTextDeltaEvent("r1", "c2", "agent_spawn", "session_id: sub-bc425729"),
                        new ToolResultEndEvent("r1", "c2", "agent_spawn", ToolResultState.SUCCESS)))
                .collectList()
                .block();

        AgentTraceRun run = newestRun();

        // 普通工具照旧走 ACTING：工具名与结果口径都不变，老数据不需要回填
        AgentTraceEvent toolStarted = startedEvent(run, AgentTraceEvent.PHASE_ACTING);
        assertEquals("doc_get", toolStarted.input().get("tool"));
        assertEquals("hello", completedEvent(run, toolStarted.seq()).output().get("result"));

        // 派发改走 SUBAGENT：标题给出目标 agent 与批次 label，input 里再留一份 targetAgent 供成树方当 source
        AgentTraceEvent dispatchStarted = startedEvent(run, AgentTraceEvent.PHASE_SUBAGENT);
        assertEquals("子 agent 派发 · workwx_doc_agent · roster-reader-3", dispatchStarted.title());
        assertEquals("workwx_doc_agent", dispatchStarted.input().get("targetAgent"));
        assertEquals("roster-reader-3", dispatchStarted.input().get("label"));
        assertEquals("SUCCESS", completedEvent(run, dispatchStarted.seq()).output().get("state"));

        // 分流是"按工具名各归各位"，不是一个批次整体改阶段。阶段名同时出现在 STARTED 与 COMPLETED 上，
        // 所以这里数出来的是"一对"事件（2 条），而不是 1 条。
        assertEquals(2, run.getEvents().stream()
                .filter(event -> AgentTraceEvent.PHASE_ACTING.equals(event.phase())).count(),
                "同批的普通工具仍应落在 ACTING（STARTED + COMPLETED 两条）");
        assertEquals(2, run.getEvents().stream()
                .filter(event -> AgentTraceEvent.PHASE_SUBAGENT.equals(event.phase())).count(),
                "派发应有一对 SUBAGENT 事件（STARTED + COMPLETED）");
    }

    /**
     * 轮次必须按 agent 分桶 —— 一批子 agent 是并发进入 onReasoning 的。
     *
     * <p>现场（2026-09-23 真库 run mub3rqvg-esxkhw）：9 个并发子 agent 的推理标题变成
     * "第 20 / 24 / 22 / 25 / 23 / 26 / 27 / 21 / 28 轮推理 · workwx_doc_agent" ——
     * 轮次乱序，因为它们共用一个整轮计数器在抢号。分桶之后每个 agent 各自从 1 开始。
     */
    @Test
    void iterationNumbersAreBucketedPerAgentSoConcurrentChildrenDoNotStealEachOthersNumbers() {
        RuntimeContext ctx = newContext("s1", "u1");
        Agent child = mock(Agent.class);
        when(child.getName()).thenReturn("t_child");

        // 父 → 子 → 父：正是"父派发子、子自己跑了一轮、父再跑一轮"的时序
        middleware.onAgent(agent, ctx, new AgentInput(List.of()), in ->
                        middleware.onReasoning(agent, ctx, reasoningInput(), x -> Flux.empty())
                                .thenMany(middleware.onReasoning(child, ctx, reasoningInput(), x -> Flux.empty()))
                                .thenMany(middleware.onReasoning(agent, ctx, reasoningInput(), x -> Flux.empty())))
                .blockLast();

        AgentTraceRun run = newestRun();
        List<String> reasoningTitles = run.getEvents().stream()
                .filter(event -> AgentTraceEvent.PHASE_REASONING.equals(event.phase()))
                .filter(event -> !event.isTerminal())
                .map(AgentTraceEvent::title).toList();

        assertEquals(List.of("第 1 轮推理 · t_agent", "第 1 轮推理 · t_child", "第 2 轮推理 · t_agent"),
                reasoningTitles, "轮次按 agent 分桶：子 agent 从 1 开始，而不是接着父 agent 抢号");
        assertEquals(3, rootCompletedOf(run).output().get("iterations"),
                "run 级 iterations 是各 agent 分桶之和（父 2 轮 + 子 1 轮）");
    }

    // ==================== 子 agent 同步 / 异步（Step 6） ====================

    /**
     * 每个阶段都要带上"自己属于哪个子会话"。
     *
     * <p>这是成树方精确归位的唯一依据（见 {@code AgentRunEventTranslator#sessionParentOf}）：
     * 框架给每个子实例发的 ctx 是 {@code builder(parentCtx).sessionId("sub-…")}，子 agent 的所有
     * 回调都带这个值，所以"这条事件属于哪个子 agent"从数据上就是确定的 ——
     * 不需要（也不能）靠时间区间去猜。
     */
    @Test
    void everyStageCarriesTheSessionIdSoSubagentSubtreesCanBeAttributed() {
        RuntimeContext ctx = newContext("s1", "u1");
        startRun(ctx);

        middleware.onReasoning(agent, ctx, reasoningInput(), x -> Flux.empty()).blockLast();
        middleware.onActing(agent, ctx, new ActingInput(List.of(
                        new ToolUseBlock("c1", "doc_get", Map.of("id", "x")))),
                in -> Flux.just(new ToolResultEndEvent("r1", "c1", "doc_get", ToolResultState.SUCCESS)))
                .blockLast();

        AgentTraceRun run = newestRun();
        assertEquals("s1", startedEvent(run, AgentTraceEvent.PHASE_REASONING).input().get("agentSession"));
        assertEquals("s1", startedEvent(run, AgentTraceEvent.PHASE_ACTING).input().get("agentSession"));
    }

    /**
     * 框架注入的合成消息（后台任务交付的 {@code <system-reminder>}）必须在推理入参里被标出来。
     *
     * <p>它的 role 是 USER、正文是一段 XML，在页面的"推理输入"里和用户消息长得一样 ——
     * 不标出来就会让人误以为"用户什么时候发了这段东西"。判据用框架自己的元数据键
     * （{@code Msg.METADATA_SYNTHETIC}）而不是正文里的标签，后者是渲染格式，会随框架变形。
     */
    @Test
    void syntheticRemindersInTheReasoningInputAreFlagged() {
        RuntimeContext ctx = newContext("s1", "u1");
        startRun(ctx);

        Msg delivery = Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder()
                        .text("<system-reminder>\n1 background subagent task has completed…\n</system-reminder>")
                        .build())
                .metadata(Map.of(Msg.METADATA_SYNTHETIC, true,
                        Msg.METADATA_REMINDER_KIND, "task_delivery"))
                .build();
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("帮我看下工时").build();

        middleware.onReasoning(agent, ctx, new ReasoningInput(List.of(userMsg, delivery), List.of(), null),
                x -> Flux.empty()).blockLast();

        assertEquals(List.of("task_delivery"),
                startedEvent(newestRun(), AgentTraceEvent.PHASE_REASONING).input().get("syntheticReminders"),
                "只有带 synthetic 标记的那条才算框架注入，普通用户消息不能被算进去");
    }

    /**
     * 派发结果里的 {@code status} / {@code task_id} / {@code session_id} 必须被解析出来。
     *
     * <p>这三样是页面区分"同步等回来了"与"丢到后台去了"的全部依据。两个坑都在这个用例里守着：
     * ① 结果头里可能先出现一行 {@code status: exposed (…)}（spawnInfo 的旁注），
     * 真正的结论在末尾 —— 所以必须取<b>最后</b>一条；
     * ② {@code reply:} 之后是子 agent 的原样回答，里面完全可能再出现 {@code status: ok} 这种行 ——
     * 所以碰到 {@code reply:} 必须停下来。
     */
    @Test
    void dispatchResultHeaderIsParsedIntoStatusTaskIdAndChildSession() {
        RuntimeContext ctx = newContext("s1", "u1");
        startRun(ctx);

        String result = String.join("\n",
                "agent_key: agent:workwx_doc_agent:abc",
                "agent_id: workwx_doc_agent",
                "session_id: sub-bc425729",
                "status: exposed (user can send messages directly via subagent_id)",
                "status: timeout_promoted",
                "task_id: task_7f3a",
                "The task exceeded the 30s sync timeout but is still running in the background.",
                "",
                "reply:",
                "status: ok",
                "这里是子 agent 的回答正文");

        middleware.onActing(agent, ctx,
                        new ActingInput(List.of(new ToolUseBlock("c2", "agent_spawn",
                                Map.of("task", "第 5 片执行者", "label", "roster-reader-3",
                                        "agent_id", "workwx_doc_agent")))),
                        in -> Flux.just(
                                new ToolResultStartEvent("r1", "c2", "agent_spawn"),
                                new ToolResultTextDeltaEvent("r1", "c2", "agent_spawn", result),
                                new ToolResultEndEvent("r1", "c2", "agent_spawn", ToolResultState.SUCCESS)))
                .collectList()
                .block();

        AgentTraceRun run = newestRun();
        AgentTraceEvent dispatch = startedEvent(run, AgentTraceEvent.PHASE_SUBAGENT);
        Map<?, ?> output = completedEvent(run, dispatch.seq()).output();

        assertEquals("timeout_promoted", output.get("dispatchStatus"),
                "要取最后一条 status（末尾的结论），且不能被 reply 正文里的 status 带偏");
        assertEquals("task_7f3a", output.get("taskId"));
        assertEquals("sub-bc425729", output.get("childSession"));
    }

    /**
     * 真实世界的形态：内置工具递出来的结果文本是<b>被 JSON 编码过一遍</b>的字符串字面量。
     *
     * <p>这条是 2026-09-28 真机联调发现的（run muktpso3-257fe2）：本地手写用例用的是"真正的多行文本"，
     * 于是解析一路绿灯；真库里的文本却是 {@code "agent_key: …\nagent_id: …\nstatus: accepted\n…"}
     * —— 带一对引号、换行是字面量 {@code \n} 两个字符。按 {@code \n} 切行只会得到一整行，
     * 派发结论全部落成 null，而且不报任何错：页面上"后台派发"又被画成"同步成功"。
     *
     * <p>断言两件事：① 引号 + 字面量 {@code \n} 的形态照样能解析出结论；
     * ② 落库的 result 里不再残留字面量 {@code \n}（digest 会把还原出来的真换行折成空格，
     * 页面上的"输出"面板因此是一句通顺的文本，而不是一串 {@code \n}）。
     */
    @Test
    void jsonQuotedDispatchResultIsDecodedBeforeItIsParsedAndStored() {
        RuntimeContext ctx = newContext("s1", "u1");
        startRun(ctx);

        String plain = String.join("\n",
                "agent_key: agent:t_child:abc",
                "agent_id: t_child",
                "session_id: sub-abc",
                "status: accepted",
                "task_id: task_1",
                "Use task_output(task_id='task_1') to fetch the result when ready.");
        // 框架递出来的就是这个形态：整体被 JSON 编码成一个字符串字面量
        String quoted = "\"" + plain.replace("\n", "\\n") + "\"";

        middleware.onActing(agent, ctx,
                        new ActingInput(List.of(new ToolUseBlock("c3", "agent_spawn",
                                Map.of("task", "跑个长任务", "agent_id", "t_child")))),
                        in -> Flux.just(
                                new ToolResultStartEvent("r1", "c3", "agent_spawn"),
                                new ToolResultTextDeltaEvent("r1", "c3", "agent_spawn", quoted),
                                new ToolResultEndEvent("r1", "c3", "agent_spawn", ToolResultState.SUCCESS)))
                .collectList()
                .block();

        AgentTraceRun run = newestRun();
        AgentTraceEvent dispatch = startedEvent(run, AgentTraceEvent.PHASE_SUBAGENT);
        Map<?, ?> output = completedEvent(run, dispatch.seq()).output();

        assertEquals("accepted", output.get("dispatchStatus"), "JSON 编码过的结果文本也要能解析出结论");
        assertEquals("task_1", output.get("taskId"));
        assertEquals("sub-abc", output.get("childSession"));
        // digest 把还原出来的真换行折成空格（见 digest 的实现），所以这里比的是折行后的文本
        assertEquals(plain.replace("\n", " "), output.get("result"),
                "落库的 result 应当是还原后再折行的文本");
        assertFalse(String.valueOf(output.get("result")).contains("\\n"), "不能再残留字面量 \\n");
        assertFalse(String.valueOf(output.get("result")).startsWith("\""), "落库的 result 不该还带着包裹引号");
    }

    /**
     * 异步子 agent 在父 run 收口之后才开跑时，新开的 run 必须记下"父是谁、对应哪个后台任务"。
     *
     * <p>这是页面上"一条孤立链路"能指回父链路的唯一线索。父 run 已经收口，中间件只能新开一条 run，
     * 此时父 runId 从那个已收口的 RunState 上取，task_id 从父子共享的映射里取
     * （派发结果解析出来的那一刻记进去的）。
     */
    @Test
    void asyncChildRunOpenedAfterTheParentFinishedRemembersItsParentAndTask() {
        RuntimeContext parentCtx = newContext("s1", "u1");
        Agent child = mock(Agent.class);
        when(child.getName()).thenReturn("t_child");

        // 父 run：派发一个后台任务（timeout_seconds=0 → status: accepted + task_id），然后收口
        String dispatchResult = String.join("\n",
                "agent_key: agent:t_child:abc",
                "agent_id: t_child",
                "session_id: sub-abc",
                "status: accepted",
                "task_id: task_1");
        middleware.onAgent(agent, parentCtx, new AgentInput(List.of()), in ->
                        middleware.onActing(agent, parentCtx,
                                new ActingInput(List.of(new ToolUseBlock("c2", "agent_spawn",
                                        Map.of("task", "跑个长任务", "agent_id", "t_child")))),
                                x -> Flux.just(
                                        new ToolResultStartEvent("r1", "c2", "agent_spawn"),
                                        new ToolResultTextDeltaEvent("r1", "c2", "agent_spawn", dispatchResult),
                                        new ToolResultEndEvent("r1", "c2", "agent_spawn",
                                                ToolResultState.SUCCESS))))
                .blockLast();
        String parentRunId = newestRun().getRunId();

        // 父 run 已收口。子 agent 的 ctx 由框架 builder(parentCtx).sessionId(子会话) 派生 —— 照抄这个形状。
        RuntimeContext childCtx = RuntimeContext.builder(parentCtx).sessionId("sub-abc").build();
        middleware.onAgent(child, childCtx, new AgentInput(List.of()), in -> Flux.empty()).blockLast();

        AgentTraceRun childRun = newestRun();
        assertNotEquals(parentRunId, childRun.getRunId(), "子 agent 不能在已收口的父 run 上继续写");
        assertEquals(parentRunId, childRun.getParentRunId(), "新开的 run 必须记下派发它的父 run");
        assertEquals("task_1", childRun.getTaskId(), "后台任务号要跟着写进 run 行");
    }

    /**
     * 真正的全新调用（ctx 上没有 RunState）不该被误记成子链路。
     *
     * <p>反方向的守门：如果判据写成"只要 ctx 上有 RunState 就拿它当父"，那么所有正常调用都会
     * 凭空多出一个 parent_run_id，页面上到处是"回到父链路"的死链。
     */
    @Test
    void aFreshCallHasNoParentRun() {
        startRun(newContext("s1", "u1"));

        assertNull(newestRun().getParentRunId());
        assertNull(newestRun().getTaskId());
    }

    // ==================== 辅助 ====================

    /** Registry 里最新的那个 run（页面列表的第一行）。 */
    private AgentTraceRun newestRun() {
        List<AgentTraceRun> runs = traceRegistry.recent(10);
        assertFalse(runs.isEmpty(), "Registry 里没有任何 run");
        return runs.get(0);
    }

    private void startRun(RuntimeContext ctx) {
        middleware.onAgent(agent, ctx, new AgentInput(List.of()), in -> Flux.empty()).blockLast();
    }

    private static RuntimeContext newContext(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    private static ModelCallInput modelCallInput() {
        return new ModelCallInput(List.of(), List.of(), null, null);
    }

    /** 空的推理入参：本轮没有上下文、没有可见工具、没有采样参数，用于验证折叠契约。 */
    private static ReasoningInput reasoningInput() {
        return new ReasoningInput(List.of(), List.of(), null);
    }

    /** 在一个完整的 run 里跑一次模型调用 —— token 只有在 run 收口时才会汇总到 run 上。 */
    private void callModelOnce(RuntimeContext ctx, ChatUsage usage) {
        middleware.onAgent(agent, ctx, new AgentInput(List.of()),
                        in -> middleware.onModelCall(agent, ctx, modelCallInput(),
                                x -> Flux.just(new ModelCallEndEvent("r", usage))))
                .blockLast();
    }

    /** 按 sessionId 找 run —— 用来断言两次调用各自的状态没有互相污染。 */
    private AgentTraceRun runBySession(String sessionId) {
        for (AgentTraceRun run : traceRegistry.recent(10)) {
            if (sessionId.equals(run.getSessionId())) {
                return run;
            }
        }
        throw new AssertionError("Registry 里没有 session=" + sessionId + " 的 run");
    }

    /** run 的根节点收口事件 —— 顶层结果（result / signal / eventCounts）都挂在它的 output 上。 */
    private static AgentTraceEvent rootCompletedOf(AgentTraceRun run) {
        return run.getEvents().stream()
                .filter(event -> AgentTraceEvent.PHASE_AGENT.equals(event.phase()))
                .filter(AgentTraceEvent::isTerminal)
                .findFirst()
                .orElseThrow(() -> new AssertionError("run 没有收口事件: " + run.getRunId()));
    }

    /** 某个阶段的第一条开始事件。 */
    private static AgentTraceEvent startedEvent(AgentTraceRun run, String phase) {
        return run.getEvents().stream()
                .filter(event -> phase.equals(event.phase()))
                .filter(event -> !event.isTerminal())
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 " + phase + " 的开始事件"));
    }

    /** 与某个开始事件配对的收口事件。 */
    private static AgentTraceEvent completedEvent(AgentTraceRun run, int stageSeq) {
        return run.getEvents().stream()
                .filter(AgentTraceEvent::isTerminal)
                .filter(event -> event.stageSeq() == stageSeq)
                .findFirst()
                .orElseThrow(() -> new AssertionError("阶段 " + stageSeq + " 没有收口事件"));
    }
}
