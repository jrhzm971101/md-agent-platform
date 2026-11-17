package com.md.agentplatform.server.tool.git;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 工作区工具公共基类。
 *
 * <p>工作区不再使用 Git worktree 元数据，而是由 GitLab archive 物化出的独立快照目录。
 * 本类只负责把 repoDir 解析成 GitLab 项目与仓库别名。
 */
public abstract class AbstractWorktreeTool extends AbstractGitTool {

    @Autowired
    protected GitlabWorkspaceManager workspaceManager;

    protected GitlabWorkspaceManager.Project resolveProject(String environment, String repoDir) {
        return workspaceManager.resolveProject(environment, repoDir);
    }

    protected String resolveAlias(String environment, String repoDir, GitlabWorkspaceManager.Project project) {
        return workspaceManager.aliasOf(environment, repoDir, project);
    }
}
