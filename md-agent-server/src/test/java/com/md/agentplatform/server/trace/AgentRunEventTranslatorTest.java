package com.md.agentplatform.server.trace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件 → 前端事件流的翻译测试（纯函数，不起 Spring）。
 *
 * <p>这里守的是<b>跨端契约</b>，不是内部实现：seq 是否与写入一一对应、节点是否成对、path 是否可用、
 * 词表是否闭集、进行中的阶段是否可见。这几条任意一条破了，页面的表现分别是"永远转圈"、
 * "节点整段消失"、"树长不出来"、"渲染时抛异常"、"看不到正在跑的那一步" ——
 * 全是编译期看不见、后端日志里也不报错的类型。
 *
 * <h2>与旧版测试的关键差别</h2>
 * 旧版喂进来的是 {@code AgentTraceStep}（一个阶段一条，含开始 + 耗时 + 进出参），翻译器负责
 * 把它拆成 started / completed 两条事件<b>并现场编号</b>。现在拆条与编号都前移到了写入侧
 * （见 {@link AgentTraceEvent} 的类注释），所以：
 * <ul>
 *   <li>喂进来的是事件序列，<b>顺序就是真实发生顺序</b>（推理先开始 → 工具跑 → 工具先结束 → 推理后结束）；</li>
 *   <li>seq 由测试自己按写入顺序分配，翻译器只做透传 —— 因此"seq 不因新事件到达而位移"
 *       是这里最该守的一条；</li>
 *   <li>MODEL_CALL 不再产生事件（被折叠进 REASONING），所以没有"折叠"相关的用例了 ——
 *       那件事现在由中间件负责，测试见 AgentRunTraceMiddlewareTest。</li>
 * </ul>
 */
class AgentRunEventTranslatorTest {

    @Test
    void nakedRunProducesRootStartedAndRootCompletedOnly() {
        AgentTraceRun run = runningRun();
        finishRun(run, AgentTraceRun.STATUS_DONE, 10, 5, 0, null, Map.of("iterations", 1));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.started", "run.completed");
        assertThat(events).extracting(AgentRunEvent::path).containsExactly("1", "1");
        assertThat(events).extracting(AgentRunEvent::parentPath).containsOnlyNulls();
        assertThat(events).extracting(AgentRunEvent::kind).containsExactly("agent", "agent");
        assertThat(events).extracting(AgentRunEvent::status).containsExactly("run", "ok");
        // 开始事件没有耗时、没有令牌；结束事件才带整轮耗时
        assertThat(events.get(0).durationMs()).isNull();
        assertThat(events.get(0).payload().input()).containsEntry("messageCount", 1);
        assertThat(events.get(1).durationMs()).isNotNull();
        // 根节点不带 tokens：前端会把所有事件的 tokens 求和，根节点再报一次就翻倍
        assertThat(events.get(1).tokens()).isNull();
        assertThat(events.get(1).payload().metrics()).containsEntry("inputTokens", 10);
    }

    @Test
    void runningRunHasNoTerminalEventSoTheRootStaysOpen() {
        AgentTraceRun run = runningRun();

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::type).containsExactly("run.started");
        assertThat(events.get(0).status()).isEqualTo(AgentRunEvent.STATUS_RUN);
    }

    @Test
    void failedRunEndsWithErrorRaisedCarryingTheReason() {
        AgentTraceRun run = runningRun();
        finishRun(run, AgentTraceRun.STATUS_ERROR, 10, 5, 0, "RuntimeException: boom", Map.of());

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.started", "error.raised");
        assertThat(events.get(1).status()).isEqualTo(AgentRunEvent.STATUS_ERR);
        assertThat(events.get(1).payload().note()).isEqualTo("RuntimeException: boom");
    }

    /**
     * 本次改造的核心用例：事件流的发射顺序 = 事件真实发生的顺序。
     *
     * <p>旧模型把同一节点的两条事件紧挨着发（step 是"一个整体"）；新模型按到达顺序发，
     * 于是"工具先结束、推理后结束"会交错体现出来 —— 这正是增量推送要看的东西。
     */
    @Test
    void eventsAreEmittedInTheOrderTheyHappened() {
        AgentTraceRun run = runningRun();
        int reasoningStage = startStage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent",
                5, Map.of("iteration", 1));
        int toolStage = startStage(run, AgentTraceEvent.PHASE_ACTING, "工具调用 · timesheet_list",
                1010, Map.of("tool", "timesheet_list"));
        endStage(run, toolStage, AgentTraceEvent.PHASE_ACTING, "工具调用 · timesheet_list",
                1010, 500, Map.of("state", "SUCCESS"));
        endStage(run, reasoningStage, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent",
                5, 1000, reasoningOutput());
        finishRun(run, AgentTraceRun.STATUS_DONE, 120, 30, 0, null, Map.of("iterations", 1));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        // seq 从 1 开始连续 —— 前端的 mergeContiguousEvents 只放行连续前缀，缺号会永久卡住
        assertThat(events).extracting(AgentRunEvent::seq)
                .containsExactly(1, 2, 3, 4, 5, 6);
        // 词表闭集：前端 eventPresentation 是查表，表外类型会让页面渲染时抛异常
        assertThat(events).allSatisfy(event -> {
            assertThat(AgentRunEvent.TYPES).contains(event.type());
            assertThat(AgentRunEvent.KINDS).contains(event.kind());
            assertThat(AgentRunEvent.STATUSES).contains(event.status());
            assertThat(event.id()).isEqualTo("r1-" + event.seq());
        });
        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.started", "reasoning.started", "tool.started",
                        "tool.completed", "reasoning.completed", "run.completed");
        // path 按时间区间算：推理开始得更早（+5ms）所以是 1.1，工具（+1010ms）是 1.2
        assertThat(events).extracting(AgentRunEvent::path)
                .containsExactly("1", "1.1", "1.2", "1.2", "1.1", "1");
        // 开始事件一定是"进行中"且没有耗时，终止事件才带耗时
        assertThat(events.get(1).status()).isEqualTo(AgentRunEvent.STATUS_RUN);
        assertThat(events.get(1).durationMs()).isNull();
        assertThat(events.get(3).status()).isEqualTo(AgentRunEvent.STATUS_OK);
        assertThat(events.get(3).durationMs()).isEqualTo(500L);
        // 父路径：这两个阶段节点都挂在根（1）下
        assertThat(events.get(1).parentPath()).isEqualTo("1");
        assertThat(events.get(2).parentPath()).isEqualTo("1");
    }

    /**
     * 进行中的阶段必须出现在树上。
     *
     * <p>这是 Step 2 要解决的问题本身：旧模型"阶段结束时才写一条"，所以正在跑的那一步在页面上
     * 根本不存在。现在开始事件先落地，节点立刻可见 —— 且它的结束时刻按"无穷大"处理，
     * 从而能收编内部正在跑的子节点。
     */
    @Test
    void unfinishedStageAppearsAsARunningNode() {
        AgentTraceRun run = runningRun();
        startStage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent", 5, Map.of("iteration", 1));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.started", "reasoning.started");
        assertThat(events.get(1).path()).isEqualTo("1.1");
        assertThat(events.get(1).status()).isEqualTo(AgentRunEvent.STATUS_RUN);
        assertThat(events.get(1).durationMs()).isNull();
    }

    /**
     * MODEL_CALL 的信息由 REASONING 的收口事件携带。
     *
     * <p>旧模型靠翻译器把 MODEL_CALL 那条 step "折叠"进推理节点；现在折叠发生在写入侧，
     * 所以这里要验的是"推理节点确实吃到了模型名与用量"这个结果。
     */
    @Test
    void reasoningNodeCarriesModelNameAndTokensFromTheFoldedModelCall() {
        AgentTraceRun run = runningRun();
        stage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent", 5, 1000,
                Map.of("iteration", 1), reasoningOutput());

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        // 只有根 + 推理两个节点：不再出现"推理里再套一个推理"这种同义反复
        assertThat(events).extracting(AgentRunEvent::path)
                .containsExactly("1", "1.1", "1.1");
        AgentRunEvent completed = events.get(2);
        assertThat(completed.source()).isEqualTo("deepseek-chat");
        assertThat(completed.tokens()).isEqualTo(150);
        assertThat(completed.payload().metrics())
                .containsEntry("inputTokens", 120)
                .containsEntry("outputTokens", 30);
        // 推理自身的输出（模型说了什么）不能因为折叠而丢
        assertThat(completed.payload().output()).containsEntry("text", "本月共 3 条");
    }

    @Test
    void actingNestsInsideReasoningWhenItsIntervalIsContained() {
        // 工具在推理还没结束时就开始执行 → 时间区间被推理包住 → 显示成推理的子节点
        AgentTraceRun run = runningRun();
        int reasoningStage = startStage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent",
                5, Map.of("iteration", 1));
        stage(run, AgentTraceEvent.PHASE_ACTING, "工具调用 · timesheet_list", 100, 200,
                Map.of("tool", "timesheet_list"), Map.of("state", "SUCCESS"));
        endStage(run, reasoningStage, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent",
                5, 1000, reasoningOutput());

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::path)
                .containsExactly("1", "1.1", "1.1.1", "1.1.1", "1.1");
        assertThat(events.get(2).parentPath()).isEqualTo("1.1");
        assertThat(events.get(1).parentPath()).isEqualTo("1");
    }

    @Test
    void actingStaysASiblingWhenItRunsAfterReasoningEnded() {
        AgentTraceRun run = runningRun();
        stage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent", 5, 1000,
                Map.of("iteration", 1), reasoningOutput());
        stage(run, AgentTraceEvent.PHASE_ACTING, "工具调用 · timesheet_list", 1010, 500,
                Map.of("tool", "timesheet_list"), Map.of("state", "SUCCESS"));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::path)
                .containsExactly("1", "1.1", "1.1", "1.2", "1.2");
        assertThat(events).extracting(AgentRunEvent::parentPath)
                .containsExactly(null, "1", "1", "1", "1");
    }

    /**
     * path 会变，seq 绝不能变。
     *
     * <p>这是"事件先落、编号后算"这个设计的已知代价与硬性底线：新来的外层阶段会把已有节点
     * 收编成子节点（path 从 1.1 变 1.1.1），但已经发出去的 seq / id 必须原样不动，
     * 否则客户端的增量游标就会指到别的事件上。
     */
    @Test
    void lateArrivingOuterStageReparentsExistingNodeButNeverRenumbersSeq() {
        AgentTraceRun run = runningRun();
        stage(run, AgentTraceEvent.PHASE_ACTING, "工具调用 · timesheet_list", 100, 200,
                Map.of("tool", "timesheet_list"), Map.of("state", "SUCCESS"));
        List<AgentRunEvent> before = AgentRunEventTranslator.translate(run);
        assertThat(before).extracting(AgentRunEvent::path).containsExactly("1", "1.1", "1.1");

        // 推理的区间包住了刚写下的工具
        stage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent", 5, 1000,
                Map.of("iteration", 1), reasoningOutput());
        List<AgentRunEvent> after = AgentRunEventTranslator.translate(run);

        assertThat(after.get(1).seq()).isEqualTo(before.get(1).seq());
        assertThat(after.get(1).id()).isEqualTo(before.get(1).id());
        assertThat(after.get(1).path()).isEqualTo("1.1.1");
        assertThat(after.get(3).path()).isEqualTo("1.1");
        assertThat(after.get(3).type()).isEqualTo("reasoning.started");
    }

    /** 增量游标的前提：新事件只会追加在后面，已经发出去的那一段逐字节不变。 */
    @Test
    void appendingASiblingLeavesEarlierEventsByteIdentical() {
        AgentTraceRun run = runningRun();
        stage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent", 5, 1000,
                Map.of("iteration", 1), reasoningOutput());
        List<AgentRunEvent> first = AgentRunEventTranslator.translate(run);

        stage(run, AgentTraceEvent.PHASE_REASONING, "第 2 轮推理 · t_agent", 5000, 1000,
                Map.of("iteration", 2), reasoningOutput());
        List<AgentRunEvent> second = AgentRunEventTranslator.translate(run);

        assertThat(second.subList(0, first.size())).isEqualTo(first);
        assertThat(second).extracting(AgentRunEvent::seq).containsExactly(1, 2, 3, 4, 5);
        assertThat(second.get(4).path()).isEqualTo("1.2");
    }

    /**
     * 系统提示词是 run 级属性，不进调用树。
     *
     * <p>旧模型里它是一条不产出事件的"阶段"（数据留着但没人发）。现在连阶段都不是了 ——
     * 它挂在 run 上，压根不占事件序号。这条断言同时守住了"占号就必须输出"这条铁律。
     */
    @Test
    void systemPromptIsRunLevelAndProducesNoEvent() {
        AgentTraceRun run = runningRun();
        run.setSystemPrompt("你是工时助手");
        stage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent", 5, 1000,
                Map.of("iteration", 1), reasoningOutput());

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.started", "reasoning.started", "reasoning.completed");
        assertThat(run.getSystemPrompt()).isEqualTo("你是工时助手");
    }

    @Test
    void failedStageRaisesErrorAndKeepsTheNodeKindFromItsStartedEvent() {
        AgentTraceRun run = runningRun();
        int toolStage = startStage(run, AgentTraceEvent.PHASE_ACTING, "工具调用 · git_commit",
                10, Map.of("tool", "git_commit"));
        run.addEvent(AgentTraceEvent.completed(run.nextSeq(), toolStage, AgentTraceEvent.PHASE_ACTING,
                "工具调用 · git_commit", run.getStartedAtMs() + 10, 30L, null, "TimeoutException: 超时"));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.started", "tool.started", "error.raised");
        // 节点类型由起点事件决定，不会因为终止事件是 error 就变成"异常"节点
        assertThat(events.get(1).kind()).isEqualTo(AgentRunEvent.KIND_TOOL);
        assertThat(events.get(2).status()).isEqualTo(AgentRunEvent.STATUS_ERR);
        assertThat(events.get(2).payload().note()).isEqualTo("TimeoutException: 超时");
        // 失败时不出结果，避免页面把半截输出当成功结果渲染
        assertThat(events.get(2).payload().output()).isNull();
    }

    @Test
    void actingWithoutToolNameFallsBackToActingVocabulary() {
        AgentTraceRun run = runningRun();
        stage(run, AgentTraceEvent.PHASE_ACTING, "动作 · t_agent", 10, 30,
                Map.of(), Map.of("state", "SUCCESS"));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.started", "acting.started", "acting.completed");
        assertThat(events.get(1).kind()).isEqualTo(AgentRunEvent.KIND_ACTING);
        assertThat(events.get(1).source()).isNull();
    }

    @Test
    void occurredAtIsIsoUtcWithMillisecondPrecision() {
        AgentTraceRun run = runningRun();

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events.get(0).occurredAt())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    }

    @Test
    void nullRunTranslatesToEmptyList() {
        assertThat(AgentRunEventTranslator.translate(null)).isEmpty();
    }

    /**
     * 输出与写入一一对应 —— 不跳号、不重排、不吞事件。
     *
     * <p>这是整套设计里最容易被后来者破坏、破坏后又最难查的一条：任何"这条不该发"的过滤
     * （例如把某个阶段悄悄跳过）都会在事件流里留下空洞，前端会卡在空洞之前不再渲染，
     * 而网络面板里一切正常。所以把它单列成一条断言。
     */
    @Test
    void outputMapsOneToOneOntoWrittenEvents() {
        AgentTraceRun run = runningRun();
        int reasoningStage = startStage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent",
                5, Map.of("iteration", 1));
        stage(run, AgentTraceEvent.PHASE_ACTING, "工具调用 · git_commit", 100, 200,
                Map.of("tool", "git_commit"), Map.of("state", "SUCCESS"));
        endStage(run, reasoningStage, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent",
                5, 1000, reasoningOutput());
        finishRun(run, AgentTraceRun.STATUS_DONE, 120, 30, 0, null, Map.of("iterations", 1));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).hasSameSizeAs(run.getEvents());
        assertThat(events).extracting(AgentRunEvent::seq)
                .containsExactlyElementsOf(run.getEvents().stream().map(AgentTraceEvent::seq).toList());
    }

    // ==================================================================================
    // 子 agent 派发（Step 5）
    // ==================================================================================

    /**
     * 子 agent 派发被正名为 subagent.dispatched / subagent.completed，节点类型与来源也各归各位。
     *
     * <p>这条守的是前端契约：eventPresentation.ts 早就备好了 subagent.* 的图标与标签，
     * 但后端此前只会发 tool.started / tool.completed（工具名 agent_spawn），
     * 于是页面上永远只有"一个叫 agent_spawn 的工具"，看不到"派发了一层子 agent"。
     */
    @Test
    void subagentDispatchIsTranslatedWithItsOwnVocabularyAndSource() {
        AgentTraceRun run = runningRun();
        int reasoningStage = startStage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent",
                5, Map.of("iteration", 1));
        // 派发：入参里带着目标 agent 与 label（写入侧从工具入参的 agent_id / label 抄过来的）
        Map<String, Object> dispatchInput = new LinkedHashMap<>();
        dispatchInput.put("tool", "agent_spawn");
        dispatchInput.put("targetAgent", "workwx_doc_agent");
        dispatchInput.put("label", "roster-reader-3");
        int dispatchStage = startStage(run, AgentTraceEvent.PHASE_SUBAGENT,
                "子 agent 派发 · workwx_doc_agent · roster-reader-3", 100, dispatchInput);
        endStage(run, dispatchStage, AgentTraceEvent.PHASE_SUBAGENT,
                "子 agent 派发 · workwx_doc_agent · roster-reader-3", 100, 900,
                Map.of("state", "SUCCESS"));
        endStage(run, reasoningStage, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_agent",
                5, 1000, reasoningOutput());

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.started", "reasoning.started", "subagent.dispatched",
                        "subagent.completed", "reasoning.completed");
        // 图例 / 图标：必须是 subagent，而不是 tool
        assertThat(events.get(2).kind()).isEqualTo(AgentRunEvent.KIND_SUBAGENT);
        // source 取目标 agent 名（开始事件上就要有，那时结果还没回来）
        assertThat(events.get(2).source()).isEqualTo("workwx_doc_agent");
        assertThat(events.get(3).source()).isEqualTo("workwx_doc_agent");
        // 词表闭集：subagent.* 必须在 TYPES 里，否则前端查表会在渲染时抛异常
        assertThat(events).allSatisfy(event -> {
            assertThat(AgentRunEvent.TYPES).contains(event.type());
            assertThat(AgentRunEvent.KINDS).contains(event.kind());
        });
        // 派发（100~1000ms）落在推理（5~1005ms）区间内 → 挂成推理的子节点
        assertThat(events.get(2).path()).isEqualTo("1.1.1");
        assertThat(events.get(2).parentPath()).isEqualTo("1.1");
    }

    /**
     * 同一毫秒开始的并发派发必须并排，不能被"跑得最久的那个"收编成子节点。
     *
     * <p>现场（2026-09-23 真库 run mub3rqvg-esxkhw）：同一毫秒同时开始 9 个 agent_spawn，
     * 各自的子 agent 耗时从十几秒到几分钟不等 —— 区间互相重叠。若判父子只看"区间包含"，
     * 最久的那次派发会把它之后的兄弟全吞掉，页面上就出现一层层套起来的"子 agent 派发"链，
     * 而它们本该并排（这正是"一次扇出 N 个"要一眼看清的东西）。
     */
    @Test
    void concurrentDispatchesStartedAtTheSameMomentStaySiblings() {
        AgentTraceRun run = runningRun();
        stage(run, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child_a · 第 1 批", 100, 900,
                Map.of("targetAgent", "t_child_a"), Map.of("state", "SUCCESS"));
        stage(run, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child_b · 第 2 批", 100, 500,
                Map.of("targetAgent", "t_child_b"), Map.of("state", "SUCCESS"));
        stage(run, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child_c · 第 3 批", 100, 1500,
                Map.of("targetAgent", "t_child_c"), Map.of("state", "SUCCESS"));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        // 三个派发都是根节点（1）的孩子，没有任何一个挂在另一个下面
        assertThat(events).extracting(AgentRunEvent::path)
                .containsExactly("1", "1.1", "1.1", "1.2", "1.2", "1.3", "1.3");
        assertThat(events).extracting(AgentRunEvent::parentPath)
                .containsExactly(null, "1", "1", "1", "1", "1", "1");
    }

    // ==================================================================================
    // 子 agent 同步 / 异步（Step 6）
    // ==================================================================================

    /**
     * 子会话归属：哪怕并发派发的区间互相重叠，各自的子树也必须挂到"派发我的那一次"下面。
     *
     * <p>这守的是"一次扇出 N 个"能不能看清归属。反证：两次派发都在 100ms 开始，A 到 900ms 结束、
     * B 到 1500ms 结束，两个子节点都在 950ms 才开跑 —— 只按时间区间归属时，两者都会落到"结束更早"
     * 的 A 下面（见 {@code isInnermost}），B 的子树就被 A 吞了。写入侧给子节点记了
     * {@code input.agentSession}、给派发节点记了 {@code output.childSession}，这里必须据此精确归位。
     */
    @Test
    void subagentSubtreesAreAttachedByChildSessionNotByTimeInterval() {
        AgentTraceRun run = runningRun();
        // 两次派发同刻开始、时长不同（A 短、B 长）—— 只按区间归属时子节点会全落到 A 下面
        int dispatchA = startStage(run, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child_a · 第 1 批",
                100, Map.of("targetAgent", "t_child_a"));
        endStage(run, dispatchA, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child_a · 第 1 批",
                100, 800, Map.of("state", "SUCCESS", "childSession", "sub-a"));
        int dispatchB = startStage(run, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child_b · 第 2 批",
                100, Map.of("targetAgent", "t_child_b"));
        endStage(run, dispatchB, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child_b · 第 2 批",
                100, 1400, Map.of("state", "SUCCESS", "childSession", "sub-b"));
        // 子 agent 的节点：都在派发结束之后才开跑，且各自带着自己的子会话 id
        stage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_child_a", 950, 250,
                Map.of("agentSession", "sub-a"), reasoningOutput());
        stage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理 · t_child_b", 950, 350,
                Map.of("agentSession", "sub-b"), reasoningOutput());

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        // 两次派发并排占据顶层分支（1.1 / 1.2），各自的子节点落在自己的分支里
        assertThat(startedPathsOf(events, AgentRunEvent.TYPE_SUBAGENT_DISPATCHED))
                .containsExactly("1.1", "1.2");
        assertThat(startedPathsOf(events, AgentRunEvent.TYPE_REASONING_STARTED))
                .containsExactly("1.1.1", "1.2.1");
    }

    /**
     * 后台派发必须收口成 wait（"结果还没到"），而不是像同步派发一样渲染成 ok。
     *
     * <p>accepted（{@code timeout_seconds=0} 立刻返回）与 timeout_promoted（同步等超时、框架转后台）
     * 都是"这套调用没拿到结果"。此前两者都显示 ok，让人以为子 agent 已经给了结论，而结果其实要等到
     * 后面某一轮才以 {@code <system-reminder>} 回到父 agent 的上下文里。note 必须把这件事写出来，
     * 并把 task_id 与子会话给到页面，否则看上去就像子 agent 什么都没干。
     */
    @Test
    void backgroundDispatchClosesWithWaitAndANoteThatExplainsWhereTheResultWent() {
        for (String backgroundStatus : List.of(AgentRunEvent.DISPATCH_ACCEPTED,
                AgentRunEvent.DISPATCH_TIMEOUT_PROMOTED)) {
            AgentTraceRun run = runningRun();
            stage(run, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child · 长任务", 100, 50,
                    Map.of("targetAgent", "t_child"),
                    Map.of("state", "SUCCESS", "dispatchStatus", backgroundStatus,
                            "taskId", "task_1", "childSession", "sub-x"));

            AgentRunEvent completed = AgentRunEventTranslator.translate(run).stream()
                    .filter(event -> AgentRunEvent.TYPE_SUBAGENT_COMPLETED.equals(event.type()))
                    .findFirst().orElseThrow();

            assertThat(completed.status()).as("status=%s", backgroundStatus)
                    .isEqualTo(AgentRunEvent.STATUS_WAIT);
            assertThat(completed.payload().note())
                    .as("status=%s", backgroundStatus)
                    .contains("task_1").contains("sub-x");
            // 派发结论原样带出，页面靠它区分"等回来了"与"丢后台了"
            assertThat(completed.payload().metrics())
                    .containsEntry("dispatchStatus", backgroundStatus)
                    .containsEntry("taskId", "task_1")
                    .containsEntry("childSession", "sub-x");
        }
    }

    /** 同步派发（结果头里没有后台结论）仍收口为 ok —— 老数据不能凭空变成 wait。 */
    @Test
    void synchronousDispatchWithoutABackgroundConclusionStillClosesWithOk() {
        AgentTraceRun run = runningRun();
        stage(run, AgentTraceEvent.PHASE_SUBAGENT, "子 agent 派发 · t_child · 第 1 批", 100, 900,
                Map.of("targetAgent", "t_child"), Map.of("state", "SUCCESS"));

        AgentRunEvent completed = AgentRunEventTranslator.translate(run).stream()
                .filter(event -> AgentRunEvent.TYPE_SUBAGENT_COMPLETED.equals(event.type()))
                .findFirst().orElseThrow();

        assertThat(completed.status()).isEqualTo(AgentRunEvent.STATUS_OK);
        assertThat(completed.payload().note()).isNull();
    }

    /**
     * 框架注入的合成提醒要在推理节点上标出来（这是<b>入参</b>侧指标）。
     *
     * <p>不标的话，推理节点的上下文里会凭空多出一段 {@code <system-reminder>} XML，看着像用户发的。
     * 取值必须读 started 事件（送进模型那一刻才知道），收口时已无从得知。
     */
    @Test
    void syntheticRemindersShowUpAsAReasoningMetric() {
        AgentTraceRun run = runningRun();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("iteration", 2);
        input.put("syntheticReminders", List.of("task_delivery"));
        int reasoningStage = startStage(run, AgentTraceEvent.PHASE_REASONING, "第 2 轮推理 · t_agent", 5, input);
        endStage(run, reasoningStage, AgentTraceEvent.PHASE_REASONING, "第 2 轮推理 · t_agent",
                5, 100, reasoningOutput());

        AgentRunEvent completed = AgentRunEventTranslator.translate(run).stream()
                .filter(event -> AgentRunEvent.TYPE_REASONING_COMPLETED.equals(event.type()))
                .findFirst().orElseThrow();

        assertThat(completed.payload().metrics()).containsEntry("syntheticReminders", "task_delivery");
    }

    // ==================================================================================
    // 夹具：都按"事件真实产生的顺序"追加，与写入侧（AgentTraceRegistry）的行为对齐
    // ==================================================================================

    /** 起一个 run，并写下根节点的 STARTED 事件（seq 恒为 1）—— 与 Registry.start 的副作用一致。 */
    private static AgentTraceRun runningRun() {
        AgentTraceRun run = new AgentTraceRun("r1", "t_agent", AgentTraceRun.AGENT_KIND_REACT,
                "s1", "u1", Map.of("messageCount", 1), null);
        run.addEvent(AgentTraceEvent.started(run.nextSeq(), AgentTraceEvent.PHASE_AGENT,
                "t_agent", run.getStartedAtMs(), run.getInput()));
        return run;
    }

    /** 只写阶段开始事件（模拟"这一步还在跑"），返回阶段标识。 */
    private static int startStage(AgentTraceRun run, String phase, String title,
                                  long startOffsetMs, Map<String, Object> input) {
        int seq = run.nextSeq();
        run.addEvent(AgentTraceEvent.started(seq, phase, title,
                run.getStartedAtMs() + startOffsetMs, input));
        return seq;
    }

    private static void endStage(AgentTraceRun run, int stageSeq, String phase, String title,
                                 long startOffsetMs, long costMs, Map<String, Object> output) {
        run.addEvent(AgentTraceEvent.completed(run.nextSeq(), stageSeq, phase, title,
                run.getStartedAtMs() + startOffsetMs, costMs, output, null));
    }

    /** 写一对事件（阶段开始 + 收口），返回阶段标识。 */
    private static int stage(AgentTraceRun run, String phase, String title,
                             long startOffsetMs, long costMs,
                             Map<String, Object> input, Map<String, Object> output) {
        int stageSeq = startStage(run, phase, title, startOffsetMs, input);
        endStage(run, stageSeq, phase, title, startOffsetMs, costMs, output);
        return stageSeq;
    }

    /** run 收口：写最终状态 + 根节点的 COMPLETED 事件（与 Registry.finish 的副作用一致）。 */
    private static void finishRun(AgentTraceRun run, String status, int inputTokens, int outputTokens,
                                  int cachedTokens, String error, Map<String, Object> output) {
        run.finish(status, inputTokens, outputTokens, cachedTokens, error);
        run.addEvent(AgentTraceEvent.completed(run.nextSeq(), 1, AgentTraceEvent.PHASE_AGENT,
                "t_agent", run.getStartedAtMs(), run.getCostMs(), output, error));
    }

    /** 某种类型的开始事件的 path 列表（按 seq 顺序）。 */
    private static List<String> startedPathsOf(List<AgentRunEvent> events, String type) {
        return events.stream().filter(event -> type.equals(event.type()))
                .map(AgentRunEvent::path).toList();
    }

    /** 推理节点收口时携带的内容：模型调用的信息被折叠进来（见中间件的 claimFold）。 */
    private static Map<String, Object> reasoningOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", "本月共 3 条");
        output.put("model", "deepseek-chat");
        output.put("inputTokens", 120);
        output.put("outputTokens", 30);
        output.put("cachedTokens", 0);
        output.put("totalTokens", 150);
        return output;
    }
}
