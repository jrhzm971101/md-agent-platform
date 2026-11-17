package com.md.agentplatform.server.workflow;

import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流定义校验器的单测。
 *
 * <p>写法上刻意用<b>假的存在性断言</b>（{@code name -> true/false}）而不是连库：
 * 校验逻辑与"agent 在不在"的实现方无关，这一点由 {@code check(spec, Predicate)} 的签名保证。
 * 连库的话每条用例都要起 Spring + MySQL，而它验证的东西（有没有环、入口唯一）
 * 一条都不需要库。
 *
 * <p>每个用例对应检查清单里的一条，并且断言<b>问题文本里出现了能定位的关键词</b> ——
 * 只断言"有错误"是不够的：这个类的价值在于一次把全部问题说清楚，
 * 如果报错说成别的意思，用户照样改不对。
 */
class WorkflowValidatorTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    /** 造一个 agent 节点。 */
    private static WorkflowSpec.WorkflowNode agent(String key, String agentId) {
        WorkflowSpec.WorkflowNode node = new WorkflowSpec.WorkflowNode();
        node.setKey(key);
        node.setType("agent");
        node.setAgentId(agentId);
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

    /** 全部 agent 都存在。 */
    private static final java.util.function.Predicate<String> ALL_EXIST = name -> true;

    private List<String> check(WorkflowSpec spec) {
        return validator.check(spec, ALL_EXIST);
    }

    /** 断言问题列表里有一条包含给定关键词。 */
    private static void assertProblemContains(List<String> problems, String keyword) {
        assertTrue(problems.stream().anyMatch(p -> p.contains(keyword)),
                "期望有一条问题包含「" + keyword + "」，实际是: " + problems);
    }

    // ==================== 一份正确的图 ====================

    @Test
    void 顺序加分支加汇合的图是合法的() {
        WorkflowSpec spec = spec(List.of(
                agent("start", "triage"),
                condition("needReview", "input.urgent == true"),
                agent("fastPath", "reply"),
                agent("slowPath", "review"),
                join("merge"),
                agent("finish", "summary")),
                edge("start", "needReview"),
                edgeIf("needReview", "slowPath", "input.urgent == true"),
                edge("needReview", "fastPath"),
                edge("fastPath", "merge"),
                edge("slowPath", "merge"),
                edge("merge", "finish"));
        // 问题列表必须为空 —— 这是"校验器没有过度拦截"的基准用例
        assertEquals(List.of(), check(spec), "这份图应当完全合法");
        validator.validate(spec, ALL_EXIST); // 不该抛
    }

    // ==================== 逐条检查 ====================

    @Test
    void 空定义为空报一条() {
        assertEquals(List.of("工作流定义为空"), check(null));
        assertEquals(List.of("至少要有一个节点"), check(new WorkflowSpec()));
    }

    @Test
    void 节点缺key要报出来() {
        WorkflowSpec.WorkflowNode node = new WorkflowSpec.WorkflowNode();
        node.setType("agent");
        node.setAgentId("a");
        assertProblemContains(check(spec(List.of(node))), "缺少 key");
    }

    @Test
    void key必须是合法标识符() {
        // 连字符能建出来，但表达式里 node.triage-1.text 引用不到它 —— 这种半残状态必须拦住
        assertProblemContains(check(spec(List.of(agent("triage-1", "a")))), "key 不合法");
        assertProblemContains(check(spec(List.of(agent("1triage", "a")))), "key 不合法");
    }

    @Test
    void key重复要报出来() {
        assertProblemContains(check(spec(List.of(agent("dup", "a"), agent("dup", "b")))), "key 重复");
    }

    @Test
    void 未知类型与用早了的类型报不同的错() {
        WorkflowSpec.WorkflowNode typo = agent("x", "a");
        typo.setType("agnet");
        assertProblemContains(check(spec(List.of(typo))), "未知的节点类型");

        WorkflowSpec.WorkflowNode early = agent("x", "a");
        early.setType("timer");
        assertProblemContains(check(spec(List.of(early))), "后续批次");
    }

    @Test
    void agent节点必须填agentId() {
        WorkflowSpec.WorkflowNode node = new WorkflowSpec.WorkflowNode();
        node.setKey("x");
        node.setType("agent");
        assertProblemContains(check(spec(List.of(node))), "没有选目标 agent");
    }

    @Test
    void agent节点引用的agent不存在要报出来() {
        // 发布时拦住它，比跑到那一步才发现"Agent 不存在"便宜得多 ——
        // 那时前面的节点已经产生副作用了。
        // 断言里"只有 deployed 是装配好的"，所以引用 ghost 必须报出来。
        List<String> problems = validator.check(spec(List.of(agent("x", "ghost"))), "deployed"::equals);
        assertProblemContains(problems, "不存在");
        // 引用一个确实存在的 agent 时不该误报
        assertEquals(List.of(), validator.check(spec(List.of(agent("x", "deployed"))), "deployed"::equals));
    }

    @Test
    void condition节点必须填表达式且语法要正确() {
        assertProblemContains(check(spec(List.of(condition("c", ""), agent("e", "a")),
                edge("c", "e"))), "表达式");

        assertProblemContains(check(spec(List.of(condition("c", "input.x = 1"), agent("e", "a")),
                edge("c", "e"))), "表达式无效");
    }

    @Test
    void condition节点至少要有一条出边() {
        // 没有出边 = 流程到那里就断了，而画面上看不出来
        assertProblemContains(check(spec(List.of(condition("c", "input.x == 1")))), "没有出边");
    }

    @Test
    void 边两端必须存在() {
        assertProblemContains(check(spec(List.of(agent("a1", "x")), edge("a1", "ghost"))), "不是已定义的节点");
        assertProblemContains(check(spec(List.of(agent("a1", "x")), edge("ghost", "a1"))), "不是已定义的节点");
    }

    @Test
    void 自环与重复边要报出来() {
        assertProblemContains(check(spec(List.of(agent("a1", "x")), edge("a1", "a1"))), "自环");
        assertProblemContains(check(spec(List.of(agent("a1", "x"), agent("a2", "y")),
                edge("a1", "a2"), edge("a1", "a2"))), "边重复");
    }

    @Test
    void 条件只能出现在condition节点的出边上() {
        // 别的节点的出边带条件不会被求值，用户会以为它在生效 —— 这一类必须拦住
        assertProblemContains(check(spec(List.of(agent("a1", "x"), agent("a2", "y")),
                edgeIf("a1", "a2", "input.x == 1"))), "不是 condition 节点");
    }

    @Test
    void join模式只支持all() {
        WorkflowSpec.WorkflowNode merge = join("m");
        WorkflowSpec.JoinSpec joinSpec = new WorkflowSpec.JoinSpec();
        joinSpec.setMode("quorum");
        joinSpec.setQuorum(2);
        merge.setJoin(joinSpec);
        assertProblemContains(check(spec(List.of(agent("a1", "x"), merge), edge("a1", "m"))), "暂不支持");

        // 不写 mode 按 all 处理：join 的默认语义就是"等所有入边"
        merge.setJoin(null);
        assertEquals(List.of(), check(spec(List.of(agent("a1", "x"), join("m")), edge("a1", "m"))));
    }

    // ==================== 图结构 ====================

    @Test
    void 有环要报出来() {
        WorkflowSpec spec = spec(List.of(agent("a1", "x"), agent("a2", "y")),
                edge("a1", "a2"), edge("a2", "a1"));
        List<String> problems = check(spec);
        assertProblemContains(problems, "环");
    }

    @Test
    void 入口必须唯一() {
        // 两个都没有入边的节点：从哪开始没有答案，执行结果取决于遍历顺序
        WorkflowSpec spec = spec(List.of(agent("a1", "x"), agent("a2", "y")));
        assertProblemContains(check(spec), "入口不唯一");
    }

    @Test
    void 连通的图不报可达性问题() {
        WorkflowSpec spec = spec(List.of(agent("a1", "x"), agent("a2", "y"), agent("a3", "z")),
                edge("a1", "a2"), edge("a2", "a3"));
        assertEquals(List.of(), check(spec), "这条链完全连通，不该有任何问题");
    }

    /**
     * 可达性检查在实际规则下是<b>兜底</b>，构造不出独立的失败样例 —— 这件事值得钉住，
     * 否则后人会以为少测了一条。
     *
     * <p>理由：「入口唯一 + 无环」已经蕴含全部可达。反证：取任意节点 v，
     * 沿入边一直往回走 —— 除入口外每个节点入度都 ≥ 1（入口唯一），而 DAG 无环，
     * 所以这条回溯必然在有限步内停在那个唯一的入度为 0 的节点上，也就证明了 v 从入口可达。
     * 因此校验器里"先报环 / 先报入口不唯一、两者都过了才查可达"的顺序是对的：
     * 走到第三步时它只会是空集。
     */
    @Test
    void 第二入口优先报为入口不唯一而不是不可达() {
        WorkflowSpec spec = spec(List.of(agent("a1", "x"), agent("a2", "y"), agent("a3", "z")),
                edge("a1", "a3"), edge("a2", "a3"));
        List<String> problems = check(spec);
        assertProblemContains(problems, "入口不唯一");
        assertFalse(problems.stream().anyMatch(p -> p.contains("不可达")),
                "入口问题优先，不该同时报不可达: " + problems);
    }

    @Test
    void 图结构检查只在其余问题都清空后才跑() {
        // 有字段级问题时不再叠加图结构的报错：否则用户会同时看到"key 不合法"与
        // "入口不唯一"，而后者可能只是因为前者的节点参与不了拓扑
        List<String> problems = check(spec(List.of(agent("triage-1", "x"), agent("b", "y"))));
        assertProblemContains(problems, "key 不合法");
        assertFalse(problems.stream().anyMatch(p -> p.contains("入口不唯一")),
                "字段级问题存在时不该顺带报图结构问题: " + problems);
    }

    @Test
    void 全部问题一次性返回而不是抛第一条() {
        // 三个互相独立的问题应当同时出现在结果里，页面上一次看得完。
        // 这是"收集式校验"的核心价值：若只抛第一条，用户每改一处就要再存一次。
        WorkflowSpec.WorkflowNode noKey = new WorkflowSpec.WorkflowNode();
        noKey.setType("agent");
        noKey.setAgentId("a");

        WorkflowSpec.WorkflowNode badType = agent("x", "a");
        badType.setType("agnet");

        WorkflowSpec.WorkflowNode noAgent = new WorkflowSpec.WorkflowNode();
        noAgent.setKey("y");
        noAgent.setType("agent");

        List<String> problems = check(spec(List.of(noKey, badType, noAgent)));
        assertProblemContains(problems, "缺少 key");
        assertProblemContains(problems, "未知的节点类型");
        assertProblemContains(problems, "没有选目标 agent");
    }

    // ==================== validate ====================

    @Test
    void validate把全部问题包进异常() {
        ControlPlaneValidationException e = assertThrows(ControlPlaneValidationException.class,
                () -> validator.validate(spec(List.of(agent("trip-1", "x"))), ALL_EXIST));
        assertFalse(e.getViolations().isEmpty());
    }
}