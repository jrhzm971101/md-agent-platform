package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.ZoneId;
import java.util.List;

/**
 * GitLab 提交改动查询工具 —— 拿到"某一次提交"或"两个提交之间"的完整改动内容。
 *
 * <p>两种模式（对应两个 GitLab API）：
 * <ul>
 *   <li>单次提交：{@code GET /projects/:id/repository/commits/:sha} + {@code /diff}，
 *       即这次提交自身引入了哪些文件改动；</li>
 *   <li>提交区间：{@code GET /projects/:id/repository/compare?from=&to=}，
 *       即从 from 到 to 的净改动（一次请求就能拿到整段区间的合并结果，比逐笔提交拉 diff 省很多请求）。</li>
 * </ul>
 *
 * <p>11.5 的 diff 接口不返回每个文件的增删行数，工具按 diff 文本自行统计；
 * 单次提交的行数优先用提交对象里的 {@code stats}（服务端统计，含二进制约行）。
 */
@Component
public class GitlabCommitDiff extends AbstractGitlabTool {

    private static final String TOOL = "gitlab-commit-diff";

    /** 默认返回文件数 */
    private static final int DEFAULT_MAX_FILES = 20;

    /** 返回文件数硬上限 */
    private static final int HARD_MAX_FILES = 200;

    /** 默认单个文件 diff 最大字符数 */
    private static final int DEFAULT_MAX_DIFF_CHARS = 4000;

    /** 单文件 diff 字符数硬上限 */
    private static final int HARD_MAX_DIFF_CHARS = 20000;

    @Tool(
            name = "gitlab-commit-diff",
            description = """
                    查询 GitLab 提交的完整改动内容（等价 git show / git diff），支持两种模式:
                    单次提交 —— GET /projects/:id/repository/commits/:sha/diff（附提交详情与 stats）；
                    提交区间 —— GET /projects/:id/repository/compare?from=&to=（区间内提交 + 净改动）。

                    适用场景:
                    - 用户拿到一个 commitId，要"这次提交改了什么"、"给我完整改动内容"
                    - 看某个分支相对 master 的全部改动（from=master to=feature-branch）
                    - 统计一次提交/一段区间的增删行数

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - projectId: 项目 ID 或路径（必填），如 1367 或 mtn/lms
                    - commitId: 提交 SHA 或分支/标签名（单次提交模式必填），如 89b04188 或 master
                    - from / to: 区间模式（两者同时给时优先走 compare），如 from=master to=feature-x
                    - path: 只看路径包含该关键字的文件（选填），如 mapper/mysql
                    - maxFiles: 最多返回多少个文件（选填，默认 20，最大 200）；超出时 truncated=true
                    - includeDiff: 是否返回 diff 文本（选填，默认 true）；只要文件清单和行数统计时传 false
                    - maxDiffChars: 单个文件 diff 最大字符数（选填，默认 4000，最大 20000），超出截断

                    返回: JSON（mode / projectId / commit{单次模式} / from,to,commitCount,commits[]{区间模式} /
                    files）。
                    files 含 total、returned、truncated、additions、deletions、list[]；
                    list[] 每条含 newPath、oldPath(仅重命名时)、newFile、deletedFile、renamedFile、
                    additions、deletions、diff(includeDiff=true 时)。

                    注意:
                    - 增删行数在 11.5 由工具按 diff 文本统计（排除 +++/--- 文件头），可能与服务端 stats 略有出入
                    - 合并提交（merge commit）的 diff 相对第一个父提交，跨分支合并请用区间模式更直观
                    - 超大提交的 diff 可能被服务端截断（返回里会有 truncated 标记）
                    - 只读工具，不会修改仓库
                    """
    )
    public String commitDiff(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "projectId", description = "项目 ID 或路径（必填），如 1367 或 mtn/lms") String projectId,
            @ToolParam(name = "commitId", description = "提交 SHA 或分支/标签名（单次提交模式必填）") String commitId,
            @ToolParam(name = "from", description = "区间起点的提交 SHA / 分支名（区间模式必填）") String from,
            @ToolParam(name = "to", description = "区间终点的提交 SHA / 分支名（区间模式必填）") String to,
            @ToolParam(name = "path", description = "只看路径包含该关键字的文件（选填）") String path,
            @ToolParam(name = "maxFiles", description = "最多返回多少个文件（选填，默认 20，最大 200）") String maxFiles,
            @ToolParam(name = "includeDiff", description = "是否返回 diff 文本（选填，默认 true）") String includeDiff,
            @ToolParam(name = "maxDiffChars", description = "单个文件 diff 最大字符数（选填，默认 4000，最大 20000）") String maxDiffChars
    ) {
        logger.info("gitlab-commit-diff called, environment:{}, projectId:{}, commitId:{}, from:{}, to:{}, path:{}, maxFiles:{}, includeDiff:{}",
                environment, projectId, commitId, from, to, path, maxFiles, includeDiff);

        String configError = checkConfig(TOOL, environment);
        if (configError != null) {
            return configError;
        }
        if (!notBlank(projectId)) {
            return "[" + TOOL + "] 参数缺失: projectId 必填";
        }
        boolean rangeMode = notBlank(from) && notBlank(to);
        if (!rangeMode && !notBlank(commitId)) {
            return "[" + TOOL + "] 参数缺失: 需要 commitId（单次提交），或者同时给 from 与 to（提交区间）";
        }

        int fileLimit = parseInt(maxFiles, DEFAULT_MAX_FILES, 1, HARD_MAX_FILES);
        int diffLimit = parseInt(maxDiffChars, DEFAULT_MAX_DIFF_CHARS, 1, HARD_MAX_DIFF_CHARS);
        boolean withDiff = parseBool(includeDiff, true);
        ZoneId zone = ZoneId.systemDefault();
        String project = encodeProject(projectId);
        String basePath = "/projects/" + project + "/repository";

        try {
            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("projectId", projectId.trim());

            if (rangeMode) {
                result.put("mode", "compare");
                result.put("from", from.trim());
                result.put("to", to.trim());
                List<String> query = List.of(
                        "from=" + urlEncode(from.trim()),
                        "to=" + urlEncode(to.trim()));
                JsonNode node = get(environment, uri(environment, basePath + "/compare", query));
                if (node == null || !node.isObject()) {
                    return "[" + TOOL + "] 查询异常: compare 返回为空（from=" + from + ", to=" + to + "）";
                }
                ArrayNode commits = JsonUtil.newArray();
                JsonNode commitArray = node.path("commits");
                if (commitArray.isArray()) {
                    for (JsonNode commit : commitArray) {
                        commits.add(toCommitBrief(commit, zone));
                    }
                }
                result.put("commitCount", commits.size());
                result.set("commits", commits);
                if (node.path("compare_timeout").asBoolean(false)) {
                    result.put("compareTimeout", true);
                }
                if (node.path("compare_same_ref").asBoolean(false)) {
                    result.put("compareSameRef", true);
                }
                result.set("files", buildFiles(node.path("diffs"), path, fileLimit, withDiff, diffLimit));
            } else {
                result.put("mode", "commit");
                result.put("commitId", commitId.trim());
                String encoded = urlEncode(commitId.trim());
                JsonNode info = get(environment, uri(environment, basePath + "/commits/" + encoded, List.of()));
                if (info != null && info.isObject()) {
                    result.set("commit", toCommitInfo(info, zone));
                }
                JsonNode diffs = get(environment, uri(environment, basePath + "/commits/" + encoded + "/diff", List.of()));
                result.set("files", buildFiles(diffs, path, fileLimit, withDiff, diffLimit));
            }
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError(TOOL, e, "projectId=" + projectId
                    + (rangeMode ? ", from=" + from + ", to=" + to : ", commitId=" + commitId));
        } catch (IllegalArgumentException e) {
            return "[" + TOOL + "] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("gitlab-commit-diff 异常, environment:{}, projectId:{}, commitId:{}, from:{}, to:{}",
                    environment, projectId, commitId, from, to, e);
            return "[" + TOOL + "] 查询异常: " + e.getMessage();
        }
    }

    // ==================== 解析 ====================

    /** diff 数组 → 文件改动汇总（按 path 过滤并按 maxFiles 截断）。 */
    static ObjectNode buildFiles(JsonNode array, String pathFilter, int maxFiles,
                                 boolean includeDiff, int maxDiffChars) {
        ObjectNode files = JsonUtil.newObject();
        ArrayNode list = JsonUtil.newArray();
        int total = 0;
        int additions = 0;
        int deletions = 0;
        boolean truncated = false;
        if (array != null && array.isArray()) {
            for (JsonNode change : array) {
                if (!matchesPathFilter(text(change, "new_path"), text(change, "old_path"), pathFilter)) {
                    continue;
                }
                total++;
                // 行数按"所有命中文件"累加，保证汇总与 total 口径一致；只有明细列表受 maxFiles 限制
                int[] stat = diffStat(text(change, "diff"));
                additions += stat[0];
                deletions += stat[1];
                if (list.size() >= maxFiles) {
                    truncated = true;
                    continue;
                }
                list.add(toDiffFile(change, stat, includeDiff, maxDiffChars));
            }
        }
        files.put("total", total);
        files.put("returned", list.size());
        files.put("truncated", truncated);
        files.put("additions", additions);
        files.put("deletions", deletions);
        files.set("list", list);
        return files;
    }

    /** 提交详情 → 输出结构（含服务端 stats）。 */
    static ObjectNode toCommitInfo(JsonNode commit, ZoneId zone) {
        ObjectNode out = JsonUtil.newObject();
        String id = text(commit, "id");
        out.put("id", id);
        out.put("shortId", id.length() > 8 ? id.substring(0, 8) : id);
        out.put("title", text(commit, "title"));
        String message = text(commit, "message").strip();
        out.put("message", message);
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
        if (commit.path("stats").isObject()) {
            JsonNode stats = commit.path("stats");
            out.put("additions", stats.path("additions").asInt(0));
            out.put("deletions", stats.path("deletions").asInt(0));
            out.put("total", stats.path("total").asInt(0));
        }
        JsonNode pipeline = commit.path("last_pipeline");
        if (pipeline.isObject()) {
            String status = text(pipeline, "status");
            if (notBlank(status)) {
                out.put("pipelineStatus", status);
            }
        }
        return out;
    }

    /** compare 模式下的提交 → 精简输出结构。 */
    static ObjectNode toCommitBrief(JsonNode commit, ZoneId zone) {
        ObjectNode out = JsonUtil.newObject();
        String id = text(commit, "id");
        out.put("id", id);
        out.put("shortId", id.length() > 8 ? id.substring(0, 8) : id);
        out.put("title", text(commit, "title"));
        out.put("authorName", text(commit, "author_name"));
        String commitDate = localTime(text(commit, "committed_date"), zone);
        if (notBlank(commitDate)) {
            out.put("commitDate", commitDate);
        }
        return out;
    }
}