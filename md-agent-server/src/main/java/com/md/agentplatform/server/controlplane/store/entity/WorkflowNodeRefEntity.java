package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_workflow_node_ref} 的行模型 —— 发布时投影出的「这个节点引用了谁」。
 *
 * <h2>一、为什么需要它：spec 是 JSON，反查不了</h2>
 * 工作流的节点图存成一段 JSON（理由见 workflow-schema.sql 头部注释），
 * JSON 列没法建索引，于是"删掉 agent X 会毁掉哪些工作流"这个问题，
 * 只能靠把 spec 全文读出来逐个解析。反查发生在<b>每次删除 agent 之前</b>，
 * 而那时要遍历的是全表所有工作流的 spec —— 显然不能这么做。
 *
 * <p>所以发布时把引用投影成行（本表），反查变成一次带索引的等值查询。
 * 这就是"用 JSON 存内容"所付出的、唯一需要补偿的能力，补偿一处比拆成两张关系表便宜得多。
 *
 * <h2>二、只在发布时写，且按 revision 记</h2>
 * 草稿里的引用不投影：草稿随时会改，而草稿引用错了不会影响任何线上行为。
 * 记 {@code revision} 而不是只记 workflow_key，是为了让报错能说清
 * 「工作流 A 的 v3 版本里 node triage 引用了它」—— 只有这个粒度才能直接跳到画布上那一版。
 * 同一工作流发布 v4 时新增 rows，v3 的 rows 保留（v3 仍可能被某次运行冻结着）。
 */
@Getter
@Setter
@TableName("md_workflow_node_ref")
public class WorkflowNodeRefEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String workflowKey;

    /** 所属发布版本号（= md_resource_version.version）；同一个工作流的多版引用并存。 */
    private Integer revision;

    private String nodeKey;

    /** AGENT | CONDITION | JOIN（取自 WorkflowNodeType）。 */
    private String nodeType;

    /** 被引用的资源类型；第一批只有 AGENT —— 将来 condition 可能引用知识库、subrun 引用另一个工作流。 */
    private String refType;

    /** 被引用资源的业务键（第一批是 agent 名）。 */
    private String refId;

    /** 第一批唯一会出现的引用类型。写成常量而不是散落的字面量：它要与建表脚本的注释保持同一个词。 */
    public static final String REF_AGENT = "AGENT";
}