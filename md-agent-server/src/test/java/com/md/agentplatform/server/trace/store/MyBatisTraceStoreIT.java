package com.md.agentplatform.server.trace.store;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.store.entity.AgentRunEventEntity;
import com.md.agentplatform.server.trace.store.mapper.AgentRunEventMapper;
import com.md.agentplatform.server.trace.store.mapper.AgentRunMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档存储的<b>真库</b>集成测试（需要连得到 application.yml 里那台 MySQL）。
 *
 * <h2>为什么类名以 IT 结尾</h2>
 * surefire 默认只跑 {@code *Test / Test* / *Tests / *TestCase}，{@code *IT} 不在其中 ——
 * 于是这个测试<b>不会</b>出现在 {@code mvn test} 里（别人的机器上没库也能构建通过），
 * 需要时手工执行：
 * <pre>
 * mvn -o test -Dtest=MyBatisTraceStoreIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <h2>它守的是"普通单测守不住"的那几条</h2>
 * <ol>
 *   <li><b>JSON 列能不能读回来</b>：实体上少了 {@code autoResultMap = true} 时写入正常、
 *       读取恒为 null，而纯映射单测（{@link TraceEntityMappingTest}）<b>测不出来</b>，
 *       因为那一项只在 MyBatis 组装 ResultMap 时才生效。只有真库能验。</li>
 *   <li><b>批量插入（MybatisBatch）到底能不能用</b>：它靠"实体类名"作命名空间去找已注册的语句，
 *       写错就是运行期 {@code Mapped Statements collection does not contain value for ...}。</li>
 *   <li><b>唯一键 / 列宽 / 字符集</b>：DDL 与实体对不上时（漏列、类型不符、utf8 存不下中文）
 *       只有真库会报。</li>
 * </ol>
 *
 * <p>测试数据统一用 {@code itest-} 前缀的 runId，{@link #cleanUp} 负责删干净，
 * 不会在开发库里留下垃圾，也不会影响真实链路数据。
 */
class MyBatisTraceStoreIT {

    // 通过环境变量提供真实库连接：MYSQL_URL / MYSQL_USERNAME / MYSQL_PASSWORD。
    private static final String URL = System.getenv().getOrDefault("MYSQL_URL",
            "jdbc:mysql://127.0.0.1:3306/md-agent-platform"
                    + "?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai");
    private static final String USERNAME = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv("MYSQL_PASSWORD");

    /** 测试数据前缀：清理时只删自己的。 */
    private static final String RUN_PREFIX = "itest-";

    private static DataSource dataSource;
    private static SqlSession sqlSession;
    private static MyBatisTraceStore store;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(PASSWORD != null && !PASSWORD.isBlank(),
                "skip: set MYSQL_PASSWORD (and optionally MYSQL_URL/MYSQL_USERNAME) to run this integration test");
        dataSource = new DriverManagerDataSource(URL, USERNAME, PASSWORD);
        applySchema();

        SqlSessionFactory factory = sqlSessionFactory(dataSource);
        sqlSession = factory.openSession(true);
        store = new MyBatisTraceStore(sqlSession.getMapper(AgentRunMapper.class),
                sqlSession.getMapper(AgentRunEventMapper.class), factory);

        cleanUp();
    }

    @AfterAll
    static void tearDown() throws Exception {
        cleanUp();
        sqlSession.close();
    }

    /** 主体往返：run 落库（开始 / 收口）与事件批量落库，读回来必须逐字段对上。 */
    @Test
    void runAndEventsSurviveARoundTripThroughMysql() {
        String runId = RUN_PREFIX + "roundtrip-a1";
        AgentTraceRun run = newRun(runId);

        // 1) 开始：先落 run 行，再落根节点事件
        store.saveRun(run);
        AgentTraceEvent rootStarted = AgentTraceEvent.started(1, AgentTraceEvent.PHASE_AGENT,
                "workwx_batch_agent", run.getStartedAtMs(), Map.of("messageCount", 1));
        run.addEvent(rootStarted);
        store.appendEvent(run, rootStarted);

        // 2) 一批事件（这一步走 MybatisBatch —— 就是"它到底能不能用"的验证点）
        AgentTraceEvent reasoningStarted = AgentTraceEvent.started(2, AgentTraceEvent.PHASE_REASONING,
                "第 1 轮推理", run.getStartedAtMs() + 10, Map.of("iteration", 1, "visibleTools", "[doc_get]"));
        AgentTraceEvent actingStarted = AgentTraceEvent.started(3, AgentTraceEvent.PHASE_ACTING,
                "工具调用 · doc_get", run.getStartedAtMs() + 20, Map.of("tool", "doc_get"));
        store.insertEvents(List.of(AgentRunEventEntity.of(runId, reasoningStarted),
                AgentRunEventEntity.of(runId, actingStarted)));

        // 3) 收口：一条 COMPLETED 事件 + 把 run 行刷成 DONE
        AgentTraceEvent actingCompleted = AgentTraceEvent.completed(4, 3, AgentTraceEvent.PHASE_ACTING,
                "工具调用 · doc_get", run.getStartedAtMs() + 20, 42L,
                Map.of("state", "SUCCESS", "result", "hello"), null);
        run.addEvent(actingCompleted);
        store.appendEvent(run, actingCompleted);
        run.finish(AgentTraceRun.STATUS_DONE, 10, 20, 3, null);
        run.setSystemPrompt("you are a timesheet auditor");
        store.saveRun(run);

        AgentTraceRun loaded = store.findRun(runId);
        assertNotNull(loaded, "刚写进去的 run 必须读得回来");
        assertEquals(AgentTraceRun.STATUS_DONE, loaded.getStatus());
        assertEquals("you are a timesheet auditor", loaded.getSystemPrompt());
        assertEquals(10, loaded.getInputTokens());
        assertEquals(20, loaded.getOutputTokens());
        assertEquals(3, loaded.getCachedTokens());
        assertEquals(run.getStartedAtMs(), loaded.getStartedAtMs());
        assertEquals(run.getEndedAtMs(), loaded.getEndedAtMs());
        assertEquals("workwx_batch_agent", loaded.getAgentName());
        assertEquals(AgentTraceRun.AGENT_KIND_HARNESS, loaded.getAgentKind());

        // ---- JSON 列：这一条是 autoResultMap 的守门人 ----
        assertNotNull(loaded.getInput(), "run 表的 input JSON 列读回来不能是 null（漏 autoResultMap 就会）");
        assertEquals(1, loaded.getInput().get("messageCount"));

        // ---- 事件：按 seq 升序；入参 / 出参各自落在 STARTED / COMPLETED 上 ----
        List<AgentTraceEvent> events = loaded.getEvents();
        assertEquals(List.of(1, 2, 3, 4), events.stream().map(AgentTraceEvent::seq).toList(),
                "事件必须按 seq 升序返回，实际: " + events);
        assertEquals(AgentTraceEvent.EVENT_STARTED, events.get(0).eventType());
        assertEquals(AgentTraceEvent.STATUS_RUNNING, events.get(0).status());

        AgentTraceEvent loadedActingStarted = events.get(2);
        assertNotNull(loadedActingStarted.input(), "STARTED 的 input JSON 列读回来不能是 null");
        assertEquals("doc_get", loadedActingStarted.input().get("tool"));
        assertNull(loadedActingStarted.output(), "STARTED 不带出参");

        AgentTraceEvent loadedActingCompleted = events.get(3);
        assertEquals(3, loadedActingCompleted.stageSeq(), "COMPLETED 的 stage_seq 要指回 STARTED 的 seq");
        assertEquals(AgentTraceEvent.STATUS_OK, loadedActingCompleted.status());
        assertEquals(42L, loadedActingCompleted.costMs());
        assertNotNull(loadedActingCompleted.output(), "COMPLETED 的 output JSON 列读回来不能是 null");
        assertEquals("hello", loadedActingCompleted.output().get("result"));
    }

    /** 同一个 run 反复 saveRun 只会有一行（"先查再决定 insert / update"那条路径）。 */
    @Test
    void savingTheSameRunRepeatedlyKeepsExactlyOneRow() {
        String runId = RUN_PREFIX + "idempotent-b1";
        AgentTraceRun run = newRun(runId);
        store.saveRun(run);
        store.saveRun(run);
        run.finish(AgentTraceRun.STATUS_ERROR, 1, 2, 0, "IllegalStateException: boom");
        store.saveRun(run);

        assertEquals(1, countRuns(runId), "同一个 runId 只能有一行");
        AgentTraceRun loaded = store.findRun(runId);
        assertEquals(AgentTraceRun.STATUS_ERROR, loaded.getStatus());
        assertEquals("IllegalStateException: boom", loaded.getError());
    }

    /** (run_id, seq) 唯一键必须真的存在 —— 它是"重复投递不会写脏数据"的唯一保障。 */
    @Test
    void duplicateSeqIsRejectedByTheUniqueKey() {
        String runId = RUN_PREFIX + "dupe-c1";
        AgentTraceRun run = newRun(runId);
        store.saveRun(run);
        AgentTraceEvent root = AgentTraceEvent.started(1, AgentTraceEvent.PHASE_AGENT, "root",
                1_700_000_000_000L, Map.of());
        store.appendEvent(run, root);

        Exception thrown = assertThrows(Exception.class, () -> store.appendEvent(run, root),
                "同一个 (run_id, seq) 写两次必须被唯一键挡住");
        // 不直接断言异常类型：走 Spring 的 SqlSessionTemplate 时 MyBatis 会把它翻译成
        // DuplicateKeyException，而这里用的是裸 SqlSession（不经过 Spring 的异常翻译器），
        // 抛的是 PersistenceException 包着 SQLIntegrityConstraintViolationException。
        // 断言"因由链里确实是一次唯一键冲突"，与具体封装层无关。
        assertTrue(hasDuplicateKeyCause(thrown), "期望唯一键冲突，实际异常: " + thrown);
    }

    /** 列表路径：新的在前，且刻意不带事件（列表页不需要重载荷）。 */
    @Test
    void recentRunsComeBackNewestFirstAndCarryNoEvents() {
        String older = RUN_PREFIX + "recent-d1";
        String newer = RUN_PREFIX + "recent-d2";
        store.saveRun(newRun(older));
        AgentTraceRun newerRun = newRun(newer);
        store.saveRun(newerRun);
        store.appendEvent(newerRun, AgentTraceEvent.started(1, AgentTraceEvent.PHASE_AGENT,
                "root", newerRun.getStartedAtMs(), Map.of()));

        // 直接问存储要最近 5 条，再从里面挑出本次这两条（表里可能还有别的真实数据）。
        List<AgentTraceRun> recent = store.findRecent(5);
        List<String> ids = recent.stream().map(AgentTraceRun::getRunId).toList();
        int newerIndex = ids.indexOf(newer);
        int olderIndex = ids.indexOf(older);
        assertTrue(newerIndex >= 0 && olderIndex >= 0, "两条测试数据都应在最近 5 条里，实际: " + ids);
        assertTrue(newerIndex < olderIndex, "新的必须排在旧的前面（startedAtMs 降序），实际: " + ids);
        assertTrue(recent.get(newerIndex).getEvents().isEmpty(),
                "列表路径不带事件，避免一次查询把上千行事件拖回来");
    }

    /** 保留期清理：run 与其事件一起删，不能留下读不出来的孤儿事件。 */
    @Test
    void retentionDeletesExpiredRunsTogetherWithTheirEvents() {
        String runId = RUN_PREFIX + "expired-e1";
        // 直接把 run 快照的开始时刻改到 40 天前 —— 比"写一条 30 天前的数据"更直接。
        AgentTraceRun expired = newRun(runId);
        expired.restoreState(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000, 0L,
                AgentTraceRun.STATUS_RUNNING, 0, 0, 0, null, null);
        store.saveRun(expired);
        store.appendEvent(expired, AgentTraceEvent.started(1, AgentTraceEvent.PHASE_AGENT, "root",
                expired.getStartedAtMs(), Map.of()));

        long cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
        assertTrue(store.deleteOlderThan(cutoff) >= 1, "40 天前的 run 应被删掉");

        assertNull(store.findRun(runId), "过期 run 必须读不到了");
        assertEquals(0, countEvents(runId), "过期 run 的事件也必须一起删掉，不能留孤儿");
    }

    /**
     * 清空（页面上的"清空"按钮 → {@code DELETE /api/traces}）。
     *
     * <p>它验的是 MyBatis-Plus 的"空 Wrapper delete"能不能生成无 WHERE 的 {@code DELETE} ——
     * 这个写法如果失效（例如被当成"条件为空就不执行"），表现是按钮点了没反应，而日志里什么都没有。
     *
     * <p><b>但它会清掉整张表</b>，所以先确认库里没有真实链路数据；一旦有（说明后端已经跑起来在写），
     * 就跳过本用例 —— 测试绝不允许误删用户的数据。
     */
    @Test
    void clearAllRemovesEveryArchivedRow() {
        long realRows = count("SELECT COUNT(*) FROM md_agent_run WHERE run_id NOT LIKE '" + RUN_PREFIX + "%'");
        Assumptions.assumeTrue(realRows == 0,
                "库里已有 " + realRows + " 条真实链路数据，跳过清空测试以免误删");

        String runId = RUN_PREFIX + "clear-f1";
        AgentTraceRun run = newRun(runId);
        store.saveRun(run);
        store.appendEvent(run, AgentTraceEvent.started(1, AgentTraceEvent.PHASE_AGENT, "root",
                run.getStartedAtMs(), Map.of()));

        store.clearAll();

        assertEquals(0, countRuns(runId), "清空后 run 表应为空");
        assertEquals(0, countEvents(runId), "清空后事件表也应为空");
    }

    // ==================== 辅助 ====================

    private static AgentTraceRun newRun(String runId) {
        return new AgentTraceRun(runId, "workwx_batch_agent", AgentTraceRun.AGENT_KIND_HARNESS,
                "sess-it", "ou_it", Map.of("messageCount", 1), AgentTraceRun.SOURCE_WECOM);
    }

    /**
     * 手工搭一个 MyBatis-Plus 的 SqlSessionFactory。
     *
     * <p>不启动 Spring 容器：这个测试要验的是存储层本身，启动整个应用会连带拉起
     * Nacos / Redis / xxl-job，既慢又会引入无关的失败源。
     */
    private static SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentRunMapper.class);
        configuration.addMapper(AgentRunEventMapper.class);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    /** 建表：直接执行 md-agent-bootstrap 里的那份 DDL，保证"测试用的表"与"生产用的表"是同一份定义。 */
    private static void applySchema() throws Exception {
        // surefire 的工作目录是模块目录（md-agent-server），所以从模块目录向上找到仓库根，再进 bootstrap。
        Path schema = Path.of("..", "md-agent-bootstrap", "src", "main", "resources", "db", "trace-schema.sql")
                .toAbsolutePath().normalize();
        assertTrue(Files.exists(schema), "找不到建表脚本，期望路径: " + schema);
        String sql = Files.readString(schema, StandardCharsets.UTF_8);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String raw : sql.split(";")) {
                // 去掉整行注释后还剩内容才执行（文件里有大段说明性注释）。
                // 用 [ \t] 而不是 \s：后者会连带吃掉换行，把注释后面的语句一起删掉。
                String cleaned = raw.replaceAll("(?m)^[ \t]*--.*$", "").trim();
                if (!cleaned.isEmpty()) {
                    statement.execute(cleaned);
                }
            }
        }
    }

    private static void cleanUp() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM md_agent_run_event WHERE run_id LIKE '" + RUN_PREFIX + "%'");
            statement.execute("DELETE FROM md_agent_run WHERE run_id LIKE '" + RUN_PREFIX + "%'");
        }
    }

    /** 因由链里是否藏着"唯一键冲突"（不依赖具体的封装层）。 */
    private static boolean hasDuplicateKeyCause(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
        }
        return false;
    }

    private static int countRuns(String runId) {
        return count("SELECT COUNT(*) FROM md_agent_run WHERE run_id = '" + runId + "'");
    }

    private static int countEvents(String runId) {
        return count("SELECT COUNT(*) FROM md_agent_run_event WHERE run_id = '" + runId + "'");
    }

    private static int count(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }
}
