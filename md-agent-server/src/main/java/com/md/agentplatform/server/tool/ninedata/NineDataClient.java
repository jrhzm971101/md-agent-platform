package com.md.agentplatform.server.tool.ninedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.NineDataProperties;
import com.md.agentplatform.server.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * NineData OpenAPI 的底层 HTTP 客户端。
 *
 * <p>只做三件事：按 NineData 规则签名、发请求、把响应解析成 JSON 树。
 * 参数默认值、只读 SQL 校验、给模型的错误文案都放在 {@link NineDataTool}，
 * 这样这一层可以在单测里被本地 HTTP Server 完整验证。
 *
 * <p>请求头固定三个（见 references/openapi-auth.md）:
 * <ul>
 *   <li>{@code access-key-id}：AccessKeyId</li>
 *   <li>{@code timestamp}：UTC，{@code yyyy-MM-dd'T'HH:mm:ss'Z'}</li>
 *   <li>{@code signature}：SHA-256(path + "/" + AccessKeySecret + "&" + timestamp)</li>
 * </ul>
 * <b>不要在日志里输出 header 或配置对象</b>，否则 AK/SK 会顺着日志落盘。
 */
@Component
public class NineDataClient {

    static final String DATASOURCE_LIST_PATH = "/openapi/v1/datasource/list";
    static final String SQL_EXECUTE_PATH = "/openapi/v1/sql/execute";

    /** 可安全重试的 HTTP 状态：限流、超时与网关/服务端临时故障。 */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 425, 429, 500, 502, 503, 504);

    /** 单次退避上限，避免配置过大时把请求拖死。 */
    private static final long MAX_BACKOFF_MS = 30_000;

    private static final Logger logger = LoggerFactory.getLogger(NineDataClient.class);

    private final NineDataProperties properties;
    private final RestTemplate restTemplate;

    public NineDataClient(NineDataProperties properties) {
        this.properties = properties;
        int timeoutMs = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 120000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 使用指定环境的目标地址与凭据查询数据源。 */
    public JsonNode listDatasources(NineDataTarget target, String datasourceType, String keyword,
                                    Integer current, Integer pageSize) {
        Map<String, Object> query = new LinkedHashMap<>();
        putIfNotBlank(query, "datasourceType", datasourceType);
        putIfNotBlank(query, "keyword", keyword);
        if (current != null && current > 0) {
            query.put("current", current);
        }
        if (pageSize != null && pageSize > 0) {
            query.put("pageSize", pageSize);
        }
        return get(target, DATASOURCE_LIST_PATH, query);
    }

    /** 使用指定环境的目标地址与凭据执行只读 SQL。 */
    public JsonNode executeSql(NineDataTarget target, ObjectNode body) {
        return post(target, SQL_EXECUTE_PATH, body);
    }

    JsonNode get(NineDataTarget target, String path, Map<String, Object> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl(target)).path(path);
        if (query != null) {
            query.forEach((name, value) -> builder.queryParam(name, value));
        }
        URI uri = builder.build().encode(StandardCharsets.UTF_8).toUri();

        return withRetry(path, () -> {
            String timestamp = NineDataSigner.timestamp(Instant.now());
            HttpHeaders headers = signedHeaders(target, path, timestamp);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parse(path, response.getBody());
        });
    }

    JsonNode post(NineDataTarget target, String path, ObjectNode body) {
        String json = JsonUtil.stringify(body == null ? JsonUtil.newObject() : body);
        return withRetry(path, () -> {
            String timestamp = NineDataSigner.timestamp(Instant.now());
            HttpHeaders headers = signedHeaders(target, path, timestamp);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(baseUrl(target) + path), HttpMethod.POST,
                    new HttpEntity<>(json, headers), String.class);
            return parse(path, response.getBody());
        });
    }

    /**
     * 对网络异常与可重试状态码做指数退避重试。
     *
     * <p>每次尝试都会重新生成 timestamp 与签名，避免长退避后签名时间戳过期；
     * 4xx（除 408/425/429）属于请求本身的问题，重试只会放大错误，直接抛出。
     * 重试次数与退避基数来自 {@code ninedata.max-retries} / {@code ninedata.retry-backoff-ms}。
     */
    private JsonNode withRetry(String path, Supplier<JsonNode> request) {
        int maxRetries = Math.max(0, properties.getMaxRetries());
        long backoffMs = properties.getRetryBackoffMs() > 0 ? properties.getRetryBackoffMs() : 500;

        for (int attempt = 0; ; attempt++) {
            try {
                return request.get();
            } catch (ResourceAccessException e) {
                if (attempt >= maxRetries) {
                    throw networkError(path, e);
                }
                backoff(path, attempt, backoffMs, "网络异常: " + e.getMessage());
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (!RETRYABLE_STATUS.contains(status) || attempt >= maxRetries) {
                    throw httpError(path, e);
                }
                backoff(path, attempt, backoffMs, "HTTP " + status);
            }
        }
    }

    private void backoff(String path, int attempt, long baseBackoffMs, String reason) {
        long delay = Math.min(baseBackoffMs * (1L << Math.min(attempt, 10)), MAX_BACKOFF_MS);
        logger.warn("NineData {} 请求失败（{}），{}ms 后重试（第 {} 次）", path, reason, delay, attempt + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NineDataException("NineData OpenAPI 重试被中断: " + path, null, null, null, e);
        }
    }

    private NineDataException networkError(String path, ResourceAccessException e) {
        logger.error("NineData {} 网络异常: {}", path, e.getMessage());
        return new NineDataException("NineData OpenAPI 网络请求失败: " + e.getMessage(), null, null, null, e);
    }

    private HttpHeaders signedHeaders(NineDataTarget target, String path, String timestamp) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("access-key-id", target.accessKeyId());
        headers.set("timestamp", timestamp);
        headers.set("signature", NineDataSigner.sign(path, target.accessKeySecret(), timestamp));
        return headers;
    }

    private JsonNode parse(String path, String body) {
        if (body == null || body.isBlank()) {
            throw new NineDataException("NineData OpenAPI 返回空响应: " + path, 200, null, body, null);
        }
        try {
            return JsonUtil.parse(body);
        } catch (IllegalArgumentException e) {
            throw new NineDataException("NineData OpenAPI 返回非 JSON 响应: " + path, 200, null, body, e);
        }
    }

    /**
     * 把 RestTemplate 的 HTTP 异常转成带平台信息的业务异常。
     * NineData 的错误体通常形如 {@code {"success":false,"requestId":"...","message":"..."}}，
     * 能取到就取，取不到就回退到框架给的 message。
     */
    private NineDataException httpError(String path, HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        String requestId = null;
        String message = e.getMessage();
        try {
            JsonNode node = JsonUtil.parse(body);
            String platformRequestId = text(node, "requestId");
            if (platformRequestId != null && !platformRequestId.isBlank()) {
                requestId = platformRequestId;
            }
            String platformMessage = text(node, "message");
            if (platformMessage != null && !platformMessage.isBlank()) {
                message = platformMessage;
            }
        } catch (Exception ignored) {
            // 响应体不是 JSON（网关 502 页面等）时，保留框架 message 即可
        }
        logger.error("NineData {} 请求失败, HTTP {}, requestId: {}, message: {}",
                path, e.getStatusCode().value(), requestId, message);
        return new NineDataException(
                "NineData OpenAPI 请求失败: HTTP " + e.getStatusCode().value() + ", " + message,
                e.getStatusCode().value(), requestId, body, e);
    }

    private String baseUrl(NineDataTarget target) {
        String endpoint = target.endpoint();
        String trimmed = endpoint == null ? "" : endpoint.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void putIfNotBlank(Map<String, Object> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, value.trim());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
