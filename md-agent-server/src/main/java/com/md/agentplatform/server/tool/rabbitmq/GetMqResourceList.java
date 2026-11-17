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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * RabbitMQ 资源查询工具 —— 通过管理 HTTP API 查询指定 vhost 下的队列或交换机列表（按名称过滤）。
 * url / 鉴权 / vhost 由 {@code environment} 从环境目录解析，resource / 名称 / 分页等由方法参数传入。
 */
@Component
public class GetMqResourceList extends AbstractRabbitMqTool {

    @Tool(
            name = "get-mq-resource-list",
            description = """
                    查询 RabbitMQ 指定 vhost 下的队列或交换机列表（支持按名称过滤）。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - resource: 资源类型（选填，默认 queue），queue=队列 / exchange=交换机
                    - name: 名称过滤（选填），子串匹配；useRegex=true 时按正则匹配
                    - useRegex: name 是否按正则匹配（选填，默认 false）
                    - page: 页码（选填，默认 1）
                    - pageSize: 每页条数（选填，默认 100）

                    vhost 固定取环境目录 rabbitmq.vhost，无需传参（未配置时回退 /）。

                    返回: 队列/交换机列表，含类型、持久化、消息数/消费者数（队列）或 internal（交换机）等关键信息。
                    """
    )
    public String getMqResourceList(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "resource", description = "资源类型（选填，默认 queue），可选 queue / exchange") String resource,
            @ToolParam(name = "name", description = "名称过滤（选填），子串匹配，useRegex=true 时按正则") String name,
            @ToolParam(name = "useRegex", description = "name 是否按正则匹配（选填，默认 false）") String useRegex,
            @ToolParam(name = "page", description = "页码（选填，默认 1）") String page,
            @ToolParam(name = "pageSize", description = "每页条数（选填，默认 100）") String pageSize
    ) {
        logger.info("get-mq-resource-list called, environment:{}, resource:{}, name:{}, useRegex:{}, page:{}, pageSize:{}",
                environment, resource, name, useRegex, page, pageSize);

        if (!notBlank(environment)) {
            return "[get-mq-resource-list] 参数缺失: environment 必填";
        }

        String resourceType = resolveResource(resource);
        if (resourceType == null) {
            return "[get-mq-resource-list] 不支持的资源类型: " + resource + "，可选: queue / exchange";
        }

        boolean regex = parseBool(useRegex, false);

        try {
            EnvironmentProperties.Rabbit rabbit = rabbit(environment);
            String vhost = resolveVhost(rabbit);
            String url = buildUrl(rabbit, resourceType, vhost, name, regex, page, pageSize);

            HttpHeaders headers = authHeaders(rabbit);
            headers.set("x-vhost", vhost);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                logger.error("get-mq-resource-list 失败, httpStatus:{}, body:{}", resp.getStatusCode(), resp.getBody());
                return formatError("get-mq-resource-list", resp.getStatusCode().value(), resp.getBody());
            }
            return formatResult(resp.getBody(), resourceType, vhost);

        } catch (IllegalArgumentException e) {
            return "[get-mq-resource-list] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("get-mq-resource-list 异常, environment:{}, resource:{}, name:{}, error:{}",
                    environment, resource, name, e.getMessage(), e);
            return "[get-mq-resource-list] 查询失败: " + e.getMessage();
        }
    }

    private String buildUrl(EnvironmentProperties.Rabbit rabbit, String resourceType, String vhost,
                            String name, boolean useRegex, String page, String pageSize) {
        StringBuilder url = new StringBuilder(managementUrl(rabbit, "api", resourceType, vhost))
                .append("?page=").append(defaultIfBlank(page, "1"))
                .append("&page_size=").append(defaultIfBlank(pageSize, "100"))
                .append("&pagination=true");
        if (notBlank(name)) {
            url.append("&name=").append(URLEncoder.encode(name.trim(), StandardCharsets.UTF_8))
                    .append("&use_regex=").append(useRegex);
        }
        return url.toString();
    }

    private String formatResult(String responseBody, String resourceType, String vhost) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            logger.warn("get-mq-resource-list 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }

        String displayName = "queues".equals(resourceType) ? "队列" : "交换机";
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "[get-mq-resource-list] 未查询到" + displayName + "（vhost=" + vhost + "）";
        }

        int total = root.path("total_count").asInt();
        int pageNo = root.path("page").asInt();
        int pageCount = root.path("page_count").asInt();

        StringBuilder sb = new StringBuilder();
        sb.append("[get-mq-resource-list] 返回 ").append(items.size())
                .append(" 个").append(displayName)
                .append("（vhost=").append(vhost)
                .append("，共 ").append(total).append(" 个")
                .append("，第 ").append(pageNo).append('/').append(pageCount).append(" 页）:\n");

        for (JsonNode item : items) {
            sb.append("queues".equals(resourceType) ? formatQueue(item) : formatExchange(item));
        }
        return sb.toString();
    }
}
