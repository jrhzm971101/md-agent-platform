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
 * {@code md_prompt} 的行模型 —— 提示词正文。
 *
 * <h2>正文为什么不在这里做任何加工</h2>
 * {@code body} 存的是<b>原文</b>（纯文本 / Markdown），后端既不解析、也不改写：
 * 同一份正文有两个消费者 —— 装配期原样进 {@code builder.sysPrompt(...)}，页面里原样进编辑器。
 * 任何"顺手美化"（去空白、重排标题）都会让页面看到的内容与实际生效的内容产生偏差，
 * 而这类偏差在排查"为什么模型行为不对"时最难发现。
 *
 * <h2>description 与 agent 的关系</h2>
 * 旧数据里 prompt 的"描述"其实就是对应 agent 的 description（因为本地 md 时代
 * 提示词正文写在 agent 文件的 frontmatter 下方）。迁移时直接沿用，
 * 之后 prompt 与 agent 各自独立演进，不再联动。
 */
@Getter
@Setter
@TableName(value = "md_prompt", autoResultMap = true)
public class PromptEntity implements GovernedRow {

    @TableId(value = "prompt_key", type = IdType.INPUT)
    private String promptKey;

    /**
     * 展示名 —— 页面上唯一该露给人看的名字。
     *
     * <p>与 agent 同因不同果：prompt-key 是装配期 sysPrompt 的解析键，也是 md_agent_ref.prompt_key
     * 的引用值，所以它不能承担展示职责（详见 AgentEntity.displayName）。
     * 本列只做展示，不参与装配、不参与唯一键、不可被引用。
     */
    private String displayName;

    private String description;

    @TableField(value = "biz_tags", typeHandler = JacksonTypeHandler.class)
    private List<String> bizTags;

    /** 提示词正文（纯文本 / Markdown）。 */
    private String body;

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
