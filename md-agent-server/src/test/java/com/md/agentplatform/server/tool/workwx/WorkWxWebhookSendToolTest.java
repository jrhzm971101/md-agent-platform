package com.md.agentplatform.server.tool.workwx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.WeComWebhookProperties;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企微群机器人 Webhook 工具测试 —— 覆盖最容易出错的三处纯逻辑：
 * 消息类型归一化、@ 列表解析、请求体组装（text 与 markdown 的 @ 机制完全不同）。
 */
class WorkWxWebhookSendToolTest {

    // ==================== 消息类型 ====================

    @Test
    void msgtypeDefaultsToMarkdown() {
        assertEquals("markdown", WorkWxWebhookSendTool.normalizeMsgtype(null));
        assertEquals("markdown", WorkWxWebhookSendTool.normalizeMsgtype("  "));
        assertEquals("markdown", WorkWxWebhookSendTool.normalizeMsgtype("MD"));
    }

    @Test
    void msgtypeIsNormalized() {
        assertEquals("text", WorkWxWebhookSendTool.normalizeMsgtype("TEXT"));
        assertEquals("text", WorkWxWebhookSendTool.normalizeMsgtype(" txt "));
        assertEquals("markdown_v2", WorkWxWebhookSendTool.normalizeMsgtype("markdown-v2"));
        assertEquals("markdown_v2", WorkWxWebhookSendTool.normalizeMsgtype("MD_V2"));
    }

    @Test
    void unknownMsgtypeIsPassedThroughForWeComToReject() {
        assertEquals("template_card", WorkWxWebhookSendTool.normalizeMsgtype("template_card"));
    }

    // ==================== @ 列表解析 ====================

    @Test
    void parseMentionsAcceptsCommonSeparators() {
        assertEquals(List.of("zhangsan", "lisi"),
                WorkWxWebhookSendTool.parseMentions("zhangsan,lisi"));
        assertEquals(List.of("zhangsan", "lisi"),
                WorkWxWebhookSendTool.parseMentions("zhangsan，lisi"));
        assertEquals(List.of("zhangsan", "lisi"),
                WorkWxWebhookSendTool.parseMentions("zhangsan;lisi"));
        assertEquals(List.of("zhangsan", "lisi"),
                WorkWxWebhookSendTool.parseMentions("zhangsan lisi"));
        assertEquals(List.of("zhangsan", "lisi"),
                WorkWxWebhookSendTool.parseMentions("zhangsan\nlisi"));
    }

    @Test
    void parseMentionsAcceptsJsonArraySyntax() {
        // 模型经常直接传 JSON 数组字面量，这里必须也能吃下
        assertEquals(List.of("zhangsan", "lisi"),
                WorkWxWebhookSendTool.parseMentions("[\"zhangsan\", \"lisi\"]"));
    }

    @Test
    void parseMentionsDeduplicatesAndIgnoresBlank() {
        assertEquals(List.of("zhangsan"), WorkWxWebhookSendTool.parseMentions("zhangsan,zhangsan, "));
        assertTrue(WorkWxWebhookSendTool.parseMentions(null).isEmpty());
        assertTrue(WorkWxWebhookSendTool.parseMentions("  ").isEmpty());
    }

    // ==================== 请求体组装 ====================

    @Test
    void textPayloadCarriesMentionedLists() {
        ObjectNode payload = WorkWxWebhookSendTool.buildPayload(
                "text", "今天有 3 个新分支", List.of("zhangsan"), List.of("13800001111"), true);

        assertEquals("text", payload.path("msgtype").asText());
        assertEquals("今天有 3 个新分支", payload.path("text").path("content").asText());
        JsonNode mentioned = payload.path("text").path("mentioned_list");
        assertEquals(2, mentioned.size());
        assertEquals("zhangsan", mentioned.get(0).asText());
        assertEquals("@all", mentioned.get(1).asText(), "mentionAll 通过 mentioned_list 里的 @all 生效");
        assertEquals("13800001111",
                payload.path("text").path("mentioned_mobile_list").get(0).asText());
    }

    @Test
    void textPayloadOmitsEmptyMentionFields() {
        ObjectNode payload = WorkWxWebhookSendTool.buildPayload(
                "text", "纯通知", List.of(), List.of(), false);

        assertFalse(payload.path("text").has("mentioned_list"),
                "不 @ 人时不能带空数组，否则企微按非法参数处理");
        assertFalse(payload.path("text").has("mentioned_mobile_list"));
    }

    @Test
    void markdownPayloadHasNoMentionedList() {
        // markdown 消息带上 mentioned_list 会被企微拒（40058），@ 只能写在正文里
        ObjectNode payload = WorkWxWebhookSendTool.buildPayload(
                "markdown", "## 日报\n- 新分支 3 个", List.of("zhangsan"), List.of(), true);

        assertEquals("markdown", payload.path("msgtype").asText());
        assertEquals("## 日报\n- 新分支 3 个", payload.path("markdown").path("content").asText());
        assertFalse(payload.path("markdown").has("mentioned_list"));
        assertFalse(payload.has("text"), "消息体字段名必须与 msgtype 同名");
    }

    @Test
    void markdownV2PayloadUsesItsOwnField() {
        ObjectNode payload = WorkWxWebhookSendTool.buildPayload(
                "markdown_v2", "| 指标 | 值 |", List.of(), List.of(), false);

        assertEquals("markdown_v2", payload.path("msgtype").asText());
        assertEquals("| 指标 | 值 |", payload.path("markdown_v2").path("content").asText());
    }

    // ==================== markdown 正文 @ ====================

    @Test
    void markdownMentionsAreAppendedToContent() {
        String body = WorkWxWebhookSendTool.withMentions(
                "## 日报\n待处理 3 条", List.of("zhangsan", "lisi"), false);

        assertEquals("## 日报\n待处理 3 条\n\n<@zhangsan><@lisi>", body);
    }

    @Test
    void markdownMentionAllIsAppended() {
        assertEquals("正文\n\n<@all>", WorkWxWebhookSendTool.withMentions("正文", List.of(), true));
    }

    @Test
    void markdownWithoutMentionsKeepsContentUntouched() {
        assertEquals("正文", WorkWxWebhookSendTool.withMentions("正文", List.of(), false));
    }

    // ==================== 错误码翻译 ====================

    @Test
    void describeErrorTranslatesKnownCodes() {
        assertTrue(WorkWxWebhookSendTool.describeError(93000).contains("key 无效"));
        assertTrue(WorkWxWebhookSendTool.describeError(45009).contains("20 条"));
        assertTrue(WorkWxWebhookSendTool.describeError(40008).contains("msgtype"));
        assertEquals("", WorkWxWebhookSendTool.describeError(0), "未知/成功不臆造原因");
    }

    // ==================== 返回结构 ====================

    @Test
    void resultJsonIsStable() {
        ObjectNode node = (ObjectNode) JsonUtil.parse(WorkWxWebhookSendTool.result(
                true, 0, "ok", "default", "markdown", List.of("zhangsan"), null,
                List.of("正文过长"), 120, "", 88));

        assertTrue(node.path("success").asBoolean());
        assertEquals(0, node.path("errcode").asInt());
        assertEquals("default", node.path("endpoint").asText());
        assertEquals("zhangsan", node.path("mentions").get(0).asText());
        assertEquals("正文过长", node.path("warnings").get(0).asText());
        assertEquals(88, node.path("elapsedMs").asInt());
        assertFalse(node.has("hint"), "成功且无提示时不写 hint");
    }

    @Test
    void resultJsonCarriesHintOnFailure() {
        ObjectNode node = (ObjectNode) JsonUtil.parse(WorkWxWebhookSendTool.result(
                false, 93000, "invalid webhook url", "default", "text", List.of(), null,
                List.of(), 10, WorkWxWebhookSendTool.describeError(93000), 5));

        assertFalse(node.path("success").asBoolean());
        assertEquals(93000, node.path("errcode").asInt());
        assertTrue(node.path("hint").asText().contains("key 无效"));
    }

    // ==================== webhook 地址解析 ====================

    @Test
    void resolveUrlBuildsUrlFromKey() {
        WeComWebhookProperties props = new WeComWebhookProperties();
        props.getEndpoints().put("default", "96e90e39-47e5-4919-9cf6-f397b9c59abe");

        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=96e90e39-47e5-4919-9cf6-f397b9c59abe",
                props.resolveUrl(null));
        assertEquals(props.resolveUrl("default"), props.resolveUrl("  default  "), "名字应自动去空格");
    }

    @Test
    void resolveUrlAcceptsFullUrlAndUnknownName() {
        WeComWebhookProperties props = new WeComWebhookProperties();
        props.getEndpoints().put("git-report", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc");

        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc",
                props.resolveUrl("git-report"), "值本身是完整 URL 时按原样使用");
        assertEquals("https://example.com/hook?key=x", props.resolveUrl("https://example.com/hook?key=x"),
                "也允许直接传 URL 当 endpoint");
        assertNull(props.resolveUrl("not-configured"), "未登记的名字返回 null，由工具报错提示");
        assertNull(new WeComWebhookProperties().resolveUrl(null), "未配置时不静默拼出空 key");
    }

    // ==================== 配置绑定 ====================

    @Test
    void propertiesBindFromTheYamlPathUsedInApplicationYml() {
        // 校验 @ConfigurationProperties 前缀与 application.yml / cm.yaml 里的层级一致，
        // 否则部署后 endpoint 解析不到 key，只有运行时才会炸
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("agent-platform.messaging.wecom-webhook.api-url",
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send");
        source.put("agent-platform.messaging.wecom-webhook.timeout-ms", "15000");
        source.put("agent-platform.messaging.wecom-webhook.endpoints.default",
                "96e90e39-47e5-4919-9cf6-f397b9c59abe");

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", source));
        WeComWebhookProperties props = Binder.get(environment)
                .bind("agent-platform.messaging.wecom-webhook", WeComWebhookProperties.class)
                .get();

        assertEquals(15000, props.getTimeoutMs());
        assertEquals(List.of("default"), List.copyOf(props.endpointNames()));
        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=96e90e39-47e5-4919-9cf6-f397b9c59abe",
                props.resolveUrl("default"));
    }
}
