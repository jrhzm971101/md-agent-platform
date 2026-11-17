package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `AgentEntity` 的 Mapper —— agent 主表。全部走 BaseMapper 默认方法：单行 upsert、按主键查询、列表排序都由调用方用 Wrapper 表达，手写 SQL 只会多一份需要同步维护的拼写。
 *
 * <p>`@Mapper` 让 MyBatis 自动配置扫描到它（扫描范围 = 启动类所在包 `com.md.agentplatform`
 * 及其子包），因此不需要在启动类上写 `@MapperScan` —— 与 `trace/store/mapper` 下的写法一致。
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}