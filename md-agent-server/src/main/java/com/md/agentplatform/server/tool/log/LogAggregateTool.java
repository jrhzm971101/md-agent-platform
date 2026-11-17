package com.md.agentplatform.server.tool.log;

import com.md.agentplatform.model.rsp.OpenObserveSearchRsp;
import com.md.agentplatform.server.service.OpenObserveService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 日志时序聚合工具 —— 调用 OpenObserveService.aggregate()，内部使用 histogram SQL。
 */
@Component
public class LogAggregateTool {

    private static final Logger logger = LoggerFactory.getLogger(LogAggregateTool.class);

    @Autowired
    private OpenObserveService openObserveService;

    @Tool(
            name = "log-aggregate",
            description = """
                    日志时序聚合统计工具，按指定时间间隔对日志量进行分桶统计，支持按关键字过滤。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，OpenObserve 连接信息从环境目录解析

                    时间间隔选择建议:
                    - 近 1 小时 → 30 seconds 或 1 minute
                    - 近 1 天 → 15 minutes 或 1 hour
                    - 近 7 天 → 1 hour
                    - 近 30 天 → 1 days
                    """
    )
    public String aggregate(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "stream", description = "日志流名称，必填，如 risk_open_api、lms_backend") String stream,
            @ToolParam(name = "interval", description = "分桶时间粒度，必填，如 30 seconds、1 minute、1 hour、1 days") String interval,
            @ToolParam(name = "keyword", description = "日志过滤关键字，选填，如 NullPointerException") String keyword,
            @ToolParam(name = "startTime", description = "查询起始时间，必填，格式 yyyy-MM-dd HH:mm:ss") String startTime,
            @ToolParam(name = "endTime", description = "查询结束时间，必填，格式 yyyy-MM-dd HH:mm:ss") String endTime,
            @ToolParam(name = "size", description = "返回的时间桶数量上限，选填，默认 200") String size
    ) {
        logger.info("log-aggregate called, environment:{}, stream:{}, interval:{}", environment, stream, interval);

        if (environment == null || environment.isBlank()) {
            return "[log-aggregate] 参数缺失: environment 必填";
        }

        try {
            long startMicros = LogSearchTool.toMicros(startTime);
            long endMicros = LogSearchTool.toMicros(endTime);
            int s = LogSearchTool.parseSize(size, 200);

            String filter = (keyword != null && !keyword.isBlank())
                    ? "str_match_ignore_case(log, '" + LogSearchTool.escapeSqlString(keyword) + "')"
                    : null;

            OpenObserveSearchRsp rsp = openObserveService.aggregate(
                    environment, stream, interval, filter, startMicros, endMicros, s);

            return formatAggregateResult(rsp, stream, interval, keyword, startTime, endTime);
        } catch (Exception e) {
            logger.error("log-aggregate 执行失败, stream:{}, error:{}", stream, e.getMessage(), e);
            return "[log-aggregate] 查询失败: " + e.getMessage();
        }
    }

    private String formatAggregateResult(OpenObserveSearchRsp rsp, String stream,
                                         String interval, String keyword,
                                         String startTime, String endTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("[log-aggregate] 流: ").append(stream)
                .append(", 间隔: ").append(interval);
        if (keyword != null && !keyword.isBlank()) {
            sb.append(", 过滤: ").append(keyword);
        }
        sb.append(", 时间范围: ").append(startTime).append(" ~ ").append(endTime)
                .append(", 桶数: ").append(rsp.getHits() != null ? rsp.getHits().size() : 0).append("\n");

        if (rsp.getHits() == null || rsp.getHits().isEmpty()) {
            sb.append("(无匹配数据)");
            return sb.toString();
        }

        List<Map<String, Object>> hits = rsp.getHits();
        Map<String, Object> peak = hits.get(0);
        long totalCount = 0;
        for (Map<String, Object> hit : hits) {
            Object num = hit.get("num");
            if (num instanceof Number) {
                long n = ((Number) num).longValue();
                totalCount += n;
                if (n > ((Number) peak.getOrDefault("num", 0)).longValue()) {
                    peak = hit;
                }
            }
        }
        sb.append("总日志量: ").append(totalCount).append(", 峰值时段: ")
                .append(peak.get("key")).append(" (").append(peak.get("num")).append(" 条)\n");

        sb.append("---\n");
        sb.append(String.format("%-22s %s\n", "时间桶", "数量"));
        for (Map<String, Object> hit : hits) {
            Object key = hit.get("key");
            Object num = hit.get("num");
            sb.append(String.format("%-22s %s\n",
                    key != null ? key.toString() : "-",
                    num != null ? num.toString() : "0"));
        }
        return sb.toString();
    }
}
