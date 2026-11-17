package com.md.agentplatform.server.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 「一个模型一行」的用量聚合结果 —— 映射模型，字段跟着 {@code ModelMapper.usageSince} 的 SQL 走。
 *
 * <p>用 {@code @Getter/@Setter} 的可变类而不是 record：MyBatis 通过反射 setter 组装行，
 * record 没有无参构造，要额外配 {@code @ConstructorArgs} 才能映射（同 {@code SessionAggregateRow}）。
 *
 * <p>{@code modelKey} 允许为 null（那批 agent 没声明 model_ref），所以是包装类型的 String，
 * 不是"查漏了"。
 */
@Getter
@Setter
public class ModelUsageRow {

    /** 被引用的模型键；null = 该 agent 用平台兜底模型。 */
    private String modelKey;

    /** 时间窗内的执行次数。 */
    private long runs;

    /** 时间窗内的 token 合计（输入 + 输出）。 */
    private long tokens;
}
