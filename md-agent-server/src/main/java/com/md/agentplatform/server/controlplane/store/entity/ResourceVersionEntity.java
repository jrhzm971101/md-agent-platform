package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_resource_version} 的行模型 —— 四类资源共用的版本快照。
 *
 * <h2>snapshot_json 为什么是 String 而不是 Map</h2>
 * 快照的内容结构<b>随资源类型而变</b>：
 * <ul>
 *   <li>AGENT = 主表 + 5 张关联表拼出来的复合对象（含 subAgents 数组）；</li>
 *   <li>SKILL = 官方表内容（正文 + 附属文件）+ 本表治理元数据。</li>
 * </ul>
 * 若把它声明成 {@code Map<String, Object>}（即让 MyBatis 去做 JSON 反序列化），
 * 存储层就必须知道每种类型的目标结构，等于把"按类型组装快照"的职责漏进行模型。
 * 保持为不透明文本，序列化与反序列化都留在写快照的服务层，
 * 存储层只负责"存取一段文本" —— 这也是回滚功能将来能独立演进的前提。
 *
 * <h2>只在发布时写入</h2>
 * 草稿的每次保存都落快照会让本表迅速膨胀，而草稿的价值本来就低（还没上线）。
 * 因此版本序列 = 发布序列，与 {@code published_version} 一一对应。
 */
@Getter
@Setter
@TableName("md_resource_version")
public class ResourceVersionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** AGENT | PROMPT | SKILL | MCP。 */
    private String resourceType;

    /** 对应资源的主键值（agent_name / prompt_key / server_name / skill_name）。 */
    private String resourceName;

    /** 快照对应的版本号，与主表 published_version 一致。 */
    private Integer version;

    /** 整行快照（结构随类型而变，见类注释）。 */
    private String snapshotJson;

    private String changeNote;

    private Long createdAtMs;

    private String createdBy;
}