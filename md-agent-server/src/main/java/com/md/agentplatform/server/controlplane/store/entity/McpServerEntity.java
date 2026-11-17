package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * {@code md_mcp_server} 的行模型 —— MCP 服务端（控制面里唯一一处 MCP 注册项）。
 *
 * <h2>字段为什么按官方 McpServerConfig 全量建（含当前用不上的 stdio 三件套）</h2>
 * {@code command / args / env} 是 stdio（本地进程）MCP 的配置项，而现有实现
 * （{@code DbMcpClientFactory}）明确拒绝 stdio、只支持远端。把它们保留为空列的理由是
 * <b>表结构的变更成本远高于几个空列</b>：将来若要放开本地 MCP，改的是装配逻辑而不是 DDL，
 * 也就不需要在生产库上做 ALTER。
 *
 * <h2>enable_tools / disable_tools 的语义（迁移期最需要注意的一处）</h2>
 * 它们原本是 <b>agent 级</b>旋钮（"某个 agent 挂这个 MCP 时只注册哪些工具"），
 * 本次下沉到 <b>MCP 条目级</b>，含义变成"这个 MCP 在本平台的工具范围"。两者在
 * 「一个 MCP 只被一个 agent 引用」时等价，多 agent 引用同一 MCP 且白名单不同时会冲突 ——
 * 导入器必须检出并打 ERROR 交人工裁决，不允许静默取其一（设计文档 D10）。
 */
@Getter
@Setter
@TableName(value = "md_mcp_server", autoResultMap = true)
public class McpServerEntity implements GovernedRow {

    @TableId(value = "server_name", type = IdType.INPUT)
    private String serverName;

    /**
     * 展示名 —— 页面上唯一该露给人看的名字。
     *
     * <p>与 agent 同因不同果：server-name 是装配顺序的排序依据、也是 md_agent_ref.mcp_names
     * 的引用值（顺序即装配顺序），所以它不能承担展示职责（详见 AgentEntity.displayName）。
     * 本列只做展示，不参与装配、不参与唯一键、不可被引用。
     */
    private String displayName;

    private String description;

    @TableField(value = "biz_tags", typeHandler = JacksonTypeHandler.class)
    private List<String> bizTags;

    /** streamable | sse | stateless-http（stdio 暂不支持）。 */
    private String transport;

    /** 远端端点 URL。 */
    private String url;

    @TableField(value = "headers", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> headers;

    @TableField(value = "query_params", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> queryParams;

    // ==================== stdio 专用（当前保留不用） ====================

    private String command;

    @TableField(value = "args", typeHandler = JacksonTypeHandler.class)
    private List<String> args;

    @TableField(value = "env", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> env;

    // ==================== 工具范围 ====================

    /** 工具白名单；非空则以它为准。 */
    @TableField(value = "enable_tools", typeHandler = JacksonTypeHandler.class)
    private List<String> enableTools;

    /** 工具黑名单；enable_tools 非空时忽略本列。 */
    @TableField(value = "disable_tools", typeHandler = JacksonTypeHandler.class)
    private List<String> disableTools;

    /** 单次调用超时（毫秒）；装配时转 Duration。 */
    private Long timeoutMs;

    /** 握手 / 初始化超时（毫秒）；装配时转 Duration。 */
    private Long initTimeoutMs;

    private Boolean enabled;

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
