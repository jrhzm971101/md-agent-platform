package com.md.agentplatform.server.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区路径校验的契约测试。
 *
 * <p>这个类是全篇唯一的安全边界，所以测试按「一定要拒绝什么」来组织，而不是按方法。
 * 用例全是纯字符串断言，不起磁盘。
 */
class WorkspacePathGuardTest {

    private final WorkspacePathGuard guard = new WorkspacePathGuard();

    private static final String[] TRAVERSAL = {
            "../a.md",
            "skills/../../a.md",
            "skills/./a.md",
            "..",
            "agents/../skills/A.md",
    };

    private static final String[] ABSOLUTE_OR_HIDDEN = {
            "/etc/passwd",
            "C:\\Windows\\win.ini",
            ".agentscope/transcripts/x.jsonl",
            ".skills-cache/x",
            ".git/config",
    };

    /** Windows 特性：ADS、保留设备名、段尾点/空格。 */
    private static final String[] WINDOWS_TRAPS = {
            "a.txt::$DATA",
            "con",
            "con.md",
            "skills/nul.md",
            "AUX",
            "skills/lpt1.log",
            "secret.md ",
            "skills/secret.md ",
            "secret.md.",
    };

    private static final String[] ILLEGAL_CHARS = {
            "a*b.md",
            "a?b.md",
            "a|b.md",
            "a\"b.md",
            "a<b.md",
            "a\nb.md",
    };

    private static final String[] MALFORMED = {
            "",
            "   ",
            "a//b.md",
            "skills/",
            "  AGENTS.md",
            "AGENTS.md  ",
    };

    @Test
    void rejectsPathTraversal() {
        for (String p : TRAVERSAL) {
            assertThrows(WorkspacePathException.class, () -> guard.validate(p), "应拒绝: " + p);
        }
    }

    @Test
    void rejectsAbsoluteAndHidden() {
        for (String p : ABSOLUTE_OR_HIDDEN) {
            assertThrows(WorkspacePathException.class, () -> guard.validate(p), "应拒绝: " + p);
        }
    }

    @Test
    void rejectsWindowsTraps() {
        for (String p : WINDOWS_TRAPS) {
            assertThrows(WorkspacePathException.class, () -> guard.validate(p), "应拒绝: " + p);
        }
    }

    @Test
    void rejectsIllegalCharsAndMalformed() {
        for (String p : ILLEGAL_CHARS) {
            assertThrows(WorkspacePathException.class, () -> guard.validate(p), "应拒绝: " + p);
        }
        for (String p : MALFORMED) {
            assertThrows(WorkspacePathException.class, () -> guard.validate(p), "应拒绝: " + p);
        }
    }

    @Test
    void acceptsNormalPathsAndNormalizesSeparator() {
        assertEquals("AGENTS.md", guard.validate("AGENTS.md"));
        assertEquals("MEMORY.md", guard.validate("MEMORY.md"));
        assertEquals("skills/k8s_diagnose/SKILL.md",
                guard.validate("skills/k8s_diagnose/SKILL.md"));
        // 反斜杠归一成 /
        assertEquals("skills/k8s_diagnose/SKILL.md",
                guard.validate("skills\\k8s_diagnose\\SKILL.md"));
        // 中文段名允许
        assertEquals("knowledge/运维手册.md", guard.validate("knowledge/运维手册.md"));
        // 段中间的点和空格合法（只有结尾不允许）
        assertEquals("subagents/log.agent.md", guard.validate("subagents/log.agent.md"));
    }

    @Test
    void managedScopeIsTheWriteWhitelist() {
        assertDoesNotThrow(() -> guard.assertManaged("AGENTS.md"));
        assertDoesNotThrow(() -> guard.assertManaged("MEMORY.md"));
        assertDoesNotThrow(() -> guard.assertManaged("tools.json"));
        assertDoesNotThrow(() -> guard.assertManaged("subagents/log.md"));
        assertDoesNotThrow(() -> guard.assertManaged("knowledge/x.md"));

        // skills 已迁到「技能」页，工作区这份不再允许写 —— 否则又会变成"两份技能哪份生效"
        assertThrows(WorkspacePathException.class, () -> guard.assertManaged("skills/a/SKILL.md"));

        // harness 运行时数据根：这个名字和 agent 概念撞名，最容易被误放进来
        assertThrows(WorkspacePathException.class,
                () -> guard.assertManaged("agents/deployment/sessions/sessions.json"));
        assertThrows(WorkspacePathException.class,
                () -> guard.assertManaged("memory/2026-09-20.md"));
        assertThrows(WorkspacePathException.class, () -> guard.assertManaged("README.md"));
        assertThrows(WorkspacePathException.class, () -> guard.assertManaged("nacos/x.json"));
    }

    /**
     * 只读可见范围：运行时产物可看可清理，但 git 仓库与框架内部目录照样挡住。
     *
     * <p>{@code code/} 是真 git 仓库、{@code .xxx/} 是框架内部（缓存 / 消息总线 / 索引），
     * 这两类与"页面维护工作区"无关，一旦放开就是任意文件读写。
     */
    @Test
    void visibleScopeCoversRuntimeButStillBlocksGitAndFrameworkDirs() {
        assertDoesNotThrow(() -> guard.assertVisible("AGENTS.md"));
        assertDoesNotThrow(() -> guard.assertVisible("subagents/log.md"));
        assertDoesNotThrow(() -> guard.assertVisible("memory/2026-09-20.md"));
        assertDoesNotThrow(() -> guard.assertVisible("plans/p.md"));
        assertDoesNotThrow(() -> guard.assertVisible("agents/deployment/tasks/t.json"));
        assertDoesNotThrow(() -> guard.assertVisible("skills/a/SKILL.md"));

        assertThrows(WorkspacePathException.class,
                () -> guard.assertVisible("code/lms/pom.xml"));
        assertThrows(WorkspacePathException.class,
                () -> guard.assertVisible(".agentscope/bus/queues/x.json"));
        assertThrows(WorkspacePathException.class,
                () -> guard.assertVisible(".index/workspace.db"));
        assertThrows(WorkspacePathException.class,
                () -> guard.assertVisible(".skills-cache/filesystem-a/b/SKILL.md"));
        assertThrows(WorkspacePathException.class, () -> guard.assertVisible("datasource/mysql/ddl"));
    }

    /*
     * 裸名（不带层级）的那条分支。
     *
     * 这一组是 2026-09-23 真实起服务点出来的洞：页面把根级条目渲染成「可点进去的目录」，
     * 点击时传的就是 {@code subagents} 这种裸名，而当时的实现只查文件白名单 ——
     * 于是每一个根级目录都变成「列得出来、点进去 400」，页面等于只能看不能进。
     * （当时那批用例全带层级，正好绕过了这条分支。）
     */
    @Test
    void acceptsBareRootDirectoryNames() {
        for (String dir : new String[] {"subagents", "knowledge", "memory", "plans", "agents", "skills"}) {
            assertDoesNotThrow(() -> guard.assertVisible(dir), "可见档应放行裸目录名: " + dir);
        }

        // 可写档只放行可写目录：skills / memory / agents 这些仍要挡住
        assertDoesNotThrow(() -> guard.assertManaged("subagents"));
        assertDoesNotThrow(() -> guard.assertManaged("knowledge"));
        assertThrows(WorkspacePathException.class, () -> guard.assertManaged("skills"));
        assertThrows(WorkspacePathException.class, () -> guard.assertManaged("memory"));
        assertThrows(WorkspacePathException.class, () -> guard.assertManaged("code"));

        // 白名单外的名字依旧拒绝（裸名不等于"随便什么名字都行"）
        assertThrows(WorkspacePathException.class, () -> guard.assertVisible("README.md"));
        assertThrows(WorkspacePathException.class, () -> guard.assertVisible("datasource"));
        assertThrows(WorkspacePathException.class, () -> guard.assertVisible("code"));

        // 两个「不抛异常」的判定必须和上面同一份白名单，否则列目录时 writable 徽标会说谎
        assertTrue(guard.isManaged("subagents"));
        assertFalse(guard.isManaged("skills"));
        assertTrue(guard.isVisible("skills"));
        assertFalse(guard.isVisible("code"));
    }

    /** 写文件不接受裸目录名：那不叫"写一个叫 subagents 的文件"。 */
    @Test
    void writeScopeRejectsBareDirectoryName() {
        WorkspacePathException error = assertThrows(WorkspacePathException.class,
                () -> guard.assertWritableFile("subagents"));
        assertTrue(error.getMessage().contains("目录名"), error.getMessage());
        assertThrows(WorkspacePathException.class, () -> guard.assertWritableFile("knowledge"));

        assertDoesNotThrow(() -> guard.assertWritableFile("AGENTS.md"));
        assertDoesNotThrow(() -> guard.assertWritableFile("subagents/planner.md"));
        assertThrows(WorkspacePathException.class, () -> guard.assertWritableFile("skills/a/SKILL.md"));
    }

    @Test
    void resolveStaysInsideRoot(@TempDir Path root) {
        Path target = guard.resolve(root, "skills/a/SKILL.md");
        assertEquals("SKILL.md", target.getFileName().toString());
        assertTrue(target.startsWith(root.toAbsolutePath().normalize()),
                "解析结果必须落在工作区根目录内: " + target);
    }

    @Test
    void resolveRejectsRootItself(@TempDir Path root) {
        // "." 已经在隐藏路径那关被拒，这里保证即使放行也拿不到根目录本身
        assertThrows(WorkspacePathException.class, () -> guard.resolve(root, "."));
    }
}
