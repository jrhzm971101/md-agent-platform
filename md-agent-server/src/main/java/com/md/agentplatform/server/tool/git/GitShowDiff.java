package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.List;

/**
 * Git 提交 diff 工具 —— 通过 GitLab API 获取文件改动并拼装为标准 unified diff 文本。
 */
@Component
public class GitShowDiff extends AbstractGitlabTool {

    private static final int DEFAULT_CONTEXT = 3;
    private static final int DEFAULT_MAX_CHARS = 200_000;
    private static final int HARD_MAX_CHARS = 2_000_000;

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "git-show-diff",
            description = """
                    通过 GitLab API 获取提交的 unified diff / patch 文本（等价 git show --format=）。

                    适用场景:
                    - 用户要查看某次提交的逐行改动
                    - 代码评审、问题定位、变更分析

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库别名/工作区目录/项目路径/ID（必填）
                    - commitId: 提交 SHA、分支、标签，支持 HEAD、HEAD~1（必填）
                    - baseCommitId: 对比基准（选填）。不传则用单提交 diff 接口（相对第一个父提交）；
                      传了则调用 /repository/compare?from=base&to=commit
                    - path: 只看该文件/目录范围内的改动（选填，本地过滤）
                    - context: 兼容旧参数；GitLab API 固定返回服务端上下文，不能自定义行数
                    - detectRenames: 兼容旧参数；GitLab diff 自带重命名标记
                    - maxChars: 返回字符上限（默认 200000）

                    返回: 标准 diff 文本；无文本改动时返回说明文本。

                    说明:
                    - 单提交: GET /repository/commits/:sha/diff
                    - 区间: GET /repository/compare?from=&to=
                    - GitLab diff 只包含 @@ 片段，本工具补齐 diff --git / --- / +++ 文件头
                    - 合并提交默认相对第一个父提交，与 git show 行为一致
                    """
    )
    public String diff(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/工作区目录/项目路径/ID（必填）") String repoDir,
            @ToolParam(name = "commitId", description = "提交 SHA/分支/标签（必填），支持 HEAD、HEAD~1") String commitId,
            @ToolParam(name = "baseCommitId", description = "对比基准提交（选填），默认相对第一个父提交") String baseCommitId,
            @ToolParam(name = "path", description = "只看该文件/目录范围（选填）") String path,
            @ToolParam(name = "context", description = "兼容旧参数；API 固定上下文") String context,
            @ToolParam(name = "detectRenames", description = "兼容旧参数；API 自带重命名标记") String detectRenames,
            @ToolParam(name = "maxChars", description = "返回文本字符上限（默认 200000）") String maxChars
    ) {
        logger.info("git-show-diff called, environment:{}, repoDir:{}, commitId:{}, baseCommitId:{}, path:{}",
                environment, repoDir, commitId, baseCommitId, path);

        if (!notBlank(environment)) {
            return "[git-show-diff] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-show-diff] 参数缺失: repoDir 必填";
        }
        if (!notBlank(commitId)) {
            return "[git-show-diff] 参数缺失: commitId 必填";
        }
        String configError = checkConfig("git-show-diff", environment);
        if (configError != null) {
            return configError;
        }

        parseInt(context, DEFAULT_CONTEXT, 0, 100);
        parseBool(detectRenames, true);
        int charLimit = parseInt(maxChars, DEFAULT_MAX_CHARS, 1000, HARD_MAX_CHARS);

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, repoDir);
            String currentBranch = workspaceManager.currentBranch(environment, repoDir, project);
            String target = GitShowCommit.normalizeCommitRef(commitId, currentBranch);
            JsonNode diffs;

            if (notBlank(baseCommitId)) {
                String base = GitShowCommit.normalizeCommitRef(baseCommitId, currentBranch);
                diffs = get(environment, uri(environment, "/projects/" + project.id() + "/repository/compare", List.of(
                        "from=" + urlEncode(base),
                        "to=" + urlEncode(target))));
                if (diffs != null && diffs.isObject()) {
                    diffs = diffs.path("diffs");
                }
            } else {
                diffs = get(environment, uri(environment, "/projects/" + project.id() + "/repository/commits/"
                        + urlEncode(target) + "/diff", List.of()));
            }

            String patch = renderPatch(diffs, path);
            if (!notBlank(patch)) {
                return "[git-show-diff] 提交 " + GitlabWorkspaceManager.shortHash(target)
                        + " 在指定范围内没有可展示的文本改动"
                        + (notBlank(path) ? "（path=" + normalizePath(path) + "）" : "")
                        + "。可用 git-show-commit 查看文件清单。";
            }
            if (patch.length() > charLimit) {
                return patch.substring(0, charLimit)
                        + "\n\n--- [git-show-diff] 输出已截断: 完整 diff 共 " + patch.length()
                        + " 字符，仅返回前 " + charLimit + " 字符。"
                        + "可用 path 参数只看单个文件 ---\n";
            }
            return patch;
        } catch (HttpStatusCodeException e) {
            return describeHttpError("git-show-diff", e,
                    "repoDir=" + repoDir + ", commitId=" + commitId);
        } catch (IllegalArgumentException e) {
            return "[git-show-diff] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-show-diff 异常, environment:{}, repoDir:{}, commitId:{}, error:{}",
                    environment, repoDir, commitId, e.getMessage(), e);
            return "[git-show-diff] 查询失败: " + e.getMessage();
        }
    }

    static String renderPatch(JsonNode diffs, String pathFilter) {
        if (diffs == null || !diffs.isArray()) {
            return "";
        }
        StringBuilder patch = new StringBuilder();
        for (JsonNode change : diffs) {
            String oldPath = text(change, "old_path");
            String newPath = text(change, "new_path");
            if (!matchesPathFilter(newPath, oldPath, pathFilter)) {
                continue;
            }
            if (!notBlank(oldPath)) {
                oldPath = newPath;
            }
            if (!notBlank(newPath)) {
                newPath = oldPath;
            }

            boolean newFile = change.path("new_file").asBoolean(false);
            boolean deletedFile = change.path("deleted_file").asBoolean(false);
            boolean renamedFile = change.path("renamed_file").asBoolean(false);
            String oldMode = text(change, "a_mode");
            String newMode = text(change, "b_mode");
            String body = text(change, "diff");

            patch.append("diff --git a/").append(oldPath).append(" b/").append(newPath).append('\n');
            if (newFile) {
                patch.append("new file mode ").append(notBlank(newMode) ? newMode : "100644").append('\n');
            } else if (deletedFile) {
                patch.append("deleted file mode ").append(notBlank(oldMode) ? oldMode : "100644").append('\n');
            } else if (renamedFile && !oldPath.equals(newPath)) {
                patch.append("rename from ").append(oldPath).append('\n');
                patch.append("rename to ").append(newPath).append('\n');
            }
            patch.append("--- ").append(newFile ? "/dev/null" : "a/" + oldPath).append('\n');
            patch.append("+++ ").append(deletedFile ? "/dev/null" : "b/" + newPath).append('\n');
            if (notBlank(body)) {
                patch.append(body);
                if (!body.endsWith("\n")) {
                    patch.append('\n');
                }
            } else if (!renamedFile) {
                patch.append("Binary files a/").append(oldPath).append(" and b/")
                        .append(newPath).append(" differ\n");
            }
        }
        return patch.toString();
    }
}
