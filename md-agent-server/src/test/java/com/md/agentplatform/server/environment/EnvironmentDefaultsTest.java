package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内置分组模板必须包含运行时工具依赖的新增字段。 */
class EnvironmentDefaultsTest {

    @Test
    void builtInGroupsDeclareNamespaceAndVhostKeys() {
        assertTrue(keyCodes("nacos").contains("namespace"));
        assertTrue(keyCodes("k8s").contains("namespace"));
        assertTrue(keyCodes("rabbitmq").contains("vhost"));
    }

    @Test
    void monitoringGroupsDeclareConnectionAndAuthKeys() {
        assertTrue(keyCodes("grafana").containsAll(Set.of("url", "username", "password")));
        assertTrue(keyCodes("skywalking").containsAll(Set.of("url", "username", "password")));
    }

    private static Set<String> keyCodes(String groupCode) {
        return EnvironmentDefaults.seeds().stream()
                .filter(seed -> groupCode.equals(seed.group().getGroupCode()))
                .findFirst()
                .orElseThrow()
                .keys().stream()
                .map(EnvGroupKeyEntity::getKeyCode)
                .collect(Collectors.toSet());
    }
}
