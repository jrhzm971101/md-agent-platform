package com.md.agentplatform.server.trace.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.trace.store.entity.AgentRunEventEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code md_agent_run_event} 的 Mapper。
 *
 * <p>批量插入不在这里声明：它由 {@code MybatisBatch} 走 {@link BaseMapper#insert} 由框架攒成
 * JDBC 批处理，手写 {@code foreach} 多值 INSERT 反而绕过了框架自己的批处理机制，
 * 且一旦某条超长会整条语句失败。见 {@code MyBatisTraceStore#appendEvents}。
 */
@Mapper
public interface AgentRunEventMapper extends BaseMapper<AgentRunEventEntity> {

    /**
     * 删除"所属 run 已过期"的事件，返回删除行数。
     *
     * <p>为什么用 JOIN 而不是先查 runId 再传进去：过期判定只依赖 run 表的 started_at_ms，
     * 一条语句交给 MySQL 一次做完，避免把可能上万个 runId 拼进 IN 子句。
     *
     * <p>顺序是"先删事件、再删 run"：反过来的话，run 行没了就再也 JOIN 不出这些孤儿事件。
     */
    @Delete("DELETE e FROM md_agent_run_event e JOIN md_agent_run r ON e.run_id = r.run_id "
            + "WHERE r.started_at_ms < #{cutoffMs}")
    int deleteByRunStartedBefore(@Param("cutoffMs") long cutoffMs);
}
