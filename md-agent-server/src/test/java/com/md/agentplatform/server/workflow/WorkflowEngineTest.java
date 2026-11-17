package com.md.agentplatform.server.workflow;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import com.md.agentplatform.server.controlplane.store.entity.WorkflowEntity;
import com.md.agentplatform.server.workflow.store.WorkflowRunStore;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunEntity;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunNodeEntity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作流执行引擎的单测。
 *
 * <p>三个替身，各自只替代"引擎不想知道的那件事"：
 * <ul>
 *   <li>{@link WorkflowRunStore} → 内存实现（本文件里的 {@code FakeRunStore}）：
 *       断言"库里到底记了什么"是这一批最关键的可观测点，所以它必须能被读回来；</li>
 *   <li>{@link ControlPlaneStore} → Mockito，只打桩 {@code loadPublishedWorkflow}
 *       （引擎唯一的定义来源）；</li>
 *   <li>{@link AgentManager} → 匿名子类：手写两个方法比 mock 一个 4 个构造依赖的
 *       真实类更直白，还能顺手把"下游收到了什么消息"记下来。</li>
 * </ul>
 * 不连库、不起 Spring。真实 SQL（JSON 列、跨表）由 MyBatisControlPlaneStoreIT 一类负责。
 *
 * <p>断言的重点是<b>节点级状态机</b>：SUCCEEDED / SKIPPED / PENDING 三者的区别，
 * 以及失败时"未执行的节点保持 PENDING"这条（页面上要靠它区分"分支没走"与"炸在半路"）。
 */
class WorkflowEngineTest {

    private static final String WORKFLOW_KEY = "wf-test";
    private static final String SESSION = "wf-run-1";

    private FakeRunStore runStore;
    private AgentManager manager;
    /** 每次 agent 调用的入参，按调用顺序记录：用来断言"上游结果有没有传给下游"。 */
    private final List<String> agentInputs = new ArrayList<>();
    /** 被调用过的 agent 名，按调用顺序记录：断言"谁被调了、谁没被调"比数消息条数可靠。 */
    private final List<String> agentCalls = new ArrayList<>();
    /** agent 名 → 返回文本；不含的名字视为未装配。 */
    private final Map<String, String> agentOutputs = new LinkedHashMap<>();
    /** 让某个 agent 抛错。 */
    private final List<String> failingAgents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        runStore = new FakeRunStore();
        agentInputs.clear();
        agentCalls.clear();
        agentOutputs.clear();
        failingAgents.clear();
        manager = new AgentManager() {
            @Override
            public boolean exists(String name) {
                return agentOutputs.containsKey(name) || failingAgents.contains(name);
            }

            @Override
            public Flux<AgentEvent> stream(String name, List<Msg> msgs, RuntimeContext ctx) {
                agentCalls.add(name);
                agentInputs.add(msgs.get(0).getTextContent());
                if (failingAgents.contains(name)) {
                    return Flux.error(new IllegalStateException("模型调用失败：" + name));
                }
                return Flux.just(new AgentResultEvent(
                        Msg.builder().role(MsgRole.ASSISTANT).textContent(agentOutputs.get(name)).build()));
            }
        };
    }

    // ==================== 顺序推进 ====================

    @Test
    void 顺序推进并且上游结果会传给下游() {
        agentOutputs.put("a1", "第一步的结论");
        agentOutputs.put("a2", "第二步的结论");

        WorkflowRunEntity run = runEngine(chain(), Map.of("topic", "报销"));

        assertEquals(WorkflowRunEntity.STATE_SUCCEEDED, run.getState());
        assertEquals(2, runStore.findNodes(run.getRunId()).size());
        assertEquals(WorkflowRunNodeEntity.STATE_SUCCEEDED, nodeState(run, "s1"));
        assertEquals(WorkflowRunNodeEntity.STATE_SUCCEEDED, nodeState(run, "s2"));

        // 第一个节点只看到"本次输入"，看不到任何上游
        assertTrue(agentInputs.get(0).contains("报销"));
        assertFalse(agentInputs.get(0).contains("【上游节点的结果】"));
        // 第二个节点必须看到第一个节点的产出 —— 这是"依次执行"能传下去的唯一途径
        assertTrue(agentInputs.get(1).contains("第一步的结论"),
                "下游应拿到上游文本，实际收到: " + agentInputs.get(1));
        assertTrue(agentInputs.get(1).contains("【上游节点的结果】"));
    }

    @Test
    void 终态输出汇总了每个节点的状态() {
        agentOutputs.put("a1", "x");
        agentOutputs.put("a2", "y");
        WorkflowRunEntity run = runEngine(chain(), Map.of());

        Map<String, Object> output = run.getOutput();
        assertNotNull(output);
        Object nodes = output.get("nodes");
        assertTrue(nodes instanceof Map<?, ?>, "输出里应有 nodes 汇总，实际: " + output);
        assertTrue(((Map<?, ?>) nodes).containsKey("s1"));
    }

    @Test
    void 同一个agent出现在两个节点上时第二个节点看得到第一个的产出() {
        // 会话是整次运行共用一个，而状态按 agent 分区 —— 这里钉住"引擎不会自作聪明改会话键"
        agentOutputs.put("same", "同一次运行里的产出");
        WorkflowSpec.WorkflowNode n1 = agent("s1", "same");
        WorkflowSpec.WorkflowNode n2 = agent("s2", "same");
        WorkflowSpec spec = spec(List.of(n1, n2), edge("s1", "s2"));

        WorkflowRunEntity run = runEngine(spec, Map.of());
        assertEquals(WorkflowRunEntity.STATE_SUCCEEDED, run.getState());
        assertTrue(agentInputs.get(1).contains("同一次运行里的产出"));
    }

    // ==================== 分支 ====================

    @Test
    void 条件为真时走真分支的另一条边被标跳过() {
        registerBranchAgents();
        WorkflowRunEntity run = runEngine(branch(), Map.of("urgent", true));

        assertEquals(WorkflowRunEntity.STATE_SUCCEEDED, run.getState());
        assertEquals(WorkflowRunNodeEntity.STATE_SUCCEEDED, nodeState(run, "yes"));
        assertEquals(WorkflowRunNodeEntity.STATE_SKIPPED, nodeState(run, "no"));
        // 被跳过的节点绝不能被调用 —— 否则分支就白分了
        assertEquals(List.of("start", "yes"), agentCalls);
        // branchTaken 是页面画"这条边走通了"的依据
        assertEquals("yes", runStore.findNode(run.getRunId(), "decide").getBranchTaken());
    }

    @Test
    void 条件为假时走兜底边() {
        registerBranchAgents();
        WorkflowRunEntity run = runEngine(branch(), Map.of("urgent", false));

        assertEquals(WorkflowRunEntity.STATE_SUCCEEDED, run.getState());
        assertEquals(WorkflowRunNodeEntity.STATE_SKIPPED, nodeState(run, "yes"));
        assertEquals(WorkflowRunNodeEntity.STATE_SUCCEEDED, nodeState(run, "no"));
        assertEquals(List.of("start", "no"), agentCalls);
        assertEquals("no", runStore.findNode(run.getRunId(), "decide").getBranchTaken());
    }

    @Test
    void 所有出边条件都不成立时节点失败并给出可照做的提示() {
        // condition 节点只有一条带条件的出边，条件不成立 ⇒ 无路可走。
        // 校验器刻意不强制要求兜底边（理由见 WorkflowValidator），所以这条路必须在运行期有明确定义。
        agentOutputs.put("a1", "x");
        WorkflowSpec spec = spec(List.of(
                        agent("start", "a1"),
                        condition("decide", "input.urgent == true"),
                        agent("next", "a1")),
                edge("start", "decide"),
                edgeIf("decide", "next", "input.urgent == true"));

        WorkflowRunEntity run = runEngine(spec, Map.of("urgent", false));

        assertEquals(WorkflowRunEntity.STATE_FAILED, run.getState());
        WorkflowRunNodeEntity decide = runStore.findNode(run.getRunId(), "decide");
        assertEquals(WorkflowRunNodeEntity.STATE_FAILED, decide.getState());
        assertEquals(WorkflowRunNodeEntity.FAILURE_NO_BRANCH, decide.getFailureCode());
        assertTrue(decide.getFailureMessage().contains("兜底"), "提示应直接告诉人怎么改：" + decide.getFailureMessage());
        // 失败点之后的节点保持 PENDING（与 SKIPPED 区分开）
        assertEquals(WorkflowRunNodeEntity.STATE_PENDING, nodeState(run, "next"));
    }

    @Test
    void 跳过会沿着边逐层传播() {
        // decide → no → tail：no 被跳过时，tail 也必须被跳过（否则它会拿到空上游还照跑）
        agentOutputs.put("yes", "真分支");
        agentOutputs.put("tail", "不该被调用");
        WorkflowSpec spec = spec(List.of(
                        agent("start", "yes"),
                        condition("decide", "input.urgent == true"),
                        agent("yes", "yes"),
                        agent("no", "tail"),
                        agent("tail", "tail")),
                edge("start", "decide"),
                edgeIf("decide", "yes", "input.urgent == true"),
                edge("decide", "no"),
                edge("no", "tail"));

        WorkflowRunEntity run = runEngine(spec, Map.of("urgent", true));

        assertEquals(WorkflowRunNodeEntity.STATE_SKIPPED, nodeState(run, "no"));
        assertEquals(WorkflowRunNodeEntity.STATE_SKIPPED, nodeState(run, "tail"));
    }

    // ==================== 汇合 ====================

    @Test
    void join只收集走通的分支并作为自己的输出() {
        agentOutputs.put("first", "第一条分支的结果");
        agentOutputs.put("second", "第二条分支的结果");
        WorkflowSpec spec = spec(List.of(
                        agent("start", "first"),
                        condition("gate", "input.urgent == true"),
                        agent("hot", "first"),
                        agent("cold", "second"),
                        join("merge"),
                        agent("finish", "first")),
                edge("start", "gate"),
                edgeIf("gate", "hot", "input.urgent == true"),
                edge("gate", "cold"),
                // 两条分支都汇到 merge，所以 merge 的入边有两条、其中一条会被跳过
                edge("hot", "merge"),
                edge("cold", "merge"),
                edge("merge", "finish"));

        WorkflowRunEntity run = runEngine(spec, Map.of("urgent", true));

        assertEquals(WorkflowRunEntity.STATE_SUCCEEDED, run.getState());
        WorkflowRunNodeEntity merge = runStore.findNode(run.getRunId(), "merge");
        assertEquals(WorkflowRunNodeEntity.STATE_SUCCEEDED, merge.getState());
        // 只收集 TAKEN 的入边：被跳过的那支什么都没产出，不该出现在里面
        assertEquals(List.of("hot"), merge.getOutput().get("joinedFrom"));
        // merge 的文本 = 上游结果拼起来，下游引用 node.merge.text 就能一次拿到全部分支
        assertTrue(agentInputs.get(2).contains("第一条分支的结果"),
                "join 下游应拿到汇合后的文本，实际: " + agentInputs.get(2));
        assertFalse(agentInputs.get(2).contains("第二条分支的结果"));
    }

    /**
     * 这条钉住的是最容易写错的一处语义：condition 节点是 <b>if / else-if</b>，
     * 不是"所有条件成立的分支都走一遍"。
     *
     * <p>真分支的条件成立时，排在它后面的那条不带条件的兜底边<b>也必须被跳过</b>。
     * 若实现里少一个"已经选定了就不再看后面的边"的判断，兜底分支会被无条件执行 ——
     * 而那在页面上看只是一条多出来的绿色节点，很难联想到是引擎的锅。
     */
    @Test
    void 真分支成立时兜底边必须被跳过() {
        agentOutputs.put("entry", "起点");
        agentOutputs.put("yes", "真分支");
        agentOutputs.put("fallback", "兜底分支");
        WorkflowSpec spec = spec(List.of(
                        agent("start", "entry"),
                        condition("decide", "input.urgent == true"),
                        agent("yes", "yes"),
                        agent("fallback", "fallback")),
                edge("start", "decide"),
                // 带条件的真分支在前，兜底边在后 —— 兜底边的顺序就是 spec 里 edges 的顺序
                edgeIf("decide", "yes", "input.urgent == true"),
                edge("decide", "fallback"));

        WorkflowRunEntity run = runEngine(spec, Map.of("urgent", true));

        assertEquals(WorkflowRunEntity.STATE_SUCCEEDED, run.getState());
        assertEquals("yes", runStore.findNode(run.getRunId(), "decide").getBranchTaken());
        assertEquals(WorkflowRunNodeEntity.STATE_SKIPPED, nodeState(run, "fallback"));
        // 只应调用"起点"与"真分支"这两个 agent：兜底分支被放行的话 fallback 会出现在这里
        assertEquals(List.of("entry", "yes"), agentCalls);
    }

    @Test
    void 两条互斥条件边里只有一条进入汇合() {
        agentOutputs.put("l", "左边");
        agentOutputs.put("r", "右边");
        WorkflowSpec spec = spec(List.of(
                        agent("start", "l"),
                        condition("gate", "input.flag == true"),
                        agent("left", "l"),
                        agent("right", "r"),
                        join("merge"),
                        agent("finish", "l")),
                edge("start", "gate"),
                edgeIf("gate", "left", "input.flag == true"),
                edgeIf("gate", "right", "input.flag == false"),
                edge("left", "merge"),
                edge("right", "merge"),
                edge("merge", "finish"));

        WorkflowRunEntity run = runEngine(spec, Map.of("flag", true));
        WorkflowRunNodeEntity merge = runStore.findNode(run.getRunId(), "merge");
        assertEquals(List.of("left"), merge.getOutput().get("joinedFrom"));
    }

    // ==================== 失败 ====================

    @Test
    void agent失败时整次运行失败且未执行到的节点保持PENDING() {
        agentOutputs.put("ok", "正常");
        failingAgents.add("boom");
        WorkflowSpec spec = spec(List.of(
                        agent("s1", "ok"),
                        agent("s2", "boom"),
                        agent("s3", "ok")),
                edge("s1", "s2"),
                edge("s2", "s3"));

        WorkflowRunEntity run = runEngine(spec, Map.of());

        assertEquals(WorkflowRunEntity.STATE_FAILED, run.getState());
        assertEquals(WorkflowRunEntity.FAILURE_NODE_FAILED, run.getFailureCode());
        assertTrue(run.getFailureMessage().contains("s2"), "失败信息要指出是哪个节点：" + run.getFailureMessage());
        assertEquals(WorkflowRunNodeEntity.STATE_SUCCEEDED, nodeState(run, "s1"));
        assertEquals(WorkflowRunNodeEntity.STATE_FAILED, nodeState(run, "s2"));
        // 关键：s3 是 PENDING 而不是 SKIPPED —— 页面要靠这个区分"没轮到就断了"与"分支没走"
        assertEquals(WorkflowRunNodeEntity.STATE_PENDING, nodeState(run, "s3"));
        // 失败节点要留下原因，而不是只有一句 run 级的话
        assertNotNull(runStore.findNode(run.getRunId(), "s2").getFailureMessage());
    }

    @Test
    void agent未装配时在节点上留下明确记录而不是让整次运行起不来() {
        // 发布时校过存在性，所以这里模拟"发布之后 agent 被删了"的漂移
        agentOutputs.put("ok", "正常");
        WorkflowSpec spec = spec(List.of(agent("s1", "ghost")), new WorkflowSpec.WorkflowEdge[0]);

        WorkflowRunEntity run = runEngine(spec, Map.of());

        assertEquals(WorkflowRunEntity.STATE_FAILED, run.getState());
        WorkflowRunNodeEntity row = runStore.findNode(run.getRunId(), "s1");
        // 节点行本身必须是 FAILED 且带着原因 —— 只把失败记在 run 上是这一批最容易漏的一处
        assertEquals(WorkflowRunNodeEntity.STATE_FAILED, row.getState());
        assertEquals(WorkflowRunNodeEntity.FAILURE_AGENT_MISSING, row.getFailureCode());
        assertTrue(row.getFailureMessage().contains("ghost"));
        // 这是跳过了节点执行的情况，所以那个 agent 不该被调用
        assertEquals(List.of(), agentCalls);
    }

    @Test
    void 没有已发布版本时不能执行() {
        ControlPlaneStore store = mock(ControlPlaneStore.class);
        when(store.loadPublishedWorkflow(WORKFLOW_KEY)).thenReturn(null);
        when(store.findWorkflow(WORKFLOW_KEY)).thenReturn(new WorkflowEntity());
        WorkflowEngine engine = new WorkflowEngine(store, runStore, new WorkflowValidator(), manager);

        ControlPlaneValidationException e = assertThrows(ControlPlaneValidationException.class,
                () -> engine.run(WORKFLOW_KEY, Map.of(), "u", WorkflowRunEntity.TRIGGER_MANUAL, null));
        assertTrue(e.getMessage().contains("已发布"), e.getMessage());
    }

    @Test
    void 工作流不存在时给出的是不存在而不是没发布() {
        ControlPlaneStore store = mock(ControlPlaneStore.class);
        when(store.loadPublishedWorkflow(WORKFLOW_KEY)).thenReturn(null);
        when(store.findWorkflow(WORKFLOW_KEY)).thenReturn(null);
        WorkflowEngine engine = new WorkflowEngine(store, runStore, new WorkflowValidator(), manager);

        ControlPlaneValidationException e = assertThrows(ControlPlaneValidationException.class,
                () -> engine.run(WORKFLOW_KEY, Map.of(), "u", WorkflowRunEntity.TRIGGER_MANUAL, null));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    // ==================== 会话键 / 用户消毒 ====================

    @Test
    void 会话键按运行生成且整次运行共用一个() {
        agentOutputs.put("a1", "x");
        agentOutputs.put("a2", "y");
        WorkflowRunEntity run = runEngine(chain(), Map.of("userId", "whatever"));

        assertEquals("wf-" + run.getRunId(), run.getSessionId());
        assertTrue(run.getSessionId().length() > 3);
    }

    @Test
    void 用户标识消毒后回落到默认用户() {
        agentOutputs.put("a1", "x");
        // "..." 消毒后剩下一串点号：点号在文件状态存储下会变成"上一级目录"，
        // 所以这种值必须回落成默认用户，而不是当成一个合法的路径片段用下去。
        WorkflowRunEntity run = runEngine(spec(List.of(agent("s1", "a1"))), Map.of(),
                "...", WorkflowRunEntity.TRIGGER_MANUAL, null);
        assertEquals(WorkflowEngine.DEFAULT_USER_ID, run.getUserId());
    }

    @Test
    void 用户标识里的非法字符被收敛成连字符() {
        assertEquals("a-b-c", WorkflowEngine.sanitize("a/b c"));
        assertEquals("a.b_c-1", WorkflowEngine.sanitize("a.b_c-1"));
        // 前导/尾随空格先 trim 掉
        assertEquals("abc", WorkflowEngine.sanitize("  abc  "));
        // 全点号序列（含只有一个点）都回落成 null，由调用方换默认用户
        assertEquals(null, WorkflowEngine.sanitize("..."));
        assertEquals(null, WorkflowEngine.sanitize("."));
        assertEquals(null, WorkflowEngine.sanitize("   "));
        assertEquals(null, WorkflowEngine.sanitize(null));
        // 超长会被截断，避免拼出一个超长的一级目录 / 列值
        assertEquals(120, WorkflowEngine.sanitize("x".repeat(500)).length());
    }

    // ==================== 测试脚手架 ====================

    private WorkflowRunEntity runEngine(WorkflowSpec spec, Map<String, Object> input) {
        return runEngine(spec, input, "tester", WorkflowRunEntity.TRIGGER_MANUAL, "单测");
    }

    /** 挂上"已发布版本"再跑一次；返回终态的 run 行。 */
    private WorkflowRunEntity runEngine(WorkflowSpec spec, Map<String, Object> input, String userId,
                                        String triggerType, String triggerRef) {
        ControlPlaneStore store = mock(ControlPlaneStore.class);
        WorkflowEntity published = new WorkflowEntity();
        published.setWorkflowKey(WORKFLOW_KEY);
        published.setPublishedVersion(3);
        published.setSpec(spec);
        when(store.loadPublishedWorkflow(WORKFLOW_KEY)).thenReturn(published);
        when(store.findWorkflow(WORKFLOW_KEY)).thenReturn(published);

        WorkflowEngine engine = new WorkflowEngine(store, runStore, new WorkflowValidator(), manager);
        return engine.run(WORKFLOW_KEY, input, userId, triggerType, triggerRef);
    }

    private String nodeState(WorkflowRunEntity run, String nodeKey) {
        WorkflowRunNodeEntity row = runStore.findNode(run.getRunId(), nodeKey);
        assertNotNull(row, "节点行不存在: " + nodeKey);
        return row.getState();
    }

    private static WorkflowSpec.WorkflowNode agent(String key, String agentId) {
        WorkflowSpec.WorkflowNode node = new WorkflowSpec.WorkflowNode();
        node.setKey(key);
        node.setType("agent");
        node.setAgentId(agentId);
        node.setLabel("处理 " + key);
        return node;
    }

    private static WorkflowSpec.WorkflowNode condition(String key, String expr) {
        WorkflowSpec.WorkflowNode node = new WorkflowSpec.WorkflowNode();
        node.setKey(key);
        node.setType("condition");
        node.setCondition(expr);
        return node;
    }

    private static WorkflowSpec.WorkflowNode join(String key) {
        WorkflowSpec.WorkflowNode node = new WorkflowSpec.WorkflowNode();
        node.setKey(key);
        node.setType("join");
        return node;
    }

    private static WorkflowSpec.WorkflowEdge edge(String from, String to) {
        WorkflowSpec.WorkflowEdge edge = new WorkflowSpec.WorkflowEdge();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

    private static WorkflowSpec.WorkflowEdge edgeIf(String from, String to, String expr) {
        WorkflowSpec.WorkflowEdge edge = edge(from, to);
        edge.setCondition(expr);
        return edge;
    }

    private static WorkflowSpec spec(List<WorkflowSpec.WorkflowNode> nodes, WorkflowSpec.WorkflowEdge... edges) {
        WorkflowSpec spec = new WorkflowSpec();
        spec.setNodes(new ArrayList<>(nodes));
        spec.setEdges(new ArrayList<>(List.of(edges)));
        return spec;
    }

    /** 两个 agent 节点串起来的最小链。 */
    private static WorkflowSpec chain() {
        return spec(List.of(agent("s1", "a1"), agent("s2", "a2")), edge("s1", "s2"));
    }

    /** 三个 agent 都注册上，供分支用例使用。 */
    private void registerBranchAgents() {
        agentOutputs.put("start", "起点");
        agentOutputs.put("yes", "走了真分支");
        agentOutputs.put("no", "走了兜底");
    }

    /** start → decide（条件）→ yes / no（兜底边）。 */
    private static WorkflowSpec branch() {
        return spec(List.of(
                        agent("start", "start"),
                        condition("decide", "input.urgent == true"),
                        agent("yes", "yes"),
                        agent("no", "no")),
                edge("start", "decide"),
                edgeIf("decide", "yes", "input.urgent == true"),
                edge("decide", "no"));
    }

    /**
     * 内存版运行存储。
     *
     * <p>刻意实现<b>整行覆盖</b>而不是"合并写入"：引擎的写入语义就是"我手里这份就是完整的一行"，
     * 若这里做合并，引擎少 set 一个字段（比如忘了写 endedAtMs）就永远测不出来。
     * 用 {@code put} 覆盖才能把这类遗漏暴露成断言失败。
     */
    private static final class FakeRunStore implements WorkflowRunStore {

        private final Map<String, WorkflowRunEntity> runs = new LinkedHashMap<>();
        private final Map<String, Map<String, WorkflowRunNodeEntity>> nodes = new LinkedHashMap<>();

        @Override
        public void insertRun(WorkflowRunEntity run) {
            runs.put(run.getRunId(), run);
        }

        @Override
        public void updateRun(WorkflowRunEntity run) {
            assertTrue(runs.containsKey(run.getRunId()), "update 之前必须先 insert");
            runs.put(run.getRunId(), run);
        }

        @Override
        public WorkflowRunEntity findRun(String runId) {
            return runs.get(runId);
        }

        @Override
        public List<WorkflowRunEntity> findRecent(String workflowKey, int limit) {
            return runs.values().stream()
                    .filter(r -> r.getWorkflowKey().equals(workflowKey))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void insertNode(WorkflowRunNodeEntity node) {
            nodes.computeIfAbsent(node.getRunId(), k -> new LinkedHashMap<>()).put(node.getNodeKey(), node);
        }

        @Override
        public void updateNode(WorkflowRunNodeEntity node) {
            insertNode(node);
        }

        @Override
        public List<WorkflowRunNodeEntity> findNodes(String runId) {
            return List.copyOf(nodes.getOrDefault(runId, Map.of()).values());
        }

        @Override
        public WorkflowRunNodeEntity findNode(String runId, String nodeKey) {
            return nodes.getOrDefault(runId, Map.of()).get(nodeKey);
        }
    }
}