package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.List;

/**
 * Git 远程元数据查询工具。
 *
 * <p>API 快照模式不需要 fetch 本地 refs。本工具保留旧名称，用于通过 GitLab API
 * 查询远程分支、标签及其最新 commit，便于 Agent 在创建快照前确认版本。
 */
@Component
public class GitFetch extends AbstractGitlabTool {

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "git-fetch",
            description = """
                    通过 GitLab API 查询远程分支/标签元数据（不再更新本地 .git refs）。

                    适用场景:
                    - 创建分支快照前，确认远程是否存在某个分支/标签
                    - 查看分支最新 commit SHA
                    - 兼容旧工作流中的"先 fetch 再 worktree"步骤（新模式下该步骤可选）

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库别名/项目路径/项目 ID（必填），如 lms、mtn/lms、1367
                    - remote: 兼容旧参数，默认 origin；API 模式只访问该 environment 对应的 GitLab 实例
                    - allBranches: 是否查询所有分支，默认 true；false 时仍返回 API 可见分支（仅作提示）
                    - unshallow: 兼容旧参数；API 快照不依赖本地历史，该参数无作用
                    - tags: 是否查询标签列表（选填，默认 false）
                    - prune: 兼容旧参数；API 模式没有本地远程跟踪引用，无需清理

                    返回: JSON（success / project / branches[] / tags[] / branchCount / tagCount）。

                    说明: 分支物化由 git-checkout / git-worktree-add 直接按 API 最新 SHA 下载，
                    不需要先 fetch。
                    """
    )
    public String fetch(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/项目路径/项目 ID（必填），如 lms、mtn/lms、1367") String repoDir,
            @ToolParam(name = "remote", description = "兼容旧参数，默认 origin") String remote,
            @ToolParam(name = "allBranches", description = "是否查询所有分支（选填，默认 true）") String allBranches,
            @ToolParam(name = "unshallow", description = "兼容旧参数；API 快照模式无作用") String unshallow,
            @ToolParam(name = "tags", description = "是否查询标签（选填，默认 false）") String tags,
            @ToolParam(name = "prune", description = "兼容旧参数；API 快照模式无作用") String prune
    ) {
        logger.info("git-fetch called, environment:{}, repoDir:{}, remote:{}, allBranches:{}, tags:{}",
                environment, repoDir, remote, allBranches, tags);

        if (!notBlank(environment)) {
            return "[git-fetch] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-fetch] 参数缺失: repoDir 必填";
        }
        String configError = checkConfig("git-fetch", environment);
        if (configError != null) {
            return configError;
        }

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, repoDir);
            boolean includeTags = parseBool(tags, false);
            List<GitlabWorkspaceManager.Branch> branches =
                    workspaceManager.branches(environment, project, null);

            ArrayNode branchArray = JsonUtil.newArray();
            for (GitlabWorkspaceManager.Branch branch : branches) {
                ObjectNode node = JsonUtil.newObject();
                node.put("name", branch.name());
                node.put("commitId", branch.commitId());
                node.put("shortId", GitlabWorkspaceManager.shortHash(branch.commitId()));
                node.put("commitDate", localTime(branch.commitDate(), java.time.ZoneId.systemDefault()));
                node.put("protected", branch.protectedBranch());
                node.put("merged", branch.merged());
                branchArray.add(node);
            }

            ArrayNode tagArray = JsonUtil.newArray();
            if (includeTags) {
                for (GitlabWorkspaceManager.Tag tag : workspaceManager.tags(environment, project)) {
                    ObjectNode node = JsonUtil.newObject();
                    node.put("name", tag.name());
                    node.put("commitId", tag.commitId());
                    node.put("shortId", GitlabWorkspaceManager.shortHash(tag.commitId()));
                    node.put("commitDate", localTime(tag.commitDate(), java.time.ZoneId.systemDefault()));
                    tagArray.add(node);
                }
            }

            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("mode", "gitlab-api-metadata");
            result.put("projectId", project.id());
            result.put("projectPath", project.pathWithNamespace());
            result.put("remote", notBlank(remote) ? remote.trim() : "origin");
            result.put("branchCount", branchArray.size());
            result.put("tagCount", tagArray.size());
            result.put("note", "API 快照模式不需要 fetch 本地 refs；git-checkout / git-worktree-add 会直接读取最新 SHA");
            result.set("branches", branchArray);
            result.set("tags", tagArray);
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError("git-fetch", e, "repoDir=" + repoDir);
        } catch (IllegalArgumentException e) {
            return "[git-fetch] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-fetch 异常, environment:{}, repoDir:{}, error:{}", environment, repoDir, e.getMessage(), e);
            return "[git-fetch] 查询失败: " + e.getMessage();
        }
    }
}
