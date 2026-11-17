package com.md.agentplatform.server.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流定义（{@code md_workflow.spec_json} 的形状）—— 一张无环有向图。
 *
 * <pre>
 * {
 *   "nodes": [
 *     { "key": "triage", "type": "agent",     "label": "分诊",     "agentId": "triage_agent" },
 *     { "key": "route",  "type": "condition", "condition": "triage.text != \"\"" },
 *     { "key": "merge",  "type": "join",      "join": { "mode": "all" } }
 *   ],
 *   "edges": [
 *     { "from": "triage", "to": "route" },
 *     { "from": "route",  "to": "merge", "condition": "triage.text != \"\"" },
 *     { "from": "route",  "to": "merge" }
 *   ]
 * }
 * </pre>
 *
 * <h2>一、为什么用普通类而不是 record</h2>
 * 这是会经由 Jackson 从 JSON 反序列化的对象，而某几个字段必须可以被"没写"（null）表达。
 * record 的规范构造器要求全参，反序列化端要靠参数名对齐 —— 本项目没有开
 * {@code -parameters} 编译开关（之前 Servlet 报过 "parameter name information not available
 * via reflection"），所以这里沿用与实体类一致的 {@code @Getter @Setter} 写法。
 *
 * <h2>二、字段分三类，不要混</h2>
 * <ol>
 *   <li>{@link WorkflowNode#key} 与 {@link WorkflowNode#type} —— <b>结构字段</b>，
 *       校验、拓扑排序、执行都靠它们，任何节点都必须有；</li>
 *   <li>{@code agentId} / {@code condition} / {@code join} —— <b>按类型使用的配置字段</b>，
 *       用什么由 {@code type} 决定。它们平铺在同一层而不是各自套一个对象，
 *       是为了让手写 JSON 的人少一层括号；</li>
 *   <li>{@code teamRef} / {@code signalName} / {@code timer} / {@code approval} / {@code subrun}
 *       —— <b>本版本不消费但保留</b>的字段。</li>
 * </ol>
 *
 * <h2>三、为什么"不消费的字段"也要留</h2>
 * 校验会让含 {@code timer} 节点的工作流发布失败，但<b>保存草稿是允许的</b>
 * （否则没法先把图搭出来再一个个补）。若解析时把不认识的字段丢掉，用户存一次草稿，
 * 他写好的 timer 配置就没了 —— 报错还能改，静默丢失没法查。所以这些字段声明为
 * {@code Object}（内容原样透传，本版本既不看也不改），并允许未知字段（见
 * {@code @JsonIgnoreProperties}），让整份 spec 能完整地存进去、读出来。
 *
 * <p>代价是这些字段的拼写错误在本版本不会被发现。这是可接受的：它们本来就还没生效。
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowSpec {

    private List<WorkflowNode> nodes = new ArrayList<>();

    private List<WorkflowEdge> edges = new ArrayList<>();

    /** 节点列表；永不返回 null —— 调用方（校验器、引擎）不必到处判空。 */
    public List<WorkflowNode> nodesOrEmpty() {
        return nodes == null ? List.of() : nodes;
    }

    public List<WorkflowEdge> edgesOrEmpty() {
        return edges == null ? List.of() : edges;
    }

    /** 全部节点 key，保持声明顺序；用于去重检查与"边引用了不存在的节点"报错。 */
    public Set<String> nodeKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (WorkflowNode node : nodesOrEmpty()) {
            if (node != null && node.getKey() != null && !node.getKey().isBlank()) {
                keys.add(node.getKey().trim());
            }
        }
        return keys;
    }

    /** 按 key 找节点；不存在返回 null。 */
    public WorkflowNode node(String key) {
        for (WorkflowNode node : nodesOrEmpty()) {
            if (node != null && node.getKey() != null && node.getKey().trim().equals(key)) {
                return node;
            }
        }
        return null;
    }

    /**
     * 一个节点的定义。
     *
     * <p>{@code label} 只用于展示（画布上节点的标题）。为空时页面回落到 {@code key} ——
     * 这与本项目「xxId 不展示、展示 name」的约定一致：key 是业务键，label 才是给人看的。
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkflowNode {

        /** 节点 key，同一流程内唯一；边引用的是它。 */
        private String key;

        /** 节点类型，取值见 {@link WorkflowNodeType}。 */
        private String type;

        /** 画布上的显示名；为空时页面回落到 key。 */
        private String label;

        /** {@code type=agent} 时必填：目标 agent 名（md_agent.agent_name）。 */
        private String agentId;

        /** {@code type=condition} 时必填：分支表达式，语法见 {@link WorkflowConditionEvaluator}。 */
        private String condition;

        /** {@code type=join} 时的汇合配置。 */
        private JoinSpec join;

        // ---------- 以下为后续批次的节点配置：本版本原样保留、不解析、不消费 ----------

        /** {@code type=team} 用的团队引用。 */
        private Object teamRef;

        /** {@code type=signal} 用的信号名。 */
        private Object signalName;

        private Object timer;

        private Object approval;

        private Object subrun;

        /** 边的引用目标最终都要落到 key 上，所以 key 一律去空白后比较。 */
        public String keyOrEmpty() {
            return key == null ? "" : key.trim();
        }
    }

    /**
     * {@code join} 节点的汇合配置。
     *
     * <p>第一批只支持 {@code all}（所有入边都到达才放行）。官方还有 {@code quorum}
     * （等到 N 条）与对应的 {@code quorum} 阈值 —— 这里把字段先声明出来并允许保存，
     * 但校验阶段只放行 {@code all}：quorum 的语义牵涉"少数分支永不返回时怎么办"，
     * 而第一批没有超时与取消机制，放行它只会做出一个会永久卡住的流程。
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JoinSpec {

        /** all | quorum；第一批只实现 all。 */
        private String mode;

        /** quorum 模式的阈值；第一批不消费。 */
        private Integer quorum;
    }

    /**
     * 一条有向边。
     *
     * <p>{@code condition} 只在<b>起点是 condition 节点</b>时有意义，其余情况必须留空 ——
     * 这条约束由校验器保证（见 WorkflowValidator）。之所以不在这里禁止，
     * 是因为它还承担一个观测职责：条件节点求值后，"哪条边被选中"会写进
     * {@code md_workflow_run_node.branch_taken}，而页面需要回显当时用的是哪个表达式。
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkflowEdge {

        private String from;

        private String to;

        /** 分支条件表达式；仅当 from 是 condition 节点时使用。 */
        private String condition;

        public String fromOrEmpty() {
            return from == null ? "" : from.trim();
        }

        public String toOrEmpty() {
            return to == null ? "" : to.trim();
        }
    }
}