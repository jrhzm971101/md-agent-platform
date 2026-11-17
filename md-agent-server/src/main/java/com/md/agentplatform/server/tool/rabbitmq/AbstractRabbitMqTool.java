package com.md.agentplatform.server.tool.rabbitmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RabbitMQ 工具公共基类 —— 通过管理 HTTP API（REST）访问，封装环境解析、
 * URL 拼接（vhost / 名称做 path segment 编码，如 / → %2F）、Basic 鉴权头与错误解析。
 *
 * <p>与 nacos / xxljob 工具不同：这里不注入 Spring Bean 连接信息，url / authorization
 * 由 {@code environment} 从环境目录解析，vhost 同样来自环境目录，
 * 其余参数（名称 / 类型等）由方法参数传入。
 */
public abstract class AbstractRabbitMqTool {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final RestTemplate restTemplate = new RestTemplate();
    protected final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    protected EnvironmentRegistry environmentRegistry;

    protected EnvironmentProperties.Rabbit rabbit(String environment) {
        return environmentRegistry.rabbit(environment);
    }

    /** vhost 解析：固定取环境目录配置的 vhost，未配置时回退 /。 */
    protected String resolveVhost(EnvironmentProperties.Rabbit rabbit) {
        return defaultIfBlank(rabbit.getVhost(), "/");
    }

    /** 拼接管理 API URL：baseUrl + 各段（逐段做 path segment 编码，如 vhost 的 / → %2F） */
    protected String managementUrl(EnvironmentProperties.Rabbit rabbit, String... segments) {
        String base = defaultIfBlank(rabbit.getUrl(), "").trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringBuilder sb = new StringBuilder(base);
        for (String seg : segments) {
            sb.append('/').append(UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 构造仅带 Basic 鉴权的请求头（GET 等无请求体场景） */
    protected HttpHeaders authHeaders(EnvironmentProperties.Rabbit rabbit) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, defaultIfBlank(rabbit.getAuthorization(), "").trim());
        return headers;
    }

    /** 构造 JSON + Basic 鉴权请求头 */
    protected HttpHeaders jsonHeaders(EnvironmentProperties.Rabbit rabbit) {
        HttpHeaders headers = authHeaders(rabbit);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** 执行请求并返回响应体（body 为 null 时表示无请求体） */
    protected ResponseEntity<String> execute(EnvironmentProperties.Rabbit rabbit, String url,
                                             HttpMethod method, Map<String, Object> body) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, jsonHeaders(rabbit));
        return restTemplate.exchange(URI.create(url), method, entity, String.class);
    }

    /** 解析 RabbitMQ 管理 API 错误响应（error / reason 字段） */
    protected String formatError(String toolName, int httpCode, String responseBody) {
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                String error = root.path("error").asText("");
                String reason = root.path("reason").asText("");
                if (!error.isBlank() || !reason.isBlank()) {
                    StringBuilder sb = new StringBuilder("[")
                            .append(toolName).append("] 请求失败, HTTP ").append(httpCode);
                    if (!error.isBlank()) {
                        sb.append(" (").append(error).append(")");
                    }
                    if (!reason.isBlank()) {
                        sb.append(": ").append(reason);
                    }
                    return sb.toString();
                }
            } catch (Exception ignore) {
                // 解析失败走原始 body
            }
        }
        return "[" + toolName + "] 请求失败, HTTP " + httpCode + ", body: " + responseBody;
    }

    /** 解析资源类型为 API 路径段；非法值返回 null */
    protected String resolveResource(String resource) {
        String r = defaultIfBlank(resource, "queue").toLowerCase();
        return switch (r) {
            case "queue", "queues" -> "queues";
            case "exchange", "exchanges" -> "exchanges";
            default -> null;
        };
    }

    /** 队列详情/列表项格式化 */
    protected String formatQueue(JsonNode q) {
        String name = q.path("name").asText("");
        String type = q.path("type").asText("");
        boolean durable = q.path("durable").asBoolean(false);
        boolean autoDelete = q.path("auto_delete").asBoolean(false);
        boolean exclusive = q.path("exclusive").asBoolean(false);
        String state = q.path("state").asText("");
        int messages = q.path("messages").asInt();
        int ready = q.path("messages_ready").asInt();
        int unacked = q.path("messages_unacknowledged").asInt();
        int consumers = q.path("consumers").asInt();
        String node = q.path("node").asText("");

        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append("\n");
        sb.append("  type=").append(type)
                .append(" durable=").append(durable)
                .append(" auto_delete=").append(autoDelete)
                .append(" exclusive=").append(exclusive)
                .append(" state=").append(state).append("\n");
        sb.append("  messages=").append(messages)
                .append(" (ready=").append(ready)
                .append(", unacked=").append(unacked)
                .append(") consumers=").append(consumers).append("\n");

        JsonNode args = q.path("arguments");
        if (args.isObject()) {
            String dlx = args.path("x-dead-letter-exchange").asText("");
            String dlk = args.path("x-dead-letter-routing-key").asText("");
            if (!dlx.isBlank() || !dlk.isBlank()) {
                sb.append("  deadLetter: exchange=").append(dlx.isBlank() ? "(无)" : dlx)
                        .append(", routingKey=").append(dlk.isBlank() ? "(无)" : dlk).append("\n");
            }
        }
        if (!node.isBlank()) {
            sb.append("  node=").append(node).append("\n");
        }
        return sb.toString();
    }

    /** 交换机详情/列表项格式化 */
    protected String formatExchange(JsonNode e) {
        String name = e.path("name").asText("");
        String type = e.path("type").asText("");
        boolean durable = e.path("durable").asBoolean(false);
        boolean autoDelete = e.path("auto_delete").asBoolean(false);
        boolean internal = e.path("internal").asBoolean(false);

        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append("\n");
        sb.append("  type=").append(type)
                .append(" durable=").append(durable)
                .append(" auto_delete=").append(autoDelete)
                .append(" internal=").append(internal).append("\n");

        JsonNode args = e.path("arguments");
        if (args.isObject() && args.size() > 0) {
            sb.append("  arguments=").append(args).append("\n");
        }
        return sb.toString();
    }

    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    protected static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    protected static boolean parseBool(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String v = value.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v)) {
            return true;
        }
        if ("false".equals(v) || "0".equals(v)) {
            return false;
        }
        return defaultValue;
    }
}
