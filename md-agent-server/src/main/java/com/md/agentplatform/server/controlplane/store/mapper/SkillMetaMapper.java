package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.SkillMetaEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `SkillMetaEntity` 的 Mapper —— skill 治理元数据表。注意本表不含 skill 内容 —— 内容在官方 agentscope 库，由 MysqlSkillRepository 读写。
 *
 * <p>`@Mapper` 让 MyBatis 自动配置扫描到它（扫描范围 = 启动类所在包 `com.md.agentplatform`
 * 及其子包），因此不需要在启动类上写 `@MapperScan` —— 与 `trace/store/mapper` 下的写法一致。
 */
@Mapper
public interface SkillMetaMapper extends BaseMapper<SkillMetaEntity> {
}