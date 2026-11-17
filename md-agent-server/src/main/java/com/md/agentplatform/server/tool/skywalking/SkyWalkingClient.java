package com.md.agentplatform.server.tool.skywalking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.SkyWalkingProperties;
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

/**
 * SkyWalking GraphQL 客户端。
 *
 * <p>地址与 Basic Auth 按 {@code environment} 从环境目录读取；traceId、时间窗、serviceId
 * 等业务参数由各工具自己组装。所有 SkyWalking 工具共用本 Bean。
 */
@Component
public class SkyWalkingClient {

    private static final Logger log = LoggerFactory.getLogger(SkyWalkingClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EnvironmentRegistry environmentRegistry;
    private final RestTemplate restTemplate;

    public SkyWalkingClient(SkyWalkingProperties properties, EnvironmentRegistry environmentRegistry) {
        this.environmentRegistry = environmentRegistry;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    /** 向指定环境的 SkyWalking GraphQL 端点发送请求，返回原始响应文本。 */
    public String post(String environment, ObjectNode payload) throws Exception {
        EnvironmentProperties.SkyWalking skywalking = environmentRegistry.skywalking(environment);
        String url = skywalking.getUrl() == null ? "" : skywalking.getUrl().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("环境 " + environment + " 未配置 skywalking.url");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("priority", "u=1, i");
        if (skywalking.getUsername() != null && !skywalking.getUsername().isBlank()) {
            headers.setBasicAuth(skywalking.getUsername(),
                    skywalking.getPassword() == null ? "" : skywalking.getPassword());
        }
        HttpEntity<String> request = new HttpEntity<>(MAPPER.writeValueAsString(payload), headers);

        log.debug("skywalking POST {}", url);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("HTTP " + response.getStatusCode().value()
                    + ": " + abbreviate(response.getBody(), 500));
        }
        return response.getBody();
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }
}
