package com.md.agentplatform.server.environment.store;

import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceConfigEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceBindingEntity;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;

import java.util.List;

/**
 * 环境目录的存储接口。
 *
 * <h2>一、为什么单独一个 Store，而不是塞进 {@code ControlPlaneStore}</h2>
 * 环境目录是四张自己的表、一套自己的读写模式（读全量、写全量替换），而
 * {@code ControlPlaneStore} 已经是一个 41 个方法的接口，管的是"受治理元数据"的草稿/发布/版本。
 * 硬塞进去只会让两个不相干的变更理由挤在同一个接口上。追溯期与
 * {@code TraceStore} 独立成包是同一个判断。
 *
 * <h2>二、为什么读写都以「全量」为单位</h2>
 * 环境目录只有几十行，读侧永远要全量（页面要算完整度、解析层要随手取任意环境的任意键），
 * 写侧永远是"表单提交整份"。为它设计按行增量更新的 API，只会多出一堆
 * "谁负责保证行与行之间一致"的问题，而收益是零。
 *
 * <h2>三、事务边界在实现类里</h2>
 * 与 {@code MyBatisControlPlaneStore} 的约定一致：凡是"删一组行再插一组行"这类必须原子完成的动作，
 * {@code @Transactional} 标在实现方法上，接口只描述语义。
 */
public interface EnvironmentStore {

    // ==================== 环境 ====================

    /** 全部环境，按 sort_order、env_code 排序。 */
    List<EnvironmentEntity> listEnvironments();

    /** 一个环境的主表行；不存在返回 null。 */
    EnvironmentEntity findEnvironment(String envCode);

    /** 新建环境；env_code 已存在返回 false（控制器回 409）。 */
    boolean createEnvironment(EnvironmentEntity entity, String operator);

    /** 保存环境主表；不存在返回 false。只动主表，不动配置行。 */
    boolean saveEnvironment(EnvironmentEntity entity, String operator);

    /** 删除环境及其绑定关系。实例配置属于实例本身，不随环境删除。 */
    void deleteEnvironment(String envCode);

    // ==================== 分组模板 ====================

    /** 全部分组，按 sort_order、group_code 排序。 */
    List<EnvGroupEntity> listGroups();

    EnvGroupEntity findGroup(String groupCode);

    boolean createGroup(EnvGroupEntity entity);

    boolean saveGroup(EnvGroupEntity entity);

    /** 删除分组，连同它的键声明、实例、实例配置与绑定关系。 */
    void deleteGroup(String groupCode);

    // ==================== 键声明 ====================

    /** 全部键声明，按 group_code、sort_order、id 排序。 */
    List<EnvGroupKeyEntity> listGroupKeys();

    /**
     * 用给定列表<b>整体替换</b>某个分组的键声明（页面上的模板编辑就是一次整体提交）。
     *
     * <p>它会连做两件事，而且必须原子完成：替换键声明，并删掉"已经不再被声明"的那些键
     * 在所有实例上的配置值。否则库里会留下一批页面看不见、解析层也读不到的孤儿行 ——
     * 它们不会被任何界面展示，却会在下一次同名键被重新声明时"复活"成旧值。
     *
     * @return 被连带删除的配置行数（服务层据此告诉用户"删这个键，顺带清了 N 个实例上的值"）
     */
    int replaceGroupKeys(String groupCode, List<EnvGroupKeyEntity> keys);

    // ==================== 配置值 ====================

    /** 全部分组实例元数据。实例是分组级模板，不在环境上各存一份。 */
    List<EnvGroupInstanceEntity> listGroupInstances();

    /** 单个实例元数据；不存在返回 null。 */
    EnvGroupInstanceEntity findGroupInstance(String groupCode, String instanceCode);

    /** 新建单个实例；复合主键已存在返回 false。 */
    boolean createGroupInstance(EnvGroupInstanceEntity entity);

    /** 更新单个实例元数据；不存在返回 false。 */
    boolean saveGroupInstance(EnvGroupInstanceEntity entity);

    /** 删除单个实例、它的配置值，以及所有环境对它的绑定。 */
    void deleteGroupInstance(String groupCode, String instanceCode);

    /** 全部环境-实例绑定。详情组装时按环境取用。 */
    List<EnvInstanceBindingEntity> listInstanceBindings();

    /** 某个环境绑定了哪些实例。 */
    List<EnvInstanceBindingEntity> listInstanceBindings(String envCode);

    /**
     * 用给定列表<b>整体替换</b>某个环境的实例绑定。
     *
     * <p>只动绑定关系，不改实例元数据；解绑一个实例后它仍可以被其它环境使用。
     */
    void replaceInstanceBindings(String envCode, List<EnvInstanceBindingEntity> bindings);

    /** 全部实例配置值。解析层要的就是这一份全量快照。 */
    List<EnvInstanceConfigEntity> listInstanceConfigs();

    /** 某个实例的配置值。 */
    List<EnvInstanceConfigEntity> listInstanceConfigs(String groupCode, String instanceCode);

    /**
     * 用给定列表<b>整体替换</b>某个实例的配置值。
     *
     * <p>配置属于实例本身，与绑定它的环境无关。空值在服务层就被过滤掉了，所以"清空一个字段"
     * 与"从来没配过"在库里是同一个状态（没有行），不需要再设计一套"置空"语义。
     */
    void replaceInstanceConfigs(String groupCode, String instanceCode,
                                List<EnvInstanceConfigEntity> configs);

    /** 引用了该环境的 agent 名列表（删除环境前的保护检查）。 */
    List<String> findAgentNamesByEnvironment(String envCode);
}
