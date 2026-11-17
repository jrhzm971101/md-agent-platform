package com.md.agentplatform.server.messaging.render;

import com.md.agentplatform.server.messaging.BotPlatform;
import com.md.agentplatform.server.messaging.InboundMessage;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话键与 HARNESS agent 的接口测试 —— 这一处真崩过：
 * 会话键曾是 {@code single:<userid>:<date>}，框架的 {@code validateSessionId} 放行，
 * 但 harness 把会话键当成任务文件名 {@code tasks/<sessionId>.json}，
 * Windows 下 {@code :} 让 {@code Path.of} 抛 InvalidPathException，
 * 表现是「harness 一进 REASONING 就报错」，且只有 harness 会中招（REACT 不写任务文件）。
 *
 * <p>所以断言落在两头：会话键必须是文件名安全字符集，且拿它真跑一遍 harness 推理链路不炸。
 * 放在 render 包下是因为这条不变式属于「会话键怎么拼」，改 {@link WeComThinkRenderer}
 * 的分隔符时这个测试必须跟着红。
 */
class HarnessSessionIdTest {

    private static final String USER_ID = "wo7U1ZEQAApziTCwT6jow9UKLs0xrqlw";

    @Test
    void singleChatSessionIdSurvivesHarnessReasoning() throws Exception {
        Path temp = Files.createTempDirectory("harness-session-test");
        try {
            String sessionId = WeComThinkRenderer.resolveSessionId(inbound(null));

            assertFalse(sessionId.matches(".*[:*?\"<>|].*"),
                    "会话键含 Windows 文件名非法字符，harness 写任务文件时会崩: " + sessionId);

            HarnessAgent agent = HarnessAgent.builder()
                    .name("workwx_batch_agent")
                    .workspace(temp)
                    .model(emptyModel())
                    .build();

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId(sessionId)
                    .userId(USER_ID)
                    .build();

            agent.streamEvents(List.of(userMsg()), ctx).blockLast();

            // 会话键在这里变成了落盘文件名 —— 含 `:` 时 Path.of 会在这之前就抛异常
            Path sessionFile = temp.resolve(USER_ID).resolve("agents/workwx_batch_agent/sessions")
                    .resolve(sessionId + ".jsonl");
            assertTrue(Files.isRegularFile(sessionFile),
                    "没写出以会话键命名的会话文件，说明这条断言没跑到写文件那一步: " + sessionFile);
        } finally {
            deleteTree(temp);
        }
    }

    @Test
    void groupChatSessionIdIsAlsoFileNameSafe() {
        String sessionId = WeComThinkRenderer.resolveSessionId(
                inbound("wr7U1ZEQAApTfv9HpsQk6541gBDCfUqg"));

        assertFalse(sessionId.matches(".*[:*?\"<>|].*"), "群聊会话键同样会被当成文件名: " + sessionId);
        Path.of(sessionId);
    }

    private static InboundMessage inbound(String chatId) {
        return new InboundMessage("default", BotPlatform.WECOM, USER_ID,
                chatId, chatId == null ? "single" : "group",
                "text", "msg-1", "{\"content\":\"hi\"}", Map.of(), "req-1");
    }

    private static Msg userMsg() {
        return Msg.builder().role(MsgRole.USER).textContent("hi").build();
    }

    /** 空响应桩：只需要链路跑到写任务文件，不关心模型输出。 */
    private static Model emptyModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "stub";
            }
        };
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 临时目录清理失败不影响断言
                }
            });
        }
    }
}
