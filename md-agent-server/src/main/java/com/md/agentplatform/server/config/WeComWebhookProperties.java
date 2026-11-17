package com.md.agentplatform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 企业微信「群机器人」Webhook 推送配置。
 *
 * <p>注意这和 {@code agent-platform.messaging.bots} 下的智能机器人是<b>两套独立通道</b>：
 * <ul>
 *   <li>智能机器人：WebSocket 长连接，只能发 markdown，<b>不能 @ 人</b>（见 WorkWxSendMsgTool）</li>
 *   <li>群机器人（本配置）：HTTP Webhook 推送，text 消息可用 {@code mentioned_list} /
 *       {@code mentioned_mobile_list} 真正 @ 到人，markdown 消息可在正文写 {@code <@userid>}</li>
 * </ul>
 *
 * <pre>
 * agent-platform:
 *   messaging:
 *     wecom-webhook:
 *       api-url: https://qyapi.weixin.qq.com/cgi-bin/webhook/send
 *       timeout-ms: 10000
 *       endpoints:
 *         # 值可以是 webhook key，也可以是完整 URL（完整 URL 按原样使用）
 *         default: 96e90e39-47e5-4919-9cf6-f397b9c59abe
 *         git-report: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx
 * </pre>
 *
 * <p><b>群清单的权威登记表在 `.workspace/skills/workwx_agent/workwx-registry/SKILL.md`</b>
 * （群名 → chatid / chattype / botName / webhook 地址 / 用途，含按人 @ 的手机号）。
 * 这里只是"按名字解析出 URL"的运行时兜底；新增群优先改技能，改完同步到这里。
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.messaging.wecom-webhook")
public class WeComWebhookProperties {

    /** 调用方未指定 endpoint 时使用的名字。 */
    public static final String DEFAULT_ENDPOINT = "default";

    /** Webhook 发送接口地址（不含 query）。 */
    private String apiUrl = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send";

    /** 单次请求超时（毫秒）。 */
    private int timeoutMs = 10000;

    /** 目标群登记表：名字 → webhook key 或完整 URL。 */
    private Map<String, String> endpoints = new LinkedHashMap<>();

    /**
     * 解析可直接 POST 的 webhook 地址。
     *
     * @param endpoint 登记表里的名字；也允许直接传完整 URL；为空取 {@link #DEFAULT_ENDPOINT}
     * @return 完整 URL；名字不存在或未配置 key 时返回 {@code null}
     */
    public String resolveUrl(String endpoint) {
        String name = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint.trim();
        if (isHttpUrl(name)) {
            return name;
        }
        String spec = endpoints.get(name);
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String trimmed = spec.trim();
        if (isHttpUrl(trimmed)) {
            return trimmed;
        }
        return trimTrailingSlash(apiUrl) + "?key=" + trimmed;
    }

    /** 已登记的目标群名，用于报错时提示可用取值。 */
    public Set<String> endpointNames() {
        return endpoints.keySet();
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Map<String, String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, String> endpoints) {
        this.endpoints = endpoints == null ? new LinkedHashMap<>() : endpoints;
    }
}
