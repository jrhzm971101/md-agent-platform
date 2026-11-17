package com.md.agentplatform.server.environment.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_env_instance_binding} 的行模型 —— 某个环境绑定了分组下的哪个实例。
 *
 * <p>实例元数据和配置值都是分组级全局数据；绑定关系只回答“这个环境使用哪些实例”。
 * 这张表只表达“当前环境是否使用这个实例”，因此解绑不会删除实例定义，
 * 也不会影响它在其它环境中的配置。
 */
@Getter
@Setter
@TableName("md_env_instance_binding")
public class EnvInstanceBindingEntity {

    /** 复合主键第一段。所有写路径都使用显式 wrapper，不依赖 updateById。 */
    @TableId(value = "env_code", type = IdType.INPUT)
    private String envCode;

    private String groupCode;

    private String instanceCode;

    private Long createdAtMs;

    private Long updatedAtMs;
}
