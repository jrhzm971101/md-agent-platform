package com.md.agentplatform.server.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * workwx 三个 agent 的本地定义自检 —— 保证「只用本地 MD 定义也能把这一组跑起来」。
 *
 * <p>覆盖三件都必须成立的事：定义能被 MD loader 解析、harness 引用的子 agent 名字对得上、
 * 声明了历史 skills 目录的定义仍然带有 MySQL {@code skill-names}。技能初始化已改为
 * MySQL 单源，工作区里的历史技能目录只保留、不再参与加载。任何一条断了，本地测试就会以
 * 「agent 不存在」「工具为空」「技能静默丢失」这类难查的方式失败。
 */
class WorkwxAgentLocalDefinitionTest {

    private static final Path AGENTS_DIR = repoRoot().resolve("workspace/agents");
    private static final Path WORKSPACE_ROOT = repoRoot().resolve("workspace");

    private final AgentDefinitionMdLoader loader = new AgentDefinitionMdLoader();

    @Test
    void harnessReferencesExistingSubAgents() {
        AgentDefinition harness = parse("workwx_batch_agent");

        assertEquals(AgentDefinition.Type.HARNESS, harness.getType());
        assertEquals(Set.of("workwx_doc_agent", "workwx_notify_agent"),
                Set.copyOf(harness.getSubAgents()));
        assertEquals("harness/workwx_batch_agent", harness.getWorkspace());

        for (String sub : harness.getSubAgents()) {
            assertTrue(Files.isRegularFile(AGENTS_DIR.resolve(sub + ".md")),
                    "harness 引用的子 agent 没有本地定义: " + sub);
        }
    }

    @Test
    void docAgentIsReadOnlyWhitelist() {
        AgentDefinition doc = parse("workwx_doc_agent");

        assertEquals(AgentDefinition.Type.REACT, doc.getType());
        assertTrue(doc.getEnableTools() != null && !doc.getEnableTools().isEmpty(),
                "doc agent 必须显式 declare enable-tools，否则会把 MCP 的写入类工具一起挂上");
        assertTrue(doc.getEnableTools().stream().noneMatch(t -> t.startsWith("doc_create")
                        || t.startsWith("doc_contents_append") || t.startsWith("doc_contents_overwrite")
                        || t.contains("_update") || t.contains("_delete") || t.contains("_add")),
                "只读白名单里混进了写入类工具: " + doc.getEnableTools());
    }

    @Test
    void workdayToolIsWiredOnlyWhereItBelongs() throws Exception {
        assertEquals(List.of("workdayQueryTool"), parse("workwx_doc_agent").getTools(),
                "doc agent 要自己取工作日，不能依赖上游转述日期列表");
        assertTrue(parse("workwx_batch_agent").getTools().contains("workdayQueryTool"),
                "harness 需要自行判定「今天是否为工作日」，应挂 workdayQueryTool");
        assertFalse(parse("workwx_notify_agent").getTools().contains("workdayQueryTool"),
                "通知 agent 不该有能力重算工作日判定");

        // frontmatter 里写的是 Spring bean 名，必须与 @Component 类的默认 bean 名一致，
        // 否则 ConfigurableAgentBuilder.validateTools 会在启动时报「工具 bean 不存在」
        Class<?> toolClass = Class.forName("com.md.agentplatform.server.tool.workday.WorkdayQueryTool");
        String simpleName = toolClass.getSimpleName();
        assertEquals("workdayQueryTool",
                Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1));
    }

    @Test
    void notifyAgentUsesMysqlSkillNamesAndKeepsArchiveFiles() {
        AgentDefinition notify = parse("workwx_notify_agent");

        assertEquals(Set.of("workwx-send-msg", "workwx-webhook-send"),
                Set.copyOf(notify.getSkillNames()), "技能应从 MySQL 按 skill-names 解析");

        Path legacyDir = WORKSPACE_ROOT.resolve(notify.getSkills());
        assertTrue(Files.isDirectory(legacyDir),
                "历史技能目录必须保留在工作区（不参与加载，但也不允许删除）: " + legacyDir);
    }

    @Test
    void everyLocalDefinitionWithLegacySkillsDirDeclaresMysqlSkillNames() throws Exception {
        try (var files = Files.list(AGENTS_DIR)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted().toList()) {
                String name = file.getFileName().toString().replaceFirst("\\.md$", "");
                AgentDefinition def = parse(name);
                if (def.getSkills() != null && !def.getSkills().isBlank()) {
                    assertFalse(def.getSkillNames().isEmpty(),
                            name + " 还有历史 skills 目录，但没有 skill-names；"
                                    + "文件路径已不参与装配，会导致技能静默丢失");
                }
            }
        }
    }

    private AgentDefinition parse(String name) {
        Path file = AGENTS_DIR.resolve(name + ".md");
        assertTrue(Files.isRegularFile(file), "缺少本地定义: " + file);
        try {
            return loader.parse(Files.readString(file), name + ".md");
        } catch (Exception e) {
            throw new IllegalStateException("解析本地定义失败: " + file, e);
        }
    }

    private static Path repoRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("workspace/agents"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("未找到仓库根（workspace/agents），user.dir="
                + System.getProperty("user.dir"));
    }
}
