package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * {@code md_agent} 的行模型 —— 一个 agent 一行，只装「身份 + 生命周期」。
 *
 * <h2>一、为什么实体不直接用 AgentDefinition</h2>
 * {@code AgentDefinition} 是<b>装配视角</b>的对象：它按 builder 的参数组织（modelRef、maxIters、
 * subAgents……），且为了兼容 MD / Nacos 两种历史来源，字段是「typed 超集」。
 * 行模型是<b>存储视角</b>的：列名、可空性、JSON 列与表结构一一对应。
 * 两者一旦合并，加一个"只在装配期派生、不落库"的字段就得回头给表加列或者打
 * {@code @TableField(exist = false)} —— 那是把存储细节反向渗进领域模型。
 * 转换只发生在 {@code AgentDefinitionResolver} 一处。
 *
 * <h2>二、JSON 列的两个必要条件（改本类前必读）</h2>
 * <ol>
 *   <li>{@code @TableName} 必须带 {@code autoResultMap = true}；
 *   <li>JSON 字段必须显式声明 {@code typeHandler = JacksonTypeHandler.class}。
 * </ol>
 * 少了前者：查询回来恒为 null，而写入正常（写入走参数绑定，与 autoResultMap 无关），
 * 表现为"存进去了但读不出来"，极难定位。这条坑在 {@code MyBatisTraceStore} 的实体注释里
 * 已经踩过一次，这里沿用同样写法。{@code biz_tags} 是本类唯一的 JSON 列。
 *
 * <h2>三、布尔列一律用包装类型 Boolean</h2>
 * {@code enabled} 对应 TINYINT(1) NULL。用原始类型 boolean 会把 NULL 读成 false，
 * 语义就丢了；本表虽然 NOT NULL，但同目录下 runtime / workspace 两张表的开关是
 * <b>三态</b>（NULL=未设置 / 0=显式关闭 / 1=显式开启），统一用包装类型避免两套写法。
 */
@Getter
@Setter
@TableName(value = "md_agent", autoResultMap = true)
public class AgentEntity implements GovernedRow {

    /** 业务主键（AgentDefinition.name），不由数据库生成，所以是 {@code IdType.INPUT}。 */
    @TableId(value = "agent_name", type = IdType.INPUT)
    private String agentName;

    /**
     * 展示名 —— 页面上唯一该露给人看的名字。
     *
     * <p>为什么不复用 agent-name：agent-name 同时兼任运行时注册键（AgentManager 的注册表键）、
     * 前端路由参数（/chat/:agentId）与装配期的 AgentDefinition.name，因此它必须稳定、可放进 URL，
     * 且改一次就等于新建一个 agent。让这种列去承担"给人看的名字"，改一次展示口径就要走整套
     * 版本快照与重新装配，代价和收益完全不成比例。
     *
     * <p>本列<b>只做展示</b>：不参与装配、不参与唯一键、不能作为 md_agent_ref 的引用值，
     * 也不能被当成任何查找键。装配层读的永远是 agent-name。
     *
     * <p>非空由 validateAgentContent 在写入前拦住 —— 页面宁可逼人在创建时想一个名字，
     * 也不要出现一张写着"（未命名）"的卡片。
     */
    private String displayName;

    /** REACT | HARNESS。决定装配时走哪条 builder 链。 */
    private String agentType;

    private String description;

    /** 业务标签，仅控制面用（分组/筛选），不参与装配。 */
    @TableField(value = "biz_tags", typeHandler = JacksonTypeHandler.class)
    private List<String> bizTags;

    /** 能力分组（页面筛选用）。 */
    private String category;

    /** DRAFT | PUBLISHED。 */
    private String status;

    /** 内容版本号，每次保存 +1。 */
    private Integer version;

    /** 已发布版本号；null = 从未发布。用包装类型是为了区分「没发布过」与「发布的是第 0 版」。 */
    private Integer publishedVersion;

    private Long publishedAtMs;

    /** 是否参与装配。 */
    private Boolean enabled;

    private Long createdAtMs;

    private Long updatedAtMs;

    private String createdBy;

    private String updatedBy;
}
