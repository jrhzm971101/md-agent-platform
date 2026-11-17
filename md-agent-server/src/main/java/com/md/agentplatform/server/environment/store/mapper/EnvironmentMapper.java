package com.md.agentplatform.server.environment.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code md_environment} 的 Mapper。
 *
 * <p>没有扩展方法：列表、按主键取、插入、更新、删除都是 {@link BaseMapper} 的声明式能力，
 * 手写 SQL 只会多一份需要同步维护的拼写。
 *
 * <p>{@code @Mapper} 让 MyBatis 自动配置扫到它（扫描范围 = 启动类所在包
 * {@code com.md.agentplatform} 及其子包），因此不需要在启动类上再写 {@code @MapperScan}
 *（同 {@code AgentRunMapper} 的约定）。
 */
@Mapper
public interface EnvironmentMapper extends BaseMapper<EnvironmentEntity> {
}