package com.md.agentplatform.controller.alert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenObserve 告警 Webhook 接收接口。
 *
 * <h2>一、为什么要有这层接收</h2>
 * OpenObserve 的 Webhook Destination 只会把模板渲染后的 Body 原样 POST 出来，本身不提供
 * “查询某次告警的推送内容”的接口。平台要留一份可检索的告警通知，就必须先有一个稳定的接收入口。
 * 本接口只负责<b>接住并记录</b>，不猜测业务后续动作：转发企微、落库、通知值班等都应该在
 * 服务层按需扩展，而不是塞进 Controller。
 *
 * <h2>二、报文按“企微机器人 text 消息”结构解析</h2>
 * 约定 Body 为：
 * <pre>{@code
 * {
 *   "msgtype": "text",
 *   "text": { "content": "🚨 【告警通知】\n告警名称: ...\n流名称: ..." }
 * }
 * }</pre>
 * 这与 OpenObserve 模板里写的内容无关 —— 模板怎么写，这里就收到什么。因此不使用
 * {@code @Valid} 去卡死字段格式：模板以后加字段、改标题，不应该让接收端开始 400。
 * 只校验“能否作为一条通知入账”的最低要求：{@code msgtype} 非空、{@code text.content} 非空。
 *
 * <h2>三、为什么返回 200 而不是业务码</h2>
 * OpenObserve 只看 HTTP 状态码：非 2xx 会重试（默认最多 3 次），随后把这次通知记为
 * {@code notify_failed}。接收端只要成功落日志就应回 200；真正需要区分业务结果时，
 * 让 OpenObserve 的模板或后续接口去表达，不要把重试策略绑在业务码上。
 *
 * <h2>四、日志口径</h2>
 * INFO 只打印从 content 里抽取的少量关键字段（告警名、流名、触发时间），避免把整段日志
 * 反复刷进日志文件；完整原文放 DEBUG，排障时再开。抽取失败不影响接收，字段留空即可 ——
 * 模板文案是可变的，接收端不能因为换了个标题就拒收。
 */
@RestController
@RequestMapping("/api/webhooks/openobserve")
public class OpenObserveAlertWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(OpenObserveAlertWebhookController.class);

    /** 与 OpenObserve 模板中的中文标签保持一致；冒号兼容中英文。 */
    private static final Pattern ALERT_NAME = lineField("告警名称");
    private static final Pattern STREAM_NAME = lineField("流名称");
    private static final Pattern SEVERITY = lineField("严重程度");
    private static final Pattern TRIGGER_TIME = lineField("触发时间");

    /**
     * 接收 OpenObserve 推送的告警通知。
     *
     * @param payload OpenObserve 模板渲染后的 Body；允许缺省，缺省时按非法报文处理
     * @return 成功 200；报文缺少 {@code msgtype} 或 {@code text.content} 时 400
     */
    @PostMapping("/alerts")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody(required = false) OpenObserveAlertPayload payload) {
        if (payload == null || !hasText(payload.msgtype())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("received", false, "error", "msgtype 不能为空"));
        }
        if (payload.text() == null || !hasText(payload.text().content())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("received", false, "error", "text.content 不能为空"));
        }

        String content = payload.text().content();
        logger.info("[OpenObserve-Webhook] received alert: msgtype={}, alertName={}, stream={}, severity={}, triggerTime={}",
                payload.msgtype(),
                extract(ALERT_NAME, content),
                extract(STREAM_NAME, content),
                extract(SEVERITY, content),
                extract(TRIGGER_TIME, content));
        logger.debug("[OpenObserve-Webhook] raw content: {}", content);

        return ResponseEntity.ok(Map.of("received", true));
    }

    /** 单行字段：{@code 标签: 值}，直到行尾。 */
    private static Pattern lineField(String label) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(label) + "\\s*[:：]\\s*(.*?)\\s*$");
    }

    private static String extract(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? oneLine(matcher.group(1)) : "";
    }

    /** 日志里的字段值不保留换行，避免一条告警把日志切成多行。 */
    private static String oneLine(String value) {
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 企微机器人 text 消息的外层结构。
     *
     * <p>{@code ignoreUnknown = true} 是刻意保留的兼容口：OpenObserve 模板以后多带
     * {@code mentioned_list}、{@code markdown} 等字段时，接收端不需要跟着发版。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenObserveAlertPayload(String msgtype, Text text) {}

    /** text 消息体。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Text(String content) {}
}
