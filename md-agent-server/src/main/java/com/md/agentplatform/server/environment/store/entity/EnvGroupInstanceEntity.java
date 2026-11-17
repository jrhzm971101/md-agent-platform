package com.md.agentplatform.server.environment.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_env_group_instance} 的行模型 —— 一个分组下的一套实例定义。
 *
 * <p>实例负责“这组连接信息有几套、页面怎么显示”，以及它自己的固定配置值
 * （{@code md_env_instance_config}）。
 * 把实例元数据单独放一张表，而不是在配置行里重复 display_name / enabled，是为了让
 * “新增一个实例”不依赖它当时有没有填任何值。
 */
@Getter
@Setter
@TableName("md_env_group_instance")
public class EnvGroupInstanceEntity {

    /**
     * 表使用 (group_code, instance_code) 复合主键。
     *
     * <p>MyBatis-Plus 的 BaseMapper 需要一个 @TableId，这里把第一段标成 INPUT；
     * 本项目的所有写路径都是显式 wrapper，不会调用 updateById。
     */
    @TableId(value = "group_code", type = IdType.INPUT)
    private String groupCode;

    private String instanceCode;

    private String displayName;

    private Boolean enabled;

    private Integer sortOrder;

    private Long createdAtMs;

    private Long updatedAtMs;
}
