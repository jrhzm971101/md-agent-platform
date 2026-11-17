package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.WorkflowEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code md_workflow} 的 Mapper。
 *
 * <p>只走 {@link BaseMapper} 的默认实现 —— 列表 / 按主键查 / 插入 / 更新 / 删除都是声明式的，
 * 这里没有任何需要手写 SQL 的查询（"最近改过什么"直接按 updated_at_ms 排序即可）。
 *
 * <p>{@code @Mapper} 让 MyBatis 自动配置扫描到它（扫描范围 = 启动类所在包
 * {@code com.md.agentplatform} 及其子包），因此不需要在启动类上写 {@code @MapperScan}。
 */
@Mapper
public interface WorkflowMapper extends BaseMapper<WorkflowEntity> {
}