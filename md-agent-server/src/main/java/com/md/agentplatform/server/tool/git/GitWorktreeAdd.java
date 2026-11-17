package com.md.agentplatform.server.tool.git;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 分支快照工作区创建工具。
 *
 * <p>名称保留 git-worktree-add 以兼容 Agent，但实现已改为 GitLab archive API 物化。
 * 每条分支使用独立目录，天然支持并行分析。
 */
@Component
public class GitWorktreeAdd extends AbstractWorktreeTool {

    @Tool(
            name = "git-worktree-add",
            description = """
                    通过 GitLab API 为指定分支创建独立代码快照目录（原 git worktree add 的替代实现）。

                    适用场景:
                    - 同时分析多条分支（如对比 Cameroon / Uganda）
                    - 已经 clone 了基准目录，需要为另一个分支准备独立目录
                    - 多个分析任务并行执行，互不影响

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库别名/基准目录/项目路径/ID（必填），如 lms
                    - branch: 分支/标签/SHA（必填），如 MTN-Feature/CDI，也接受 origin/MTN-Feature/CDI
                    - path: 分支子目录名（选填，默认 <branch>，父目录固定为仓库目录）
                    - force: 目录已存在且分支不同/非 API 工作区时是否覆盖（选填，默认 false）
                    - detach: 兼容旧参数；API 快照本身不绑定本地分支，可忽略
                    - update: 是否刷新到远端最新 SHA（选填，默认 true）

                    返回: 工作区绝对路径、分支、commit SHA。

                    说明:
                    - 仓库目录固定为 gitlab.base-dir/<alias>，每个分支是它下面的独立子目录，目录之间没有共享索引锁
                    - 多个 git-worktree-add 可以并行调用
                    - 目录内没有 .git，只用于本地读取/检索/分析
                    """
    )
    public String add(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/基准目录/项目路径/ID（必填），如 lms") String repoDir,
            @ToolParam(name = "branch", description = "要获取的分支/标签/SHA（必填）") String branch,
            @ToolParam(name = "path", description = "工作区目录名（选填），默认 <branch>") String path,
            @ToolParam(name = "force", description = "是否覆盖已有目录（选填，默认 false）") String force,
            @ToolParam(name = "detach", description = "兼容旧参数；API 快照无需 detach") String detach,
            @ToolParam(name = "update", description = "是否刷新到远端最新 SHA（选填，默认 true）") String update
    ) {
        logger.info("git-worktree-add called, environment:{}, repoDir:{}, branch:{}, path:{}, force:{}, detach:{}, update:{}",
                environment, repoDir, branch, path, force, detach, update);

        if (!notBlank(environment)) {
            return "[git-worktree-add] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-worktree-add] 参数缺失: repoDir 必填";
        }
        if (!notBlank(branch)) {
            return "[git-worktree-add] 参数缺失: branch 必填";
        }

        try {
            GitlabWorkspaceManager.Project project = resolveProject(environment, repoDir);
            String alias = resolveAlias(environment, repoDir, project);
            boolean forceFlag = parseBool(force, false);
            boolean updateFlag = parseBool(update, true);
            GitlabWorkspaceManager.Workspace workspace = workspaceManager.ensureWorkspace(
                    environment, project, alias, branch.trim(), path, updateFlag, forceFlag);

            StringBuilder message = new StringBuilder("[git-worktree-add] ✅ 工作区已就绪: ")
                    .append(workspace.path().toAbsolutePath())
                    .append(", 分支: ").append(workspace.branch())
                    .append(", 提交: ").append(GitlabWorkspaceManager.shortHash(workspace.commitSha()));
            if (workspace.stale()) {
                message.append("\n⚠️ 该目录已存在且落后远端；传 update=true 可刷新");
            } else if (workspace.created()) {
                message.append("\n已按最新 SHA 物化独立快照目录，可与其它分支目录并行分析。");
            } else {
                message.append("\n目录已是最新，直接复用。");
            }
            message.append("\n后续用 repoDir=").append(workspace.path().getParent().getFileName()).append("/").append(workspace.path().getFileName());
            message.append(" 读取该分支代码。");
            return message.toString();
        } catch (IllegalArgumentException e) {
            return "[git-worktree-add] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-worktree-add 异常, environment:{}, repoDir:{}, branch:{}, error:{}",
                    environment, repoDir, branch, e.getMessage(), e);
            return "[git-worktree-add] 创建失败: " + e.getMessage();
        }
    }
}
