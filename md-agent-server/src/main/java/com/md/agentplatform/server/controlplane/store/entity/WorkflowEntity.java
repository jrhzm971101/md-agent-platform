package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.md.agentplatform.server.workflow.WorkflowSpec;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_workflow} 的行模型 —— 工作流定义（草稿内容 + 发布状态）。
 *
 * <h2>一、它为什么是第六个 GovernedRow 实现</h2>
 * 前五类资源（agent / prompt / skill / mcp / channel / model）共用的那 8 个治理字段，
 * 工作流逐字需要同一套：草稿可改、发布生成快照、可回滚、版本单调递增。
 * 实现 {@link GovernedRow} 之后，"新建即 v1 + DRAFT""保存即 version+1 并保留发布信息"
 * "发布不推 version"这三段规则自动与另外五类一致 —— 不需要在这里重写一遍。
 *
 * <h2>二、spec 是这一段 JSON 里唯一的"内容"</h2>
 * 其余字段全是治理字段（谁建的、什么时候改的、发布到哪一版）。
 * 内容本身只有 {@code spec} 一个对象，它的形状见 {@link WorkflowSpec}。
 *
 * <p>为什么字段类型是 {@code WorkflowSpec} 而不是 {@code String}：与 {@code md_agent_run.input}
 * 的做法一致（那里是 {@code Map<String,Object>}）。让存储层负责序列化、让业务层只认对象，
 * 好处是"spec 必须是合法 JSON"这件事由 MySQL 的 JSON 列兜底，
 * 而不是靠每个写入点自己记得调一次 parse。
 *
 * <p><b>改本类前必读</b>：JSON 列必须同时满足
 * {@code @TableName(autoResultMap = true)} 与字段上显式声明 {@code typeHandler = JacksonTypeHandler.class}。
 * 少了前者会"写得进去、读回来是 null"（写走参数绑定，与 autoResultMap 无关）。
 */
@Getter
@Setter
@TableName(value = "md_workflow", autoResultMap = true)
public class WorkflowEntity implements GovernedRow {

    /** 业务键，页面提交时确定；装配与引用都用它，创建后不可改（路径是权威，见 WorkflowController）。 */
    @TableId(value = "workflow_key", type = IdType.INPUT)
    private String workflowKey;

    /**
     * 展示名 —— 页面上唯一该露给人看的名字。
     *
     * <p>与本项目其它资源的约定一致：{@code workflow_key} 是业务键（出现在引用、URL、日志里），
     * 一旦允许它承担展示职责，改名就会变成一次跨表的数据迁移。所以展示一律走本列。
     */
    private String displayName;

    private String description;

    /** 节点图（nodes + edges）。 */
    @TableField(value = "spec_json", typeHandler = JacksonTypeHandler.class)
    private WorkflowSpec spec;

    /** DRAFT | PUBLISHED。 */
    private String status;

    private Integer version;

    private Integer publishedVersion;

    private Long publishedAtMs;

    private Long createdAtMs;

    private Long updatedAtMs;

    private String createdBy;

    private String updatedBy;
}