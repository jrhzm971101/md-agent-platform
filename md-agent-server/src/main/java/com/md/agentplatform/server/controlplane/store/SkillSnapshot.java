package com.md.agentplatform.server.controlplane.store;

import com.md.agentplatform.server.controlplane.store.entity.SkillMetaEntity;

import java.util.Map;

/**
 * 一个 skill 的<b>完整快照载荷</b>：内容 + 治理元数据。
 *
 * <h2>为什么 skill 的快照要单独定一个类型</h2>
 * 另外三类资源的快照分别是 md_agent 束 / md_prompt 整行 / md_mcp_server 整行，
 * 一个对象就够了。skill 不同：内容在官方 agentscope 库、治理字段在 md_skill_meta，
 * 两边都要进快照才能完整重建（DDL 里 {@code md_resource_version.snapshot_json} 的约定也这么写）。
 *
 * <h2>为什么不直接序列化官方的 {@code AgentSkill}</h2>
 * {@code AgentSkill} 没有无参构造器，也没有 Jackson 的 Creator 标注 —— 写进去能不能读回来
 * 取决于 Jackson 的版本与模块配置，属于「别人的类，我的持久化格式」这种脆弱依赖。
 * 这里只抄<b>我们要用的三个字段</b>（描述 / 正文 / 附属文件），
 * 官方类将来加字段也不会污染已落库的快照。
 *
 * <p>{@code description} 与 {@code meta.description} 刻意都留着：前者的权威位置是官方表
 * （框架读它拼技能提示词），后者是控制面的缓存副本（列表页不逐条回查官方表）。
 * 一份快照必须能同时还原这两处，否则回滚后两者会不一致。
 */
public record SkillSnapshot(
        /** md_skill_meta 整行。 */
        SkillMetaEntity meta,
        /** 官方 agentscope_skills.description。 */
        String description,
        /** 官方 agentscope_skills.skill_content（SKILL.md 正文）。 */
        String skillContent,
        /** 官方 agentscope_skill_resources：附属文件路径 → 内容。 */
        Map<String, String> resources) {
}
