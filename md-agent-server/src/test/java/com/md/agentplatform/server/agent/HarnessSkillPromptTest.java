//package com.md.agentplatform.server.agent;
//
//import io.agentscope.core.agent.RuntimeContext;
//import io.agentscope.core.message.Msg;
//import io.agentscope.core.model.ChatResponse;
//import io.agentscope.core.model.GenerateOptions;
//import io.agentscope.core.model.Model;
//import io.agentscope.core.model.ToolSchema;
//import io.agentscope.core.skill.repository.AgentSkillRepository;
//import io.agentscope.core.tool.Toolkit;
//import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
//import io.agentscope.harness.agent.HarnessAgent;
//import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
//import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
//import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
//import org.junit.jupiter.api.Test;
//import reactor.core.publisher.Flux;
//
//import java.nio.file.FileVisitResult;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.SimpleFileVisitor;
//import java.nio.file.StandardCopyOption;
//import java.nio.file.attribute.BasicFileAttributes;
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.assertTrue;
//
///**
// * 「技能进 system prompt」链路测试 —— 技能文件落盘只算一半，另一半是它得真的出现在
// * {@code <available_skills>} 里，否则模型根本不知道该加载什么。
// *
// * <p>这里直接跑框架的 {@link HarnessSkillMiddleware}，把工作区技能仓库喂进去，
// * 断言渲染出的提示词里带上了技能名与 description。技能正文本身不该出现——正文是
// * 按需 {@code load_skill} 才读的，出现在常驻提示词里就说明「技能目录不占上下文」这个前提破了。
// */
//class HarnessSkillPromptTest {
//
//    @Test
//    void workspaceSkillIsAdvertisedInSystemPrompt() {
//        Path workspace = findHarnessWorkspace();
//        AgentSkillRepository repo = new WorkspaceSkillRepository(
//                new LocalFilesystem(workspace), "skills", () -> null);
//        HarnessSkillMiddleware middleware =
//                new HarnessSkillMiddleware(List.of(repo), new Toolkit());
//
//        String prompt = middleware
//                .onSystemPrompt(null, RuntimeContext.empty(), "BASE_PROMPT")
//                .block();
//
//        assertTrue(prompt != null && prompt.contains("batch-audit-and-notify"),
//                "技能没进 <available_skills>，模型无从得知要加载它。渲染结果:\n" + prompt);
//        assertTrue(prompt.contains("批量核对与提醒下发流程"),
//                "技能 description 没进提示词，模型没有选择依据。渲染结果:\n" + prompt);
//        assertTrue(prompt.contains("BASE_PROMPT"), "原有 system prompt 被覆盖了:\n" + prompt);
//    }
//
//    private static Path findHarnessWorkspace() {
//        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
//        while (cursor != null) {
//            Path candidate = cursor.resolve("workspace/harness/workwx_batch_agent");
//            if (Files.isDirectory(candidate.resolve("skills"))) {
//                return candidate;
//            }
//            cursor = cursor.getParent();
//        }
//        throw new IllegalStateException("未找到 workspace/harness/workwx_batch_agent/skills");
//    }
//
//    /**
//     * 工作区 {@code AGENTS.md} 必须进 system prompt —— 它承载「稳定参数表 + 硬约束」这类
//     * 每轮都得生效的内容，靠技能按需加载是靠不住的。
//     *
//     * <p>用真实文件复制到临时工作区再跑，避免 HarnessAgent 初始化在真实工作区里生成
//     * memory / session 目录；断言的文本取自真实 AGENTS.md，改名或删段都会失败。
//     */
//    @Test
//    void workspaceAgentsMdIsInjectedIntoSystemPrompt() throws Exception {
//        Path real = findHarnessWorkspace();
//        Path temp = Files.createTempDirectory("workwx-harness-ws");
//        try {
//            Files.copy(real.resolve("AGENTS.md"), temp.resolve("AGENTS.md"),
//                    StandardCopyOption.REPLACE_EXISTING);
//            copyTree(real.resolve("skills"), temp.resolve("skills"));
//
//            HarnessAgent agent;
//            try {
//                agent = HarnessAgent.builder()
//                        .name("workwx_batch_agent")
//                        .workspace(temp)
//                        .model(stubModel())
//                        .build();
//            } catch (Exception e) {
//                throw new IllegalStateException("harness 装配失败（本地定义跑不起来）", e);
//            }
//
//            String prompt = new WorkspaceContextMiddleware(agent.getWorkspaceManager())
//                    .onSystemPrompt(null, RuntimeContext.empty(), "BASE_PROMPT")
//                    .block();
//
//            assertTrue(prompt != null && prompt.contains("本次运行参数"),
//                    "AGENTS.md 没进 system prompt，参数表就形同虚设。渲染结果:\n" + prompt);
//            assertTrue(prompt.contains("待确认"),
//                    "AGENTS.md 的取值状态列没进 prompt，模型无法判断哪些参数还没定。渲染结果:\n" + prompt);
//            assertTrue(prompt.contains("BASE_PROMPT"), "原有 system prompt 被覆盖了");
//        } finally {
//            deleteTree(temp);
//        }
//    }
//
//    /** 不调模型的桩：这些断言只关心提示词怎么拼，不关心模型返回什么。 */
//    private static Model stubModel() {
//        return new Model() {
//            @Override
//            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
//                                             GenerateOptions options) {
//                return Flux.empty();
//            }
//
//            @Override
//            public String getModelName() {
//                return "stub";
//            }
//        };
//    }
//
//    private static void copyTree(Path from, Path to) throws Exception {
//        Files.walkFileTree(from, new SimpleFileVisitor<>() {
//            @Override
//            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
//                    throws java.io.IOException {
//                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
//                return FileVisitResult.CONTINUE;
//            }
//
//            @Override
//            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
//                    throws java.io.IOException {
//                Files.copy(file, to.resolve(from.relativize(file).toString()),
//                        StandardCopyOption.REPLACE_EXISTING);
//                return FileVisitResult.CONTINUE;
//            }
//        });
//    }
//
//    private static void deleteTree(Path root) throws Exception {
//        if (!Files.exists(root)) {
//            return;
//        }
//        try (var paths = Files.walk(root)) {
//            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
//                try {
//                    Files.deleteIfExists(p);
//                } catch (Exception ignored) {
//                    // 临时目录清理失败不影响断言
//                }
//            });
//        }
//    }
//}
