//package com.md.agentplatform.server.agent;
//
//import io.agentscope.core.skill.AgentSkill;
//import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
//import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
//import org.junit.jupiter.api.Test;
//
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.assertFalse;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
///**
// * 工作区技能的本地落盘契约测试。
// *
// * <p>HARNESS agent 的技能不走显式配置：框架用 {@code WorkspaceSkillRepository} 在
// * {@code <workspace>/skills/**&#47;SKILL.md} 下自动扫盘。所以「技能文件放本地就能被加载」
// * 这件事取决于两件事同时成立：目录层级对、frontmatter 能被解析。
// * 本测试直接拿真实工作区目录跑一遍扫描，防的是改名 / 挪目录 / frontmatter 写错后静默失效。
// */
//class WorkspaceSkillLayoutTest {
//
//    @Test
//    void harnessWorkspaceSkillIsDiscovered() throws Exception {
//        Path workspace = findHarnessWorkspace();
//        WorkspaceSkillRepository repo = new WorkspaceSkillRepository(
//                new LocalFilesystem(workspace), "skills", () -> null);
//
//        List<AgentSkill> skills = repo.getAllSkills();
//        List<String> names = repo.getAllSkillNames();
//
//        assertTrue(names.contains("batch-audit-and-notify"),
//                "工作区技能未被扫到，实际扫到: " + names + "（目录 " + workspace.resolve("skills") + "）");
//
//        AgentSkill skill = skills.stream()
//                .filter(s -> "batch-audit-and-notify".equals(s.getName()))
//                .findFirst()
//                .orElseThrow();
//        assertFalse(skill.getDescription() == null || skill.getDescription().isBlank(),
//                "SKILL.md 的 frontmatter 缺 description：技能目录会进 <available_skills>，"
//                        + "description 空了模型就没法判断该不该加载");
//    }
//
//    /** 从模块目录往上找仓库根，定位 harness 工作区，避免写死绝对路径。 */
//    private static Path findHarnessWorkspace() {
//        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
//        while (cursor != null) {
//            Path candidate = cursor.resolve("workspace/harness/workwx_batch_agent");
//            if (Files.isDirectory(candidate.resolve("skills"))) {
//                return candidate;
//            }
//            cursor = cursor.getParent();
//        }
//        throw new IllegalStateException("未找到 workspace/harness/workwx_batch_agent/skills，"
//                + "user.dir=" + System.getProperty("user.dir"));
//    }
//}
