package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Git 提交历史查询工具 —— 全部通过 GitLab REST API 完成。
 *
 * <p>对应接口: {@code GET /projects/:id/repository/commits}。
 * 不再依赖本地 clone 历史，任意分支/标签均可直接查询。
 */
@Component
public class GitLog extends AbstractGitlabTool {

    private static final int DEFAULT_MAX_COUNT = 50;
    private static final int HARD_MAX_COUNT = 1000;
    private static final int MAX_PAGES = 50;
    private static final int MAX_MESSAGE_CHARS = 4000;
    private static final DateTimeFormatter GIT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "git-log",
            description = """
                    通过 GitLab API 查询提交历史（等价 git log），不依赖本地 .git。

                    适用场景:
                    - 查最近提交、某个分支/文件/时间段的提交
                    - 获取 commitId 后，用 git-show-commit / git-show-diff 下钻

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库别名/工作区目录/项目路径/ID（必填），如 lms、lms/MTN-Feature-Cameroon、mtn/lms、1367
                    - ref: 分支/标签/提交，如 main、origin/main、v1.0.0、a1b2c3d（默认当前分支）
                    - path: 只看影响该文件/目录的提交（选填）
                    - author: 按作者姓名/邮箱子串过滤（选填，本地过滤）
                    - keyword: 按提交标题/正文关键字过滤（选填，本地过滤）
                    - since / until: 时间范围，格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss
                    - maxCount: 返回条数上限（默认 50，最大 1000）
                    - skip: 跳过前 N 条（选填，默认 0）
                    - noMerges: 排除合并提交（选填，默认 false）

                    返回: JSON（success / projectId / ref / currentBranch / count / scannedPages /
                    truncated / commits[]）。commit 字段与旧接口兼容：commitId、shortId、
                    author、authorEmail、authorTime、committer、commitTime、subject、message、
                    merge、parents。

                    说明:
                    - API 调用: GET /projects/:id/repository/commits?ref_name=&path=&since=&until=
                    - author / keyword / noMerges / skip 在本地过滤，因此会多扫描若干页
                    - 不需要 git-fetch，也不需要完整历史克隆
                    """
    )
    public String log(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/工作区目录/项目路径/ID（必填）") String repoDir,
            @ToolParam(name = "ref", description = "分支/标签/提交（选填），默认当前分支") String ref,
            @ToolParam(name = "path", description = "只看影响该路径的提交（选填）") String path,
            @ToolParam(name = "author", description = "按作者姓名/邮箱子串过滤（选填）") String author,
            @ToolParam(name = "keyword", description = "按提交标题/正文关键字过滤（选填）") String keyword,
            @ToolParam(name = "since", description = "起始时间（选填），yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss") String since,
            @ToolParam(name = "until", description = "结束时间（选填），yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss") String until,
            @ToolParam(name = "maxCount", description = "返回条数上限（默认 50，最大 1000）") String maxCount,
            @ToolParam(name = "skip", description = "跳过前 N 条（默认 0）") String skip,
            @ToolParam(name = "noMerges", description = "是否排除合并提交（默认 false）") String noMerges
    ) {
        logger.info("git-log called, environment:{}, repoDir:{}, ref:{}, path:{}, author:{}, keyword:{}, since:{}, until:{}, maxCount:{}, skip:{}",
                environment, repoDir, ref, path, author, keyword, since, until, maxCount, skip);

        if (!notBlank(environment)) {
            return "[git-log] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-log] 参数缺失: repoDir 必填";
        }
        String configError = checkConfig("git-log", environment);
        if (configError != null) {
            return configError;
        }

        int limit = parseInt(maxCount, DEFAULT_MAX_COUNT, 1, HARD_MAX_COUNT);
        int offset = parseInt(skip, 0, 0, Integer.MAX_VALUE);
        boolean excludeMerges = parseBool(noMerges, false);
        Date from = parseDate(since, false);
        Date to = parseDate(until, true);
        ZoneId zone = ZoneId.systemDefault();

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, repoDir);
            String currentBranch = workspaceManager.currentBranch(environment, repoDir, project);
            String targetRef = resolveRefName(ref, currentBranch);
            String pathFilter = normalizePath(path);

            ArrayNode commits = JsonUtil.newArray();
            int scannedPages = 0;
            int matched = 0;
            boolean truncated = false;

            for (int page = 1; page <= MAX_PAGES; page++) {
                List<String> query = new ArrayList<>();
                query.add("per_page=" + PAGE_SIZE);
                query.add("page=" + page);
                if (notBlank(targetRef)) {
                    query.add("ref_name=" + urlEncode(targetRef));
                }
                if (notBlank(pathFilter)) {
                    query.add("path=" + urlEncode(pathFilter));
                }
                if (from != null) {
                    query.add("since=" + urlEncode(isoOf(from, zone)));
                }
                if (to != null) {
                    query.add("until=" + urlEncode(isoOf(to, zone)));
                }

                JsonNode array = get(environment, uri(environment, "/projects/" + project.id() + "/repository/commits", query));
                scannedPages++;
                if (array == null || !array.isArray() || array.isEmpty()) {
                    break;
                }

                for (JsonNode commit : array) {
                    if (!matchesAuthor(commit, author)
                            || !matchesKeyword(commit, keyword)
                            || (excludeMerges && commit.path("parent_ids").size() > 1)) {
                        continue;
                    }
                    matched++;
                    if (matched <= offset) {
                        continue;
                    }
                    if (commits.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    commits.add(toJson(commit, zone));
                }
                if (truncated || array.size() < PAGE_SIZE) {
                    break;
                }
            }

            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("mode", "gitlab-api");
            result.put("projectId", project.id());
            result.put("projectPath", project.pathWithNamespace());
            result.put("ref", notBlank(targetRef) ? targetRef : "HEAD");
            result.put("currentBranch", currentBranch);
            result.put("count", commits.size());
            result.put("maxCount", limit);
            result.put("skip", offset);
            result.put("scannedPages", scannedPages);
            result.put("truncated", truncated);
            result.set("commits", commits);
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError("git-log", e, "repoDir=" + repoDir + ", ref=" + ref);
        } catch (IllegalArgumentException e) {
            return "[git-log] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-log 异常, environment:{}, repoDir:{}, error:{}", environment, repoDir, e.getMessage(), e);
            return "[git-log] 查询失败: " + e.getMessage();
        }
    }

    private String resolveRefName(String ref, String currentBranch) {
        if (!notBlank(ref)) {
            return currentBranch;
        }
        String value = ref.trim();
        if ("HEAD".equalsIgnoreCase(value)) {
            return currentBranch;
        }
        if (value.startsWith("HEAD~") || value.startsWith("HEAD^")) {
            return currentBranch + value.substring("HEAD".length());
        }
        if (value.startsWith("origin/")) {
            return value.substring("origin/".length());
        }
        return value;
    }

    private static boolean matchesAuthor(JsonNode commit, String author) {
        if (!notBlank(author)) {
            return true;
        }
        String needle = author.trim().toLowerCase(Locale.ROOT);
        return text(commit, "author_name").toLowerCase(Locale.ROOT).contains(needle)
                || text(commit, "author_email").toLowerCase(Locale.ROOT).contains(needle)
                || text(commit, "committer_name").toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean matchesKeyword(JsonNode commit, String keyword) {
        if (!notBlank(keyword)) {
            return true;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        return text(commit, "title").toLowerCase(Locale.ROOT).contains(needle)
                || text(commit, "message").toLowerCase(Locale.ROOT).contains(needle);
    }

    private static ObjectNode toJson(JsonNode commit, ZoneId zone) {
        String id = text(commit, "id");
        ObjectNode node = JsonUtil.newObject();
        node.put("commitId", id);
        node.put("shortId", text(commit, "short_id").isEmpty()
                ? GitlabWorkspaceManager.shortHash(id) : text(commit, "short_id"));
        node.put("author", text(commit, "author_name"));
        node.put("authorEmail", text(commit, "author_email"));
        node.put("authorTime", gitTime(text(commit, "authored_date"), zone));
        node.put("committer", text(commit, "committer_name"));
        node.put("commitTime", gitTime(text(commit, "committed_date"), zone));
        node.put("subject", text(commit, "title"));
        node.put("message", truncate(text(commit, "message"), MAX_MESSAGE_CHARS));

        ArrayNode parents = JsonUtil.newArray();
        for (JsonNode parent : commit.path("parent_ids")) {
            parents.add(parent.asText(""));
        }
        node.put("merge", parents.size() > 1);
        node.set("parents", parents);
        return node;
    }

    private static String gitTime(String iso, ZoneId zone) {
        Instant instant = parseInstant(iso);
        return instant == null ? "" : GIT_TIME_FORMAT.format(instant.atZone(zone));
    }
}
