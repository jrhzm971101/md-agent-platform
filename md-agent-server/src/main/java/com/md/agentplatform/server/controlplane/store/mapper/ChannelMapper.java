package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * `ChannelEntity` 的 Mapper —— 渠道主表。
 *
 * <p>`@Mapper` 让 MyBatis 自动配置扫描到它（扫描范围 = 启动类所在包 `com.md.agentplatform`
 * 及其子包），不需要在启动类上写 `@MapperScan` —— 与其它 store mapper 的写法一致。
 */
@Mapper
public interface ChannelMapper extends BaseMapper<ChannelEntity> {
}
