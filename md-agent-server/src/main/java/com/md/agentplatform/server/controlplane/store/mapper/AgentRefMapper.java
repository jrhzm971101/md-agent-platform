package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.AgentRefEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code md_agent_ref} 的 Mapper —— 除了常规 CRUD，还承担<b>引用反查</b>。
 *
 * <h2>一、反向查询为什么放在 SQL 里而不是"全表捞出来在 Java 里过滤"</h2>
 * 这两种写法在本项目的规模下都正确，但 SQL 版有两个好处：
 * <ul>
 *   <li>不需要把整张引用表读进内存。表本身很小（每个 agent 一行），但"很小"是<b>当下的</b>事实，
 *       写死在代码里就成了隐含假设；</li>
 *   <li>删除保护要求"拒绝删除并列出使用方"，这条路径在页面上会频繁触发（每次点删除都查），
 *       让它走一条能用到索引/能在 SQL 层收窄的查询，比每次都传输全表更稳。</li>
 * </ul>
 *
 * <h2>二、JSON 数组的成员查询：JSON_CONTAINS + JSON_QUOTE</h2>
 * {@code skill_names} / {@code mcp_names} 是 JSON 数组列，用
 * {@code JSON_CONTAINS(col, JSON_QUOTE(#{name}))} 判断成员。
 * 用 {@code JSON_QUOTE} 而不是在字符串里手工拼引号（{@code '"' + name + '"'}）有两个原因：
 * <ul>
 *   <li><b>安全</b>：手工拼接会把参数值直接塞进 SQL 文本，名字里出现引号就能改写语句；</li>
 *   <li><b>正确</b>：JSON_QUOTE 会按 JSON 规则转义，名字里含反斜杠或引号时仍能匹配上。</li>
 * </ul>
 * 另外 {@code JSON_CONTAINS} 对 NULL 列返回 NULL（不匹配），所以不需要额外写
 * {@code col IS NOT NULL} —— 但这也意味着<b>空数组与 NULL 都不会被匹配到</b>，
 * 这正是我们要的语义（没引用就不算使用方）。
 *
 * <h2>三、这三个方法都只返回 String 列表，不返回实体</h2>
 * 刻意如此：本 Mapper 上的自定义 {@code @Select} 若返回<b>实体</b>，
 * JSON 列的结果映射不会被 MyBatis-Plus 的 autoResultMap 自动接管
 * （那套 resultMap 只注入到框架自己的 selectList/selectById 等路径），
 * 于是会退化成"能查出行、但 JSON 列恒为 null"这个极难定位的问题。
 * 需要实体时一律走 {@code BaseMapper} 的 Wrapper 查询（见 MyBatisControlPlaneStore）。
 */
@Mapper
public interface AgentRefMapper extends BaseMapper<AgentRefEntity> {

    /** 引用了该 prompt 的 agent 名列表（删除 prompt 前的保护检查）。 */
    @Select("SELECT agent_name FROM md_agent_ref WHERE prompt_key = #{promptKey}")
    List<String> findAgentNamesByPromptKey(@Param("promptKey") String promptKey);

    /** 引用了该 skill 的 agent 名列表（删除 skill 前的保护检查）。 */
    @Select("SELECT agent_name FROM md_agent_ref WHERE JSON_CONTAINS(skill_names, JSON_QUOTE(#{skillName}))")
    List<String> findAgentNamesBySkillName(@Param("skillName") String skillName);

    /** 引用了该 MCP 的 agent 名列表（删除 MCP 前的保护检查）。 */
    @Select("SELECT agent_name FROM md_agent_ref WHERE JSON_CONTAINS(mcp_names, JSON_QUOTE(#{serverName}))")
    List<String> findAgentNamesByMcpName(@Param("serverName") String serverName);
}