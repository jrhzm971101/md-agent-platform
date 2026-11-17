package com.md.agentplatform.server.tool.ninedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.NineDataProperties;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * NineData 数据查询工具 —— 通过 NineData 受控 OpenAPI 查询数据源、执行只读 SQL。
 *
 * <p>与 {@code MagicApiDataSearchTool} 的区别：NineData 是<b>受治理</b>的查询入口，
 * 权限、SQL 规则、脱敏、限流、审计都由平台负责。因此本工具只做三件事：
 * <ol>
 *   <li>按必填的 {@code environment} 从环境目录解析地址与 AK/SK，
 *       再把工具参数合并成 OpenAPI 请求；</li>
 *   <li>在本地先挡掉明显不是只读的 SQL（最终仍以 NineData 的 {@code decision} 为准）；</li>
 *   <li>把 NineData 原始响应原样返回，让模型按 {@code data.decision} 判断是否真的执行。</li>
 * </ol>
 *
 * <p><b>不要把 {@code success=true} 当成执行成功</b>：它只代表 OpenAPI 调用成功。
 * 真正的执行结论在 {@code data.decision}：
 * {@code EXECUTED / REJECTED / NEED_CONFIRMATION / NEED_SQL_TASK / FAILED}。
 *
 * <p>本工具只开放只读 SQL。DML/DDL/DCL/TCL 等变更必须走 NineData SQL Task
 * （后续如需，再按同样的方式补 {@code ninedata-sql-task-*} 工具）。
 */
@Component
public class NineDataTool {

    private static final Logger logger = LoggerFactory.getLogger(NineDataTool.class);

    /** 与 ninedata-skill 的 skillVersion 对齐，便于 NineData 侧归因。 */
    private static final String SKILL_VERSION = "1.2.2";

    private static final String DEFAULT_REASON = "agent 只读数据查询";

    /**
     * 只读语句白名单。只校验首关键字，不做全文关键字扫描 ——
     * 后者会把字符串字面量里的 "update" 误判成 DML；真正的 SQL 类型判定交给 NineData。
     */
    private static final Pattern READ_ONLY_SQL = Pattern.compile(
            "^\\s*(SELECT|WITH|SHOW|DESCRIBE|DESC|EXPLAIN)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final NineDataProperties properties;
    private final NineDataClient client;
    private final EnvironmentRegistry environmentRegistry;

    @Autowired
    public NineDataTool(NineDataProperties properties, NineDataClient client,
                        EnvironmentRegistry environmentRegistry) {
        this.properties = properties;
        this.client = client;
        this.environmentRegistry = environmentRegistry;
    }

    @Tool(
            name = "ninedata-list-datasource",
            description = """
                    列出 NineData 中当前 OpenAPI 凭证可见的数据源。

                    参数说明：
                    - environment: 环境名（如 cameroon-uat，必填），地址与 AK/SK 只从该环境配置读取
                    - ninedataInstance: NineData 实例标识；省略时优先 default，没有 default 且只有一个实例时自动使用它；多实例且无 default 则必须指定
                    - datasourceType: 数据源类型，如 MySQL / PostgreSQL / SQLServer / Oracle；省略则不过滤
                    - keyword: 数据源名称关键字，支持模糊匹配
                    - current: 页码，默认 1
                    - pageSize: 每页条数，默认取服务配置（通常 50）

                    返回：NineData 原始 JSON（含 success / requestId / data）。
                    失败时返回 {"success": false, "error": "...", "requestId": "..."}。
                    """
    )
    public String listDatasource(
            @ToolParam(name = "environment", required = true,
                    description = "环境名（必填），如 cameroon-uat；地址与 AK/SK 只从该环境配置读取") String environment,
            @ToolParam(name = "datasourceType", required = false,
                    description = "数据源类型，如 MySQL / PostgreSQL / SQLServer / Oracle；省略则不过滤") String datasourceType,
            @ToolParam(name = "keyword", required = false,
                    description = "数据源名称关键字，支持模糊匹配") String keyword,
            @ToolParam(name = "current", required = false, description = "页码，默认 1") Integer current,
            @ToolParam(name = "pageSize", required = false,
                    description = "每页条数，默认取服务配置（通常 50）") Integer pageSize,
            @ToolParam(name = "ninedataInstance", required = false,
                    description = "NineData 实例标识；省略时优先 default，其次唯一实例；多实例且无 default 时必须指定") String ninedataInstance
    ) {
        long startedAt = System.currentTimeMillis();
        logger.info("ninedata-list-datasource called, environment:{}, instance:{}, datasourceType:{}, keyword:{}, current:{}, pageSize:{}",
                environment, ninedataInstance, datasourceType, keyword, current, pageSize);

        Context context;
        try {
            context = resolveContext(environment, ninedataInstance);
        } catch (IllegalArgumentException e) {
            return error("环境解析失败: " + e.getMessage());
        }
        String configError = checkTarget(context);
        if (configError != null) {
            return error(configError);
        }

        try {
            int page = (current == null || current < 1) ? 1 : current;
            int size = (pageSize == null || pageSize < 1) ? defaultPageSize() : pageSize;
            JsonNode response = client.listDatasources(
                    context.target(), trimToNull(datasourceType), trimToNull(keyword), page, size);
            logger.info("ninedata-list-datasource finished in {}ms", System.currentTimeMillis() - startedAt);
            return JsonUtil.pretty(response.toString());
        } catch (NineDataException e) {
            return error(e);
        } catch (Exception e) {
            logger.error("ninedata-list-datasource 异常: {}", e.getMessage(), e);
            return error("NineData 数据源查询异常: " + e.getMessage());
        }
    }

    /** 兼容旧调用：不指定实例时由环境目录解析 default / 唯一实例。 */
    public String listDatasource(String environment, String datasourceType, String keyword,
                                 Integer current, Integer pageSize) {
        return listDatasource(environment, datasourceType, keyword, current, pageSize, null);
    }

    @Tool(
            name = "ninedata-sql-execute",
            description = """
                    通过 NineData 受控 OpenAPI 执行只读 SQL，返回平台判定与结果预览。

                    仅支持只读语句：SELECT、WITH ... SELECT、SHOW、DESCRIBE / DESC、EXPLAIN。
                    INSERT / UPDATE / DELETE / DDL 等变更请走 NineData SQL Task，本工具会直接拒绝。

                    参数说明：
                    - environment: 环境名（如 cameroon-uat，必填），地址与 AK/SK 只从该环境配置读取
                    - ninedataInstance: NineData 实例标识；省略时优先 default，没有 default 且只有一个实例时自动使用它；多实例且无 default 则必须指定
                    - sql: 只读 SQL（必填）
                    - datasourceId: NineData 数据源 ID（必填，不提供默认值；缺失时直接报错）
                    - databaseName: 库名；省略时取该环境的 defaultDbName，环境未配置则留空
                    - schemaName: schema 名；省略时取该环境的 defaultSchemaName，环境未配置则留空
                    - clusterName: 集群名，仅部分数据源需要
                    - reason: 查询原因（写入 NineData 审计日志）
                    - current: 结果页码，默认 1
                    - pageSize: 结果预览条数，默认取服务配置（通常 50）
                    - useSessionPersistence: 是否保持 SQL 会话状态，默认 false

                    返回：NineData 原始 JSON。注意 success=true 只代表接口调用成功，
                    是否真正执行必须看 data.decision：
                    EXECUTED / REJECTED / NEED_CONFIRMATION / NEED_SQL_TASK / FAILED。
                    大结果集可能被截断，以返回中的 truncated / masking 字段为准。
                    """
    )
    public String sqlExecute(
            @ToolParam(name = "environment", required = true,
                    description = "环境名（必填），如 cameroon-uat；地址与 AK/SK 只从该环境配置读取") String environment,
            @ToolParam(name = "sql", required = true,
                    description = "只读 SQL；仅允许 SELECT / WITH ... SELECT / SHOW / DESCRIBE / DESC / EXPLAIN") String sql,
            @ToolParam(name = "datasourceId", required = true,
                    description = "NineData 数据源 ID（必填，不提供默认值）") String datasourceId,
            @ToolParam(name = "databaseName", required = false,
                    description = "库名；省略时取该环境的 defaultDbName") String databaseName,
            @ToolParam(name = "schemaName", required = false,
                    description = "schema 名；省略时取该环境的 defaultSchemaName") String schemaName,
            @ToolParam(name = "clusterName", required = false,
                    description = "集群名，仅部分数据源需要，通常省略") String clusterName,
            @ToolParam(name = "reason", required = false,
                    description = "查询原因，写入 NineData 审计日志") String reason,
            @ToolParam(name = "current", required = false, description = "结果页码，默认 1") Integer current,
            @ToolParam(name = "pageSize", required = false,
                    description = "结果预览条数，默认取服务配置（通常 50）") Integer pageSize,
            @ToolParam(name = "useSessionPersistence", required = false,
                    description = "是否保持 SQL 会话状态，默认 false") Boolean useSessionPersistence,
            @ToolParam(name = "ninedataInstance", required = false,
                    description = "NineData 实例标识；省略时优先 default，其次唯一实例；多实例且无 default 时必须指定") String ninedataInstance
    ) {
        long startedAt = System.currentTimeMillis();
        String trimmedSql = trimToNull(sql);
        // 数据源 ID 必须由调用方显式提供：不支持全局/环境默认值，也不做任何兜底。
        String resolvedDsId = trimToNull(datasourceId);

        Context context;
        try {
            context = resolveContext(environment, ninedataInstance);
        } catch (IllegalArgumentException e) {
            return error("环境解析失败: " + e.getMessage());
        }
        String configError = checkTarget(context);
        if (configError != null) {
            return error(configError);
        }

        String resolvedDbName = firstNonBlank(databaseName, context.envConfig().getDefaultDbName());
        String resolvedSchemaName = firstNonBlank(schemaName, context.envConfig().getDefaultSchemaName());

        logger.info("ninedata-sql-execute called, environment:{}, instance:{}, datasourceId:{}, databaseName:{}, schemaName:{}, sql:{}",
                environment, ninedataInstance, resolvedDsId, resolvedDbName, resolvedSchemaName,
                abbreviate(trimmedSql));

        if (trimmedSql == null) {
            return error("参数缺失: sql 必填");
        }
        if (!READ_ONLY_SQL.matcher(stripLeadingComments(trimmedSql)).find()) {
            return error("ninedata-sql-execute 仅支持只读 SQL（SELECT / WITH ... SELECT / SHOW / DESCRIBE / DESC / EXPLAIN）；"
                    + "INSERT / UPDATE / DELETE / DDL 等变更请走 NineData SQL Task。");
        }
        if (resolvedDsId == null) {
            return error("参数缺失: datasourceId 必填；NineData 不提供默认数据源，"
                    + "请先通过 ninedata-list-datasource 确认数据源 ID 后再调用");
        }

        ObjectNode body = JsonUtil.newObject();
        body.put("datasourceId", resolvedDsId);
        putIfNotBlank(body, "databaseName", resolvedDbName);
        putIfNotBlank(body, "schemaName", resolvedSchemaName);
        putIfNotBlank(body, "clusterName", trimToNull(clusterName));
        body.put("sql", trimmedSql);
        body.put("current", (current == null || current < 1) ? 1 : current);
        body.put("pageSize", (pageSize == null || pageSize < 1) ? defaultPageSize() : pageSize);
        body.put("ignoreTips", true);
        body.put("useSessionPersistence", Boolean.TRUE.equals(useSessionPersistence));
        body.put("reason", firstNonBlank(reason, DEFAULT_REASON));
        body.put("source", firstNonBlank(properties.getSource(), "NINEDATA_SKILL"));
        body.put("toolId", "sql-execute");
        body.put("sourceClient", firstNonBlank(properties.getSourceClient(), "md-agent-platform"));
        body.put("skillVersion", SKILL_VERSION);
        body.put("language", firstNonBlank(properties.getDefaultLanguage(), "enus"));

        try {
            JsonNode response = client.executeSql(context.target(), body);
            logger.info("ninedata-sql-execute finished in {}ms", System.currentTimeMillis() - startedAt);
            return JsonUtil.pretty(response.toString());
        } catch (NineDataException e) {
            return error(e);
        } catch (Exception e) {
            logger.error("ninedata-sql-execute 异常: {}", e.getMessage(), e);
            return error("NineData SQL 执行异常: " + e.getMessage());
        }
    }

    /** 兼容旧调用：不指定实例时由环境目录解析 default / 唯一实例。 */
    public String sqlExecute(String environment, String sql, String datasourceId, String databaseName,
                             String schemaName, String clusterName, String reason, Integer current,
                             Integer pageSize, Boolean useSessionPersistence) {
        return sqlExecute(environment, sql, datasourceId, databaseName, schemaName, clusterName,
                reason, current, pageSize, useSessionPersistence, null);
    }

    /** 一次调用的解析结果；environment 与 envConfig 必然来自同一个环境，不存在全局回落。 */
    private record Context(NineDataTarget target, EnvironmentProperties.NineData envConfig,
                           String environment, String instance) {
    }

    private Context resolveContext(String environment, String ninedataInstance) {
        String env = trimToNull(environment);
        if (env == null) {
            throw new IllegalArgumentException("environment 必填；地址与 AK/SK 只能从指定环境读取");
        }
        if (environmentRegistry == null) {
            throw new IllegalArgumentException("当前运行环境未启用环境目录，无法使用 environment 参数");
        }
        String instance = trimToNull(ninedataInstance);
        EnvironmentProperties.NineData config = instance == null
                ? environmentRegistry.ninedata(env)
                : environmentRegistry.ninedata(env, instance);
        NineDataTarget target = new NineDataTarget(
                config.getEndpoint(), config.getAccessKeyId(), config.getAccessKeySecret());
        return new Context(target, config, env, instance);
    }

    /** 调用目标配置检查；缺失时返回给模型看的提示，齐全返回 null。 */
    private String checkTarget(Context context) {
        NineDataTarget target = context.target();
        String targetName = context.instance() == null || context.instance().isBlank()
                ? "ninedata"
                : "ninedata 实例 " + context.instance();
        String prefix = "环境 " + context.environment() + " 的 " + targetName + " 配置缺失: 请配置 ";
        if (target == null || isBlank(target.endpoint())) {
            return prefix + "endpoint";
        }
        if (isBlank(target.accessKeyId())) {
            return prefix + "accessKeyId";
        }
        if (isBlank(target.accessKeySecret())) {
            return prefix + "accessKeySecret";
        }
        return null;
    }

    private int defaultPageSize() {
        return properties.getDefaultPageSize() > 0 ? properties.getDefaultPageSize() : 50;
    }

    private static void putIfNotBlank(ObjectNode node, String name, String value) {
        if (value != null && !value.isBlank()) {
            node.put(name, value.trim());
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

    /**
     * 去掉 SQL 开头的空白、行注释与块注释，再判断首关键字。
     * 不处理这一层的话，{@code "-- 核对\nSELECT ..."} 会被误判成非只读。
     */
    private static String stripLeadingComments(String sql) {
        String remaining = sql;
        while (true) {
            remaining = remaining.stripLeading();
            if (remaining.startsWith("--")) {
                int lineEnd = remaining.indexOf('\n');
                if (lineEnd < 0) {
                    return "";
                }
                remaining = remaining.substring(lineEnd + 1);
            } else if (remaining.startsWith("/*")) {
                int commentEnd = remaining.indexOf("*/");
                if (commentEnd < 0) {
                    return "";
                }
                remaining = remaining.substring(commentEnd + 2);
            } else {
                return remaining;
            }
        }
    }

    private static String abbreviate(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.length() > 300 ? sql.substring(0, 300) + "..." : sql;
    }

    private static String error(String message) {
        ObjectNode node = JsonUtil.newObject();
        node.put("success", false);
        node.put("error", (message == null || message.isBlank()) ? "未知错误" : message);
        return node.toString();
    }

    private static String error(NineDataException e) {
        ObjectNode node = JsonUtil.newObject();
        node.put("success", false);
        node.put("error", e.getMessage());
        if (e.getHttpStatus() != null) {
            node.put("httpStatus", e.getHttpStatus());
        }
        if (e.getRequestId() != null && !e.getRequestId().isBlank()) {
            node.put("requestId", e.getRequestId());
        }
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            node.put("response", e.getResponseBody());
        }
        return node.toString();
    }
}
