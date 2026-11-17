package com.md.agentplatform.server.tool.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.GrafanaProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Grafana HTTP API 客户端。
 *
 * <p>统一处理 base URL、Basic Auth、超时和 JSON 解析；所有工具共用这一个 Bean。
 * 只读用途：不调用任何写接口。
 */
@Component
public class GrafanaClient {

    private static final Logger log = LoggerFactory.getLogger(GrafanaClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GrafanaProperties properties;
    private final EnvironmentRegistry environmentRegistry;
    private RestTemplate restTemplate;

    public GrafanaClient(GrafanaProperties properties, EnvironmentRegistry environmentRegistry) {
        this.properties = properties;
        this.environmentRegistry = environmentRegistry;
    }

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    public JsonNode get(String environment, String path) {
        return get(environment, path, Map.of());
    }

    public JsonNode get(String environment, String path, Map<String, String> queryParams) {
        return exchange(environment, HttpMethod.GET, path, queryParams, null);
    }

    public JsonNode post(String environment, String path, ObjectNode body) {
        return exchange(environment, HttpMethod.POST, path, Map.of(), body);
    }

    /** Prometheus 数据源代理地址：/api/datasources/proxy/uid/{uid}{promPath}。 */
    public String prometheusPath(String datasourceUid, String promPath) {
        String uid = isBlank(datasourceUid) ? properties.getDefaultDatasourceUid() : datasourceUid.trim();
        return "/api/datasources/proxy/uid/" + uid + (promPath.startsWith("/") ? promPath : "/" + promPath);
    }

    public String defaultDatasourceUid() {
        return properties.getDefaultDatasourceUid();
    }

    private JsonNode exchange(String environment, HttpMethod method, String path,
                              Map<String, String> queryParams, ObjectNode body) {
        EnvironmentProperties.Grafana grafana = environmentRegistry.grafana(environment);
        String baseUrl = normalizeBaseUrl(grafana.getUrl(), environment);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + normalizePath(path));
        if (queryParams != null) {
            queryParams.forEach((key, value) -> {
                if (!isBlank(key) && value != null) {
                    builder.queryParam(key, value);
                }
            });
        }
        URI uri = builder.build().encode().toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (!isBlank(grafana.getUsername())) {
            headers.setBasicAuth(grafana.getUsername(),
                    grafana.getPassword() == null ? "" : grafana.getPassword());
        }

        HttpEntity<String> entity;
        if (body == null) {
            entity = new HttpEntity<>(headers);
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
            try {
                entity = new HttpEntity<>(MAPPER.writeValueAsString(body), headers);
            } catch (Exception e) {
                throw new IllegalArgumentException("Grafana 请求体序列化失败: " + e.getMessage(), e);
            }
        }

        log.debug("grafana {} {}", method, uri);
        ResponseEntity<String> response = restTemplate.exchange(uri, method, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("HTTP " + response.getStatusCode().value()
                    + ": " + abbreviate(response.getBody(), 500));
        }
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Grafana 响应不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static String normalizeBaseUrl(String value, String environment) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("环境 " + environment + " 未配置 grafana.url");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
