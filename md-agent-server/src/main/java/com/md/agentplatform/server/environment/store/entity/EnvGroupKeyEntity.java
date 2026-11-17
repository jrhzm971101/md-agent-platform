package com.md.agentplatform.server.environment.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_env_group_key} 的行模型 —— 一行 = 表单上的一个字段。
 *
 * <h2>一、这张表是「声明」不是「数据」</h2>
 * 它说的是"k8s 这一组应该有 url 和 token，其中 token 是凭据且必填"，
 * 而不是"cameroon-uat 的 k8s token 是 xxx"。后者的落点是 {@code md_env_config}。
 * 分开的理由：声明是**稀有的、跨环境共享的**（8 个分组一共几十行），
 * 数据是**每个环境都有一份的**（9 × 20 ≈ 180 行）。混在一起就得为每个环境重复声明一遍，
 * 而"给所有环境加一个可配置项"会变成 9 次操作。
 *
 * <h2>二、key_code 与强类型 POJO 字段名逐字一致</h2>
 * 见 {@code env-schema.sql} 文件头第三节：这样回投影与报错信息都零翻译。
 *
 * <h2>三、required 只影响完整度，不阻断保存</h2>
 * 调试期新建一个环境时，凭据往往还拿不到。如果 required 能阻断保存，就必须"先凑齐再存"，
 * 结果是把值填在别处（记事本 / 聊天记录）再回来 —— 那正是本页面要消灭的东西。
 * 所以：可以存半成品，页面上如实显示"还缺 3 个必填项"。
 */
@Getter
@Setter
@TableName("md_env_group_key")
public class EnvGroupKeyEntity {

    /** 自增主键。键可以删了再加，没有天然稳定业务键，不虚构一个。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String groupCode;

    /**
     * 键名，如 {@code serverAddr}。
     *
     * <p>与 {@code EnvironmentProperties} 里 POJO 的字段名逐字一致，强类型投影靠这一点零映射。
     */
    private String keyCode;

    /** 表单上的字段名（中文）。 */
    private String label;

    /** {@code STRING} | {@code INT} | {@code BOOL}；决定表单控件与保存时的格式校验。 */
    private String valueType;

    /** 1 = 缺失计入完整度缺口。 */
    private Boolean required;

    /** 1 = 凭据：列表视图只显示有无，不参与关键字检索。 */
    private Boolean secret;

    private String placeholder;

    /** 示例值。只展示，<b>绝不</b>被当成默认值写进配置行。 */
    private String exampleValue;

    private String description;

    private Integer sortOrder;

    private Long createdAtMs;

    private Long updatedAtMs;
}
