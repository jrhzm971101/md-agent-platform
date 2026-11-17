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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GitLab 单个合并请求（MR）详情工具 —— 拿到一个 MR 的完整改动内容。
 *
 * <p>对应 GitLab API：{@code GET /projects/:id/merge_requests/:iid/changes}（MR 全量字段 +
 * 文件改动 diff）、{@code /commits}（该 MR 的所有提交）、{@code /versions}（每次推送后的版本快照）。
 *
 * <p>说明：{@code /changes} 返回的是"相对目标分支的净改动"，一次请求即可拿到全量文件与 diff，
 * 因此这里是"单次 MR 的全部改动"的首选入口；逐提交的改动请用 gitlab-commit-diff。
 * 11.5 的 {@code /changes} 不返回每个文件的增删行数，工具按 diff 文本自行统计。
 */
@Component
public class GitlabMergeRequestDetail extends AbstractGitlabTool {

    private static final String TOOL = "gitlab-merge-request-detail";

    /** 默认返回文件数 */
    private static final int DEFAULT_MAX_FILES = 20;

    /** 返回文件数硬上限 */
    private static final int HARD_MAX_FILES = 200;

    /** 默认单个文件 diff 最大字符数 */
    private static final int DEFAULT_MAX_DIFF_CHARS = 4000;

    /** 单文件 diff 字符数硬上限 */
    private static final int HARD_MAX_DIFF_CHARS = 20000;

    @Tool(
            name = "gitlab-merge-request-detail",
            description = """
                    查询单个 GitLab 合并请求（MR）的完整改动内容，等价 GitLab API:
                    GET /projects/:id/merge_requests/:iid/changes（另可按需取 /commits、/versions）。

                    适用场景:
                    - 用户要"这个 MR 改了什么"、"把某次合并请求的改动内容给我"
                    - 需要看某个 MR 包含哪些提交、每次推送后的版本快照

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - projectId: 项目 ID 或路径（必填），如 1367 或 mtn/lms
                    - mergeRequestIid: MR 的内部编号 iid（必填，URL 里的 !57 就填 57），不是全局 id
                    - include: 需要的内容（选填，默认 changes）: changes(文件改动) / commits(提交列表) /
                      versions(版本快照)，多个用逗号分隔，all 表示全要
                    - path: 只看路径包含该关键字的文件（选填），如 mapper/mysql
                    - maxFiles: 最多返回多少个文件（选填，默认 20，最大 200）；超出时 truncated=true
                    - includeDiff: 是否返回 diff 文本（选填，默认 true）；只要文件清单时传 false 可显著减小返回
                    - maxDiffChars: 单个文件 diff 的最大字符数（选填，默认 4000，最大 20000），超出截断

                    返回: JSON（projectId / iid / mergeRequest / diffRefs / changes / commits / versions）。
                    changes 含 total、returned、truncated、additions、deletions、files[]；
                    files[] 每条含 newPath、oldPath(仅重命名时)、newFile、deletedFile、renamedFile、
                    additions、deletions、diff(includeDiff=true 时)。
                    commits[] 含 id、shortId、title、message、authorName、authorEmail、authoredDate。
                    versions[] 含 id、headCommitSha、baseCommitSha、createdAt、state。

                    注意:
                    - changes 是"相对目标分支的净改动"，与逐个提交 diff 相加可能不同（中间提交会被覆盖）
                    - 合并后的 MR 依然可以查（state=merged），改动内容不会因合并而丢失
                    - 超大 MR 的 diff 可能被服务端截断，工具会给出 truncated 标记
                    - 本工具只读，不会修改仓库
                    """
    )
    public String mergeRequestDetail(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "projectId", description = "项目 ID 或路径（必填），如 1367 或 mtn/lms") String projectId,
            @ToolParam(name = "mergeRequestIid", description = "MR 内部编号 iid（必填），如 57") String mergeRequestIid,
            @ToolParam(name = "include", description = "需要的内容（选填，默认 changes）: changes,commits,versions；all 表示全要") String include,
            @ToolParam(name = "path", description = "只看路径包含该关键字的文件（选填）") String path,
            @ToolParam(name = "maxFiles", description = "最多返回多少个文件（选填，默认 20，最大 200）") String maxFiles,
            @ToolParam(name = "includeDiff", description = "是否返回 diff 文本（选填，默认 true）") String includeDiff,
            @ToolParam(name = "maxDiffChars", description = "单个文件 diff 最大字符数（选填，默认 4000，最大 20000）") String maxDiffChars
    ) {
        logger.info("gitlab-merge-request-detail called, projectId:{}, iid:{}, include:{}, path:{}, maxFiles:{}, includeDiff:{}",
                projectId, mergeRequestIid, include, path, maxFiles, includeDiff);

        String configError = checkConfig(TOOL, environment);
        if (configError != null) {
            return configError;
        }
        if (!notBlank(projectId)) {
            return "[" + TOOL + "] 参数缺失: projectId 必填";
        }
        if (!notBlank(mergeRequestIid) || !mergeRequestIid.trim().matches("\\d+")) {
            return "[" + TOOL + "] 参数错误: mergeRequestIid 必填且为数字（MR 的内部编号 iid）";
        }

        String iid = mergeRequestIid.trim();
        String basePath = "/projects/" + encodeProject(projectId) + "/merge_requests/" + iid;
        List<String> parts = parseInclude(include);
        int fileLimit = parseInt(maxFiles, DEFAULT_MAX_FILES, 1, HARD_MAX_FILES);
        int diffLimit = parseInt(maxDiffChars, DEFAULT_MAX_DIFF_CHARS, 1, HARD_MAX_DIFF_CHARS);
        boolean withDiff = parseBool(includeDiff, true);
        ZoneId zone = ZoneId.systemDefault();

        try {
            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("projectId", projectId.trim());
            result.put("iid", Integer.parseInt(iid));

            if (parts.contains("changes")) {
                JsonNode node = get(environment, uri(environment, basePath + "/changes", List.of()));
                if (node == null || !node.isObject()) {
                    return "[" + TOOL + "] 查询异常: MR !" + iid + " 的 changes 返回为空";
                }
                result.set("mergeRequest", GitlabMergeRequests.toMergeRequest(node, zone));
                JsonNode diffRefs = node.path("diff_refs");
                if (diffRefs.isObject()) {
                    ObjectNode refs = JsonUtil.newObject();
                    putIfNotBlank(refs, "baseSha", text(diffRefs, "base_sha"));
                    putIfNotBlank(refs, "headSha", text(diffRefs, "head_sha"));
                    putIfNotBlank(refs, "startSha", text(diffRefs, "start_sha"));
                    result.set("diffRefs", refs);
                }
                result.set("changes", buildChanges(node, path, fileLimit, withDiff, diffLimit));
            } else {
                JsonNode node = get(environment, uri(environment, basePath, List.of()));
                if (node != null && node.isObject()) {
                    result.set("mergeRequest", GitlabMergeRequests.toMergeRequest(node, zone));
                }
            }

            if (parts.contains("commits")) {
                JsonNode node = get(environment, uri(environment, basePath + "/commits", List.of()));
                ArrayNode commits = JsonUtil.newArray();
                if (node != null && node.isArray()) {
                    for (JsonNode commit : node) {
                        commits.add(toCommit(commit, zone));
                    }
                }
                result.put("commitCount", commits.size());
                result.set("commits", commits);
            }

            if (parts.contains("versions")) {
                JsonNode node = get(environment, uri(environment, basePath + "/versions", List.of()));
                ArrayNode versions = JsonUtil.newArray();
                if (node != null && node.isArray()) {
                    for (JsonNode version : node) {
                        versions.add(toVersion(version, zone));
                    }
                }
                result.put("versionCount", versions.size());
                result.set("versions", versions);
            }

            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError(TOOL, e, "projectId=" + projectId + ", iid=" + iid);
        } catch (IllegalArgumentException e) {
            return "[" + TOOL + "] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("gitlab-merge-request-detail 异常, environment:{}, projectId:{}, iid:{}", environment, projectId, iid, e);
            return "[" + TOOL + "] 查询异常: " + e.getMessage();
        }
    }

    // ==================== 解析 ====================

    /** include 参数 → 需要拉取的内容集合（空/未识别默认 changes）。 */
    static List<String> parseInclude(String include) {
        List<String> parts = new ArrayList<>();
        if (!notBlank(include)) {
            parts.add("changes");
            return parts;
        }
        for (String part : include.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(value)) {
                parts.add("changes");
                parts.add("commits");
                parts.add("versions");
            } else if (("changes".equals(value) || "commits".equals(value) || "versions".equals(value))
                    && !parts.contains(value)) {
                parts.add(value);
            }
        }
        if (parts.isEmpty()) {
            parts.add("changes");
        }
        return parts;
    }

    /** MR changes 节点 → 精简后的改动结构（按 path 过滤并按 maxFiles 截断）。 */
    static ObjectNode buildChanges(JsonNode mr, String pathFilter, int maxFiles,
                                   boolean includeDiff, int maxDiffChars) {
        ObjectNode changes = JsonUtil.newObject();
        String changesCount = text(mr, "changes_count");
        if (notBlank(changesCount)) {
            changes.put("changesCount", changesCount);
        }
        ArrayNode files = JsonUtil.newArray();
        int total = 0;
        int additions = 0;
        int deletions = 0;
        boolean truncated = false;
        JsonNode array = mr.path("changes");
        if (array.isArray()) {
            for (JsonNode change : array) {
                if (!matchesPathFilter(text(change, "new_path"), text(change, "old_path"), pathFilter)) {
                    continue;
                }
                total++;
                // 行数按"所有命中文件"累加，保证汇总与 total 口径一致；只有明细列表受 maxFiles 限制
                int[] stat = diffStat(text(change, "diff"));
                additions += stat[0];
                deletions += stat[1];
                if (files.size() >= maxFiles) {
                    truncated = true;
                    continue;
                }
                files.add(toDiffFile(change, stat, includeDiff, maxDiffChars));
            }
        }
        changes.put("total", total);
        changes.put("returned", files.size());
        changes.put("truncated", truncated);
        changes.put("additions", additions);
        changes.put("deletions", deletions);
        changes.set("files", files);
        return changes;
    }

    /** MR 下的提交 → 输出结构。 */
    static ObjectNode toCommit(JsonNode commit, ZoneId zone) {
        ObjectNode out = JsonUtil.newObject();
        String id = text(commit, "id");
        out.put("id", id);
        if (notBlank(id)) {
            out.put("shortId", id.length() > 8 ? id.substring(0, 8) : id);
        }
        out.put("title", text(commit, "title"));
        out.put("message", text(commit, "message").strip());
        out.put("authorName", text(commit, "author_name"));
        out.put("authorEmail", text(commit, "author_email"));
        String createdAt = localTime(text(commit, "created_at"), zone);
        if (notBlank(createdAt)) {
            out.put("createdAt", createdAt);
        }
        return out;
    }

    /** MR 版本快照 → 输出结构。 */
    static ObjectNode toVersion(JsonNode version, ZoneId zone) {
        ObjectNode out = JsonUtil.newObject();
        if (version.hasNonNull("id")) {
            out.put("id", version.get("id").asText());
        }
        putIfNotBlank(out, "headCommitSha", GitlabMergeRequests.shortSha(text(version, "head_commit_sha")));
        putIfNotBlank(out, "baseCommitSha", GitlabMergeRequests.shortSha(text(version, "base_commit_sha")));
        putIfNotBlank(out, "startCommitSha", GitlabMergeRequests.shortSha(text(version, "start_commit_sha")));
        String createdAt = localTime(text(version, "created_at"), zone);
        if (notBlank(createdAt)) {
            out.put("createdAt", createdAt);
        }
        putIfNotBlank(out, "state", text(version, "state"));
        putIfNotBlank(out, "realSize", text(version, "real_size"));
        return out;
    }

    private static void putIfNotBlank(ObjectNode node, String field, String value) {
        if (notBlank(value)) {
            node.put(field, value);
        }
    }
}