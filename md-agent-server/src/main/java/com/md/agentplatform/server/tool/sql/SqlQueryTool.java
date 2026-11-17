package com.md.agentplatform.server.tool.sql;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 查询工具 — 仅允许 SELECT 类语句，拒绝任何数据修改操作。
 * 自动对无 LIMIT 的查询追加 LIMIT 200，防止大结果集导致 OOM。
 */
@Component
public class SqlQueryTool {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryTool.class);

    private static final Pattern ALLOWED_PATTERN = Pattern.compile(
            "^\\s*(SELECT|WITH|EXPLAIN|DESCRIBE|DESC|SHOW)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE",
            "REPLACE", "GRANT", "REVOKE", "LOAD", "CALL", "EXEC", "EXECUTE",
            "RENAME", "LOCK", "UNLOCK", "SET", "FLUSH", "KILL", "SHUTDOWN",
            "MERGE", "UPSERT", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT",
            "HANDLER", "PURGE", "RESET", "STOP", "START", "SLAVE", "MASTER",
            "PREPARE", "DEALLOCATE", "ANALYZE", "OPTIMIZE", "REPAIR", "CHECKSUM",
            "INSTALL", "UNINSTALL", "CACHE", "LOAD DATA", "LOAD_FILE"
    );

    private static final int DEFAULT_LIMIT = 200;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Tool(
            name = "sql-query",
            description = """
                    执行 MySQL SELECT 查询并返回结果。

                    重要限制：
                    - **仅允许 SELECT / WITH / EXPLAIN / DESCRIBE / SHOW 语句**
                    - 禁止 INSERT、UPDATE、DELETE、DROP 等任何数据修改操作
                    - 自动追加 LIMIT 200（如 SQL 中未指定）
                    - 返回 JSON 格式：包含 columns（列名列表）、rows（数据行）、
                      rowCount（行数）、executionTimeMs（执行耗时毫秒）

                    参数：
                    - sql: 要执行的 SQL SELECT 语句
                    """
    )
    public String execute(
            @ToolParam(
                    name = "sql",
                    description = "待执行的 MySQL SELECT 查询语句。仅允许 SELECT/WITH/EXPLAIN/DESCRIBE/SHOW，禁止 INSERT/UPDATE/DELETE/DROP 等。"
            ) String sql) {

        long startTime = System.currentTimeMillis();
        ObjectNode result = JsonUtil.newObject();

        if (!ALLOWED_PATTERN.matcher(sql).find()) {
            String rejected = extractFirstWord(sql);
            log.warn("sql-query 拒绝非 SELECT 语句: {}", rejected);
            result.put("success", false);
            result.put("error", "仅允许 SELECT / WITH / EXPLAIN / DESCRIBE / SHOW 查询，拒绝语句类型: " + rejected);
            result.put("sql", sql);
            return JsonUtil.stringify(result);
        }

        String upperSql = sql.toUpperCase();
        for (String forbidden : FORBIDDEN_KEYWORDS) {
            if (containsWholeWord(upperSql, forbidden)) {
                log.warn("sql-query 检测到危险关键字 '{}'，已拒绝", forbidden);
                result.put("success", false);
                result.put("error", "SQL 包含禁止的关键字: " + forbidden);
                result.put("sql", sql);
                return JsonUtil.stringify(result);
            }
        }

        String execSql = sql.trim();
        if (execSql.endsWith(";")) {
            execSql = execSql.substring(0, execSql.length() - 1).trim();
        }
        if (!upperSql.contains("LIMIT") && !upperSql.contains("INTO") && !upperSql.startsWith("SHOW")
                && !upperSql.startsWith("DESCRIBE") && !upperSql.startsWith("DESC ")
                && !upperSql.startsWith("EXPLAIN")) {
            execSql += " LIMIT " + DEFAULT_LIMIT;
        }

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(execSql);
            long elapsed = System.currentTimeMillis() - startTime;

            ArrayNode columns = JsonUtil.newArray();
            if (!rows.isEmpty()) {
                rows.get(0).keySet().forEach(columns::add);
            }

            ArrayNode dataRows = JsonUtil.newArray();
            for (Map<String, Object> row : rows) {
                ObjectNode rowObj = JsonUtil.newObject();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    Object value = entry.getValue();
                    if (value == null) {
                        rowObj.putNull(entry.getKey());
                    } else if (value instanceof Number) {
                        rowObj.set(entry.getKey(), JsonUtil.toTree(value));
                    } else if (value instanceof Boolean) {
                        rowObj.put(entry.getKey(), (Boolean) value);
                    } else {
                        rowObj.put(entry.getKey(), value.toString());
                    }
                }
                dataRows.add(rowObj);
            }

            result.put("success", true);
            result.put("rowCount", rows.size());
            result.set("columns", columns);
            result.set("rows", dataRows);
            result.put("executionTimeMs", elapsed);
            return JsonUtil.stringify(result);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("sql-query 执行失败 ({}ms): {}", elapsed, e.getMessage());
            result.put("success", false);
            result.put("error", "数据库查询失败: " + e.getMessage());
            result.put("sql", execSql);
            result.put("executionTimeMs", elapsed);
            return JsonUtil.stringify(result);
        }
    }

    private static String extractFirstWord(String sql) {
        String trimmed = sql.trim();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            return trimmed.substring(0, spaceIdx);
        }
        return trimmed.isEmpty() ? "(空语句)" : trimmed;
    }

    private static boolean containsWholeWord(String text, String word) {
        String[] parts = word.split("\\s+");
        for (String part : parts) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(part) + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
