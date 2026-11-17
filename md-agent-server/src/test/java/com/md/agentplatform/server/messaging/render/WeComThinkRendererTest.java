package com.md.agentplatform.server.messaging.render;

import com.md.agentplatform.server.messaging.BotPlatform;
import com.md.agentplatform.server.messaging.InboundMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企微会话分区键测试 —— 覆盖最容易出错的一处：企微<b>单聊没有 chatid</b>，
 * 直接把它透传给 RuntimeContext 会被框架静默回落到 defaultSessionId（缺省是 agent 名），
 * 所有单聊用户就落到同一个记忆分区里。
 */
class WeComThinkRendererTest {

    private static InboundMessage wecom(String userId, String chatId, String chatType) {
        return new InboundMessage("default", BotPlatform.WECOM, userId, chatId, chatType,
                "text", "msg-" + userId, "{\"content\":\"hi\"}", Map.of(), "req-1");
    }

    private static final String TODAY = "-" + LocalDate.now();

    @Test
    void groupSessionIsScopedByChatId() {
        String sessionId = WeComThinkRenderer.resolveSessionId(
                wecom("zhangsan", "wr7U1ZEQAApTfv9HpsQk6541gBDCfUqg", "group"));

        assertEquals("wr7U1ZEQAApTfv9HpsQk6541gBDCfUqg" + TODAY, sessionId);
    }

    @Test
    void groupMembersShareOneSession() {
        // 群聊下同群不同人应落到同一个 session（群 = 一个房间），身份区分由 userId 承担
        String a = WeComThinkRenderer.resolveSessionId(wecom("zhangsan", "chatA", "group"));
        String b = WeComThinkRenderer.resolveSessionId(wecom("lisi", "chatA", "group"));

        assertEquals(a, b);
    }

    @Test
    void singleChatFallsBackToUserIdInsteadOfNull() {
        // 单聊没有 chatid，绝不能传出 null
        String sessionId = WeComThinkRenderer.resolveSessionId(
                wecom("wo7U1ZEQAApziTCwT6jow9UKLs0xrqlw", null, "single"));

        assertEquals("single-wo7U1ZEQAApziTCwT6jow9UKLs0xrqlw" + TODAY, sessionId);
    }

    @Test
    void singleChatUsersAreIsolatedFromEachOther() {
        String a = WeComThinkRenderer.resolveSessionId(wecom("zhangsan", null, "single"));
        String b = WeComThinkRenderer.resolveSessionId(wecom("lisi", null, "single"));

        assertFalse(a.equals(b), "两个单聊用户不能共用一个会话分区");
    }

    @Test
    void singleChatSessionNeverCollidesWithGroupSession() {
        // "single-" 前缀保证单聊分区不会和某个群 id 撞名
        String single = WeComThinkRenderer.resolveSessionId(wecom("zhangsan", null, "single"));
        String group = WeComThinkRenderer.resolveSessionId(wecom("zhangsan", "zhangsan", "group"));

        assertFalse(single.equals(group));
    }

    @Test
    void blankChatIdIsTreatedAsSingleChat() {
        assertEquals("single-zhangsan" + TODAY,
                WeComThinkRenderer.resolveSessionId(wecom("zhangsan", "   ", "single")));
    }

    @Test
    void sessionIdIsDailyReset() {
        // 日期带在 sessionId 上 → 同一群/同一个人跨天开一份新上下文
        String sessionId = WeComThinkRenderer.resolveSessionId(wecom("zhangsan", "chatA", "group"));
        assertTrue(sessionId.endsWith(TODAY), "日期应拼在末尾");
    }

    @Test
    void sessionIdContainsNoPathSeparator() {
        // 框架的 validateSessionId 遇到 / 或 \ 会直接抛异常
        String sessionId = WeComThinkRenderer.resolveSessionId(wecom("zhangsan", "chatA", "group"));

        assertFalse(sessionId.contains("/"), "sessionId 不能含路径分隔符");
        assertFalse(sessionId.contains("\\"), "sessionId 不能含路径分隔符");
    }

    @Test
    void sessionIdIsSafeAsFileName() {
        // HARNESS agent 把 sessionId 当任务文件名用（tasks/<sessionId>.json），
        // Windows 下 : * ? " < > | 都会让 Path.of 抛 InvalidPathException。
        // 这里对单聊/群聊两种来源都过一遍，防止再退回 ":2026-09-17" 这种写法。
        for (String sessionId : new String[]{
                WeComThinkRenderer.resolveSessionId(wecom("zhangsan", null, "single")),
                WeComThinkRenderer.resolveSessionId(wecom("zhangsan", "chatA", "group"))}) {

            assertFalse(sessionId.matches(".*[:*?\"<>|].*"),
                    "sessionId 含 Windows 文件名非法字符，harness 会崩: " + sessionId);
            java.nio.file.Path.of(sessionId);
            assertTrue(sessionId.length() <= 255, "sessionId 超过框架 255 上限");
        }
    }

    @Test
    void sessionIdSanitizesUnexpectedUserInput() {
        // userId 是外部输入，不能假设它只有字母数字
        String sessionId = WeComThinkRenderer.resolveSessionId(wecom("zhang:san/../a", null, "single"));

        assertFalse(sessionId.contains(":"), "外部输入里的非法字符必须被替换: " + sessionId);
        assertFalse(sessionId.contains("/"), "外部输入里的路径分隔符必须被替换: " + sessionId);
        assertTrue(sessionId.startsWith("single-zhang-san-..-a"), "实际: " + sessionId);
    }
}
