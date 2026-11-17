package com.md.agentplatform.server.tool.git;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * API 工作区临时文件清理工具。
 */
@Component
public class GitWorktreePrune extends AbstractWorktreeTool {

    @Tool(
            name = "git-worktree-prune",
            description = """
                    清理 API 快照模式留下的临时文件/目录。

                    适用场景:
                    - archive 下载或解压中途失败，gitlab.base-dir 下残留 *.zip.tmp-* / *.tmp-* / *.old-*
                    - 长时间运行后需要清理临时空间

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 仓库别名/任意工作区目录/项目路径/ID（必填，用于校验项目）
                    - dryRun: 是否只查看不删除（选填，默认 false）

                    返回: 被清理（或待清理）的临时文件清单。

                    说明: 只清理超过 10 分钟的临时项，避免影响正在并行下载的其它任务。
                    正常的 .md-agent-workspace.json 工作区不会被删除。
                    """
    )
    public String prune(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/任意工作区目录/项目路径/ID（必填）") String repoDir,
            @ToolParam(name = "dryRun", description = "是否只查看不删除（选填，默认 false）") String dryRun
    ) {
        logger.info("git-worktree-prune called, environment:{}, repoDir:{}, dryRun:{}", environment, repoDir, dryRun);

        if (!notBlank(environment)) {
            return "[git-worktree-prune] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-worktree-prune] 参数缺失: repoDir 必填";
        }

        try {
            resolveProject(environment, repoDir);
            boolean dryRunFlag = parseBool(dryRun, false);
            List<String> pruned = workspaceManager.pruneTempDirs(dryRunFlag);
            if (pruned.isEmpty()) {
                return "[git-worktree-prune] 无需清理：没有超时的临时文件";
            }
            return "[git-worktree-prune] " + (dryRunFlag ? "待清理" : "✅ 已清理")
                    + " " + pruned.size() + " 项: " + String.join(", ", pruned);
        } catch (IllegalArgumentException e) {
            return "[git-worktree-prune] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-worktree-prune 异常, environment:{}, repoDir:{}, error:{}", environment, repoDir, e.getMessage(), e);
            return "[git-worktree-prune] 清理失败: " + e.getMessage();
        }
    }
}
