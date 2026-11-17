package com.md.agentplatform.server.agent;

import java.util.List;

/**
 * 工具元数据（供 {@code /api/agents/tools/available} 与可视化编排页面渲染）。
 */
public record ToolMeta(
        String name,        // @Tool name（运行时工具名）
        String beanName,    // Spring bean 名（AgentManager 按此从容器解析）
        String description,
        List<ParamMeta> parameters
) {
    public record ParamMeta(String name, String description) {}
}
