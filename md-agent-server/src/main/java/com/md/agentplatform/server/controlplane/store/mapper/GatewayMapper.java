package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.GatewayEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `GatewayEntity` 的 Mapper —— {@code md_gateway} 单行表。
 *
 * <p>只有 selectById(1) / updateById 两种用法，没有自定义 SQL：
 * 单行表不需要列表查询，也不需要任何条件筛选。
 */
@Mapper
public interface GatewayMapper extends BaseMapper<GatewayEntity> {
}
