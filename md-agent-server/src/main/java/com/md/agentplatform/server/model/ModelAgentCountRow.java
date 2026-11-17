package com.md.agentplatform.server.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 「一个模型被多少个 agent 引用」的聚合结果 —— 映射模型，字段跟着
 * {@code ModelMapper.agentCounts} 的 SQL 走。
 *
 * <p>单独用一条 SQL 一次算完全部模型的引用数，而不是"每个模型查一次"：
 * 列表页一次要显示全部模型，逐行查是典型的 N+1；而这条聚合本身只是一次 UNION + GROUP BY，
 * 在几十个 agent 的量级上代价可以忽略。
 */
@Getter
@Setter
public class ModelAgentCountRow {

    private String modelKey;

    /** 去重后的引用方数量。 */
    private int agentCount;
}
