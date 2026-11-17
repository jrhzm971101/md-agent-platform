package com.md.agentplatform.server.tool.git;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * API 工作区删除工具。
 */
@Component
public class GitWorktreeRemove extends AbstractWorktreeTool {

    @Tool(
            name = "git-worktree-remove",
            description = """
                    删除一个 API 快照工作区目录（不涉及 .git/worktree 元数据）。

                    适用场景:
                    - 分支分析结束，释放磁盘
                    - 工作区目录建错，需要删除重建

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库别名/任意工作区目录/项目路径/ID（必填）
                    - path: 要删除的工作区目录名或绝对路径（必填），如 lms/MTN-Feature-Uganda
                    - force: 兼容旧参数；基准目录（base）删除时必须显式传 true

                    返回: 删除结果。

                    说明: 只允许删除 gitlab.base-dir 下的、清单属于当前项目的 API 工作区。
                    """
    )
    public String remove(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/任意工作区目录/项目路径/ID（必填）") String repoDir,
            @ToolParam(name = "path", description = "要删除的工作区目录名或绝对路径（必填）") String path,
            @ToolParam(name = "force", description = "删除 base 基准目录时需传 true（选填）") String force
    ) {
        logger.info("git-worktree-remove called, environment:{}, repoDir:{}, path:{}, force:{}", environment, repoDir, path, force);

        if (!notBlank(environment)) {
            return "[git-worktree-remove] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-worktree-remove] 参数缺失: repoDir 必填";
        }
        if (!notBlank(path)) {
            return "[git-worktree-remove] 参数缺失: path 必填";
        }

        try {
            GitlabWorkspaceManager.Project project = resolveProject(environment, repoDir);
            Path target = workspaceManager.resolveWorkspacePath(path);
            workspaceManager.removeWorkspace(environment, project, target, parseBool(force, false));
            return "[git-worktree-remove] ✅ 已删除工作区: " + target.toAbsolutePath()
                    + "（其它分支目录不受影响）";
        } catch (IllegalArgumentException e) {
            return "[git-worktree-remove] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-worktree-remove 异常, environment:{}, repoDir:{}, path:{}, error:{}",
                    environment, repoDir, path, e.getMessage(), e);
            return "[git-worktree-remove] 删除失败: " + e.getMessage();
        }
    }
}
