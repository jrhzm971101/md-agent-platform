package com.md.agentplatform.server.environment.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceConfigEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceBindingEntity;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;
import com.md.agentplatform.server.environment.store.mapper.EnvConfigMapper;
import com.md.agentplatform.server.environment.store.mapper.EnvGroupInstanceMapper;
import com.md.agentplatform.server.environment.store.mapper.EnvGroupKeyMapper;
import com.md.agentplatform.server.environment.store.mapper.EnvGroupMapper;
import com.md.agentplatform.server.environment.store.mapper.EnvInstanceConfigMapper;
import com.md.agentplatform.server.environment.store.mapper.EnvInstanceBindingMapper;
import com.md.agentplatform.server.environment.store.mapper.EnvironmentMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link EnvironmentStore} 的 MyBatis-Plus 实现。
 *
 * <h2>一、为什么用 {@code @Component} 而不是在配置类里写 {@code @Bean}</h2>
 * 与 {@code TraceStoreConfig} 不同，这个 Store 没有开关也没有构造参数（四个 Mapper 由 Spring 注入），
 * 不存在"用它或不用它"的部署选择 —— 于是没有理由再拉一个配置类只为写一行 {@code new}。
 *
 * <h2>二、空列表为什么单独判</h2>
 * MyBatis-Plus 的 {@code delete(wrapper)} 在 wrapper 条件为空时会**删全表**。
 * 本项目所有"整体替换"都先删后插，如果调用方传了一个合法但为空的列表（例如用户删光了
 * 某个分组的全部键），天真实现会把全表清掉。所以这里对空列表显式短路：
 * 什么都不插，只删自己那一组。
 *
 * <h2>三、覆盖写用 updateById 还是 update(wrapper)</h2>
 * 用 {@code updateById}：主键 1 个，语义最直白，返回行数即可判断"存在与否"，控制器据此回 404。
 * 注意 MyBatis-Plus 默认<b>忽略 null 字段</b>，所以「把某列改回 NULL」不能用它表达 ——
 * 服务层因此把所有可空文本统一规范化成空串（见 {@code EnvironmentAdminService#normalize}），
 * 空串是能写进去的。这一点必须成对阅读，否则会出现"页面清空了备注，库里还是旧值"。
 */
@Component
public class MyBatisEnvironmentStore implements EnvironmentStore {

    private final EnvironmentMapper environmentMapper;
    private final EnvGroupMapper groupMapper;
    private final EnvGroupKeyMapper groupKeyMapper;
    private final EnvGroupInstanceMapper groupInstanceMapper;
    private final EnvConfigMapper configMapper;
    private final EnvInstanceConfigMapper instanceConfigMapper;
    private final EnvInstanceBindingMapper instanceBindingMapper;

    public MyBatisEnvironmentStore(EnvironmentMapper environmentMapper, EnvGroupMapper groupMapper,
                                   EnvGroupKeyMapper groupKeyMapper, EnvGroupInstanceMapper groupInstanceMapper,
                                   EnvConfigMapper configMapper,
                                   EnvInstanceConfigMapper instanceConfigMapper,
                                   EnvInstanceBindingMapper instanceBindingMapper) {
        this.environmentMapper = environmentMapper;
        this.groupMapper = groupMapper;
        this.groupKeyMapper = groupKeyMapper;
        this.groupInstanceMapper = groupInstanceMapper;
        this.configMapper = configMapper;
        this.instanceConfigMapper = instanceConfigMapper;
        this.instanceBindingMapper = instanceBindingMapper;
    }

    // ==================== 环境 ====================

    @Override
    public List<EnvironmentEntity> listEnvironments() {
        return environmentMapper.selectList(new LambdaQueryWrapper<EnvironmentEntity>()
                .orderByAsc(EnvironmentEntity::getSortOrder)
                .orderByAsc(EnvironmentEntity::getEnvCode));
    }

    @Override
    public EnvironmentEntity findEnvironment(String envCode) {
        return envCode == null ? null : environmentMapper.selectById(envCode);
    }

    @Override
    public boolean createEnvironment(EnvironmentEntity entity, String operator) {
        long now = System.currentTimeMillis();
        entity.setCreatedAtMs(now);
        entity.setUpdatedAtMs(now);
        entity.setCreatedBy(operator);
        entity.setUpdatedBy(operator);
        try {
            return environmentMapper.insert(entity) > 0;
        } catch (DuplicateKeyException e) {
            // 唯一键冲突 = 已存在。这里是先写后判，而不是先查后写：
            // 先查后写在同一秒内的两次点击下仍会双写，而唯一键永远不会。
            return false;
        }
    }

    @Override
    public boolean saveEnvironment(EnvironmentEntity entity, String operator) {
        entity.setUpdatedAtMs(System.currentTimeMillis());
        entity.setUpdatedBy(operator);
        // createdAtMs / createdBy 不回写：它们属于"行的一生"，不属于这一次提交。
        entity.setCreatedAtMs(null);
        entity.setCreatedBy(null);
        return environmentMapper.updateById(entity) > 0;
    }

    @Override
    @Transactional
    public void deleteEnvironment(String envCode) {
        instanceBindingMapper.delete(new LambdaQueryWrapper<EnvInstanceBindingEntity>()
                .eq(EnvInstanceBindingEntity::getEnvCode, envCode));
        environmentMapper.deleteById(envCode);
    }

    // ==================== 分组模板 ====================

    @Override
    public List<EnvGroupEntity> listGroups() {
        return groupMapper.selectList(new LambdaQueryWrapper<EnvGroupEntity>()
                .orderByAsc(EnvGroupEntity::getSortOrder)
                .orderByAsc(EnvGroupEntity::getGroupCode));
    }

    @Override
    public EnvGroupEntity findGroup(String groupCode) {
        return groupCode == null ? null : groupMapper.selectById(groupCode);
    }

    @Override
    public boolean createGroup(EnvGroupEntity entity) {
        long now = System.currentTimeMillis();
        entity.setCreatedAtMs(now);
        entity.setUpdatedAtMs(now);
        try {
            return groupMapper.insert(entity) > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean saveGroup(EnvGroupEntity entity) {
        entity.setUpdatedAtMs(System.currentTimeMillis());
        entity.setCreatedAtMs(null);
        return groupMapper.updateById(entity) > 0;
    }

    @Override
    @Transactional
    public void deleteGroup(String groupCode) {
        instanceConfigMapper.delete(new LambdaQueryWrapper<EnvInstanceConfigEntity>()
                .eq(EnvInstanceConfigEntity::getGroupCode, groupCode));
        instanceBindingMapper.delete(new LambdaQueryWrapper<EnvInstanceBindingEntity>()
                .eq(EnvInstanceBindingEntity::getGroupCode, groupCode));
        groupInstanceMapper.delete(new LambdaQueryWrapper<EnvGroupInstanceEntity>()
                .eq(EnvGroupInstanceEntity::getGroupCode, groupCode));
        groupKeyMapper.delete(new LambdaQueryWrapper<EnvGroupKeyEntity>()
                .eq(EnvGroupKeyEntity::getGroupCode, groupCode));
        groupMapper.deleteById(groupCode);
    }

    // ==================== 键声明 ====================

    @Override
    public List<EnvGroupKeyEntity> listGroupKeys() {
        return groupKeyMapper.selectList(new LambdaQueryWrapper<EnvGroupKeyEntity>()
                .orderByAsc(EnvGroupKeyEntity::getGroupCode)
                .orderByAsc(EnvGroupKeyEntity::getSortOrder)
                .orderByAsc(EnvGroupKeyEntity::getId));
    }

    @Override
    @Transactional
    public int replaceGroupKeys(String groupCode, List<EnvGroupKeyEntity> keys) {
        groupKeyMapper.delete(new LambdaQueryWrapper<EnvGroupKeyEntity>()
                .eq(EnvGroupKeyEntity::getGroupCode, groupCode));

        List<String> declared = new ArrayList<>();
        if (keys != null && !keys.isEmpty()) {
            long now = System.currentTimeMillis();
            for (EnvGroupKeyEntity key : keys) {
                key.setId(null); // 整体替换 = 重新插入，不能带着调用方传来的旧 id
                key.setGroupCode(groupCode);
                key.setCreatedAtMs(now);
                key.setUpdatedAtMs(now);
                groupKeyMapper.insert(key);
                declared.add(key.getKeyCode());
            }
        }

        // 不再被声明的键，它的值在页面上已经无处可看、解析层也不会去读。
        // 留着它只会造成一个假象："这个键从来没配过"，直到某天有人把同名键加回来，
        // 然后发现"新加的键居然自动有值了"。所以一并清掉，并把行数报给服务层。
        LambdaQueryWrapper<EnvInstanceConfigEntity> orphan =
                new LambdaQueryWrapper<EnvInstanceConfigEntity>()
                        .eq(EnvInstanceConfigEntity::getGroupCode, groupCode);
        if (!declared.isEmpty()) {
            orphan.notIn(EnvInstanceConfigEntity::getKeyCode, declared);
        }
        return instanceConfigMapper.delete(orphan);
    }

    // ==================== 配置值 ====================

    @Override
    public List<EnvGroupInstanceEntity> listGroupInstances() {
        return groupInstanceMapper.selectList(new LambdaQueryWrapper<EnvGroupInstanceEntity>()
                .orderByAsc(EnvGroupInstanceEntity::getGroupCode)
                .orderByAsc(EnvGroupInstanceEntity::getSortOrder)
                .orderByAsc(EnvGroupInstanceEntity::getInstanceCode));
    }

    @Override
    public EnvGroupInstanceEntity findGroupInstance(String groupCode, String instanceCode) {
        if (groupCode == null || instanceCode == null) {
            return null;
        }
        return groupInstanceMapper.selectOne(new LambdaQueryWrapper<EnvGroupInstanceEntity>()
                .eq(EnvGroupInstanceEntity::getGroupCode, groupCode)
                .eq(EnvGroupInstanceEntity::getInstanceCode, instanceCode));
    }

    @Override
    public boolean createGroupInstance(EnvGroupInstanceEntity entity) {
        long now = System.currentTimeMillis();
        entity.setCreatedAtMs(now);
        entity.setUpdatedAtMs(now);
        try {
            return groupInstanceMapper.insert(entity) > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean saveGroupInstance(EnvGroupInstanceEntity entity) {
        entity.setUpdatedAtMs(System.currentTimeMillis());
        entity.setCreatedAtMs(null);
        return groupInstanceMapper.update(entity, new LambdaUpdateWrapper<EnvGroupInstanceEntity>()
                .eq(EnvGroupInstanceEntity::getGroupCode, entity.getGroupCode())
                .eq(EnvGroupInstanceEntity::getInstanceCode, entity.getInstanceCode())) > 0;
    }

    @Override
    @Transactional
    public void deleteGroupInstance(String groupCode, String instanceCode) {
        instanceConfigMapper.delete(new LambdaQueryWrapper<EnvInstanceConfigEntity>()
                .eq(EnvInstanceConfigEntity::getGroupCode, groupCode)
                .eq(EnvInstanceConfigEntity::getInstanceCode, instanceCode));
        instanceBindingMapper.delete(new LambdaQueryWrapper<EnvInstanceBindingEntity>()
                .eq(EnvInstanceBindingEntity::getGroupCode, groupCode)
                .eq(EnvInstanceBindingEntity::getInstanceCode, instanceCode));
        groupInstanceMapper.delete(new LambdaQueryWrapper<EnvGroupInstanceEntity>()
                .eq(EnvGroupInstanceEntity::getGroupCode, groupCode)
                .eq(EnvGroupInstanceEntity::getInstanceCode, instanceCode));
    }

    @Override
    public List<EnvInstanceBindingEntity> listInstanceBindings() {
        return instanceBindingMapper.selectList(new LambdaQueryWrapper<EnvInstanceBindingEntity>()
                .orderByAsc(EnvInstanceBindingEntity::getEnvCode)
                .orderByAsc(EnvInstanceBindingEntity::getGroupCode)
                .orderByAsc(EnvInstanceBindingEntity::getInstanceCode));
    }

    @Override
    public List<EnvInstanceBindingEntity> listInstanceBindings(String envCode) {
        return envCode == null ? List.of() : instanceBindingMapper.selectList(
                new LambdaQueryWrapper<EnvInstanceBindingEntity>()
                        .eq(EnvInstanceBindingEntity::getEnvCode, envCode)
                        .orderByAsc(EnvInstanceBindingEntity::getGroupCode)
                        .orderByAsc(EnvInstanceBindingEntity::getInstanceCode));
    }

    @Override
    @Transactional
    public void replaceInstanceBindings(String envCode, List<EnvInstanceBindingEntity> bindings) {
        instanceBindingMapper.delete(new LambdaQueryWrapper<EnvInstanceBindingEntity>()
                .eq(EnvInstanceBindingEntity::getEnvCode, envCode));
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (EnvInstanceBindingEntity binding : bindings) {
            binding.setEnvCode(envCode);
            binding.setCreatedAtMs(now);
            binding.setUpdatedAtMs(now);
            instanceBindingMapper.insert(binding);
        }
    }

    @Override
    public List<EnvInstanceConfigEntity> listInstanceConfigs() {
        return instanceConfigMapper.selectList(new LambdaQueryWrapper<EnvInstanceConfigEntity>()
                .orderByAsc(EnvInstanceConfigEntity::getGroupCode)
                .orderByAsc(EnvInstanceConfigEntity::getInstanceCode)
                .orderByAsc(EnvInstanceConfigEntity::getKeyCode));
    }

    @Override
    public List<EnvInstanceConfigEntity> listInstanceConfigs(String groupCode, String instanceCode) {
        return instanceConfigMapper.selectList(new LambdaQueryWrapper<EnvInstanceConfigEntity>()
                .eq(EnvInstanceConfigEntity::getGroupCode, groupCode)
                .eq(EnvInstanceConfigEntity::getInstanceCode, instanceCode)
                .orderByAsc(EnvInstanceConfigEntity::getKeyCode));
    }

    @Override
    @Transactional
    public void replaceInstanceConfigs(String groupCode, String instanceCode,
                                       List<EnvInstanceConfigEntity> configs) {
        instanceConfigMapper.delete(new LambdaQueryWrapper<EnvInstanceConfigEntity>()
                .eq(EnvInstanceConfigEntity::getGroupCode, groupCode)
                .eq(EnvInstanceConfigEntity::getInstanceCode, instanceCode));
        if (configs == null || configs.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (EnvInstanceConfigEntity config : configs) {
            config.setId(null);
            config.setGroupCode(groupCode);
            config.setInstanceCode(instanceCode);
            config.setUpdatedAtMs(now);
            // 逐行 insert 而不是批量：BaseMapper 没有批插方法（要批量得自己写 SqlInjector 或 XML），
            // 而这里的真实规模是"一个环境 ≤ 40 行"，同库同事务下多几次往返换来的是零额外基础设施。
            // 真嫌慢时的正解是把这些行拼成一条 INSERT ... VALUES (...),(...) —— 到那时再看，现在不算。
            instanceConfigMapper.insert(config);
        }
    }

    @Override
    public List<String> findAgentNamesByEnvironment(String envCode) {
        return envCode == null ? List.of() : configMapper.findAgentNamesByEnvironment(envCode);
    }
}
