package com.md.agentplatform.server.environment.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.environment.store.entity.EnvConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 旧表 {@code md_env_config} 的 Mapper。
 *
 * <p>配置值已迁到 {@code md_env_instance_config}；这里只保留“按环境找引用 agent”的查询，
 * 因为那条 SQL 原本恰好放在这个 Mapper 上，和配置行本身无关。
 */
@Mapper
public interface EnvConfigMapper extends BaseMapper<EnvConfigEntity> {

    /**
     * 引用了该环境的 agent 名列表（删除环境前的保护检查）。
     *
     * <p>引用落在 {@code md_agent_workspace.environment} 上 —— 这一列是"该 agent 在哪个环境里干活"，
     * 由 harness 装配期读取（{@code HarnessAgent.Builder.environment(...)}）。
     * 删掉一个还被引用的环境不会立刻报错，而是等到该 agent 下次跑工具时，
     * 工具拿着一个查不到的环境名去解析 —— 失败点离"我删了个环境"非常远。
     * 所以在这里挡住，并把引用方列给用户。
     *
     * <p>为什么用 {@code DISTINCT}：同一 agent 在工作区表里只有一行，理论上不会重复，
     * 但这条查询的唯一职责是"给人看的清单"，去重成本为零、收益是不会出现视觉上的重复项。
     */
    @Select("SELECT DISTINCT agent_name FROM md_agent_workspace"
            + " WHERE environment = #{envCode} AND environment <> ''")
    List<String> findAgentNamesByEnvironment(@Param("envCode") String envCode);
}
