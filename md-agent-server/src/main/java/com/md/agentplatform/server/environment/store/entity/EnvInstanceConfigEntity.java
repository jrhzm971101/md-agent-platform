package com.md.agentplatform.server.environment.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_env_instance_config} 的行模型 —— 一行 = 某个分组实例 / 某个键的值。
 *
 * <p>配置属于实例本身，不再带 env_code。环境只通过 {@code md_env_instance_binding}
 * 选择自己使用哪些实例；需要不同配置时，新建一个实例即可。
 */
@Getter
@Setter
@TableName("md_env_instance_config")
public class EnvInstanceConfigEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String groupCode;

    private String instanceCode;

    private String keyCode;

    /** 值。当前明文存储（调试期决定，见 env-schema.sql 文件头第四节）。 */
    private String configValue;

    private Long updatedAtMs;

    private String updatedBy;
}
