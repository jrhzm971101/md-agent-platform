package com.md.agentplatform.server.tool.mysql;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MySQL 直连工具。
 *
 * <p>定位：test 环境没有 NineData 覆盖时，直接通过 JDBC 查询/维护测试库。
 * prod / uat 仍应使用 NineData，不建议给这两个环境绑定 {@code mysql} 分组。
 *
 * <p>安全边界：
 * <ul>
 *   <li>{@code environment} 必填，连接信息只从环境目录的 {@code mysql} 分组读取；</li>
 *   <li>{@code allowWrite=false} 时只允许 SELECT / SHOW / DESCRIBE / EXPLAIN；</li>
 *   <li>无论是否允许写，都拒绝多语句和服务器管理类语句；</li>
 *   <li>结果默认最多 200 行，硬上限 5000 行；SQL 超时硬上限 300 秒。</li>
 * </ul>
 */
@Component("mysqlTool")
public class MySqlTool {

    private static final Logger logger = LoggerFactory.getLogger(MySqlTool.class);

    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int HARD_MAX_ROWS = 5000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int HARD_TIMEOUT_SECONDS = 300;

    private final EnvironmentRegistry environmentRegistry;
    private final MySqlClient client;

    @Autowired
    public MySqlTool(EnvironmentRegistry environmentRegistry, MySqlClient client) {
        this.environmentRegistry = environmentRegistry;
        this.client = client;
    }

    @Tool(
            name = "mysql-list-databases",
            description = """
                    列出 MySQL 实例中当前账号可见的数据库。

                    参数：
                    - environment: 环境名（必填），如 cameroon-test；连接信息只从该环境的 mysql 分组读取
                    - includeSystem: 是否包含 information_schema/mysql/performance_schema/sys，默认 false
                    - mysqlInstance: MySQL 实例标识；省略时优先 default，其次唯一实例

                    返回：{ success, environment, databases, count }。
                    """
    )
    public String listDatabases(
            @ToolParam(name = "environment", required = true,
                    description = "环境名（必填），如 cameroon-test；直连信息只从该环境的 mysql 分组读取") String environment,
            @ToolParam(name = "includeSystem", required = false,
                    description = "是否包含系统库，默认 false") Boolean includeSystem,
            @ToolParam(name = "mysqlInstance", required = false,
                    description = "MySQL 实例标识；省略时优先 default，其次唯一实例") String mysqlInstance) {
        try {
            Context context = resolveContext(environment, mysqlInstance);
            String configError = checkTarget(context);
            if (configError != null) {
                return error(configError, environment, "FAILED");
            }
            MySqlClient.DatabaseList list = client.listDatabases(
                    context.target(), Boolean.TRUE.equals(includeSystem));
            ObjectNode result = baseResult("mysql-list-databases", context);
            ArrayNode databases = JsonUtil.newArray();
            list.databases().forEach(databases::add);
            result.set("databases", databases);
            result.put("count", list.databases().size());
            return JsonUtil.stringify(result);
        } catch (IllegalArgumentException e) {
            return error("环境解析失败: " + e.getMessage(), environment, "REJECTED");
        } catch (MySqlException e) {
            return error(e, environment);
        } catch (Exception e) {
            logger.error("mysql-list-databases 异常: {}", e.getMessage(), e);
            return error("MySQL 查询数据库列表异常: " + e.getMessage(), environment, "FAILED");
        }
    }

    @Tool(
            name = "mysql-list-tables",
            description = """
                    列出指定数据库中的表/视图及其基本信息。

                    参数：
                    - environment: 环境名（必填），如 cameroon-test
                    - database: 库名；省略时取该环境 mysql.defaultDatabase
                    - keyword: 表名关键字，模糊匹配
                    - maxRows: 最多返回多少张表，默认取环境配置（通常 200）
                    - mysqlInstance: MySQL 实例标识

                    返回：{ success, environment, database, tables, count, truncated }。
                    tables 每项包含 tableName / tableType / engine / estimatedRows / tableComment /
                    createTime / updateTime。estimatedRows 来自 information_schema，是估算值。
                    """
    )
    public String listTables(
            @ToolParam(name = "environment", required = true,
                    description = "环境名（必填），如 cameroon-test") String environment,
            @ToolParam(name = "database", required = false,
                    description = "库名；省略时取该环境的 defaultDatabase") String database,
            @ToolParam(name = "keyword", required = false,
                    description = "表名关键字，模糊匹配") String keyword,
            @ToolParam(name = "maxRows", required = false,
                    description = "最多返回多少张表；默认取环境配置，硬上限 5000") Integer maxRows,
            @ToolParam(name = "mysqlInstance", required = false,
                    description = "MySQL 实例标识；省略时优先 default，其次唯一实例") String mysqlInstance) {
        try {
            Context context = resolveContext(environment, mysqlInstance);
            String configError = checkTarget(context);
            if (configError != null) {
                return error(configError, environment, "FAILED");
            }
            String resolvedDatabase = firstNonBlank(database, context.target().defaultDatabase());
            if (resolvedDatabase == null) {
                return error("参数缺失: database 必填，或在该环境 mysql.defaultDatabase 中配置默认库",
                        environment, "REJECTED");
            }
            int limit = resolveLimit(maxRows, context.target());
            MySqlClient.TableList tableList = client.listTables(
                    context.target(), resolvedDatabase, keyword, limit);

            ObjectNode result = baseResult("mysql-list-tables", context);
            result.put("database", resolvedDatabase);
            ArrayNode tables = JsonUtil.newArray();
            for (MySqlClient.TableInfo table : tableList.tables()) {
                ObjectNode item = JsonUtil.newObject();
                putNullable(item, "tableName", table.tableName());
                putNullable(item, "tableType", table.tableType());
                putNullable(item, "engine", table.engine());
                if (table.estimatedRows() == null) {
                    item.putNull("estimatedRows");
                } else {
                    item.put("estimatedRows", table.estimatedRows());
                }
                putNullable(item, "tableComment", table.tableComment());
                putNullable(item, "createTime", table.createTime());
                putNullable(item, "updateTime", table.updateTime());
                tables.add(item);
            }
            result.set("tables", tables);
            result.put("count", tableList.tables().size());
            result.put("truncated", tableList.truncated());
            return JsonUtil.stringify(result);
        } catch (IllegalArgumentException e) {
            return error("环境解析失败: " + e.getMessage(), environment, "REJECTED");
        } catch (MySqlException e) {
            return error(e, environment);
        } catch (Exception e) {
            logger.error("mysql-list-tables 异常: {}", e.getMessage(), e);
            return error("MySQL 查询表列表异常: " + e.getMessage(), environment, "FAILED");
        }
    }

    @Tool(
            name = "mysql-describe-table",
            description = """
                    查看一张表的完整结构：字段、索引和 SHOW CREATE TABLE DDL。

                    参数：
                    - environment: 环境名（必填），如 cameroon-test
                    - database: 库名；省略时取该环境 mysql.defaultDatabase
                    - table: 表名（必填）
                    - mysqlInstance: MySQL 实例标识

                    返回：{ success, environment, database, table, columns, indexes, ddl }。
                    写 SQL 前应先用本工具确认字段名与类型，禁止凭猜测编造字段。
                    """
    )
    public String describeTable(
            @ToolParam(name = "environment", required = true,
                    description = "环境名（必填），如 cameroon-test") String environment,
            @ToolParam(name = "database", required = false,
                    description = "库名；省略时取该环境的 defaultDatabase") String database,
            @ToolParam(name = "table", required = true,
                    description = "表名（必填）") String table,
            @ToolParam(name = "mysqlInstance", required = false,
                    description = "MySQL 实例标识；省略时优先 default，其次唯一实例") String mysqlInstance) {
        try {
            Context context = resolveContext(environment, mysqlInstance);
            String configError = checkTarget(context);
            if (configError != null) {
                return error(configError, environment, "FAILED");
            }
            String resolvedDatabase = firstNonBlank(database, context.target().defaultDatabase());
            String resolvedTable = trimToNull(table);
            if (resolvedDatabase == null) {
                return error("参数缺失: database 必填，或在该环境 mysql.defaultDatabase 中配置默认库",
                        environment, "REJECTED");
            }
            if (resolvedTable == null) {
                return error("参数缺失: table 必填", environment, "REJECTED");
            }

            MySqlClient.TableDetail detail = client.describeTable(
                    context.target(), resolvedDatabase, resolvedTable);
            ObjectNode result = baseResult("mysql-describe-table", context);
            result.put("database", resolvedDatabase);
            result.put("table", resolvedTable);

            ArrayNode columns = JsonUtil.newArray();
            for (MySqlClient.ColumnInfo column : detail.columns()) {
                ObjectNode item = JsonUtil.newObject();
                putNullable(item, "name", column.name());
                putNullable(item, "type", column.type());
                item.put("nullable", column.nullable());
                putNullable(item, "key", column.key());
                putNullable(item, "defaultValue", column.defaultValue());
                putNullable(item, "extra", column.extra());
                putNullable(item, "comment", column.comment());
                item.put("ordinalPosition", column.ordinalPosition());
                putNullable(item, "characterSet", column.characterSet());
                putNullable(item, "collation", column.collation());
                columns.add(item);
            }
            result.set("columns", columns);

            ArrayNode indexes = JsonUtil.newArray();
            for (MySqlClient.IndexInfo index : detail.indexes()) {
                ObjectNode item = JsonUtil.newObject();
                putNullable(item, "indexName", index.indexName());
                item.put("nonUnique", index.nonUnique());
                item.put("seqInIndex", index.seqInIndex());
                putNullable(item, "columnName", index.columnName());
                putNullable(item, "indexType", index.indexType());
                if (index.cardinality() == null) {
                    item.putNull("cardinality");
                } else {
                    item.put("cardinality", index.cardinality());
                }
                indexes.add(item);
            }
            result.set("indexes", indexes);
            putNullable(result, "ddl", detail.ddl());
            return JsonUtil.stringify(result);
        } catch (IllegalArgumentException e) {
            return error("环境解析失败: " + e.getMessage(), environment, "REJECTED");
        } catch (MySqlException e) {
            return error(e, environment);
        } catch (Exception e) {
            logger.error("mysql-describe-table 异常: {}", e.getMessage(), e);
            return error("MySQL 查询表结构异常: " + e.getMessage(), environment, "FAILED");
        }
    }

    @Tool(
            name = "mysql-execute",
            description = """
                    在指定环境的 MySQL 上执行一条 SQL。

                    参数：
                    - environment: 环境名（必填），如 cameroon-test
                    - sql: 单条 SQL，必填
                    - database: 库名；省略时取该环境 mysql.defaultDatabase
                    - maxRows: SELECT 最大返回行数；默认取环境配置（通常 200），硬上限 5000
                    - timeoutSeconds: SQL 执行超时秒数；默认取环境配置（通常 30），硬上限 300
                    - reason: 执行原因，写入日志便于审计
                    - mysqlInstance: MySQL 实例标识

                    安全限制：
                    - allowWrite=false 时只允许 SELECT / WITH ... SELECT / SHOW / DESCRIBE / EXPLAIN
                    - allowWrite=true 时允许 DML / DDL，但仍拒绝多语句和服务器管理类语句
                    - 生产 / UAT 不应配置 mysql 分组，继续使用 ninedata-sql-execute

                    返回：{ success, decision, environment, database, sqlType, readOnly,
                    rowCount, columns, rows, truncated, affectedRows, generatedKeys,
                    executionTimeMs }。
                    """
    )
    public String execute(
            @ToolParam(name = "environment", required = true,
                    description = "环境名（必填），如 cameroon-test") String environment,
            @ToolParam(name = "sql", required = true,
                    description = "单条 SQL；allowWrite=false 时仅允许只读查询") String sql,
            @ToolParam(name = "database", required = false,
                    description = "库名；省略时取该环境的 defaultDatabase") String database,
            @ToolParam(name = "maxRows", required = false,
                    description = "SELECT 最大返回行数，默认取环境配置，硬上限 5000") Integer maxRows,
            @ToolParam(name = "timeoutSeconds", required = false,
                    description = "SQL 执行超时秒数，默认取环境配置，硬上限 300") Integer timeoutSeconds,
            @ToolParam(name = "reason", required = false,
                    description = "执行原因，写入日志便于审计") String reason,
            @ToolParam(name = "mysqlInstance", required = false,
                    description = "MySQL 实例标识；省略时优先 default，其次唯一实例") String mysqlInstance) {
        String trimmedSql = trimToNull(sql);
        try {
            Context context = resolveContext(environment, mysqlInstance);
            String configError = checkTarget(context);
            if (configError != null) {
                return error(configError, environment, "FAILED");
            }
            if (trimmedSql == null) {
                return error("参数缺失: sql 必填", environment, "REJECTED");
            }
            if (SqlText.hasMultipleStatements(trimmedSql)) {
                return error("仅允许单条 SQL，拒绝多语句执行", environment, "REJECTED");
            }
            if (SqlText.containsForbiddenAdmin(trimmedSql)) {
                return error("拒绝执行服务器管理类 SQL（如 GRANT/REVOKE/SHUTDOWN/LOAD DATA/INTO OUTFILE 等）",
                        environment, "REJECTED");
            }

            boolean readOnly = SqlText.isReadOnly(trimmedSql);
            if (!readOnly && !context.target().allowWrite()) {
                return error("环境 " + environment + " 未开启 mysql.allowWrite，只允许只读 SQL",
                        environment, "REJECTED");
            }

            String resolvedDatabase = firstNonBlank(database, context.target().defaultDatabase());
            int limit = resolveLimit(maxRows, context.target());
            int timeout = resolveTimeout(timeoutSeconds, context.target());
            logger.info("mysql-execute called, environment:{}, instance:{}, database:{}, readOnly:{}, reason:{}, sql:{}",
                    environment, mysqlInstance, resolvedDatabase, readOnly, reason, abbreviate(trimmedSql));

            MySqlClient.ExecutionResult execution = client.execute(
                    context.target(), resolvedDatabase, trimmedSql, limit, timeout);
            ObjectNode result = baseResult("mysql-execute", context);
            result.put("decision", "EXECUTED");
            putNullable(result, "database", resolvedDatabase);
            result.put("sqlType", execution.sqlType());
            result.put("readOnly", execution.readOnly());
            result.put("rowCount", execution.rows().size());
            result.put("truncated", execution.truncated());
            result.put("affectedRows", execution.affectedRows());
            result.put("executionTimeMs", execution.elapsedMs());

            ArrayNode columns = JsonUtil.newArray();
            for (int i = 0; i < execution.columns().size(); i++) {
                ObjectNode column = JsonUtil.newObject();
                column.put("name", execution.columns().get(i));
                column.put("type", i < execution.columnTypes().size() ? execution.columnTypes().get(i) : null);
                columns.add(column);
            }
            result.set("columns", columns);
            result.set("rows", JsonUtil.toTree(execution.rows()));
            result.set("generatedKeys", JsonUtil.toTree(execution.generatedKeys()));
            return JsonUtil.stringify(result);
        } catch (IllegalArgumentException e) {
            return error("环境解析失败: " + e.getMessage(), environment, "REJECTED");
        } catch (MySqlException e) {
            return error(e, environment);
        } catch (Exception e) {
            logger.error("mysql-execute 异常: {}", e.getMessage(), e);
            return error("MySQL SQL 执行异常: " + e.getMessage(), environment, "FAILED");
        }
    }

    private Context resolveContext(String environment, String mysqlInstance) {
        String env = trimToNull(environment);
        if (env == null) {
            throw new IllegalArgumentException("environment 必填，可用环境请从环境目录查询");
        }
        EnvironmentProperties.Mysql config = (mysqlInstance == null || mysqlInstance.isBlank())
                ? environmentRegistry.mysql(env)
                : environmentRegistry.mysql(env, mysqlInstance);
        int maxRows = normalizeLimit(config.getMaxRows());
        int timeout = normalizeTimeout(config.getQueryTimeoutSeconds());
        MySqlTarget target = new MySqlTarget(
                config.getUrl(), config.getUsername(), config.getPassword(),
                config.getDefaultDatabase(), Boolean.TRUE.equals(config.getAllowWrite()),
                maxRows, timeout);
        return new Context(target, env);
    }

    private String checkTarget(Context context) {
        MySqlTarget target = context.target();
        String prefix = "环境 " + context.environment() + " 的 mysql 配置缺失: 请配置 ";
        if (isBlank(target.url())) {
            return prefix + "url";
        }
        if (isBlank(target.username())) {
            return prefix + "username";
        }
        if (isBlank(target.password())) {
            return prefix + "password";
        }
        return null;
    }

    private int resolveLimit(Integer requested, MySqlTarget target) {
        if (requested != null && requested > 0) {
            return Math.min(requested, HARD_MAX_ROWS);
        }
        return target.maxRows();
    }

    private int resolveTimeout(Integer requested, MySqlTarget target) {
        if (requested != null && requested > 0) {
            return Math.min(requested, HARD_TIMEOUT_SECONDS);
        }
        return target.queryTimeoutSeconds();
    }

    private static int normalizeLimit(Integer configured) {
        if (configured == null || configured <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(configured, HARD_MAX_ROWS);
    }

    private static int normalizeTimeout(Integer configured) {
        if (configured == null || configured <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(configured, HARD_TIMEOUT_SECONDS);
    }

    private static ObjectNode baseResult(String tool, Context context) {
        ObjectNode result = JsonUtil.newObject();
        result.put("success", true);
        result.put("tool", tool);
        result.put("environment", context.environment());
        return result;
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String abbreviate(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.length() > 300 ? sql.substring(0, 300) + "..." : sql;
    }

    private static String error(String message, String environment, String decision) {
        ObjectNode node = JsonUtil.newObject();
        node.put("success", false);
        node.put("decision", decision);
        node.put("environment", environment == null ? "" : environment);
        node.put("error", (message == null || message.isBlank()) ? "未知错误" : message);
        return JsonUtil.stringify(node);
    }

    private static String error(MySqlException e, String environment) {
        ObjectNode node = JsonUtil.newObject();
        node.put("success", false);
        node.put("decision", "FAILED");
        node.put("environment", environment == null ? "" : environment);
        node.put("error", e.getMessage());
        if (e.getSqlState() != null && !e.getSqlState().isBlank()) {
            node.put("sqlState", e.getSqlState());
        }
        if (e.getErrorCode() != null) {
            node.put("errorCode", e.getErrorCode());
        }
        return JsonUtil.stringify(node);
    }

    private record Context(MySqlTarget target, String environment) {
    }
}
