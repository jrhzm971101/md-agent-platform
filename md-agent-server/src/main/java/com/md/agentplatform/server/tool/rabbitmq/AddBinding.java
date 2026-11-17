package com.md.agentplatform.server.tool.rabbitmq;

import com.md.agentplatform.server.config.EnvironmentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ 绑定声明工具 —— 通过管理 HTTP API 在目标实例上声明队列到交换机的绑定关系。
 * url / 鉴权 / vhost 由 {@code environment} 从环境目录解析，队列名 / 交换机名 / 路由键由方法参数传入。
 */
@Component
public class AddBinding extends AbstractRabbitMqTool {

    @Tool(
            name = "add-binding",
            description = """
                    在 RabbitMQ 上声明一个绑定，把队列绑定到交换机。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - queueName: 队列名称（必填），即要绑定的目标队列
                    - exchangeName: 交换机名称（必填），即绑定的源交换机
                    - routingKey: 路由键（选填，默认空）。direct/topic 交换机用路由键做匹配；
                      fanout 交换机忽略路由键，保持为空即可

                    vhost 固定取环境目录 rabbitmq.vhost，无需传参（未配置时回退 /）。

                    返回: 绑定成功或失败的结果信息。
                    """
    )
    public String addBinding(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "queueName", description = "队列名称（必填），即要绑定的目标队列") String queueName,
            @ToolParam(name = "exchangeName", description = "交换机名称（必填），即绑定的源交换机") String exchangeName,
            @ToolParam(name = "routingKey", description = "路由键（选填，默认空），direct/topic 用于匹配，fanout 忽略") String routingKey
    ) {
        logger.info("add-binding called, environment:{}, queueName:{}, exchangeName:{}, routingKey:{}",
                environment, queueName, exchangeName, routingKey);

        if (!notBlank(environment)) {
            return "[add-binding] 参数缺失: environment 必填";
        }
        if (!notBlank(queueName)) {
            return "[add-binding] 参数缺失: queueName 必填";
        }
        if (!notBlank(exchangeName)) {
            return "[add-binding] 参数缺失: exchangeName 必填";
        }

        String routingKeyValue = defaultIfBlank(routingKey, "");

        try {
            EnvironmentProperties.Rabbit rabbit = rabbit(environment);
            String vhost = resolveVhost(rabbit);
            String url = managementUrl(rabbit, "api", "bindings", vhost, "e", exchangeName.trim(), "q", queueName.trim());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vhost", vhost);
            body.put("source", exchangeName.trim());
            body.put("destination", queueName.trim());
            body.put("destination_type", "q");
            body.put("routing_key", routingKeyValue);
            body.put("arguments", new LinkedHashMap<String, Object>());

            ResponseEntity<String> resp = execute(rabbit, url, HttpMethod.POST, body);
            if (resp.getStatusCode() != HttpStatus.CREATED) {
                logger.error("add-binding 失败, httpStatus:{}, body:{}", resp.getStatusCode(), resp.getBody());
                return formatError("add-binding", resp.getStatusCode().value(), resp.getBody());
            }
            logger.info("add-binding 成功, queue:{}, exchange:{}, routingKey:{}",
                    queueName, exchangeName, routingKeyValue);
            return "✅ 绑定成功: 队列 '" + queueName.trim() + "' → 交换机 '" + exchangeName.trim()
                    + "' (routingKey=" + (routingKeyValue.isEmpty() ? "(空)" : routingKeyValue) + ")";
        } catch (IllegalArgumentException e) {
            return "[add-binding] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("add-binding 异常, queue:{}, exchange:{}, error:{}",
                    queueName, exchangeName, e.getMessage(), e);
            return "[add-binding] 绑定失败: " + e.getMessage();
        }
    }
}
