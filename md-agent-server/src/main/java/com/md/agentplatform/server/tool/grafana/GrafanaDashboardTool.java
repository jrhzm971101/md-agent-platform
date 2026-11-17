package com.md.agentplatform.server.tool.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grafana 看板查询工具。
 *
 * <p>用于：查看看板列表、读取看板 JSON、提取每个 panel 的 PromQL 表达式（指标字典）。
 * 只读，不修改看板。
 */
@Component
public class GrafanaDashboardTool {

    private static final Logger log = LoggerFactory.getLogger(GrafanaDashboardTool.class);

    private static final int MAX_PANELS = 120;
    private static final int MAX_EXPR_CHARS = 600;

    private final GrafanaClient client;

    public GrafanaDashboardTool(GrafanaClient client) {
        this.client = client;
    }

    @Tool(
            name = "grafana-dashboards",
            description = """
                    查询 Grafana 看板（只读）。用于找到某个服务/中间件的监控看板，
                    并读取 panel 里的 PromQL 表达式，作为后续 grafana-query 的指标字典。

                    action 取值:
                    - list（默认）: 按 query / tag / folder 搜索看板，返回 title、uid、folder、tags
                    - get: 按 uid 读取看板详情，展开 panel、datasource、expr、legend、unit
                    - folders: 列出看板目录
                    - tags: 列出看板标签及数量

                    参数:
                    - environment: 环境名（必填），如 cameroon-uat，Grafana 地址与认证从环境目录解析
                    - uid: action=get 时必填
                    - query: 看板标题关键字（选填）
                    - tag: 标签过滤（选填，多个用逗号分隔）
                    - folder: 目录标题关键字（选填）
                    - limit: list 返回条数，默认 50，上限 200

                    典型用法: 先 list 找到 uid，再 get 拿到 panel 的 expr，直接交给 grafana-query。
                    """
    )
    public String queryDashboards(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat，连接信息从环境目录解析") String environment,
            @ToolParam(name = "action", description = "list | get | folders | tags，默认 list") String action,
            @ToolParam(name = "uid", description = "看板 UID，action=get 时必填") String uid,
            @ToolParam(name = "query", description = "看板标题关键字（选填）") String query,
            @ToolParam(name = "tag", description = "标签过滤（选填），多个用逗号分隔") String tag,
            @ToolParam(name = "folder", description = "目录标题关键字（选填）") String folder,
            @ToolParam(name = "limit", description = "list 返回条数，默认 50，上限 200") String limit
    ) {
        String normalized = isBlank(action) ? "list" : action.trim().toLowerCase(Locale.ROOT);
        log.info("grafana-dashboards called, environment:{}, action:{}, uid:{}, query:{}, tag:{}, folder:{}",
                environment, normalized, uid, query, tag, folder);

        if (isBlank(environment)) {
            return "[grafana-dashboards] 参数缺失: environment 必填";
        }

        try {
            return switch (normalized) {
                case "list" -> listDashboards(environment, query, tag, folder, parseLimit(limit));
                case "get" -> getDashboard(environment, uid);
                case "folders" -> listFolders(environment, folder);
                case "tags" -> listTags(environment);
                default -> "[grafana-dashboards] action 非法: " + action + "，可选 list / get / folders / tags";
            };
        } catch (HttpStatusCodeException e) {
            return "[grafana-dashboards] 查询失败, HTTP " + e.getStatusCode().value()
                    + ": " + abbreviate(e.getResponseBodyAsString(), 500);
        } catch (Exception e) {
            log.error("grafana-dashboards 执行失败, error:{}", e.getMessage(), e);
            return "[grafana-dashboards] 查询异常: " + e.getMessage();
        }
    }

    private String listDashboards(String environment, String query, String tag, String folder, int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "dash-db");
        params.put("limit", String.valueOf(limit));
        if (!isBlank(query)) {
            params.put("query", query.trim());
        }
        List<String> tags = splitCsv(tag);
        if (!tags.isEmpty()) {
            params.put("tag", tags.get(0));
        }

        JsonNode root = client.get(environment, "/api/search", params);
        if (!root.isArray()) {
            return "[grafana-dashboards] 返回结构异常: search 不是数组";
        }

        String folderFilter = isBlank(folder) ? "" : folder.trim().toLowerCase(Locale.ROOT);
        List<DashboardRow> rows = new ArrayList<>();
        for (JsonNode node : root) {
            String title = node.path("title").asText("");
            String folderTitle = node.path("folderTitle").asText("");
            if (!folderFilter.isEmpty() && !folderTitle.toLowerCase(Locale.ROOT).contains(folderFilter)) {
                continue;
            }
            Set<String> itemTags = new LinkedHashSet<>();
            node.path("tags").forEach(t -> itemTags.add(t.asText("")));
            if (!tags.isEmpty() && !itemTags.containsAll(tags)) {
                continue;
            }
            rows.add(new DashboardRow(node.path("uid").asText(""), title, folderTitle, itemTags,
                    node.path("url").asText("")));
        }
        rows.sort(Comparator.comparing(DashboardRow::folder).thenComparing(DashboardRow::title));

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-dashboards] action=list  count=").append(rows.size())
                .append("  query=").append(isBlank(query) ? "-" : query.trim())
                .append("  tag=").append(tags.isEmpty() ? "-" : String.join(",", tags))
                .append("  folder=").append(isBlank(folder) ? "-" : folder.trim()).append('\n');
        if (rows.isEmpty()) {
            sb.append("\n未匹配到看板。可尝试:\n")
                    .append("1. 清空 query/tag/folder 再查\n")
                    .append("2. 用 grafana-dashboards(action=folders) 先看目录\n");
            return sb.toString().strip();
        }
        sb.append("\n# DASHBOARDS\n");
        int index = 1;
        for (DashboardRow row : rows) {
            sb.append('[').append(index++).append("] ").append(row.title())
                    .append("  uid=").append(row.uid());
            if (!row.folder().isBlank()) {
                sb.append("  folder=").append(row.folder());
            }
            if (!row.tags().isEmpty()) {
                sb.append("  tags=").append(String.join(",", row.tags()));
            }
            sb.append('\n');
        }
        sb.append("\n# NEXT\n用 grafana-dashboards(action=get, uid=...) 读取 panel 的 expr。\n");
        return sb.toString().strip();
    }

    private String getDashboard(String environment, String uid) {
        if (isBlank(uid)) {
            return "[grafana-dashboards] action=get 需要 uid";
        }
        JsonNode root = client.get(environment, "/api/dashboards/uid/" + uid.trim());
        JsonNode dashboard = root.path("dashboard");
        JsonNode meta = root.path("meta");
        if (dashboard.isMissingNode() || !dashboard.isObject()) {
            return "[grafana-dashboards] 看板不存在或无权访问: " + uid;
        }

        String title = dashboard.path("title").asText("");
        String folderTitle = meta.path("folderTitle").asText("");
        Set<String> tags = new LinkedHashSet<>();
        dashboard.path("tags").forEach(t -> tags.add(t.asText("")));

        List<PanelRow> panels = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collectPanels(dashboard.path("panels"), panels, seen);

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-dashboards] action=get  title=").append(title)
                .append("  uid=").append(dashboard.path("uid").asText(uid))
                .append("  version=").append(dashboard.path("version").asInt(0));
        if (!folderTitle.isBlank()) {
            sb.append("  folder=").append(folderTitle);
        }
        if (!tags.isEmpty()) {
            sb.append("  tags=").append(String.join(",", tags));
        }
        sb.append('\n');
        sb.append("time: from=").append(dashboard.path("time").path("from").asText("-"))
                .append(" to=").append(dashboard.path("time").path("to").asText("-"))
                .append(" refresh=").append(dashboard.path("refresh").asText("-")).append('\n');

        appendVariables(sb, dashboard.path("templating").path("list"));

        sb.append("\n# PANELS (").append(panels.size()).append(")\n");
        if (panels.isEmpty()) {
            sb.append("该看板没有可解析的 panel（可能是纯文本或空看板）。\n");
            return sb.toString().strip();
        }
        int index = 1;
        for (PanelRow panel : panels) {
            if (index > MAX_PANELS) {
                sb.append("... 其余 ").append(panels.size() - MAX_PANELS).append(" 个 panel 已省略\n");
                break;
            }
            sb.append('[').append(index++).append("] ").append(panel.title())
                    .append("  type=").append(panel.type());
            if (!panel.datasource().isBlank()) {
                sb.append("  datasource=").append(panel.datasource());
            }
            if (!panel.unit().isBlank()) {
                sb.append("  unit=").append(panel.unit());
            }
            sb.append('\n');
            if (panel.exprs().isEmpty()) {
                sb.append("    expr: -\n");
            } else {
                for (String expr : panel.exprs()) {
                    sb.append("    expr: ").append(abbreviate(expr, MAX_EXPR_CHARS)).append('\n');
                }
            }
            if (!panel.legends().isEmpty()) {
                sb.append("    legend: ").append(String.join(" | ", panel.legends())).append('\n');
            }
        }
        sb.append("\n# NEXT\n把 panel 的 expr 作为 grafana-query 的 expr；先用 mode=instant 验证指标存在，再做 range 趋势分析。\n");
        return sb.toString().strip();
    }

    private String listFolders(String environment, String folder) {
        JsonNode root = client.get(environment, "/api/folders", Map.of("limit", "1000"));
        if (!root.isArray()) {
            return "[grafana-dashboards] 返回结构异常: folders 不是数组";
        }
        String filter = isBlank(folder) ? "" : folder.trim().toLowerCase(Locale.ROOT);
        List<FolderRow> rows = new ArrayList<>();
        for (JsonNode node : root) {
            String title = node.path("title").asText("");
            if (!filter.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            rows.add(new FolderRow(node.path("id").asLong(0), node.path("uid").asText(""), title));
        }
        rows.sort(Comparator.comparing(FolderRow::title));

        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-dashboards] action=folders  count=").append(rows.size()).append('\n');
        for (FolderRow row : rows) {
            sb.append("- ").append(row.title()).append("  uid=").append(row.uid())
                    .append("  id=").append(row.id()).append('\n');
        }
        return sb.toString().strip();
    }

    private String listTags(String environment) {
        JsonNode root = client.get(environment, "/api/dashboards/tags");
        if (!root.isArray()) {
            return "[grafana-dashboards] 返回结构异常: tags 不是数组";
        }
        List<TagRow> rows = new ArrayList<>();
        for (JsonNode node : root) {
            rows.add(new TagRow(node.path("term").asText(""), node.path("count").asLong(0)));
        }
        rows.sort(Comparator.comparingLong(TagRow::count).reversed());
        StringBuilder sb = new StringBuilder();
        sb.append("[grafana-dashboards] action=tags  count=").append(rows.size()).append('\n');
        for (TagRow row : rows) {
            sb.append("- ").append(row.term()).append("  count=").append(row.count()).append('\n');
        }
        return sb.toString().strip();
    }

    // ==================== panel 解析 ====================

    private static void collectPanels(JsonNode panels, List<PanelRow> out, Set<String> seen) {
        if (panels == null || !panels.isArray()) {
            return;
        }
        for (JsonNode panel : panels) {
            JsonNode nested = panel.path("panels");
            if (nested.isArray()) {
                collectPanels(nested, out, seen);
            }
            JsonNode rows = panel.path("rows");
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    collectPanels(row.path("panels"), out, seen);
                }
            }

            String type = panel.path("type").asText("");
            if (!panel.has("targets") && "row".equals(type)) {
                continue;
            }
            if (!panel.has("targets") && !panel.has("datasource")) {
                continue;
            }

            String title = panel.path("title").asText("(untitled)");
            String key = panel.path("id").asText("") + "|" + title + "|" + type;
            if (!seen.add(key)) {
                continue;
            }

            String datasource = datasourceText(panel.path("datasource"));
            String unit = panel.path("fieldConfig").path("defaults").path("unit").asText("");
            if (unit.isBlank() && panel.path("yaxes").isArray() && !panel.path("yaxes").isEmpty()) {
                unit = panel.path("yaxes").get(0).path("format").asText("");
            }

            List<String> exprs = new ArrayList<>();
            List<String> legends = new ArrayList<>();
            for (JsonNode target : panel.path("targets")) {
                if (target.path("hide").asBoolean(false)) {
                    continue;
                }
                String expr = target.path("expr").asText("");
                if (!expr.isBlank()) {
                    exprs.add(normalizeExpr(expr));
                    String legend = target.path("legendFormat").asText("");
                    if (!legend.isBlank()) {
                        legends.add(legend.trim());
                    }
                }
                if (datasource.isBlank()) {
                    datasource = datasourceText(target.path("datasource"));
                }
            }
            if (exprs.isEmpty()) {
                continue;
            }
            out.add(new PanelRow(title, type, datasource, exprs, legends, unit));
        }
    }

    private static String normalizeExpr(String expr) {
        return expr.replace('\n', ' ').replace('\r', ' ').replaceAll("[ \t]+", " ").trim();
    }

    private static String datasourceText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isObject()) {
            String type = node.path("type").asText("");
            String uid = node.path("uid").asText("");
            if (!type.isBlank() && !uid.isBlank()) {
                return type + "/" + uid;
            }
            return !uid.isBlank() ? uid : type;
        }
        return "";
    }

    private static void appendVariables(StringBuilder sb, JsonNode variables) {
        if (!variables.isArray() || variables.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode variable : variables) {
            String name = variable.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String type = variable.path("type").asText("");
            String current = variable.path("current").path("text").asText("");
            if (current.isBlank()) {
                JsonNode value = variable.path("current").path("value");
                if (value.isArray()) {
                    List<String> values = new ArrayList<>();
                    value.forEach(v -> values.add(v.asText("")));
                    current = String.join(",", values);
                } else {
                    current = value.asText("");
                }
            }
            parts.add(name + "(" + type + ")=" + (current.isBlank() ? "-" : current));
            if (parts.size() >= 20) {
                break;
            }
        }
        if (!parts.isEmpty()) {
            sb.append("variables: ").append(String.join("  ", parts)).append('\n');
        }
    }

    // ==================== 工具方法 ====================

    private static List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (isBlank(value)) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static int parseLimit(String value) {
        if (isBlank(value)) {
            return 50;
        }
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return 50;
        }
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

    private record DashboardRow(String uid, String title, String folder, Set<String> tags, String url) {
    }

    private record PanelRow(String title, String type, String datasource, List<String> exprs,
                            List<String> legends, String unit) {
    }

    private record FolderRow(long id, String uid, String title) {
    }

    private record TagRow(String term, long count) {
    }
}
