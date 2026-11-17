package com.md.agentplatform.server.tool.mysql;

import java.util.List;
import java.util.Map;

/**
 * MySQL 直连客户端接口。工具层依赖这个接口，测试可以完全脱离真实数据库。
 */
public interface MySqlClient {

    DatabaseList listDatabases(MySqlTarget target, boolean includeSystem);

    TableList listTables(MySqlTarget target, String database, String keyword, int maxRows);

    TableDetail describeTable(MySqlTarget target, String database, String table);

    ExecutionResult execute(MySqlTarget target, String database, String sql,
                            int maxRows, int timeoutSeconds);

    record DatabaseList(List<String> databases) {
    }

    record TableInfo(String tableName, String tableType, String engine, Long estimatedRows,
                     String tableComment, String createTime, String updateTime) {
    }

    record TableList(String database, List<TableInfo> tables, boolean truncated) {
    }

    record ColumnInfo(String name, String type, boolean nullable, String key, String defaultValue,
                      String extra, String comment, int ordinalPosition,
                      String characterSet, String collation) {
    }

    record IndexInfo(String indexName, boolean nonUnique, int seqInIndex, String columnName,
                     String indexType, Long cardinality) {
    }

    record TableDetail(String database, String table, List<ColumnInfo> columns,
                       List<IndexInfo> indexes, String ddl) {
    }

    record ExecutionResult(boolean readOnly, String sqlType, List<String> columns,
                           List<String> columnTypes, List<Map<String, Object>> rows,
                           boolean truncated, int affectedRows, List<Object> generatedKeys,
                           long elapsedMs) {
    }
}
