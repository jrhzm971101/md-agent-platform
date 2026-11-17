package com.md.agentplatform.server.tool.git;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * API 工作区列表工具。
 *
 * <p>读取 gitlab.base-dir 下所有 .md-agent-workspace.json 清单，展示目录、分支、commit、
 * 是否落后远端，以及工作区类型（base / workspace）。
 */
@Component
public class GitWorktreeList extends AbstractWorktreeTool {

    @Tool(
            name = "git-worktree-list",
            description = """
                    列出某个 GitLab 仓库在本地的所有 API 快照工作区。

                    适用场景:
                    - 确认某个目录对应哪条分支
                    - 查看各分支目录是否落后远端
                    - 并行分析前确认已有哪些分支目录

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库别名/任意工作区目录/项目路径/ID（必填），如 lms

                    返回: 每行一个分支目录，含 路径 | 仓库 | 类型 | 分支 | commit | 是否最新。
                    典型布局: gitlab.base-dir/lms/master、gitlab.base-dir/lms/MTN-Feature-Cameroon。

                    说明: 列表来自工作区清单 .md-agent-workspace.json，不依赖 .git/worktrees。
                    """
    )
    public String list(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/任意工作区目录/项目路径/ID（必填）") String repoDir
    ) {
        logger.info("git-worktree-list called, environment:{}, repoDir:{}", environment, repoDir);

        if (!notBlank(environment)) {
            return "[git-worktree-list] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-worktree-list] 参数缺失: repoDir 必填";
        }

        try {
            GitlabWorkspaceManager.Project project = resolveProject(environment, repoDir);
            List<GitlabWorkspaceManager.WorkspaceEntry> entries =
                    workspaceManager.listWorkspaces(environment, project);
            if (entries.isEmpty()) {
                return "[git-worktree-list] 暂无工作区。请先执行 git-clone。";
            }

            StringBuilder message = new StringBuilder("[git-worktree-list] 项目: ")
                    .append(project.pathWithNamespace())
                    .append("（工作区 ").append(entries.size()).append(" 个）\n");
            int index = 1;
            for (GitlabWorkspaceManager.WorkspaceEntry entry : entries) {
                message.append(index++).append(". ").append(entry.path().toAbsolutePath())
                        .append(" | 仓库: ").append(entry.alias())
                        .append(" | 类型: ").append(entry.kind())
                        .append(" | 分支: ").append(entry.branch())
                        .append(" | 提交: ").append(GitlabWorkspaceManager.shortHash(entry.commitSha()))
                        .append(" | 获取时间: ").append(entry.fetchedAt());
                try {
                    GitlabWorkspaceManager.RefCommit latest =
                            workspaceManager.resolveRef(environment, project, entry.branch());
                    if (latest.commitId().equals(entry.commitSha())) {
                        message.append(" | ✅ 已最新");
                    } else {
                        message.append(" | ⚠️ 落后远端，最新 ")
                                .append(GitlabWorkspaceManager.shortHash(latest.commitId()));
                    }
                } catch (Exception e) {
                    message.append(" | 无法比较远端版本");
                }
                message.append('\n');
            }
            message.append("同一仓库的分支都在同一个仓库目录下；可并行用 filesystemTool 读取不同分支目录，需要刷新用 git-pull。");
            return message.toString().trim();
        } catch (IllegalArgumentException e) {
            return "[git-worktree-list] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-worktree-list 异常, environment:{}, repoDir:{}, error:{}", environment, repoDir, e.getMessage(), e);
            return "[git-worktree-list] 查询失败: " + e.getMessage();
        }
    }
}
