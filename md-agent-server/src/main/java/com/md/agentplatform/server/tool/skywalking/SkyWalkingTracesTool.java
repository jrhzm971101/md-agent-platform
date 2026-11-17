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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SkyWalking trace 列表查询工具 —— 按时间范围 + 服务/端点/实例过滤，返回慢或错误的 trace 列表。
 *
 * <p>固定使用 {@code queryBasicTraces}，先定位 traceId，再用 {@link SkyWalkingTraceTool}
 * 查完整调用链。参数默认值对应 prod::open-api 的 /customers/{encryptPhone}。
 */
@Component
public class SkyWalkingTracesTool {

    private static final Logger log = LoggerFactory.getLogger(SkyWalkingTracesTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUERY = """
            query queryTraces($condition: TraceQueryCondition) {
              data: queryBasicTraces(condition: $condition) {
                traces {
                  key: segmentId
                  endpointNames
                  duration
                  start
                  isError
                  traceIds
                }
              }
            }
            """;

    private static final String DEFAULT_SERVICE_ID = "cHJvZDo6b3Blbi1hcGk=.1";
    private static final String DEFAULT_ENDPOINT_ID =
            "cHJvZDo6b3Blbi1hcGk=.1_UE9TVDovY3VzdG9tZXJzL3tlbmNyeXB0UGhvbmV9";
    private static final String DEFAULT_INSTANCE_ID =
            "cHJvZDo6b3Blbi1hcGk=.1_b3Blbi1hcGktNTZjY2I2YjU4OS1mamd4Mg==";

    private static final Set<String> VALID_STEPS = Set.of("SECOND", "MINUTE", "HOUR", "DAY");
    private static final Set<String> VALID_STATES = Set.of("ALL", "SUCCESS", "ERROR");
    private static final Set<String> VALID_ORDERS = Set.of("BY_START_TIME", "BY_DURATION");

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    );

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter START_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private final SkyWalkingClient client;

    public SkyWalkingTracesTool(SkyWalkingClient client) {
        this.client = client;
    }

    @Tool(
            name = "skywalking-traces",
            description = """
                    按时间范围查询 SkyWalking trace 列表（queryBasicTraces），用于先找出慢或错误的 trace，
                    再拿 traceId 调 skywalking-trace 查看完整调用链。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，SkyWalking 地址与认证从环境目录解析
                    - startTime / endTime: 查询时间范围（必填），支持秒/分钟/小时/天粒度；跨度不设上限
                    - step: SECOND | MINUTE | HOUR | DAY，默认 MINUTE
                    - serviceId: 服务 ID，默认 prod::open-api 的 SkyWalking ID
                    - endpointId: 端点 ID，默认 POST:/customers/{encryptPhone}
                    - serviceInstanceId: 实例 ID，默认 open-api-56ccb6b589-fjgx2
                    - traceState: ALL | SUCCESS | ERROR，默认 ALL
                    - queryOrder: BY_DURATION | BY_START_TIME，默认 BY_DURATION
                    - pageNum / pageSize: 分页，默认 1 / 20，pageSize 上限 100
                    - minTraceDuration / maxTraceDuration: 最小/最大耗时（毫秒，选填）

                    serviceId / endpointId / serviceInstanceId 传 "-" 表示取消该过滤条件。
                    时间按 SkyWalking 服务端时区解释（当前表现为 UTC）；本地日志时间 +08 需要减 8 小时。
                    """
    )
    public String queryTraces(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat，连接信息从环境目录解析") String environment,
            @ToolParam(name = "startTime", description = "查询起始时间（必填），如 2026-11-10 05:43") String startTime,
            @ToolParam(name = "endTime", description = "查询结束时间（必填），如 2026-11-11 05:58，跨度不设上限") String endTime,
            @ToolParam(name = "step", description = "时间粒度（选填），SECOND | MINUTE | HOUR | DAY，默认 MINUTE") String step,
            @ToolParam(name = "serviceId", description = "服务 ID（选填），传 \"-\" 取消过滤") String serviceId,
            @ToolParam(name = "endpointId", description = "端点 ID（选填），传 \"-\" 取消过滤") String endpointId,
            @ToolParam(name = "serviceInstanceId", description = "实例 ID（选填），传 \"-\" 取消过滤") String serviceInstanceId,
            @ToolParam(name = "traceState", description = "ALL | SUCCESS | ERROR，默认 ALL") String traceState,
            @ToolParam(name = "queryOrder", description = "BY_DURATION | BY_START_TIME，默认 BY_DURATION") String queryOrder,
            @ToolParam(name = "pageNum", description = "页码，默认 1") String pageNum,
            @ToolParam(name = "pageSize", description = "每页条数，默认 20，上限 100") String pageSize,
            @ToolParam(name = "minTraceDuration", description = "最小耗时（毫秒，选填）") String minTraceDuration,
            @ToolParam(name = "maxTraceDuration", description = "最大耗时（毫秒，选填）") String maxTraceDuration
    ) {
        log.info("skywalking-traces called, environment:{}, start:{}, end:{}, step:{}, serviceId:{}, endpointId:{}, instanceId:{}",
                environment, startTime, endTime, step, serviceId, endpointId, serviceInstanceId);

        if (isBlank(environment)) {
            return "[skywalking-traces] 参数缺失: environment 必填";
        }
        if (isBlank(startTime) || isBlank(endTime)) {
            return "[skywalking-traces] 参数缺失: startTime / endTime 必填";
        }

        String normalizedStep = normalize(step, VALID_STEPS, "MINUTE");
        if (normalizedStep == null) {
            return "[skywalking-traces] step 非法: " + step + "，可选值 SECOND / MINUTE / HOUR / DAY";
        }

        LocalDateTime start = parseTime(startTime);
        LocalDateTime end = parseTime(endTime);
        if (start == null || end == null) {
            return "[skywalking-traces] 时间格式无法识别: " + startTime + " ~ " + endTime
                    + "，支持 yyyy-MM-dd HH:mm[:ss]、yyyy-MM-dd HHmm、yyyy-MM-dd HH、yyyy-MM-dd";
        }
        if (start.isAfter(end)) {
            return "[skywalking-traces] 时间范围非法: startTime 不能晚于 endTime";
        }

        String normalizedState = normalize(traceState, VALID_STATES, "ALL");
        if (normalizedState == null) {
            return "[skywalking-traces] traceState 非法: " + traceState + "，可选值 ALL / SUCCESS / ERROR";
        }
        String normalizedOrder = normalize(queryOrder, VALID_ORDERS, "BY_DURATION");
        if (normalizedOrder == null) {
            return "[skywalking-traces] queryOrder 非法: " + queryOrder + "，可选值 BY_DURATION / BY_START_TIME";
        }

        Integer minDuration = parseOptionalInt(minTraceDuration);
        Integer maxDuration = parseOptionalInt(maxTraceDuration);
        if (minDuration == null && !isBlank(minTraceDuration)
                || maxDuration == null && !isBlank(maxTraceDuration)) {
            return "[skywalking-traces] minTraceDuration / maxTraceDuration 必须是整数毫秒";
        }
        if (minDuration != null && maxDuration != null && minDuration > maxDuration) {
            return "[skywalking-traces] minTraceDuration 不能大于 maxTraceDuration";
        }

        int page = parsePositiveInt(pageNum, 1, 1, Integer.MAX_VALUE);
        int size = parsePositiveInt(pageSize, 20, 1, 100);

        String sid = resolveId(serviceId, DEFAULT_SERVICE_ID);
        String eid = resolveId(endpointId, DEFAULT_ENDPOINT_ID);
        String iid = resolveId(serviceInstanceId, DEFAULT_INSTANCE_ID);

        try {
            ObjectNode condition = MAPPER.createObjectNode();
            ObjectNode queryDuration = condition.putObject("queryDuration");
            queryDuration.put("start", formatForStep(start, normalizedStep, false));
            queryDuration.put("end", formatForStep(end, normalizedStep, true));
            queryDuration.put("step", normalizedStep);

            ObjectNode paging = condition.putObject("paging");
            paging.put("pageNum", page);
            paging.put("pageSize", size);

            condition.put("traceState", normalizedState);
            condition.put("queryOrder", normalizedOrder);
            putNullable(condition, "minTraceDuration", minDuration);
            putNullable(condition, "maxTraceDuration", maxDuration);
            putNullable(condition, "serviceId", sid);
            putNullable(condition, "endpointId", eid);
            putNullable(condition, "serviceInstanceId", iid);

            JsonNode root = postGraphQl(environment, condition);

            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                List<String> messages = new ArrayList<>();
                errors.forEach(e -> messages.add(e.path("message").asText("unknown error")));
                return "[skywalking-traces] GraphQL 错误: " + String.join("; ", messages);
            }

            // GraphQL 里 queryBasicTraces 被别名成了 data，所以响应是 {"data":{"data":{"traces":[...]}}}
            JsonNode tracesNode = root.path("data").path("data").path("traces");
            if (!tracesNode.isArray() || tracesNode.isEmpty()) {
                return emptyResultMessage(startTime, endTime, normalizedStep, sid, eid, iid);
            }

            return formatTraces(tracesNode, startTime, endTime, normalizedStep,
                    sid, eid, iid, normalizedState, normalizedOrder, page, size, minDuration, maxDuration);

        } catch (HttpStatusCodeException e) {
            return "[skywalking-traces] 查询失败, HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString(), 500);
        } catch (Exception e) {
            log.error("skywalking-traces 执行失败, error:{}", e.getMessage(), e);
            return "[skywalking-traces] 查询异常: " + e.getMessage();
        }
    }

    private JsonNode postGraphQl(String environment, ObjectNode condition) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", QUERY);
        ObjectNode variables = payload.putObject("variables");
        variables.set("condition", condition);

        return MAPPER.readTree(client.post(environment, payload));
    }

    // ==================== 格式化 ====================

    private String formatTraces(JsonNode tracesNode, String startTime, String endTime, String step,
                                String serviceId, String endpointId, String instanceId,
                                String state, String order, int page, int size,
                                Integer minDuration, Integer maxDuration) {
        Map<String, TraceRow> merged = new LinkedHashMap<>();
        int rows = 0;

        for (JsonNode node : tracesNode) {
            rows++;
            String segmentId = node.path("key").asText("");
            String traceId = firstText(node.path("traceIds"));
            if (isBlank(traceId)) {
                traceId = segmentId;
            }
            long duration = node.path("duration").asLong(0);
            long start = parseLong(node.path("start").asText(""), 0);
            boolean error = node.path("isError").asBoolean(false);
            Set<String> endpoints = textSet(node.path("endpointNames"));

            TraceRow row = merged.get(traceId);
            if (row == null) {
                merged.put(traceId, new TraceRow(traceId, segmentId, duration, start, error, endpoints));
            } else {
                row.merge(segmentId, duration, start, error, endpoints);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[skywalking-traces] rows=").append(rows)
                .append("  uniqueTraces=").append(merged.size())
                .append("  window=").append(startTime).append(" ~ ").append(endTime)
                .append("  step=").append(step).append('\n');
        sb.append("query: ").append(formatForStep(parseTime(startTime), step, false))
                .append(" ~ ").append(formatForStep(parseTime(endTime), step, true))
                .append("  (SkyWalking server time, currently UTC)\n");
        sb.append("filters: serviceId=").append(display(serviceId))
                .append("  endpointId=").append(display(endpointId))
                .append("  instanceId=").append(display(instanceId))
                .append("  state=").append(state)
                .append("  order=").append(order)
                .append("  page=").append(page)
                .append("  size=").append(size)
                .append("  minDur=").append(minDuration == null ? "-" : minDuration + "ms")
                .append("  maxDur=").append(maxDuration == null ? "-" : maxDuration + "ms")
                .append('\n');
        sb.append("\n# TRACES (server order)\n");

        int index = 1;
        for (TraceRow row : merged.values()) {
            sb.append('[').append(index++).append("] dur=").append(row.duration).append("ms")
                    .append("  start=").append(formatEpochMillis(row.start))
                    .append("  error=").append(row.error);
            if (row.segments > 1) {
                sb.append("  segments=").append(row.segments);
            }
            sb.append('\n');
            sb.append("    endpoint: ").append(String.join(", ", row.endpoints)).append('\n');
            sb.append("    traceId: ").append(row.traceId).append('\n');
            sb.append("    segmentId: ").append(row.segmentId).append('\n');
        }

        sb.append("\n# NEXT\n");
        sb.append("用 skywalking-trace 传入上面的 traceId + 相同时间范围，查看完整调用链与各 span 耗时。\n");
        return sb.toString().strip();
    }

    private String emptyResultMessage(String startTime, String endTime, String step,
                                      String serviceId, String endpointId, String instanceId) {
        return "[skywalking-traces] 未查询到 trace\n"
                + "window=" + startTime + " ~ " + endTime + "  step=" + step
                + "  (SkyWalking server time, currently UTC)\n"
                + "filters: serviceId=" + display(serviceId)
                + " endpointId=" + display(endpointId)
                + " instanceId=" + display(instanceId) + "\n"
                + "可尝试:\n"
                + "1. 扩大 startTime / endTime（时间按 SkyWalking 服务端时区，当前为 UTC；本地 +08 需减 8 小时）\n"
                + "2. serviceInstanceId 可能因 Pod 重建失效，传 \"-\" 取消实例过滤\n"
                + "3. endpointId 传 \"-\" 取消端点过滤\n"
                + "4. traceState 改为 ALL\n";
    }

    private static String formatEpochMillis(long millis) {
        if (millis <= 0) {
            return "-";
        }
        ZonedDateTime utc = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC);
        ZonedDateTime cst = utc.withZoneSameInstant(CST);
        return utc.format(START_FORMAT) + " UTC (" + cst.format(CLOCK_FORMAT) + " +08)";
    }

    // ==================== 参数处理 ====================

    private static String resolveId(String value, String defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if ("-".equals(trimmed) || "none".equalsIgnoreCase(trimmed) || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static String normalize(String value, Set<String> valid, String defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return valid.contains(normalized) ? normalized : null;
    }

    private static Integer parseOptionalInt(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parsePositiveInt(String value, int defaultValue, int min, int max) {
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
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
        try {
            return LocalDate.parse(trimmed, DATE_FORMAT).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 继续尝试仅小时
        }
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}")) {
            try {
                LocalDate date = LocalDate.parse(trimmed.substring(0, 10), DATE_FORMAT);
                return date.atStartOfDay().plusHours(Integer.parseInt(trimmed.substring(10).trim()));
            } catch (Exception ignored) {
                // 落到无法识别
            }
        }
        return null;
    }

    private static String formatForStep(LocalDateTime time, String step, boolean roundUp) {
        LocalDateTime adjusted = time;
        if (roundUp) {
            switch (step) {
                case "SECOND" -> {
                    if (time.getNano() > 0) {
                        adjusted = time.plusSeconds(1);
                    }
                }
                case "MINUTE" -> {
                    if (time.getSecond() > 0 || time.getNano() > 0) {
                        adjusted = time.plusMinutes(1);
                    }
                }
                case "HOUR" -> {
                    if (time.getMinute() > 0 || time.getSecond() > 0 || time.getNano() > 0) {
                        adjusted = time.plusHours(1);
                    }
                }
                case "DAY" -> {
                    if (time.toLocalTime() != LocalTime.MIDNIGHT) {
                        adjusted = time.plusDays(1).toLocalDate().atStartOfDay();
                    }
                }
                default -> {
                    // 不可达
                }
            }
        }
        return switch (step) {
            case "SECOND" -> adjusted.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"));
            case "HOUR" -> adjusted.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH"));
            case "DAY" -> adjusted.format(DATE_FORMAT);
            default -> adjusted.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"));
        };
    }

    private static String firstText(JsonNode array) {
        if (array != null && array.isArray() && !array.isEmpty()) {
            return array.get(0).asText("");
        }
        return "";
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String display(String value) {
        return value == null ? "-" : value;
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

    private static final class TraceRow {
        private final String traceId;
        private String segmentId;
        private long duration;
        private long start;
        private boolean error;
        private final Set<String> endpoints = new LinkedHashSet<>();
        private int segments = 1;

        private TraceRow(String traceId, String segmentId, long duration, long start,
                         boolean error, Set<String> endpoints) {
            this.traceId = traceId;
            this.segmentId = segmentId;
            this.duration = duration;
            this.start = start;
            this.error = error;
            this.endpoints.addAll(endpoints);
        }

        private void merge(String segmentId, long duration, long start, boolean error, Set<String> endpoints) {
            segments++;
            if (duration > this.duration) {
                this.duration = duration;
                this.segmentId = segmentId;
            }
            if (this.start == 0 || (start > 0 && start < this.start)) {
                this.start = start;
            }
            this.error = this.error || error;
            this.endpoints.addAll(endpoints);
        }
    }
}
