package com.md.agentplatform.server.state;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 状态表的只读查询 + 会话删除。
 *
 * <h2>一、为什么这里必须写注解 SQL，不能用 {@code BaseMapper}</h2>
 * 状态表的表名是<b>数据</b>（{@code md_agent_runtime.state_table}，一个 agent 一张表），
 * 而 MyBatis-Plus 的 {@code @TableName} 是编译期常量 —— 一个实体类只能对一张表。
 * 想看"别的 agent 的状态"就必须把表名当参数传进 SQL，于是只能用 {@code ${}} 拼接。
 * （另一条路是 {@code DynamicTableNameInnerInterceptor}，但它要独占一个
 * {@code MybatisPlusInterceptor}，为三五个只读查询再搭一套 SqlSessionFactory 不划算。）
 *
 * <h2>二、{@code ${}} 的注入风险由服务层挡</h2>
 * {@code ${}} 是字符串直替，本身就是一条注入路径。两个闸门都在
 * {@link AgentStateAdminService}：
 * <ol>
 *   <li>表名白名单正则；</li>
 *   <li>表名必须在 {@code information_schema} 里真实存在。</li>
 * </ol>
 * 两条都过才拼进 SQL。库名来自配置（{@code agent-platform.state-store-database}），
 * 不是用户输入。
 *
 * <h2>三、跨库查询为什么不需要第二个 DataSource</h2>
 * 状态库与控制面库在<b>同一个 MySQL 实例</b>上，SQL 里写全限定名 {@code `库`.`表`}
 * 就可以直接连表 —— 同实例跨库是同一个连接、同一个本地事务，没有分布式事务问题。
 * 注意下面那段查 {@code md_agent_runtime} 的 SQL <b>没有</b>写库名：它靠连接的默认库
 * （即业务库）解析，这样"业务库叫什么"不需要在代码里再写死一遍。
 */
@Mapper
public interface AgentStateMapper {

    /**
     * 目标库下这张表是否存在。
     *
     * <p>这既是"表存在性"判断，也是 {@code ${table}} 的安全闸门 ——
     * 服务层在拼任何动态 SQL 之前都会先走一次它。
     */
    @Select("SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = #{database} AND table_name = #{table} "
            + "AND table_type = 'BASE TABLE'")
    int countTable(@Param("database") String database, @Param("table") String table);

    /**
     * 库里真实存在的状态表，并顺带把"哪个 agent 声明了它"查出来。
     *
     * <p>关联条件是 {@code COALESCE(NULLIF(state_table,''), agent_name)} ——
     * 与装配期 {@code ConfigurableAgentBuilder.resolveStateStore} 选表名的规则<b>必须一致</b>，
     * 否则页面会把一张正在被使用的表标成"孤儿"。两处都写成这个表达式是刻意的：
     * 它比"抽一个 SQL 片段常量"更能让人在改一处时看见另一处。
     */
    @Select("SELECT t.table_name AS tableName,"
            + " (SELECT r.agent_name FROM md_agent_runtime r"
            + "   WHERE r.state_store IS NOT NULL"
            + "     AND COALESCE(NULLIF(r.state_table, ''), r.agent_name) = t.table_name"
            + "   LIMIT 1) AS agentName"
            + " FROM information_schema.tables t"
            + " WHERE t.table_schema = #{database} AND t.table_type = 'BASE TABLE'"
            + " ORDER BY t.table_name")
    List<StateTableRow> listExistingTables(@Param("database") String database);

    /**
     * 声明了状态存储、但库里还没有对应表的 agent。
     *
     * <p>页面必须能看见这批：框架是"第一次真正用到时才建表"，所以刚配好的 agent
     * 在页面上会缺席。没有这行记录，用户会以为配置没保存成功。
     */
    @Select("SELECT r.agent_name AS agentName,"
            + " COALESCE(NULLIF(r.state_table, ''), r.agent_name) AS tableName"
            + " FROM md_agent_runtime r"
            + " WHERE r.state_store IS NOT NULL"
            + "   AND NOT EXISTS (SELECT 1 FROM information_schema.tables t"
            + "     WHERE t.table_schema = #{database}"
            + "       AND t.table_name = COALESCE(NULLIF(r.state_table, ''), r.agent_name))")
    List<StateTableRow> listDeclaredMissingTables(@Param("database") String database);

    /**
     * 单表统计：行数 / 字节数 / 最近写入时间。
     *
     * <p>{@code SUM(LENGTH(state_data))} 而不是 {@code information_schema.data_length}：
     * 后者是 InnoDB 的估算值（实测同一张表估 19 行、实际 21 行），而 LENGTH 在 InnoDB 里
     * 可以直接从行外大字段的长度前缀读出，不需要真读整块 LONGTEXT，代价很低。
     *
     * <p>{@code rows} 必须加反引号：它是 MySQL 的保留字（窗口函数 {@code ROWS BETWEEN ...}）。
     * 不加会直接语法报错 —— 这条是实测出来的，不是防御性写法。
     */
    @Select("SELECT COUNT(*) AS `rows`, SUM(LENGTH(state_data)) AS bytes, MAX(updated_at) AS updatedAt"
            + " FROM `${database}`.`${table}`")
    StateSlotRow tableStats(@Param("database") String database, @Param("table") String table);

    /** 该表里的会话数（去重后的 session_id 个数）。 */
    @Select("SELECT COUNT(DISTINCT session_id) FROM `${database}`.`${table}`")
    long countSessions(@Param("database") String database, @Param("table") String table);

    /**
     * 该表里出现过的全部 slotId（去重）。
     *
     * <p>「会话记录」页用它回答"这个会话还有没有记忆"：一个 slotId 就是一行
     * {@code <userId>:<sessionId>}，在不在就是有没有。刻意只取这一列、不取 state_data ——
     * 为了判空把 MB 级的正文拉出来，是这一页最不该犯的错（连详情页都只为它读过 LENGTH）。
     */
    @Select("SELECT DISTINCT session_id FROM `${database}`.`${table}`")
    List<String> listSlotIds(@Param("database") String database, @Param("table") String table);

    /**
     * 按会话聚合的列表。
     *
     * <p>刻意<b>不</b>分页、也<b>不</b>查 state_data：
     * <ul>
     *   <li>不分页：一张表里的会话数是"几十"量级（实测最多 35），全取回来在服务层过滤/分页，
     *       比为了关键字模糊匹配去拼动态 SQL 简单得多，且页面能一次拿到准确总数；</li>
     *   <li>不查 state_data：只取 {@code LENGTH(state_data)}。带上大字段的话，
     *       一页 35 个会话就是 48 MB 出库。</li>
     * </ul>
     */
    @Select("SELECT session_id AS slotId, COUNT(*) AS `rows`, SUM(LENGTH(state_data)) AS bytes,"
            + " MAX(version) AS version, MAX(updated_at) AS updatedAt"
            + " FROM `${database}`.`${table}` GROUP BY session_id"
            + " ORDER BY MAX(updated_at) DESC")
    List<StateSlotRow> listSessions(@Param("database") String database, @Param("table") String table);

    /** 一个会话下的全部槽位（state_key × item_index），只取元信息。 */
    @Select("SELECT session_id AS slotId, state_key AS stateKey, item_index AS itemIndex,"
            + " LENGTH(state_data) AS bytes, version, created_at AS createdAt, updated_at AS updatedAt"
            + " FROM `${database}`.`${table}` WHERE session_id = #{slotId}"
            + " ORDER BY state_key, item_index")
    List<StateSlotRow> selectSessionSlots(@Param("database") String database,
                                         @Param("table") String table,
                                         @Param("slotId") String slotId);

    /** 单个槽位的原始 JSON。 */
    @Select("SELECT state_data FROM `${database}`.`${table}`"
            + " WHERE session_id = #{slotId} AND state_key = #{stateKey} AND item_index = #{itemIndex}")
    String selectStateData(@Param("database") String database,
                          @Param("table") String table,
                          @Param("slotId") String slotId,
                          @Param("stateKey") String stateKey,
                          @Param("itemIndex") int itemIndex);

    /**
     * 删除一个会话的全部状态行。
     *
     * <p>删的是 {@code session_id} 这一列的全部匹配行 —— 无论它下面挂了几个
     * {@code state_key} / {@code item_index}。语义是"把这个会话的记忆清空"，
     * 而不是"删某一行"。
     *
     * <p><b>只删状态，不碰观测数据</b>：调用链路（{@code md_agent_run} /
     * {@code md_agent_run_event}）与状态是两套数据、两种用途 ——
     * 前者的价值恰恰在于"上下文已经没了之后还能回看当时发生了什么"。
     */
    @Delete("DELETE FROM `${database}`.`${table}` WHERE session_id = #{slotId}")
    int deleteSession(@Param("database") String database,
                      @Param("table") String table,
                      @Param("slotId") String slotId);
}
