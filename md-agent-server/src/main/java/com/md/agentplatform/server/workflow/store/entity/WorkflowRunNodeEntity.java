package com.md.agentplatform.server.workflow.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * {@code md_workflow_run_node} 的行模型 —— 一次执行里每个节点一行。
 *
 * <h2>一、为什么节点状态独立成表而不是 run 的一个 JSON 列</h2>
 * 页面按节点渲染执行图，每个节点要单独取状态与输入输出；塞 JSON 会让
 * "这一轮里哪些节点跑完了"变成全量读取 + 应用层过滤。
 * 而且 {@code (run_id, node_key)} 的唯一键让"重复派发同一节点"变成一次幂等错误
 * 而不是脏数据 —— 这是后续批次引入重试 / 租约时最需要的一道防线。
 *
 * <h2>二、agentRunId 是通向执行链路观测的钥匙</h2>
 * 页面从工作流运行详情点进某个 AGENT 节点，就是拿它去 {@code /api/traces/{runId}}。
 * 它可空，而且 CONDITION / JOIN 节点<b>永远</b>为空 —— 那两类不调用模型，没有执行链路。
 * 页面必须能区分"这个节点没有链路"（正常）与"链路 id 丢了"（异常），
 * 所以判定依据是 nodeType 而不是 agentRunId 是否为空。
 *
 * <h2>三、state 与 run 的 state 不是一回事</h2>
 * SKIPPED 只出现在节点上：condition 没选中的分支，其下游节点会被标成 SKIPPED，
 * 表示"这次执行明确地没有走它"。这与 PENDING（还没轮到，通常是失败后卡住的）不同，
 * 页面用两种颜色区分，人能一眼看出是"被跳过"还是"没跑到"。
 */
@Getter
@Setter
@TableName(value = "md_workflow_run_node", autoResultMap = true)
public class WorkflowRunNodeEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String runId;

    private String nodeKey;

    /** AGENT | CONDITION | JOIN（取自 WorkflowNodeType）。 */
    private String nodeType;

    /** PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED。 */
    private String state;

    @TableField(value = "input_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> input;

    @TableField(value = "output_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> output;

    /** CONDITION 选中的出边目标节点 key；未选中被跳过的分支写 SKIPPED 而不是这个值。 */
    private String branchTaken;

    private String failureCode;

    private String failureMessage;

    /** 该节点呼出的执行链路 id（md_agent_run.run_id）；非 AGENT 节点恒为 null。 */
    private String agentRunId;

    private Long startedAtMs;

    private Long endedAtMs;

    // ---------- 取值常量 ----------

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_SKIPPED = "SKIPPED";

    /** 节点失败分类：目标 agent 在发布后又被删了（发布时校过，所以这是运行期才会发生的漂移）。 */
    public static final String FAILURE_AGENT_MISSING = "AGENT_NOT_FOUND";
    /** 节点失败分类：condition 的全部出边条件都不成立，无路可走。 */
    public static final String FAILURE_NO_BRANCH = "NO_BRANCH_MATCHED";
    /** 节点失败分类：其余未归类异常。 */
    public static final String FAILURE_INTERNAL = "INTERNAL";
}