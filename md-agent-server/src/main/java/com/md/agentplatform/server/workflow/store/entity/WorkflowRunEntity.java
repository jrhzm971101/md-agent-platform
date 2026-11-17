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
 * {@code md_workflow_run} 的行模型 —— 一次工作流执行一行。
 *
 * <h2>一、它与 md_agent_run 是两层，不要合并</h2>
 * 一个 AGENT 节点执行一次 = 一条 {@code md_agent_run}；一次工作流执行 = 多条 md_agent_run。
 * 所以两者是 1:N，关联靠 {@code md_agent_run.workflow_run_id + workflow_node_key}
 * （见 workflow-schema-upgrade-2026-09-23.sql）。
 * 把编排状态塞进 md_agent_run 会让"一次 agent 调用"这张表长出"编排"的字段，
 * 而绝大多数调用根本不是工作流发起的。
 *
 * <h2>二、state 第一批只有三种</h2>
 * {@code RUNNING / SUCCEEDED / FAILED}。官方还有 waiting / paused / cancelled 等，
 * 但那些只在 timer / signal 节点下才有意义（要能挂起并跨进程恢复），
 * 而第一批不含这两类节点。留一个永远不会被写入的取值，只会让读代码的人以为系统支持挂起。
 *
 * <h2>三、revision 为什么必须冻结</h2>
 * 执行期间有人改了草稿并发布，这次执行仍按启动时的版本跑完。
 * 不冻结的话，页面会出现"图变了但节点状态对不上"——节点 key 在新版本里可能已经不存在。
 */
@Getter
@Setter
@TableName(value = "md_workflow_run", autoResultMap = true)
public class WorkflowRunEntity {

    /** 主键由业务生成（TraceRunIdGenerator，与执行链路共用同一套 id 形状），不让数据库回填。 */
    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    private String workflowKey;

    /** 本次执行冻结的版本号。 */
    private Integer revision;

    /** manual | cron | event；第一批只有 manual。 */
    private String triggerType;

    /** 触发者（操作人 / 作业名）；null = 入口方没写，页面显示为空而不是编一个值。 */
    private String triggerRef;

    private String sessionId;

    private String userId;

    /** RUNNING | SUCCEEDED | FAILED。 */
    private String state;

    @TableField(value = "input_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> input;

    /** 终态输出：每个节点的结果汇总。进行中为 null。 */
    @TableField(value = "output_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> output;

    /** 失败分类（VALIDATION | NODE_FAILED | INTERNAL）；成功时为 null。 */
    private String failureCode;

    private String failureMessage;

    /** 失败策略 fail_fast | continue；第一批只有 fail_fast。 */
    private String policy;

    private long startedAtMs;

    /** 进行中为 null；用包装类型而不是 long：null（还没结束）与 0（1970 年）语义完全不同。 */
    private Long endedAtMs;

    private long createdAtMs;

    // ---------- 取值常量：库里存的就是这些字符串 ----------

    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";

    public static final String TRIGGER_MANUAL = "manual";

    public static final String POLICY_FAIL_FAST = "fail_fast";

    public static final String FAILURE_VALIDATION = "VALIDATION";
    public static final String FAILURE_NODE_FAILED = "NODE_FAILED";
    public static final String FAILURE_INTERNAL = "INTERNAL";
}