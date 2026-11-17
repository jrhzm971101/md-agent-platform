package com.md.agentplatform.server.agent;

import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.util.List;

/**
 * 「一个 MCP 服务端」在装配期的完整形态 = 连接 + 该连接自己的工具白/黑名单。
 *
 * <h2>为什么不是直接返回 {@link McpClientWrapper}</h2>
 * 工具过滤名单原本挂在<b>agent</b> 上（{@code agent.enable-tools}），一次改造后下沉到
 * <b>MCP 服务端</b>（设计文档 D10）：同一个 MCP 被两个 agent 引用时，白名单只该有一份，
 * 否则会出现「同一个 MCP 在两个 agent 里看到的工具不一样」这种没法在页面上解释的状态。
 *
 * <p>名单和连接因此必须成对传递 —— 只传连接的话，注册工具时就不知道按谁的名单过滤。
 * 用 record 而不是 Map：这两项永远一起出现、且都是不可变值。
 *
 * @param name         MCP 服务端名（{@code md_mcp_server.server_name}）
 * @param client       已握手的 MCP 客户端（同名复用，见 {@link DbMcpClientFactory}）
 * @param enableTools  工具白名单；null / 空 = 不过滤
 * @param disableTools 工具黑名单；仅在白名单为空时生效
 */
public record McpServerBinding(
        String name,
        McpClientWrapper client,
        List<String> enableTools,
        List<String> disableTools) {

    /** 空名单归一为 null：框架把 null 与空列表都当作「不过滤」，显式归一便于日志区分。 */
    public McpServerBinding {
        enableTools = (enableTools == null || enableTools.isEmpty()) ? null : List.copyOf(enableTools);
        disableTools = (disableTools == null || disableTools.isEmpty()) ? null : List.copyOf(disableTools);
    }

    /** 是否配置了过滤（决定要不要打那行过滤日志）。 */
    public boolean filtered() {
        return enableTools != null || disableTools != null;
    }
}
