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
 * RabbitMQ 队列声明工具 —— 通过管理 HTTP API 在目标实例上声明（创建）一个队列，
 * 支持持久化 / 排他 / 自动删除 / 死信交换机 / 死信路由键 / 消息 TTL。
 * url / 鉴权 / vhost 由 {@code environment} 从环境目录解析，其余队列配置由方法参数传入。
 */
@Component
public class AddQueue extends AbstractRabbitMqTool {

    @Tool(
            name = "add-queue",
            description = """
                    在 RabbitMQ 上声明（创建）一个队列。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - queueName: 队列名称（必填），如 my.queue
                    - durable: 是否持久化（选填，默认 true）
                    - exclusive: 是否排他（选填，默认 false）
                    - autoDelete: 是否自动删除（选填，默认 false）
                    - deadLetterExchange: 死信交换机（选填），消息被拒绝/过期后转发到该交换机
                    - deadLetterRoutingKey: 死信路由键（选填）
                    - messageTtl: 消息过期时间 TTL（选填，单位毫秒）

                    vhost 固定取环境目录 rabbitmq.vhost，无需传参（未配置时回退 /）。

                    返回: 声明成功或失败的结果信息。
                    """
    )
    public String addQueue(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "queueName", description = "队列名称（必填），如 my.queue") String queueName,
            @ToolParam(name = "durable", description = "是否持久化（选填，默认 true）") String durable,
            @ToolParam(name = "exclusive", description = "是否排他（选填，默认 false）") String exclusive,
            @ToolParam(name = "autoDelete", description = "是否自动删除（选填，默认 false）") String autoDelete,
            @ToolParam(name = "deadLetterExchange", description = "死信交换机（选填），消息被拒绝/过期后转发到该交换机") String deadLetterExchange,
            @ToolParam(name = "deadLetterRoutingKey", description = "死信路由键（选填）") String deadLetterRoutingKey,
            @ToolParam(name = "messageTtl", description = "消息过期时间 TTL（选填，单位毫秒）") String messageTtl
    ) {
        logger.info("add-queue called, environment:{}, queueName:{}, durable:{}, dlx:{}",
                environment, queueName, durable, deadLetterExchange);

        if (!notBlank(environment)) {
            return "[add-queue] 参数缺失: environment 必填";
        }
        if (!notBlank(queueName)) {
            return "[add-queue] 参数缺失: queueName 必填";
        }

        boolean durableVal = parseBool(durable, true);
        boolean exclusiveVal = parseBool(exclusive, false);
        boolean autoDeleteVal = parseBool(autoDelete, false);

        // 队列参数：死信交换机 / 死信路由键 / 消息 TTL
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (notBlank(deadLetterExchange)) {
            arguments.put("x-dead-letter-exchange", deadLetterExchange.trim());
        }
        if (notBlank(deadLetterRoutingKey)) {
            arguments.put("x-dead-letter-routing-key", deadLetterRoutingKey.trim());
        }
        if (notBlank(messageTtl)) {
            try {
                arguments.put("x-message-ttl", Integer.parseInt(messageTtl.trim()));
            } catch (NumberFormatException e) {
                return "[add-queue] 参数错误: messageTtl 必须是数字";
            }
        }

        try {
            EnvironmentProperties.Rabbit rabbit = rabbit(environment);
            String vhost = resolveVhost(rabbit);
            String url = managementUrl(rabbit, "api", "queues", vhost, queueName.trim());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vhost", vhost);
            body.put("name", queueName.trim());
            body.put("durable", durableVal);
            body.put("exclusive", exclusiveVal);
            body.put("auto_delete", autoDeleteVal);
            body.put("arguments", arguments);

            ResponseEntity<String> resp = execute(rabbit, url, HttpMethod.PUT, body);
            if (resp.getStatusCode() != HttpStatus.CREATED) {
                logger.error("add-queue 失败, httpStatus:{}, body:{}", resp.getStatusCode(), resp.getBody());
                return formatError("add-queue", resp.getStatusCode().value(), resp.getBody());
            }
            logger.info("add-queue 成功, queue:{}, durable:{}, exclusive:{}, autoDelete:{}",
                    queueName, durableVal, exclusiveVal, autoDeleteVal);
            return "✅ 队列 '" + queueName.trim() + "' 声明成功 (durable=" + durableVal
                    + ", exclusive=" + exclusiveVal + ", autoDelete=" + autoDeleteVal
                    + ", 参数: " + (arguments.isEmpty() ? "无" : arguments) + ")";
        } catch (IllegalArgumentException e) {
            return "[add-queue] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("add-queue 异常, queue:{}, error:{}", queueName, e.getMessage(), e);
            return "[add-queue] 声明失败: " + e.getMessage();
        }
    }
}
