package com.md.agentplatform.server.tool.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Grafana 查询工具。
 *
 * <p>核心是 {@code POST /api/ds/query}：与 Grafana 面板走同一条查询链路，
 * 支持 instant / range 两种模式，也支持指标名、标签、series 的发现接口。
 * 只读，不写数据。
 */
@Component
public class GrafanaQueryTool {

    private static final Logger log = LoggerFactory.getLogger(GrafanaQueryTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter OUTPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter POINT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter POINT_TIME_LONG = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    );
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final int MAX_EXPRS = 5;
    private static final int MAX_SERIES = 10;
    private static final int MAX_POINTS = 60;
    private static final int MAX_DISCOVERY = 200;

    private final GrafanaClient client;

    public GrafanaQueryTool(GrafanaClient client) {
        this.client = client;
    }

    @Tool(
            name = "grafana-query",
            description = """
                    查询 Grafana / Prometheus 指标（只读）。
                    主接口与 Grafana 面板一致：POST /api/ds/query，返回时间序列。

                    action 取值:
                    - query（默认）: 执行 PromQL；expr 多行表示多个查询（最多 5 条）
                    - metric_names: 列出指标名，keyword 过滤（写 PromQL 前先确认指标存在）
                    - labels: 列出标签名，keyword 过滤
                    - label_values: 列出某个标签的取值，label 必填
                    - series: 按 match[] 选择器列出序列，expr 填 {job="..."} 这类选择器

                    参数:
                    - environment: 环境名（必填），如 cameroon-uat，Grafana 地址与认证从环境目录解析
                    - expr: PromQL 或 series 选择器（action=query/series 时必填）
                    - mode: range（默认）| instant
                    - last: 最近时间窗口，如 15m / 1h / 6h / 24h / 7d，默认 1h
                    - startTime / endTime: 明确时间范围，如 2026-11-11 09:00；按 +08 解释
                    - step: 采样步长，如 30s / 1m / 5m；不传由 maxDataPoints 自动计算
                    - datasourceUid: 数据源 UID，默认 DmKRmGQvk（prometheus）
                    - keyword: metric_names / labels / label_values 的过滤词
                    - label: label_values 的标签名，如 namespace、pod、service

                    输出默认最多 10 条序列、每条最多 60 个采样点，按最后值倒序。
                    时间参数按 Asia/Shanghai（+08）解释；返回时间统一标注 +08。
                    """
    )
    public String query(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat，连接信息从环境目录解析") String environment,
            @ToolParam(name = "action", description = "query | metric_names | labels | label_values | series，默认 query") String action,
            @ToolParam(name = "expr", description = "PromQL 或 series 选择器；多条查询用换行分隔，最多 5 条") String expr,
            @ToolParam(name = "mode", description = "range | instant，默认 range") String mode,
            @ToolParam(name = "startTime", description = "起始时间（选填），如 2026-11-11 09:00，按 +08 解释") String startTime,
            @ToolParam(name = "endTime", description = "结束时间（选填），如 2026-11-11 10:00，按 +08 解释") String endTime,
            @ToolParam(name = "last", description = "最近窗口（选填），如 15m / 1h / 6h / 24h / 7d，默认 1h") String last,
            @ToolParam(name = "step", description = "采样步长（选填），如 30s / 1m / 5m") String step,
            @ToolParam(name = "datasourceUid", description = "数据源 UID（选填），默认 DmKRmGQvk") String datasourceUid,
            @ToolParam(name = "keyword", description = "指标名/标签过滤词（选填）") String keyword,
            @ToolParam(name = "label", description = "label_values 的标签名（选填），如 namespace") String label
    ) {
        String normalized = isBlank(action) ? "query" : action.trim().toLowerCase(Locale.ROOT);
        log.info("grafana-query called, environment:{}, action:{}, mode:{}, expr:{}, last:{}, start:{}, end:{}",
                environment, normalized, mode, abbreviate(expr, 200), last, startTime, endTime);

        if (isBlank(environment)) {
            return "[grafana-query] 参数缺失: environment 必填";
        }

        try {
            return switch (normalized) {
                case "query" -> runQuery(environment, expr, mode, startTime, endTime, last, step, datasourceUid);
                case "metric_names" -> discoverNames(environment, "metric", keyword, datasourceUid);
                case "labels" -> discoverNames(environment, "label", keyword, datasourceUid);
                case "label_values" -> discoverLabelValues(environment, label, keyword, datasourceUid);
                case "series" -> discoverSeries(environment, expr, startTime, endTime, last, datasourceUid, keyword);
                default -> "[grafana-query] action 非法: " + action
                        + "，可选 query / metric_names / labels / label_values / series";
            };
        } catch (HttpStatusCodeException e) {
            return "[grafana-query] 查询失败, HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString(), 500);
        } catch (IllegalArgumentException e) {
            return "[grafana-query] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            log.error("grafana-query 执行失败, error:{}", e.getMessage(), e);
            return "[grafana-query] 查询异常: " + e.getMessage();
        }
    }

    // ==================== PromQL ====================

    private String runQuery(String environment, String expr, String mode, String startTime, String endTime,
                            String last, String step, String datasourceUid) {
        List<String> exprs = splitExprs(expr);
        if (exprs.isEmpty()) {
            return "[grafana-query] expr 不能为空";
        }
        if (exprs.size() > MAX_EXPRS) {
            return "[grafana-query] expr 最多 " + MAX_EXPRS + " 条，当前 " + exprs.size() + " 条";
        }

        String normalizedMode = isBlank(mode) ? "range" : mode.trim();
        if (!"range".equalsIgnoreCase(normalizedMode) && !"instant".equalsIgnoreCase(normalizedMode)) {
            return "[grafana-query] mode 非法: " + mode + "，可选 range / instant";
        }
        boolean instant = "instant".equalsIgnoreCase(normalizedMode);

        long[] window = resolveWindow(startTime, endTime, last);
        if (window == null) {
            return "[grafana-query] 时间格式无法识别: " + startTime + " ~ " + endTime
                    + "，支持 yyyy-MM-dd HH:mm[:ss]、yyyy-MM-dd HHmm、yyyy-MM-dd、ISO offset";
        }
        long from = window[0];
        long to = window[1];
        if (from >= to) {
            return "[grafana-query] 时间范围非法: startTime 必须早于 endTime";
        }

        long range = to - from;
        long stepMs = parseDurationMillis(step);
        long intervalMs;
        long maxDataPoints;
        if (stepMs > 0) {
            intervalMs = stepMs;
            maxDataPoints = Math.max(2, Math.min(2000, range / stepMs + 1));
        } else {
            maxDataPoints = instant ? 1 : 60;
            intervalMs = instant ? 60000 : Math.max(1000, range / maxDataPoints);
        }

        String uid = isBlank(datasourceUid) ? client.defaultDatasourceUid() : datasourceUid.trim();
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode queries = body.putArray("queries");
        char ref = 'A';
        for (String item : exprs) {
            ObjectNode query = queries.addObject();
            query.put("refId", String.valueOf(ref++));
            ObjectNode ds = query.putObject("datasource");
            ds.put("type", "prometheus");
            ds.put("uid", uid);
            query.put("expr", item);
            query.put("instant", instant);
            query.put("range", !instant);
            query.put("intervalMs", intervalMs);
            query.put("maxDataPoints", maxDataPoints);
        }
        body.put("from", String.valueOf(from));
        body.put("to", String.valueOf(to));

        JsonNode root = client.post(environment, "/api/ds/query", body);
        if (root.hasNonNull("message") && !root.has("results")) {
            return "[grafana-query] Grafana 返回错误: " + root.path("message").asText("");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-query] action=query  mode=").append(instant ? "instant" : "range")
                .append("  exprs=").append(exprs.size())
                .append("  datasource=").append(uid)
                .append("  window=").append(formatTime(from)).append(" ~ ").append(formatTime(to)).append(" (+08)")
                .append("  step=").append(intervalMs).append("ms").append('\n');

        JsonNode results = root.path("results");
        for (int i = 0; i < exprs.size(); i++) {
            String refId = String.valueOf((char) ('A' + i));
            String currentExpr = exprs.get(i);
            sb.append("\n# QUERY ").append(refId).append('\n');
            sb.append("expr: ").append(currentExpr).append('\n');

            JsonNode result = results.path(refId);
            if (result.isMissingNode()) {
                sb.append("无结果: results.").append(refId).append(" 缺失\n");
                continue;
            }
            if (result.hasNonNull("error")) {
                sb.append("error: ").append(result.path("error").asText("")).append('\n');
                continue;
            }
            JsonNode frames = result.path("frames");
            if (!frames.isArray() || frames.isEmpty()) {
                sb.append("无数据：检查指标名、标签过滤和时间窗口\n");
                continue;
            }

            List<Series> seriesList = parseFrames(frames);
            seriesList.sort(Comparator.comparingDouble(Series::lastValue).reversed());
            sb.append("series=").append(seriesList.size())
                    .append("  shown=").append(Math.min(MAX_SERIES, seriesList.size()))
                    .append("  (按最后值倒序)\n");

            int shown = 0;
            for (Series series : seriesList) {
                if (shown++ >= MAX_SERIES) {
                    sb.append("... 其余 ").append(seriesList.size() - MAX_SERIES).append(" 条序列已省略\n");
                    break;
                }
                sb.append('[').append(shown).append("] ").append(formatLabels(series.labels()));
                if (instant) {
                    sb.append("  value=").append(formatNumber(series.lastValue())).append('\n');
                } else {
                    sb.append("  count=").append(series.count())
                            .append(" min=").append(formatNumber(series.min()))
                            .append(" max=").append(formatNumber(series.max()))
                            .append(" avg=").append(formatNumber(series.avg()))
                            .append(" last=").append(formatNumber(series.lastValue())).append('\n');
                    sb.append("    points: ").append(formatPoints(series)).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    private static List<Series> parseFrames(JsonNode frames) {
        List<Series> result = new ArrayList<>();
        for (JsonNode frame : frames) {
            JsonNode schema = frame.path("schema");
            JsonNode fields = schema.path("fields");
            Map<String, String> labels = new LinkedHashMap<>();
            int timeIdx = -1;
            int valueIdx = -1;
            for (int i = 0; i < fields.size(); i++) {
                JsonNode field = fields.get(i);
                String type = field.path("type").asText("");
                if ("time".equals(type) && timeIdx < 0) {
                    timeIdx = i;
                }
                if ("number".equals(type) && valueIdx < 0) {
                    valueIdx = i;
                }
                JsonNode labelNode = field.path("labels");
                if (labelNode.isObject()) {
                    labelNode.fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText("")));
                }
            }
            if (valueIdx < 0) {
                continue;
            }
            if (timeIdx < 0) {
                timeIdx = 0;
            }
            JsonNode values = frame.path("data").path("values");
            if (!values.isArray() || values.size() <= valueIdx) {
                continue;
            }
            JsonNode timeValues = values.get(timeIdx);
            JsonNode numberValues = values.get(valueIdx);
            int size = Math.min(timeValues.size(), numberValues.size());
            long[] times = new long[size];
            double[] numbers = new double[size];
            for (int i = 0; i < size; i++) {
                times[i] = timeValues.get(i).asLong(0);
                numbers[i] = numberValues.get(i).isNull() ? Double.NaN : numberValues.get(i).asDouble();
            }
            if (labels.isEmpty()) {
                String name = schema.path("name").asText("");
                if (!name.isBlank()) {
                    labels.put("__name__", name);
                }
            }
            result.add(new Series(labels, times, numbers));
        }
        return result;
    }

    // ==================== 指标发现 ====================

    private String discoverNames(String environment, String type, String keyword, String datasourceUid) {
        String uid = isBlank(datasourceUid) ? client.defaultDatasourceUid() : datasourceUid.trim();
        String path = client.prometheusPath(uid, "metric".equals(type)
                ? "/api/v1/label/__name__/values" : "/api/v1/labels");
        JsonNode root = client.get(environment, path);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return "[grafana-query] 返回结构异常: data 不是数组";
        }

        String filter = isBlank(keyword) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (JsonNode node : data) {
            String name = node.asText("");
            if (!filter.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            names.add(name);
        }
        names.sort(String::compareTo);

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-query] action=").append("metric".equals(type) ? "metric_names" : "labels")
                .append("  datasource=").append(uid)
                .append("  keyword=").append(isBlank(keyword) ? "-" : keyword.trim())
                .append("  total=").append(data.size())
                .append("  matched=").append(names.size()).append('\n');
        int shown = 0;
        for (String name : names) {
            if (shown++ >= MAX_DISCOVERY) {
                sb.append("... 其余 ").append(names.size() - MAX_DISCOVERY).append(" 项已省略\n");
                break;
            }
            sb.append("- ").append(name).append('\n');
        }
        if (names.isEmpty()) {
            sb.append("未匹配到结果；换更短的 keyword，或先不传 keyword 查看全部。\n");
        }
        return sb.toString().strip();
    }

    private String discoverLabelValues(String environment, String label, String keyword, String datasourceUid) {
        if (isBlank(label) || !label.trim().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return "[grafana-query] action=label_values 需要合法的 label，如 namespace、pod、service";
        }
        String uid = isBlank(datasourceUid) ? client.defaultDatasourceUid() : datasourceUid.trim();
        String path = client.prometheusPath(uid, "/api/v1/label/" + label.trim() + "/values");
        JsonNode root = client.get(environment, path);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return "[grafana-query] 返回结构异常: data 不是数组";
        }
        String filter = isBlank(keyword) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        for (JsonNode node : data) {
            String value = node.asText("");
            if (!filter.isEmpty() && !value.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            values.add(value);
        }
        values.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-query] action=label_values  label=").append(label.trim())
                .append("  datasource=").append(uid)
                .append("  keyword=").append(isBlank(keyword) ? "-" : keyword.trim())
                .append("  total=").append(data.size())
                .append("  matched=").append(values.size()).append('\n');
        int shown = 0;
        for (String value : values) {
            if (shown++ >= MAX_DISCOVERY) {
                sb.append("... 其余 ").append(values.size() - MAX_DISCOVERY).append(" 项已省略\n");
                break;
            }
            sb.append("- ").append(value).append('\n');
        }
        return sb.toString().strip();
    }

    private String discoverSeries(String environment, String selector, String startTime, String endTime, String last,
                                  String datasourceUid, String keyword) {
        if (isBlank(selector)) {
            return "[grafana-query] action=series 需要 expr 作为 match[] 选择器，如 {job=\"kubelet\"}";
        }
        String uid = isBlank(datasourceUid) ? client.defaultDatasourceUid() : datasourceUid.trim();
        long[] window = resolveWindow(startTime, endTime, last);
        if (window == null) {
            return "[grafana-query] 时间格式无法识别: " + startTime + " ~ " + endTime;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("match[]", selector.trim());
        params.put("start", String.valueOf(window[0] / 1000));
        params.put("end", String.valueOf(window[1] / 1000));

        JsonNode root = client.get(environment, client.prometheusPath(uid, "/api/v1/series"), params);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return "[grafana-query] 返回结构异常: data 不是数组";
        }
        String filter = isBlank(keyword) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode node : data) {
            Map<String, String> labels = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText("")));
            if (!filter.isEmpty()) {
                String joined = labels.toString().toLowerCase(Locale.ROOT);
                if (!joined.contains(filter)) {
                    continue;
                }
            }
            rows.add(labels);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-query] action=series  selector=").append(selector.trim())
                .append("  datasource=").append(uid)
                .append("  count=").append(rows.size()).append('\n');
        int shown = 0;
        for (Map<String, String> labels : rows) {
            if (shown++ >= 50) {
                sb.append("... 其余 ").append(rows.size() - 50).append(" 条已省略\n");
                break;
            }
            sb.append("- ").append(formatLabels(labels)).append('\n');
        }
        return sb.toString().strip();
    }

    // ==================== 时间与格式 ====================

    private static List<String> splitExprs(String expr) {
        List<String> result = new ArrayList<>();
        if (isBlank(expr)) {
            return result;
        }
        for (String line : expr.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static long parseDurationMillis(String value) {
        if (isBlank(value)) {
            return 0;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (!text.matches("\\d+[smhdw]")) {
            return 0;
        }
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (text.charAt(text.length() - 1)) {
            case 's' -> amount * 1000;
            case 'm' -> amount * 60_000;
            case 'h' -> amount * 3_600_000;
            case 'd' -> amount * 86_400_000;
            case 'w' -> amount * 604_800_000;
            default -> 0;
        };
    }

    private static long[] resolveWindow(String startTime, String endTime, String last) {
        Long start = parseMillis(startTime);
        Long end = parseMillis(endTime);
        if ((start == null && !isBlank(startTime)) || (end == null && !isBlank(endTime))) {
            return null;
        }
        long lastMillis = parseDurationMillis(last);
        if (lastMillis <= 0) {
            lastMillis = 3_600_000;
        }
        long now = System.currentTimeMillis();
        if (start == null && end == null) {
            end = now;
            start = end - lastMillis;
        } else if (start == null) {
            start = end - lastMillis;
        } else if (end == null) {
            end = now;
            if (end <= start) {
                end = start + lastMillis;
            }
        }
        if (start >= end) {
            return null;
        }
        return new long[]{start, end};
    }

    private static Long parseMillis(String value) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地格式
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, formatter).atZone(CST).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        try {
            return LocalDate.parse(text, DATE_FORMAT).atStartOfDay(CST).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 尝试仅小时
        }
        if (text.matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}")) {
            try {
                LocalDate date = LocalDate.parse(text.substring(0, 10), DATE_FORMAT);
                return date.atStartOfDay(CST).plusHours(Integer.parseInt(text.substring(10).trim()))
                        .toInstant().toEpochMilli();
            } catch (Exception ignored) {
                // 无法识别
            }
        }
        return null;
    }

    private static String formatTime(long millis) {
        return Instant.ofEpochMilli(millis).atZone(CST).format(OUTPUT_TIME);
    }

    private static String formatPoints(Series series) {
        int size = series.times().length;
        if (size == 0) {
            return "-";
        }
        boolean sameDay = Instant.ofEpochMilli(series.times()[0]).atZone(CST).toLocalDate()
                .equals(Instant.ofEpochMilli(series.times()[size - 1]).atZone(CST).toLocalDate());
        DateTimeFormatter formatter = sameDay ? POINT_TIME : POINT_TIME_LONG;
        int stride = Math.max(1, (int) Math.ceil(size / (double) MAX_POINTS));
        StringBuilder sb = new StringBuilder();
        int printed = 0;
        int lastPrinted = -1;
        for (int i = 0; i < size; i += stride) {
            if (printed > 0) {
                sb.append(", ");
            }
            sb.append(Instant.ofEpochMilli(series.times()[i]).atZone(CST).format(formatter))
                    .append('=').append(formatNumber(series.values()[i]));
            printed++;
            lastPrinted = i;
        }
        if (lastPrinted != size - 1) {
            sb.append(", ").append(Instant.ofEpochMilli(series.times()[size - 1]).atZone(CST).format(formatter))
                    .append('=').append(formatNumber(series.values()[size - 1]));
        }
        return sb.toString();
    }

    private static String formatLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "{}";
        }
        List<String> parts = new ArrayList<>();
        labels.forEach((key, value) -> parts.add(key + "=\"" + value + "\""));
        return "{" + String.join(", ", parts) + "}";
    }

    private static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        if (decimal.scale() <= 0) {
            return decimal.toPlainString();
        }
        return decimal.toPlainString();
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

    private record Series(Map<String, String> labels, long[] times, double[] values) {

        double lastValue() {
            for (int i = values.length - 1; i >= 0; i--) {
                if (!Double.isNaN(values[i])) {
                    return values[i];
                }
            }
            return Double.NEGATIVE_INFINITY;
        }

        int count() {
            int count = 0;
            for (double value : values) {
                if (!Double.isNaN(value)) {
                    count++;
                }
            }
            return count;
        }

        double min() {
            double min = Double.NaN;
            for (double value : values) {
                if (!Double.isNaN(value) && (Double.isNaN(min) || value < min)) {
                    min = value;
                }
            }
            return min;
        }

        double max() {
            double max = Double.NaN;
            for (double value : values) {
                if (!Double.isNaN(value) && (Double.isNaN(max) || value > max)) {
                    max = value;
                }
            }
            return max;
        }

        double avg() {
            double sum = 0;
            int count = 0;
            for (double value : values) {
                if (!Double.isNaN(value)) {
                    sum += value;
                    count++;
                }
            }
            return count == 0 ? Double.NaN : sum / count;
        }
    }
}
