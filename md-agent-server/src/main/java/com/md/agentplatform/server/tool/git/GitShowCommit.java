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
import java.util.List;

/**
 * Git 单次提交详情工具 —— 通过 GitLab API 查看提交元信息与文件改动清单。
 */
@Component
public class GitShowCommit extends AbstractGitlabTool {

    private static final int DEFAULT_MAX_FILES = 200;
    private static final int HARD_MAX_FILES = 2000;
    private static final DateTimeFormatter GIT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "git-show-commit",
            description = """
                    通过 GitLab API 查看单次提交的元信息与改动文件清单（等价 git show --stat）。

                    适用场景:
                    - 用户说"这个 commit 改了什么"、"涉及哪些文件"
                    - 先看改动范围，再决定是否用 git-show-diff 看逐行内容

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库别名/工作区目录/项目路径/ID（必填）
                    - commitId: 提交 SHA、分支、标签，支持 HEAD、HEAD~1（必填）
                    - path: 只看该文件/目录范围内的改动（选填）
                    - maxFiles: 返回的文件条数上限（默认 200，最大 2000）
                    - detectRenames: 兼容旧参数；GitLab diff 自带 renamed_file 标记

                    返回: JSON（success / commit{...} / files[]{...} / stats{...}）。
                    每个文件含 changeType、path、oldPath/newPath、additions、deletions、
                    binary、oldMode/newMode。

                    说明:
                    - 元信息: GET /repository/commits/:sha
                    - 文件改动: GET /repository/commits/:sha/diff
                    - GitLab 11.5 不返回文件级增删行数，本工具按 diff 文本统计
                    - 只读，不修改仓库
                    """
    )
    public String show(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/工作区目录/项目路径/ID（必填）") String repoDir,
            @ToolParam(name = "commitId", description = "提交 SHA/分支/标签（必填），支持 HEAD、HEAD~1") String commitId,
            @ToolParam(name = "path", description = "只看该文件/目录范围内的改动（选填）") String path,
            @ToolParam(name = "maxFiles", description = "返回文件条数上限（默认 200，最大 2000）") String maxFiles,
            @ToolParam(name = "detectRenames", description = "兼容旧参数；API 自带重命名标记") String detectRenames
    ) {
        logger.info("git-show-commit called, environment:{}, repoDir:{}, commitId:{}, path:{}",
                environment, repoDir, commitId, path);

        if (!notBlank(environment)) {
            return "[git-show-commit] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-show-commit] 参数缺失: repoDir 必填";
        }
        if (!notBlank(commitId)) {
            return "[git-show-commit] 参数缺失: commitId 必填";
        }
        String configError = checkConfig("git-show-commit", environment);
        if (configError != null) {
            return configError;
        }

        int fileLimit = parseInt(maxFiles, DEFAULT_MAX_FILES, 1, HARD_MAX_FILES);
        ZoneId zone = ZoneId.systemDefault();

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, repoDir);
            String currentBranch = workspaceManager.currentBranch(environment, repoDir, project);
            String target = normalizeCommitRef(commitId, currentBranch);
            String encoded = urlEncode(target);
            String base = "/projects/" + project.id() + "/repository/commits/";

            JsonNode info = get(environment, uri(environment, base + encoded, List.of()));
            JsonNode diffs = get(environment, uri(environment, base + encoded + "/diff", List.of()));
            if (info == null || !info.isObject()) {
                return "[git-show-commit] 提交不存在: " + target;
            }

            ObjectNode commit = JsonUtil.newObject();
            String id = text(info, "id");
            commit.put("commitId", id);
            commit.put("shortId", text(info, "short_id").isEmpty()
                    ? GitlabWorkspaceManager.shortHash(id) : text(info, "short_id"));
            commit.put("author", text(info, "author_name"));
            commit.put("authorEmail", text(info, "author_email"));
            commit.put("authorTime", gitTime(text(info, "authored_date"), zone));
            commit.put("committer", text(info, "committer_name"));
            commit.put("commitTime", gitTime(text(info, "committed_date"), zone));
            commit.put("subject", text(info, "title"));
            commit.put("message", text(info, "message"));
            ArrayNode parents = JsonUtil.newArray();
            for (JsonNode parent : info.path("parent_ids")) {
                parents.add(parent.asText(""));
            }
            commit.put("merge", parents.size() > 1);
            commit.set("parents", parents);

            ArrayNode files = JsonUtil.newArray();
            int totalFiles = 0;
            int additions = 0;
            int deletions = 0;
            boolean truncated = false;
            if (diffs != null && diffs.isArray()) {
                for (JsonNode change : diffs) {
                    String newPath = text(change, "new_path");
                    String oldPath = text(change, "old_path");
                    if (!matchesPathFilter(newPath, oldPath, path)) {
                        continue;
                    }
                    totalFiles++;
                    String diff = text(change, "diff");
                    int[] stat = diffStat(diff);
                    additions += stat[0];
                    deletions += stat[1];
                    if (files.size() >= fileLimit) {
                        truncated = true;
                        continue;
                    }

                    ObjectNode file = JsonUtil.newObject();
                    file.put("changeType", changeType(change));
                    file.put("path", notBlank(newPath) ? newPath : oldPath);
                    file.put("oldPath", oldPath);
                    file.put("newPath", newPath);
                    file.put("additions", stat[0]);
                    file.put("deletions", stat[1]);
                    file.put("binary", diff.isEmpty() && !change.path("renamed_file").asBoolean(false));
                    file.put("oldMode", text(change, "a_mode"));
                    file.put("newMode", text(change, "b_mode"));
                    files.add(file);
                }
            }

            ObjectNode stats = JsonUtil.newObject();
            stats.put("filesChanged", totalFiles);
            stats.put("additions", additions);
            stats.put("deletions", deletions);
            stats.put("truncated", truncated);

            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("mode", "gitlab-api");
            result.put("projectId", project.id());
            result.put("projectPath", project.pathWithNamespace());
            result.set("commit", commit);
            result.set("files", files);
            result.set("stats", stats);
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError("git-show-commit", e,
                    "repoDir=" + repoDir + ", commitId=" + commitId);
        } catch (IllegalArgumentException e) {
            return "[git-show-commit] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-show-commit 异常, environment:{}, repoDir:{}, commitId:{}, error:{}",
                    environment, repoDir, commitId, e.getMessage(), e);
            return "[git-show-commit] 查询失败: " + e.getMessage();
        }
    }

    static String normalizeCommitRef(String commitId, String currentBranch) {
        String value = commitId.trim();
        if ("HEAD".equalsIgnoreCase(value)) {
            return notBlank(currentBranch) ? currentBranch : value;
        }
        if (value.startsWith("HEAD~") || value.startsWith("HEAD^")) {
            return (notBlank(currentBranch) ? currentBranch : "HEAD")
                    + value.substring("HEAD".length());
        }
        return value;
    }

    private static String changeType(JsonNode change) {
        if (change.path("new_file").asBoolean(false)) {
            return "ADD";
        }
        if (change.path("deleted_file").asBoolean(false)) {
            return "DELETE";
        }
        if (change.path("renamed_file").asBoolean(false)) {
            return "RENAME";
        }
        return "MODIFY";
    }

    private static String gitTime(String iso, ZoneId zone) {
        Instant instant = parseInstant(iso);
        return instant == null ? "" : GIT_TIME_FORMAT.format(instant.atZone(zone));
    }
}
