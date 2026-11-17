package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.environment.store.EnvironmentStore;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceBindingEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceConfigEntity;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分组多实例的解析规则 —— 这块是兼容旧工具调用的关键，必须有测试钉住：
 * 未指定实例时优先 default；没有 default 但只有一个实例时自动选中；
 * 多个实例且没有 default 时必须报错，不能悄悄选一套。
 */
class EnvironmentCatalogInstanceTest {

    private static final String ENV = "uat";
    private static final String GROUP = "ninedata";

    @Test
    void unspecifiedInstancePrefersDefaultWhenItExists() {
        EnvironmentCatalog catalog = catalog(
                List.of(instance("default"), instance("report")),
                List.of(config("default", "report"), config("report", "report-db")));

        assertEquals("report", catalog.groupValues(ENV, GROUP).get("url"));
        assertEquals("report-db", catalog.instanceValues(ENV, GROUP, "report").get("url"));
    }

    @Test
    void unspecifiedInstanceFallsBackToTheOnlyInstance() {
        EnvironmentCatalog catalog = catalog(
                List.of(instance("report")),
                List.of(config("report", "report-db")));

        assertEquals("report-db", catalog.groupValues(ENV, GROUP).get("url"));
    }

    @Test
    void ambiguousInstancesFailWithAvailableList() {
        EnvironmentCatalog catalog = catalog(
                List.of(instance("report"), instance("archive")),
                List.of(config("report", "report-db"), config("archive", "archive-db")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> catalog.groupValues(ENV, GROUP));
        assertTrue(failure.getMessage().contains("多个实例"), failure.getMessage());
        assertTrue(failure.getMessage().contains("report"), failure.getMessage());
        assertTrue(failure.getMessage().contains("archive"), failure.getMessage());
    }

    @Test
    void explicitUnknownInstanceFailsWithAvailableList() {
        EnvironmentCatalog catalog = catalog(
                List.of(instance("default")),
                List.of(config("default", "default-db")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> catalog.instanceValues(ENV, GROUP, "missing"));
        assertTrue(failure.getMessage().contains("missing"), failure.getMessage());
        assertTrue(failure.getMessage().contains("default"), failure.getMessage());
    }

    @Test
    void undeclaredConfigValueDoesNotResurrectInstanceMetadata() {
        EnvironmentCatalog catalog = catalog(
                List.of(instance("default")),
                List.of(config("default", "default-db"), config("ghost", "ghost-db")));

        assertEquals(List.of("default"), catalog.instancesOf(GROUP).stream()
                .map(EnvGroupInstanceEntity::getInstanceCode)
                .toList());
        assertThrows(IllegalArgumentException.class,
                () -> catalog.instanceValues(ENV, GROUP, "ghost"));
    }

    @Test
    void databaseFailureIsReportedInsteadOfFallingBackToYml() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        when(store.listEnvironments()).thenThrow(new IllegalStateException("db down"));
        EnvironmentCatalog catalog = new EnvironmentCatalog(store);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> catalog.groupValues(ENV, GROUP));

        assertTrue(failure.getMessage().contains("只从数据库加载"), failure.getMessage());
        assertTrue(failure.getCause() instanceof IllegalStateException, failure.toString());
    }

    private static EnvironmentCatalog catalog(List<EnvGroupInstanceEntity> instances,
                                              List<EnvInstanceConfigEntity> configs) {
        EnvironmentStore store = mock(EnvironmentStore.class);

        EnvironmentEntity environment = new EnvironmentEntity();
        environment.setEnvCode(ENV);
        environment.setDisplayName("UAT");
        environment.setEnabled(true);
        environment.setSortOrder(0);

        when(store.listEnvironments()).thenReturn(List.of(environment));
        when(store.listGroupInstances()).thenReturn(instances);
        when(store.listInstanceConfigs()).thenReturn(configs);
        when(store.listInstanceBindings()).thenReturn(instances.stream()
                .map(instance -> binding(instance.getInstanceCode()))
                .toList());
        return new EnvironmentCatalog(store);
    }

    private static EnvGroupInstanceEntity instance(String instanceCode) {
        EnvGroupInstanceEntity instance = new EnvGroupInstanceEntity();
        instance.setGroupCode(GROUP);
        instance.setInstanceCode(instanceCode);
        instance.setDisplayName(instanceCode);
        instance.setEnabled(true);
        instance.setSortOrder(0);
        instance.setCreatedAtMs(0L);
        instance.setUpdatedAtMs(0L);
        return instance;
    }

    private static EnvInstanceConfigEntity config(String instanceCode, String value) {
        EnvInstanceConfigEntity config = new EnvInstanceConfigEntity();
        config.setGroupCode(GROUP);
        config.setInstanceCode(instanceCode);
        config.setKeyCode("url");
        config.setConfigValue(value);
        return config;
    }

    private static EnvInstanceBindingEntity binding(String instanceCode) {
        EnvInstanceBindingEntity binding = new EnvInstanceBindingEntity();
        binding.setEnvCode(ENV);
        binding.setGroupCode(GROUP);
        binding.setInstanceCode(instanceCode);
        return binding;
    }
}
