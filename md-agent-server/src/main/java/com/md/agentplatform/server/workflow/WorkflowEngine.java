package com.md.agentplatform.server.workflow;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import com.md.agentplatform.server.controlplane.store.entity.WorkflowEntity;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.TraceRunIdGenerator;
import com.md.agentplatform.server.util.JsonUtil;
import com.md.agentplatform.server.workflow.store.WorkflowRunStore;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunEntity;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunNodeEntity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工作流执行引擎 —— 第一批（agent / condition / join 三类节点）。
 *
 * <h2>一、执行模型：顺序推进 + 边状态机</h2>
 * 整张图按<b>拓扑序</b>逐节点推进，一次运行只有一个线程在做决策。
 * 第一批刻意不做并行：并行会立刻引入"同一时刻多个节点在改同一份状态"
 * 与"某个分支永远不返回时怎么办（超时 / 取消 / 租约）"这两件事，
 * 而后者本来就要跟第二批的 timer 节点一起设计。顺序执行的语义是确定的、可单测的，
 * 页面上"哪个节点先跑"也一眼能看懂。
 *
 * <p>分支靠<b>边的状态</b>表达，而不是先算出"要跳过哪些节点"：
 * <pre>
 * 每条边有 PENDING / TAKEN / SKIPPED 三种状态（只在本方法的内存里，不落库）
 * 一个节点<b>就绪</b>   = 它的全部入边都已决断（TAKEN 或 SKIPPED）
 * 一个节点<b>真的执行</b> = 就绪 且 至少有一条入边是 TAKEN（入口节点没有入边，视为满足）
 * 否则它被标 SKIPPED，并把它的出边全部标 SKIPPED（逐层传播）
 * </pre>
 * 之所以不直接算"被跳过的节点集合"：分支可以再次分叉、可以在 join 处汇合，
 * 逐节点判定边状态是唯一不会算错的写法。拓扑序保证"轮到某个节点时它的入边一定都已决断"，
 * 所以这个判定不需要循环收敛。
 *
 * <h2>二、失败策略只有 fail_fast</h2>
 * 任何节点失败 → 整次运行立即停止并标 FAILED，<b>未执行到的节点保持 PENDING</b>。
 * 保持 PENDING 而不是也标 SKIPPED 是有意的：SKIPPED 的含义是"这次明确地没走它"，
 * PENDING 是"还没轮到、之后就断了"。页面上这两种颜色必须不同，
 * 否则人分不清"分支没走这条路"与"流程炸在半路"。
 *
 * <p>{@code continue} 没有实现：它的语义是"后面的节点照跑，最后汇总部分成功"，
 * 而"照跑"意味着下游拿不到上游输出却仍要执行 —— 在边状态回退机制出现之前，
 * 放行它只会产生一堆看着跑完、结果全是空的节点，比直接失败更难排查。
 *
 * <h2>三、冻结的 revision</h2>
 * 定义取的是<b>已发布版本</b>的快照内容（{@link ControlPlaneStore#loadPublishedWorkflow}），
 * 不是主表上的草稿。执行期间有人改草稿并发布，这次运行仍按启动时的版本跑完 ——
 * 不冻结的话，页面上会出现"图变了、节点状态对不上"，因为节点 key 在新版本里可能已经不存在。
 *
 * <h2>四、节点怎么拿到输入（第一批不做字段级映射）</h2>
 * 官方那套 CEL input mapping 这里没有实现，agent 节点收到的消息由三段拼成：
 * 本步说明（节点的 label）、本次运行的输入 JSON、<b>上游节点的结果文本</b>。
 * 最后一段是关键 —— 它让"依次执行"这条链路真的能传下去，而不需要用户先声明字段映射。
 *
 * <p>不做字段映射的理由：映射要先有"节点输出的结构契约"，而 agent 节点的输出是自由文本，
 * 声明契约只会得到一堆永远为空的字段。需要精确取字段时，说明这一步该拆成两个节点，
 * 或者把取值逻辑写进那个 agent 自己的提示词里。
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /**
     * userId 的缺省值。
     *
     * <p>与页面对话那个入口保持一致（{@code ChatStreamService.CONSOLE_USER_ID}）：
     * 工作流也是"从控制台发起"的一类调用，两者用同一个默认用户，排查时一眼能认出
     * "这条不是企微/飞书来的"。刻意复制这个字面量而不是去引用另一个包的常量 ——
     * 那个常量是 chat 模块的实现细节，把它变成公共契约等于给将来"页面对话换默认用户"
     * 埋下一个隐藏的连带影响。
     */
    static final String DEFAULT_USER_ID = "console";

    /** 会话键前缀。整次运行共用一个会话，见 {@link #buildSessionId}。 */
    static final String SESSION_PREFIX = "wf-";

    /** 会话键 / 用户标识的长度上限，与 ChatStreamService 的路径片段上限保持一致。 */
    private static final int PATH_SEGMENT_MAX_LEN = 120;

    /** 上游结果拼进消息时的单节点截断长度：超出部分截断，避免把整份长文塞给下游。 */
    private static final int UPSTREAM_TEXT_LIMIT = 4_000;

    private static final String EDGE_PENDING = "PENDING";
    private static final String EDGE_TAKEN = "TAKEN";
    private static final String EDGE_SKIPPED = "SKIPPED";

    private final ControlPlaneStore store;
    private final WorkflowRunStore runStore;
    private final WorkflowValidator validator;
    private final AgentManager agentManager;

    public WorkflowEngine(ControlPlaneStore store, WorkflowRunStore runStore,
                          WorkflowValidator validator, AgentManager agentManager) {
        this.store = store;
        this.runStore = runStore;
        this.validator = validator;
        this.agentManager = agentManager;
    }

    // ==================================================================================
    // 对外入口
    // ==================================================================================

    /**
     * 跑一次工作流（同步阻塞）。
     *
     * <p><b>同步</b>是第一批的选择：调用方是 HTTP 请求，而节点执行可能要几分钟。
     * 所以控制器必须把它切到 boundedElastic 上执行（见 WorkflowController），
     * 否则会占住 WebFlux 的事件循环线程。
     *
     * @param workflowKey 工作流 key
     * @param input       本次输入（页面提交的 JSON）；null 视为空表
     * @param userId      发起人；为空用 {@link #DEFAULT_USER_ID}
     * @param triggerRef  触发者描述（操作人 / 作业名）；可为空，页面上显示为空
     * @return 终态的 run 行 —— 成功与失败都返回它，只有"根本没法开始"才抛异常
     * @throws ControlPlaneValidationException 工作流不存在 / 没有已发布版本 / 已发布内容结构非法
     */
    public WorkflowRunEntity run(String workflowKey, Map<String, Object> input, String userId,
                                 String triggerType, String triggerRef) {
        WorkflowEntity published = store.loadPublishedWorkflow(workflowKey);
        if (published == null) {
            throw new ControlPlaneValidationException(store.findWorkflow(workflowKey) == null
                    ? "工作流不存在: " + workflowKey
                    : "工作流 " + workflowKey + " 没有已发布的版本，无法执行（先去设计器点一次发布）");
        }
        WorkflowSpec spec = published.getSpec();

        // 发布时已经校过一次，这里再校是为了兜住"库里的内容被绕过控制面改过"这种漂移：
        // 主循环依赖"无环 + 入口唯一 + 全部可达"这三条，一旦不成立就会死循环或空转。
        //
        // agent 是否存在<b>刻意不在这里校</b>：那件事的真相是运行时注册表（AgentManager.exists），
        // 而且它应该在<b>节点上</b>留下一条明确的失败记录，而不是让整次运行根本起不来 ——
        // 用户更想看到"跑到第三步时那个 agent 不见了"，而不是一句"不能执行"。
        validator.validate(spec, name -> true);

        long startedAt = System.currentTimeMillis();
        String runId = TraceRunIdGenerator.next();
        String safeUser = sanitize(userId);

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunId(runId);
        run.setWorkflowKey(published.getWorkflowKey());
        run.setRevision(published.getPublishedVersion());
        run.setTriggerType(triggerType == null || triggerType.isBlank()
                ? WorkflowRunEntity.TRIGGER_MANUAL
                : triggerType.trim());
        run.setTriggerRef(triggerRef);
        run.setSessionId(buildSessionId(runId));
        run.setUserId(safeUser == null ? DEFAULT_USER_ID : safeUser);
        run.setState(WorkflowRunEntity.STATE_RUNNING);
        run.setInput(input == null ? Map.of() : input);
        run.setPolicy(WorkflowRunEntity.POLICY_FAIL_FAST);
        run.setStartedAtMs(startedAt);
        run.setCreatedAtMs(startedAt);
        runStore.insertRun(run);

        log.info("[Workflow] 开始 run={} workflow={} revision={} user={} trigger={}",
                runId, run.getWorkflowKey(), run.getRevision(), run.getUserId(), run.getTriggerType());

        return execute(run, spec);
    }

    /** 某个工作流最近的执行（不含节点明细）；给列表页用。 */
    public List<WorkflowRunEntity> recentRuns(String workflowKey, int limit) {
        return runStore.findRecent(workflowKey, limit);
    }

    /** 一次执行的节点明细，按 nodeKey 升序；给详情页用。 */
    public List<WorkflowRunNodeEntity> nodesOf(String runId) {
        return runStore.findNodes(runId);
    }

    /** 一次执行的 run 行；不存在返回 null。 */
    public WorkflowRunEntity findRun(String runId) {
        return runStore.findRun(runId);
    }

    // ==================================================================================
    // 主循环
    // ==================================================================================

    private WorkflowRunEntity execute(WorkflowRunEntity run, WorkflowSpec spec) {
        Map<String, WorkflowRunNodeEntity> rows = seedNodes(run, spec);
        Map<String, String> edgeState = new LinkedHashMap<>();
        for (WorkflowSpec.WorkflowEdge edge : spec.edgesOrEmpty()) {
            edgeState.put(edgeKey(edge.fromOrEmpty(), edge.toOrEmpty()), EDGE_PENDING);
        }

        // 表达式作用域：nodeScope 是"已执行完的节点"的投影，边跑边填（见类注释第四节里
        // 表达式求值对 node.<key>.text 的支持）。它同时承担"把上游结果拼给下游"的数据源。
        Map<String, Object> nodeScope = new LinkedHashMap<>();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", run.getInput());
        scope.put("run", Map.of("input", run.getInput(), "variables", Map.of()));
        scope.put("node", nodeScope);

        for (String key : topologicalOrder(spec)) {
            WorkflowSpec.WorkflowNode node = spec.node(key);
            WorkflowRunNodeEntity row = rows.get(key);
            NodeContext ctx = new NodeContext(run, spec, node, row, edgeState, scope, nodeScope);

            if (!isActive(ctx)) {
                skipNode(ctx);
                continue;
            }
            WorkflowNodeType type = WorkflowNodeType.fromSpec(node.getType());
            NodeOutcome outcome = switch (type) {
                case AGENT -> runAgentNode(ctx);
                case CONDITION -> runConditionNode(ctx);
                case JOIN -> runJoinNode(ctx);
            };
            if (outcome.failed()) {
                return failRun(run, row, outcome);
            }
            nodeScope.put(key, Map.of(
                    "text", outcome.text() == null ? "" : outcome.text(),
                    "state", WorkflowRunNodeEntity.STATE_SUCCEEDED,
                    "type", type.name()));
            // condition 节点的出边状态由它自己决定（见 runConditionNode）；
            // 其余类型的节点把所有出边都放行 —— 分支只由 condition 节点制造。
            if (type != WorkflowNodeType.CONDITION) {
                for (WorkflowSpec.WorkflowEdge edge : outgoing(spec, key)) {
                    edgeState.put(edgeKey(key, edge.toOrEmpty()), EDGE_TAKEN);
                }
            }
        }

        run.setState(WorkflowRunEntity.STATE_SUCCEEDED);
        run.setEndedAtMs(System.currentTimeMillis());
        run.setOutput(collectOutput(spec, rows));
        runStore.updateRun(run);
        log.info("[Workflow] 完成 run={} workflow={} 耗时={}ms", run.getRunId(), run.getWorkflowKey(),
                run.getEndedAtMs() - run.getStartedAtMs());
        return run;
    }

    // ==================================================================================
    // 三类节点
    // ==================================================================================

    /** AGENT：调一个已装配的 agent，取它的最终文本作为输出。 */
    private NodeOutcome runAgentNode(NodeContext ctx) {
        WorkflowRunNodeEntity row = ctx.row();
        String agentId = ctx.node().getAgentId().trim();
        if (!agentManager.exists(agentId)) {
            // 发布时校过存在性，所以走到这里说明"发布之后这个 agent 被删了/停用了"。
            // 这类漂移必须在<b>节点行</b>上留下明确记录，而不是只让整次运行失败 ——
            // 页面上要能直接看到"是这一步的 agent 不见了"，而不是一句 run 级的失败。
            // 所以这里手工落一次节点行，与下面的 catch 分支做同样的事。
            row.setState(WorkflowRunNodeEntity.STATE_FAILED);
            row.setStartedAtMs(System.currentTimeMillis());
            row.setFailureCode(WorkflowRunNodeEntity.FAILURE_AGENT_MISSING);
            row.setFailureMessage("agent 未装配（可能已被删除或停用）: " + agentId);
            row.setEndedAtMs(System.currentTimeMillis());
            runStore.updateNode(row);
            return NodeOutcome.failed(WorkflowRunNodeEntity.FAILURE_AGENT_MISSING,
                    "agent 未装配（可能已被删除或停用）: " + agentId);
        }
        String message = buildMessage(ctx);
        row.setState(WorkflowRunNodeEntity.STATE_RUNNING);
        row.setStartedAtMs(System.currentTimeMillis());
        row.setInput(Map.of("agentId", agentId, "message", message));
        // 链路 id 在这里就定下来并写进节点行：页面要能从"节点"点进"执行链路"，
        // 而这条链路是本次调用现产生的（中间件只是照用我们给的这个 id）。
        String traceRunId = TraceRunIdGenerator.next();
        row.setAgentRunId(traceRunId);
        runStore.updateNode(row);

        long startedAt = System.currentTimeMillis();
        try {
            String text = invokeAgent(agentId, message, ctx, traceRunId);
            row.setState(WorkflowRunNodeEntity.STATE_SUCCEEDED);
            row.setOutput(Map.of("text", text == null ? "" : text));
            row.setEndedAtMs(System.currentTimeMillis());
            runStore.updateNode(row);
            return NodeOutcome.ok(text);
        } catch (RuntimeException e) {
            row.setState(WorkflowRunNodeEntity.STATE_FAILED);
            row.setFailureCode(WorkflowRunNodeEntity.FAILURE_INTERNAL);
            row.setFailureMessage(describe(e));
            row.setEndedAtMs(System.currentTimeMillis());
            runStore.updateNode(row);
            log.error("[Workflow] 节点执行失败 run={} node={} agent={} 耗时={}ms",
                    ctx.run().getRunId(), row.getNodeKey(), agentId,
                    System.currentTimeMillis() - startedAt, e);
            return NodeOutcome.failed(WorkflowRunNodeEntity.FAILURE_INTERNAL, describe(e));
        }
    }

    /**
     * CONDITION：按声明顺序求出第一条为真的出边，其余出边标 SKIPPED。
     *
     * <p>"按声明顺序取第一条"而不是"求全部真值再算"：多条边同时为真时必须有确定的答案，
     * 否则同一张图跑两次可能走不同分支。顺序就是用户在画布上连边的顺序，
     * 页面上、spec 里都看得见 —— 这比"取条件最具体的那个"可靠得多。
     *
     * <p>不带条件的那条边恒为真，所以它天然是兜底（放在最后就是 else 分支）。
     * 引擎不强制要求存在这样一条边（理由见 {@code WorkflowValidator} 类注释第三节），
     * 但一条都不成立时会以 {@code NO_BRANCH_MATCHED} 明确失败，而不是静默停住。
     */
    private NodeOutcome runConditionNode(NodeContext ctx) {
        WorkflowRunNodeEntity row = ctx.row();
        row.setState(WorkflowRunNodeEntity.STATE_RUNNING);
        row.setStartedAtMs(System.currentTimeMillis());
        row.setInput(Map.of("expression", String.valueOf(ctx.node().getCondition())));
        runStore.updateNode(row);

        List<WorkflowSpec.WorkflowEdge> outs = outgoing(ctx.spec(), ctx.node().keyOrEmpty());
        String taken = null;
        for (WorkflowSpec.WorkflowEdge edge : outs) {
            String to = edge.toOrEmpty();
            // 语义是 if / else-if：<b>取第一条成立的出边，其余一律 SKIPPED</b>。
            // 这个 taken == null 不能省 —— 少了它，"条件成立的真分支"与"最后那条不带条件的
            // 兜底边"会同时被标 TAKEN，于是兜底分支被无条件执行一遍，而页面上看起来
            // 只走了一条路。出边的先后顺序就是这里声明（spec.edges）的顺序，
            // 所以兜底边应当放在最后一条。
            boolean matched = taken == null
                    && (edge.getCondition() == null || edge.getCondition().isBlank()
                        || WorkflowConditionEvaluator.evaluate(edge.getCondition(), ctx.scope()));
            ctx.edgeState().put(edgeKey(ctx.node().keyOrEmpty(), to), matched ? EDGE_TAKEN : EDGE_SKIPPED);
            if (matched) {
                taken = to;
            }
        }
        row.setEndedAtMs(System.currentTimeMillis());
        if (taken == null) {
            row.setState(WorkflowRunNodeEntity.STATE_FAILED);
            row.setFailureCode(WorkflowRunNodeEntity.FAILURE_NO_BRANCH);
            row.setFailureMessage("全部出边的条件都不成立，无路可走。请给这个节点留一条不带条件的兜底边");
            runStore.updateNode(row);
            return NodeOutcome.failed(WorkflowRunNodeEntity.FAILURE_NO_BRANCH, row.getFailureMessage());
        }
        row.setState(WorkflowRunNodeEntity.STATE_SUCCEEDED);
        row.setBranchTaken(taken);
        row.setOutput(Map.of("branchTaken", taken));
        runStore.updateNode(row);
        return NodeOutcome.ok("");
    }

    /**
     * JOIN：汇合点。它不调用模型，只是"等依赖到齐"的显式标记。
     *
     * <p>顺序执行下它看起来是白拿的（轮到自己时入边必然都已决断），但它的价值有两处：
     * 一是画布上让"多条分支在这里合流"这件事可见，二是把上游结果<b>收集</b>起来
     * 作为自己的输出，让下游只依赖 join 一个节点，而不必分别依赖每条分支。
     */
    private NodeOutcome runJoinNode(NodeContext ctx) {
        WorkflowRunNodeEntity row = ctx.row();
        row.setState(WorkflowRunNodeEntity.STATE_RUNNING);
        row.setStartedAtMs(System.currentTimeMillis());
        runStore.updateNode(row);

        Map<String, Object> joined = new LinkedHashMap<>();
        for (WorkflowSpec.WorkflowEdge edge : incoming(ctx.spec(), ctx.node().keyOrEmpty())) {
            String from = edge.fromOrEmpty();
            if (EDGE_TAKEN.equals(ctx.edgeState().get(edgeKey(from, ctx.node().keyOrEmpty())))) {
                joined.put(from, upstreamText(ctx.nodeScope(), from));
            }
        }
        row.setState(WorkflowRunNodeEntity.STATE_SUCCEEDED);
        row.setOutput(Map.of("joinedFrom", new ArrayList<>(joined.keySet())));
        row.setEndedAtMs(System.currentTimeMillis());
        runStore.updateNode(row);
        // join 自己的"文本"是所有上游结果拼起来：这样下游引用 node.<join>.text
        // 就能一次拿到所有分支的产出，不需要知道是哪几条分支汇过来的。
        return NodeOutcome.ok(String.join("\n\n", joined.values().stream().map(String::valueOf).toList()));
    }

    // ==================================================================================
    // agent 调用
    // ==================================================================================

    /**
     * 调一次 agent 并等它跑完，返回最终文本。
     *
     * <p>用 {@code blockLast()} 阻塞：主循环本来就是同步的（见类注释第一节）。
     * 结果是订阅在<b>当前线程</b>上完成的，所以这里不会引入额外的调度器切换。
     *
     * <p>链路归属靠 RuntimeContext 上的四个属性传下去（runId / source / workflowRunId /
     * workflowNodeKey），中间件会照单全收写进 {@code md_agent_run}。
     * 这样页面上"工作流运行 → 节点 → 执行链路"三层能互相点得动。
     */
    private String invokeAgent(String agentId, String message, NodeContext ctx, String traceRunId) {
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId(ctx.run().getSessionId())
                .userId(ctx.run().getUserId())
                .put(AgentTraceRegistry.RUN_ID_ATTR, traceRunId)
                .put(AgentTraceRegistry.SOURCE_ATTR, AgentTraceRun.SOURCE_WORKFLOW)
                .put(AgentTraceRegistry.WORKFLOW_RUN_ID_ATTR, ctx.run().getRunId())
                .put(AgentTraceRegistry.WORKFLOW_NODE_KEY_ATTR, ctx.node().keyOrEmpty())
                .build();
        List<Msg> msgs = List.of(Msg.builder().role(MsgRole.USER).textContent(message).build());

        // 结果文本从 AgentResultEvent 里取，而不是自己攒文本增量：
        // 增量事件会被重试 / 压缩等中间件切碎，攒出来的串在"这一轮重试过"时会重复。
        AtomicReference<String> result = new AtomicReference<>();
        Flux<AgentEvent> stream = agentManager.stream(agentId, msgs, runtime);
        stream.doOnNext(event -> {
            if (event instanceof AgentResultEvent resultEvent
                    && resultEvent.getResult() != null
                    && resultEvent.getResult().getTextContent() != null) {
                result.set(resultEvent.getResult().getTextContent());
            }
        }).blockLast();
        return result.get();
    }

    /**
     * 拼一个 agent 节点收到的消息：本步说明 + 本次输入 + 上游结果。
     *
     * <p>上游结果<b>只带 TAKEN 的入边</b>：被跳过的分支什么都没产出，
     * 把它们也拼进去会让模型对着空段落猜"这里为什么是空的"。
     */
    private String buildMessage(NodeContext ctx) {
        StringBuilder message = new StringBuilder();
        String label = ctx.node().getLabel();
        if (label != null && !label.isBlank()) {
            message.append("【本步任务】").append(label.trim()).append("\n\n");
        }
        Map<String, Object> input = ctx.run().getInput();
        message.append("【本次工作流输入】\n");
        message.append(input == null || input.isEmpty()
                ? "（无）"
                : JsonUtil.pretty(JsonUtil.stringify(input)));
        message.append("\n");

        Map<String, String> upstream = new LinkedHashMap<>();
        for (WorkflowSpec.WorkflowEdge edge : incoming(ctx.spec(), ctx.node().keyOrEmpty())) {
            String from = edge.fromOrEmpty();
            if (EDGE_TAKEN.equals(ctx.edgeState().get(edgeKey(from, ctx.node().keyOrEmpty())))) {
                String text = upstreamText(ctx.nodeScope(), from);
                if (text != null && !text.isBlank()) {
                    upstream.put(from, text);
                }
            }
        }
        if (!upstream.isEmpty()) {
            message.append("\n【上游节点的结果】\n");
            for (Map.Entry<String, String> entry : upstream.entrySet()) {
                message.append("\n--- 节点 ").append(entry.getKey()).append(" ---\n");
                message.append(clip(entry.getValue(), UPSTREAM_TEXT_LIMIT));
                message.append("\n");
            }
        }
        return message.toString();
    }

    /** 从 nodeScope 里取某个节点的文本；没跑过或没有文本时返回空串。 */
    private static String upstreamText(Map<String, Object> nodeScope, String key) {
        Object value = nodeScope.get(key);
        if (value instanceof Map<?, ?> map && map.get("text") != null) {
            return String.valueOf(map.get("text"));
        }
        return "";
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "...(已截断)";
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    // ==================================================================================
    // 节点行 / 图结构
    // ==================================================================================

    /**
     * 把本次执行要用到的<b>全部</b>节点先种成 PENDING 行。
     *
     * <p>"先全部种下、再逐个改状态"而不是"跑到哪个节点才插哪一行"：
     * 页面上的执行图是<b>一打开就要画出来的</b>，没有行就没法画。
     * 而且失败时未执行到的节点保持 PENDING，这一行必须存在才能显示出来。
     */
    private Map<String, WorkflowRunNodeEntity> seedNodes(WorkflowRunEntity run, WorkflowSpec spec) {
        Map<String, WorkflowRunNodeEntity> rows = new LinkedHashMap<>();
        for (WorkflowSpec.WorkflowNode node : spec.nodesOrEmpty()) {
            WorkflowRunNodeEntity row = new WorkflowRunNodeEntity();
            row.setRunId(run.getRunId());
            row.setNodeKey(node.keyOrEmpty());
            row.setNodeType(WorkflowNodeType.fromSpec(node.getType()).name());
            row.setState(WorkflowRunNodeEntity.STATE_PENDING);
            runStore.insertNode(row);
            rows.put(row.getNodeKey(), row);
        }
        return rows;
    }

    /** 一个节点是否该真的执行：全部入边已决断，且至少一条是 TAKEN（入口节点没有入边）。 */
    private static boolean isActive(NodeContext ctx) {
        List<WorkflowSpec.WorkflowEdge> ins = incoming(ctx.spec(), ctx.node().keyOrEmpty());
        if (ins.isEmpty()) {
            return true;
        }
        boolean anyTaken = false;
        for (WorkflowSpec.WorkflowEdge edge : ins) {
            String state = ctx.edgeState().get(edgeKey(edge.fromOrEmpty(), ctx.node().keyOrEmpty()));
            if (EDGE_TAKEN.equals(state)) {
                anyTaken = true;
            }
        }
        return anyTaken;
    }

    /** 跳过一个节点：标 SKIPPED，并把它的出边全部标 SKIPPED，让跳过逐层传播下去。 */
    private void skipNode(NodeContext ctx) {
        WorkflowRunNodeEntity row = ctx.row();
        row.setState(WorkflowRunNodeEntity.STATE_SKIPPED);
        runStore.updateNode(row);
        for (WorkflowSpec.WorkflowEdge edge : outgoing(ctx.spec(), ctx.node().keyOrEmpty())) {
            ctx.edgeState().put(edgeKey(ctx.node().keyOrEmpty(), edge.toOrEmpty()), EDGE_SKIPPED);
        }
        // 被跳过的节点也要进作用域，且 text 是空串而不是"缺失"：
        // 下游写 node.x.text == "" 就能判断"这条分支没走"，而不必处理取不到值的情况。
        ctx.nodeScope().put(ctx.node().keyOrEmpty(), Map.of(
                "text", "",
                "state", WorkflowRunNodeEntity.STATE_SKIPPED,
                "type", row.getNodeType()));
    }

    private WorkflowRunEntity failRun(WorkflowRunEntity run, WorkflowRunNodeEntity row, NodeOutcome outcome) {
        run.setState(WorkflowRunEntity.STATE_FAILED);
        run.setEndedAtMs(System.currentTimeMillis());
        // run 级的 code 统一是 NODE_FAILED，<b>不</b>照抄节点的 code：
        // 两者回答的问题不同 —— run 级回答"这次运行为什么断"，原因只有一种（某个节点失败了）；
        // 节点级回答"这一步具体错在哪"（agent 没了 / 无路可走 / 内部异常），由节点行自己带着。
        // 照抄的话 run 表上会出现 AGENT_NOT_FOUND 这种"看起来像 run 级原因"的取值，
        // 而它其实只对某个节点成立。
        run.setFailureCode(WorkflowRunEntity.FAILURE_NODE_FAILED);
        run.setFailureMessage("节点 " + row.getNodeKey() + " 失败：" + outcome.failureMessage());
        runStore.updateRun(run);
        log.warn("[Workflow] 失败 run={} workflow={} 节点={} code={} 原因={}",
                run.getRunId(), run.getWorkflowKey(), row.getNodeKey(), outcome.failureCode(),
                outcome.failureMessage());
        return run;
    }

    /**
     * 终态输出：每个节点的类型 / 状态 / 结果摘要。
     *
     * <p>为什么要单独汇总一份而不是让页面去读节点表：这份输出会随 {@code md_workflow_run}
     * 一起被下游消费（企业微信推送、定时任务的日报、将来的 subrun）。
     * 让它自带结果，调用方就不必再多查一次节点表 —— 而那一次查询在"只要一个结论"的场景里
     * 完全是浪费。
     */
    private Map<String, Object> collectOutput(WorkflowSpec spec, Map<String, WorkflowRunNodeEntity> rows) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (WorkflowSpec.WorkflowNode node : spec.nodesOrEmpty()) {
            WorkflowRunNodeEntity row = rows.get(node.keyOrEmpty());
            if (row == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", row.getNodeType());
            entry.put("state", row.getState());
            if (row.getBranchTaken() != null) {
                entry.put("branchTaken", row.getBranchTaken());
            }
            // 执行链路 id 放进去：调用方（页面上是"跳去链路"，将来是 IM 推送里的链接）
            // 不必再查一次节点表就能拿到它
            if (row.getAgentRunId() != null) {
                entry.put("traceRunId", row.getAgentRunId());
            }
            if (row.getOutput() != null) {
                entry.put("output", row.getOutput());
            }
            nodes.put(row.getNodeKey(), entry);
        }
        return Map.of("nodes", nodes);
    }

    /**
     * 拓扑序（Kahn），同层按 spec 里的声明顺序 —— 顺序确定才能复现，也才能单测。
     *
     * <p>此刻图已经过 {@link WorkflowValidator}，所以这里不做环检测：
     * 真出现环说明校验被绕过了，届时 {@code result} 会比节点数少，
     * 而下面的断言会把它抛出来，而不是让主循环少跑几个节点却看着"成功"。
     */
    private static List<String> topologicalOrder(WorkflowSpec spec) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new LinkedHashMap<>();
        for (WorkflowSpec.WorkflowNode node : spec.nodesOrEmpty()) {
            inDegree.put(node.keyOrEmpty(), 0);
            successors.put(node.keyOrEmpty(), new ArrayList<>());
        }
        for (WorkflowSpec.WorkflowEdge edge : spec.edgesOrEmpty()) {
            String from = edge.fromOrEmpty();
            String to = edge.toOrEmpty();
            if (!inDegree.containsKey(from) || !inDegree.containsKey(to)) {
                continue;
            }
            inDegree.merge(to, 1, Integer::sum);
            successors.get(from).add(to);
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.addLast(entry.getKey());
            }
        }
        List<String> order = new ArrayList<>(inDegree.size());
        Set<String> seen = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String key = ready.pollFirst();
            if (!seen.add(key)) {
                continue;
            }
            order.add(key);
            for (String next : successors.getOrDefault(key, List.of())) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.addLast(next);
                }
            }
        }
        if (order.size() != inDegree.size()) {
            throw new ControlPlaneValidationException(
                    "工作流存在环，无法确定执行顺序（发布前的校验应当已经拦住这种情况）");
        }
        return order;
    }

    private static List<WorkflowSpec.WorkflowEdge> outgoing(WorkflowSpec spec, String key) {
        List<WorkflowSpec.WorkflowEdge> result = new ArrayList<>();
        for (WorkflowSpec.WorkflowEdge edge : spec.edgesOrEmpty()) {
            if (edge.fromOrEmpty().equals(key)) {
                result.add(edge);
            }
        }
        return result;
    }

    private static List<WorkflowSpec.WorkflowEdge> incoming(WorkflowSpec spec, String key) {
        List<WorkflowSpec.WorkflowEdge> result = new ArrayList<>();
        for (WorkflowSpec.WorkflowEdge edge : spec.edgesOrEmpty()) {
            if (edge.toOrEmpty().equals(key)) {
                result.add(edge);
            }
        }
        return result;
    }

    private static String edgeKey(String from, String to) {
        return from + "->" + to;
    }

    /**
     * 会话键：<b>整次运行共用一个</b>（{@code wf-<runId>}）。
     *
     * <p>为什么不让每个节点各用一个：一次工作流运行在页面上、在排查时都是"一件事"，
     * 共用会话之后，会话页与执行链路页都天然把它聚合在一起。
     * 而"不同节点之间会不会串味"这个问题由框架的结构回答了 ——
     * 状态是<b>按 agent 分区</b>存的，所以两个不同 agent 的节点互不影响；
     * 同一个 agent 出现在两个节点上时，第二个节点会看到第一个的上下文，
     * 这恰好是"同一条线索继续往下做"的直觉读法。
     *
     * <p><b>不接受调用方传来的会话键</b>：会话键在文件状态存储下会变成路径片段，
     * 而消毒规则的唯一出口在 ChatStreamService 里（那个包不对外暴露）。
     * 与其在这里复制一份消毒规则并等着两边漂移，不如让引擎自己生成 ——
     * 一次运行一个会话本来就是它该负责的语义。
     */
    static String buildSessionId(String runId) {
        return SESSION_PREFIX + runId;
    }

    /**
     * 用户标识消毒：收敛成路径安全字符集，全点号序列回落到 null（由调用方换成默认用户）。
     *
     * <p>规则与 {@code ChatStreamService#resolveUserId} 一致，理由也一样 ——
     * userId 在文件状态存储下是工作区的一级目录，在 MySQL 状态下会与会话键拼成
     * {@code <userId>:<sessionId>} 一列。
     */
    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String safe = raw.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isEmpty() || safe.chars().allMatch(c -> c == '.')) {
            return null;
        }
        return safe.length() <= PATH_SEGMENT_MAX_LEN
                ? safe
                : safe.substring(0, PATH_SEGMENT_MAX_LEN);
    }

    // ==================================================================================
    // 内部结构
    // ==================================================================================

    /**
     * 一次节点执行要用到的全部上下文。
     *
     * <p>打包成一个 record 而不是给每个方法传六个参数：三类节点里有两类要读
     * "图 + 边状态 + 作用域"这三样，逐个传参会让签名长到看不出哪个参数是什么，
     * 而漏传一个（比如 join 忘了拿 nodeScope）在编译期是发现不了的。
     */
    private record NodeContext(WorkflowRunEntity run, WorkflowSpec spec, WorkflowSpec.WorkflowNode node,
                              WorkflowRunNodeEntity row, Map<String, String> edgeState,
                              Map<String, Object> scope, Map<String, Object> nodeScope) {
    }

    /** 一个节点的执行结果（只在引擎内部传递，不落库）。 */
    private record NodeOutcome(boolean failed, String failureCode, String failureMessage, String text) {

        static NodeOutcome ok(String text) {
            return new NodeOutcome(false, null, null, text);
        }

        static NodeOutcome failed(String code, String message) {
            return new NodeOutcome(true, code, message, null);
        }
    }
}