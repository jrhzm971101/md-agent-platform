package com.md.agentplatform.server.workspace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 「这个 agent 被哪些身份用过」的只读查询 —— 工作区页「隔离维度」下拉框的数据源。
 *
 * <h2>一、候选值为什么不从工作区 KV 里扫</h2>
 * 工作区在 REMOTE 模式下确实是按用户分命名空间的（{@code agents/<id>/users/<uid>/...}），
 * 看上去扫一遍 KV 的 namespace_path 就能得到名单。但那样做有两个代价：
 * <ol>
 *   <li>KV 的命名空间拼接是<b>框架内部约定</b>（段间用不可见分隔符，前缀由
 *       {@code IsolationScope.USER} 决定）。页面的下拉框耦合上去之后，框架调整一次
 *       隔离策略，这里就要跟着改，而且改错了不报错 —— 只会静静地少列几个人。</li>
 *   <li>框架只给了 {@code BaseStore.search(namespace, ...)}，它按命名空间前缀过滤，
 *       但返回的 {@code StoreItem} <b>只有 itemKey，没有 namespace</b>，
 *       拿不到"这一行属于哪个用户"。</li>
 * </ol>
 * 而 {@code md_agent_run} 天然带着 {@code user_id}，与「会话记录」页同源，
 * 且语义更贴近这个下拉框要回答的问题：<b>谁用过这个 agent</b>。
 *
 * <h2>二、为什么用 {@code COUNT(*)} 而不是 {@code COUNT(DISTINCT session_id)}</h2>
 * 这里只想给下拉框一个规模感（"这个身份是真在用还是留了一条历史"），
 * 次数比会话数更稳定：同一 session 续聊不会让次数失真到看不出量级。
 *
 * <h2>三、为什么在 SQL 里就滤掉空 user_id</h2>
 * 库里确实有 {@code user_id} 为 NULL 的 run（老数据、非渠道入口），
 * 它们是"没有隔离维度"的记录，对应页面上的<b>基线层</b> —— 那是一个固定选项，
 * 不该在下拉框里再出现一次空条目。所以在 SQL 里过滤，而不是在服务层跳过。
 */
@Mapper
public interface WorkspaceUserMapper {

    /**
     * 一个 agent 用过的全部隔离维度，最近活跃的在前。
     *
     * <p>{@code ORDER BY lastMs DESC}：下拉框默认展开时，最上面应该是还在用的那个身份。
     */
    @Select("SELECT r.user_id AS userId,"
            + " COUNT(*) AS runs,"
            + " MAX(r.started_at_ms) AS lastMs"
            + " FROM md_agent_run r"
            + " WHERE r.agent_name = #{agentName}"
            + " AND r.user_id IS NOT NULL AND r.user_id <> ''"
            + " GROUP BY r.user_id"
            + " ORDER BY lastMs DESC")
    List<WorkspaceUserRow> listUsers(@Param("agentName") String agentName);
}