package com.md.agentplatform.server.tool.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Kubernetes Pod 查询工具 —— 根据组件名（labelSelector app）查询其 Pod 列表，
 * 汇总 Pod 名称、IP、状态、节点、容器就绪/重启等关键信息。
 *
 * <p>API 基础地址 / 命名空间 / token 由 environment 从环境目录解析，
 * limit / 组件名通过 ToolParam 传入。
 */
@Component
public class GetComponentPods {

    private static final Logger log = LoggerFactory.getLogger(GetComponentPods.class);

    /** Pod 列表接口相对路径模板 */
    private static final String PODS_PATH = "/api/v1/namespaces/%s/pods";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EnvironmentRegistry environmentRegistry;

    @Tool(
            name = "get-component-pods",
            description = """
                    根据组件名（labelSelector app={component}）查询 Kubernetes 指定命名空间下该组件对应的 Pod 列表。

                    适用场景:
                    - 用户问"某个服务的 Pod 有哪些 / 某个组件的 Pod IP 是什么"
                    - 排查某组件的 Pod 是否 Running、分布在哪些节点、是否频繁重启

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - component: 组件名（必填），即 labelSelector 中 app 的值，如 lms-backend
                    - limit: 返回条数上限（选填，默认 20）

                    命名空间固定取环境目录 k8s.namespace，无需传参。

                    返回: 该组件对应的 Pod 列表，包含名称、IP、状态、节点、容器就绪/重启、启动时间等。
                    """
    )
    public String listPodsByComponent(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "component", description = "组件名（必填），即 labelSelector 中 app 的值，如 lms-backend") String component,
            @ToolParam(name = "limit", description = "返回条数上限（选填，默认 20）") String limit
    ) {
        log.info("get-component-pods called, environment:{}, component:{}, limit:{}",
                environment, component, limit);

        if (!notBlank(environment)) {
            return "[get-component-pods] 参数缺失: environment 必填";
        }
        if (!notBlank(component)) {
            return "[get-component-pods] 参数缺失: component 必填";
        }

        try {
            EnvironmentProperties.K8s k8s = environmentRegistry.k8s(environment);
            String ns = k8s.getNamespace();
            if (!notBlank(ns)) {
                return "[get-component-pods] 环境 " + environment + " 未配置 k8s.namespace，无法确定命名空间";
            }
            String url = k8s.getUrl();
            String token = k8s.getToken();

            String baseUrl = url.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            // labelSelector 整段 URL 编码，如 app=lms-backend -> app%3Dlms-backend
            String labelSelector = URLEncoder.encode("app=" + component.trim(), StandardCharsets.UTF_8);
            String requestUrl = baseUrl
                    + String.format(PODS_PATH, ns.trim())
                    + "?limit=" + defaultIfBlank(limit, "20")
                    + "&labelSelector=" + labelSelector
                    + "&field=status.podIP,metadata.name";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token.trim());
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(URI.create(requestUrl), HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("get-component-pods 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return formatError(response.getStatusCode().value(), response.getBody());
            }

            return formatResult(response.getBody(), component.trim());

        } catch (Exception e) {
            log.error("get-component-pods 异常, environment:{}, component:{}, error:{}",
                    environment, component, e.getMessage(), e);
            return "[get-component-pods] 查询异常: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 结果格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatResult(String responseBody, String component) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // K8s API 出错时返回 Status 对象
            if ("Status".equals(root.path("kind").asText())) {
                return "[get-component-pods] API 错误 (" + root.path("code").asText() + "): "
                        + root.path("message").asText();
            }

            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) {
                return "[get-component-pods] 组件 " + component + " 无 Pod";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[get-component-pods] 组件 ").append(component)
                    .append(" 共 ").append(items.size()).append(" 个 Pod:\n");

            for (JsonNode item : items) {
                appendPod(sb, item);
            }
            return sb.toString();

        } catch (Exception e) {
            log.warn("get-component-pods 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }
    }

    private void appendPod(StringBuilder sb, JsonNode pod) {
        JsonNode meta = pod.path("metadata");
        JsonNode spec = pod.path("spec");
        JsonNode status = pod.path("status");

        String name = meta.path("name").asText("");
        String podIP = status.path("podIP").asText("");
        String phase = status.path("phase").asText("");
        String nodeName = spec.path("nodeName").asText("");
        String startTime = status.path("startTime").asText("");

        sb.append("\n---\n");
        sb.append("名称: ").append(name).append("\n");
        sb.append("IP: ").append(blankAs(podIP, "-")).append("\n");
        sb.append("状态: ").append(blankAs(phase, "Unknown")).append("\n");
        if (!nodeName.isBlank()) {
            sb.append("节点: ").append(nodeName).append("\n");
        }

        // 容器就绪/重启情况
        JsonNode containerStatuses = status.path("containerStatuses");
        if (containerStatuses.isArray() && !containerStatuses.isEmpty()) {
            int ready = 0;
            int restarts = 0;
            for (JsonNode cs : containerStatuses) {
                if (cs.path("ready").asBoolean(false)) {
                    ready++;
                }
                restarts += cs.path("restartCount").asInt(0);
            }
            sb.append("容器: 就绪 ").append(ready).append(" / ").append(containerStatuses.size())
                    .append("，重启 ").append(restarts).append("\n");
        }

        if (!startTime.isBlank()) {
            sb.append("启动时间: ").append(startTime).append("\n");
        }
    }

    /** 非 2xx 响应时，尽量解析出 K8s Status 里的可读错误信息 */
    private String formatError(int httpCode, String responseBody) {
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if ("Status".equals(root.path("kind").asText())) {
                    return "[get-component-pods] 请求失败, HTTP " + httpCode
                            + " (" + root.path("reason").asText() + "): " + root.path("message").asText();
                }
            } catch (Exception ignore) {
                // 解析失败走原始 body
            }
        }
        return "[get-component-pods] 请求失败, HTTP " + httpCode + ", body: " + responseBody;
    }

    private static String blankAs(String value, String placeholder) {
        return (value == null || value.isBlank()) ? placeholder : value;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
