package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code md_agent_sub_agent} 的行模型 —— HARNESS 的子 agent 声明，一行一个。
 *
 * <h2>为什么这张表逐字段建列，而 runtime 那边用小配置却用 JSON</h2>
 * 判断依据是「页面上是不是一个可编辑的列表」：
 * <ul>
 *   <li>子 agent <b>是</b>：页面上是一个可增删的表格，每行一个表单。需要按列校验
 *       （maxIters 必须为正整数、temperature 范围 0~2）、按 sort_order 排序、
 *       按 sub_name 去重（唯一键 uk_agent_sub）。这些能力塞进 JSON 列就全丢了。</li>
 *   <li>runtime 的小配置 <b>不是</b>：它们是一组固定开关和固定阈值，界面上一行一个输入框，
 *       不会增删行，也不会按子字段查询，所以用 JSON 列更省。</li>
 * </ul>
 *
 * <h2>id 用自增而不是业务主键</h2>
 * 业务唯一性由 {@code (agent_name, sub_name)} 表达（DDL 里有唯一键），
 * 自增 id 只是让 MyBatis-Plus 有个可以 updateById / deleteById 的把手。
 * 保存时的策略是"先按 agent_name 全删、再插当前列表"（见 MyBatisControlPlaneStore），
 * 不做逐行 diff —— 子 agent 数量是个位数，diff 的复杂度换不来任何收益。
 */
@Getter
@Setter
@TableName(value = "md_agent_sub_agent", autoResultMap = true)
public class AgentSubAgentEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 父 agent（HARNESS）。 */
    private String agentName;

    /** 子 agent 名；本地子 agent = 另一个 md_agent.agent_name。 */
    private String subName;

    /** 路由展示顺序（暴露给模型的路由描述按此排序）。 */
    private Integer sortOrder;

    /** 派发职责说明；不填则装配时回落到子 agent 自己的 description。 */
    private String subDescription;

    /** ISOLATED | SHARED。 */
    private String workspaceMode;

    private String workspacePath;

    /** 内联 AGENTS.md 正文；空则读子 agent 自己定义里的提示词。 */
    private String inlineAgentsBody;

    private String modelRef;

    private Integer maxIters;

    private Integer steps;

    private BigDecimal temperature;

    private BigDecimal topP;

    private String variant;

    /** PRIMARY | SUBAGENT | ALL。 */
    private String mode;

    private Boolean hidden;

    private Boolean persistSession;

    private Boolean inheritParentPermissions;

    private Boolean exposeToUser;

    @TableField(value = "tools", typeHandler = JacksonTypeHandler.class)
    private List<String> tools;

    @TableField(value = "skills", typeHandler = JacksonTypeHandler.class)
    private List<String> skills;

    // ==================== 远端子 agent（A2A） ====================

    private Boolean remote;

    private String url;

    @TableField(value = "headers", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> headers;

    private Boolean remoteStreaming;

    private String remoteStreamDetail;

    private String remoteAskPolicy;

    @TableField(value = "remote_context_attributes", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> remoteContextAttributes;
}