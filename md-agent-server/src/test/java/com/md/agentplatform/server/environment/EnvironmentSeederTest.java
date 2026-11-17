package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.environment.store.EnvironmentStore;
import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动播种的兼容性测试：旧库已有 gitlab 分组时，只补缺失的内置键声明，不改已有键。
 */
class EnvironmentSeederTest {

    @Test
    void backfillsMissingBuiltInKeysWithoutOverwritingExistingOnes() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);

        EnvGroupEntity gitlab = new EnvGroupEntity();
        gitlab.setGroupCode("gitlab");
        gitlab.setBuiltin(Boolean.TRUE);
        when(store.listGroups()).thenReturn(List.of(gitlab));
        when(store.listEnvironments()).thenReturn(List.of(new EnvironmentEntity()));

        EnvGroupKeyEntity url = key("url", "站点地址");
        EnvGroupKeyEntity token = key("token", "旧 token 标签");
        EnvGroupKeyEntity username = key("username", "用户名");
        when(store.listGroupKeys()).thenReturn(List.of(url, token, username));

        new EnvironmentSeeder(store, catalog).run(mock(ApplicationArguments.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EnvGroupKeyEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).replaceGroupKeys(eq("gitlab"), captor.capture());
        List<EnvGroupKeyEntity> merged = captor.getValue();
        Set<String> codes = merged.stream().map(EnvGroupKeyEntity::getKeyCode).collect(Collectors.toSet());

        assertTrue(codes.containsAll(Set.of("url", "token", "username",
                "authMode", "password", "oauthClientId", "oauthClientSecret",
                "oauthScope", "oauthTokenUrl", "tokenCacheSeconds")));
        assertEquals("旧 token 标签", merged.stream()
                .filter(key -> "token".equals(key.getKeyCode()))
                .findFirst().orElseThrow().getLabel());
        verify(catalog).invalidate();
    }

    @Test
    void createsDefaultInstanceForNewBuiltInGroupsWithoutCreatingEnvironments() {
        EnvironmentStore store = mock(EnvironmentStore.class);
        EnvironmentCatalog catalog = mock(EnvironmentCatalog.class);
        when(store.createGroup(any(EnvGroupEntity.class))).thenReturn(true);

        new EnvironmentSeeder(store, catalog).run(mock(ApplicationArguments.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EnvGroupInstanceEntity> captor = ArgumentCaptor.forClass(EnvGroupInstanceEntity.class);
        verify(store, atLeast(8)).createGroupInstance(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(instance -> "default".equals(instance.getInstanceCode())));
        verify(store, never())
                .createEnvironment(any(EnvironmentEntity.class), any(String.class));
    }

    private static EnvGroupKeyEntity key(String code, String label) {
        EnvGroupKeyEntity key = new EnvGroupKeyEntity();
        key.setGroupCode("gitlab");
        key.setKeyCode(code);
        key.setLabel(label);
        key.setValueType("STRING");
        key.setRequired(Boolean.FALSE);
        key.setSecret(Boolean.FALSE);
        key.setSortOrder(10);
        return key;
    }
}
