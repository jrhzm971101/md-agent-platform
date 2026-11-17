package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.ZoneId;

/**
 * GitLab 流水线详情查询工具。
 *
 * <p>用于从部署镜像的 tag 反查流水线，再拿到该流水线对应的代码 ref 与精确 commit SHA。
 * 本项目约定镜像版本号与流水线号一致，因此 image 的 tag 可直接解析为 pipeline id。
 */
@Component
public class GitlabPipelineDetail extends AbstractGitlabTool {

    private static final String TOOL = "gitlab-pipeline-detail";

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "gitlab-pipeline-detail",
            description = """
                    查询单条 GitLab 流水线，返回分支 ref、精确 commit SHA、状态和作者。
                    支持从镜像名直接反查：镜像 tag 为纯数字时按 pipeline id 解析。

                    适用场景:
                    - 已知环境上部署的镜像，如 harbor.example.com/mtn/lms:109478
                    - 已知 pipeline id 或 pipeline URL，需要拿到代码分支和 commit
                    - 需要定位“这个镜像到底部署的是哪次提交的代码”

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - projectId: GitLab 项目 ID / 路径 / 已登记别名（必填），如 1367、mtn/lms、lms
                    - pipelineId: 流水线 ID 或流水线 URL（选填）
                    - image: 镜像名（选填），支持 registry:5000/path:109478、path:v109478

                    pipelineId 与 image 至少填一个；同时填时以 pipelineId 为准。

                    返回 JSON:
                    projectPath、pipelineId、ref、sha、shortSha、status、tag、webUrl、作者、时间、耗时。
                    其中 sha 是流水线构建的精确提交，建议后续用它物化代码，而不是直接用分支 HEAD。

                    注意:
                    - 镜像 tag 必须带数字；image@sha256:... 这种 digest 形式无法反推 pipeline id
                    - 只读工具，不修改仓库
                    """
    )
    public String detail(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "projectId", description = "GitLab 项目 ID / 路径 / 别名（必填），如 1367、mtn/lms、lms") String projectId,
            @ToolParam(name = "pipelineId", description = "流水线 ID 或流水线 URL（选填），如 109478") String pipelineId,
            @ToolParam(name = "image", description = "镜像名（选填），如 harbor.example.com/mtn/lms:109478") String image
    ) {
        String source = notBlank(pipelineId) ? pipelineId : image;
        logger.info("gitlab-pipeline-detail called, environment:{}, projectId:{}, pipelineId:{}, image:{}",
                environment, projectId, pipelineId, image);

        String configError = checkConfig(TOOL, environment);
        if (configError != null) {
            return configError;
        }
        if (!notBlank(projectId)) {
            return "[" + TOOL + "] 参数缺失: projectId 必填";
        }
        if (!notBlank(source)) {
            return "[" + TOOL + "] 参数缺失: pipelineId 与 image 至少填一个";
        }

        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, projectId);
            GitlabWorkspaceManager.Pipeline pipeline = workspaceManager.pipeline(environment, project, source);

            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("projectId", project.id());
            result.put("projectPath", project.pathWithNamespace());
            result.put("pipelineId", pipeline.id());
            result.put("ref", pipeline.ref());
            result.put("sha", pipeline.sha());
            result.put("shortSha", shortSha(pipeline.sha()));
            result.put("beforeSha", pipeline.beforeSha());
            result.put("status", pipeline.status());
            result.put("tag", pipeline.tag());
            result.put("webUrl", pipeline.webUrl());
            result.put("createdAt", localTime(pipeline.createdAt(), ZoneId.systemDefault()));
            result.put("startedAt", localTime(pipeline.startedAt(), ZoneId.systemDefault()));
            result.put("finishedAt", localTime(pipeline.finishedAt(), ZoneId.systemDefault()));
            result.put("durationSeconds", pipeline.duration());
            result.put("userName", pipeline.userName());
            result.put("userUsername", pipeline.userUsername());
            if (notBlank(image)) {
                result.put("image", image);
            }
            result.put("suggestedCheckout", "git-pipeline-checkout environment=" + environment + " repoDir=" + project.pathWithNamespace()
                    + " pipelineId=" + pipeline.id());
            result.put("description", "使用 sha 物化代码可确保与流水线构建的提交完全一致；ref 仅用于展示分支来源。");
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError(TOOL, e, "pipeline=" + source + ", project=" + projectId);
        } catch (IllegalArgumentException e) {
            return "[" + TOOL + "] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("gitlab-pipeline-detail 异常, environment:{}, projectId:{}, source:{}, error:{}",
                    environment, projectId, source, e.getMessage(), e);
            return "[" + TOOL + "] 查询异常: " + e.getMessage();
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }
}
