package com.md.agentplatform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.Map;

/**
 * Markdown Agent 定义的解析器。
 *
 * <p><b>运行期不再从文件加载 Agent</b>：定义只从控制面 MySQL 读取。本类仅保留纯文本解析能力，
 * 供测试与历史数据迁移复用；workspace 里的 {@code .md} 文件不会被删除，也不会参与装配。
 *
 * <p>格式为「YAML frontmatter + Markdown 正文」，frontmatter 的键与 {@link AgentDefinition}
 * 字段一一对应（kebab-case，与 application.yml 完全一致），正文自动成为 {@code sysPrompt}：
 *
 * <pre>
 * ---
 * name: rag_agent
 * type: REACT
 * tools: [ragIntelligentRetrievalTool]
 * skill-names: [rag-intelligent-retrieval]
 * state-store: single/statestore/rag_agent
 * max-iters: 5
 * max-retries: 3
 * enable-meta-tool: true
 * enable-pending-tool-recovery: true
 * default-session-id: default-session-id
 * ---
 *
 * 这里是系统提示词正文，直接作为 sysPrompt。
 * </pre>
 *
 * <p>{@code skills}（技能目录路径）是历史字段，仅为兼容旧文档保留，装配期<b>不再读取</b>；
 * 技能内容统一按 {@code skill-names} 从 MySQL 解析。工作区里的技能文件不会被删除。
 *
 * <p>规则：
 * <ul>
 *   <li>未写 {@code name} 时用文件名（去掉 .md）；</li>
 *   <li>未写 {@code sysPrompt} 时用正文；frontmatter 里显式写了 sysPrompt 则优先；</li>
 *   <li>{@code type} 缺省为 {@code REACT}，其余字段缺省与 {@link AgentDefinition} 一致。</li>
 * </ul>
 */
public class AgentDefinitionMdLoader {

    /** kebab-case 键 → camelCase 字段（与 application.yml 的松绑定规则一致）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    /** 解析单个 MD 内容：拆出 frontmatter + 正文，映射为 AgentDefinition。 */
    AgentDefinition parse(String content, String fileName) {
        String normalized = content.replace("\r\n", "\n");
        String frontmatter = null;
        String body = normalized;

        if (normalized.startsWith("---\n")) {
            int close = normalized.indexOf("\n---\n", 4);
            if (close >= 0) {
                frontmatter = normalized.substring(4, close);
                body = normalized.substring(close + 5);
            } else if (normalized.endsWith("\n---")) {
                frontmatter = normalized.substring(4, normalized.length() - 4);
                body = "";
            }
        }

        AgentDefinition def = new AgentDefinition();
        if (frontmatter != null && !frontmatter.isBlank()) {
            Object parsed = YAML.load(frontmatter);
            if (parsed instanceof Map<?, ?> map) {
                def = MAPPER.convertValue(map, AgentDefinition.class);
            }
        }

        if (def.getName() == null || def.getName().isBlank()) {
            def.setName(stripMdExtension(fileName));
        }
        if ((def.getSysPrompt() == null || def.getSysPrompt().isBlank()) && !body.isBlank()) {
            //todo 通过md文件中的prompt key 从nacos中读取
            def.setSysPrompt(body.strip());
        }
        return def;
    }

    private static String stripMdExtension(String fileName) {
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }
}
