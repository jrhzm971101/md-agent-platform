package com.md.agentplatform.server.environment.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_env_group} 的行模型 —— 一行 = 页面上的一列。
 *
 * <h2>为什么 group_code 是主键而不是自增 id</h2>
 * group_code 会被写进配置行（{@code md_env_config.group_code}），是真正的业务键；
 * 再套一层自增 id 只会让"页面上看见的列"与"库里的键"又多一层翻译。
 * 注意它<b>不是</b>展示名：展示名在 {@code display_name}，两者可以不一致（这也是硬规矩：
 * 页面只露名称、不露 code）。
 */
@Getter
@Setter
@TableName("md_env_group")
public class EnvGroupEntity {

    /** 分组标识，键值路径第一段，如 {@code k8s}。 */
    @TableId(value = "group_code", type = IdType.INPUT)
    private String groupCode;

    /** 展示名（表格列头）。 */
    private String displayName;

    /** 前端图标名；空 = 不画图标。 */
    private String icon;

    private String description;

    /**
     * 是否被工具代码强引用（k8s / rabbitmq / nacos / xxljob / magic / openobserve 为 1）。
     *
     * <p>它的用途只有一个：<b>拦住删除</b>。这些分组的键名与 {@code EnvironmentRegistry} 的强类型
     * 访问器逐字对应，删掉分组不会报错，而是等到某次工具调用时才以"未配置"的形式暴露。
     * 允许改、允许加键，但不允许删 —— 这与本项目的既定原则一致：
     * 与代码强绑定的东西（middleware / tool）不在页面上创建，这里退一步，
     * 用"能改不能删"把风险面收窄。
     */
    private Boolean builtin;

    private Integer sortOrder;

    private Long createdAtMs;

    private Long updatedAtMs;
}