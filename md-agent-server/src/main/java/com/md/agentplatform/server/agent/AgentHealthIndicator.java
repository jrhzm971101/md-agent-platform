package com.md.agentplatform.server.agent;

import com.md.agentplatform.server.messaging.BotInstance;
import com.md.agentplatform.server.messaging.BotInstanceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 健康检查：暴露 Agent 数量与各 Bot 存活状态。
 */
@Component
public class AgentHealthIndicator implements HealthIndicator {

    @Autowired
    private AgentManager agentManager;

    @Autowired
    private BotInstanceManager botInstanceManager;

    @Override
    public Health health() {
        List<BotInstance> bots = botInstanceManager.getAll();
        Map<String, Boolean> botHealth = bots.stream()
                .collect(Collectors.toMap(BotInstance::getName, BotInstance::isAlive));

        boolean allAlive = bots.isEmpty() || botHealth.values().stream().allMatch(Boolean::booleanValue);

        return (allAlive ? Health.up() : Health.down())
                .withDetail("agents", agentManager.listAll().size())
                .withDetail("bots", botHealth)
                .build();
    }
}
