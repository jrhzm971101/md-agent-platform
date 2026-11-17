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

/**
 * Kubernetes 计算组件列表查询工具 —— 调用 K8s API 查询指定命名空间下的各类计算组件
 * （Deployment / StatefulSet / DaemonSet / ReplicaSet / Pod / Job / CronJob 等），
 * 并汇总名称、镜像、副本/状态等关键指标。
 *
 * <p>API 基础地址 / 命名空间 / token 由 environment 从环境目录解析，
 * limit / 组件类型通过 ToolParam 传入。
 * 支持的计算组件在 {@link ComputeComponent} 枚举中全量写死，组件名称通过 {@code resource} 参数动态指定。
 */
@Component
public class GetComputeComponentList {

    private static final Logger log = LoggerFactory.getLogger(GetComputeComponentList.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EnvironmentRegistry environmentRegistry;

    @Tool(
            name = "get-compute-component-list",
            description = """
                    查询 Kubernetes 指定命名空间下的计算组件列表，并汇总关键指标与状态。

                    支持的计算组件类型（resource 参数）:
                    - deployment: Deployment 部署
                    - statefulset: StatefulSet 有状态应用
                    - daemonset: DaemonSet 守护进程集
                    - replicaset: ReplicaSet 副本集
                    - pod: Pod 容器组
                    - job: Job 一次性任务
                    - cronjob: CronJob 定时任务
                    - replicationcontroller: ReplicationController 副本控制器

                    适用场景:
                    - 用户问"某个命名空间下有哪些服务/工作负载"
                    - 排查计算组件的副本数、就绪状态、镜像版本
                    - 查看某个服务是否正常（Available / Progressing / Running 状态）

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - resource: 计算组件名称（选填，默认 deployment），可选值见上
                    - limit: 返回条数上限（选填，默认 20）

                    命名空间固定取环境目录 k8s.namespace，无需传参。

                    返回: 对应类型的计算组件列表，包含名称、容器级镜像（含 initContainer）、副本数/状态等关键信息。
                    """
    )
    public String listComputeComponents(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "resource", description = "计算组件名称（选填，默认 deployment），可选: deployment / statefulset / daemonset / replicaset / pod / job / cronjob / replicationcontroller") String resource,
            @ToolParam(name = "limit", description = "返回条数上限（选填，默认 20）") String limit
    ) {
        log.info("get-compute-component-list called, environment:{}, resource:{}, limit:{}",
                environment, resource, limit);

        if (!notBlank(environment)) {
            return "[get-compute-component-list] 参数缺失: environment 必填";
        }

        ComputeComponent component = ComputeComponent.fromValue(defaultIfBlank(resource, "deployment").toLowerCase());
        if (component == null) {
            return "[get-compute-component-list] 不支持的计算组件名称: " + resource
                    + "，可选: " + ComputeComponent.supportedValues();
        }

        try {
            EnvironmentProperties.K8s k8s = environmentRegistry.k8s(environment);
            String ns = k8s.getNamespace();
            if (!notBlank(ns)) {
                return "[get-compute-component-list] 环境 " + environment + " 未配置 k8s.namespace，无法确定命名空间";
            }
            String url = k8s.getUrl();
            String token = k8s.getToken();

            String baseUrl = url.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String requestUrl = baseUrl
                    + String.format(component.pathTemplate, ns.trim())
                    + "?limit=" + defaultIfBlank(limit, "20");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token.trim());
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(requestUrl, HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("get-compute-component-list 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return formatError(response.getStatusCode().value(), response.getBody());
            }

            return formatResult(response.getBody(), component);

        } catch (Exception e) {
            log.error("get-compute-component-list 异常, environment:{}, resource:{}, error:{}",
                    environment, component.value, e.getMessage(), e);
            return "[get-compute-component-list] 查询异常: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 结果格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatResult(String responseBody, ComputeComponent component) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // K8s API 出错时返回 Status 对象
            if ("Status".equals(root.path("kind").asText())) {
                return "[get-compute-component-list] API 错误 (" + root.path("code").asText() + "): "
                        + root.path("message").asText();
            }

            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) {
                return "[get-compute-component-list] 命名空间下无 " + component.displayName;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[get-compute-component-list] 共 ").append(items.size())
                    .append(" 个 ").append(component.displayName).append(":\n");

            for (JsonNode item : items) {
                sb.append(ComputeComponentFormatter.formatItem(item, component));
            }
            return sb.toString();

        } catch (Exception e) {
            log.warn("get-compute-component-list 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }
    }

    /** 非 2xx 响应时，尽量解析出 K8s Status 里的可读错误信息 */
    private String formatError(int httpCode, String responseBody) {
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if ("Status".equals(root.path("kind").asText())) {
                    return "[get-compute-component-list] 请求失败, HTTP " + httpCode
                            + " (" + root.path("reason").asText() + "): " + root.path("message").asText();
                }
            } catch (Exception ignore) {
                // 解析失败走原始 body
            }
        }
        return "[get-compute-component-list] 请求失败, HTTP " + httpCode + ", body: " + responseBody;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
