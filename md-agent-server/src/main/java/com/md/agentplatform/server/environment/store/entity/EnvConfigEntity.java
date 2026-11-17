package com.md.agentplatform.server.environment.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 旧表 {@code md_env_config} 的行模型 —— 一行 = 某个环境 / 分组 / 实例 / 键的值。
 *
 * <p><b>2026-11-07 起这是只读的历史备份表</b>。配置值已经迁到 {@link EnvInstanceConfigEntity}
 * （{@code md_env_instance_config}），行里不再带 env_code。这里保留实体只为能读取旧数据做排查，
 * 业务读写不再经过它。
 *
 * <h2>一、字段名为什么叫 configValue 而不是 value</h2>
 * 列名 {@code config_value} 与字段 {@code configValue} 由 MyBatis-Plus 的默认驼峰规则自动对上，
 * 一个注解都不用加。叫 {@code value} 的话要么撞 MySQL 的非保留字 {@code VALUE}，
 * 要么必须挂 {@code @TableField}，两种都比现在多一处要记的东西。
 *
 * <h2>二、为什么空值 = 没有行，而不是 value = ''</h2>
 * 完整度是"必填键里填了几个"。如果允许空串行存在，每个查询都要额外判 {@code <> ''}，
 * 漏一处就会把"没配"算成"配了"。保存时直接把空值过滤掉不落行，这个不变式只有一处要守
 *（新表由 {@code EnvironmentAdminService} 的实例保存用例维护）。
 *
 * <h2>三、为什么没有"值级备注"</h2>
 * "这个 token 是临时的，2026-10 到期"这类备注确实有用，但当前没有真实需求用到它，
 * 而它会让编辑表单每个字段都多一个输入框。先不加列，需要时再加 ——
 * 加列是纯增量动作（默认 NULL，不影响任何现有读写）。
 */
@Getter
@Setter
@TableName("md_env_config")
public class EnvConfigEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String envCode;

    private String groupCode;

    /** 实例标识。旧数据与未显式指定实例的调用统一使用 {@code default}。 */
    private String instanceCode;

    private String keyCode;

    /** 值。当前明文存储（调试期决定，见 env-schema.sql 文件头第四节）。 */
    private String configValue;

    private Long updatedAtMs;

    private String updatedBy;
}
