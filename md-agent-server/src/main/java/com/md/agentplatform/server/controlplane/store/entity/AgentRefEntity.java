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
 * {@code md_agent_ref} 的行模型 —— 「这个 agent 用了谁」。
 *
 * <p>本表的每一列都是<b>引用</b>，页面上对应一个下拉多选控件，因此存的一律是"被引用者的名字"，
 * 不存被引用者的内容（prompt 正文在 md_prompt、skill 内容在 agentscope 库、MCP 端点在 md_mcp_server）。
 * 这条约定是整套设计的地基：一旦有人往这里塞内容副本，就回退成"三套真相"的老问题。
 *
 * <h2>为什么单独一张表，而不是并进 md_agent</h2>
 * 引用会随页面勾选频繁变动，运行时旋钮很少动。合在一起意味着每次勾一个 skill 都要把
 * 模型、迭代上限等配置整行重写一遍，出问题时无法从"哪一列被改动"上区分是谁写坏的。
 * 另外「谁引用了 prompt X」这个反查（删除保护）只需扫本表。
 *
 * <p>{@code prompt_key} 是普通列（唯一引用，一对一）；其余四项是 JSON 数组（多引用）。
 * 数组顺序有语义的只有 {@code mcp_names}（决定装配顺序），其余按页面选择顺序存即可。
 */
@Getter
@Setter
@TableName(value = "md_agent_ref", autoResultMap = true)
public class AgentRefEntity {

    @TableId(value = "agent_name", type = IdType.INPUT)
    private String agentName;

    /** 引用 md_prompt.prompt_key；非空时其正文会成为 sysPrompt（覆盖定义里内联的提示词）。 */
    private String promptKey;

    /** Spring 工具 bean 名列表，来源是 tool 只读目录（扫 @Tool 注解的 bean）。 */
    @TableField(value = "tools", typeHandler = JacksonTypeHandler.class)
    private List<String> tools;

    /** 中间件 bean 名列表，来源是 middleware 只读目录（扫 MiddlewareBase 的 bean）。 */
    @TableField(value = "middleware", typeHandler = JacksonTypeHandler.class)
    private List<String> middleware;

    /** 引用 md_skill_meta.skill_name 列表。 */
    @TableField(value = "skill_names", typeHandler = JacksonTypeHandler.class)
    private List<String> skillNames;

    /** 引用 md_mcp_server.server_name 列表，顺序即装配顺序。 */
    @TableField(value = "mcp_names", typeHandler = JacksonTypeHandler.class)
    private List<String> mcpNames;

    /** RAG Knowledge bean 名列表。 */
    @TableField(value = "knowledges", typeHandler = JacksonTypeHandler.class)
    private List<String> knowledges;
}
