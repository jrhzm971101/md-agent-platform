package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.ZoneId;
import java.util.List;

/**
 * GitLab 流水线列表查询工具。
 *
 * <p>支持按分支 ref、commit SHA、状态、触发人过滤，用于在不知道 pipeline id 时定位流水线。
 */
@Component
public class GitlabPipelines extends AbstractGitlabTool {

    private static final String TOOL = "gitlab-pipelines";
    private static final int DEFAULT_MAX_COUNT = 20;
    private static final int HARD_MAX_COUNT = 200;

    @Autowired
    private GitlabWorkspaceManager workspaceManager;

    @Tool(
            name = "gitlab-pipelines",
            description = """
                    查询 GitLab 项目流水线列表，可按分支、commit SHA、状态、触发人过滤。

                    适用场景:
                    - 不知道 pipeline id，只知道分支或提交，需要找对应流水线
                    - 查看某个分支最近的流水线状态
                    - 确认某个 commit 是否跑过流水线

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - projectId: 项目 ID / 路径 / 已登记别名（必填），如 1367、mtn/lms、lms
                    - ref: 分支或标签名（选填），如 mtn-main/cameroon-airtime
                    - sha: commit SHA（选填）
                    - status: 状态（选填），如 success、failed、running、pending、canceled
                    - username: 触发人用户名（选填）
                    - maxCount: 最多返回条数（选填，默认 20，最大 200）

                    返回 JSON: count 与 pipelines[]，每条含 id、ref、sha、shortSha、status、webUrl、时间、作者。
                    输出按 pipeline id 倒序，最新的在前。
                    """
    )
    public String list(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "projectId", description = "项目 ID / 路径 / 别名（必填），如 1367、mtn/lms") String projectId,
            @ToolParam(name = "ref", description = "分支或标签名（选填）") String ref,
            @ToolParam(name = "sha", description = "commit SHA（选填）") String sha,
            @ToolParam(name = "status", description = "流水线状态（选填），如 success、failed、running") String status,
            @ToolParam(name = "username", description = "触发人用户名（选填）") String username,
            @ToolParam(name = "maxCount", description = "最多返回条数（选填，默认 20，最大 200）") String maxCount
    ) {
        logger.info("gitlab-pipelines called, environment:{}, projectId:{}, ref:{}, sha:{}, status:{}, username:{}, maxCount:{}",
                environment, projectId, ref, sha, status, username, maxCount);

        String configError = checkConfig(TOOL, environment);
        if (configError != null) {
            return configError;
        }
        if (!notBlank(projectId)) {
            return "[" + TOOL + "] 参数缺失: projectId 必填";
        }

        int limit = parseLimit(maxCount, DEFAULT_MAX_COUNT, 1, HARD_MAX_COUNT);
        try {
            GitlabWorkspaceManager.Project project = workspaceManager.resolveProject(environment, projectId);
            List<GitlabWorkspaceManager.Pipeline> pipelines = workspaceManager.pipelines(
                    environment, project, ref, sha, status, username, limit);

            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("projectId", project.id());
            result.put("projectPath", project.pathWithNamespace());
            result.put("count", pipelines.size());
            ArrayNode items = result.putArray("pipelines");
            for (GitlabWorkspaceManager.Pipeline pipeline : pipelines) {
                ObjectNode item = items.addObject();
                item.put("pipelineId", pipeline.id());
                item.put("ref", pipeline.ref());
                item.put("sha", pipeline.sha());
                item.put("shortSha", shortSha(pipeline.sha()));
                item.put("status", pipeline.status());
                item.put("tag", pipeline.tag());
                item.put("webUrl", pipeline.webUrl());
                item.put("createdAt", localTime(pipeline.createdAt(), ZoneId.systemDefault()));
                item.put("userName", pipeline.userName());
                item.put("userUsername", pipeline.userUsername());
            }
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError(TOOL, e, "project=" + projectId);
        } catch (IllegalArgumentException e) {
            return "[" + TOOL + "] ❌ " + e.getMessage();
        } catch (Exception e) {
            logger.error("gitlab-pipelines 异常, environment:{}, projectId:{}, error:{}", environment, projectId, e.getMessage(), e);
            return "[" + TOOL + "] 查询异常: " + e.getMessage();
        }
    }

    private static int parseLimit(String value, int defaultValue, int min, int max) {
        if (!notBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }
}
