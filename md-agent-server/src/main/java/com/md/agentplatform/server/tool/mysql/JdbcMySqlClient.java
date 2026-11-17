package com.md.agentplatform.server.tool.mysql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 基于 JDBC 的 MySQL 直连实现。
 *
 * <p>每次调用独立建立连接，适合 Agent 这种低频、按需触发的场景；不在进程内缓存连接池，
 * 避免多环境、多实例的池泄漏与失效连接问题。连接参数来自当前环境的 {@code mysql} 分组。
 */
@Component
public class JdbcMySqlClient implements MySqlClient {

    private static final Logger log = LoggerFactory.getLogger(JdbcMySqlClient.class);

    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    private static final int DEFAULT_METADATA_LIMIT = 1000;
    private static final int HARD_LIMIT = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    private final ConnectionFactory connectionFactory;

    public JdbcMySqlClient() {
        this(DriverManager::getConnection);
    }

    JdbcMySqlClient(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public DatabaseList listDatabases(MySqlTarget target, boolean includeSystem) {
        List<String> databases = new ArrayList<>();
        try (Connection connection = open(target, null, target.queryTimeoutSeconds());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW DATABASES")) {
            while (resultSet.next()) {
                String name = resultSet.getString(1);
                if (name != null && (includeSystem || !SYSTEM_DATABASES.contains(name.toLowerCase()))) {
                    databases.add(name);
                }
            }
            return new DatabaseList(databases);
        } catch (SQLException e) {
            throw error("list-databases", e, target);
        }
    }

    @Override
    public TableList listTables(MySqlTarget target, String database, String keyword, int maxRows) {
        int limit = normalizeLimit(maxRows);
        String trimmedKeyword = blankToNull(keyword);
        String sql = "SELECT table_name, table_type, engine, table_rows, table_comment, "
                + "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time, "
                + "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time "
                + "FROM information_schema.tables WHERE table_schema = ?"
                + (trimmedKeyword == null ? "" : " AND table_name LIKE ?")
                + " ORDER BY table_name LIMIT ?";
        List<TableInfo> tables = new ArrayList<>();
        try (Connection connection = open(target, null, target.queryTimeoutSeconds());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, database);
            if (trimmedKeyword != null) {
                statement.setString(index++, "%" + trimmedKeyword + "%");
            }
            statement.setInt(index, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long rows = resultSet.getLong("table_rows");
                    boolean rowsNull = resultSet.wasNull();
                    tables.add(new TableInfo(
                            resultSet.getString("table_name"),
                            resultSet.getString("table_type"),
                            resultSet.getString("engine"),
                            rowsNull ? null : rows,
                            resultSet.getString("table_comment"),
                            resultSet.getString("create_time"),
                            resultSet.getString("update_time")));
                }
            }
            boolean truncated = tables.size() > limit;
            if (truncated) {
                tables = new ArrayList<>(tables.subList(0, limit));
            }
            return new TableList(database, tables, truncated);
        } catch (SQLException e) {
            throw error("list-tables", e, target);
        }
    }

    @Override
    public TableDetail describeTable(MySqlTarget target, String database, String table) {
        List<ColumnInfo> columns = new ArrayList<>();
        List<IndexInfo> indexes = new ArrayList<>();
        try (Connection connection = open(target, database, target.queryTimeoutSeconds())) {
            columns.addAll(readColumns(connection, database, table));
            if (columns.isEmpty()) {
                throw new MySqlException("表不存在或当前账号不可见: " + database + "." + table,
                        null, null, null);
            }
            indexes.addAll(readIndexes(connection, database, table));
            String ddl = readCreateTable(connection, database, table);
            return new TableDetail(database, table, columns, indexes, ddl);
        } catch (MySqlException e) {
            throw e;
        } catch (SQLException e) {
            throw error("describe-table", e, target);
        }
    }

    @Override
    public ExecutionResult execute(MySqlTarget target, String database, String sql,
                                   int maxRows, int timeoutSeconds) {
        int limit = normalizeLimit(maxRows);
        int timeout = normalizeTimeout(timeoutSeconds);
        boolean readOnly = SqlText.isReadOnly(sql);
        String sqlType = SqlText.classify(sql);
        long startedAt = System.currentTimeMillis();
        try (Connection connection = open(target, database, timeout);
             Statement statement = createStatement(connection, sql, sqlType)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(limit + 1);

            boolean hasResultSet = statement instanceof PreparedStatement prepared
                    ? prepared.execute() : statement.execute(sql);
            if (hasResultSet) {
                RowResult rows = readRows(statement.getResultSet(), limit);
                return new ExecutionResult(readOnly, sqlType, rows.columns(),
                        rows.columnTypes(), rows.rows(), rows.truncated(), 0,
                        List.of(), System.currentTimeMillis() - startedAt);
            }

            int affectedRows = Math.max(statement.getUpdateCount(), 0);
            List<Object> generatedKeys = readGeneratedKeys(statement);
            return new ExecutionResult(readOnly, sqlType, List.of(), List.of(),
                    List.of(), false, affectedRows, generatedKeys,
                    System.currentTimeMillis() - startedAt);
        } catch (SQLException e) {
            throw error("execute", e, target);
        }
    }

    /**
     * DML 用 PreparedStatement 才能拿到自增主键；DDL / 查询 / SHOW 保持普通 Statement，
     * 避免把 MySQL 不支持预编译的语句误伤成语法错误。
     */
    private static Statement createStatement(Connection connection, String sql, String sqlType)
            throws SQLException {
        if ("DML".equals(sqlType)) {
            return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        }
        return connection.createStatement();
    }

    private List<ColumnInfo> readColumns(Connection connection, String database, String table)
            throws SQLException {
        String sql = "SELECT column_name, column_type, is_nullable, column_key, column_default, "
                + "extra, column_comment, ordinal_position, character_set_name, collation_name "
                + "FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        List<ColumnInfo> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(new ColumnInfo(
                            resultSet.getString("column_name"),
                            resultSet.getString("column_type"),
                            "YES".equalsIgnoreCase(resultSet.getString("is_nullable")),
                            resultSet.getString("column_key"),
                            resultSet.getString("column_default"),
                            resultSet.getString("extra"),
                            resultSet.getString("column_comment"),
                            resultSet.getInt("ordinal_position"),
                            resultSet.getString("character_set_name"),
                            resultSet.getString("collation_name")));
                }
            }
        }
        return columns;
    }

    private List<IndexInfo> readIndexes(Connection connection, String database, String table)
            throws SQLException {
        String sql = "SELECT index_name, non_unique, seq_in_index, column_name, index_type, cardinality "
                + "FROM information_schema.statistics "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY index_name, seq_in_index";
        List<IndexInfo> indexes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long cardinality = resultSet.getLong("cardinality");
                    boolean cardinalityNull = resultSet.wasNull();
                    indexes.add(new IndexInfo(
                            resultSet.getString("index_name"),
                            resultSet.getInt("non_unique") != 0,
                            resultSet.getInt("seq_in_index"),
                            resultSet.getString("column_name"),
                            resultSet.getString("index_type"),
                            cardinalityNull ? null : cardinality));
                }
            }
        }
        return indexes;
    }

    private String readCreateTable(Connection connection, String database, String table) {
        String sql = "SHOW CREATE TABLE " + SqlText.quoteIdentifier(database)
                + "." + SqlText.quoteIdentifier(table);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(2) : null;
        } catch (SQLException e) {
            log.warn("MySQL SHOW CREATE TABLE 失败，已忽略 DDL: {}.{}, {}", database, table, e.getMessage());
            return null;
        }
    }

    private RowResult readRows(ResultSet resultSet, int limit) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<String> columnTypes = new ArrayList<>(columnCount);
        Set<String> usedLabels = new LinkedHashSet<>();
        for (int i = 1; i <= columnCount; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(uniqueLabel(label == null || label.isBlank() ? "column_" + i : label, usedLabels));
            columnTypes.add(meta.getColumnTypeName(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() <= limit) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1), toJsonValue(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new RowResult(columns, columnTypes, rows, truncated);
    }

    private List<Object> readGeneratedKeys(Statement statement) {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys == null) {
                return List.of();
            }
            List<Object> values = new ArrayList<>();
            while (keys.next()) {
                values.add(toJsonValue(keys.getObject(1)));
            }
            return values;
        } catch (SQLException e) {
            return List.of();
        }
    }

    private Connection open(MySqlTarget target, String database, int timeoutSeconds) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", nullToEmpty(target.username()));
        properties.setProperty("password", nullToEmpty(target.password()));
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", String.valueOf(Math.max(10, timeoutSeconds + 5) * 1000L));
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("allowLoadLocalInfile", "false");

        Connection connection = connectionFactory.open(target.url(), properties);
        try {
            if (database != null && !database.isBlank()) {
                connection.setCatalog(database.trim());
            }
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    private static Object toJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof java.sql.Timestamp || value instanceof java.sql.Date
                || value instanceof java.sql.Time) {
            return value.toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }

    private static String uniqueLabel(String label, Set<String> used) {
        String candidate = label;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = label + "_" + suffix++;
        }
        return candidate;
    }

    private static int normalizeLimit(int maxRows) {
        if (maxRows <= 0) {
            return DEFAULT_METADATA_LIMIT;
        }
        return Math.min(maxRows, HARD_LIMIT);
    }

    private static int normalizeTimeout(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
    }

    private MySqlException error(String operation, SQLException e, MySqlTarget target) {
        String message = "MySQL " + operation + " 失败: " + sanitize(e.getMessage(), target);
        log.error("MySQL {} 失败, SQLState={}, errorCode={}, message={}",
                operation, e.getSQLState(), e.getErrorCode(), sanitize(e.getMessage(), target));
        return new MySqlException(message, e.getSQLState(), e.getErrorCode(), e);
    }

    private static String sanitize(String message, MySqlTarget target) {
        if (message == null) {
            return "";
        }
        String sanitized = message;
        if (target.password() != null && !target.password().isBlank()) {
            sanitized = sanitized.replace(target.password(), "***");
        }
        return sanitized.replaceAll("(?i)(password=)[^&\\s]+", "$1***");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record RowResult(List<String> columns, List<String> columnTypes,
                             List<Map<String, Object>> rows, boolean truncated) {
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(String url, Properties properties) throws SQLException;
    }
}
