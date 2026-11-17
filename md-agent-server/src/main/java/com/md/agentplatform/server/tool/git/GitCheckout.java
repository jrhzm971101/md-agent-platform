package com.md.agentplatform.server.tool.git;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 分支快照准备工具。
 *
 * <p>checkout 不修改任何已有目录，而是把目标分支的最新 commit 物化到
 * gitlab.base-dir/<alias>/<branch>。因此多个分支天然是不同目录，可并行分析。
 */
@Component
public class GitCheckout extends AbstractGitTool {

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "git-checkout",
            description = """
                    通过 GitLab API 准备指定分支的独立代码快照目录（等效于只读 checkout）。

                    适用场景:
                    - 用户明确说"切换到 xx 分支"或"看一下 xx 分支的代码"
                    - 需要在保留其它分支目录的同时，获取该分支最新代码

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 已 git-clone 的别名/目录（必填），如 lms 或绝对工作区路径
                    - branch: 分支/标签/commit SHA（必填），如 main、develop、MTN-Feature/CDI

                    返回: 独立目录的绝对路径、分支、commit SHA。

                    说明:
                    - 实现调用 GET /repository/archive.zip?sha=<该分支最新 commit>
                    - 不切换、不删除、不修改仓库基准目录和其它分支目录
                    - 目录固定为 <alias>/<branch>；同一分支重复调用会复用或刷新该目录
                    - 这是只读快照，不支持 commit/push
                    """
    )
    public String checkout(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "已 git-clone 的别名/目录（必填），如 lms") String repoDir,
            @ToolParam(name = "branch", description = "分支/标签/commit SHA（必填），如 main、develop") String branch
    ) {
        logger.info("git-checkout called, environment:{}, repoDir:{}, branch:{}", environment, repoDir, branch);

        if (!notBlank(environment)) {
            return "[git-checkout] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-checkout] 参数缺失: repoDir 必填";
        }
        if (!notBlank(branch)) {
            return "[git-checkout] 参数缺失: branch 必填";
        }

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, repoDir);
            String alias = workspaceManager.aliasOf(environment, repoDir, project);
            GitlabWorkspaceManager.Workspace workspace =
                    workspaceManager.checkout(environment, project, alias, branch.trim());
            return "[git-checkout] ✅ 分支快照已就绪: " + workspace.path().toAbsolutePath()
                    + ", 分支: " + workspace.branch()
                    + ", 提交: " + GitlabWorkspaceManager.shortHash(workspace.commitSha())
                    + "\n后续用该目录读取代码；其它分支目录不受影响，可并行分析。";
        } catch (IllegalArgumentException e) {
            return "[git-checkout] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-checkout 异常, environment:{}, repoDir:{}, branch:{}, error:{}",
                    environment, repoDir, branch, e.getMessage(), e);
            return "[git-checkout] 创建快照失败: " + e.getMessage();
        }
    }
}
