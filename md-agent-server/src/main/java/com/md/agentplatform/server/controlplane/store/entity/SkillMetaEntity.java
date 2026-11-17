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
 * {@code md_skill_meta} 的行模型 —— skill 的<b>治理元数据</b>（不含内容）。
 *
 * <h2>为什么 skill 有"两张表 + 两个库"，而另外三类资源只有一张</h2>
 * skill 的<b>内容</b>（SKILL.md 正文 + 附属文件）由官方 {@code MysqlSkillRepository} 管理，
 * 它自己建表（{@code agentscope} 库的 {@code agentscope_skills} / {@code agentscope_skill_resources}），
 * 且它的库名校验只允许字母数字下划线，因此无法并入本库（设计文档 §4.3.1）。
 * 本表只补官方表<b>没有</b>而控制面必须有的东西：展示名、业务标签、草稿/发布态、版本号。
 *
 * <h2>一致性约定（改本类或调用方前必读）</h2>
 * <ul>
 *   <li><b>内容的权威位置永远是官方表</b>，本表绝不存正文 —— 否则立刻变成两处真相；</li>
 *   <li>{@code description} 是<b>缓存</b>：列表页要展示描述，逐条回查官方表代价高，
 *       因此保存时从官方表同步一份。以官方表为准；</li>
 *   <li>若有人绕过控制面直接改官方表，本表会漂移 —— 列表接口需要做一次
 *       「官方表 name 集合 vs 本表 name 集合」的差集校验并告警（设计文档 §10.7）。</li>
 * </ul>
 *
 * <p>{@code skill_name} 与官方表的 {@code name} 一一对应，因此这里不自增 id，
 * 直接用 skill_name 当主键。
 */
@Getter
@Setter
@TableName(value = "md_skill_meta", autoResultMap = true)
public class SkillMetaEntity implements GovernedRow {

    @TableId(value = "skill_name", type = IdType.INPUT)
    private String skillName;

    /** 展示名（官方表只有 name）。 */
    private String displayName;

    /** 缓存官方表的 description，避免列表页逐条回查官方表。 */
    private String description;

    @TableField(value = "biz_tags", typeHandler = JacksonTypeHandler.class)
    private List<String> bizTags;

    /** DRAFT | PUBLISHED。 */
    private String status;

    private Integer version;

    private Integer publishedVersion;

    private Long publishedAtMs;

    private Boolean enabled;

    private Long createdAtMs;

    private Long updatedAtMs;

    private String createdBy;

    private String updatedBy;
}
