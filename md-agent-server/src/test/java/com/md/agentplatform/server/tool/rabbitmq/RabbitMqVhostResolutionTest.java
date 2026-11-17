package com.md.agentplatform.server.tool.rabbitmq;

import com.md.agentplatform.server.config.EnvironmentProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** vhost 解析：固定取环境配置，未配置回退 /。 */
class RabbitMqVhostResolutionTest {

    private final TestRabbitTool tool = new TestRabbitTool();

    @Test
    void fallsBackToEnvironmentVhost() {
        assertEquals("/env-vhost", tool.resolve(rabbit("/env-vhost")));
    }

    @Test
    void fallsBackToRootWhenNothingConfigured() {
        assertEquals("/", tool.resolve(rabbit(null)));
        assertEquals("/", tool.resolve(rabbit(" ")));
    }

    private static EnvironmentProperties.Rabbit rabbit(String vhost) {
        EnvironmentProperties.Rabbit rabbit = new EnvironmentProperties.Rabbit();
        rabbit.setVhost(vhost);
        return rabbit;
    }

    private static final class TestRabbitTool extends AbstractRabbitMqTool {
        String resolve(EnvironmentProperties.Rabbit rabbit) {
            return resolveVhost(rabbit);
        }
    }
}
