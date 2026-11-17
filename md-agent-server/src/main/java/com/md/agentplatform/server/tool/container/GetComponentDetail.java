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
 * Kubernetes 单组件详情查询工具 —— 按组件名称（fieldSelector metadata.name）查询指定计算组件的详细信息，
 * 汇总名称、镜像、副本数/状态、条件、资源规格等关键指标。
 *
 * <p>API 基础地址 / 命名空间 / token 由 environment 从环境目录解析，
 * 组件名称 / 组件类型通过 ToolParam 传入。
 * 支持的计算组件在 {@link ComputeComponent} 枚举中全量写死，组件名称通过 {@code name} 参数动态指定。
 */
@Component
public class GetComponentDetail {

    private static final Logger log = LoggerFactory.getLogger(GetComponentDetail.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EnvironmentRegistry environmentRegistry;

    @Tool(
            name = "get-component-detail",
            description = """
                    按名称查询 Kubernetes 指定命名空间下单个计算组件的详细信息。

                    适用场景:
                    - 用户问"某个服务/部署的详细配置是什么"
                    - 查看某个组件的副本数、镜像版本、就绪状态、资源规格

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - name: 组件名（必填），即资源的 metadata.name，如 digiops-backend
                    - resource: 计算组件类型（选填，默认 deployment），可选: deployment / statefulset / daemonset / replicaset / pod / job / cronjob / replicationcontroller

                    命名空间固定取环境目录 k8s.namespace，无需传参。

                    返回: 该组件的详细信息，包含名称、容器级镜像（含 initContainer）、副本数/状态、条件、资源规格等。
                    """
    )
    public String getComponentDetail(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "name", description = "组件名（必填），即资源的 metadata.name，如 digiops-backend") String name,
            @ToolParam(name = "resource", description = "计算组件类型（选填，默认 deployment），可选: deployment / statefulset / daemonset / replicaset / pod / job / cronjob / replicationcontroller") String resource
    ) {
        log.info("get-component-detail called, environment:{}, name:{}, resource:{}",
                environment, name, resource);

        if (!notBlank(environment)) {
            return "[get-component-detail] 参数缺失: environment 必填";
        }
        if (!notBlank(name)) {
            return "[get-component-detail] 参数缺失: name 必填";
        }

        ComputeComponent component = ComputeComponent.fromValue(defaultIfBlank(resource, "deployment").toLowerCase());
        if (component == null) {
            return "[get-component-detail] 不支持的计算组件类型: " + resource
                    + "，可选: " + ComputeComponent.supportedValues();
        }

        try {
            EnvironmentProperties.K8s k8s = environmentRegistry.k8s(environment);
            String ns = k8s.getNamespace();
            if (!notBlank(ns)) {
                return "[get-component-detail] 环境 " + environment + " 未配置 k8s.namespace，无法确定命名空间";
            }
            String url = k8s.getUrl();
            String token = k8s.getToken();

            String baseUrl = url.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            // fieldSelector 整段 URL 编码，如 metadata.name=digiops-backend -> metadata.name%3Ddigiops-backend
            String fieldSelector = URLEncoder.encode("metadata.name=" + name.trim(), StandardCharsets.UTF_8);
            String requestUrl = baseUrl
                    + String.format(component.pathTemplate, ns.trim())
                    + "?fieldSelector=" + fieldSelector;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token.trim());
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(URI.create(requestUrl), HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("get-component-detail 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return formatError(response.getStatusCode().value(), response.getBody());
            }

            return formatResult(response.getBody(), name.trim(), component);

        } catch (Exception e) {
            log.error("get-component-detail 异常, environment:{}, name:{}, resource:{}, error:{}",
                    environment, name, component.value, e.getMessage(), e);
            return "[get-component-detail] 查询异常: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 结果格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatResult(String responseBody, String name, ComputeComponent component) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // K8s API 出错时返回 Status 对象
            if ("Status".equals(root.path("kind").asText())) {
                return "[get-component-detail] API 错误 (" + root.path("code").asText() + "): "
                        + root.path("message").asText();
            }

            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) {
                return "[get-component-detail] 未找到组件 " + name + "（" + component.displayName + "）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[get-component-detail] 组件 ").append(name)
                    .append("（").append(component.displayName).append("）详情:\n");

            for (JsonNode item : items) {
                sb.append(ComputeComponentFormatter.formatItem(item, component));
            }
            return sb.toString();

        } catch (Exception e) {
            log.warn("get-component-detail 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }
    }

    /** 非 2xx 响应时，尽量解析出 K8s Status 里的可读错误信息 */
    private String formatError(int httpCode, String responseBody) {
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if ("Status".equals(root.path("kind").asText())) {
                    return "[get-component-detail] 请求失败, HTTP " + httpCode
                            + " (" + root.path("reason").asText() + "): " + root.path("message").asText();
                }
            } catch (Exception ignore) {
                // 解析失败走原始 body
            }
        }
        return "[get-component-detail] 请求失败, HTTP " + httpCode + ", body: " + responseBody;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
