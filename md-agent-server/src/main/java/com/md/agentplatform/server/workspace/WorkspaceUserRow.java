package com.md.agentplatform.server.workspace;

import lombok.Getter;
import lombok.Setter;

/**
 * 「一个隔离维度一行」的聚合结果 —— 映射模型，字段跟着 SQL 走。
 *
 * <h2>为什么与输出模型 {@link WorkspaceViews.UserRow} 分开</h2>
 * 这一层直接对应 {@code md_agent_run} 的列（{@code user_id} / 次数 / 最近时刻），
 * 输出模型是页面的形状。两者现在字段一样，也不合并：合并之后，
 * 「给下拉框多加一个字段（比如来源 source）」就会连带改到 SQL 映射的契约上。
 *
 * <p>与 {@code SessionAggregateRow} 的取舍一致：用可变的 {@code @Getter/@Setter}
 * 类而不是 record，因为 MyBatis 靠反射 setter 组装行，record 没有无参构造，
 * 要额外配 {@code @ConstructorArgs} 才能映射。
 */
@Getter
@Setter
public class WorkspaceUserRow {

    /** 隔离维度：工作区命名空间 {@code agents/<id>/users/<uid>} 里的那一段。 */
    private String userId;

    /** 这个身份在这个 agent 上跑过多少次（= run 行数）。 */
    private long runs;

    /** 最近一次执行的开始时刻（epoch millis）；下拉框用它排"还在用"与"历史"。 */
    private long lastMs;
}