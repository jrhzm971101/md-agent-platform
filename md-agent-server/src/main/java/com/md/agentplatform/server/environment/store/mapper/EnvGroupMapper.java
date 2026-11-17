package com.md.agentplatform.server.environment.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import org.apache.ibatis.annotations.Mapper;

/** {@code md_env_group} 的 Mapper。理由同 {@link EnvironmentMapper}（无扩展方法）。 */
@Mapper
public interface EnvGroupMapper extends BaseMapper<EnvGroupEntity> {
}