package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.AgentWorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `AgentWorkspaceEntity` 的 Mapper —— agent 工作区表。一行对应一个 agent，没有自定义查询需求。
 *
 * <p>`@Mapper` 让 MyBatis 自动配置扫描到它（扫描范围 = 启动类所在包 `com.md.agentplatform`
 * 及其子包），因此不需要在启动类上写 `@MapperScan` —— 与 `trace/store/mapper` 下的写法一致。
 */
@Mapper
public interface AgentWorkspaceMapper extends BaseMapper<AgentWorkspaceEntity> {
}