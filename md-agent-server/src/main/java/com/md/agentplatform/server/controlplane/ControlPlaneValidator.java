package com.md.agentplatform.server.controlplane;

import com.md.agentplatform.server.agent.AgentDefinition;
import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.agent.ToolMeta;
import com.md.agentplatform.server.controlplane.store.AgentDefinitionBundle;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRefEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRuntimeEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentSubAgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentWorkspaceEntity;
import com.md.agentplatform.server.controlplane.store.entity.McpServerEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 保存前的引用完整性校验（设计文档 §6.4 的 7 条规则，后端为准）。
 *
 * <h2>一、为什么校验放在控制层而不是 Store</h2>
 * Store 的职责是"把这一束行整体写进库"，它不知道也不该知道"什么算合法定义"。
 * 而且这几条规则依赖的东西 Store 没有：工具目录要扫 Spring 容器、中间件目录要扫 bean。
 * 让 Store 去依赖容器，事务边界和测试隔离立刻一起崩掉。
 *
 * <h2>二、为什么是"收集式"而不是遇到第一条就返回</h2>
 * 页面上一次提交可能同时踩中好几条（prompt-key 拼错 + 某个 skill 早被删 + 子 agent 自引用）。
 * 一次报全，用户改一遍就能提交成功；只报第一条就要来回提交三次。
 * 复用 {@link ControlPlaneValidationException} 的收集语义，理由与它一致。
 *
 * <h2>三、第 7 条规则（类型与字段不匹配）为什么要专门做</h2>
 * 两条链读的行不是同一批：{@code HARNESS} 不读 {@code md_agent_memory}，也不读
 * {@code md_agent_runtime} 里那几项 REACT 专有开关；{@code REACT} 则完全不读
 * {@code md_agent_workspace}（该表自 2026-09-22 起是纯 HARNESS 表）。
 * 于是"配了但不生效"就会以最难查的形式出现：页面上明明勾了、库里也存了、agent 起来就是没这个行为。
 * 与其让运维靠经验记住哪些字段属于哪条链，不如在保存时直接拒绝 ——
 * 这样"某张表有没有行"本身就表达了类型，不必再维护一份会随加列而过期的字段归属清单。
 */
@Component
public class ControlPlaneValidator {

    @Autowired
    private ControlPlaneStore store;

    @Autowired
    private AgentManager agentManager;

    @Autowired
    private MiddlewareCatalog middlewareCatalog;

    /**
     * 校验一个 agent 行束。全部问题收集完一次性抛出。
     *
     * @throws ControlPlaneValidationException 有任何一条不通过
     */
    public void validate(AgentDefinitionBundle bundle) {
        List<String> problems = new ArrayList<>();
        if (bundle == null || bundle.getAgent() == null) {
            throw new ControlPlaneValidationException("缺少 agent 主表内容");
        }
        String name = bundle.getAgent().getAgentName();
        AgentDefinition.Type type = parseType(bundle.getAgent().getAgentType());

        validateRefs(name, bundle.getRef(), problems);
        validateSubAgents(name, bundle.getSubAgents(), problems);
        validateTypeFields(name, type, bundle, problems);

        if (!problems.isEmpty()) {
            throw new ControlPlaneValidationException(problems);
        }
    }

    // ==================== 规则 1~5：引用必须在库里/目录里 ====================

    private void validateRefs(String name, AgentRefEntity ref, List<String> problems) {
        if (ref == null) {
            return;
        }
        // 规则 1：prompt-key
        String promptKey = ref.getPromptKey();
        if (isNotBlank(promptKey) && store.findPrompt(promptKey) == null) {
            problems.add("prompt-key 不存在: " + promptKey);
        }
        // 规则 2：skill-names（只查治理表；内容表的一致性由列表页的差集校验负责，§10.7）
        for (String skillName : nonNull(ref.getSkillNames())) {
            if (store.findSkillMeta(skillName) == null) {
                problems.add("skill 不存在: " + skillName);
            }
        }
        // 规则 3：mcp-names 必须存在且 enabled —— 停用的 MCP 配上去等于这个 agent 少了工具
        for (String mcpName : nonNull(ref.getMcpNames())) {
            McpServerEntity mcp = store.findMcpServer(mcpName);
            if (mcp == null) {
                problems.add("MCP 不存在: " + mcpName);
            } else if (Boolean.FALSE.equals(mcp.getEnabled())) {
                problems.add("MCP 已停用: " + mcpName);
            }
        }
        // 规则 4：tools 必须命中工具目录（bean 名 + 该 bean 上有 @Tool 方法）
        Set<String> toolBeans = new HashSet<>();
        for (ToolMeta tool : agentManager.listAvailableTools()) {
            toolBeans.add(tool.beanName());
        }
        for (String tool : nonNull(ref.getTools())) {
            if (!toolBeans.contains(tool)) {
                problems.add("工具 bean 不存在或没有 @Tool 方法: " + tool);
            }
        }
        // 规则 5：middleware 必须命中中间件目录
        Set<String> middlewareBeans = new HashSet<>();
        for (MiddlewareCatalog.MiddlewareMeta meta : middlewareCatalog.list()) {
            middlewareBeans.add(meta.beanName());
        }
        for (String middleware : nonNull(ref.getMiddleware())) {
            if (!middlewareBeans.contains(middleware)) {
                problems.add("中间件 bean 不存在: " + middleware);
            }
        }
    }

    // ==================== 规则 6：子 agent ====================

    private void validateSubAgents(String name, List<AgentSubAgentEntity> rows, List<String> problems) {
        if (rows == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (AgentSubAgentEntity row : rows) {
            String subName = row.getSubName();
            if (!isNotBlank(subName)) {
                problems.add("子 agent 名字为空");
                continue;
            }
            // 自引用会让 harness 在路由时无限递归到自己的实例
            if (subName.equals(name)) {
                problems.add("子 agent 不允许自引用: " + subName);
                continue;
            }
            if (!seen.add(subName)) {
                problems.add("子 agent 重复声明: " + subName);
            }
            AgentEntity subAgent = store.findAgent(subName);
            if (subAgent == null) {
                problems.add("子 agent 不存在: " + subName);
            } else if (parseType(subAgent.getAgentType()) != AgentDefinition.Type.REACT) {
                // 子 agent 是"被派发的执行体"，只有能力单元（REACT）适合这个角色。
                // 放开会引来"编排体套编排体"，而这里只挡了自引用，挡不住 A→B→A 这类环。
                problems.add("子 agent 必须是 REACT 类型: " + subName
                        + "（当前是 " + subAgent.getAgentType() + "）");
            }
        }
    }

    // ==================== 规则 7：类型与字段必须匹配 ====================

    private void validateTypeFields(String name, AgentDefinition.Type type,
                                    AgentDefinitionBundle bundle, List<String> problems) {
        AgentWorkspaceEntity workspace = bundle.getWorkspace();
        if (type == AgentDefinition.Type.REACT) {
            // REACT 链不读工作区与记忆配置
            if (bundle.getMemory() != null) {
                problems.add("REACT agent 不应配置上下文压缩 / 记忆（md_agent_memory）");
            }
            if (workspace != null) {
                // 这里曾经逐字段报错（workspace / harness-agent-id / 计划模式 / disable-* / skills-enabled）。
                // 2026-09-22 把 REACT 专有的三项技能策略迁进 md_agent_runtime 之后，本表已无一列属于 REACT，
                // 于是判据从"哪几列不该填"收紧成"整行都不该有"：一条规则顶掉五条，
                // 而且将来给本表加 HARNESS 列时不需要同步回来补一条规则。
                problems.add("REACT agent 不应有 md_agent_workspace 行"
                        + "（该表为 HARNESS 专有；需要工作区请新建一个 HARNESS agent）");
            }
            if (!nonNullSubAgents(bundle).isEmpty()) {
                problems.add("REACT agent 不应声明子 agent（只有 harness 会路由子 agent）");
            }
        } else {
            // HARNESS 链不读 REACT 独有的四项。这四项现在同住 md_agent_runtime
            //（2026-09-22 迁移的结果），所以判据也从两个域收敛到一个域 —— 少一处会漂移的地方。
            AgentRuntimeEntity runtime = bundle.getRuntime();
            if (runtime != null) {
                if (runtime.getDynamicSkillsEnabled() != null) {
                    problems.add("HARNESS agent 不应配置 dynamic-skills-enabled（REACT 专有）");
                }
                if (runtime.getSkillCodeExecutionEnabled() != null) {
                    problems.add("HARNESS agent 不应配置 skill-code-execution-enabled（REACT 专有）");
                }
                if (isNotBlank(runtime.getSkillWorkDir())) {
                    problems.add("HARNESS agent 不应配置 skill-work-dir（REACT 专有）");
                }
                if (isNotBlank(runtime.getConflictPolicy())) {
                    problems.add("HARNESS agent 不应配置 conflict-policy（REACT 专有）");
                }
            }
        }
    }

    private List<AgentSubAgentEntity> nonNullSubAgents(AgentDefinitionBundle bundle) {
        return bundle.getSubAgents() == null ? List.of() : bundle.getSubAgents();
    }

    private static AgentDefinition.Type parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentDefinition.Type.REACT;
        }
        try {
            return AgentDefinition.Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AgentDefinition.Type.REACT;
        }
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
