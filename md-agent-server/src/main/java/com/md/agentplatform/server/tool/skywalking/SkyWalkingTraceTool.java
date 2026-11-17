package com.md.agentplatform.server.tool.skywalking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * SkyWalking 链路查询工具 —— 按 traceId + 时间范围拉取完整调用链。
 *
 * <p>GraphQL 查询固定，只由 traceId / duration 驱动；返回结果被压缩成
 * 「时间线 + 慢 span + 外部调用 + SQL 汇总 + 错误详情」文本，便于模型直接做耗时分析。
 */
@Component
public class SkyWalkingTraceTool {

    private static final Logger log = LoggerFactory.getLogger(SkyWalkingTraceTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定的 GraphQL 查询：字段只保留耗时分析需要的，不返回 logs / refs / attachedEvents。 */
    private static final String QUERY = """
            query queryTraceSpansFromColdStage($traceId: ID!, $duration: Duration!, $debug: Boolean) {
              trace: queryTrace(traceId: $traceId, duration: $duration, debug: $debug) {
                spans {
                  segmentId spanId parentSpanId serviceCode startTime endTime
                  endpointName type component peer isError layer
                  tags { key value }
                }
              }
            }
            """;

    private static final Set<String> VALID_STEPS = Set.of("SECOND", "MINUTE", "HOUR", "DAY");

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    );

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final int MAX_TIMELINE_SPANS = 500;
    private static final int MAX_SLOW_SPANS = 15;
    private static final int MAX_EXTERNAL_CALLS = 50;
    private static final int MAX_SQLS = 50;
    private static final int MAX_ERRORS = 10;
    private static final int MAX_SQL_CHARS = 600;
    private static final int MAX_STACK_CHARS = 1200;

    private final SkyWalkingClient client;

    public SkyWalkingTraceTool(SkyWalkingClient client) {
        this.client = client;
    }

    @Tool(
            name = "skywalking-trace",
            description = """
                    按 traceId 查询 SkyWalking 完整调用链，返回各 span 的耗时时间线，用于定位慢接口、
                    慢外部调用、重复 SQL 与异常节点。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，SkyWalking 地址与认证从环境目录解析
                    - traceId: 链路 ID（必填），从日志 / 告警 / 上游系统获取
                    - startTime: 查询起始时间（必填），如 2026-11-10 01:46
                    - endTime: 查询结束时间（必填），如 2026-11-10 02:16
                    - step: 时间分桶粒度（选填），SECOND | MINUTE | HOUR | DAY，默认 MINUTE

                    时间范围必须覆盖 trace 发生时间，否则查不到数据。
                    返回内容: 汇总信息、span 时间线、Top 慢 span、外部 HTTP 调用、SQL 汇总、错误 span 详情。
                    """
    )
    public String queryTrace(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat，连接信息从环境目录解析") String environment,
            @ToolParam(name = "traceId", description = "链路 ID（必填），如 ac2ba5a7...37823.17942762637690061") String traceId,
            @ToolParam(name = "startTime", description = "查询起始时间（必填），如 2026-11-10 01:46") String startTime,
            @ToolParam(name = "endTime", description = "查询结束时间（必填），如 2026-11-10 02:16") String endTime,
            @ToolParam(name = "step", description = "时间分桶粒度（选填），SECOND | MINUTE | HOUR | DAY，默认 MINUTE") String step
    ) {
        log.info("skywalking-trace called, environment:{}, traceId:{}, start:{}, end:{}, step:{}",
                environment, traceId, startTime, endTime, step);

        if (isBlank(environment)) {
            return "[skywalking-trace] 参数缺失: environment 必填";
        }
        if (isBlank(traceId)) {
            return "[skywalking-trace] 参数缺失: traceId 必填";
        }
        if (isBlank(startTime) || isBlank(endTime)) {
            return "[skywalking-trace] 参数缺失: startTime / endTime 必填，且时间范围必须覆盖 trace 发生时间";
        }

        String normalizedStep = normalizeStep(step);
        if (normalizedStep == null) {
            return "[skywalking-trace] step 非法: " + step + "，可选值 SECOND / MINUTE / HOUR / DAY";
        }

        LocalDateTime start = parseTime(startTime);
        LocalDateTime end = parseTime(endTime);
        if (start == null || end == null) {
            return "[skywalking-trace] 时间格式无法识别: " + startTime + " ~ " + endTime
                    + "，支持 yyyy-MM-dd HH:mm[:ss]、yyyy-MM-dd HHmm、yyyy-MM-dd HH、yyyy-MM-dd";
        }
        if (start.isAfter(end)) {
            return "[skywalking-trace] 时间范围非法: startTime 不能晚于 endTime";
        }

        try {
            JsonNode root = MAPPER.readTree(postGraphQl(environment, traceId.trim(), startTime.trim(),
                    endTime.trim(), normalizedStep));

            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                List<String> messages = new ArrayList<>();
                errors.forEach(e -> messages.add(e.path("message").asText("unknown error")));
                return "[skywalking-trace] GraphQL 错误: " + String.join("; ", messages);
            }

            JsonNode trace = root.path("data").path("trace");
            JsonNode spansNode = trace.path("spans");
            if (trace.isMissingNode() || trace.isNull() || !spansNode.isArray() || spansNode.isEmpty()) {
                return "[skywalking-trace] 未查询到链路: traceId=" + traceId
                        + "，时间范围=" + startTime + " ~ " + endTime + "，step=" + normalizedStep
                        + "。请确认 traceId 正确、时间范围覆盖链路时间，且 step 与时间粒度匹配。";
            }

            List<Span> spans = parseSpans(spansNode);
            return formatTrace(traceId.trim(), startTime.trim(), endTime.trim(), normalizedStep, spans);

        } catch (HttpStatusCodeException e) {
            return "[skywalking-trace] 查询失败, HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString(), 500);
        } catch (Exception e) {
            log.error("skywalking-trace 执行失败, traceId:{}, error:{}", traceId, e.getMessage(), e);
            return "[skywalking-trace] 查询异常: " + e.getMessage();
        }
    }

    private String postGraphQl(String environment, String traceId, String startTime, String endTime,
                               String step) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", QUERY);
        ObjectNode variables = payload.putObject("variables");
        variables.put("traceId", traceId);
        ObjectNode duration = variables.putObject("duration");
        duration.put("start", startTime);
        duration.put("end", endTime);
        duration.put("step", step);

        return client.post(environment, payload);
    }

    // ==================== 解析 ====================

    private List<Span> parseSpans(JsonNode spansNode) {
        List<Span> spans = new ArrayList<>(spansNode.size());
        for (JsonNode n : spansNode) {
            Map<String, String> tags = new LinkedHashMap<>();
            JsonNode tagsNode = n.path("tags");
            if (tagsNode.isArray()) {
                for (JsonNode tag : tagsNode) {
                    tags.put(tag.path("key").asText(""), tag.path("value").asText(""));
                }
            }
            spans.add(new Span(
                    n.path("startTime").asLong(),
                    n.path("endTime").asLong(),
                    n.path("segmentId").asText(""),
                    n.path("spanId").asLong(),
                    n.path("parentSpanId").asLong(-1),
                    n.path("serviceCode").asText(""),
                    n.path("type").asText(""),
                    n.path("endpointName").asText(""),
                    n.path("component").asText(""),
                    n.path("peer").asText(""),
                    n.path("isError").asBoolean(false),
                    n.path("layer").asText(""),
                    tags,
                    buildErrorDetail(n)
            ));
        }
        spans.sort(Comparator.comparingLong(Span::startTime).thenComparingLong(Span::spanId));
        return spans;
    }

    private String buildErrorDetail(JsonNode span) {
        if (!span.path("isError").asBoolean(false)) {
            return "";
        }
        StringBuilder detail = new StringBuilder();
        JsonNode logs = span.path("logs");
        if (logs.isArray()) {
            for (JsonNode logNode : logs) {
                for (JsonNode data : logNode.path("data")) {
                    String key = data.path("key").asText("");
                    String value = data.path("value").asText("");
                    if ("stack".equals(key)) {
                        value = abbreviate(value, MAX_STACK_CHARS);
                    }
                    detail.append(key).append(": ").append(value).append('\n');
                }
            }
        }
        return detail.toString().strip();
    }

    // ==================== 格式化 ====================

    private String formatTrace(String traceId, String startTime, String endTime, String step, List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        long base = spans.stream().mapToLong(Span::startTime).min().orElse(0);
        long maxEnd = spans.stream().mapToLong(Span::endTime).max().orElse(base);
        long errors = spans.stream().filter(Span::error).count();

        sb.append("[skywalking-trace] traceId=").append(traceId)
                .append("  spans=").append(spans.size())
                .append("  total=").append(maxEnd - base).append("ms")
                .append("  errors=").append(errors).append('\n');
        sb.append("window: ").append(startTime).append(" ~ ").append(endTime)
                .append("  step=").append(step).append('\n');
        sb.append("services: ").append(servicesSummary(spans)).append('\n');

        appendTimeline(sb, spans, base);
        appendSlowSpans(sb, spans, base);
        appendExternalCalls(sb, spans, base);
        appendSqlSummary(sb, spans);
        appendErrors(sb, spans);

        return sb.toString().strip();
    }

    private String servicesSummary(List<Span> spans) {
        Map<String, Long> counts = spans.stream()
                .collect(Collectors.groupingBy(Span::shortService, TreeMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .map(e -> e.getKey() + "(" + e.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    private void appendTimeline(StringBuilder sb, List<Span> spans, long base) {
        sb.append("\n# TIMELINE (relStart -> relEnd, ms from trace start)\n");
        int printed = 0;
        for (Span span : spans) {
            if (printed >= MAX_TIMELINE_SPANS) {
                sb.append("... (").append(spans.size() - MAX_TIMELINE_SPANS).append(" spans truncated)\n");
                break;
            }
            appendSpanLine(sb, span, base);
            printed++;
        }
    }

    private void appendSlowSpans(StringBuilder sb, List<Span> spans, long base) {
        sb.append("\n# SLOW SPANS (top ").append(MAX_SLOW_SPANS).append(")\n");
        spans.stream()
                .sorted(Comparator.comparingLong(Span::duration).reversed())
                .limit(MAX_SLOW_SPANS)
                .forEach(span -> appendSpanLine(sb, span, base));
    }

    private void appendExternalCalls(StringBuilder sb, List<Span> spans, long base) {
        List<Span> calls = spans.stream()
                .filter(span -> "Exit".equalsIgnoreCase(span.type()))
                .filter(span -> "Http".equalsIgnoreCase(span.layer())
                        || "RPC".equalsIgnoreCase(span.layer())
                        || "MQ".equalsIgnoreCase(span.layer()))
                .toList();

        sb.append("\n# EXTERNAL CALLS (").append(calls.size()).append(")\n");
        calls.stream().limit(MAX_EXTERNAL_CALLS).forEach(span -> {
            sb.append(String.format(Locale.ROOT, "%7d -> %7d  dur=%6dms  %-14s %-12s %s",
                    span.startTime() - base, span.endTime() - base, span.duration(),
                    span.shortService(), span.component(), span.endpointName()));
            String url = span.tags().get("url");
            if (!isBlank(url)) {
                sb.append("  url=").append(abbreviate(url, 300));
            }
            sb.append('\n');
        });
    }

    private void appendSqlSummary(StringBuilder sb, List<Span> spans) {
        List<Span> dbSpans = spans.stream()
                .filter(span -> "Database".equalsIgnoreCase(span.layer()))
                .filter(span -> !isBlank(span.tags().get("db.statement")))
                .toList();

        long sum = dbSpans.stream().mapToLong(Span::duration).sum();
        long max = dbSpans.stream().mapToLong(Span::duration).max().orElse(0);
        sb.append("\n# SQL (").append(dbSpans.size()).append(" spans, sum=").append(sum)
                .append("ms, max=").append(max).append("ms)\n");

        if (dbSpans.isEmpty()) {
            sb.append("(no SQL spans)\n");
            return;
        }

        Map<String, SqlStat> stats = new LinkedHashMap<>();
        for (Span span : dbSpans) {
            String sql = span.tags().get("db.statement").replaceAll("\\s+", " ").trim();
            stats.computeIfAbsent(sql, key -> new SqlStat()).add(span);
        }
        stats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().max, a.getValue().max))
                .limit(MAX_SQLS)
                .forEach(entry -> {
                    SqlStat stat = entry.getValue();
                    sb.append("x").append(stat.count)
                            .append("  max=").append(stat.max).append("ms")
                            .append(" sum=").append(stat.sum).append("ms")
                            .append("  [").append(stat.service).append("] ")
                            .append(abbreviate(entry.getKey(), MAX_SQL_CHARS))
                            .append('\n');
                });
    }

    private void appendErrors(StringBuilder sb, List<Span> spans) {
        List<Span> errors = spans.stream().filter(Span::error).toList();
        sb.append("\n# ERRORS (").append(errors.size()).append(")\n");
        if (errors.isEmpty()) {
            sb.append("(no error spans)\n");
            return;
        }
        int index = 1;
        for (Span span : errors.stream().limit(MAX_ERRORS).toList()) {
            sb.append('[').append(index++).append("] ")
                    .append(span.shortService()).append(" | ")
                    .append(span.type()).append(" | ")
                    .append(span.endpointName()).append(" | dur=")
                    .append(span.duration()).append("ms\n");

            String status = span.tags().get("http.status_code");
            if (!isBlank(status)) {
                sb.append("    http.status_code: ").append(status).append('\n');
            }
            String url = span.tags().get("url");
            if (!isBlank(url)) {
                sb.append("    url: ").append(abbreviate(url, 300)).append('\n');
            }
            if (!isBlank(span.errorDetail())) {
                sb.append(indent(span.errorDetail(), "    ")).append('\n');
            }
        }
    }

    private void appendSpanLine(StringBuilder sb, Span span, long base) {
        sb.append(String.format(Locale.ROOT, "%7d -> %7d  dur=%6dms  %-14s %-5s %s",
                span.startTime() - base, span.endTime() - base, span.duration(),
                span.shortService(), span.type(), span.endpointName()));
        if (!isBlank(span.peer())) {
            sb.append("  peer=").append(span.peer());
        }
        String status = span.tags().get("http.status_code");
        if (!isBlank(status)) {
            sb.append("  status=").append(status);
        }
        if (span.error()) {
            sb.append("  [ERROR]");
        }
        sb.append('\n');
    }

    // ==================== 工具方法 ====================

    private static String normalizeStep(String step) {
        if (isBlank(step)) {
            return "MINUTE";
        }
        String normalized = step.trim().toUpperCase(Locale.ROOT);
        return VALID_STEPS.contains(normalized) ? normalized : null;
    }

    private static LocalDateTime parseTime(String value) {
        String trimmed = value == null ? "" : value.trim();
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        // 仅日期
        try {
            return LocalDate.parse(trimmed, DATE_FORMAT).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 继续尝试仅小时
        }
        // yyyy-MM-dd H / yyyy-MM-dd HH
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}")) {
            try {
                LocalDate date = LocalDate.parse(trimmed.substring(0, 10), DATE_FORMAT);
                int hour = Integer.parseInt(trimmed.substring(10).trim());
                return date.atStartOfDay().plusHours(hour);
            } catch (Exception ignored) {
                // 落到无法识别
            }
        }
        return null;
    }

    private static String indent(String text, String prefix) {
        return text.lines()
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Span(
            long startTime,
            long endTime,
            String segmentId,
            long spanId,
            long parentSpanId,
            String serviceCode,
            String type,
            String endpointName,
            String component,
            String peer,
            boolean error,
            String layer,
            Map<String, String> tags,
            String errorDetail
    ) {
        long duration() {
            return Math.max(0, endTime - startTime);
        }

        String shortService() {
            int index = serviceCode.lastIndexOf("::");
            return index >= 0 ? serviceCode.substring(index + 2) : serviceCode;
        }
    }

    private static final class SqlStat {
        private int count;
        private long sum;
        private long max;
        private String service;

        void add(Span span) {
            count++;
            long duration = span.duration();
            sum += duration;
            max = Math.max(max, duration);
            if (service == null) {
                service = span.shortService();
            }
        }
    }
}
