package com.md.agentplatform.server.trace;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件记录（{@link AgentTraceEvent}）→ 前端事件流（{@link AgentRunEvent}）。
 *
 * <h2>一、这一层现在做什么、不做什么</h2>
 * <b>做</b>：把散着的事件配对成"阶段"，按时间区间推导出树的父子关系，给每个节点算出 path。
 * <b>不做</b>：<b>不再分配 seq</b>。
 *
 * <p>早期版本里 seq 是本类现场累加出来的（按 step 顺序逐条 +1）。那在纯内存场景没问题，但在
 * "增量推送 + 断点续传"下是错的：seq 是客户端的游标，一旦发出就不能再变，而"现场累加"意味着
 * 新阶段的到达会让已有事件的编号整体位移。现在 seq 由 {@link AgentTraceRegistry} 在事件产生时
 * 分配并冻结（见 {@link AgentTraceEvent} 的类注释），本类只把已有的 seq 原样映射出去 ——
 * 因此同一个 run 翻译两次必然逐字节相同，这是前端做增量游标的前提。
 *
 * <h2>二、不变量（改这个类之前先读）</h2>
 * <ol>
 *   <li><b>输出与输入一一对应。</b>有多少条事件就有多少个元素，顺序即 seq 顺序。
 *       绝不"跳过某条不发"——那会在事件流里留下空洞，前端的 mergeContiguousEvents
 *       只放行连续前缀，卡在空洞前就再也不渲染了。这也是 MODEL_CALL / SYSTEM_PROMPT
 *       干脆不产生事件的原因（它们的归属见 AgentTraceEvent 的注释）。</li>
 *   <li><b>每个节点必须成对。</b>前端 eventPresentation.ts 里 {@code *.completed} 的 standalone
 *       是 false：只有完成事件、没有起点事件时节点会被直接丢弃。写入侧保证了 STARTED 一定
 *       先于 COMPLETED，本类不做补救。</li>
 * </ol>
 *
 * <h2>三、path 是算出来的，不是存下来的</h2>
 * 层级由"时间区间包含"推导：外层阶段（onReasoning）开始得更早、结束得更晚，它内部发生的阶段
 * （onActing）区间被它包住，于是成为它的子节点。能这么算的前提是事件带了 stageStartMs + costMs。
 *
 * <p>派生而不是写入时分配，是为了避开并发：写入方是会被复制给子 agent 复用的 middleware 实例，
 * 并行工具 / 并行子 agent 的订阅可能交错，"谁先 push 谁就定下 path"的共享栈一定会串台。
 * 纯函数没有共享状态，随便并发。
 *
 * <p>代价是已知且可接受的：路径会随新阶段的到达而变化（一个阶段被后来的更外层阶段收编后，
 * 路径从 1.2 变成 1.1.1）。页面按 path 选中节点时会回落到根节点（TracesPage 已有兜底），
 * 刷新后重新对齐即可。
 *
 * <h2>四、进行中的阶段怎么参与成树</h2>
 * 未收口的阶段没有结束时刻，这里取 {@link Long#MAX_VALUE}。理由是"进行中的容器应当能收编
 * 它内部正在跑的节点"（否则运行期间树会一直跳变）。副作用是两个"同时进行中且开始时刻不同"的
 * 节点会被判成父子 —— 实际上不会发生：同一批并行工具 / 并行派发的开始时刻取的是同一个值
 * （见中间件），而 {@code contains} 要求"外层开始得更早"，所以它们恒为平级。
 *
 * <h2>五、同一批并发的东西为什么一定是平级</h2>
 * {@code contains} 只认"外层开始得更早"，而不是"区间包住"——差别在于<b>开始时刻相同、耗时不同</b>
 * 的那一批：一批并行工具（或一次并发派发出来的多个子 agent）开始时刻相同，但各自结束得早
 * 晚不同，区间会互相重叠。若只按"区间包含"判父子，跑得久的那一个就会把其余兄弟吞成子节点，
 * 页面上表现为一层层套起来的"子 agent 派发"链，而它们本该并排。这也是子 agent 派发能看清
 * "一次扇出 N 个"的前提。
 *
 * <p><b>2026-09-28 起归属改为精确制导。</b>此前只能靠"区间包含"，于是并发的子 agent 会在重叠区间里
 * 随便挑一个"包住它的派发节点"挂上去 —— 页面上看不出哪个子节点属于哪一次派发。当时以为框架不提供
 * span id 就没辙，实测后发现有一条更硬的线索：<b>每个子实例的 sessionId 都不同</b>
 * （框架给它发的 ctx 是 {@code RuntimeContext.builder(parentCtx).sessionId("sub-…")}，
 * 子 agent 的所有回调都带这个值），而派发结果文本里恰好写着这个 {@code session_id}。
 * 写入侧把两边都记下来（节点的 input.agentSession、派发节点的 output.childSession），
 * 这里按"节点属于哪个子会话"直接挂到对应的派发节点下 —— 见 {@link #sessionParentOf}。
 * 它同时也是异步派发的唯一解法：父 run 早就收口，子 agent 的区间与派发节点的区间根本不重叠。
 *
 * <p>时间区间那条路仍然保留，作为"没有子会话信息"（老数据、普通工具节点）的兜底。
 */
public final class AgentRunEventTranslator {

    /** 调用树根路径 —— 与前端约定一致：根是 1，不是 0。 */
    private static final String ROOT_PATH = "1";

    /**
     * ISO-8601 / UTC / 毫秒三位 —— 与 JS {@code new Date(ms).toISOString()} 逐字节一致。
     * 用固定 pattern 而不是 {@code Instant.toString()}：后者在毫秒为 0 时会省掉小数位，
     * 虽然仍是合法 ISO，但前端展示层的字符串比较 / 快照测试会因此抖动。
     */
    private static final DateTimeFormatter ISO_UTC_MS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /** 排序：开始早的在前；开始相同时区间长的在前（父一定排在子前面）；再相同按阶段序号。 */
    private static final Comparator<Node> BY_START_THEN_LONGER_THEN_SEQ =
            Comparator.comparingLong((Node node) -> node.startMs)
                    .thenComparing(Comparator.comparingLong((Node node) -> node.endMs).reversed())
                    .thenComparingInt(node -> node.stage.stageSeq);

    /** 编号用排序：开始早的在前，开始相同按阶段序号 —— 保证兄弟顺序稳定、不随请求变化。 */
    private static final Comparator<Node> BY_START_THEN_SEQ =
            Comparator.comparingLong((Node node) -> node.startMs)
                    .thenComparingInt(node -> node.stage.stageSeq);

    private AgentRunEventTranslator() {
    }

    /**
     * 把一次 run 的事件翻译成前端可直接消费的事件流（按 seq 升序返回）。
     *
     * <p>纯函数：不读当前时间、不碰外部状态、不改动传进来的 run。同一个 run 翻译两次，
     * 只要期间没有新事件写入，结果逐字节相同 —— 这条性质守着前端做增量游标的前提。
     */
    public static List<AgentRunEvent> translate(AgentTraceRun run) {
        if (run == null) {
            return List.of();
        }
        String runId = run.getRunId();
        List<AgentTraceEvent> events = List.copyOf(run.getEvents());

        // ① 配对：STARTED 与 COMPLETED 靠 stageSeq 归位，得到"阶段"（成树需要开始 + 结束两端）。
        List<Stage> stages = pairStages(events);

        // ② 拣出能成节点的阶段。AGENT 阶段不参与：根节点由 run 本身承担 —— 这样即使 run 还在跑，
        //    页面上也已经有根节点，用户能立刻看到"开始跑了"，而不是一片空白。
        List<Node> nodes = new ArrayList<>();
        for (Stage stage : stages) {
            if (isNodePhase(stage.phase())) {
                nodes.add(new Node(stage));
            }
        }

        // ③ 定父子。两条路，优先级明确：
        //    ① 子会话归属（精确）：节点带着"自己属于哪个子会话"（input.agentSession），而派发节点
        //       带着"这次派发出去的子会话 id"（output.childSession）。两边一对，子树就精确挂上了 ——
        //       异步派发（父 run 收口后子 agent 还在跑）靠时间区间根本认不出来，只能靠这条。
        //       它同时也是"同一批并发派发"的解法：每个子实例的 sessionId 都是独立的 sub-…，
        //       所以 9 个并发子 agent 各自的子树不会互相串。
        //    ② 时间区间归属（兜底）：没有子会话信息的节点（老数据、以及非子 agent 的普通节点）
        //       仍按"外层开始得更早"找最内层的包含者，见 contains。
        Map<String, List<Node>> dispatchByChildSession = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (node.childSession != null) {
                dispatchByChildSession.computeIfAbsent(node.childSession, key -> new ArrayList<>()).add(node);
            }
        }

        //    容器一定排在前面：容器的开始不晚于被包含者，开始相同时区间更长者排在前面。
        nodes.sort(BY_START_THEN_LONGER_THEN_SEQ);
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            Node parent = sessionParentOf(node, dispatchByChildSession);
            if (parent == null) {
                for (int j = 0; j < i; j++) {
                    Node candidate = nodes.get(j);
                    if (!contains(candidate, node)) {
                        continue;
                    }
                    if (parent == null || isInnermost(candidate, parent)) {
                        parent = candidate;
                    }
                }
            }
            node.parent = parent;
        }

        // ④ 编号：根是 1，孩子是 1.1 / 1.2。只给"留在树上的节点"编号。
        assignPaths(nodes);

        // ⑤ 发射：按事件自己的 seq 走一遍，逐条映射 —— seq 原样透传，不重排、不重编号。
        Map<Integer, Node> nodeByStageSeq = new LinkedHashMap<>();
        for (Node node : nodes) {
            nodeByStageSeq.put(node.stage.stageSeq, node);
        }

        List<AgentTraceEvent> byWriteOrder = new ArrayList<>(events);
        byWriteOrder.sort(Comparator.comparingInt(AgentTraceEvent::seq));

        List<AgentRunEvent> result = new ArrayList<>(byWriteOrder.size());
        for (AgentTraceEvent event : byWriteOrder) {
            if (AgentTraceEvent.PHASE_AGENT.equals(event.phase())) {
                // 根节点：STARTED → run.started，COMPLETED → run.completed / error.raised。
                result.add(event.isTerminal() ? runTerminal(run, event) : runStarted(run, event));
                continue;
            }
            Node node = nodeByStageSeq.get(event.stageSeq());
            if (node == null) {
                // 走到这里说明有事件没配对成阶段（理论上不可能）。宁可整条不发，也不要半对节点 ——
                // 只有 completed 没有 started 的节点会被前端直接丢弃，反而看不出问题在哪。
                continue;
            }
            result.add(event.isTerminal()
                    ? stageTerminal(runId, node, event)
                    : stageStarted(runId, node, event));
        }
        return List.copyOf(result);
    }

    /**
     * 第一个 seq 大于 {@code seq} 的元素下标（要求 {@code events} 按 seq 升序）。
     *
     * <p>增量读取（REST 的 after 游标、SSE 从 Last-Event-ID 续传）都要用它，
     * 所以放在这里只有一份实现 —— 两处各写一遍二分，迟早有一处写错边界。
     *
     * <p>为什么不写成"直接拿 seq 当下标"：那等于隐式依赖"seq 与下标 1:1"这条<b>分配</b>规则，
     * 而分配规则是 {@link AgentTraceRegistry} 的事，读取方不该知道。
     * 哪天分配规则改成"有些事件不占号"，下标写法会静默错位（多发或少发事件），二分写法不用改。
     *
     * @return 所有事件的 seq 都不大于它时返回 {@code events.size()}（表示"没有更多了"）
     */
    public static int indexAfterSeq(List<AgentRunEvent> events, int seq) {
        int low = 0;
        int high = events.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (events.get(mid).seq() <= seq) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * 把事件按 stageSeq 配成阶段。
     *
     * <p>只有 STARTED 没有 COMPLETED 是合法状态（阶段正在跑），此时 {@code completed} 为 null，
     * 节点照样要出现在树上 —— "进行中"正是页面要展示的东西。
     */
    private static List<Stage> pairStages(List<AgentTraceEvent> events) {
        Map<Integer, AgentTraceEvent> startedBySeq = new LinkedHashMap<>();
        Map<Integer, AgentTraceEvent> completedByStageSeq = new LinkedHashMap<>();
        for (AgentTraceEvent event : events) {
            if (AgentTraceEvent.PHASE_AGENT.equals(event.phase())) {
                continue;
            }
            if (event.isTerminal()) {
                completedByStageSeq.put(event.stageSeq(), event);
            } else {
                startedBySeq.put(event.seq(), event);
            }
        }
        List<Stage> stages = new ArrayList<>(startedBySeq.size());
        for (Map.Entry<Integer, AgentTraceEvent> entry : startedBySeq.entrySet()) {
            stages.add(new Stage(entry.getKey(), entry.getValue(), completedByStageSeq.get(entry.getKey())));
        }
        return stages;
    }

    // ==================================================================================
    // 根节点（run 本身）
    // ==================================================================================

    /** run 开始事件：根节点的起点，携带本次调用的输入摘要（"这次到底问的什么"）。 */
    private static AgentRunEvent runStarted(AgentTraceRun run, AgentTraceEvent event) {
        return new AgentRunEvent(
                eventId(run.getRunId(), event.seq()), event.seq(), run.getRunId(),
                AgentRunEvent.TYPE_RUN_STARTED, iso(event.stageStartMs()),
                ROOT_PATH, null, AgentRunEvent.KIND_AGENT,
                run.getAgentName(), null, AgentRunEvent.STATUS_RUN,
                null, null,
                new AgentRunEvent.Payload(event.input(), null, null, null));
    }

    /**
     * run 的终止事件。
     *
     * <p>失败时发 {@code error.raised} 而不是 {@code run.completed}：前端的 projectRunMeta
     * 就是靠这两个事件判定成功 / 失败的，前者语义更准，也顺带把失败原因送到 note 里，
     * 页面不用再单独找错误。
     */
    private static AgentRunEvent runTerminal(AgentTraceRun run, AgentTraceEvent event) {
        boolean failed = AgentTraceEvent.STATUS_ERROR.equals(event.status());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("inputTokens", run.getInputTokens());
        metrics.put("outputTokens", run.getOutputTokens());
        metrics.put("cachedTokens", run.getCachedTokens());

        return new AgentRunEvent(
                eventId(run.getRunId(), event.seq()), event.seq(), run.getRunId(),
                failed ? AgentRunEvent.TYPE_ERROR_RAISED : AgentRunEvent.TYPE_RUN_COMPLETED,
                // 结束时刻 = 开始 + 耗时，全部来自事件本身，不读当前时间：
                // 事件内容必须只由已落地的数据决定，否则同一个 run 两次读取会得到不同结果。
                iso(event.stageEndMs()), ROOT_PATH, null, AgentRunEvent.KIND_AGENT,
                run.getAgentName(), null,
                failed ? AgentRunEvent.STATUS_ERR : AgentRunEvent.STATUS_OK,
                event.costMs(),
                // tokens 刻意留空：前端 projectRunMeta 会把所有事件的 tokens 求和当 run 总量，
                // 根节点再报一次就翻倍了。run 级用量走上面的 metrics（详情面板里能看到）。
                null,
                new AgentRunEvent.Payload(null, event.output(),
                        failed ? event.error() : null, metrics));
    }

    // ==================================================================================
    // 阶段节点
    // ==================================================================================

    /** 阶段开始事件：只占位、不带载荷（此时还没有结果可看）。 */
    private static AgentRunEvent stageStarted(String runId, Node node, AgentTraceEvent event) {
        return new AgentRunEvent(
                eventId(runId, event.seq()), event.seq(), runId,
                startedTypeOf(node), iso(node.startMs),
                node.path, parentPathOf(node), kindOf(node),
                node.stage.title(), sourceOf(node), AgentRunEvent.STATUS_RUN,
                null, null, null);
    }

    /**
     * 阶段终止事件：成功发 {@code *.completed}，失败发 {@code error.raised}。
     *
     * <p>失败也走 error.raised 而不是"completed + err 状态"：前端把 error.raised 当成独立事件类型，
     * 它顺带承担"把失败原因塞进 note"的职责；completed 在页面上是"收口"信号，语义不同，不该混用。
     *
     * <p>载荷把 STARTED 上的入参与 COMPLETED 上的出参拼在一起：一个阶段在页面上是一个节点，
     * 点开应当同时看到进出参，不该让前端做配对。
     */
    private static AgentRunEvent stageTerminal(String runId, Node node, AgentTraceEvent event) {
        boolean failed = AgentTraceEvent.STATUS_ERROR.equals(event.status());

        return new AgentRunEvent(
                eventId(runId, event.seq()), event.seq(), runId,
                failed ? AgentRunEvent.TYPE_ERROR_RAISED : completedTypeOf(node),
                iso(event.stageEndMs()),
                node.path, parentPathOf(node), kindOf(node),
                node.stage.title(), sourceOf(node),
                terminalStatusOf(node, failed),
                event.costMs(), tokensOf(node),
                new AgentRunEvent.Payload(node.stage.input(), failed ? null : event.output(),
                        failed ? event.error() : terminalNoteOf(node), metricsOf(node)));
    }

    /**
     * 收口事件的状态色。
     *
     * <p>普通阶段只有两种：失败 → err、成功 → ok。子 agent 派发多出一档 <b>wait</b>：
     * 派发本身"成功"了（工具调用没报错），但子 agent 被丢到后台继续跑，这次调用并没有拿到结果 ——
     * {@code accepted}（{@code timeout_seconds=0}）与 {@code timeout_promoted}（同步等超时、框架
     * 把它转成后台任务）都是这种。
     *
     * <p>这一档非有不可：此前它们和"同步跑完"渲染得一模一样，页面上会让人以为子 agent 已经给出了
     * 结论，而实际上结果要等到后面某一轮才以 {@code <system-reminder>} 的形式回到父 agent 的上下文里。
     * 前端的 {@code projectTrace} 把 wait 视作"仍在进行中"（节点显示 pending），正是这个语义。
     */
    private static String terminalStatusOf(Node node, boolean failed) {
        if (failed) {
            return AgentRunEvent.STATUS_ERR;
        }
        return isBackgroundDispatch(node) ? AgentRunEvent.STATUS_WAIT : AgentRunEvent.STATUS_OK;
    }

    /**
     * 这次派发是不是"丢到后台去了"。判据取自写入侧从 agent_spawn 结果文本里解析出的 status
     * （见 {@code AgentRunTraceMiddleware#lineValue}），取值缺省时按"同步"处理 ——
     * 老数据与解析不到的情况都退回改造前的表现，不凭空造一个 wait 出来。
     */
    private static boolean isBackgroundDispatch(Node node) {
        if (!AgentTraceEvent.PHASE_SUBAGENT.equals(node.stage.phase())) {
            return false;
        }
        String status = textOf(node.stage.output(), "dispatchStatus");
        return status != null && AgentRunEvent.BACKGROUND_DISPATCH_STATUSES.contains(status);
    }

    /**
     * 节点说明文字。
     *
     * <p>后台派发一定要写清楚"结果去哪了"：出参里只有一句"任务已提交"，不解释的话，
     * 页面上看起来就像子 agent 什么都没干。这条 note 把下一步指出来（后续轮次会以
     * {@code <system-reminder>} 注入），与 {@code TraceRunInfo} 的 parentRunId 入口互相呼应。
     */
    private static String terminalNoteOf(Node node) {
        if (!isBackgroundDispatch(node)) {
            return null;
        }
        String taskId = textOf(node.stage.output(), "taskId");
        String childSession = textOf(node.stage.output(), "childSession");
        return "子 agent 已在后台继续执行，本次派发没有拿到结果"
                + (taskId == null ? "" : "（task_id=" + taskId + "）")
                + (childSession == null ? "" : "（子会话 " + childSession + "）")
                + "。它跑完后结果会在后续轮次以 <system-reminder> 的形式回到父 agent 的上下文里。";
    }

    /** 阶段 → 开始事件的类型。 */
    private static String startedTypeOf(Node node) {
        if (AgentTraceEvent.PHASE_SUBAGENT.equals(node.stage.phase())) {
            return AgentRunEvent.TYPE_SUBAGENT_DISPATCHED;
        }
        if (AgentTraceEvent.PHASE_ACTING.equals(node.stage.phase())) {
            return toolNameOf(node) == null
                    ? AgentRunEvent.TYPE_ACTING_STARTED : AgentRunEvent.TYPE_TOOL_STARTED;
        }
        return AgentRunEvent.TYPE_REASONING_STARTED;
    }

    /** 阶段 → 完成事件的类型（与 started 严格成对，否则前端会丢掉整个节点）。 */
    private static String completedTypeOf(Node node) {
        if (AgentTraceEvent.PHASE_SUBAGENT.equals(node.stage.phase())) {
            return AgentRunEvent.TYPE_SUBAGENT_COMPLETED;
        }
        if (AgentTraceEvent.PHASE_ACTING.equals(node.stage.phase())) {
            return toolNameOf(node) == null
                    ? AgentRunEvent.TYPE_ACTING_COMPLETED : AgentRunEvent.TYPE_TOOL_COMPLETED;
        }
        return AgentRunEvent.TYPE_REASONING_COMPLETED;
    }

    /** 阶段 → 节点类型（页面图例 / 图标用它）。 */
    private static String kindOf(Node node) {
        if (AgentTraceEvent.PHASE_SUBAGENT.equals(node.stage.phase())) {
            return AgentRunEvent.KIND_SUBAGENT;
        }
        if (AgentTraceEvent.PHASE_ACTING.equals(node.stage.phase())) {
            return toolNameOf(node) == null ? AgentRunEvent.KIND_ACTING : AgentRunEvent.KIND_TOOL;
        }
        return AgentRunEvent.KIND_REASONING;
    }

    /**
     * 节点来源（页面上显示在标题旁边的小字）：模型节点取模型名，工具节点取工具名，
     * 子 agent 派发节点取<b>目标 agent 名</b>。
     *
     * <p>模型名为什么在 REASONING 的收口事件里而不是单独一个节点：MODEL_CALL 在页面上被折叠进
     * 所属的 REASONING（"这一轮推理里的一次模型请求"），所以它压根不产生事件，模型名与 token
     * 由中间件在 REASONING 收口时一并带上（见 AgentTraceEvent 的注释）。
     *
     * <p>子 agent 的目标名为什么取 STARTED 事件的 input、而不是从 COMPLETED 的结果文本里抠：
     * source 在开始事件上就要有（节点一出现就要显示"派发给谁"），而那一刻结果还没回来；
     * 取值口径与写入侧同源（写侧也是从工具入参的 agent_id 取的），不会出现"开始与结束显示的不一致"。
     *
     * <p>官方 mock 里工具节点的 source 是 MCP server 名（如 workwx-doc-mcp），这个信息目前后端
     * 还没有（工具来自本地 bean 还是 MCP，只有装配期知道），落库时再补；先如实给工具名，不编假数据。
     */
    private static String sourceOf(Node node) {
        if (AgentTraceEvent.PHASE_SUBAGENT.equals(node.stage.phase())) {
            return textOf(node.stage.input(), "targetAgent");
        }
        if (AgentTraceEvent.PHASE_ACTING.equals(node.stage.phase())) {
            return toolNameOf(node);
        }
        return textOf(node.stage.output(), "model");
    }

    /** 工具名；ACTING 阶段的入参里一定有（中间件按 toolCallId 从请求侧回查出来的）。 */
    private static String toolNameOf(Node node) {
        if (!AgentTraceEvent.PHASE_ACTING.equals(node.stage.phase())) {
            return null;
        }
        return textOf(node.stage.input(), "tool");
    }

    /**
     * 节点 token 用量：只有推理节点有，其余为 null。
     *
     * <p>取 totalTokens（in + out）而不是只取输入：前端 projectRunMeta 会把所有事件的 tokens
     * 相加当作 run 总量，用 total 才不会漏掉输出侧用量。
     */
    private static Integer tokensOf(Node node) {
        Object total = node.stage.output() == null ? null : node.stage.output().get("totalTokens");
        return total instanceof Number number ? number.intValue() : null;
    }

    /**
     * 节点明细指标：推理节点给 token 分项，子 agent 派发给派发结论，其余不给
     * （前端会把除 inputTokens / outputTokens 之外的键平铺成一行行字段，见 adapters.ts#describeTraceNode）。
     *
     * <p>三处取值的来源不同，别混：
     * <ul>
     *   <li>token 分项在<b>出参</b>上（推理收口时才拿得到用量）；</li>
     *   <li>派发结论（status / task_id / 子会话）也在出参上，但它是写入侧从结果文本里抠出来的；</li>
     *   <li>"这一轮注入过几条合成提醒"只在<b>入参</b>上（它是送进模型的上下文的一部分，
     *       收口时已经不可能再知道），所以这里要读 started 而不是 completed。</li>
     * </ul>
     */
    private static Map<String, Object> metricsOf(Node node) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Map<String, Object> output = node.stage.output();
        if (output != null) {
            for (String key : List.of("inputTokens", "outputTokens", "cachedTokens")) {
                Object value = output.get(key);
                if (value != null) {
                    metrics.put(key, value);
                }
            }
            if (AgentTraceEvent.PHASE_SUBAGENT.equals(node.stage.phase())) {
                putIfPresent(metrics, "dispatchStatus", textOf(output, "dispatchStatus"));
                putIfPresent(metrics, "taskId", textOf(output, "taskId"));
                putIfPresent(metrics, "childSession", textOf(output, "childSession"));
            }
        }
        // 后台任务交付（<system-reminder>）：标出"这一轮推理收到的不是用户消息，而是框架补的交付"。
        // 不标的话，推理节点的上下文里会凭空多出一段 XML，看着像用户发的。
        Object reminders = node.stage.input() == null ? null : node.stage.input().get("syntheticReminders");
        if (reminders instanceof List<?> list && !list.isEmpty()) {
            metrics.put("syntheticReminders", String.join(", ", list.stream().map(String::valueOf).toList()));
        }
        return metrics.isEmpty() ? null : metrics;
    }

    /** 值非空才写进 metrics —— 空值平铺到页面上会变成一行没有内容的字段。 */
    private static void putIfPresent(Map<String, Object> metrics, String key, String value) {
        if (value != null) {
            metrics.put(key, value);
        }
    }

    // ==================================================================================
    // 树：归并父指针 / 编号
    // ==================================================================================

    /** 能成节点的阶段。AGENT 由 run 承担；MODEL_CALL / SYSTEM_PROMPT 不产生事件，自然不在此列。 */
    private static boolean isNodePhase(String phase) {
        return AgentTraceEvent.PHASE_REASONING.equals(phase)
                || AgentTraceEvent.PHASE_ACTING.equals(phase)
                || AgentTraceEvent.PHASE_SUBAGENT.equals(phase);
    }

    /** 自顶向下编号：根是 "1"，根的孩子们是 "1.1" / "1.2"，再往下 "1.1.1"。 */
    private static void assignPaths(List<Node> nodes) {
        List<Node> tops = childrenOf(nodes, null);
        for (int i = 0; i < tops.size(); i++) {
            Node top = tops.get(i);
            top.path = ROOT_PATH + "." + (i + 1);
            numberChildren(nodes, top);
        }
    }

    private static void numberChildren(List<Node> nodes, Node parent) {
        List<Node> children = childrenOf(nodes, parent);
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            child.path = parent.path + "." + (i + 1);
            numberChildren(nodes, child);
        }
    }

    /** 取某个父节点下的所有节点（parent 传 null 表示取顶层），顺序固定。 */
    private static List<Node> childrenOf(List<Node> nodes, Node parent) {
        List<Node> children = new ArrayList<>();
        for (Node node : nodes) {
            if (node.parent == parent) {
                children.add(node);
            }
        }
        children.sort(BY_START_THEN_SEQ);
        return children;
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    /**
     * 外层是否包住内层。
     *
     * <p>判据是"外层<b>开始得更早</b>，且结束得不晚于内层"，而不是简单的"区间包含"。差别在于
     * <b>开始时刻相同、耗时不同</b>的那一批节点：同一批并行工具 / 同一次并发派发出来的多个子
     * agent 开始时刻是同一个值（写入侧刻意拉平，见中间件），但各自结束得有早有晚，区间会重叠。
     * 若只看"区间包含"，跑得最久的那一个会把其余兄弟全吞成子节点 —— 页面上就会看到一层层套起来
     * 的"子 agent 派发"链，而它们本该并排（2026-09-23 真库 run mub3rqvg-esxkhw：同一毫秒开始
     * 9 个 agent_spawn，耗时从十几秒到几分钟不等）。
     *
     * <p>改成"开始得更早"之后：同一毫秒开始的一律平级（正是我们要的"同一批并发"），
     * 只有确实更早开始的容器才有资格收编后来的节点。顺带也修掉了并行工具的老问题 ——
     * 它们此前同样会按耗时排成父子链。
     */
    private static boolean contains(Node outer, Node inner) {
        return outer.startMs < inner.startMs && inner.endMs <= outer.endMs;
    }

    /**
     * 按子会话归属找父节点：节点自己属于子会话 X，就挂到"本次派发出去的正是 X"的那个派发节点下。
     *
     * <p>为什么需要这条路由：异步派发（{@code agent_spawn timeout_seconds=0}，或同步等待超时后
     * 被 promote 成后台任务）在父侧只占一个很短的区间 —— 子 agent 是在那个区间结束<b>之后</b>
     * 才继续跑的。靠时间区间归属的话，子 agent 的节点根本落不进派发节点的区间里，
     * 会被挂到根上或某个不相干的分支下，页面上就看不出"这棵树是谁派发出来的"。
     *
     * <p>为什么阈值取"派发节点开始得不晚于该节点"：同一个子会话可能被多次派发
     * （{@code persist_session=true} 时会话会被复用），这时应当归到<b>最近一次</b>、
     * 也就是开始得最晚的那次派发下，而不是最早那次。
     *
     * <p>顺带也修掉了并发归属：每个子实例的 sessionId 都是独立的（{@code sub-<uuid>}），
     * 所以哪怕 9 个并发派发的区间互相重叠，各自的子树也能精确归位 —— 不再需要"挑一个碰巧包住它
     * 的派发节点"这种猜测。
     *
     * @return 命中的派发节点；本次派发在这个节点之后才发生（或压根没有对应派发）时返回 null，
     *         交给调用方退回时间区间归属
     */
    private static Node sessionParentOf(Node node, Map<String, List<Node>> dispatchByChildSession) {
        if (node.agentSession == null || dispatchByChildSession.isEmpty()) {
            return null;
        }
        List<Node> candidates = dispatchByChildSession.get(node.agentSession);
        if (candidates == null) {
            return null;
        }
        Node best = null;
        for (Node candidate : candidates) {
            if (candidate == node || candidate.startMs > node.startMs) {
                continue;
            }
            if (best == null || candidate.startMs > best.startMs) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 在两个都包含某节点的候选父里挑更内层的那个。
     *
     * <p>嵌套区间的结束时刻单调不减，所以"结束更早"就是"更内层"；结束相同时，开始更晚的更内层；
     * 再相同则按阶段序号取靠后者 —— 保证结果确定，不依赖遍历顺序。
     */
    private static boolean isInnermost(Node candidate, Node current) {
        if (candidate.endMs != current.endMs) {
            return candidate.endMs < current.endMs;
        }
        if (candidate.startMs != current.startMs) {
            return candidate.startMs > current.startMs;
        }
        return candidate.stage.stageSeq > current.stage.stageSeq;
    }

    /**
     * 父路径。顶层阶段的父就是 run 根节点（路径固定为 {@code 1}）——
     * 根不对应任何阶段，所以不能因为 {@code parent == null} 就返回 null：那样前端拿到的
     * parentPath 与它自己的 mock 数据对不上，排查时会被误导。只有根节点自己的 parentPath 才是 null。
     */
    private static String parentPathOf(Node node) {
        return node.parent == null ? ROOT_PATH : node.parent.path;
    }

    /** 事件 id：同一个 run 内由 seq 决定，前端拿它去重。 */
    private static String eventId(String runId, int seq) {
        return runId + "-" + seq;
    }

    private static String iso(long epochMillis) {
        return ISO_UTC_MS.format(Instant.ofEpochMilli(epochMillis));
    }

    /** 从载荷 map 里取字符串；key 不存在、值不是字符串、或只有空白，都按"拿不到"处理。 */
    private static String textOf(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * 一对事件配成的"阶段" —— 成树需要开始与结束两端，而事件是一条一条来的。
     *
     * <p>{@code completed} 为 null 表示阶段还在跑：此时结束时刻按"无穷大"处理，
     * 好让它内部正在跑的子节点能挂到它下面（详见类注释第四节）。
     */
    private static final class Stage {
        private final int stageSeq;
        private final AgentTraceEvent started;
        private final AgentTraceEvent completed;

        private Stage(int stageSeq, AgentTraceEvent started, AgentTraceEvent completed) {
            this.stageSeq = stageSeq;
            this.started = started;
            this.completed = completed;
        }

        private String phase() {
            return started.phase();
        }

        private String title() {
            return started.title();
        }

        private Map<String, Object> input() {
            return started.input();
        }

        private Map<String, Object> output() {
            return completed == null ? null : completed.output();
        }

        /** 这个阶段属于哪个子会话（写入侧在 input 里记的 ctx.sessionId）；拿不到返回 null。 */
        private String agentSession() {
            return textOf(input(), "agentSession");
        }

        /**
         * 这次派发出去的子会话 id（写入侧从 agent_spawn 结果文本里解析出来的 session_id）。
         *
         * <p>只对 SUBAGENT 阶段有意义：它把"派发节点"与"子 agent 的整棵子树"对上的钥匙。
         */
        private String childSession() {
            return textOf(output(), "childSession");
        }
    }

    /**
     * 树节点 —— 只在翻译过程中存活（输入不可变 + 指针可变），所以是私有可变类而不是 record。
     */
    private static final class Node {
        private final Stage stage;
        private final long startMs;
        private final long endMs;
        /** 这个阶段属于哪个子会话；null = 拿不到（老数据 / 写入侧没记）。 */
        private final String agentSession;
        /** 这次派发出去的子会话 id；只有 SUBAGENT 节点可能非空。 */
        private final String childSession;
        private Node parent;
        private String path;

        private Node(Stage stage) {
            this.stage = stage;
            this.startMs = stage.started.stageStartMs();
            // 未收口时取无穷大：让"进行中的容器"能收编它内部正在跑的节点，否则运行期间树会一直跳变。
            this.endMs = stage.completed == null ? Long.MAX_VALUE : stage.completed.stageEndMs();
            this.agentSession = stage.agentSession();
            this.childSession = stage.childSession();
        }
    }
}
