package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;
import com.md.agentplatform.server.model.ModelAgentCountRow;
import com.md.agentplatform.server.model.ModelUsageRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code md_model} 的 Mapper —— 常规 CRUD 走 {@link BaseMapper}，这里只放两条"跨表"的查询。
 *
 * <h2>一、为什么引用反查是一条 UNION 而不是四次查询</h2>
 * 一个"模型引用"落在<b>四个</b>地方，它们分散在四张表里（写法如下），
 * 而且彼此之间没有"唯一权威"（主模型、兜底模型、子 agent 覆写、记忆/压缩模型是四个独立旋钮）。
 * 分开查就是四次往返 + Java 里手工去重；UNION + DISTINCT 交给数据库一次做完，
 * 结果集也天然是"去重后的 agent 名列表"。
 * <ul>
 *   <li>{@code md_agent_runtime.model_ref / fallback_model_ref} —— 主模型与兜底模型</li>
 *   <li>{@code md_agent_sub_agent.model_ref} —— 子 agent 覆写（一个子 agent 一行）</li>
 *   <li>{@code md_agent_memory.compaction_model_ref / memory_model_ref} —— 压缩与记忆整理</li>
 * </ul>
 * 漏掉后三处会让删除保护失效：页面上显示"没有 agent 引用它"，删完却有 agent 装配不出来。
 *
 * <h2>二、token 统计为什么要 join 到运行时表</h2>
 * {@code md_agent_run} 记的是"哪个 agent 跑了多少次、花了多少 token"，
 * 而"这个 agent 用的是哪个模型"在 {@code md_agent_runtime.model_ref} 里。
 * 两者都在本库、都能按 agent_name 关联，所以这条 join 是廉价的；
 * 反过来如果按模型名去 run 表里找，就得先在应用层把 agent 列表拉出来 —— 那才是 N+1。
 *
 * <p>{@code model_ref} 为 NULL 的 agent 会聚成一行（modelKey = null）：那是"用平台兜底模型"的
 * 语义，服务层会把它归到默认模型名下；若平台没有默认模型（走 yml 兜底 Bean），
 * 这批用量就<b>无法归属到任何模型行</b>，服务层如实不显示，而不是编一个名字。
 */
@Mapper
public interface ModelMapper extends BaseMapper<ModelEntity> {

    /** 引用了该模型的 agent 名列表（删除模型前的保护检查）。 */
    @Select("SELECT DISTINCT agent_name FROM ("
            + " SELECT agent_name FROM md_agent_runtime WHERE model_ref = #{modelKey} OR fallback_model_ref = #{modelKey}"
            + " UNION ALL SELECT agent_name FROM md_agent_sub_agent WHERE model_ref = #{modelKey}"
            + " UNION ALL SELECT agent_name FROM md_agent_memory"
            + "   WHERE compaction_model_ref = #{modelKey} OR memory_model_ref = #{modelKey}"
            + ") t")
    List<String> findAgentNamesByModelKey(@Param("modelKey") String modelKey);

    /**
     * 自 {@code sinceMs} 起，按「agent 声明的模型」聚合 token 与运行次数。
     *
     * @return 每个 model_ref 一行；model_ref 为 NULL 的行代表"用平台兜底模型"的那批 agent
     */
    @Select("SELECT rt.model_ref AS modelKey,"
            + " COUNT(*) AS runs,"
            + " COALESCE(SUM(r.input_tokens + r.output_tokens), 0) AS tokens"
            + " FROM md_agent_run r"
            + " JOIN md_agent_runtime rt ON rt.agent_name = r.agent_name"
            + " WHERE r.started_at_ms >= #{sinceMs}"
            + " GROUP BY rt.model_ref")
    List<ModelUsageRow> usageSince(@Param("sinceMs") long sinceMs);

    /**
     * 没有声明 {@code model_ref} 的 agent 名（也就是"用平台兜底模型"的那批）。
     *
     * <p>它只为默认模型的热装配服务：默认模型换了端点，这批 agent 的实例也是旧的，
     * 而它们<b>不出现在</b>引用反查的结果里（那个查询找的是"谁写了 model_ref = X"）。
     * 漏掉它们的表现是"改了默认模型，一半 agent 生效、一半还在连老地址"。
     */
    @Select("SELECT agent_name FROM md_agent_runtime WHERE model_ref IS NULL OR model_ref = ''")
    List<String> findAgentsWithoutModelRef();

    /**
     * 全部模型的引用方数量（列表页一次拿全，避免逐行查询的 N+1）。
     *
     * <p>内层五个 SELECT 覆盖"一个模型引用可能落地的全部位置"（与 {@link #findAgentNamesByModelKey}
     * 同源）；外层用 {@code LEFT JOIN} 而不是 {@code JOIN}，是为了让<b>零引用</b>的模型也出现在结果里
     * —— 用 JOIN 的话新加一个还没被引用的模型会直接从统计里消失，页面上那一格永远是空。
     * {@code COUNT(DISTINCT ...)}：同一行 UNION 出来的重复引用（例如 model_ref 与
     * compaction_model_ref 填了同一个模型）只算一个 agent。
     */
    @Select("SELECT m.model_key AS modelKey, COUNT(DISTINCT t.agent_name) AS agentCount"
            + " FROM md_model m"
            + " LEFT JOIN ("
            + "   SELECT agent_name, model_ref AS ref FROM md_agent_runtime"
            + "   UNION ALL SELECT agent_name, fallback_model_ref FROM md_agent_runtime"
            + "   UNION ALL SELECT agent_name, model_ref FROM md_agent_sub_agent"
            + "   UNION ALL SELECT agent_name, compaction_model_ref FROM md_agent_memory"
            + "   UNION ALL SELECT agent_name, memory_model_ref FROM md_agent_memory"
            + " ) t ON t.ref = m.model_key"
            + " GROUP BY m.model_key")
    List<ModelAgentCountRow> agentCounts();
}
