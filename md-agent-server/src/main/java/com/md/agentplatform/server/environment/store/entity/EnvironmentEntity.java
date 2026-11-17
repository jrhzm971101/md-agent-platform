package com.md.agentplatform.server.environment.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_environment} 的行模型 —— 一个环境一行。
 *
 * <h2>为什么 env_code 与 display_name 分开</h2>
 * {@code env_code}（{@code cameroon-uat}）是<b>装配期的解析键</b>：工具方法的 {@code environment}
 * 参数、{@code md_agent_workspace.environment} 列填的都是它，改一次等于所有引用它的地方一起失效。
 * {@code display_name} 只是给人看的。两者挂在一列上，就会出现"为了改个显示名，得批量改 agent 配置"
 * 这种荒唐需求（同因不同果于 {@code ModelEntity.modelKey / displayName} 那一对）。
 */
@Getter
@Setter
@TableName("md_environment")
public class EnvironmentEntity {

    /** 环境标识；工具 {@code environment} 参数填它。 */
    @TableId(value = "env_code", type = IdType.INPUT)
    private String envCode;

    /** 展示名。页面标题、下拉选项用它。 */
    private String displayName;

    private String description;

    /**
     * 是否启用。
     *
     * <p>停用只影响"页面上还能不能选它"，<b>不会</b>删掉配置、也不会让已引用它的 agent 装配失败 ——
     * 停用一个正在被使用的环境属于运维动作，不该顺带把别人的 agent 打挂。
     */
    private Boolean enabled;

    private Integer sortOrder;

    private Long createdAtMs;

    private Long updatedAtMs;

    private String createdBy;

    private String updatedBy;
}
