package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GitLab 合并请求（MR）查询工具 —— 按项目 / 组 / 全局三种范围查询 MR 列表并给出统计。
 *
 * <p>对应 GitLab API：{@code GET /projects/:id/merge_requests}、
 * {@code GET /groups/:id/merge_requests}、{@code GET /merge_requests}。
 * 实测（GitLab 11.5）不加 {@code scope=all} 时全局接口只返回当前账号创建的 MR，
 * 因此 scope=all 时工具会显式带上该参数，才能拿到全部成员的数据。
 *
 * <p>关键口径：合并时间（{@code merged_at}）在 11.5 没有服务端过滤参数，
 * 因此本工具用 {@code updated_after} 在服务端收窄（合并不可能早于最后更新时间），
 * 再按 {@code merged_at} 在本地精确过滤，避免把"当天改过但没合并"的 MR 算进来。
 */
@Component
public class GitlabMergeRequests extends AbstractGitlabTool {

    private static final String TOOL = "gitlab-merge-requests";

    /** 默认返回条数 */
    private static final int DEFAULT_MAX_COUNT = 100;

    /** 返回条数硬上限 */
    private static final int HARD_MAX_COUNT = 1000;

    /** 翻页安全阀 */
    private static final int MAX_PAGES = 20;

    private static final Set<String> SCOPES = Set.of("PROJECT", "GROUP", "ALL");
    private static final Set<String> STATES = Set.of("merged", "opened", "closed", "locked", "all");
    private static final Set<String> TIME_FIELDS = Set.of("merged", "updated", "created");

    @Tool(
            name = "gitlab-merge-requests",
            description = """
                    查询 GitLab 合并请求（MR）列表并统计，等价 GitLab API:
                    GET /projects/:id/merge_requests、GET /groups/:id/merge_requests、GET /merge_requests。

                    适用场景:
                    - "今天合并了几个 MR"、"谁合并的"、"哪些 MR 还没合并"
                    - 按人/按天汇总开发产出；按目标分支看各分支的合并情况
                    - 查某个分支的 MR、按标题关键字找 MR

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - scope: 查询范围（选填）project(单个项目) / group(组下所有项目) / all(账号可见的全部项目)；
                      不传时按入参推断（给了 projectId 用 project，给了 group 用 group，都没有用 all）
                    - projectId: 项目 ID 或路径（scope=project 必填），如 1367 或 mtn/lms
                    - group: 组 ID 或路径（scope=group 必填），如 mtn
                    - state: MR 状态（选填，默认 merged）: merged / opened / closed / locked / all
                    - date: 只查某一天（选填），格式 yyyy-MM-dd，按服务器时区整天
                    - since / until: 自定义时间范围（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss；
                      只给 since 表示"从该时刻到今天"，只给 until 表示"该日当天"
                    - timeField: 时间口径（选填）: merged(默认，按合并时间) / updated(按最后更新) / created(按创建时间)
                    - author: 按 MR 创建人过滤（选填），账号或姓名，如 xumaoxin
                    - mergedBy: 按合并人过滤（选填），账号或姓名
                    - sourceBranch / targetBranch: 按源/目标分支精确过滤（选填），如 master
                    - search: 按标题/描述关键字过滤（选填，服务端过滤）
                    - maxCount: 返回条数上限（选填，默认 100，最大 1000）

                    返回: JSON（scope / state / timeField / window / count / scannedPages / truncated /
                    summary / mergeRequests[]）。
                    summary 含 byState、byAuthor(创建人)、byMergedBy(合并人)、byTargetBranch、byDay 的条数汇总。
                    mergeRequests[] 每条含 iid、projectId、title、state、createdAt、updatedAt、mergedAt、
                    closedAt、author、authorName、assignee、mergedBy、sourceBranch、targetBranch、
                    mergeCommitSha、labels、workInProgress、userNotesCount、webUrl。

                    注意:
                    - 本工具只查 MR 列表与统计；要看单个 MR 的完整改动请用 gitlab-merge-request-detail
                    - 合并时间没有服务端过滤参数：工具已用 updated_after 收窄 + 本地按 merged_at 精确过滤
                    - 时间是本地时区口径，与事件工具 gitlab-project-events 一致
                    - state=merged 但目标时间窗内没有合并记录的 MR 会被过滤掉，属正常现象
                    - 可见范围受配置 token 的权限限制；跨项目统计用 scope=all 一次就能拿到
                    """
    )
    public String mergeRequests(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "scope", description = "查询范围（选填）: project / group / all，不传按入参推断") String scope,
            @ToolParam(name = "projectId", description = "项目 ID 或路径（scope=project 必填），如 1367 或 mtn/lms") String projectId,
            @ToolParam(name = "group", description = "组 ID 或路径（scope=group 必填），如 mtn") String group,
            @ToolParam(name = "state", description = "MR 状态（选填，默认 merged）: merged / opened / closed / locked / all") String state,
            @ToolParam(name = "date", description = "只查某一天（选填），格式 yyyy-MM-dd") String date,
            @ToolParam(name = "since", description = "起始时间（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss") String since,
            @ToolParam(name = "until", description = "结束时间（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss") String until,
            @ToolParam(name = "timeField", description = "时间口径（选填，默认 merged）: merged / updated / created") String timeField,
            @ToolParam(name = "author", description = "按 MR 创建人过滤（选填），账号或姓名") String author,
            @ToolParam(name = "mergedBy", description = "按合并人过滤（选填），账号或姓名") String mergedBy,
            @ToolParam(name = "sourceBranch", description = "按源分支精确过滤（选填）") String sourceBranch,
            @ToolParam(name = "targetBranch", description = "按目标分支精确过滤（选填），如 master") String targetBranch,
            @ToolParam(name = "search", description = "按标题/描述关键字过滤（选填）") String search,
            @ToolParam(name = "maxCount", description = "返回条数上限（选填，默认 100，最大 1000）") String maxCount
    ) {
        logger.info("gitlab-merge-requests called, scope:{}, projectId:{}, group:{}, state:{}, date:{}, since:{}, until:{}, "
                        + "timeField:{}, author:{}, mergedBy:{}, sourceBranch:{}, targetBranch:{}, search:{}, maxCount:{}",
                scope, projectId, group, state, date, since, until, timeField, author, mergedBy,
                sourceBranch, targetBranch, search, maxCount);

        String configError = checkConfig(TOOL, environment);
        if (configError != null) {
            return configError;
        }

        String effectiveScope = notBlank(scope) ? scope.trim().toUpperCase(Locale.ROOT)
                : (notBlank(projectId) ? "PROJECT" : (notBlank(group) ? "GROUP" : "ALL"));
        if (!SCOPES.contains(effectiveScope)) {
            return "[" + TOOL + "] 参数错误: scope 只支持 project / group / all";
        }
        if ("PROJECT".equals(effectiveScope) && !notBlank(projectId)) {
            return "[" + TOOL + "] 参数缺失: scope=project 时 projectId 必填";
        }
        if ("GROUP".equals(effectiveScope) && !notBlank(group)) {
            return "[" + TOOL + "] 参数缺失: scope=group 时 group 必填";
        }

        String effectiveState = notBlank(state) ? state.trim().toLowerCase(Locale.ROOT) : "merged";
        if (!STATES.contains(effectiveState)) {
            return "[" + TOOL + "] 参数错误: state 只支持 merged / opened / closed / locked / all";
        }

        String effectiveTimeField = notBlank(timeField) ? timeField.trim().toLowerCase(Locale.ROOT)
                : ("all".equals(effectiveState) ? "updated" : "merged");
        if (!TIME_FIELDS.contains(effectiveTimeField)) {
            return "[" + TOOL + "] 参数错误: timeField 只支持 merged / updated / created";
        }

        ZoneId zone = ZoneId.systemDefault();
        Date from;
        Date to;
        try {
            if (notBlank(date)) {
                from = parseDate(date, false);
                to = parseDate(date, true);
            } else if (notBlank(since) || notBlank(until)) {
                String today = LocalDate.now(zone).toString();
                from = parseDate(notBlank(since) ? since : until, false);
                to = parseDate(notBlank(until) ? until : today, true);
            } else {
                String today = LocalDate.now(zone).toString();
                from = parseDate(today, false);
                to = parseDate(today, true);
            }
        } catch (IllegalArgumentException e) {
            return "[" + TOOL + "] 参数错误: " + e.getMessage();
        }

        int limit = parseInt(maxCount, DEFAULT_MAX_COUNT, 1, HARD_MAX_COUNT);
        String basePath = switch (effectiveScope) {
            case "PROJECT" -> "/projects/" + encodeProject(projectId) + "/merge_requests";
            case "GROUP" -> "/groups/" + encodeProject(group) + "/merge_requests";
            default -> "/merge_requests";
        };
        String windowField = switch (effectiveTimeField) {
            case "created" -> "created_at";
            case "updated" -> "updated_at";
            default -> "merged_at";
        };

        List<String> baseQuery = new ArrayList<>();
        baseQuery.add("state=" + urlEncode(effectiveState));
        baseQuery.add("order_by=" + ("created".equals(effectiveTimeField) ? "created_at" : "updated_at"));
        baseQuery.add("sort=desc");
        baseQuery.add("per_page=" + PAGE_SIZE);
        if ("ALL".equals(effectiveScope)) {
            // 不加 scope=all 时全局接口只返回当前账号创建的 MR
            baseQuery.add("scope=all");
        }
        if (notBlank(sourceBranch)) {
            baseQuery.add("source_branch=" + urlEncode(sourceBranch.trim()));
        }
        if (notBlank(targetBranch)) {
            baseQuery.add("target_branch=" + urlEncode(targetBranch.trim()));
        }
        if (notBlank(search)) {
            baseQuery.add("search=" + urlEncode(search.trim()));
        }
        if ("created".equals(effectiveTimeField)) {
            baseQuery.add("created_after=" + urlEncode(isoOf(from, zone)));
            baseQuery.add("created_before=" + urlEncode(isoOf(to, zone)));
        } else if ("updated".equals(effectiveTimeField)) {
            baseQuery.add("updated_after=" + urlEncode(isoOf(from, zone)));
            baseQuery.add("updated_before=" + urlEncode(isoOf(to, zone)));
        } else {
            // merged：11.5 无 merged_after，用 updated_after 在服务端收窄，再本地按 merged_at 精确过滤
            baseQuery.add("updated_after=" + urlEncode(isoOf(from, zone)));
        }

        ArrayNode items = JsonUtil.newArray();
        Map<String, Integer> byState = new LinkedHashMap<>();
        Map<String, Integer> byAuthor = new LinkedHashMap<>();
        Map<String, Integer> byMergedBy = new LinkedHashMap<>();
        Map<String, Integer> byTargetBranch = new LinkedHashMap<>();
        Map<String, Integer> byDay = new LinkedHashMap<>();
        boolean truncated = false;
        int scannedPages = 0;

        try {
            scan:
            for (int page = 1; page <= MAX_PAGES; page++) {
                scannedPages = page;
                List<String> query = new ArrayList<>(baseQuery);
                query.add("page=" + page);
                JsonNode pageNode = get(environment, uri(environment, basePath, query));
                if (pageNode == null || !pageNode.isArray() || pageNode.isEmpty()) {
                    break;
                }
                boolean olderThanWindow = false;
                for (JsonNode mr : pageNode) {
                    Instant sortTime = parseInstant(text(mr, "updated_at"));
                    if (sortTime != null && sortTime.isBefore(from.toInstant())) {
                        // 按 updated_at 倒序返回，出现早于窗口的记录说明后面都不需要了
                        olderThanWindow = true;
                        continue;
                    }
                    Instant time = parseInstant(text(mr, windowField));
                    if (time == null || time.isBefore(from.toInstant()) || time.isAfter(to.toInstant())) {
                        continue;
                    }
                    if (notBlank(author) && !matchesPerson(author,
                            nestedText(mr, "author", "username"), nestedText(mr, "author", "name"))) {
                        continue;
                    }
                    if (notBlank(mergedBy) && !matchesPerson(mergedBy,
                            nestedText(mr, "merged_by", "username"), nestedText(mr, "merged_by", "name"))) {
                        continue;
                    }
                    if (items.size() >= limit) {
                        truncated = true;
                        break scan;
                    }
                    ObjectNode out = toMergeRequest(mr, zone);
                    items.add(out);
                    byState.merge(out.path("state").asText("未知"), 1, Integer::sum);
                    byAuthor.merge(out.path("author").asText("未知"), 1, Integer::sum);
                    if (notBlank(out.path("mergedBy").asText(""))) {
                        byMergedBy.merge(out.path("mergedBy").asText(""), 1, Integer::sum);
                    }
                    byTargetBranch.merge(out.path("targetBranch").asText("未知"), 1, Integer::sum);
                    String day = dayOf(text(mr, windowField), zone);
                    if (notBlank(day)) {
                        byDay.merge(day, 1, Integer::sum);
                    }
                }
                if (olderThanWindow || pageNode.size() < PAGE_SIZE) {
                    break;
                }
            }

            ObjectNode window = JsonUtil.newObject();
            window.put("from", formatLocal(from.toInstant(), zone));
            window.put("to", formatLocal(to.toInstant(), zone));
            window.put("timezone", zone.getId());

            ObjectNode summary = JsonUtil.newObject();
            summary.set("byState", toCountNode(byState));
            summary.set("byAuthor", toCountNode(byAuthor));
            summary.set("byMergedBy", toCountNode(byMergedBy));
            summary.set("byTargetBranch", toCountNode(byTargetBranch));
            summary.set("byDay", toCountNode(byDay));

            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("scope", effectiveScope.toLowerCase(Locale.ROOT));
            if (notBlank(projectId)) {
                result.put("projectId", projectId.trim());
            }
            if (notBlank(group)) {
                result.put("group", group.trim());
            }
            result.put("state", effectiveState);
            result.put("timeField", effectiveTimeField);
            result.set("window", window);
            result.put("count", items.size());
            result.put("scannedPages", scannedPages);
            result.put("truncated", truncated);
            result.set("summary", summary);
            result.set("mergeRequests", items);
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError(TOOL, e, "scope=" + effectiveScope
                    + (notBlank(projectId) ? ", projectId=" + projectId : "")
                    + (notBlank(group) ? ", group=" + group : ""));
        } catch (IllegalArgumentException e) {
            return "[" + TOOL + "] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("gitlab-merge-requests 异常, environment:{}: {}", environment, e.getMessage(), e);
            return "[" + TOOL + "] 查询异常: " + e.getMessage();
        }
    }

    // ==================== 解析 ====================

    /** 单个 MR → 精简后的输出结构（时间统一转本地时区）。 */
    static ObjectNode toMergeRequest(JsonNode mr, ZoneId zone) {
        ObjectNode out = JsonUtil.newObject();
        out.put("iid", mr.path("iid").asInt());
        if (mr.hasNonNull("project_id")) {
            out.put("projectId", mr.get("project_id").asInt());
        }
        out.put("title", text(mr, "title"));
        out.put("state", text(mr, "state"));
        putTime(out, "createdAt", text(mr, "created_at"), zone);
        putTime(out, "updatedAt", text(mr, "updated_at"), zone);
        putTime(out, "mergedAt", text(mr, "merged_at"), zone);
        putTime(out, "closedAt", text(mr, "closed_at"), zone);
        putPerson(out, "author", "authorName", mr, "author");
        putPerson(out, "assignee", null, mr, "assignee");
        putPerson(out, "mergedBy", null, mr, "merged_by");
        out.put("sourceBranch", text(mr, "source_branch"));
        out.put("targetBranch", text(mr, "target_branch"));
        String mergeSha = text(mr, "merge_commit_sha");
        if (notBlank(mergeSha)) {
            out.put("mergeCommitSha", shortSha(mergeSha));
        }
        List<String> labels = textList(mr.path("labels"));
        if (!labels.isEmpty()) {
            ArrayNode labelNode = JsonUtil.newArray();
            labels.forEach(labelNode::add);
            out.set("labels", labelNode);
        }
        out.put("workInProgress", mr.path("work_in_progress").asBoolean(false));
        if (mr.hasNonNull("user_notes_count")) {
            out.put("userNotesCount", mr.get("user_notes_count").asInt());
        }
        String webUrl = text(mr, "web_url");
        if (notBlank(webUrl)) {
            out.put("webUrl", webUrl);
        }
        return out;
    }

    static String shortSha(String sha) {
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }

    private static void putTime(ObjectNode out, String field, String iso, ZoneId zone) {
        String local = localTime(iso, zone);
        if (notBlank(local)) {
            out.put(field, local);
        }
    }

    private static void putPerson(ObjectNode out, String field, String nameField,
                                  JsonNode mr, String objectField) {
        String username = nestedText(mr, objectField, "username");
        if (!notBlank(username)) {
            return;
        }
        out.put(field, username);
        if (nameField != null) {
            String name = nestedText(mr, objectField, "name");
            if (notBlank(name)) {
                out.put(nameField, name);
            }
        }
    }

    /** 本地日期（yyyy-MM-dd），用于按天汇总；解析失败返回空串。 */
    static String dayOf(String iso, ZoneId zone) {
        Instant instant = parseInstant(iso);
        return instant == null ? "" : instant.atZone(zone).toLocalDate().toString();
    }

    /** 人员匹配：账号精确（忽略大小写）或账号/显示名包含关键字。 */
    static boolean matchesPerson(String keyword, String username, String name) {
        String target = keyword.trim().toLowerCase(Locale.ROOT);
        String user = username == null ? "" : username.toLowerCase(Locale.ROOT);
        String display = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return user.equals(target) || user.contains(target) || display.contains(target);
    }
}