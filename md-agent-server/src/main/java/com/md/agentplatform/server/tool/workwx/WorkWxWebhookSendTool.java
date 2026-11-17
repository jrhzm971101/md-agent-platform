package com.md.agentplatform.server.tool.workwx;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.WeComWebhookProperties;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 企业微信群机器人 Webhook 推送工具。
 *
 * <p>与 {@link WorkWxSendMsgTool}（智能机器人，WebSocket 长连接）是两套独立通道，各自解决不同问题：
 * <table border="1">
 *   <tr><th></th><th>智能机器人</th><th>群机器人（本工具）</th></tr>
 *   <tr><td>协议</td><td>WebSocket 长连接</td><td>HTTP Webhook 推送</td></tr>
 *   <tr><td>消息类型</td><td>仅 markdown</td><td>text / markdown / markdown_v2</td></tr>
 *   <tr><td>@ 人</td><td>不支持（{@code <@userid>} 会原样显示）</td><td>支持</td></tr>
 *   <tr><td>频率限制</td><td>—</td><td>每个机器人每分钟 20 条</td></tr>
 * </table>
 *
 * <p>@ 人的两种写法（企微限制，不可混用）：
 * <ul>
 *   <li>{@code msgtype=text}：通过请求体的 {@code mentioned_list} / {@code mentioned_mobile_list} 触发</li>
 *   <li>{@code msgtype=markdown}：只能在正文里写 {@code <@userid>}，工具会在正文末尾自动追加</li>
 *   <li>{@code msgtype=markdown_v2}：不支持 @，mention 参数会被忽略并给出提示</li>
 * </ul>
 */
@Component
public class WorkWxWebhookSendTool {

    private static final Logger logger = LoggerFactory.getLogger(WorkWxWebhookSendTool.class);

    /** text 消息类型：@ 通过 mentioned_list 生效。 */
    static final String MSGTYPE_TEXT = "text";

    /** markdown 消息类型（企微旧版 markdown）：@ 通过正文里的 <@userid> 生效。 */
    static final String MSGTYPE_MARKDOWN = "markdown";

    /** markdown_v2 消息类型：语法更全，但不支持 @。 */
    static final String MSGTYPE_MARKDOWN_V2 = "markdown_v2";

    /** 企微单条消息内容上限（字节），超过会被截断。 */
    private static final int MAX_CONTENT_BYTES = 4096;

    /** @所有人 在 text 消息 mentioned_list 里的写法。 */
    private static final String MENTION_ALL = "@all";

    /** @所有人 在 markdown 正文里的写法。 */
    private static final String MENTION_ALL_INLINE = "<@all>";

    private RestTemplate restTemplate;

    @Autowired
    private WeComWebhookProperties properties;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    @Tool(
            name = "workwx-webhook-send",
            description = """
                    企业微信群机器人 Webhook 推送工具，把消息推送到登记的群，并可以真正 @ 到指定成员。

                    用途: 推送日报/周报/告警/统计报表到企业微信群，并提醒负责人或提交人。
                    这是唯一能 @ 到人的推送方式（智能机器人 workwx-send-msg 不支持 @，不要用它做提醒）。

                    参数说明:
                    - content: 消息内容（必填）。msgtype=markdown 时按企微 markdown 语法书写
                      （支持 ## 标题、**加粗**、> 引用、- 列表、[文字](url)、表格、<font color="comment|info|warning">彩色</font>）
                    - msgtype: 消息类型（选填，默认 markdown），可选 text / markdown / markdown_v2
                    - endpoint: 目标群（选填，默认 default）。取值见 workwx-registry 技能的群登记表：
                      可以是登记表里的群名（如 default，由配置解析），也可以直接传完整 webhook 地址
                    - mentionedUserIds: 要 @ 的成员 userid，多个用逗号分隔（选填，建议用 text 消息）
                    - mentionedMobiles: 要 @ 的成员手机号，多个用逗号分隔（选填，建议用 text 消息）
                    - mentionAll: 是否 @所有人（选填，默认 false）

                    @ 人的写法（企微限制）:
                    - text 消息: 由 mentionedUserIds / mentionedMobiles / mentionAll 自动生成 @
                    - markdown 消息: 工具会把 <@userid>（和 <@all>）自动追加到正文末尾
                    - markdown_v2: 不支持 @，相关参数会被忽略并在 warnings 里说明

                    返回: JSON —— success / errcode / errmsg / endpoint / msgtype / mentions /
                    contentBytes / warnings / elapsedMs；失败时额外给出 hint。
                    常见 errcode: 93000 webhook key 无效；45009 触发频率限制（每分钟 20 条）。
                    """)
    public String send(
            @ToolParam(name = "content", description = "要发送的消息内容；msgtype=markdown 时为企微 markdown 文本") String content,
            @ToolParam(name = "msgtype", description = "消息类型，可选 text / markdown / markdown_v2，默认 markdown") String msgtype,
            @ToolParam(name = "endpoint", description = "目标群名（见 workwx-registry 技能的群登记表）或完整 webhook 地址，默认 default") String endpoint,
            @ToolParam(name = "mentionedUserIds", description = "要 @ 的成员 userid，多个用逗号分隔（text 消息生效）") String mentionedUserIds,
            @ToolParam(name = "mentionedMobiles", description = "要 @ 的成员手机号，多个用逗号分隔（text 消息生效）") String mentionedMobiles,
            @ToolParam(name = "mentionAll", description = "是否 @所有人，默认 false") Boolean mentionAll
    ) {
        long t0 = System.currentTimeMillis();
        String type = normalizeMsgtype(msgtype);
        String endpointName = (endpoint == null || endpoint.isBlank())
                ? WeComWebhookProperties.DEFAULT_ENDPOINT : endpoint.trim();

        if (content == null || content.isBlank()) {
            return result(false, -1, "参数缺失: content 必填", endpointName, type,
                    List.of(), null, null, 0, "", System.currentTimeMillis() - t0);
        }

        String url = properties.resolveUrl(endpointName);
        if (url == null) {
            return result(false, -1,
                    "配置缺失: endpoint=" + endpointName + " 未配置 webhook 地址",
                    endpointName, type, List.of(), null, null, 0,
                    "请配置 agent-platform.messaging.wecom-webhook.endpoints；已登记: "
                            + properties.endpointNames(),
                    System.currentTimeMillis() - t0);
        }

        List<String> userIds = parseMentions(mentionedUserIds);
        List<String> mobiles = parseMentions(mentionedMobiles);
        boolean all = Boolean.TRUE.equals(mentionAll);
        List<String> warnings = new ArrayList<>();

        String body = content;
        List<String> mentions = new ArrayList<>();
        if (MSGTYPE_TEXT.equals(type)) {
            mentions.addAll(userIds);
            mobiles.forEach(mobile -> mentions.add("@mobile:" + mobile));
            if (all) {
                mentions.add(MENTION_ALL);
            }
        } else if (MSGTYPE_MARKDOWN.equals(type)) {
            mentions.addAll(userIds);
            if (all) {
                mentions.add(MENTION_ALL);
            }
            body = withMentions(content, userIds, all);
        } else if (!userIds.isEmpty() || !mobiles.isEmpty() || all) {
            warnings.add("msgtype=" + type + " 不支持 @，mention 参数已忽略；"
                    + "需要 @ 人请改用 text 或 markdown");
        }

        int contentBytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_CONTENT_BYTES) {
            warnings.add("正文 " + contentBytes + " 字节，超过企微上限 " + MAX_CONTENT_BYTES
                    + " 字节，可能被截断");
        }

        ObjectNode payload = buildPayload(type, body, userIds, mobiles, all);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String responseBody;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(JsonUtil.stringify(payload), headers), String.class);
            responseBody = response.getBody();
        } catch (ResourceAccessException e) {
            return result(false, -1, "网络异常: " + e.getMessage(), endpointName, type,
                    mentions, null, warnings, contentBytes,
                    "请检查服务器到 qyapi.weixin.qq.com 的网络连通性", System.currentTimeMillis() - t0);
        } catch (HttpStatusCodeException e) {
            return result(false, e.getStatusCode().value(),
                    "HTTP " + e.getStatusCode().value(), endpointName, type,
                    mentions, null, warnings, contentBytes, e.getResponseBodyAsString(),
                    System.currentTimeMillis() - t0);
        }

        int errcode = -1;
        String errmsg = "";
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                ObjectNode parsed = (ObjectNode) JsonUtil.parse(responseBody);
                errcode = parsed.path("errcode").asInt(-1);
                errmsg = parsed.path("errmsg").asText("");
            } catch (RuntimeException e) {
                logger.warn("[WxWebhook] 响应体解析失败: {}", responseBody);
            }
        }
        boolean success = errcode == 0;
        logger.info("[WxWebhook] 推送完成 success={} endpoint={} msgtype={} errcode={} mentions={} 耗时:{}ms",
                success, endpointName, type, errcode, mentions, System.currentTimeMillis() - t0);
        return result(success, errcode, errmsg, endpointName, type, mentions, null, warnings,
                contentBytes, success ? "" : describeError(errcode),
                System.currentTimeMillis() - t0);
    }

    // ==================== 纯逻辑（可直接单测） ====================

    /** 归一化消息类型；空值默认 markdown，未知值原样透传交给企微报 40008。 */
    static String normalizeMsgtype(String msgtype) {
        if (msgtype == null || msgtype.isBlank()) {
            return MSGTYPE_MARKDOWN;
        }
        String value = msgtype.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "text", "txt", "plain" -> MSGTYPE_TEXT;
            case "markdown", "md" -> MSGTYPE_MARKDOWN;
            case "markdown_v2", "md_v2" -> MSGTYPE_MARKDOWN_V2;
            default -> value;
        };
    }

    /**
     * 解析 @ 列表。兼容逗号（中英文）、分号、顿号、空格、换行分隔，
     * 以及模型习惯性传入的 JSON 数组写法 {@code ["a","b"]}；去重且保持顺序。
     */
    static List<String> parseMentions(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        String normalized = raw
                .replace('[', ',').replace(']', ',')
                .replace('"', ' ').replace('\'', ' ')
                .replace('，', ',').replace('、', ',')
                .replace('；', ',').replace(';', ',')
                .replace('\n', ',').replace('\r', ',');
        for (String part : normalized.split("[,\\s]+")) {
            String value = part.trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    /** markdown 消息的 @ 只能写在正文里，这里把 <@userid> / <@all> 追加到末尾。 */
    static String withMentions(String content, List<String> userIds, boolean mentionAll) {
        StringBuilder suffix = new StringBuilder();
        for (String userId : userIds) {
            suffix.append("<@").append(userId).append('>');
        }
        if (mentionAll) {
            suffix.append(MENTION_ALL_INLINE);
        }
        return suffix.isEmpty() ? content : content + "\n\n" + suffix;
    }

    /**
     * 组装企微 webhook 请求体。
     *
     * <p>text 消息的 @ 走 {@code mentioned_list} / {@code mentioned_mobile_list}；
     * markdown / markdown_v2 不带这两个字段（实测企微会直接忽略、不报错也不生效），
     * 因此 @ 只能写在正文里，由 {@link #withMentions} 追加。
     */
    static ObjectNode buildPayload(String msgtype, String content,
                                   List<String> userIds, List<String> mobiles, boolean mentionAll) {
        ObjectNode payload = JsonUtil.newObject();
        payload.put("msgtype", msgtype);
        if (MSGTYPE_TEXT.equals(msgtype)) {
            ObjectNode text = JsonUtil.newObject();
            text.put("content", content);
            ArrayNode mentionedList = mentionArray(userIds, mentionAll);
            if (!mentionedList.isEmpty()) {
                text.set("mentioned_list", mentionedList);
            }
            ArrayNode mobileList = mentionArray(mobiles, false);
            if (!mobileList.isEmpty()) {
                text.set("mentioned_mobile_list", mobileList);
            }
            payload.set(MSGTYPE_TEXT, text);
        } else {
            ObjectNode body = JsonUtil.newObject();
            body.put("content", content);
            payload.set(msgtype, body);
        }
        return payload;
    }

    /** 翻译企微常见错误码；未知错误码返回空串（不臆造原因）。 */
    static String describeError(int errcode) {
        return switch (errcode) {
            case 93000 -> "webhook key 无效或已被删除，请确认 URL 里的 key 是否正确";
            case 45009 -> "触发频率限制：同一个群机器人每分钟最多推送 20 条，请降低频率后重试";
            case 40008 -> "消息类型不合法：msgtype 只支持 text / markdown / markdown_v2，且 content 不能为空";
            case 40058 -> "参数不合法：如 content 为空、消息体字段名与 msgtype 不匹配、@ 的 userid 格式非法";
            case 40001 -> "凭证无效：请确认 webhook key 是否正确";
            default -> "";
        };
    }

    private static ArrayNode mentionArray(List<String> values, boolean mentionAll) {
        ArrayNode array = JsonUtil.newArray();
        if (values != null) {
            values.forEach(array::add);
        }
        if (mentionAll) {
            array.add(MENTION_ALL);
        }
        return array;
    }

    /** 统一结果结构，便于调用方（含 agent）稳定解析。 */
    static String result(boolean success, int errcode, String errmsg, String endpoint, String msgtype,
                         List<String> mentions, String rawResponse, List<String> warnings,
                         int contentBytes, String hint, long elapsedMs) {
        ObjectNode node = JsonUtil.newObject();
        node.put("success", success);
        node.put("errcode", errcode);
        node.put("errmsg", errmsg == null ? "" : errmsg);
        node.put("endpoint", endpoint);
        node.put("msgtype", msgtype);
        node.put("contentBytes", contentBytes);
        ArrayNode mentionNode = node.putArray("mentions");
        if (mentions != null) {
            mentions.forEach(mentionNode::add);
        }
        if (warnings != null && !warnings.isEmpty()) {
            ArrayNode warningNode = node.putArray("warnings");
            warnings.forEach(warningNode::add);
        }
        if (rawResponse != null && !rawResponse.isBlank()) {
            node.put("response", rawResponse);
        }
        if (hint != null && !hint.isBlank()) {
            node.put("hint", hint);
        }
        node.put("elapsedMs", elapsedMs);
        return JsonUtil.stringify(node);
    }
}
