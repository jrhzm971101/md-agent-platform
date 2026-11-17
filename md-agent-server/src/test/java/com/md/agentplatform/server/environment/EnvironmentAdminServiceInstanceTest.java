package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.environment.EnvironmentViews.InstanceSaveRequest;
import com.md.agentplatform.server.environment.store.EnvironmentStore;
import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceConfigEntity;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实例独立维护的三个写用例：新建、改元数据、删除。
 *
 * <p>这里不碰数据库，重点钉住服务层对编码 / 展示名 / 排序 / 启停的约定：
 * 实例编码走 URL 且创建后不改，展示名留空回退编码，新实例排序排在已有实例之后。
 */
class EnvironmentAdminServiceInstanceTest {

    private static final String ENV = "cameroon-uat";
    private static final String GROUP = "ninedata";

    @Test
    void createInstanceUsesCodeAsDisplayNameFallbackAndAppendsSortOrder() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        EnvironmentAdminService service = new EnvironmentAdminService(store, catalog);

        when(store.findGroup(GROUP)).thenReturn(group());
        when(catalog.instancesOf(GROUP)).thenReturn(List.of(instance("default", "默认", 0)));
        when(catalog.keysOf(GROUP)).thenReturn(List.of(urlKey()));
        when(store.createGroupInstance(any())).thenReturn(true);

        assertTrue(service.createInstance(GROUP, "report",
                new InstanceSaveRequest("  ", null, null, Map.of("url", " https://report.example "))));

        verify(store).createGroupInstance(org.mockito.ArgumentMatchers.argThat(row ->
                "report".equals(row.getInstanceCode())
                        && "report".equals(row.getDisplayName())
                        && Boolean.TRUE.equals(row.getEnabled())
                        && Integer.valueOf(10).equals(row.getSortOrder())
                        && GROUP.equals(row.getGroupCode())));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EnvInstanceConfigEntity>> configs = ArgumentCaptor.forClass(List.class);
        verify(store).replaceInstanceConfigs(eq(GROUP), eq("report"), configs.capture());
        assertEquals("https://report.example", configs.getValue().get(0).getConfigValue());
        verify(catalog).invalidate();
    }

    @Test
    void saveInstanceUpdatesOnlyMutableMetadata() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        EnvironmentAdminService service = new EnvironmentAdminService(store, catalog);

        EnvGroupInstanceEntity existing = instance("report", "报表库", 10);
        when(store.findGroup(GROUP)).thenReturn(group());
        when(catalog.keysOf(GROUP)).thenReturn(List.of(urlKey()));
        when(store.findGroupInstance(GROUP, "report")).thenReturn(existing);
        when(store.saveGroupInstance(existing)).thenReturn(true);

        assertTrue(service.saveInstance(GROUP, "report",
                new InstanceSaveRequest("报表 NineData", false, 30,
                        Map.of("url", "https://report.example"))));

        assertEquals("报表 NineData", existing.getDisplayName());
        assertFalse(existing.getEnabled());
        assertEquals(30, existing.getSortOrder());
        assertEquals("report", existing.getInstanceCode());
        verify(store).replaceInstanceConfigs(eq(GROUP), eq("report"), any());
        verify(catalog).invalidate();
    }

    @Test
    void deleteMissingInstanceReturnsFalseWithoutTouchingStore() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        EnvironmentAdminService service = new EnvironmentAdminService(store, catalog);

        when(store.findGroupInstance(GROUP, "missing")).thenReturn(null);

        assertFalse(service.deleteInstance(GROUP, "missing"));
        verify(store, never()).deleteGroupInstance(GROUP, "missing");
        verify(catalog, never()).invalidate();
    }

    @Test
    void deleteExistingInstanceRemovesItAndInvalidatesCatalog() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        EnvironmentAdminService service = new EnvironmentAdminService(store, catalog);

        when(store.findGroupInstance(GROUP, "report"))
                .thenReturn(instance("report", "报表库", 10));

        assertTrue(service.deleteInstance(GROUP, "report"));
        verify(store).deleteGroupInstance(GROUP, "report");
        verify(catalog).invalidate();
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

    private static EnvGroupKeyEntity urlKey() {
        EnvGroupKeyEntity key = new EnvGroupKeyEntity();
        key.setGroupCode(GROUP);
        key.setKeyCode("url");
        key.setLabel("地址");
        key.setValueType("STRING");
        key.setRequired(Boolean.TRUE);
        key.setSortOrder(0);
        return key;
    }
}
