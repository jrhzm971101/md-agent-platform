package com.md.agentplatform.server.session;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话视图的只读查询 —— 全部打在 {@code md_agent_run} 这一张表上。
 *
 * <h2>为什么不复用 {@code AgentRunMapper}</h2>
 * 那个 Mapper 属于链路归档（写路径 + 保留期清理），这里属于会话聚合（纯读、另一个页面）。
 * 混在一起会让"改归档的列"与"改页面要的口径"变成必须互相确认的两件事。
 * 同一张表被两个用途各持一个 Mapper 是正常的：它们读的是同一份数据、回答的是不同问题。
 *
 * <h2>为什么聚合不做分页过滤，全量取回来在服务层筛</h2>
 * 与状态存储页同一个取舍（见 {@code AgentStateMapper.listSessions} 的注释）：
 * 筛选维度有五个（agent / 来源 / 时间窗 / 关键字 / 有没有记忆），其中最后一个
 * （有没有记忆）<b>物理上在另一个库</b>，SQL 里根本表达不出来 ——
 * 既然最终一定要回到内存里过一遍，那就在一个地方过完，顺便还能给出精确的总数。
 * 代价是每次都扫一遍 run 表（走 {@code idx_user_session}），
 * 会话数与 run 数都在"每次调用几行"的量级，这个代价远小于为它拼五套动态 SQL。
 */
@Mapper
public interface AgentSessionMapper {

    /**
     * 全部会话，按最近活动倒序。
     *
     * <p><b>分组键必须是 (user_id, session_id) 两列</b>，不能只按 session_id：
     * sessionId 由入口方自己算，不同用户/不同群聊完全可能撞出同一个串
     * （群聊用 chatId、单聊用 userId，但页面对话用随机短 id，跨会话撞车在理论上是可能的）。
     * 按两列分组才有确定语义。
     *
     * <p>{@code SUM(CASE WHEN ... THEN 1 ELSE 0 END)} 而不是 {@code SUM(status = 'ERROR')}：
     * 后者依赖 MySQL 把布尔当 1/0 的隐式转换，读的人需要多想一步。
     *
     * <p>{@code GROUP_CONCAT(DISTINCT ... ORDER BY ...)} 里的 {@code ORDER BY} 是必要的：
     * 不加的话拼接顺序由执行计划决定，"同一个会话两次打开显示的顺序不一样"会让人以为数据变了。
     * 注意 GROUP_CONCAT 有 {@code group_concat_max_len}（默认 1024 字节）截断风险 ——
     * 一个会话最多被几个 agent 服务，实际远达不到；真撞上了只会让这一列少几个名字，
     * 不影响其它字段与筛选（服务层按 split 后的列表判断，不会因为截断把错的行放进来）。
     */
    @Select("SELECT r.user_id AS userId, r.session_id AS sessionId,"
            + " COUNT(*) AS runs,"
            + " SUM(CASE WHEN r.status = 'ERROR' THEN 1 ELSE 0 END) AS failed,"
            + " COALESCE(SUM(r.input_tokens + r.output_tokens), 0) AS tokens,"
            + " GROUP_CONCAT(DISTINCT r.agent_name ORDER BY r.agent_name) AS agents,"
            + " GROUP_CONCAT(DISTINCT r.source ORDER BY r.source) AS sources,"
            + " MIN(r.started_at_ms) AS firstMs,"
            + " MAX(r.started_at_ms) AS lastMs"
            + " FROM md_agent_run r"
            + " WHERE r.session_id IS NOT NULL AND r.session_id <> ''"
            + " GROUP BY r.user_id, r.session_id"
            + " ORDER BY lastMs DESC")
    List<SessionAggregateRow> aggregateSessions();

    /**
     * 一个会话下的全部执行，新的在前。
     *
     * <p>{@code user_id <=> #{userId}} 是 MySQL 的 NULL 安全等值：本项目里 user_id 可能为 NULL
     * （老数据、非渠道入口），用 {@code =} 的话 {@code NULL = NULL} 结果是 NULL 而不是 TRUE，
     * 那批会话的详情会永远查不出东西 —— 且现象是"列表有、点进去空的"，很难联系到运算符语义上。
     *
     * <p>{@code LIMIT} 由调用方给定（服务层夹过上限）：会话是只增的，
     * 一个跑了一年的定时任务会话可能有几千次执行，全部返回没有意义。
     */
    @Select("SELECT r.run_id AS runId, r.agent_name AS agentId, r.agent_kind AS agentKind,"
            + " r.source AS source, r.status AS status,"
            + " r.started_at_ms AS startedAtMs, r.ended_at_ms AS endedAtMs,"
            + " (r.input_tokens + r.output_tokens) AS tokens, r.error AS error"
            + " FROM md_agent_run r"
            + " WHERE r.session_id = #{sessionId} AND r.user_id <=> #{userId}"
            + " ORDER BY r.started_at_ms DESC, r.run_id DESC"
            + " LIMIT #{limit}")
    List<SessionRunRow> listRuns(@Param("userId") String userId,
                                 @Param("sessionId") String sessionId,
                                 @Param("limit") int limit);
}
