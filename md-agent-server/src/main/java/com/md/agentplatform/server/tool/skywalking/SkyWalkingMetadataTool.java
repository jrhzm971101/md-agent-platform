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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SkyWalking 元数据查询工具 —— 对应控制台 Services / Instances / Endpoints 三个下拉框。
 *
 * <p>用途：在调用 {@link SkyWalkingTracesTool} 之前，先拿到真实的 serviceId /
 * serviceInstanceId / endpointId，避免让模型猜 base64 ID。三个工具共用固定 GraphQL：
 * <ul>
 *   <li>{@code listServices(layer)}</li>
 *   <li>{@code listInstances(serviceId, duration)}</li>
 *   <li>{@code searchEndpoint(serviceId, keyword, limit)} / {@code findEndpoint(...)}</li>
 * </ul>
 */
@Component
public class SkyWalkingMetadataTool {

    private static final Logger log = LoggerFactory.getLogger(SkyWalkingMetadataTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUERY_SERVICES = """
            query listServices($layer: String) {
              layers: listLayers
              services: listServices(layer: $layer) {
                id
                name
                group
              }
            }
            """;

    private static final String QUERY_INSTANCES = """
            query listInstances($serviceId: ID!, $duration: Duration!) {
              instances: listInstances(serviceId: $serviceId, duration: $duration) {
                id
                name
                language
              }
            }
            """;

    private static final String QUERY_SEARCH_ENDPOINTS = """
            query searchEndpoints($serviceId: ID!, $keyword: String!, $limit: Int!) {
              endpoints: searchEndpoint(serviceId: $serviceId, keyword: $keyword, limit: $limit) {
                id
                name
              }
            }
            """;

    private static final String QUERY_FIND_ENDPOINTS = """
            query findEndpoints($serviceId: ID!, $keyword: String, $limit: Int!, $duration: Duration) {
              endpoints: findEndpoint(serviceId: $serviceId, keyword: $keyword, limit: $limit, duration: $duration) {
                id
                name
              }
            }
            """;

    private static final String DEFAULT_SERVICE_ID = "cHJvZDo6b3Blbi1hcGk=.1";
    private static final String DEFAULT_LAYER = "GENERAL";

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

    private final SkyWalkingClient client;

    public SkyWalkingMetadataTool(SkyWalkingClient client) {
        this.client = client;
    }

    // ==================== 工具 ====================

    @Tool(
            name = "skywalking-services",
            description = """
                    查询 SkyWalking 服务列表（对应控制台 Service 下拉框，GraphQL listServices）。
                    返回 name、group 和 serviceId；serviceId 可直接作为 skywalking-traces 的 serviceId。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，SkyWalking 地址与认证从环境目录解析
                    - layer: 服务层，默认 GENERAL；传 "-" 表示不按层过滤（会包含数据库/Cache 等实体）
                    - keyword: 按服务名或 group 模糊过滤（选填，不区分大小写）

                    不知道 serviceId 时先调本工具，不要自行拼接 base64 ID。
                    """
    )
    public String queryServices(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat，连接信息从环境目录解析") String environment,
            @ToolParam(name = "layer", description = "服务层（选填），默认 GENERAL；传 \"-\" 表示不过滤层") String layer,
            @ToolParam(name = "keyword", description = "按服务名/分组过滤（选填），不区分大小写") String keyword
    ) {
        log.info("skywalking-services called, environment:{}, layer:{}, keyword:{}", environment, layer, keyword);

        if (isBlank(environment)) {
            return "[skywalking-services] 参数缺失: environment 必填";
        }

        String normalizedLayer = isBlank(layer) ? DEFAULT_LAYER : layer.trim();
        if ("-".equals(normalizedLayer) || "ALL".equalsIgnoreCase(normalizedLayer)) {
            normalizedLayer = null;
        }

        try {
            ObjectNode variables = MAPPER.createObjectNode();
            putNullable(variables, "layer", normalizedLayer);

            JsonNode root = postGraphQl(environment, QUERY_SERVICES, variables);
            String error = graphQlError(root);
            if (error != null) {
                return "[skywalking-services] GraphQL 错误: " + error;
            }

            JsonNode servicesNode = root.path("data").path("services");
            if (!servicesNode.isArray()) {
                return "[skywalking-services] 返回结构异常: 缺少 data.services";
            }

            String filter = isBlank(keyword) ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            List<ServiceRow> rows = new ArrayList<>();
            for (JsonNode node : servicesNode) {
                String name = node.path("name").asText("");
                String group = node.path("group").asText("");
                if (!filter.isEmpty()
                        && !name.toLowerCase(Locale.ROOT).contains(filter)
                        && !group.toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                rows.add(new ServiceRow(node.path("id").asText(""), name, group));
            }
            rows.sort(Comparator.comparing(ServiceRow::group).thenComparing(ServiceRow::name));

            StringBuilder sb = new StringBuilder();
            sb.append("[skywalking-services] layer=").append(display(normalizedLayer))
                    .append("  keyword=").append(isBlank(keyword) ? "-" : keyword.trim())
                    .append("  count=").append(rows.size()).append('\n');

            if (rows.isEmpty()) {
                sb.append("\n未匹配到服务。可尝试:\n")
                        .append("1. layer 改成 \"-\" 查看所有层\n")
                        .append("2. 清空 keyword 或换更短的服务名片段\n")
                        .append("3. 确认服务已接入 SkyWalking，且时间窗口内有流量\n");
                appendLayers(sb, root.path("data").path("layers"));
                return sb.toString().strip();
            }

            sb.append("\n# SERVICES\n");
            int index = 1;
            for (ServiceRow row : rows) {
                sb.append('[').append(index++).append("] ").append(row.name())
                        .append(row.group().isBlank() ? "" : "  group=" + row.group()).append('\n');
                sb.append("    id: ").append(row.id()).append('\n');
            }
            sb.append("\n# NEXT\n")
                    .append("把 id 作为 skywalking-traces 的 serviceId；再用 skywalking-instances / ")
                    .append("skywalking-endpoints 查实例和端点。\n");
            return sb.toString().strip();

        } catch (HttpStatusCodeException e) {
            return "[skywalking-services] 查询失败, HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString(), 500);
        } catch (Exception e) {
            log.error("skywalking-services 执行失败, error:{}", e.getMessage(), e);
            return "[skywalking-services] 查询异常: " + e.getMessage();
        }
    }

    @Tool(
            name = "skywalking-instances",
            description = """
                    查询 SkyWalking 服务实例列表（对应控制台 Instance 下拉框，GraphQL listInstances）。
                    返回实例 name、language 和 serviceInstanceId，可直接作为 skywalking-traces 的 serviceInstanceId。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，SkyWalking 地址与认证从环境目录解析
                    - serviceId: 服务 ID，默认 prod::open-api；可先用 skywalking-services 查
                    - startTime / endTime: 实例活跃的时间窗口（选填，按 SkyWalking 服务端时区，当前 UTC）
                      都不传时默认最近 60 分钟；只传一个时以它为基准前后补 60 分钟
                    - step: SECOND | MINUTE | HOUR | DAY，默认 MINUTE

                    实例 ID 会随 Pod 重建失效；返回 0 条时先扩大时间范围。
                    """
    )
    public String queryInstances(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat，连接信息从环境目录解析") String environment,
            @ToolParam(name = "serviceId", description = "服务 ID（选填），默认 prod::open-api") String serviceId,
            @ToolParam(name = "startTime", description = "起始时间（选填），如 2026-11-10 01:46") String startTime,
            @ToolParam(name = "endTime", description = "结束时间（选填），如 2026-11-10 02:16") String endTime,
            @ToolParam(name = "step", description = "时间粒度（选填），SECOND | MINUTE | HOUR | DAY，默认 MINUTE") String step
    ) {
        log.info("skywalking-instances called, environment:{}, serviceId:{}, start:{}, end:{}, step:{}",
                environment, serviceId, startTime, endTime, step);

        if (isBlank(environment)) {
            return "[skywalking-instances] 参数缺失: environment 必填";
        }
        String sid = resolveId(serviceId, DEFAULT_SERVICE_ID);
        String normalizedStep = normalizeStep(step);
        if (normalizedStep == null) {
            return "[skywalking-instances] step 非法: " + step + "，可选值 SECOND / MINUTE / HOUR / DAY";
        }

        LocalDateTime[] window = resolveWindow(startTime, endTime);
        if (window == null) {
            return "[skywalking-instances] 时间格式无法识别: " + startTime + " ~ " + endTime
                    + "，支持 yyyy-MM-dd HH:mm[:ss]、yyyy-MM-dd HHmm、yyyy-MM-dd HH、yyyy-MM-dd";
        }
        String[] duration = formatDuration(window[0], window[1], normalizedStep);

        try {
            ObjectNode variables = MAPPER.createObjectNode();
            variables.put("serviceId", sid);
            ObjectNode durationNode = variables.putObject("duration");
            durationNode.put("start", duration[0]);
            durationNode.put("end", duration[1]);
            durationNode.put("step", normalizedStep);

            JsonNode root = postGraphQl(environment, QUERY_INSTANCES, variables);
            String error = graphQlError(root);
            if (error != null) {
                return "[skywalking-instances] GraphQL 错误: " + error;
            }

            JsonNode instancesNode = root.path("data").path("instances");
            if (!instancesNode.isArray()) {
                return "[skywalking-instances] 返回结构异常: 缺少 data.instances";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[skywalking-instances] serviceId=").append(sid)
                    .append("  window=").append(duration[0]).append(" ~ ").append(duration[1])
                    .append("  step=").append(normalizedStep)
                    .append("  count=").append(instancesNode.size()).append('\n');
            sb.append("query: ").append(isBlank(startTime) ? "-" : startTime).append(" ~ ")
                    .append(isBlank(endTime) ? "-" : endTime)
                    .append("  (SkyWalking server time, currently UTC)\n");

            if (instancesNode.isEmpty()) {
                sb.append("\n未查询到实例。可尝试:\n")
                        .append("1. 扩大 startTime / endTime（按 SkyWalking 服务端时区，当前 UTC；本地 +08 需减 8 小时）\n")
                        .append("2. 用 skywalking-services 确认 serviceId\n")
                        .append("3. 实例可能已随 Pod 重建下线，改用 skywalking-traces 时把 serviceInstanceId 传 \"-\"\n");
                return sb.toString().strip();
            }

            sb.append("\n# INSTANCES\n");
            int index = 1;
            for (JsonNode node : instancesNode) {
                String name = node.path("name").asText("");
                String language = node.path("language").asText("");
                sb.append('[').append(index++).append("] ").append(name)
                        .append(language.isBlank() ? "" : "  language=" + language).append('\n');
                sb.append("    id: ").append(node.path("id").asText("")).append('\n');
            }
            sb.append("\n# NEXT\n")
                    .append("把 id 作为 skywalking-traces 的 serviceInstanceId；查不到 trace 时传 \"-\" 取消实例过滤。\n");
            return sb.toString().strip();

        } catch (HttpStatusCodeException e) {
            return "[skywalking-instances] 查询失败, HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString(), 500);
        } catch (Exception e) {
            log.error("skywalking-instances 执行失败, error:{}", e.getMessage(), e);
            return "[skywalking-instances] 查询异常: " + e.getMessage();
        }
    }

    @Tool(
            name = "skywalking-endpoints",
            description = """
                    查询 SkyWalking 端点列表（对应控制台 Endpoint 下拉框）。
                    返回端点 name 和 endpointId，可直接作为 skywalking-traces 的 endpointId。

                    两种模式:
                    - 传 keyword: 用 searchEndpoint 按路径关键字搜索（推荐），如 customers、/orders
                    - 不传 keyword: 用 findEndpoint 列出指定时间窗口内有流量的端点

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，SkyWalking 地址与认证从环境目录解析
                    - serviceId: 服务 ID，默认 prod::open-api；可先用 skywalking-services 查
                    - keyword: 端点关键字（选填），支持路径片段；HTTP 端点形如 POST:/customers/{encryptPhone}
                    - limit: 返回条数，默认 50，上限 200
                    - startTime / endTime: 时间窗口（选填，按 SkyWalking 服务端时区，当前 UTC）
                      不传 keyword 时用于 findEndpoint，默认最近 60 分钟
                    - step: SECOND | MINUTE | HOUR | DAY，默认 MINUTE

                    端点 name 可能带实际路径（POST:/customers/abc123）或模板路径（POST:/customers/{encryptPhone}），
                    两者都是合法的 endpointId，按需选择。
                    """
    )
    public String queryEndpoints(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat，连接信息从环境目录解析") String environment,
            @ToolParam(name = "serviceId", description = "服务 ID（选填），默认 prod::open-api") String serviceId,
            @ToolParam(name = "keyword", description = "端点关键字（选填），如 customers、/orders") String keyword,
            @ToolParam(name = "limit", description = "返回条数，默认 50，上限 200") String limit,
            @ToolParam(name = "startTime", description = "起始时间（选填），不传 keyword 时使用") String startTime,
            @ToolParam(name = "endTime", description = "结束时间（选填），不传 keyword 时使用") String endTime,
            @ToolParam(name = "step", description = "时间粒度（选填），SECOND | MINUTE | HOUR | DAY，默认 MINUTE") String step
    ) {
        log.info("skywalking-endpoints called, environment:{}, serviceId:{}, keyword:{}, limit:{}, start:{}, end:{}, step:{}",
                environment, serviceId, keyword, limit, startTime, endTime, step);

        if (isBlank(environment)) {
            return "[skywalking-endpoints] 参数缺失: environment 必填";
        }
        String sid = resolveId(serviceId, DEFAULT_SERVICE_ID);
        int size = parsePositiveInt(limit, 50, 1, 200);
        String normalizedStep = normalizeStep(step);
        if (normalizedStep == null) {
            return "[skywalking-endpoints] step 非法: " + step + "，可选值 SECOND / MINUTE / HOUR / DAY";
        }

        boolean searchMode = !isBlank(keyword);
        String kw = searchMode ? keyword.trim() : "";

        try {
            ObjectNode variables = MAPPER.createObjectNode();
            variables.put("serviceId", sid);
            variables.put("keyword", kw);
            variables.put("limit", size);

            JsonNode root;
            String[] duration = null;
            if (searchMode) {
                root = postGraphQl(environment, QUERY_SEARCH_ENDPOINTS, variables);
            } else {
                LocalDateTime[] window = resolveWindow(startTime, endTime);
                if (window == null) {
                    return "[skywalking-endpoints] 时间格式无法识别: " + startTime + " ~ " + endTime
                            + "，支持 yyyy-MM-dd HH:mm[:ss]、yyyy-MM-dd HHmm、yyyy-MM-dd HH、yyyy-MM-dd";
                }
                duration = formatDuration(window[0], window[1], normalizedStep);
                ObjectNode durationNode = variables.putObject("duration");
                durationNode.put("start", duration[0]);
                durationNode.put("end", duration[1]);
                durationNode.put("step", normalizedStep);
                root = postGraphQl(environment, QUERY_FIND_ENDPOINTS, variables);
            }

            String error = graphQlError(root);
            if (error != null) {
                return "[skywalking-endpoints] GraphQL 错误: " + error;
            }

            JsonNode endpointsNode = root.path("data").path("endpoints");
            if (!endpointsNode.isArray()) {
                return "[skywalking-endpoints] 返回结构异常: 缺少 data.endpoints";
            }

            List<EndpointRow> rows = new ArrayList<>();
            for (JsonNode node : endpointsNode) {
                rows.add(new EndpointRow(node.path("id").asText(""), node.path("name").asText("")));
            }
            rows.sort(Comparator.comparing(EndpointRow::name));

            StringBuilder sb = new StringBuilder();
            sb.append("[skywalking-endpoints] serviceId=").append(sid)
                    .append("  mode=").append(searchMode ? "search" : "active")
                    .append("  keyword=").append(searchMode ? kw : "-")
                    .append("  limit=").append(size)
                    .append("  count=").append(rows.size());
            if (duration != null) {
                sb.append("  window=").append(duration[0]).append(" ~ ").append(duration[1])
                        .append("  step=").append(normalizedStep);
            }
            sb.append('\n');
            sb.append("query: ")
                    .append(searchMode ? "searchEndpoint(keyword=" + kw + ")" : "findEndpoint(active endpoints)")
                    .append("  (SkyWalking server time, currently UTC)\n");

            if (rows.isEmpty()) {
                sb.append("\n未匹配到端点。可尝试:\n")
                        .append("1. 换更短的路径关键字，如 customers、order、/v1\n")
                        .append("2. 不传 keyword，改为列出时间窗口内有流量的端点\n")
                        .append("3. 扩大 startTime / endTime（按 SkyWalking 服务端时区，当前 UTC）\n")
                        .append("4. 用 skywalking-services 确认 serviceId\n");
                return sb.toString().strip();
            }

            sb.append("\n# ENDPOINTS\n");
            int index = 1;
            for (EndpointRow row : rows) {
                sb.append('[').append(index++).append("] ").append(row.name()).append('\n');
                sb.append("    id: ").append(row.id()).append('\n');
            }
            sb.append("\n# NEXT\n")
                    .append("把 id 作为 skywalking-traces 的 endpointId；查不到 trace 时传 \"-\" 取消端点过滤。\n");
            return sb.toString().strip();

        } catch (HttpStatusCodeException e) {
            return "[skywalking-endpoints] 查询失败, HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString(), 500);
        } catch (Exception e) {
            log.error("skywalking-endpoints 执行失败, error:{}", e.getMessage(), e);
            return "[skywalking-endpoints] 查询异常: " + e.getMessage();
        }
    }

    // ==================== GraphQL ====================

    private JsonNode postGraphQl(String environment, String query, ObjectNode variables) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.set("variables", variables);

        return MAPPER.readTree(client.post(environment, payload));
    }

    private static String graphQlError(JsonNode root) {
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            errors.forEach(e -> messages.add(e.path("message").asText("unknown error")));
            return String.join("; ", messages);
        }
        return null;
    }

    private static void appendLayers(StringBuilder sb, JsonNode layersNode) {
        if (layersNode.isArray() && !layersNode.isEmpty()) {
            List<String> layers = new ArrayList<>();
            layersNode.forEach(node -> layers.add(node.asText("")));
            sb.append("\n可用 layer: ").append(String.join(", ", layers)).append('\n');
        }
    }

    // ==================== 参数处理 ====================

    private static String resolveId(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private static String normalizeStep(String step) {
        if (isBlank(step)) {
            return "MINUTE";
        }
        String normalized = step.trim().toUpperCase(Locale.ROOT);
        return VALID_STEPS.contains(normalized) ? normalized : null;
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
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * 解析查询窗口。两个参数都为空时默认最近 60 分钟；只给一个时以它为基准补 60 分钟。
     * 返回 null 表示时间格式无法识别。
     */
    private static LocalDateTime[] resolveWindow(String startTime, String endTime) {
        LocalDateTime start = parseTime(startTime);
        LocalDateTime end = parseTime(endTime);
        if ((start == null && !isBlank(startTime)) || (end == null && !isBlank(endTime))) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (start == null && end == null) {
            end = now;
            start = now.minusMinutes(60);
        } else if (start == null) {
            start = end.minusMinutes(60);
        } else if (end == null) {
            end = start.plusMinutes(60);
        }
        if (start.isAfter(end)) {
            LocalDateTime tmp = start;
            start = end;
            end = tmp;
        }
        return new LocalDateTime[]{start, end};
    }

    private static LocalDateTime parseTime(String value) {
        if (isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
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

    private static String[] formatDuration(LocalDateTime start, LocalDateTime end, String step) {
        return new String[]{formatForStep(start, step, false), formatForStep(end, step, true)};
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

    private static String display(String value) {
        return isBlank(value) ? "-" : value;
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

    private record ServiceRow(String id, String name, String group) {
    }

    private record EndpointRow(String id, String name) {
    }
}
