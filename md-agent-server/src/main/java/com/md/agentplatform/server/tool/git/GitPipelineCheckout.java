package com.md.agentplatform.server.tool.git;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * 按流水线物化代码快照。
 *
 * <p>先通过 pipeline id / 镜像 tag 查询流水线，再用流水线的精确 commit SHA 下载 archive，
 * 因此本地代码与流水线构建的提交完全一致，不会受分支 HEAD 已前进的影响。
 */
@Component
public class GitPipelineCheckout extends AbstractGitlabTool {

    private static final String TOOL = "git-pipeline-checkout";

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "git-pipeline-checkout",
            description = """
                    根据流水线 ID 或部署镜像名，把该流水线对应的精确代码提交物化到本地仓库目录。

                    工作方式:
                    1. 从 image 的 tag 解析 pipeline id（本项目镜像版本号 = 流水线号）
                    2. 查询 GET /projects/:id/pipelines/:pipeline_id 拿到 ref 与 sha
                    3. 用 sha 下载 archive，落到 <repo>/pipeline-<id>/
                    4. 返回可读取的绝对路径、分支、精确 commit

                    适用场景:
                    - 已知环境部署镜像 harbor.example.com/mtn/lms:109478
                    - 需要分析“这个镜像对应的代码”，而不是分支当前最新代码
                    - 流水线失败/历史版本回溯时，精确复现构建提交

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - repoDir: 已 clone 的仓库别名/仓库目录/项目路径/ID（必填），如 lms、mtn/lms、1367
                    - pipelineId: 流水线 ID 或 URL（选填），如 109478
                    - image: 镜像名（选填），如 harbor.example.com/mtn/lms:109478
                    - path: 分支子目录名（选填，默认 pipeline-<id>）

                    pipelineId 与 image 至少填一个；同时填时以 pipelineId 为准。

                    返回: 工作区绝对路径、流水线号、ref、精确 commit、流水线状态。

                    说明:
                    - 目录布局仍为 <gitlab.base-dir>/<repo>/<path>/
                    - 使用 sha 而不是 ref，保证与流水线构建提交一致
                    - 目录没有 .git，只用于本地读取和分析
                    """
    )
    public String checkout(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "repoDir", description = "仓库别名/仓库目录/项目路径/ID（必填），如 lms") String repoDir,
            @ToolParam(name = "pipelineId", description = "流水线 ID 或 URL（选填），如 109478") String pipelineId,
            @ToolParam(name = "image", description = "镜像名（选填），如 harbor.example.com/mtn/lms:109478") String image,
            @ToolParam(name = "path", description = "分支子目录名（选填，默认 pipeline-<id>）") String path
    ) {
        String source = notBlank(pipelineId) ? pipelineId : image;
        logger.info("git-pipeline-checkout called, environment:{}, repoDir:{}, pipelineId:{}, image:{}, path:{}",
                environment, repoDir, pipelineId, image, path);

        if (!notBlank(environment)) {
            return "[" + TOOL + "] 参数缺失: environment 必填";
        }
        if (!notBlank(repoDir)) {
            return "[" + TOOL + "] 参数缺失: repoDir 必填";
        }
        if (!notBlank(source)) {
            return "[" + TOOL + "] 参数缺失: pipelineId 与 image 至少填一个";
        }

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, repoDir);
            String alias = workspaceManager.aliasOf(environment, repoDir, project);
            GitlabWorkspaceManager.Pipeline pipeline = workspaceManager.pipeline(environment, project, source);
            String targetName = notBlank(path) ? path.trim() : "pipeline-" + pipeline.id();
            GitlabWorkspaceManager.Workspace workspace = workspaceManager.ensureWorkspace(
                    environment, project, alias, pipeline.sha(), targetName, true, false);
            workspaceManager.registerAlias(environment, alias, project);

            StringBuilder message = new StringBuilder("[" + TOOL + "] ✅ 已按流水线物化代码: ")
                    .append(workspace.path().toAbsolutePath())
                    .append("\n流水线: #").append(pipeline.id())
                    .append(" (").append(pipeline.status()).append(")")
                    .append(", 分支: ").append(pipeline.ref())
                    .append(", 精确提交: ").append(shortSha(pipeline.sha()))
                    .append("\n仓库目录: ").append(workspace.path().getParent().toAbsolutePath())
                    .append(", 后续可用 repoDir=").append(workspace.path().getParent().getFileName())
                    .append("/").append(workspace.path().getFileName()).append(" 读取")
                    .append("\n说明: 使用 pipeline.sha 物化，代码与流水线构建提交一致，不受分支 HEAD 变化影响。");
            if ("failed".equalsIgnoreCase(pipeline.status())) {
                message.append("\n⚠️ 该流水线状态为 failed，请确认环境镜像是否确实由该流水线产出。");
            }
            return message.toString();
        } catch (HttpStatusCodeException e) {
            return describeHttpError(TOOL, e, "pipeline=" + source + ", repoDir=" + repoDir);
        } catch (IllegalArgumentException e) {
            return "[" + TOOL + "] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("git-pipeline-checkout 异常, environment:{}, repoDir:{}, source:{}, error:{}",
                    environment, repoDir, source, e.getMessage(), e);
            return "[" + TOOL + "] 物化失败: " + e.getMessage();
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }
}
