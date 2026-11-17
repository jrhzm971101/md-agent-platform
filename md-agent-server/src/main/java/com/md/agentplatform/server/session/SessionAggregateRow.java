package com.md.agentplatform.server.session;

import lombok.Getter;
import lombok.Setter;

/**
 * 「一个会话一行」的聚合结果 —— 映射模型，字段跟着 SQL 走。
 *
 * <h2>为什么与输出模型分开</h2>
 * 这里的两列 {@code agents} / {@code sources} 是 {@code GROUP_CONCAT} 出来的逗号分隔串，
 * 是 SQL 的产物而不是页面的形状（页面要的是数组）。让输出模型直接带一个"需要前端自己 split"
 * 的字符串，等于把 SQL 的实现细节泄漏到接口上 —— 哪天改成 {@code JSON_ARRAYAGG}
 * 就得跟着改前端。切分发生在服务层，界面只看到数组。
 *
 * <p>用 {@code @Getter/@Setter} 的可变类而不是 record：MyBatis 通过反射 setter 组装行，
 * record 没有无参构造，要额外配 {@code @ConstructorArgs} 才能映射。
 * 这与 {@code StateSlotRow} 的取舍一致。
 */
@Getter
@Setter
public class SessionAggregateRow {

    /** 发起人。可能为 null（老数据、非渠道入口）。 */
    private String userId;

    private String sessionId;

    /** 这个会话一共跑了多少次（= run 行数）。 */
    private long runs;

    /** 其中失败的次数。 */
    private long failed;

    /** token 合计（输入 + 输出）。 */
    private long tokens;

    /** 服务过这个会话的 agent，逗号分隔、已去重排序。同一会话被多个 agent 服务是常态。 */
    private String agents;

    /** 出现过的来源，逗号分隔、已去重排序；一次都没标注过时为 null（GROUP_CONCAT 会跳过 NULL）。 */
    private String sources;

    /** 最早一次执行的开始时刻（epoch millis）。 */
    private long firstMs;

    /** 最近一次执行的开始时刻（epoch millis）。 */
    private long lastMs;
}
