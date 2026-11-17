package com.md.agentplatform.server.workflow;

import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 工作流定义校验 —— 保存草稿时可以不通过，<b>发布与执行前必须通过</b>。
 *
 * <h2>一、为什么"保存不校验、发布校验"</h2>
 * 页面上的图是一步步搭出来的，中途必然处于半成品状态（刚加一个节点、边还没连）。
 * 若保存就拦截，用户每次存盘都要先把图补全，等于逼着人在脑子里搭完再落到页面上。
 * 所以：保存只做"结构上能解析"这一层（见 WorkflowStore），发布与执行做下面这一整组。
 * 这份校验的全部问题<b>一次性返回</b>，不是抛第一条 —— 与 {@code ControlPlaneValidationException}
 * 的既有约定一致（见该异常的类注释），页面上一次能改完所有问题。
 *
 * <h2>二、检查清单（每一条都对应一个真实的坏状态）</h2>
 * | 检查 | 不检查会怎样 |
 * | --- | --- |
 * | 至少一个节点 | 空图画布上什么都没有，用户以为界面坏了 |
 * | key 非空、合法标识符、唯一 | key 是表达式的寻址方式（{@code node.<key>.text}），含连字符就没法被引用；重名会让"按 key 找节点"随机命中一个 |
 * | type 可识别 | 见 {@link WorkflowNodeType#fromSpec}（区分"用早了"与"写错了"） |
 * | agent 节点：agentId 必填且存在 | 执行到那一步才发现"Agent 不存在"，而那时前面的节点已经产生了副作用 |
 * | condition 节点：表达式非空且语法正确 | 表达式错误只在走到那个节点时暴露，前面节点已经跑完 |
 * | condition 节点：至少一条出边 | 没有出边 = 流程到那里就没了，而页面画面上看不出"断了" |
 * | 边：两端都存在 | 拓扑排序会把边当成指向不存在的节点，环检测结果随之失真 |
 * | 边：同一条边不重复 | 重复边让"这个节点有几条入边"变成虚数，join 的"到齐"判定直接错 |
 * | 边：只有 condition 节点的出边能带条件 | 其余节点的出边带条件却永不求值，用户以为它在生效 |
 * | 无环 | 环会执行到爆栈或死循环，且页面上是一个看起来正常的闭环 |
 * | 入口唯一（入度为 0 的节点恰好 1 个） | 多个入口时"从哪开始"没有答案，执行结果取决于遍历顺序 |
 * | 全部节点从入口可达 | 不可达的节点永远不执行，但它会出现在画布上，让人以为它在链路里 |
 *
 * <h2>三、刻意<b>没有</b>要求的一条：condition 节点必须有兜底边</h2>
 * 可以要求"每个 condition 节点至少留一条无条件出边"，这样运行期永远不会
 * "所有条件都不成立"从而无路可走。这里没有这么做，因为穷举分支是合法写法
 * （把每种取值都列出来），强制加兜底边会逼人写一条自己也不知道该干什么的边。
 * 代价转由执行期承担：真的没有边命中时，该节点以
 * 「所有出边的条件都不成立」明确失败（见 WorkflowEngine），
 * 而不是静默停在那里 —— <b>静默是最坏的结果</b>，明确失败至少能在页面上看到是哪一步。
 */
@Component
public class WorkflowValidator {

    /**
     * 节点 key 与表达式里的标识符共用同一套规则。
     *
     * <p>为什么连形状都要管：{@code node.<key>.text} 是用户写表达式的唯一寻址方式，
     * 而表达式解析器只认"字母或下划线开头、只含字母数字下划线"的标识符。
     * 放行 {@code triage-1} 会得到"节点建得了、但永远引用不到它"这种半残状态。
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** join 模式取值；第一批只有 all。 */
    private static final String JOIN_MODE_ALL = "all";

    /**
     * 收集全部问题；{@code violations} 为空表示这份定义可以发布。
     *
     * @param agentExists 判断一个 agent 名是否已装配。用断言而不是直接查库：
     *                    校验逻辑要能脱离数据库单测（见 WorkflowValidatorTest），
     *                    而"agent 在不在"这件事的实现方（AgentManager / 控制面）与本类无关
     */
    public List<String> check(WorkflowSpec spec, Predicate<String> agentExists) {
        List<String> violations = new ArrayList<>();
        if (spec == null) {
            return List.of("工作流定义为空");
        }
        List<WorkflowSpec.WorkflowNode> nodes = spec.nodesOrEmpty();
        List<WorkflowSpec.WorkflowEdge> edges = spec.edgesOrEmpty();
        if (nodes.isEmpty()) {
            violations.add("至少要有一个节点");
            return violations;
        }

        // ---------- 节点：key / type / 按类型必填 ----------
        Map<String, WorkflowSpec.WorkflowNode> byKey = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowSpec.WorkflowNode node = nodes.get(i);
            String where = "第 " + (i + 1) + " 个节点";
            if (node == null) {
                violations.add(where + "为空");
                continue;
            }
            String key = node.keyOrEmpty();
            if (key.isEmpty()) {
                violations.add(where + "缺少 key");
                continue;
            }
            where = "节点 " + key;
            if (!KEY_PATTERN.matcher(key).matches()) {
                violations.add(where + " 的 key 不合法：只能由字母、数字、下划线组成，且不能以数字开头"
                        + "（表达式里要用 node." + key + ".text 引用它）");
            }
            if (byKey.containsKey(key)) {
                violations.add("节点 key 重复：" + key);
                continue;
            }
            byKey.put(key, node);

            WorkflowNodeType type;
            try {
                type = WorkflowNodeType.fromSpec(node.getType());
            } catch (ControlPlaneValidationException e) {
                violations.add(where + " " + e.getMessage());
                continue;
            }
            violations.addAll(checkNodeConfig(node, type, agentExists, where));
        }

        // ---------- 边：两端存在、不重复、条件只允许出现在 condition 节点之后 ----------
        Set<String> edgeKeys = new LinkedHashSet<>();
        Map<String, List<WorkflowSpec.WorkflowEdge>> outgoing = new LinkedHashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            WorkflowSpec.WorkflowEdge edge = edges.get(i);
            String where = "第 " + (i + 1) + " 条边";
            if (edge == null) {
                violations.add(where + "为空");
                continue;
            }
            String from = edge.fromOrEmpty();
            String to = edge.toOrEmpty();
            if (from.isEmpty() || to.isEmpty()) {
                violations.add(where + "缺少 from 或 to");
                continue;
            }
            where = "边 " + from + " → " + to;
            boolean endpointsKnown = true;
            if (!byKey.containsKey(from)) {
                violations.add(where + " 的起点 " + from + " 不是已定义的节点");
                endpointsKnown = false;
            }
            if (!byKey.containsKey(to)) {
                violations.add(where + " 的终点 " + to + " 不是已定义的节点");
                endpointsKnown = false;
            }
            if (from.equals(to)) {
                violations.add(where + " 是自环：节点不能指向自己");
            }
            if (!edgeKeys.add(from + "->" + to)) {
                violations.add("边重复：" + from + " → " + to);
            }
            if (endpointsKnown && !isConditionNode(byKey.get(from))) {
                if (edge.getCondition() != null && !edge.getCondition().isBlank()) {
                    violations.add(where + " 带了条件，但起点 " + from + " 不是 condition 节点"
                            + "（条件只在 condition 节点的出边上求值）");
                }
            }
            if (edge.getCondition() != null && !edge.getCondition().isBlank()) {
                try {
                    WorkflowConditionEvaluator.validate(edge.getCondition());
                } catch (ControlPlaneValidationException e) {
                    violations.add(where + " 的条件无效：" + e.getMessage());
                }
            }
            outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(edge);
        }

        // ---------- condition 节点至少一条出边 ----------
        for (Map.Entry<String, WorkflowSpec.WorkflowNode> entry : byKey.entrySet()) {
            WorkflowNodeType type;
            try {
                type = WorkflowNodeType.fromSpec(entry.getValue().getType());
            } catch (ControlPlaneValidationException e) {
                continue; // 类型问题上面已经报过了，不重复
            }
            if (type == WorkflowNodeType.CONDITION
                    && outgoing.getOrDefault(entry.getKey(), List.of()).isEmpty()) {
                violations.add("condition 节点 " + entry.getKey() + " 没有出边：流程会停在这里，"
                        + "运行期只能报错。请至少连一条出边");
            }
        }

        // ---------- 无环 + 入口唯一 + 可达性 ----------
        if (violations.isEmpty()) {
            violations.addAll(checkGraphShape(byKey.keySet(), edges));
        }
        return violations;
    }

    /** 校验并抛异常（携带全部问题）；供发布与执行入口使用。 */
    public void validate(WorkflowSpec spec, Predicate<String> agentExists) {
        List<String> violations = check(spec, agentExists);
        if (!violations.isEmpty()) {
            throw new ControlPlaneValidationException(violations);
        }
    }

    // ==================================================================================
    // 私有
    // ==================================================================================

    /** 按类型检查配置字段：agent 要目标存在，condition 要表达式能解析，join 只要模式认得。 */
    private List<String> checkNodeConfig(WorkflowSpec.WorkflowNode node, WorkflowNodeType type,
                                        Predicate<String> agentExists, String where) {
        List<String> violations = new ArrayList<>();
        switch (type) {
            case AGENT -> {
                String agentId = node.getAgentId();
                if (agentId == null || agentId.isBlank()) {
                    violations.add(where + " 是 agent 节点但没有选目标 agent");
                } else if (agentExists != null && !agentExists.test(agentId.trim())) {
                    // 这一条必须在发布前报出来：等到执行时才发现，前面的节点已经产生副作用了
                    violations.add(where + " 引用的 agent 不存在：" + agentId.trim());
                }
            }
            case CONDITION -> {
                String expression = node.getCondition();
                if (expression == null || expression.isBlank()) {
                    violations.add(where + " 是 condition 节点但没有填分支表达式");
                } else {
                    try {
                        WorkflowConditionEvaluator.validate(expression);
                    } catch (ControlPlaneValidationException e) {
                        violations.add(where + " 的分支表达式无效：" + e.getMessage());
                    }
                }
            }
            case JOIN -> {
                WorkflowSpec.JoinSpec join = node.getJoin();
                String mode = join == null || join.getMode() == null || join.getMode().isBlank()
                        // 不写 mode 按 all 处理：join 节点的默认语义就是"等所有入边"，
                        // 逼用户为一个唯一选项再点一次下拉框没有意义
                        ? JOIN_MODE_ALL
                        : join.getMode().trim().toLowerCase(Locale.ROOT);
                if (!JOIN_MODE_ALL.equals(mode)) {
                    violations.add(where + " 的汇合模式 " + mode + " 暂不支持（第一批只有 all，"
                            + "quorum 需要超时机制，否则少数分支不返回时流程会永久卡住）");
                }
            }
        }
        return violations;
    }

    private static boolean isConditionNode(WorkflowSpec.WorkflowNode node) {
        if (node == null) {
            return false;
        }
        try {
            return WorkflowNodeType.fromSpec(node.getType()) == WorkflowNodeType.CONDITION;
        } catch (ControlPlaneValidationException e) {
            return false;
        }
    }

    /**
     * 图结构检查：无环 → 入口唯一 → 全部可达。三步有先后，因为后两步都建立在"图是 DAG"之上。
     *
     * <p>环的报错要指出<b>环上有哪些节点</b>：只说"存在环"，用户对着十几个节点的图无从下手。
     * 这里用 Kahn 算法剩下的那批节点近似环上集合 —— 严格说是"环及其下游"，
     * 但配合画布足够定位（先看入度不为 0 的那几个）。
     */
    private List<String> checkGraphShape(Set<String> nodeKeys, List<WorkflowSpec.WorkflowEdge> edges) {
        List<String> violations = new ArrayList<>();

        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new LinkedHashMap<>();
        for (String key : nodeKeys) {
            inDegree.put(key, 0);
            successors.put(key, new ArrayList<>());
        }
        for (WorkflowSpec.WorkflowEdge edge : edges) {
            String from = edge.fromOrEmpty();
            String to = edge.toOrEmpty();
            if (!inDegree.containsKey(from) || !inDegree.containsKey(to)) {
                continue; // 两端存在性上面已经报过
            }
            inDegree.merge(to, 1, Integer::sum);
            successors.get(from).add(to);
        }

        // Kahn：从入度为 0 的开始剥，剥不掉的就在环上（或环的下游）
        Map<String, Integer> remaining = new HashMap<>(inDegree);
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            if (entry.getValue() == 0) {
                ready.addLast(entry.getKey());
            }
        }
        Set<String> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            String key = ready.pollFirst();
            if (!visited.add(key)) {
                continue;
            }
            for (String next : successors.getOrDefault(key, List.of())) {
                if (remaining.merge(next, -1, Integer::sum) == 0) {
                    ready.addLast(next);
                }
            }
        }
        if (visited.size() != nodeKeys.size()) {
            List<String> cyclic = new ArrayList<>();
            for (String key : nodeKeys) {
                if (!visited.contains(key)) {
                    cyclic.add(key);
                }
            }
            violations.add("工作流存在环（或环的下游），这些节点无法排序：" + String.join(", ", cyclic)
                    + "。工作流必须是有向无环图");
            return violations; // 有环时下面两步的结论没有意义
        }

        // 入口唯一：入度为 0 的节点就是"从这里开始"，多于一个时无法确定起点
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                entries.add(entry.getKey());
            }
        }
        if (entries.isEmpty()) {
            // DAG 且无入度 0 的节点 —— 上面已排除环，这里是防御性分支
            violations.add("找不到入口节点（没有任何节点入度为 0）");
            return violations;
        }
        if (entries.size() > 1) {
            violations.add("入口不唯一，这些节点都没有入边：" + String.join(", ", entries)
                    + "。请只保留一个起点（其余节点连上入边）");
            return violations;
        }

        // 可达性：从唯一入口出发能走到的才算在链路里
        Set<String> reachable = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(entries.get(0));
        while (!stack.isEmpty()) {
            String key = stack.pop();
            if (!reachable.add(key)) {
                continue;
            }
            for (String next : successors.getOrDefault(key, List.of())) {
                stack.push(next);
            }
        }
        List<String> unreachable = new ArrayList<>();
        for (String key : nodeKeys) {
            if (!reachable.contains(key)) {
                unreachable.add(key);
            }
        }
        if (!unreachable.isEmpty()) {
            violations.add("这些节点从入口 " + entries.get(0) + " 出发不可达，永远不会执行："
                    + String.join(", ", unreachable));
        }
        return violations;
    }
}