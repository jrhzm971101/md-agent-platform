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
 * {@code md_agent_workspace} 的行模型 —— 工作区路径 + 能力开关（HARNESS 专有）。
 *
 * <h2>本表混合了两类东西，是有意为之</h2>
 * <ol>
 *   <li><b>路径类</b>（workspace_path / environment_memory / plan_file_directory……）：
 *       注意它们<b>只存路径字符串，文件本体仍在磁盘上</b>。
 *       {@code HarnessAgent.Builder.workspace(Path)} 拿的是本地真实路径，框架要在该目录下
 *       读写 AGENTS.md、计划文件、会话产物 —— 这部分 DB 替代不了，是"能走 DB 都走 DB"的<b>例外</b>。
 *       技能（skills）反而是例外之外的：它走 skillRepository（DB），不扫磁盘目录。</li>
 *   <li><b>能力开关类</b>（一批 disable_*）：纯配置，与路径无关，但同属"harness 运行形态"
 *       这一个语义域，放一起才能在页面上一个分区里看完。</li>
 * </ol>
 *
 * <h2>本表是纯 HARNESS 表（2026-09-22 起）</h2>
 * REACT 专有的三项技能策略（dynamicSkillsEnabled / skillCodeExecutionEnabled / skillWorkDir）
 * 曾经写在本表，现已迁到 {@link AgentRuntimeEntity}。迁走的理由不是"放错了位置"，而是
 * <b>表要能整表归属一个页面</b>：只要本表还混着 REACT 的列，页面上就只能按列拆所有权，
 * 而列级所有权是没人能长期记住的规则 —— 每改一次代码都要重新对一遍"谁写哪几列"。
 * 迁完之后本表全部列属于 HARNESS，可以整体归到「Harness 工作台」页。
 *
 * <p>随之而来的一条硬约束：<b>REACT agent 不应该有本表的行</b>。
 * 行内所有列都是 HARNESS 的 setter 目标，REACT 拿到一行也只会全 null，
 * 那种行没有任何含义 —— 校验层直接拒掉，避免留下"看起来配了、实际不生效"的行。
 *
 * <p>所有 disable_* 都是三态（NULL=未设置 / 0=显式关闭 / 1=显式开启），原因见
 * {@link AgentRuntimeEntity} 的类注释。
 */
@Getter
@Setter
@TableName(value = "md_agent_workspace", autoResultMap = true)
public class AgentWorkspaceEntity {

    @TableId(value = "agent_name", type = IdType.INPUT)
    private String agentName;

    // ==================== 工作区与路径（字段本体在磁盘，这里只存路径） ====================

    /** HARNESS Agent 唯一 ID。 */
    private String harnessAgentId;

    /** 相对 workspace-root；空则装配层默认 harness/{agentName}。 */
    private String workspacePath;

    private String environment;

    private String environmentMemory;

    /** 项目全局技能目录。技能正常走 DB 仓库，此项仅作目录兜底。 */
    private String projectGlobalSkillsDir;

    private String planFileDirectory;

    @TableField(value = "additional_context_files", typeHandler = JacksonTypeHandler.class)
    private List<String> additionalContextFiles;

    /** filesystemSpec（local/sandbox/remote）+ filesystemRoute。 */
    @TableField(value = "filesystem", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> filesystem;

    /**
     * 工作区文件后端：{@code LOCAL}（默认，本地磁盘 + shell）/ {@code REMOTE}（进
     * {@code agent-platform.workspace-kv-database} 的 KV，无 shell）。
     *
     * <p>它与上面的 {@code filesystem} 不是一回事，别互相替代：本字段是<b>枚举类型选择</b>
     * （页面下拉、可做枚举校验），{@code filesystem} 是<b>该类型的参数覆盖</b>
     * （如 isolationScope、额外的 filesystemRoute）。装配层先看本字段决定用哪种 spec，
     * 再用 {@code filesystem} 覆盖细节。
     *
     * <p>为什么值得一个独立列而不塞进那个 JSON：这个值决定「这个 agent 还有没有 shell」，
     * 是排查「某个工具突然失效」的第一现场 —— 必须能直接 SELECT、能建索引、能在页面上一眼看到。
     * 藏进 JSON 就只能靠人肉读配置，而这是最不该有歧义的一个开关。
     */
    private String fsBackend;

    private Boolean useLegacyXmlWorkspaceContext;

    private Boolean agentTracingLogEnabled;

    // ==================== 计划模式 ====================

    private Boolean enablePlanMode;

    private Boolean planModeAllowShell;

    // ==================== 技能策略 ====================

    private Boolean skillsEnabled;

    @TableField(value = "enable_skills", typeHandler = JacksonTypeHandler.class)
    private List<String> enableSkills;

    @TableField(value = "disable_skills", typeHandler = JacksonTypeHandler.class)
    private List<String> disableSkills;

    /** 是否启用技能管理工具。 */
    private Boolean skillManageToolEnabled;

    /** SkillManageConfig。 */
    @TableField(value = "skill_manage_config", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> skillManageConfig;

    // ==================== 能力开关（三态，对应 builder 的一批 disableXxx()） ====================

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

    // ==================== 子 agent 执行形态（2026-09-28） ====================

    /**
     * 是否把子 agent 强制成同步执行。三态（NULL=用框架默认即由模型决定 / 0=显式保持模型决定 / 1=强制同步）。
     *
     * <p>框架默认由模型的 {@code agent_spawn.timeout_seconds} 决定同步还是后台，而且同步超时后
     * 还会自动 promote 成后台任务 —— 对"必须拿到结果才能继续"的编排，这种不确定性是致命的。
     * 置 1 之后装配出的运行入口会在 RuntimeContext 上写 {@code AgentSpawnTool.CTX_FORCE_SYNC}，
     * 其优先级高于模型参数。完整说明见 {@code AgentDefinition#subagentForceSync}。
     */
    private Boolean subagentForceSync;

    /** 强制同步时的等待秒数；NULL=框架默认 30。仅在 force-sync 打开时有意义。 */
    private Integer subagentSyncTimeoutSeconds;
}
