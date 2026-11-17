package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `PromptEntity` 的 Mapper —— prompt 表。删除保护用的引用反查在 AgentRefMapper 上（引用关系存在那边），本 Mapper 只管 prompt 自身。
 *
 * <p>`@Mapper` 让 MyBatis 自动配置扫描到它（扫描范围 = 启动类所在包 `com.md.agentplatform`
 * 及其子包），因此不需要在启动类上写 `@MapperScan` —— 与 `trace/store/mapper` 下的写法一致。
 */
@Mapper
public interface PromptMapper extends BaseMapper<PromptEntity> {
}