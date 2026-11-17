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
 * RabbitMQ 交换机声明工具 —— 通过管理 HTTP API 在目标实例上声明（创建）一个交换机。
 * url / 鉴权 / vhost 由 {@code environment} 从环境目录解析，名称 / 类型 / 持久化由方法参数传入。
 */
@Component
public class AddExchange extends AbstractRabbitMqTool {

    @Tool(
            name = "add-exchange",
            description = """
                    在 RabbitMQ 上声明（创建）一个交换机。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息（管理 API 地址 / 鉴权）从环境目录解析
                    - exchangeName: 交换机名称（必填），如 my.direct.exchange
                    - type: 交换机类型（选填，默认 direct），可选 direct / topic / fanout / headers
                    - durable: 是否持久化（选填，默认 true）

                    vhost 固定取环境目录 rabbitmq.vhost，无需传参（未配置时回退 /）。

                    返回: 声明成功或失败的结果信息。
                    """
    )
    public String addExchange(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "exchangeName", description = "交换机名称（必填），如 my.direct.exchange") String exchangeName,
            @ToolParam(name = "type", description = "交换机类型（选填，默认 direct），可选 direct / topic / fanout / headers") String type,
            @ToolParam(name = "durable", description = "是否持久化（选填，默认 true）") String durable
    ) {
        logger.info("add-exchange called, environment:{}, exchangeName:{}, type:{}, durable:{}",
                environment, exchangeName, type, durable);

        if (!notBlank(environment)) {
            return "[add-exchange] 参数缺失: environment 必填";
        }
        if (!notBlank(exchangeName)) {
            return "[add-exchange] 参数缺失: exchangeName 必填";
        }

        String exchangeType = defaultIfBlank(type, "direct").toLowerCase();
        if (!isSupportedType(exchangeType)) {
            return "[add-exchange] 不支持的交换机类型: " + type + "，可选: direct / topic / fanout / headers";
        }
        boolean durableVal = parseBool(durable, true);

        try {
            EnvironmentProperties.Rabbit rabbit = rabbit(environment);
            String vhost = resolveVhost(rabbit);
            String url = managementUrl(rabbit, "api", "exchanges", vhost, exchangeName.trim());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vhost", vhost);
            body.put("name", exchangeName.trim());
            body.put("type", exchangeType);
            body.put("durable", durableVal);

            ResponseEntity<String> resp = execute(rabbit, url, HttpMethod.PUT, body);
            if (resp.getStatusCode() != HttpStatus.CREATED) {
                logger.error("add-exchange 失败, httpStatus:{}, body:{}", resp.getStatusCode(), resp.getBody());
                return formatError("add-exchange", resp.getStatusCode().value(), resp.getBody());
            }
            logger.info("add-exchange 成功, exchange:{}, type:{}, durable:{}", exchangeName, exchangeType, durableVal);
            return "✅ 交换机 '" + exchangeName.trim() + "' 声明成功 (type=" + exchangeType
                    + ", durable=" + durableVal + ")";
        } catch (IllegalArgumentException e) {
            return "[add-exchange] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("add-exchange 异常, exchange:{}, error:{}", exchangeName, e.getMessage(), e);
            return "[add-exchange] 声明失败: " + e.getMessage();
        }
    }

    private static boolean isSupportedType(String type) {
        return "direct".equals(type) || "topic".equals(type) || "fanout".equals(type) || "headers".equals(type);
    }
}
