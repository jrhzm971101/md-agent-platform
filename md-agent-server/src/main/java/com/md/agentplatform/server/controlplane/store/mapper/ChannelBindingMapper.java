package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.ChannelBindingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * `ChannelBindingEntity` 的 Mapper —— 路由绑定表，除 CRUD 外承担<b>按 agent 反查</b>。
 *
 * <h2>反查为什么必须存在</h2>
 * 删除一个 agent 之前要问一句「还有渠道绑着它吗」。答案若是"有"，删掉之后那条绑定就成了
 * 悬空规则：消息进来路由到一个不存在的 agent，报错点在 {@code AgentManager.stream}，
 * 而真正的原因（有人把 agent 删了）离现场很远。所以这条反查是删除保护的依据。
 *
 * <h2>为什么只回 channel_id 列表而不是实体</h2>
 * 与 AgentRefMapper 同一个理由：本 Mapper 上自定义的 {@code @Select} 返回实体时，
 * JSON 列（roles）的结果映射不会被 MyBatis-Plus 的 autoResultMap 接管，
 * 会退化成"行能查出来、roles 恒为 null"。这里只需要渠道名，用不着实体。
 */
@Mapper
public interface ChannelBindingMapper extends BaseMapper<ChannelBindingEntity> {

    /** 绑定了该 agent 的渠道 id 列表（删除 agent 前的保护检查），去重。 */
    @Select("SELECT DISTINCT channel_id FROM md_channel_binding WHERE agent_id = #{agentId}")
    List<String> findChannelIdsByAgentId(@Param("agentId") String agentId);
}
