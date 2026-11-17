package com.md.agentplatform.server.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MCP 工具过滤：配置绑定（MD kebab-key）与注册期过滤（enable / disable 名单）。
 *
 * <p>改造后名单的归属从 agent 下沉到 MCP 服务端（设计文档 D10），所以注册入口收的是
 * {@link McpServerBinding}（连接 + 名单）。历史源（nacos / local）里名单还挂在 agent 上，
 * 由 {@code ConfigurableAgentBuilder.resolveMcpBindings} 回填进绑定 —— 本测试的
 * {@link #binding} 就是那个回填的等价物，保证两条源的形状收敛到同一处。
 */
class AgentMcpToolFilterTest {

    @Test
    void mdFrontmatterBindsEnableAndDisableTools() {
        AgentDefinition def = new AgentDefinitionMdLoader().parse("""
                ---
                name: doc_agent
                type: REACT
                mcp-names: [workwx-doc-mcp]
                enable-tools: [doc_get, smartsheet_records_list]
                disable-tools: [doc_delete]
                ---

                正文即 sysPrompt
                """, "doc_agent.md");

        assertEquals(List.of("workwx-doc-mcp"), def.getMcpNames());
        assertEquals(List.of("doc_get", "smartsheet_records_list"), def.getEnableTools());
        assertEquals(List.of("doc_delete"), def.getDisableTools());
    }

    @Test
    void registersEveryMcpToolWhenNoFilterDeclared() {
        assertEquals(Set.of("doc_get", "sheet_rows_append"),
                register(new AgentDefinition(), "doc_get", "sheet_rows_append"));
    }

    @Test
    void mdFrontmatterBindsEnableToolsWrittenAsBlockList() {
        AgentDefinition def = new AgentDefinitionMdLoader().parse("""
                ---
                name: doc_agent
                type: REACT
                mcp-names: [workwx-doc-mcp]
                enable-tools:
                  - smartsheet_records_list
                  - smartsheet_fields_list
                ---

                正文即 sysPrompt
                """, "doc_agent.md");

        assertEquals(List.of("smartsheet_records_list", "smartsheet_fields_list"), def.getEnableTools());
    }

    @Test
    void enableToolsKeepsOnlyListedTools() {
        AgentDefinition def = new AgentDefinition();
        def.setEnableTools(List.of("doc_get"));

        assertEquals(Set.of("doc_get"), register(def, "doc_get", "sheet_rows_append"));
    }

    @Test
    void disableToolsDropsListedTools() {
        AgentDefinition def = new AgentDefinition();
        def.setDisableTools(List.of("sheet_rows_append"));

        assertEquals(Set.of("doc_get"), register(def, "doc_get", "sheet_rows_append"));
    }

    @Test
    void enableToolsWinsOverDisableTools() {
        AgentDefinition def = new AgentDefinition();
        def.setEnableTools(List.of("doc_get"));
        def.setDisableTools(List.of("doc_get", "sheet_rows_append"));

        assertEquals(Set.of("doc_get"), register(def, "doc_get", "sheet_rows_append"));
    }

    @Test
    void emptyAndNullFiltersAreEquivalent() {
        AgentDefinition def = new AgentDefinition();
        def.setEnableTools(List.of());
        def.setDisableTools(null);

        assertEquals(Set.of("doc_get"), register(def, "doc_get"));
    }

    @Test
    void filterIsPerClientSoOneAgentCannotWidenAnother() {
        AgentDefinition narrow = new AgentDefinition();
        narrow.setEnableTools(List.of("doc_get"));
        AgentDefinition wide = new AgentDefinition();

        Toolkit toolkit = new Toolkit();
        ConfigurableAgentBuilder.registerMcpClients(toolkit,
                List.of(binding("doc-mcp", narrow, "doc_get", "doc_delete")));
        ConfigurableAgentBuilder.registerMcpClients(toolkit,
                List.of(binding("mail-mcp", wide, "mail_send")));

        assertEquals(Set.of("doc_get", "mail_send"), toolkit.getToolNames());
    }

    @Test
    void unknownToolNamesInFilterAreHarmless() {
        AgentDefinition def = new AgentDefinition();
        def.setEnableTools(List.of("doc_get", "typo_tool_name"));

        assertEquals(Set.of("doc_get"), register(def, "doc_get", "sheet_rows_append"));
    }

    private static Set<String> register(AgentDefinition def, String... availableTools) {
        Toolkit toolkit = new Toolkit();
        ConfigurableAgentBuilder.registerMcpClients(toolkit,
                List.of(binding("doc-mcp", def, availableTools)));
        return toolkit.getToolNames();
    }

    /** 把「挂在 agent 上的名单」装进绑定，等价于历史源在装配期的回填动作。 */
    private static McpServerBinding binding(String mcpName, AgentDefinition def, String... toolNames) {
        return new McpServerBinding(mcpName, stub(mcpName, toolNames),
                def.getEnableTools(), def.getDisableTools());
    }

    private static McpClientWrapper stub(String mcpName, String... toolNames) {
        return new McpClientWrapper(mcpName) {
            @Override
            public Mono<Void> initialize() {
                initialized = true;
                return Mono.empty();
            }

            @Override
            public Mono<List<McpSchema.Tool>> listTools() {
                return Mono.just(Arrays.stream(toolNames).map(AgentMcpToolFilterTest::tool).toList());
            }

            @Override
            public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> args) {
                return Mono.empty();
            }

            @Override
            public Mono<McpSchema.CallToolResult> callTool(
                    String name, Map<String, Object> args, Map<String, Object> meta) {
                return Mono.empty();
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, null, "desc of " + name,
                new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null),
                null, null, null);
    }
}
