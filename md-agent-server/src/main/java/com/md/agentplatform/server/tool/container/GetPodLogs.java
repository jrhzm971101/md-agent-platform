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
 * Kubernetes Pod 日志查询工具 —— 调用 K8s API 查询指定 Pod（指定容器）的日志，
 * 支持 tailLines 尾部行数与 timestamps 时间戳开关。
 *
 * <p>API 基础地址 / 命名空间 / token 由 environment 从环境目录解析，
 * pod 名称 / 容器名 / tailLines / timestamps 通过 ToolParam 传入。
 */
@Component
public class GetPodLogs {

    private static final Logger log = LoggerFactory.getLogger(GetPodLogs.class);

    /** Pod 日志接口相对路径模板 */
    private static final String POD_LOG_PATH = "/api/v1/namespaces/%s/pods/%s/log";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EnvironmentRegistry environmentRegistry;

    @Tool(
            name = "get-pod-logs",
            description = """
                    查询 Kubernetes 指定命名空间下某个 Pod（或其中某个容器）的日志。

                    适用场景:
                    - 用户问"某个 Pod 的日志是什么 / 最近报了什么错"
                    - 排查服务异常、启动失败、接口报错等运行时问题

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - pod: Pod 名称（必填），如 lms-backend-65784cd69-8dtll
                    - container: 容器名（选填，多容器 Pod 必填），如 lms-backend
                    - tailLines: 返回日志尾部行数（选填，默认 300）
                    - timestamps: 是否带时间戳（选填，默认 true）

                    命名空间固定取环境目录 k8s.namespace，无需传参。

                    返回: 该 Pod 的日志文本（纯文本）。
                    """
    )
    public String getPodLogs(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "pod", description = "Pod 名称（必填），如 lms-backend-65784cd69-8dtll") String pod,
            @ToolParam(name = "container", description = "容器名（选填，多容器 Pod 必填），如 lms-backend") String container,
            @ToolParam(name = "tailLines", description = "返回日志尾部行数（选填，默认 300）") String tailLines,
            @ToolParam(name = "timestamps", description = "是否带时间戳（选填，默认 true）") String timestamps
    ) {
        log.info("get-pod-logs called, environment:{}, pod:{}, container:{}, tailLines:{}, timestamps:{}",
                environment, pod, container, tailLines, timestamps);

        if (!notBlank(environment)) {
            return "[get-pod-logs] 参数缺失: environment 必填";
        }
        if (!notBlank(pod)) {
            return "[get-pod-logs] 参数缺失: pod 必填";
        }

        try {
            EnvironmentProperties.K8s k8s = environmentRegistry.k8s(environment);
            String ns = k8s.getNamespace();
            if (!notBlank(ns)) {
                return "[get-pod-logs] 环境 " + environment + " 未配置 k8s.namespace，无法确定命名空间";
            }
            String url = k8s.getUrl();
            String token = k8s.getToken();

            String baseUrl = url.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            StringBuilder requestUrl = new StringBuilder(baseUrl)
                    .append(String.format(POD_LOG_PATH, ns.trim(), pod.trim()))
                    .append("?tailLines=").append(defaultIfBlank(tailLines, "300"))
                    .append("&timestamps=").append(defaultIfBlank(timestamps, "true"));
            if (notBlank(container)) {
                requestUrl.append("&container=").append(container.trim());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token.trim());
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(requestUrl.toString(), HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("get-pod-logs 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return formatError(response.getStatusCode().value(), response.getBody());
            }

            return formatLog(response.getBody(), pod.trim(), container);

        } catch (Exception e) {
            log.error("get-pod-logs 异常, environment:{}, pod:{}, container:{}, error:{}",
                    environment, pod, container, e.getMessage(), e);
            return "[get-pod-logs] 查询异常: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 结果格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatLog(String responseBody, String pod, String container) {
        // 正常日志为纯文本；偶发情况下 API 可能返回 JSON Status 错误对象
        if (responseBody != null && responseBody.startsWith("{")) {
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if ("Status".equals(root.path("kind").asText())) {
                    return "[get-pod-logs] API 错误 (" + root.path("code").asText() + "): "
                            + root.path("message").asText();
                }
            } catch (Exception ignore) {
                // 不是 JSON，按纯文本日志处理
            }
        }

        if (responseBody == null || responseBody.isBlank()) {
            return "[get-pod-logs] " + pod + " 暂无日志";
        }

        String label = pod + (notBlank(container) ? " (" + container.trim() + ")" : "");
        return "[get-pod-logs] " + label + " 日志:\n" + responseBody;
    }

    /** 非 2xx 响应时，尽量解析出 K8s Status 里的可读错误信息 */
    private String formatError(int httpCode, String responseBody) {
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if ("Status".equals(root.path("kind").asText())) {
                    return "[get-pod-logs] 请求失败, HTTP " + httpCode
                            + " (" + root.path("reason").asText() + "): " + root.path("message").asText();
                }
            } catch (Exception ignore) {
                // 解析失败走原始 body
            }
        }
        return "[get-pod-logs] 请求失败, HTTP " + httpCode + ", body: " + responseBody;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
