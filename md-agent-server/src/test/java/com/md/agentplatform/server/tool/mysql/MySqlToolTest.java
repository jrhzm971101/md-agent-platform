package com.md.agentplatform.server.tool.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlToolTest {

    private static final String ENV = "cameroon-test";
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/lms";
    private static final String USER = "tester";
    private static final String PASSWORD = "secret";

    private EnvironmentProperties.Mysql config;
    private StubEnvironmentRegistry registry;
    private StubClient client;
    private MySqlTool tool;

    @BeforeEach
    void setUp() {
        config = config(URL, USER, PASSWORD, "lms", false, 50, 20);
        registry = new StubEnvironmentRegistry();
        registry.put(ENV, config);
        client = new StubClient();
        tool = new MySqlTool(registry, client);
    }

    @Test
    void missingEnvironmentIsRejectedBeforeCallingDatabase() {
        JsonNode result = JsonUtil.parse(tool.execute(null, "select 1", null, null, null, null, null));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("environment 必填"));
        assertEquals(0, client.calls);
    }

    @Test
    void unknownEnvironmentIsRejected() {
        JsonNode result = JsonUtil.parse(tool.listDatabases("not-exist", null, null));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("环境不存在"));
        assertEquals(0, client.calls);
    }

    @Test
    void missingUrlIsReportedWithoutConnectionAttempt() {
        registry.put(ENV, config(null, USER, PASSWORD, "lms", false, 50, 20));

        JsonNode result = JsonUtil.parse(tool.listDatabases(ENV, null, null));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("url"));
        assertEquals(0, client.calls);
    }

    @Test
    void listDatabasesUsesEnvironmentTarget() {
        JsonNode result = JsonUtil.parse(tool.listDatabases(ENV, true, null));

        assertTrue(result.path("success").asBoolean());
        assertEquals(2, result.path("count").asInt());
        assertTrue(client.lastIncludeSystem);
        assertEquals(URL, client.lastTarget.url());
        assertEquals(USER, client.lastTarget.username());
        assertEquals(PASSWORD, client.lastTarget.password());
    }

    @Test
    void listTablesUsesDefaultDatabaseAndRequestedLimit() {
        JsonNode result = JsonUtil.parse(tool.listTables(ENV, null, "customer", 10, null));

        assertTrue(result.path("success").asBoolean());
        assertEquals("lms", result.path("database").asText());
        assertEquals("lms", client.lastDatabase);
        assertEquals("customer", client.lastKeyword);
        assertEquals(10, client.lastMaxRows);
        assertEquals("b_customer", result.path("tables").get(0).path("tableName").asText());
    }

    @Test
    void listTablesRequiresDatabaseWhenNoDefault() {
        registry.put(ENV, config(URL, USER, PASSWORD, null, false, 50, 20));

        JsonNode result = JsonUtil.parse(tool.listTables(ENV, null, null, null, null));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("database"));
        assertEquals(0, client.calls);
    }

    @Test
    void describeTableReturnsColumnsIndexesAndDdl() {
        JsonNode result = JsonUtil.parse(tool.describeTable(ENV, null, "b_customer", null));

        assertTrue(result.path("success").asBoolean());
        assertEquals("b_customer", result.path("table").asText());
        assertEquals(1, result.path("columns").size());
        assertEquals(1, result.path("indexes").size());
        assertTrue(result.path("ddl").asText().contains("CREATE TABLE"));
        assertEquals("b_customer", client.lastTable);
    }

    @Test
    void describeTableRequiresTableName() {
        JsonNode result = JsonUtil.parse(tool.describeTable(ENV, null, "  ", null));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("table"));
        assertEquals(0, client.calls);
    }

    @Test
    void readOnlySqlIsAllowedWhenWriteDisabled() {
        JsonNode result = JsonUtil.parse(tool.execute(ENV, "select * from b_customer", null,
                null, null, "check", null));

        assertTrue(result.path("success").asBoolean());
        assertEquals("EXECUTED", result.path("decision").asText());
        assertEquals(1, result.path("rowCount").asInt());
        assertEquals(1, client.calls);
    }

    @Test
    void writeSqlIsRejectedWhenAllowWriteIsFalse() {
        JsonNode result = JsonUtil.parse(tool.execute(ENV, "update b_customer set name = 'x'", null,
                null, null, "test", null));

        assertFalse(result.path("success").asBoolean());
        assertEquals("REJECTED", result.path("decision").asText());
        assertTrue(result.path("error").asText().contains("allowWrite"));
        assertEquals(0, client.calls);
    }

    @Test
    void writeSqlIsAllowedWhenAllowWriteIsTrue() {
        config.setAllowWrite(true);

        JsonNode result = JsonUtil.parse(tool.execute(ENV, "update b_customer set name = 'x'", null,
                null, null, "test", null));

        assertTrue(result.path("success").asBoolean());
        assertEquals("EXECUTED", result.path("decision").asText());
        assertEquals(3, result.path("affectedRows").asInt());
        assertEquals(1, client.calls);
    }

    @Test
    void multipleStatementsAreRejectedEvenWhenWriteIsAllowed() {
        config.setAllowWrite(true);

        JsonNode result = JsonUtil.parse(tool.execute(ENV, "delete from b_customer; drop table b_customer",
                null, null, null, "test", null));

        assertFalse(result.path("success").asBoolean());
        assertEquals("REJECTED", result.path("decision").asText());
        assertTrue(result.path("error").asText().contains("单条"));
        assertEquals(0, client.calls);
    }

    @Test
    void administrativeSqlIsRejectedEvenWhenWriteIsAllowed() {
        config.setAllowWrite(true);

        JsonNode result = JsonUtil.parse(tool.execute(ENV, "grant all on *.* to demo", null,
                null, null, "test", null));

        assertFalse(result.path("success").asBoolean());
        assertEquals("REJECTED", result.path("decision").asText());
        assertTrue(result.path("error").asText().contains("管理类"));
        assertEquals(0, client.calls);
    }

    @Test
    void maxRowsAndTimeoutAreCapped() {
        tool.execute(ENV, "select 1", null, 9999, 9999, null, null);

        assertEquals(5000, client.lastMaxRows);
        assertEquals(300, client.lastTimeout);
    }

    @Test
    void mysqlExceptionIsReturnedWithSqlStateAndErrorCode() {
        client.failure = new MySqlException("access denied", "28000", 1045, null);

        JsonNode result = JsonUtil.parse(tool.execute(ENV, "select 1", null,
                null, null, null, null));

        assertFalse(result.path("success").asBoolean());
        assertEquals("FAILED", result.path("decision").asText());
        assertEquals("28000", result.path("sqlState").asText());
        assertEquals(1045, result.path("errorCode").asInt());
    }

    private static EnvironmentProperties.Mysql config(String url, String username, String password,
                                                       String defaultDatabase, boolean allowWrite,
                                                       int maxRows, int timeoutSeconds) {
        EnvironmentProperties.Mysql config = new EnvironmentProperties.Mysql();
        config.setUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDefaultDatabase(defaultDatabase);
        config.setAllowWrite(allowWrite);
        config.setMaxRows(maxRows);
        config.setQueryTimeoutSeconds(timeoutSeconds);
        return config;
    }

    private static class StubEnvironmentRegistry extends EnvironmentRegistry {

        private final Map<String, EnvironmentProperties.Mysql> configs = new LinkedHashMap<>();

        StubEnvironmentRegistry() {
            super(null);
        }

        void put(String environment, EnvironmentProperties.Mysql config) {
            configs.put(environment, config);
        }

        @Override
        public EnvironmentProperties.Mysql mysql(String environment) {
            return mysql(environment, null);
        }

        @Override
        public EnvironmentProperties.Mysql mysql(String environment, String instance) {
            EnvironmentProperties.Mysql config = configs.get(environment);
            if (config == null) {
                throw new IllegalArgumentException("环境不存在: " + environment + "，可用环境: " + configs.keySet());
            }
            return config;
        }
    }

    private static class StubClient implements MySqlClient {

        private MySqlException failure;
        private MySqlTarget lastTarget;
        private String lastDatabase;
        private String lastTable;
        private String lastKeyword;
        private String lastSql;
        private int lastMaxRows;
        private int lastTimeout;
        private boolean lastIncludeSystem;
        private int calls;

        @Override
        public DatabaseList listDatabases(MySqlTarget target, boolean includeSystem) {
            record(target);
            lastIncludeSystem = includeSystem;
            return new DatabaseList(List.of("lms", "risk"));
        }

        @Override
        public TableList listTables(MySqlTarget target, String database, String keyword, int maxRows) {
            record(target);
            lastDatabase = database;
            lastKeyword = keyword;
            lastMaxRows = maxRows;
            return new TableList(database, List.of(new TableInfo(
                    "b_customer", "BASE TABLE", "InnoDB", 10L, "customer table",
                    "2026-01-01 00:00:00", "2026-01-02 00:00:00")), false);
        }

        @Override
        public TableDetail describeTable(MySqlTarget target, String database, String table) {
            record(target);
            lastDatabase = database;
            lastTable = table;
            return new TableDetail(database, table,
                    List.of(new ColumnInfo("id", "bigint", false, "PRI", null,
                            "auto_increment", "pk", 1, null, null)),
                    List.of(new IndexInfo("PRIMARY", false, 1, "id", "BTREE", 10L)),
                    "CREATE TABLE b_customer (id bigint primary key)");
        }

        @Override
        public ExecutionResult execute(MySqlTarget target, String database, String sql,
                                       int maxRows, int timeoutSeconds) {
            record(target);
            lastDatabase = database;
            lastSql = sql;
            lastMaxRows = maxRows;
            lastTimeout = timeoutSeconds;
            boolean readOnly = SqlText.isReadOnly(sql);
            return new ExecutionResult(readOnly, SqlText.classify(sql),
                    readOnly ? List.of("id") : List.of(),
                    readOnly ? List.of("BIGINT") : List.of(),
                    readOnly ? List.of(Map.of("id", 1)) : List.of(),
                    false, readOnly ? 0 : 3, List.of(), 5);
        }

        private void record(MySqlTarget target) {
            calls++;
            lastTarget = target;
            if (failure != null) {
                throw failure;
            }
        }
    }
}
