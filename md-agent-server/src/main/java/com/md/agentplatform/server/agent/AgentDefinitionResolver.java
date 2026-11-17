package com.md.agentplatform.server.agent;

import com.md.agentplatform.server.controlplane.store.AgentDefinitionBundle;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentMemoryEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRefEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRuntimeEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentSubAgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentWorkspaceEntity;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 行束 → {@link AgentDefinition} 的<b>唯一转换点</b>。
 *
 * <h2>一、它在链路里的位置</h2>
 * <pre>
 *   ControlPlaneStore.loadAgentBundle()   6 张表的行（Row）
 *              ↓  本类：唯一转换
 *   AgentDefinition                        装配输入（typed 字段）
 *              ↓  ConfigurableAgentBuilder
 *   ReActAgent / HarnessAgent              运行时实例
 * </pre>
 * 之所以把转换收在一处（而不是让实体类自己带 {@code toDefinition()}）：<b>实体是表的形状，定义是
 * builder 的形状</b>，两者演进节奏不同 —— 表加一列不代表 builder 有这个 setter，反过来也一样。
 * 中间隔一层，加列/加 setter 都只需要动这一处，且「哪些列目前没被装配消费」在这里一眼可见。
 *
 * <h2>二、它同时是「哪些字段还没接线」的清单</h2>
 * 下面每个字段块都标了它落到 builder 的哪个 setter。目前<b>有意不接线</b>的有两处，
 * 都在代码里显式告警而不是静默丢弃：
 * <ul>
 *   <li>子 agent 的扩展列（description / model / tools / remote …）：当前用
 *       {@code subagentFactory(name, agents::get)} 做「按名惰性路由」，一只 agent 只用名字表达；
 *       扩展列是给将来「内联声明子 agent」用的，现在配了不生效，所以设置时打 WARN。</li>
 *   <li>{@code md_agent_ref.knowledges}（RAG Knowledge bean 名）：框架的
 *       {@code knowledge(Knowledge)} 已被标记为废弃待删，此时接线等于依赖一个即将消失的 API。
 *       列先留着（与 Nacos 结构对齐，将来框架给出替代接口可直接启用），但不做假接线。</li>
 * </ul>
 *
 * <h2>三、停用的 agent 不返回，而不是返回一个 enabled=false 的定义</h2>
 * 「停用」的语义是<b>不参与装配</b>（设计文档里 enabled 列的注释）。如果返回定义再由
 * AgentManager 判断，那「怎么算停用」这件事就有两个地方在管。直接返回 null，
 * 由调用方跳过 —— 只有一处决定「谁能上线」。
 */
@Component
public class AgentDefinitionResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionResolver.class);

    @Autowired
    private ControlPlaneStore store;

    /**
     * 全部<b>可装配</b>的定义（已剔除停用项）。
     *
     * <p>批量读而不是逐个 {@link #resolve(String)}：实现里是「6 条全表查询 + 内存分组」，
     * 逐个读会变成 6×N 次查询。
     */
    public List<AgentDefinition> resolveAll() {
        List<AgentDefinition> defs = new ArrayList<>();
        for (AgentDefinitionBundle bundle : store.loadAllAgentBundles()) {
            AgentDefinition def = resolve(bundle);
            if (def != null) {
                defs.add(def);
            }
        }
        return defs;
    }

    /** 单个 agent 的定义；不存在或已停用时返回 null。 */
    public AgentDefinition resolve(String agentName) {
        return resolve(store.loadAgentBundle(agentName));
    }

    /** 行束 → 定义。行束为 null（agent 不存在）或主表行缺失时返回 null。 */
    public AgentDefinition resolve(AgentDefinitionBundle bundle) {
        if (bundle == null || bundle.getAgent() == null) {
            return null;
        }
        AgentEntity agent = bundle.getAgent();
        if (Boolean.FALSE.equals(agent.getEnabled())) {
            log.info("[Agent] {} 已停用（enabled=0），本次不参与装配", agent.getAgentName());
            return null;
        }

        AgentDefinition def = new AgentDefinition();
        def.setName(agent.getAgentName());
        def.setType(parseType(agent.getAgentType(), agent.getAgentName()));
        def.setDescription(agent.getDescription());

        applyRef(def, bundle.getRef());
        applyPrompt(def);
        applyRuntime(def, bundle.getRuntime());
        applyMemory(def, bundle.getMemory());
        applyWorkspace(def, bundle.getWorkspace());
        applySubAgents(def, bundle.getSubAgents());
        return def;
    }

    // ==================== 引用（谁被这个 agent 用了） ====================

    /** {@code md_agent_ref} → tools / middleware / skill-names / mcp-names / knowledges / prompt-key。 */
    private void applyRef(AgentDefinition def, AgentRefEntity ref) {
        if (ref == null) {
            return;
        }
        def.setPromptKey(ref.getPromptKey());
        def.setTools(copyOf(ref.getTools()));
        def.setMiddleware(copyOf(ref.getMiddleware()));
        def.setSkillNames(copyOf(ref.getSkillNames()));
        def.setMcpNames(copyOf(ref.getMcpNames()));
        // ref.knowledges（RAG Knowledge bean 名）<b>有意不映射</b>：
        // 框架侧的 knowledge(Knowledge) / knowledges(List) 已被标记为废弃待删
        // （编译期 deprecation 告警可证），现在接线等于依赖一个即将消失的 API。
        // 列先留着（表结构与 Nacos 对齐、便于将来框架给出替代接口时直接启用），但不做假接线。
    }

    /**
     * prompt-key → sysPrompt。
     *
     * <p>正文的权威位置是 {@code md_prompt.body}，agent 定义里只留 key（设计文档 §5.1 的
     * 「sysPrompt 不存」）。取不到时<b>不改</b> sysPrompt 也不抛异常：让 agent 以框架默认提示词起来，
     * 比整个平台起不来要好；真正该报错的时机是「保存这个 agent 时 prompt-key 不存在」，
     * 那条校验在控制层。
     */
    private void applyPrompt(AgentDefinition def) {
        String key = def.getPromptKey();
        if (key == null || key.isBlank()) {
            return;
        }
        PromptEntity prompt = store.findPrompt(key);
        if (prompt == null || prompt.getBody() == null || prompt.getBody().isBlank()) {
            log.warn("[Agent] {} 引用的 prompt-key={} 在 md_prompt 里不存在或正文为空，本次装配不带系统提示词",
                    def.getName(), key);
            return;
        }
        def.setSysPrompt(prompt.getBody());
    }

    // ==================== 运行时旋钮 ====================

    /** {@code md_agent_runtime} → 模型 / 迭代 / 行为开关 / 状态存储。 */
    private void applyRuntime(AgentDefinition def, AgentRuntimeEntity rt) {
        if (rt == null) {
            return;
        }
        def.setModelRef(rt.getModelRef());
        def.setFallbackModelRef(rt.getFallbackModelRef());
        def.setMaxIters(rt.getMaxIters());
        def.setMaxRetries(rt.getMaxRetries());
        def.setMaxContextTokens(rt.getMaxContextTokens());
        def.setCheckRunning(rt.getCheckRunning());
        def.setStopOnReject(rt.getStopOnReject());
        def.setEnableMetaTool(rt.getEnableMetaTool());
        def.setEnableTaskList(rt.getEnableTaskList());
        def.setEnablePendingToolRecovery(rt.getEnablePendingToolRecovery());
        def.setConflictPolicy(rt.getConflictPolicy());
        // REACT 专有的技能策略：2026-09-22 从 workspace 行迁到 runtime 行。
        // 迁移理由见 AgentRuntimeEntity 的类注释 —— 核心是"让 md_agent_workspace 保持纯 HARNESS"。
        def.setDynamicSkillsEnabled(rt.getDynamicSkillsEnabled());
        def.setSkillCodeExecutionEnabled(rt.getSkillCodeExecutionEnabled());
        def.setSkillWorkDir(rt.getSkillWorkDir());
        def.setStateStore(rt.getStateStore());
        def.setStateTable(rt.getStateTable());
        def.setDefaultSessionId(rt.getDefaultSessionId());
    }

    // ==================== 上下文压缩 / 记忆 / 工具结果驱逐 ====================

    /**
     * {@code md_agent_memory} → 压缩 / 记忆 / 驱逐三块配置。
     *
     * <p>{@code compaction_disabled} 与 {@code evict_disabled} 在库里是 {@code NOT NULL DEFAULT 0}，
     * 所以它们<b>不是三态</b>：0 = 不关闭（继续看后面的具体阈值），1 = 关闭。
     * 一批具体阈值才是可空的 —— null 表示「没配这一项」，由 {@code ConfigurableAgentBuilder}
     * 决定是「保留框架默认」还是「完全不传这个 config」。
     */
    private void applyMemory(AgentDefinition def, AgentMemoryEntity m) {
        if (m == null) {
            return;
        }
        def.setCompactionDisabled(m.getCompactionDisabled());
        def.setTriggerMessages(m.getTriggerMessages());
        def.setTriggerTokens(m.getTriggerTokens());
        def.setReserved(m.getReserved());
        def.setKeepMessages(m.getKeepMessages());
        def.setKeepTokens(m.getKeepTokens());
        def.setKeepTokensMin(m.getKeepTokensMin());
        def.setKeepTokensMax(m.getKeepTokensMax());
        def.setKeepTokensRatio(m.getKeepTokensRatio());
        def.setSummaryPrompt(m.getSummaryPrompt());
        def.setFlushBeforeCompact(m.getFlushBeforeCompact());
        def.setOffloadBeforeCompact(m.getOffloadBeforeCompact());
        def.setCompactionModelRef(m.getCompactionModelRef());
        def.setTruncateArgs(m.getTruncateArgs());
        def.setPrune(m.getPrune());

        def.setMemoryModelRef(m.getMemoryModelRef());
        def.setFlushPrompt(m.getFlushPrompt());
        def.setConsolidationPrompt(m.getConsolidationPrompt());
        def.setConsolidationMaxTokens(m.getConsolidationMaxTokens());
        def.setConsolidationMinGapMs(m.getConsolidationMinGapMs());
        def.setDailyFileRetentionDays(m.getDailyFileRetentionDays());
        def.setSessionRetentionDays(m.getSessionRetentionDays());
        def.setFlushTrigger(m.getFlushTrigger());

        def.setEvictDisabled(m.getEvictDisabled());
        def.setEvictMaxResultChars(m.getEvictMaxResultChars());
        def.setEvictPreviewChars(m.getEvictPreviewChars());
        def.setEvictPath(m.getEvictPath());
        def.setEvictExcludedTools(copyOf(m.getEvictExcludedTools()));
    }

    // ==================== 工作区与能力开关（HARNESS 专有） ====================

    /**
     * {@code md_agent_workspace} → 工作区 + 技能管理 + 一批三态开关。
     *
     * <p>本表自 2026-09-22 起是纯 HARNESS 表，REACT 专有的三项技能策略已移到
     * {@code md_agent_runtime}（见 {@link #applyRuntime}）。本方法负责的是：
     * <ul>
     *   <li>HARNESS 工作区（workspace / environment / plan-file-directory …）；</li>
     *   <li>技能管理（skills-enabled / enable-skills / disable-skills / skill-manage-tool）；</li>
     *   <li>13 个 {@code disableXxx()} 三态开关。</li>
     * </ul>
     * 因此 {@code w == null} 对 REACT agent 是正常状态，而不是"查漏了"。
     */
    private void applyWorkspace(AgentDefinition def, AgentWorkspaceEntity w) {
        if (w == null) {
            return;
        }
        def.setAgentId(w.getHarnessAgentId());
        def.setWorkspace(w.getWorkspacePath());
        def.setFsBackend(w.getFsBackend());
        def.setEnvironment(w.getEnvironment());
        def.setEnvironmentMemory(w.getEnvironmentMemory());
        def.setProjectGlobalSkillsDir(w.getProjectGlobalSkillsDir());
        def.setPlanFileDirectory(w.getPlanFileDirectory());
        def.setAdditionalContextFiles(copyOf(w.getAdditionalContextFiles()));
        def.setUseLegacyXmlWorkspaceContext(w.getUseLegacyXmlWorkspaceContext());
        def.setEnableAgentTracingLog(w.getAgentTracingLogEnabled());
        def.setEnablePlanMode(w.getEnablePlanMode());
        def.setPlanModeAllowShell(w.getPlanModeAllowShell());
        def.setSkillsEnabled(w.getSkillsEnabled());
        def.setEnableSkills(copyOf(w.getEnableSkills()));
        def.setDisableSkills(copyOf(w.getDisableSkills()));
        def.setSkillManageToolEnabled(w.getSkillManageToolEnabled());
        def.setSkillManageConfig(w.getSkillManageConfig());

        def.setDisableFilesystemTools(w.getDisableFilesystemTools());
        def.setDisableShellTool(w.getDisableShellTool());
        def.setDisableMemoryTools(w.getDisableMemoryTools());
        def.setDisableMemoryHooks(w.getDisableMemoryHooks());
        def.setDisableTranscript(w.getDisableTranscript());
        def.setDisableSessionPersistence(w.getDisableSessionPersistence());
        def.setDisableWorkspaceContext(w.getDisableWorkspaceContext());
        def.setDisableAtPathExpansion(w.getDisableAtPathExpansion());
        def.setDisableSubagents(w.getDisableSubagents());
        def.setDisableDynamicSubagents(w.getDisableDynamicSubagents());
        def.setDisableDynamicSkills(w.getDisableDynamicSkills());
        def.setDisableDefaultWorkspaceSkills(w.getDisableDefaultWorkspaceSkills());
        def.setDisableToolsConfig(w.getDisableToolsConfig());
        // 子 agent 执行形态（2026-09-28）：与上面那批开关同一套路，原样搬运，不做第二套命名。
        def.setSubagentForceSync(w.getSubagentForceSync());
        def.setSubagentSyncTimeoutSeconds(w.getSubagentSyncTimeoutSeconds());
    }

    // ==================== 子 agent ====================

    /**
     * {@code md_agent_sub_agent} → 子 agent 名列表（按 sort_order，Store 已排序）。
     *
     * <p><b>只搬名字</b>：装配期用 {@code builder.subagentFactory(name, agents::get)}，
     * 子 agent 自己是独立注册的一个 agent 实例，不需要父侧再描述一遍它的 model / tools。
     * 表里那些扩展列（description / workspaceMode / remote …）是给将来「内联声明」用的，
     * 现在配了不生效 —— 所以检测到非空就打 WARN，不静默丢弃。
     */
    private void applySubAgents(AgentDefinition def, List<AgentSubAgentEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>(rows.size());
        for (AgentSubAgentEntity row : rows) {
            if (row.getSubName() != null && !row.getSubName().isBlank()) {
                names.add(row.getSubName());
            }
            warnOnUnusedSubAgentColumns(def.getName(), row);
        }
        def.setSubAgents(names);
    }

    /** 子 agent 扩展列目前不参与装配：配了就告警，避免「页面填了却没效果」这种最难查的问题。 */
    private void warnOnUnusedSubAgentColumns(String agentName, AgentSubAgentEntity row) {
        boolean hasExtras = row.getSubDescription() != null
                || row.getWorkspaceMode() != null
                || row.getWorkspacePath() != null
                || row.getInlineAgentsBody() != null
                || row.getModelRef() != null
                || row.getMaxIters() != null
                || row.getSteps() != null
                || row.getTemperature() != null
                || row.getTopP() != null
                || row.getVariant() != null
                || row.getMode() != null
                || row.getHidden() != null
                || row.getPersistSession() != null
                || row.getInheritParentPermissions() != null
                || row.getExposeToUser() != null
                || (row.getTools() != null && !row.getTools().isEmpty())
                || (row.getSkills() != null && !row.getSkills().isEmpty())
                || row.getRemote() != null
                || row.getUrl() != null;
        if (hasExtras) {
            log.warn("[Agent] {} 的子 agent {} 配了扩展列，但当前装配只按名字路由，这些列本次不生效",
                    agentName, row.getSubName());
        }
    }

    // ==================== 工具方法 ====================

    /** agent_type 列 → 枚举；未知值一律按 REACT 处理并告警（宁可起得来，也不要因为一个脏值整个平台起不来）。 */
    private static AgentDefinition.Type parseType(String raw, String agentName) {
        if (raw == null || raw.isBlank()) {
            return AgentDefinition.Type.REACT;
        }
        try {
            return AgentDefinition.Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[Agent] {} 的 agent_type={} 不是合法类型，按 REACT 处理", agentName, raw);
            return AgentDefinition.Type.REACT;
        }
    }

    /** JSON 列读出来可能是 null，统一成空列表：装配侧的 {@code isEmpty()} 判断比 null 判断少一半分支。 */
    private static List<String> copyOf(List<String> src) {
        return src == null ? new ArrayList<>() : new ArrayList<>(src);
    }
}
