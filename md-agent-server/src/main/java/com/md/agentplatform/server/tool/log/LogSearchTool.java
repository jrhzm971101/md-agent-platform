package com.md.agentplatform.server.tool.log;

import com.md.agentplatform.model.req.OpenObserveSearchReq;
import com.md.agentplatform.model.rsp.OpenObserveSearchRsp;
import com.md.agentplatform.server.service.OpenObserveService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 日志关键字搜索工具 —— 调用 OpenObserve /_search 接口检索日志。
 */
@Component
public class LogSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(LogSearchTool.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private OpenObserveService openObserveService;

    @Tool(
            name = "log-search",
            description = """
                    日志关键字搜索工具，根据关键字从指定日志流中检索匹配的日志记录。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，OpenObserve 连接信息从环境目录解析

                    SQL 规则（OpenObserve 方言）:
                    - 流名用双引号: FROM "lms_backend"
                    - 字符串用单引号: str_match_ignore_case(log, 'ERROR')
                    - 时间戳单位为微秒（μs）
                    - 默认使用 str_match_ignore_case 忽略大小写匹配 log 字段
                    - 结果按 _timestamp DESC 排序
                    """
    )
    public String search(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "stream", description = "日志流名称，必填，如 lms_backend、risk_open_api") String stream,
            @ToolParam(name = "keyword", description = "搜索关键字，必填，如 NullPointerException。关键字内部单引号需转义") String keyword,
            @ToolParam(name = "startTime", description = "查询起始时间，必填，格式 yyyy-MM-dd HH:mm:ss") String startTime,
            @ToolParam(name = "endTime", description = "查询结束时间，必填，格式 yyyy-MM-dd HH:mm:ss") String endTime,
            @ToolParam(name = "size", description = "返回条数，选填，默认 20，上限 200") String size
    ) {
        logger.info("log-search called, environment:{}, stream:{}, keyword:{}", environment, stream, keyword);

        if (environment == null || environment.isBlank()) {
            return "[log-search] 参数缺失: environment 必填";
        }

        try {
            int limit = parseSize(size, 20);
            long startMicros = toMicros(startTime);
            long endMicros = toMicros(endTime);

            String sql = "SELECT * FROM \"" + stream + "\" "
                    + "WHERE str_match_ignore_case(log, '" + escapeSqlString(keyword) + "') "
                    + "ORDER BY _timestamp DESC "
                    + "LIMIT " + limit;

            OpenObserveSearchRsp rsp = openObserveService.search(environment, stream,
                    OpenObserveSearchReq.builder()
                            .query(OpenObserveSearchReq.QueryBody.builder()
                                    .sql(sql)
                                    .startTime(startMicros)
                                    .endTime(endMicros)
                                    .size(limit)
                                    .build())
                            .build());

            return formatSearchResult(rsp, stream, keyword);
        } catch (Exception e) {
            logger.error("log-search 执行失败, stream:{}, keyword:{}, error:{}", stream, keyword, e.getMessage(), e);
            return "[log-search] 查询失败: " + e.getMessage();
        }
    }

    private String formatSearchResult(OpenObserveSearchRsp rsp, String stream, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append("[log-search] 流: ").append(stream)
                .append(", 关键字: ").append(keyword)
                .append(", 匹配: ").append(rsp.getTotal() != null ? rsp.getTotal() : rsp.getHits() != null ? rsp.getHits().size() : 0).append(" 条")
                .append(", 耗时: ").append(rsp.getTook()).append("ms\n");

        if (rsp.getHits() == null || rsp.getHits().isEmpty()) {
            sb.append("(无匹配结果)");
            return sb.toString();
        }

        int count = 0;
        for (Map<String, Object> hit : rsp.getHits()) {
            if (count >= 20) { sb.append("...(更多结果已截断)\n"); break; }
            sb.append("---\n");
            Object ts = hit.get("_timestamp");
            if (ts instanceof Number) {
                long micros = ((Number) ts).longValue();
                sb.append("_timestamp: ").append(formatMicros(micros)).append(" (").append(micros).append(")\n");
            } else {
                sb.append("_timestamp: ").append(ts).append("\n");
            }
            Object log = hit.get("log");
            if (log != null) {
                String logStr = log.toString();
                if (logStr.length() > 500) {
                    logStr = logStr.substring(0, 500) + "...";
                }
                sb.append("log: ").append(logStr).append("\n");
            }
            count++;
        }
        return sb.toString();
    }

    static long toMicros(String timeStr) {
        return LocalDateTime.parse(timeStr, DTF)
                .atZone(ZONE).toInstant().toEpochMilli() * 1000L;
    }

    static String formatMicros(long micros) {
        return LocalDateTime.ofEpochSecond(micros / 1_000_000,
                (int) ((micros % 1_000_000) / 1000) * 1_000_000,
                java.time.ZoneOffset.ofHours(8)).format(DTF);
    }

    static String escapeSqlString(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    static int parseSize(String size, int defaultVal) {
        if (size == null || size.isBlank()) {
            return defaultVal;
        }
        try {
            int v = Integer.parseInt(size.trim());
            return Math.min(v, 200);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
