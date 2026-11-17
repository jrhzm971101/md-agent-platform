package com.md.agentplatform.server.tool.mysql;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcMySqlClientTest {

    private static final MySqlTarget TARGET = new MySqlTarget(
            "jdbc:mysql://127.0.0.1:3306/lms", "tester", "secret",
            "lms", true, 200, 30);

    @Test
    void listDatabasesFiltersSystemSchemasAndSetsConnectionProperties() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        AtomicReference<Properties> captured = new AtomicReference<>();
        JdbcMySqlClient client = new JdbcMySqlClient((url, properties) -> {
            captured.set(properties);
            return connection;
        });

        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW DATABASES")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("information_schema", "lms");

        MySqlClient.DatabaseList result = client.listDatabases(TARGET, false);

        assertEquals(List.of("lms"), result.databases());
        assertEquals("tester", captured.get().getProperty("user"));
        assertEquals("secret", captured.get().getProperty("password"));
        assertEquals("false", captured.get().getProperty("allowMultiQueries"));
        assertEquals("false", captured.get().getProperty("allowLoadLocalInfile"));
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void listTablesUsesKeywordAndReportsTruncation() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        JdbcMySqlClient client = new JdbcMySqlClient((url, properties) -> connection);

        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getString("table_name")).thenReturn("a", "b", "c");
        when(resultSet.getLong("table_rows")).thenReturn(1L, 2L, 3L);

        MySqlClient.TableList result = client.listTables(TARGET, "lms", "cust", 2);

        assertEquals(2, result.tables().size());
        assertTrue(result.truncated());
        verify(statement).setString(1, "lms");
        verify(statement).setString(2, "%cust%");
        verify(statement).setInt(3, 3);
    }

    @Test
    void executeReadsRowsAndReportsTruncation() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        JdbcMySqlClient client = new JdbcMySqlClient((url, properties) -> connection);
        String sql = "select id, name from t";

        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(sql)).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnLabel(2)).thenReturn("name");
        when(meta.getColumnTypeName(1)).thenReturn("BIGINT");
        when(meta.getColumnTypeName(2)).thenReturn("VARCHAR");
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getObject(1)).thenReturn(1L, 2L, 3L);
        when(resultSet.getObject(2)).thenReturn("a", "b", "c");

        MySqlClient.ExecutionResult result = client.execute(TARGET, "lms", sql, 2, 10);

        assertTrue(result.readOnly());
        assertEquals("QUERY", result.sqlType());
        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(2, result.rows().size());
        assertEquals(Map.of("id", 1L, "name", "a"), result.rows().get(0));
        assertTrue(result.truncated());
        verify(statement).setMaxRows(3);
    }

    @Test
    void executeDmlUsesReturnGeneratedKeys() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet keys = mock(ResultSet.class);
        JdbcMySqlClient client = new JdbcMySqlClient((url, properties) -> connection);
        String sql = "insert into t(name) values ('x')";

        when(connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)).thenReturn(statement);
        when(statement.execute()).thenReturn(false);
        when(statement.getUpdateCount()).thenReturn(1);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(keys.next()).thenReturn(true, false);
        when(keys.getObject(1)).thenReturn(99L);

        MySqlClient.ExecutionResult result = client.execute(TARGET, "lms", sql, 200, 10);

        assertFalse(result.readOnly());
        assertEquals("DML", result.sqlType());
        assertEquals(1, result.affectedRows());
        assertEquals(List.of(99L), result.generatedKeys());
        verify(statement).setQueryTimeout(10);
        verify(statement).close();
    }

    @Test
    void describeTableReadsPrimaryKeyAsUnique() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement columnsStatement = mock(PreparedStatement.class);
        PreparedStatement indexesStatement = mock(PreparedStatement.class);
        Statement ddlStatement = mock(Statement.class);
        ResultSet columns = mock(ResultSet.class);
        ResultSet indexes = mock(ResultSet.class);
        ResultSet ddl = mock(ResultSet.class);
        JdbcMySqlClient client = new JdbcMySqlClient((url, properties) -> connection);

        when(connection.prepareStatement(anyString())).thenReturn(columnsStatement, indexesStatement);
        when(columnsStatement.executeQuery()).thenReturn(columns);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("column_name")).thenReturn("id");
        when(columns.getString("column_type")).thenReturn("bigint");
        when(columns.getString("is_nullable")).thenReturn("NO");
        when(columns.getString("column_key")).thenReturn("PRI");
        when(columns.getInt("ordinal_position")).thenReturn(1);

        when(indexesStatement.executeQuery()).thenReturn(indexes);
        when(indexes.next()).thenReturn(true, false);
        when(indexes.getString("index_name")).thenReturn("PRIMARY");
        when(indexes.getInt("non_unique")).thenReturn(0);
        when(indexes.getInt("seq_in_index")).thenReturn(1);
        when(indexes.getString("column_name")).thenReturn("id");
        when(indexes.getString("index_type")).thenReturn("BTREE");
        when(indexes.getLong("cardinality")).thenReturn(1L);

        when(connection.createStatement()).thenReturn(ddlStatement);
        when(ddlStatement.executeQuery("SHOW CREATE TABLE `lms`.`t`")).thenReturn(ddl);
        when(ddl.next()).thenReturn(true);
        when(ddl.getString(2)).thenReturn("CREATE TABLE t (id bigint primary key)");

        MySqlClient.TableDetail detail = client.describeTable(TARGET, "lms", "t");

        assertEquals(1, detail.columns().size());
        assertEquals(1, detail.indexes().size());
        assertFalse(detail.indexes().get(0).nonUnique());
        assertEquals("CREATE TABLE t (id bigint primary key)", detail.ddl());
    }
}

