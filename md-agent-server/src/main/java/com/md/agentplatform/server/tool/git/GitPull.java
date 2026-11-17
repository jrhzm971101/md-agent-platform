package com.md.agentplatform.server.tool.git;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 快照刷新工具。
 *
 * <p>通过 GitLab API 查询分支最新 commit；SHA 未变则直接复用目录，
 * 变化则重新下载 archive 并原子替换，不会产生 merge/rebase 冲突。
 */
@Component
public class GitPull extends AbstractGitTool {

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "git-pull",
            description = """
                    通过 GitLab API 刷新本地代码快照到远端最新提交（不执行本地 merge/rebase）。

                    适用场景:
                    - 用户说"更新一下代码"、"拉取最新代码"
                    - 已有工作区目录，需要确认并刷新到分支最新 SHA

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库目录/分支目录/别名（必填），如 lms 或 lms/MTN-Feature-Cameroon
                    - branch: 要拉取的分支（选填）。不传则沿用该目录已绑定的分支；
                      对基准目录不传则使用其当前分支

                    返回: 目录绝对路径、分支、提交 SHA；返回中会说明是复用还是重新物化。

                    说明:
                    - 查询 GET /repository/branches/:branch 获取最新 commit SHA
                    - SHA 相同: 不重复下载；SHA 不同: 下载 archive 后原子替换目录
                    - 只更新目标快照目录，不影响其它分支目录
                    - 不支持本地未提交改动合并（快照目录只用于只读分析）
                    """
    )
    public String pull(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "基准目录/分支快照目录/别名（必填），如 lms 或 lms/MTN-Feature-Cameroon") String repoDir,
            @ToolParam(name = "branch", description = "要拉取的分支（选填），默认沿用目录当前分支") String branch
    ) {
        logger.info("git-pull called, environment:{}, repoDir:{}, branch:{}", environment, repoDir, branch);

        if (!notBlank(environment)) {
            return "[git-pull] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-pull] 参数缺失: repoDir 必填";
        }

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, repoDir);
            GitlabWorkspaceManager.Workspace workspace =
                    workspaceManager.refresh(environment, project, repoDir, branch, false);
            String action;
            if (workspace.stale()) {
                action = "目录已存在但落后远端，可重试或检查 update 逻辑";
            } else if (workspace.created()) {
                action = "已重新物化到最新提交";
            } else {
                action = "已是最新提交，无需下载";
            }
            return "[git-pull] ✅ " + action + ": " + workspace.path().toAbsolutePath()
                    + ", 分支: " + workspace.branch()
                    + ", 提交: " + GitlabWorkspaceManager.shortHash(workspace.commitSha());
        } catch (IllegalArgumentException e) {
            return "[git-pull] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-pull 异常, environment:{}, repoDir:{}, branch:{}, error:{}",
                    environment, repoDir, branch, e.getMessage(), e);
            return "[git-pull] 刷新失败: " + e.getMessage();
        }
    }
}
