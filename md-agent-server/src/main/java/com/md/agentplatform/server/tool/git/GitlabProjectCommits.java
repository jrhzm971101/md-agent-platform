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

/**
 * GitLab 项目提交（commit）查询工具 —— 按时间/分支/目录/人查询提交明细并汇总。
 *
 * <p>对应 GitLab API：{@code GET /projects/:id/repository/commits}。
 * 与只查本地快照的 git-log 相比，本工具走服务端接口，不需要先下载仓库，
 * 且能覆盖所有分支上所有人的提交，适合做团队日报/周报的提交量与代码量统计。
 *
 * <p>三个实测确认的口径（GitLab 11.5）：
 * <ul>
 *   <li>必须带 {@code all=true}，否则只返回默认分支的提交（实测同一时间窗 6 条 vs 0 条）；</li>
 *   <li>需要 {@code with_stats=true} 才有每笔提交的增删行数；</li>
 *   <li>11.5 不支持 {@code author} 过滤参数（传了会被忽略），因此按人过滤在本地完成。</li>
 * </ul>
 *
 * <p>另外 {@code since}/{@code until} 是带时区的完整时间戳，工具按本地时区直接下发，
 * 不需要像事件接口那样做 ±1 天的 UTC 自然日换算。
 */
@Component
public class GitlabProjectCommits extends AbstractGitlabTool {

    private static final String TOOL = "gitlab-project-commits";

    /** 默认返回条数 */
    private static final int DEFAULT_MAX_COUNT = 100;

    /** 返回条数硬上限 */
    private static final int HARD_MAX_COUNT = 1000;

    /** 翻页安全阀 */
    private static final int MAX_PAGES = 20;

    @Tool(
            name = "gitlab-project-commits",
            description = """
                    查询 GitLab 项目的提交（commit）明细并统计，等价 GitLab API:
                    GET /projects/:id/repository/commits?all=true&with_stats=true。

                    适用场景:
                    - "今天提交了多少次代码"、"今天改了多少行"、"谁提交得最多"
                    - 每日/每周开发量报表：按人、按天汇总提交次数、增删行数
                    - 需要提交 id（SHA）列表，再配合 gitlab-commit-diff 看具体改动

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - projectId: 项目 ID 或路径（必填），如 1367 或 mtn/lms
                    - date: 只查某一天（选填），格式 yyyy-MM-dd，按服务器时区整天
                    - since / until: 自定义时间范围（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss；
                      只给 since 表示"从该时刻到今天"，只给 until 表示"该日当天"；都不给默认查今天
                    - refName: 只查某个分支/标签（选填），不传则查全部分支（all=true）
                    - all: 是否包含所有分支的提交（选填，默认 true）；传 false 只查默认分支
                    - withStats: 是否返回增删行数（选填，默认 true）
                    - path: 只看该文件/目录下的提交（选填，服务端过滤），如 lms-business
                    - author: 按提交人过滤（选填），姓名或邮箱子串，如 maruiheng（本地过滤，11.5 服务端不支持）
                    - keyword: 按提交信息关键字过滤（选填，本地过滤）
                    - maxCount: 返回条数上限（选填，默认 100，最大 1000）

                    返回: JSON（projectId / refName / window / count / scannedPages / truncated /
                    summary / commits[]）。
                    summary 含 totals{commits, additions, deletions, messageChars}、
                    byAuthor{姓名→{commits,additions,deletions,messageChars}}、
                    byDay{日期→{commits,additions,deletions}}。
                    commits[] 每条含 id(完整 SHA)、shortId、title、message、authorName、authorEmail、
                    authoredDate、commitDate、committerName、messageChars、additions、deletions、total。

                    注意:
                    - 时间口径是"提交时间"（committed_date），不是推送时间；与事件工具的口径略有差异
                    - 一个 push 可能含多笔提交，所以提交数 ≥ 事件里的推送数
                    - 必须 all=true 才能覆盖 feature 分支，否则统计会严重偏少（默认已开）
                    - 提交时间来自开发机时钟，跨天归属以返回的 commitDate 为准
                    - 只读工具，不会修改仓库
                    """
    )
    public String projectCommits(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "projectId", description = "项目 ID 或路径（必填），如 1367 或 mtn/lms") String projectId,
            @ToolParam(name = "date", description = "只查某一天（选填），格式 yyyy-MM-dd") String date,
            @ToolParam(name = "since", description = "起始时间（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss") String since,
            @ToolParam(name = "until", description = "结束时间（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss") String until,
            @ToolParam(name = "refName", description = "只查某个分支/标签（选填），不传查全部分支") String refName,
            @ToolParam(name = "all", description = "是否包含所有分支（选填，默认 true）") String all,
            @ToolParam(name = "withStats", description = "是否返回增删行数（选填，默认 true）") String withStats,
            @ToolParam(name = "path", description = "只看该文件/目录下的提交（选填），如 lms-business") String path,
            @ToolParam(name = "author", description = "按提交人过滤（选填），姓名或邮箱子串") String author,
            @ToolParam(name = "keyword", description = "按提交信息关键字过滤（选填）") String keyword,
            @ToolParam(name = "maxCount", description = "返回条数上限（选填，默认 100，最大 1000）") String maxCount
    ) {
        logger.info("gitlab-project-commits called, projectId:{}, date:{}, since:{}, until:{}, refName:{}, all:{}, "
                        + "withStats:{}, path:{}, author:{}, keyword:{}, maxCount:{}",
                projectId, date, since, until, refName, all, withStats, path, author, keyword, maxCount);

        String configError = checkConfig(TOOL, environment);
        if (configError != null) {
            return configError;
        }
        if (!notBlank(projectId)) {
            return "[" + TOOL + "] 参数缺失: projectId 必填";
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

        boolean allBranches = parseBool(all, true);
        boolean stats = parseBool(withStats, true);
        int limit = parseInt(maxCount, DEFAULT_MAX_COUNT, 1, HARD_MAX_COUNT);

        List<String> baseQuery = new ArrayList<>();
        baseQuery.add("all=" + allBranches);
        baseQuery.add("with_stats=" + stats);
        baseQuery.add("since=" + urlEncode(isoOf(from, zone)));
        baseQuery.add("until=" + urlEncode(isoOf(to, zone)));
        baseQuery.add("per_page=" + PAGE_SIZE);
        if (notBlank(refName)) {
            baseQuery.add("ref_name=" + urlEncode(refName.trim()));
        }
        if (notBlank(path)) {
            baseQuery.add("path=" + urlEncode(path.trim()));
        }

        String basePath = "/projects/" + encodeProject(projectId) + "/repository/commits";
        ArrayNode items = JsonUtil.newArray();
        Map<String, int[]> authorStats = new LinkedHashMap<>();
        Map<String, int[]> dayStats = new LinkedHashMap<>();
        int totalAdditions = 0;
        int totalDeletions = 0;
        int totalMessageChars = 0;
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
                for (JsonNode commit : pageNode) {
                    String commitDate = text(commit, "committed_date");
                    Instant commitTime = parseInstant(commitDate);
                    if (commitTime != null && commitTime.isBefore(from.toInstant())) {
                        // 提交按时间倒序返回，出现早于窗口的记录说明后面都不需要了
                        olderThanWindow = true;
                        continue;
                    }
                    if (commitTime == null || commitTime.isAfter(to.toInstant())) {
                        continue;
                    }
                    if (notBlank(author) && !matchesPerson(author, text(commit, "author_name"),
                            text(commit, "author_email"), text(commit, "committer_name"))) {
                        continue;
                    }
                    if (notBlank(keyword) && !matchesKeyword(commit, keyword)) {
                        continue;
                    }
                    if (items.size() >= limit) {
                        truncated = true;
                        break scan;
                    }
                    ObjectNode out = toCommit(commit, zone, stats);
                    items.add(out);

                    int additions = out.path("additions").asInt(0);
                    int deletions = out.path("deletions").asInt(0);
                    int messageChars = out.path("messageChars").asInt(0);
                    totalAdditions += additions;
                    totalDeletions += deletions;
                    totalMessageChars += messageChars;

                    String who = out.path("authorName").asText("");
                    if (!notBlank(who)) {
                        who = out.path("authorEmail").asText("未知");
                    }
                    int[] authorRow = authorStats.computeIfAbsent(who, key -> new int[4]);
                    authorRow[0]++;
                    authorRow[1] += additions;
                    authorRow[2] += deletions;
                    authorRow[3] += messageChars;

                    String day = dayOf(commitDate, zone);
                    if (notBlank(day)) {
                        int[] dayRow = dayStats.computeIfAbsent(day, key -> new int[3]);
                        dayRow[0]++;
                        dayRow[1] += additions;
                        dayRow[2] += deletions;
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

            ObjectNode totals = JsonUtil.newObject();
            totals.put("commits", items.size());
            totals.put("additions", totalAdditions);
            totals.put("deletions", totalDeletions);
            totals.put("messageChars", totalMessageChars);

            ObjectNode byAuthor = JsonUtil.newObject();
            authorStats.forEach((name, row) -> {
                ObjectNode node = JsonUtil.newObject();
                node.put("commits", row[0]);
                node.put("additions", row[1]);
                node.put("deletions", row[2]);
                node.put("messageChars", row[3]);
                byAuthor.set(name, node);
            });

            ObjectNode byDay = JsonUtil.newObject();
            dayStats.forEach((day, row) -> {
                ObjectNode node = JsonUtil.newObject();
                node.put("commits", row[0]);
                node.put("additions", row[1]);
                node.put("deletions", row[2]);
                byDay.set(day, node);
            });

            ObjectNode summary = JsonUtil.newObject();
            summary.set("totals", totals);
            summary.set("byAuthor", byAuthor);
            summary.set("byDay", byDay);

            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("projectId", projectId.trim());
            if (notBlank(refName)) {
                result.put("refName", refName.trim());
            }
            result.put("allBranches", allBranches);
            result.set("window", window);
            result.put("count", items.size());
            result.put("scannedPages", scannedPages);
            result.put("truncated", truncated);
            result.set("summary", summary);
            result.set("commits", items);
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError(TOOL, e, "projectId=" + projectId);
        } catch (IllegalArgumentException e) {
            return "[" + TOOL + "] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("gitlab-project-commits 异常, environment:{}, projectId:{}", environment, projectId, e);
            return "[" + TOOL + "] 查询异常: " + e.getMessage();
        }
    }

    // ==================== 解析 ====================

    /** 单笔提交 → 输出结构。 */
    static ObjectNode toCommit(JsonNode commit, ZoneId zone, boolean withStats) {
        ObjectNode out = JsonUtil.newObject();
        String id = text(commit, "id");
        out.put("id", id);
        out.put("shortId", id.length() > 8 ? id.substring(0, 8) : id);
        out.put("title", text(commit, "title"));
        String message = text(commit, "message").strip();
        out.put("message", message);
        out.put("messageChars", message.length());
        out.put("authorName", text(commit, "author_name"));
        out.put("authorEmail", text(commit, "author_email"));
        String authoredDate = localTime(text(commit, "authored_date"), zone);
        if (notBlank(authoredDate)) {
            out.put("authoredDate", authoredDate);
        }
        String commitDate = localTime(text(commit, "committed_date"), zone);
        if (notBlank(commitDate)) {
            out.put("commitDate", commitDate);
        }
        String committerName = text(commit, "committer_name");
        if (notBlank(committerName) && !committerName.equals(text(commit, "author_name"))) {
            out.put("committerName", committerName);
        }
        if (commit.path("parent_ids").isArray()) {
            out.put("parentCount", commit.path("parent_ids").size());
        }
        if (withStats && commit.path("stats").isObject()) {
            JsonNode stats = commit.path("stats");
            out.put("additions", stats.path("additions").asInt(0));
            out.put("deletions", stats.path("deletions").asInt(0));
            out.put("total", stats.path("total").asInt(0));
        }
        return out;
    }

    /** 本地日期（yyyy-MM-dd）；解析失败返回空串。 */
    static String dayOf(String iso, ZoneId zone) {
        Instant instant = parseInstant(iso);
        return instant == null ? "" : instant.atZone(zone).toLocalDate().toString();
    }

    /** 人员匹配：姓名/邮箱/提交人任一精确或包含关键字（忽略大小写）。 */
    static boolean matchesPerson(String keyword, String authorName, String authorEmail, String committerName) {
        String target = keyword.trim().toLowerCase(Locale.ROOT);
        return matches(target, authorName) || matches(target, authorEmail) || matches(target, committerName);
    }

    private static boolean matches(String target, String value) {
        String candidate = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return !candidate.isEmpty() && (candidate.equals(target) || candidate.contains(target));
    }

    /** 提交信息关键字匹配（标题或完整 message 包含即命中，忽略大小写）。 */
    static boolean matchesKeyword(JsonNode commit, String keyword) {
        String target = keyword.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            return true;
        }
        return text(commit, "title").toLowerCase(Locale.ROOT).contains(target)
                || text(commit, "message").toLowerCase(Locale.ROOT).contains(target);
    }
}