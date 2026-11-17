package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.environment.EnvironmentViews.EnvironmentDetail;
import com.md.agentplatform.server.environment.EnvironmentViews.EnvironmentRow;
import com.md.agentplatform.server.environment.EnvironmentViews.SaveRequest;
import com.md.agentplatform.server.environment.store.EnvironmentStore;
import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceBindingEntity;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 环境与实例绑定：解绑只影响当前环境，详情也只返回已绑定实例。 */
class EnvironmentAdminServiceBindingTest {

    private static final String ENV = "cameroon-uat";
    private static final String GROUP = "ninedata";

    @Test
    void detailOnlyReturnsInstancesBoundToEnvironment() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        EnvironmentAdminService service = new EnvironmentAdminService(store, catalog);

        EnvironmentEntity environment = environment();
        EnvGroupEntity group = group();
        when(catalog.requireEnvironment(ENV)).thenReturn(environment);
        when(catalog.listGroups()).thenReturn(List.of(group));
        when(catalog.keysOf(GROUP)).thenReturn(List.of(requiredKey()));
        when(catalog.instancesOf(GROUP)).thenReturn(List.of(
                instance("default", "默认", 0),
                instance("backup", "备用", 10)));
        when(store.listInstanceBindings(ENV)).thenReturn(List.of(binding("default")));
        when(catalog.instanceValues(ENV, GROUP, "default")).thenReturn(Map.of("url", "https://example"));
        when(catalog.instanceValues(ENV, GROUP, "backup")).thenReturn(Map.of());

        EnvironmentDetail detail = service.detail(ENV);

        assertEquals(1, detail.groups().get(0).instances().size());
        assertEquals("default", detail.groups().get(0).instances().get(0).instanceCode());
    }

    @Test
    void saveEnvironmentReplacesBindingsWithoutDeletingInstanceMetadata() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        EnvironmentAdminService service = new EnvironmentAdminService(store, catalog);

        EnvironmentEntity environment = environment();
        EnvGroupEntity group = group();
        when(store.findEnvironment(ENV)).thenReturn(environment);
        when(catalog.requireEnvironment(ENV)).thenReturn(environment);
        when(catalog.listGroups()).thenReturn(List.of(group));
        when(catalog.keysOf(GROUP)).thenReturn(List.of(requiredKey()));
        when(catalog.instancesOf(GROUP)).thenReturn(List.of(
                instance("default", "默认", 0),
                instance("backup", "备用", 10)));

        SaveRequest body = new SaveRequest(null, "喀麦隆 UAT", null, true, 0,
                Map.of(GROUP, List.of("backup")));
        assertTrue(service.save(ENV, body, "console"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EnvInstanceBindingEntity>> bindings =
                ArgumentCaptor.forClass(List.class);
        verify(store).replaceInstanceBindings(eq(ENV), bindings.capture());
        assertEquals(List.of("backup"), bindings.getValue().stream()
                .map(EnvInstanceBindingEntity::getInstanceCode).toList());
        verify(store, never()).replaceInstanceConfigs(any(), any(), any());
        verify(store, never()).deleteGroupInstance(any(), any());
    }

    @Test
    void listCountsRequiredKeysOnlyForBoundInstances() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        EnvironmentAdminService service = new EnvironmentAdminService(store, catalog);

        when(catalog.listGroups()).thenReturn(List.of(group()));
        when(catalog.keysOf(GROUP)).thenReturn(List.of(requiredKey()));
        when(catalog.instancesOf(GROUP)).thenReturn(List.of(
                instance("default", "默认", 0),
                instance("backup", "备用", 10)));
        when(catalog.listEnvironments()).thenReturn(List.of(environment()));
        when(store.listInstanceBindings(ENV)).thenReturn(List.of(binding("default")));
        when(catalog.instanceValues(ENV, GROUP, "default")).thenReturn(Map.of("url", "https://example"));
        // 未绑定的实例即使有值，也不能计入该环境的完整度分母。
        when(catalog.instanceValues(ENV, GROUP, "backup")).thenReturn(Map.of("url", "https://backup"));

        EnvironmentRow row = service.list().get(0);

        assertEquals(1, row.requiredKeys(), "分母只算当前环境绑定实例的必填项");
        assertEquals(1, row.readyKeys());
        assertEquals(1, row.groups().get(0).instanceCount());
    }

    @Test
    void listIgnoresGroupsWithoutBindings() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        EnvironmentAdminService service = new EnvironmentAdminService(store, catalog);

        when(catalog.listGroups()).thenReturn(List.of(group()));
        when(catalog.keysOf(GROUP)).thenReturn(List.of(requiredKey()));
        when(catalog.instancesOf(GROUP)).thenReturn(List.of(instance("default", "默认", 0)));
        when(catalog.listEnvironments()).thenReturn(List.of(environment()));
        when(store.listInstanceBindings(ENV)).thenReturn(List.of());
        when(catalog.instanceValues(ENV, GROUP, "default")).thenReturn(Map.of());

        EnvironmentRow row = service.list().get(0);

        assertEquals(0, row.requiredKeys(), "没有绑定任何实例时，分母为 0 而不是模板总数");
        assertEquals(0, row.readyKeys());
        assertEquals(0, row.groups().get(0).instanceCount());
    }

    private static EnvironmentEntity environment() {
        EnvironmentEntity environment = new EnvironmentEntity();
        environment.setEnvCode(ENV);
        environment.setDisplayName("喀麦隆 UAT");
        environment.setEnabled(true);
        environment.setSortOrder(0);
        return environment;
    }

    private static EnvGroupEntity group() {
        EnvGroupEntity group = new EnvGroupEntity();
        group.setGroupCode(GROUP);
        group.setDisplayName("NineData");
        group.setBuiltin(false);
        group.setSortOrder(10);
        return group;
    }

    private static EnvGroupInstanceEntity instance(String code, String displayName, int sortOrder) {
        EnvGroupInstanceEntity instance = new EnvGroupInstanceEntity();
        instance.setGroupCode(GROUP);
        instance.setInstanceCode(code);
        instance.setDisplayName(displayName);
        instance.setEnabled(true);
        instance.setSortOrder(sortOrder);
        return instance;
    }

    private static EnvGroupKeyEntity requiredKey() {
        EnvGroupKeyEntity key = new EnvGroupKeyEntity();
        key.setGroupCode(GROUP);
        key.setKeyCode("url");
        key.setLabel("地址");
        key.setValueType("STRING");
        key.setRequired(true);
        key.setSortOrder(0);
        return key;
    }

    private static EnvInstanceBindingEntity binding(String instanceCode) {
        EnvInstanceBindingEntity binding = new EnvInstanceBindingEntity();
        binding.setEnvCode(ENV);
        binding.setGroupCode(GROUP);
        binding.setInstanceCode(instanceCode);
        return binding;
    }
}
