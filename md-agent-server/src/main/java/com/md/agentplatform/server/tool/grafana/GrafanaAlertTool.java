package com.md.agentplatform.server.tool.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Grafana / Prometheus 告警查询工具。
 *
 * <p>数据来源：
 * <ul>
 *   <li>Prometheus 活跃告警：/api/datasources/proxy/uid/{uid}/api/v1/alerts</li>
 *   <li>Prometheus 告警规则：/api/v1/rules?type=alert</li>
 *   <li>Grafana Alertmanager：/api/alertmanager/grafana/api/v2/alerts、/silences</li>
 * </ul>
 * 只读，不创建/修改/静默告警。
 */
@Component
public class GrafanaAlertTool {

    private static final Logger log = LoggerFactory.getLogger(GrafanaAlertTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode EMPTY_ARRAY = MAPPER.createArrayNode();

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter OUTPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MAX_ALERTS = 30;
    private static final int MAX_RULES = 30;
    private static final int MAX_LABEL_CHARS = 400;
    private static final int MAX_TEXT_CHARS = 300;
    private static final int MAX_EXPR_CHARS = 300;

    private final GrafanaClient client;

    public GrafanaAlertTool(GrafanaClient client) {
        this.client = client;
    }

    @Tool(
            name = "grafana-alerts",
            description = """
                    查询 Grafana / Prometheus 告警（只读）。

                    action 取值:
                    - summary（默认）: 活跃告警总量、state/severity 分布、Top alertname
                    - active: 列出 Prometheus 活跃告警，可按 keyword / severity / state 过滤
                    - rules: 汇总 Prometheus 告警规则，可按 keyword / state 过滤
                    - silences: 列出 Grafana Alertmanager 的 silence
                    - grafana: 列出 Grafana Alertmanager 原生告警

                    参数:
                    - environment: 环境名（必填），如 cameroon-uat，Grafana 地址与认证从环境目录解析
                    - keyword: 匹配 alertname、标签值、注解、规则名/表达式（选填）
                    - severity: 严重级别精确匹配（选填），如 Critical / High / Medium / P1
                    - state: firing | pending | inactive（选填，按 action 生效）
                    - limit: 返回条数，默认 30，上限 200
                    - datasourceUid: Prometheus 数据源 UID，默认 DmKRmGQvk

                    典型流程: 先 summary 看整体，再 active/rules 过滤到具体告警，最后用 grafana-query 查相关指标。
                    """
    )
    public String queryAlerts(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat，连接信息从环境目录解析") String environment,
            @ToolParam(name = "action", description = "summary | active | rules | silences | grafana，默认 summary") String action,
            @ToolParam(name = "keyword", description = "匹配告警名/标签/注解/规则（选填）") String keyword,
            @ToolParam(name = "severity", description = "严重级别精确匹配（选填），如 Critical / High / P1") String severity,
            @ToolParam(name = "state", description = "firing | pending | inactive（选填）") String state,
            @ToolParam(name = "limit", description = "返回条数，默认 30，上限 200") String limit,
            @ToolParam(name = "datasourceUid", description = "Prometheus 数据源 UID（选填），默认 DmKRmGQvk") String datasourceUid
    ) {
        String normalized = isBlank(action) ? "summary" : action.trim().toLowerCase(Locale.ROOT);
        int size = parseLimit(limit);
        String uid = isBlank(datasourceUid) ? client.defaultDatasourceUid() : datasourceUid.trim();
        log.info("grafana-alerts called, environment:{}, action:{}, keyword:{}, severity:{}, state:{}, limit:{}",
                environment, normalized, keyword, severity, state, size);

        if (isBlank(environment)) {
            return "[grafana-alerts] 参数缺失: environment 必填";
        }

        try {
            return switch (normalized) {
                case "summary" -> summary(environment, uid);
                case "active" -> activeAlerts(environment, uid, keyword, severity, state, size);
                case "rules" -> alertRules(environment, uid, keyword, state, size);
                case "silences" -> silences(environment, size);
                case "grafana" -> grafanaAlerts(environment, keyword, severity, size);
                default -> "[grafana-alerts] action 非法: " + action
                        + "，可选 summary / active / rules / silences / grafana";
            };
        } catch (HttpStatusCodeException e) {
            return "[grafana-alerts] 查询失败, HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString(), 500);
        } catch (Exception e) {
            log.error("grafana-alerts 执行失败, error:{}", e.getMessage(), e);
            return "[grafana-alerts] 查询异常: " + e.getMessage();
        }
    }

    // ==================== summary ====================

    private String summary(String environment, String uid) {
        JsonNode alerts = prometheusAlerts(environment, uid);
        Map<String, Integer> severityCounts = new LinkedHashMap<>();
        Map<String, Integer> nameCounts = new LinkedHashMap<>();
        int firing = 0;
        int pending = 0;

        for (JsonNode alert : alerts) {
            String alertState = alert.path("state").asText("firing");
            if ("firing".equalsIgnoreCase(alertState)) {
                firing++;
            } else if ("pending".equalsIgnoreCase(alertState)) {
                pending++;
            }
            String severity = alert.path("labels").path("severity").asText("unknown");
            severityCounts.merge(severity, 1, Integer::sum);
            String name = alert.path("labels").path("alertname").asText("unknown");
            nameCounts.merge(name, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-alerts] action=summary  source=prometheus  datasource=").append(uid).append('\n');
        sb.append("total=").append(alerts.size())
                .append("  firing=").append(firing)
                .append("  pending=").append(pending)
                .append("  other=").append(alerts.size() - firing - pending).append('\n');
        if (!severityCounts.isEmpty()) {
            sb.append("severity: ").append(formatCounts(severityCounts)).append('\n');
        }
        sb.append("\n# TOP ALERTNAMES\n");
        nameCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> sb.append("- ").append(entry.getValue()).append("  ").append(entry.getKey()).append('\n'));

        int grafanaCount = -1;
        try {
            grafanaCount = grafanaAlertArray(environment).size();
        } catch (Exception e) {
            log.debug("读取 Grafana Alertmanager 告警失败: {}", e.getMessage());
        }
        sb.append("\nGrafana-native alerts: ").append(grafanaCount >= 0 ? grafanaCount : "n/a").append('\n');
        sb.append("\n# NEXT\n用 action=active 查看具体标签，用 action=rules 看规则状态；再用 grafana-query 查相关指标。\n");
        return sb.toString().strip();
    }

    // ==================== active ====================

    private String activeAlerts(String environment, String uid, String keyword, String severity,
                                String state, int limit) {
        JsonNode alerts = prometheusAlerts(environment, uid);
        List<AlertRow> rows = new ArrayList<>();
        for (JsonNode alert : alerts) {
            String alertState = alert.path("state").asText("firing");
            if (!isBlank(state) && !state.trim().equalsIgnoreCase(alertState)) {
                continue;
            }
            if (!matchesAlert(alert, keyword, severity)) {
                continue;
            }
            rows.add(new AlertRow(alert));
        }
        rows.sort(Comparator.comparingInt((AlertRow row) -> severityRank(row.severity()))
                .thenComparing(row -> row.alertname()));

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-alerts] action=active  datasource=").append(uid)
                .append("  total=").append(alerts.size())
                .append("  matched=").append(rows.size())
                .append("  keyword=").append(isBlank(keyword) ? "-" : keyword.trim())
                .append("  severity=").append(isBlank(severity) ? "-" : severity.trim())
                .append("  state=").append(isBlank(state) ? "-" : state.trim()).append('\n');
        if (rows.isEmpty()) {
            sb.append("\n没有匹配的活跃告警。\n");
            return sb.toString().strip();
        }
        sb.append("\n# ACTIVE ALERTS\n");
        int index = 1;
        for (AlertRow row : rows) {
            if (index > limit) {
                sb.append("... 其余 ").append(rows.size() - limit).append(" 条已省略\n");
                break;
            }
            sb.append('[').append(index++).append("] ").append(row.alertname())
                    .append("  severity=").append(display(row.severity()))
                    .append("  state=").append(row.state());
            if (!row.activeAt().isBlank()) {
                sb.append("  activeAt=").append(formatIso(row.activeAt()));
            }
            sb.append('\n');
            sb.append("    labels: ").append(abbreviate(row.labels(), MAX_LABEL_CHARS)).append('\n');
            if (!row.summary().isBlank()) {
                sb.append("    summary: ").append(abbreviate(row.summary(), MAX_TEXT_CHARS)).append('\n');
            }
        }
        return sb.toString().strip();
    }

    // ==================== rules ====================

    private String alertRules(String environment, String uid, String keyword, String state, int limit) {
        JsonNode root = client.get(environment, client.prometheusPath(uid, "/api/v1/rules"),
                Map.of("type", "alert"));
        JsonNode groups = root.path("data").path("groups");
        if (!groups.isArray()) {
            return "[grafana-alerts] 返回结构异常: data.groups 不是数组";
        }

        int total = 0;
        int firing = 0;
        int pending = 0;
        int inactive = 0;
        List<RuleRow> matched = new ArrayList<>();
        for (JsonNode group : groups) {
            String groupName = group.path("name").asText("");
            for (JsonNode rule : group.path("rules")) {
                total++;
                String ruleState = rule.path("state").asText("inactive");
                switch (ruleState) {
                    case "firing" -> firing++;
                    case "pending" -> pending++;
                    default -> inactive++;
                }
                if (!isBlank(state) && !state.trim().equalsIgnoreCase(ruleState)) {
                    continue;
                }
                if (!matchesRule(rule, groupName, keyword)) {
                    continue;
                }
                matched.add(new RuleRow(rule, groupName, ruleState));
            }
        }
        matched.sort(Comparator.comparingInt((RuleRow row) -> stateRank(row.state()))
                .thenComparing(RuleRow::name));

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-alerts] action=rules  datasource=").append(uid)
                .append("  groups=").append(groups.size())
                .append("  total=").append(total)
                .append("  firing=").append(firing)
                .append("  pending=").append(pending)
                .append("  inactive=").append(inactive)
                .append("  matched=").append(matched.size())
                .append("  keyword=").append(isBlank(keyword) ? "-" : keyword.trim())
                .append("  state=").append(isBlank(state) ? "-" : state.trim()).append('\n');
        if (matched.isEmpty()) {
            sb.append("\n没有匹配的告警规则。\n");
            return sb.toString().strip();
        }
        sb.append("\n# ALERT RULES (firing first)\n");
        int index = 1;
        for (RuleRow row : matched) {
            if (index > limit) {
                sb.append("... 其余 ").append(matched.size() - limit).append(" 条已省略\n");
                break;
            }
            sb.append('[').append(index++).append("] ").append(row.name())
                    .append("  state=").append(row.state())
                    .append("  group=").append(row.group());
            String severity = row.rule().path("labels").path("severity").asText("");
            if (!severity.isBlank()) {
                sb.append("  severity=").append(severity);
            }
            int alertCount = row.rule().path("alerts").size();
            if (alertCount > 0) {
                sb.append("  alerts=").append(alertCount);
            }
            sb.append('\n');
            String duration = row.rule().path("duration").asText("");
            if (!duration.isBlank()) {
                sb.append("    for: ").append(duration).append('\n');
            }
            sb.append("    expr: ").append(abbreviate(normalizeExpr(row.rule().path("query").asText("")), MAX_EXPR_CHARS)).append('\n');
        }
        return sb.toString().strip();
    }

    // ==================== silences / grafana ====================

    private String silences(String environment, int limit) {
        JsonNode root = client.get(environment, "/api/alertmanager/grafana/api/v2/silences");
        if (!root.isArray()) {
            return "[grafana-alerts] 返回结构异常: silences 不是数组";
        }
        long now = System.currentTimeMillis();
        List<JsonNode> active = new ArrayList<>();
        int expired = 0;
        for (JsonNode node : root) {
            String endsAt = node.path("endsAt").asText("");
            boolean isActive = endsAt.isBlank() || parseIso(endsAt) > now;
            if (isActive) {
                active.add(node);
            } else {
                expired++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-alerts] action=silences  total=").append(root.size())
                .append("  active=").append(active.size())
                .append("  expired=").append(expired).append('\n');
        if (active.isEmpty()) {
            sb.append("\n当前没有生效中的 silence。\n");
            return sb.toString().strip();
        }
        sb.append("\n# SILENCES\n");
        int index = 1;
        for (JsonNode node : active) {
            if (index > limit) {
                sb.append("... 其余 ").append(active.size() - limit).append(" 条已省略\n");
                break;
            }
            sb.append('[').append(index++).append("] id=").append(node.path("id").asText(""))
                    .append("  status=").append(node.path("status").path("state").asText("-"))
                    .append("  createdBy=").append(node.path("createdBy").asText("-")).append('\n');
            List<String> matchers = new ArrayList<>();
            for (JsonNode matcher : node.path("matchers")) {
                matchers.add(matcher.path("name").asText("") + matcher.path("value").asText(""));
            }
            sb.append("    matchers: ").append(matchers.isEmpty() ? "-" : String.join(", ", matchers)).append('\n');
            sb.append("    window: ").append(formatIso(node.path("startsAt").asText("")))
                    .append(" ~ ").append(formatIso(node.path("endsAt").asText(""))).append('\n');
            String comment = node.path("comment").asText("");
            if (!comment.isBlank()) {
                sb.append("    comment: ").append(abbreviate(comment, MAX_TEXT_CHARS)).append('\n');
            }
        }
        return sb.toString().strip();
    }

    private String grafanaAlerts(String environment, String keyword, String severity, int limit) {
        JsonNode alerts = grafanaAlertArray(environment);
        List<AlertRow> rows = new ArrayList<>();
        for (JsonNode alert : alerts) {
            if (!matchesAlert(alert, keyword, severity)) {
                continue;
            }
            rows.add(new AlertRow(alert));
        }
        rows.sort(Comparator.comparingInt((AlertRow row) -> severityRank(row.severity()))
                .thenComparing(AlertRow::alertname));

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-alerts] action=grafana  total=").append(alerts.size())
                .append("  matched=").append(rows.size())
                .append("  keyword=").append(isBlank(keyword) ? "-" : keyword.trim())
                .append("  severity=").append(isBlank(severity) ? "-" : severity.trim()).append('\n');
        if (rows.isEmpty()) {
            sb.append("\nGrafana Alertmanager 当前没有匹配的告警。\n");
            return sb.toString().strip();
        }
        sb.append("\n# GRAFANA ALERTS\n");
        int index = 1;
        for (AlertRow row : rows) {
            if (index > limit) {
                sb.append("... 其余 ").append(rows.size() - limit).append(" 条已省略\n");
                break;
            }
            sb.append('[').append(index++).append("] ").append(row.alertname())
                    .append("  severity=").append(display(row.severity()))
                    .append("  state=").append(row.state()).append('\n');
            sb.append("    labels: ").append(abbreviate(row.labels(), MAX_LABEL_CHARS)).append('\n');
            if (!row.summary().isBlank()) {
                sb.append("    summary: ").append(abbreviate(row.summary(), MAX_TEXT_CHARS)).append('\n');
            }
        }
        return sb.toString().strip();
    }

    // ==================== 数据解析 ====================

    private JsonNode prometheusAlerts(String environment, String uid) {
        JsonNode root = client.get(environment, client.prometheusPath(uid, "/api/v1/alerts"));
        JsonNode alerts = root.path("data").path("alerts");
        return alerts.isArray() ? alerts : EMPTY_ARRAY;
    }

    private JsonNode grafanaAlertArray(String environment) {
        JsonNode root = client.get(environment, "/api/alertmanager/grafana/api/v2/alerts");
        return root.isArray() ? root : EMPTY_ARRAY;
    }

    private static boolean matchesAlert(JsonNode alert, String keyword, String severity) {
        String alertSeverity = alert.path("labels").path("severity").asText("");
        if (!isBlank(severity) && !severity.trim().equalsIgnoreCase(alertSeverity)) {
            return false;
        }
        if (isBlank(keyword)) {
            return true;
        }
        String filter = keyword.trim().toLowerCase(Locale.ROOT);
        if (alert.path("labels").path("alertname").asText("").toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
        if (alert.path("labels").toString().toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
        return alert.path("annotations").toString().toLowerCase(Locale.ROOT).contains(filter);
    }

    private static boolean matchesRule(JsonNode rule, String groupName, String keyword) {
        if (isBlank(keyword)) {
            return true;
        }
        String filter = keyword.trim().toLowerCase(Locale.ROOT);
        return rule.path("name").asText("").toLowerCase(Locale.ROOT).contains(filter)
                || groupName.toLowerCase(Locale.ROOT).contains(filter)
                || rule.path("query").asText("").toLowerCase(Locale.ROOT).contains(filter);
    }

    private static String normalizeExpr(String expr) {
        return expr.replace('\n', ' ').replace('\r', ' ').replaceAll("[ \t]+", " ").trim();
    }

    private static String formatCounts(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> parts.add(entry.getKey() + "=" + entry.getValue()));
        return String.join(", ", parts);
    }

    private static int severityRank(String severity) {
        if (severity == null) {
            return 9;
        }
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "critical", "p1" -> 0;
            case "high", "p2" -> 1;
            case "medium", "p3", "business-warning" -> 2;
            case "low", "p4" -> 3;
            default -> 5;
        };
    }

    private static int stateRank(String state) {
        return switch (state == null ? "" : state.toLowerCase(Locale.ROOT)) {
            case "firing" -> 0;
            case "pending" -> 1;
            case "inactive" -> 2;
            default -> 3;
        };
    }

    private static String formatIso(String value) {
        if (isBlank(value)) {
            return "-";
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(CST).format(OUTPUT_TIME) + " +08";
        } catch (Exception e) {
            return value;
        }
    }

    private static long parseIso(String value) {
        if (isBlank(value)) {
            return Long.MAX_VALUE;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static int parseLimit(String value) {
        if (isBlank(value)) {
            return MAX_ALERTS;
        }
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return MAX_ALERTS;
        }
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

    private record AlertRow(JsonNode node, String alertname, String severity, String state,
                            String activeAt, String labels, String summary) {

        AlertRow(JsonNode node) {
            this(node,
                    node.path("labels").path("alertname").asText("unknown"),
                    node.path("labels").path("severity").asText(""),
                    node.path("state").asText(node.path("status").path("state").asText("firing")),
                    node.path("activeAt").asText(node.path("startsAt").asText("")),
                    buildLabels(node.path("labels")),
                    buildSummary(node.path("annotations")));
        }

        private static String buildLabels(JsonNode labels) {
            List<String> parts = new ArrayList<>();
            labels.fields().forEachRemaining(entry -> parts.add(entry.getKey() + "=" + entry.getValue().asText("")));
            return parts.isEmpty() ? "-" : String.join(", ", parts);
        }

        private static String buildSummary(JsonNode annotations) {
            for (String key : new String[]{"summary", "description", "message"}) {
                String value = annotations.path(key).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }
    }

    private record RuleRow(JsonNode rule, String group, String state) {
        String name() {
            return rule.path("name").asText("(unnamed)");
        }
    }
}
