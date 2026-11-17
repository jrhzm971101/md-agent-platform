package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.AgentSubAgentEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code md_agent_sub_agent} 的 Mapper。
 *
 * <h2>为什么只有删除是自定义 SQL</h2>
 * 查询用 {@code BaseMapper} 的 Wrapper 表达（{@code eq(agentName).orderByAsc(sortOrder)}），
 * 因为本表的 {@code headers} / {@code remote_context_attributes} 是 JSON 列，
 * 而只有框架自带的查询路径会自动套用 {@code autoResultMap} 生成的 resultMap。
 * 自定义 {@code @Select} 返回实体时，JSON 列会读成 null —— 存储层的注释里已说明这个坑，
 * 这里不再重复踩一次。
 *
 * <p>批量删除之所以单独写一条，是因为"保存 agent 时先清空它的子 agent 再插入当前列表"
 * 是每次保存都会走的路径（见 {@code MyBatisControlPlaneStore#saveAgent}），
 * 用 Wrapper 写也行，但语义这么重要的一句话值得有一条自己的 SQL 和一条自己的注释。
 */
@Mapper
public interface AgentSubAgentMapper extends BaseMapper<AgentSubAgentEntity> {

    /** 删除某个父 agent 的全部子 agent 声明，返回删除行数。 */
    @Delete("DELETE FROM md_agent_sub_agent WHERE agent_name = #{agentName}")
    int deleteByAgentName(@Param("agentName") String agentName);

    /**
     * 引用了该 agent 作为子 agent 的父 agent 名列表（删除 agent 前的保护检查）。
     *
     * <p>{@code DISTINCT} 不能省：同一个父 agent 若在两行子 agent 声明里都写了它，
     * 保护提示会把同一个名字列两遍，页面看起来像有两个引用方。
     */
    @Select("SELECT DISTINCT agent_name FROM md_agent_sub_agent WHERE sub_name = #{subName}")
    List<String> findAgentNamesBySubName(@Param("subName") String subName);
}
