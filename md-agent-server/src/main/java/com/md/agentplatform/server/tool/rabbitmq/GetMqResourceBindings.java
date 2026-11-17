package com.md.agentplatform.server.tool.rabbitmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.md.agentplatform.server.config.EnvironmentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * RabbitMQ 绑定查询工具 —— 通过管理 HTTP API 查询指定 vhost 下单个队列或交换机的绑定关系。
 * url / 鉴权 / vhost 由 {@code environment} 从环境目录解析，resource / 名称由方法参数传入。
 */
@Component
public class GetMqResourceBindings extends AbstractRabbitMqTool {

    @Tool(
            name = "get-mq-resource-bindings",
            description = """
                    查询 RabbitMQ 指定 vhost 下单个队列或交换机的绑定关系（按名称精确匹配）。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - resource: 资源类型（选填，默认 queue），queue=查队列的绑定 / exchange=查交换机的绑定
                    - name: 队列/交换机名称（必填），精确匹配

                    vhost 固定取环境目录 rabbitmq.vhost，无需传参（未配置时回退 /）。

                    返回: 绑定列表，含 source（来源交换机）/ destination（目标）/ routingKey 等。
                    """
    )
    public String getMqResourceBindings(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "resource", description = "资源类型（选填，默认 queue），可选 queue / exchange") String resource,
            @ToolParam(name = "name", description = "队列/交换机名称（必填），精确匹配") String name
    ) {
        logger.info("get-mq-resource-bindings called, environment:{}, resource:{}, name:{}",
                environment, resource, name);

        if (!notBlank(environment)) {
            return "[get-mq-resource-bindings] 参数缺失: environment 必填";
        }
        if (!notBlank(name)) {
            return "[get-mq-resource-bindings] 参数缺失: name 必填";
        }

        String resourceType = resolveResource(resource);
        if (resourceType == null) {
            return "[get-mq-resource-bindings] 不支持的资源类型: " + resource + "，可选: queue / exchange";
        }


        try {
            EnvironmentProperties.Rabbit rabbit = rabbit(environment);
            String vhost = resolveVhost(rabbit);
            String url = managementUrl(rabbit, "api", resourceType, vhost, name.trim(), "bindings");

            HttpHeaders headers = authHeaders(rabbit);
            headers.set("x-vhost", vhost);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                logger.error("get-mq-resource-bindings 失败, httpStatus:{}, body:{}", resp.getStatusCode(), resp.getBody());
                return formatError("get-mq-resource-bindings", resp.getStatusCode().value(), resp.getBody());
            }
            return formatResult(resp.getBody(), resourceType, name.trim());

        } catch (IllegalArgumentException e) {
            return "[get-mq-resource-bindings] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("get-mq-resource-bindings 异常, environment:{}, resource:{}, name:{}, error:{}",
                    environment, resource, name, e.getMessage(), e);
            return "[get-mq-resource-bindings] 查询失败: " + e.getMessage();
        }
    }

    private String formatResult(String responseBody, String resourceType, String name) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            logger.warn("get-mq-resource-bindings 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }

        String displayName = "queues".equals(resourceType) ? "队列" : "交换机";
        if (!root.isArray()) {
            return "[get-mq-resource-bindings] 响应异常: " + responseBody;
        }
        if (root.isEmpty()) {
            return "[get-mq-resource-bindings] " + displayName + " '" + name + "' 无绑定";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[get-mq-resource-bindings] ").append(displayName)
                .append(" '").append(name).append("' 的绑定（共 ").append(root.size()).append(" 个）:\n");

        for (JsonNode b : root) {
            sb.append(formatBinding(b));
        }
        return sb.toString();
    }

    private String formatBinding(JsonNode b) {
        String source = b.path("source").asText("");
        String destination = b.path("destination").asText("");
        String destinationType = b.path("destination_type").asText("");
        String routingKey = b.path("routing_key").asText("");

        StringBuilder sb = new StringBuilder();
        sb.append("- source=").append(source.isEmpty() ? "(默认交换机)" : source)
                .append("  destination=").append(destination)
                .append("  destinationType=").append(destinationType)
                .append("  routingKey=").append(routingKey.isEmpty() ? "(空)" : routingKey);

        JsonNode args = b.path("arguments");
        if (args.isObject() && args.size() > 0) {
            sb.append("  arguments=").append(args);
        }
        sb.append("\n");
        return sb.toString();
    }
}
