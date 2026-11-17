package com.md.agentplatform.server.tool.git;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Git 仓库准备工具。
 *
 * <p>不再执行本地 git clone，而是通过 GitLab archive API 把指定 commit 的文件树
 * 物化到 gitlab.base-dir 下的独立目录。目录中没有 .git，但完整保留源码文件结构。
 */
@Component
public class GitClone extends AbstractGitTool {

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "git-clone",
            description = """
                    通过 GitLab API 创建仓库的基准代码快照（不再执行本地 git clone）。

                    适用场景:
                    - 用户说"克隆/拉取某个仓库的代码"
                    - 首次分析一个 GitLab 仓库，需要把某个分支下载到本地

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoUrl: GitLab 项目地址/路径/ID（必填），如
                      https://git.huojintech.com/mtn/lms.git、mtn/lms、1367
                    - repoDir: 仓库目录名/别名（必填），如 lms；分支代码会放到 gitlab.base-dir/lms/<branch>
                    - branch: 要物化的分支/标签/SHA（选填，默认项目默认分支）
                    - depth: 兼容旧参数，API 快照模式不适用（无论传什么都会获取完整文件树）

                    返回: 成功时返回绝对路径、分支、commit SHA、工作区类型。

                    说明:
                    - 实现调用 GET /projects/:id/repository/archive.zip?sha=<commit>
                    - 不生成 .git，不能 commit/push，也不会修改 GitLab 仓库
                    - 同一仓库的不同分支会用 git-worktree-add 放到 lms/ 下的不同子目录
                    """
    )
    public String clone(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoUrl", description = "GitLab 项目地址/路径/ID（必填），如 https://git.huojintech.com/mtn/lms.git 或 mtn/lms") String repoUrl,
            @ToolParam(name = "repoDir", description = "本地目录名/别名（必填），如 lms，自动拼到 gitlab.base-dir 下") String repoDir,
            @ToolParam(name = "branch", description = "要物化的分支/标签/SHA（选填），默认项目默认分支") String branch,
            @ToolParam(name = "depth", description = "兼容旧参数；API 快照模式不适用") String depth
    ) {
        logger.info("git-clone called, environment:{}, repoUrl:{}, repoDir:{}, branch:{}, depth:{}",
                environment, repoUrl, repoDir, branch, depth);

        if (!notBlank(environment)) {
            return "[git-clone] 参数缺失: environment 必填";
        }
        if (!notBlank(repoUrl)) {
            return "[git-clone] 参数缺失: repoUrl 必填";
        }
        if (!notBlank(repoDir)) {
            return "[git-clone] 参数缺失: repoDir 必填";
        }

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, repoUrl);
            Path raw = Path.of(repoDir.trim());
            String alias = GitlabWorkspaceManager.safeName(
                    raw.getFileName() == null ? repoDir : raw.getFileName().toString());
            GitlabWorkspaceManager.Workspace workspace =
                    workspaceManager.cloneBase(environment, project, alias, branch, false);
            return "[git-clone] ✅ 已创建 API 快照: " + workspace.path().toAbsolutePath()
                    + ", 分支: " + workspace.branch()
                    + ", 提交: " + GitlabWorkspaceManager.shortHash(workspace.commitSha())
                    + ", 项目: " + project.pathWithNamespace()
                    + "\n说明: 仓库目录为 " + workspace.path().getParent().toAbsolutePath()
                    + "，分支目录为其下的 " + workspace.path().getFileName() + "；目录由 GitLab archive API 物化，不含 .git；depth 参数已忽略。"
                    + "后续可直接用 filesystemTool 读取，或并行执行 git-worktree-add 获取其它分支。";
        } catch (IllegalArgumentException e) {
            return "[git-clone] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-clone 异常, environment:{}, repoUrl:{}, repoDir:{}, error:{}",
                    environment, repoUrl, repoDir, e.getMessage(), e);
            return "[git-clone] 创建失败: " + e.getMessage();
        }
    }
}
