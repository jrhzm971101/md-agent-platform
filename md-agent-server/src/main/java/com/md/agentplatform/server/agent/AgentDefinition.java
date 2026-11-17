package com.md.agentplatform.server.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 定义 —— 配置驱动装配的唯一输入。字段为 REACT 与 HARNESS 两个 builder 常用配置项的
 * typed 超集（分共有 / REACT / HARNESS 三组）；控制面表（REST body，camel）与本地 MD 兜底（KEBAB）自动绑定。
 *
 * <p>配置里出现未声明的键（历史遗留 extra、拼错、框架新参数）统一忽略并告警，不入任何字段。
 * 后续新增框架参数：AgentDefinition 加 typed 字段 + {@link ConfigurableAgentBuilder} 装配一行即可，
 * 两条源会自动识别，无需再改 loader / 解析器。
 */
@Data
public class AgentDefinition {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinition.class);

    public enum Type { REACT, HARNESS }

    /** Agent 唯一名（运行时注册键）。 */
    private String name;

    /** REACT = 普通 ReActAgent；Harness = HarnessAgent 编排器。 */
    private Type type = Type.REACT;

    /** 模型 bean 名（默认 openAIChatModel）。 */
    private String modelRef;

    /** 描述（可留空用默认）。 */
    private String description;

    /** 系统提示词（可留空用默认）。 */
    private String sysPrompt;

    /** 提示词 key（sysPrompt 来源，可选；有值则按它解析 {@code md_prompt.body}）。 */
    private String promptKey;

    /** 工具 Spring bean 名列表。 */
    private List<String> tools = new ArrayList<>();

    /**
     * 历史 skills 目录（相对 workspace-root），仅为兼容旧 MD 定义保留。
     *
     * <p><b>装配期不再读取</b>：技能内容的唯一来源是 {@link #skillNames} → MySQL。
     * 工作区里的目录与文件只作为历史数据保留，不会被删除，也不会被加载。
     */
    private String skills;

    /** 该 agent 引用的技能名列表（显式声明）。技能内容只按名从 MySQL 技能库解析。 */
    private List<String> skillNames = new ArrayList<>();

    /**
     * 该 agent 引用的 MCP 服务端名列表（显式声明，对应 {@code md_mcp_server}）。
     * 非空时装配期按名解析端点、建好 MCP client 注册进工具集，与 skills 同为「按名从控制面拉取」。
     */
    private List<String> mcpNames = new ArrayList<>();

    /**
     * 只注册这些 MCP 工具（白名单，写 MCP 服务端声明的原始工具名）。
     * 非空时该 agent 的 MCP 客户端只注册名单内的工具，其余工具既不进 Toolkit 也不进模型 schema。
     * 留空表示不过滤（注册该 MCP 声明的全部工具）。仅作用于 MCP 工具；
     * 本地 Spring 工具 bean 由 {@link #tools} 精确声明，不受本字段影响。
     */
    private List<String> enableTools = new ArrayList<>();

    /** 排除这些 MCP 工具（黑名单，按原始工具名）。{@link #enableTools} 非空时以它为准。 */
    private List<String> disableTools = new ArrayList<>();

    /**
     * 状态存储开关：非空 = 启用 MySQL 状态存储。
     *
     * <p>它<b>只表示"开不开"</b>，值本身（历史上曾是文件目录路径）已无意义 ——
     * 落点由 {@code agent-platform.state-store-database} 决定，表名由 {@link #stateTable} 决定。
     */
    private String stateStore;

    /**
     * 状态表名（冻结值）。为空则回落 agent 名。
     *
     * <p>见 {@code AgentRuntimeEntity#stateTable} 的说明：把表名与 agent 名解耦，
     * 是为了让"改 agent 名"不再等于"丢掉全部上下文"。
     */
    private String stateTable;

    /**
     * 中间件 bean 名列表（来源：middleware 只读目录）。
     *
     * <p>用 List 而不是单个 String：{@code md_agent_ref.middleware} 是 JSON 数组，
     * 页面是多选。装配期按顺序逐个 {@code builder.middleware(bean)}，
     * 顺序即链上的先后（框架按注册顺序包洋葱）。
     */
    private List<String> middleware = new ArrayList<>();

    /** 状态冲突策略：OVERWRITE | FAIL | APPEND_MERGE（仅 REACT builder 支持）。 */
    private String conflictPolicy;


    private Integer maxIters;
    private Integer maxRetries;
    private Boolean enableMetaTool;
    private Boolean enablePendingToolRecovery;
    private String defaultSessionId;

    /** HARNESS 类型专用：可路由的子 Agent 名列表。 */
    private List<String> subAgents = new ArrayList<>();

    /** HARNESS 工作区（相对 workspace-root）。 */
    private String workspace;

    /**
     * HARNESS 工作区文件后端：{@code LOCAL}（默认）/ {@code REMOTE}。
     *
     * <p>null / 空 / 无法识别的值一律按 LOCAL 处理 —— 装配层的判定只认显式的 {@code REMOTE}，
     * 避免拼写错误把 agent 悄悄切到一个"没有 shell"的运行形态上。
     */
    private String fsBackend;

    // ==================== 两个 builder 共有的扩展选项 ====================

    /** 运行前是否检查 Agent 状态。 */
    private Boolean checkRunning;

    /** 是否开启任务列表。 */
    private Boolean enableTaskList;

    /** 模型拒绝调用时是否停止。 */
    private Boolean stopOnReject;

    /** fallback 模型 bean 名（可选；非空才装配，不套默认模型）。 */
    private String fallbackModelRef;

    // ==================== REACT 专用扩展选项 ====================

    /** 是否允许动态加载技能。 */
    private Boolean dynamicSkillsEnabled;

    /** 是否允许技能代码执行。 */
    private Boolean skillCodeExecutionEnabled;

    /** 技能执行工作目录（相对 workspace-root）。 */
    private String skillWorkDir;

    // ==================== HARNESS 专用扩展选项 ====================

    /** HARNESS Agent 唯一 ID。 */
    private String agentId;

    /** HARNESS 运行环境标识。 */
    private String environment;

    /** HARNESS 环境记忆目录。 */
    private String environmentMemory;

    /**
     * HARNESS 项目全局技能目录（相对 workspace-root）。
     *
     * <p>历史字段，装配期不再接线；技能内容只按 {@link #skillNames} 从 MySQL 解析。
     */
    private String projectGlobalSkillsDir;

    /** HARNESS 最大上下文 token 数。 */
    private Integer maxContextTokens;

    /** HARNESS 是否开启 Agent 链路日志。 */
    private Boolean enableAgentTracingLog;

    /** HARNESS 是否启用技能（总开关）。 */
    private Boolean skillsEnabled;

    /** HARNESS 仅启用这些技能（按名）。 */
    private List<String> enableSkills = new ArrayList<>();

    /** HARNESS 禁用这些技能（按名）。 */
    private List<String> disableSkills = new ArrayList<>();

    /** HARNESS 是否开启计划模式。 */
    private Boolean enablePlanMode;

    /** HARNESS 计划文件目录。 */
    private String planFileDirectory;

    /** HARNESS 计划模式下是否允许 shell。 */
    private Boolean planModeAllowShell;

    /** HARNESS 是否使用遗留 XML 工作区上下文。 */
    private Boolean useLegacyXmlWorkspaceContext;

    /** HARNESS 附加上下文文件（相对 workspace-root，可多个）。 */
    private List<String> additionalContextFiles = new ArrayList<>();

    /** HARNESS 技能管理工具开关；null = 用框架默认（不开）。 */
    private Boolean skillManageToolEnabled;

    /** HARNESS 技能管理工具配置（draftsDir / mainDir / autoPromote / securityScan）。 */
    private Map<String, Object> skillManageConfig;

    /**
     * 一批"HARNESS 能力开关"是否显式关闭。三态：null = 未设置（用框架默认）、
     * true = 调 {@code disableXxx()}、false = 显式保持开启。
     *
     * <p>字段名与 {@code md_agent_workspace.disable_*} 列一一对应，由
     * {@code AgentDefinitionResolver} 直接搬运，不做第二套命名。
     */
    private Boolean disableFilesystemTools;
    private Boolean disableShellTool;
    private Boolean disableMemoryTools;
    private Boolean disableMemoryHooks;
    private Boolean disableTranscript;
    private Boolean disableSessionPersistence;
    private Boolean disableWorkspaceContext;
    private Boolean disableAtPathExpansion;
    private Boolean disableSubagents;
    private Boolean disableDynamicSubagents;
    private Boolean disableDynamicSkills;
    private Boolean disableDefaultWorkspaceSkills;
    private Boolean disableToolsConfig;

    // ==================== HARNESS 子 agent 执行形态 ====================
    // 与上面那批 disable_* 同属"harness 运行形态"，字段名与 md_agent_workspace.subagent_* 列一一对应，
    // 由 AgentDefinitionResolver 直接搬运，不做第二套命名。

    /**
     * 是否把子 agent 强制成同步执行。
     *
     * <p><b>它解决的是一件"由谁说了算"的事</b>：框架默认「同步还是异步」是 <b>模型自己</b>决定的
     * —— {@code agent_spawn} 的 {@code timeout_seconds} 是模型填的参数（0=后台、>0=同步），
     * 而同步等待超时后框架还会自动把它 promote 成后台任务。模型心情不一样，同一段编排每次跑的形态
     * 就可能不同；对"必须拿到结果才能继续"的编排，这种不确定性是致命的。
     *
     * <p>置 true 之后，装配出的运行入口（{@code AgentManager.stream}）会在 RuntimeContext 上写
     * {@code AgentSpawnTool.CTX_FORCE_SYNC}，框架侧优先级高于模型参数：
     * {@code timeout_seconds=0} 被强制改写成同步等待，超时也不再 promote，而是中断子 agent
     * 并返回 {@code status: timeout}。
     *
     * <p>三态：null = 不下发（用框架默认，即由模型决定）；true = 强制同步；false = 显式保持
     * "由模型决定"。为什么允许 false：与上面那批 disable_* 同一个理由 —— 页面必须能区分
     * "未设置"和"显式关闭"，否则会把当次的框架默认行为写死进库。
     */
    private Boolean subagentForceSync;

    /**
     * 强制同步时的等待秒数；null = 用框架默认（30 秒），超过 600 由框架自己夹到 600。
     *
     * <p>只在 {@link #subagentForceSync} 为 true 时有意义：框架侧它在 force-sync 打开时
     * <b>完全取代</b>模型填的 {@code timeout_seconds}；force-sync 关着时被忽略。
     */
    private Integer subagentSyncTimeoutSeconds;

    // ==================== HARNESS 上下文压缩 CompactionConfig ====================

    /** 显式关闭上下文压缩；true 时下面一批压缩字段全部忽略（框架侧 disableCompaction 优先）。 */
    private Boolean compactionDisabled;

    private Integer triggerMessages;
    private Integer triggerTokens;
    private Integer reserved;
    private Integer keepMessages;
    private Integer keepTokens;
    private Integer keepTokensMin;
    private Integer keepTokensMax;

    /** 保留比例，与 keep-tokens-min / max 配合；用 BigDecimal 避免 YAML 里 0.3 变成 double 精度噪音。 */
    private BigDecimal keepTokensRatio;

    private String summaryPrompt;
    private Boolean flushBeforeCompact;
    private Boolean offloadBeforeCompact;

    /** 压缩用模型 bean 名；空则用主模型。 */
    private String compactionModelRef;

    /** TruncateArgsConfig：triggerMessages / triggerTokens / keepMessages / keepTokens / maxArgLength / truncationText。 */
    private Map<String, Object> truncateArgs;

    /** PruneConfig：protectTokens / minimumTokens / maxOutputChars / excludedTools。 */
    private Map<String, Object> prune;

    // ==================== HARNESS 记忆 MemoryConfig ====================

    /** 记忆整理用模型 bean 名；空则用主模型。 */
    private String memoryModelRef;
    private String flushPrompt;
    private String consolidationPrompt;
    private Integer consolidationMaxTokens;

    /** 两次归并最小间隔（毫秒，对应框架的 Duration）。 */
    private Long consolidationMinGapMs;

    private Integer dailyFileRetentionDays;
    private Integer sessionRetentionDays;

    /** FlushTrigger：mode = ALWAYS | NEVER | THROTTLED，THROTTLED 时另需 minGapMs。 */
    private Map<String, Object> flushTrigger;

    // ==================== HARNESS 工具结果驱逐 ToolResultEvictionConfig ====================

    /** 显式关闭工具结果驱逐（true 时下面 evict* 字段全部忽略）。 */
    private Boolean evictDisabled;

    private Integer evictMaxResultChars;
    private Integer evictPreviewChars;
    private String evictPath;
    private List<String> evictExcludedTools = new ArrayList<>();

    /** 未声明配置键：忽略并告警，不落地任何字段。 */
    @JsonAnySetter
    public void ignoreUnknown(String key, Object value) {
        log.warn("[AgentDefinition] 忽略未声明的配置键: {} = {}", key, value);
    }
}
