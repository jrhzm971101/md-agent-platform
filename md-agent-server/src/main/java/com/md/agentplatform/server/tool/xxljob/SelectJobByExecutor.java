package com.md.agentplatform.server.tool.xxljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.md.agentplatform.server.config.XxlJobProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * XXL-JOB 任务查询工具 —— 调用 XXL-JOB Admin 控制台的 jobinfo/pageList 接口，
 * 按执行器（jobGroup）查询已注册的定时任务列表。
 */
@Component
public class SelectJobByExecutor extends AbstractXxlJobTool {

    /**
     * XXL-JOB Admin 控制台 jobinfo/pageList 接口相对路径
     */
    private static final String PAGE_LIST_PATH = "/jobinfo/pageList";

    @Tool(
            name = "select-job-by-executor",
            description = """
                    查询 XXL-JOB 调度平台中指定执行器（jobGroup）下的定时任务列表。

                    适用场景:
                    - 用户询问"有哪些定时任务"、"某个执行器下有哪些 Job"
                    - 查找某个 Job 的 JobHandler 名称 / Cron 调度配置，以便后续触发或修改
                    - 排查某个定时任务是否存在及其运行状态
                    - 对比两个 XXL-JOB 实例（A/B）的任务差异：分别传入各自的 consoleUrl 和 loginIdentity

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，控制台地址从环境目录解析
                    - loginIdentity: 登录态 Cookie payload（必填），由 xxl-job-login 获取
                    - jobGroup: 执行器组 ID（必填），如 2
                    - triggerStatus: 任务状态过滤，-1 全部、0 停止、1 运行（默认 -1）
                    - executorHandler: 按 JobHandler 名称过滤（选填），如 autoDeductJob

                    返回: 任务列表，包含 id、任务描述、JobHandler、Cron、作者、状态等字段。
                    """
    )
    public String query(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "loginIdentity", description = "登录态 Cookie 值 XXL_JOB_LOGIN_IDENTITY 的 payload（必填）") String loginIdentity,
            @ToolParam(name = "jobGroup", description = "执行器组 ID（必填），如 2") String jobGroup,
            @ToolParam(name = "triggerStatus", description = "任务状态过滤，-1 全部、0 停止、1 运行，默认 -1") String triggerStatus,
            @ToolParam(name = "executorHandler", description = "按 JobHandler 名称过滤（选填），如 autoDeductJob") String executorHandler) {

        logger.info("select-job-by-executor called, environment:{}, jobGroup:{}, triggerStatus:{}, executorHandler:{}",
                environment, jobGroup, triggerStatus, executorHandler);

        try {
            XxlJobProperties.Page page = xxlJobProperties.getPage();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set(HttpHeaders.COOKIE, buildCookie(loginIdentity));

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("jobGroup", defaultIfBlank(jobGroup, page.getJobGroup()));
            body.add("triggerStatus", defaultIfBlank(triggerStatus, page.getTriggerStatus()));
            body.add("start", page.getStart());
            body.add("length", page.getLength());
            if (executorHandler != null && !executorHandler.isBlank()) {
                body.add("executorHandler", executorHandler.trim());
            }

            String url = buildUrl(consoleUrlOf(environment), PAGE_LIST_PATH);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.error("select-job-by-executor 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return "[select-job-by-executor] 请求失败, HTTP " + response.getStatusCode()
                        + ", body: " + response.getBody();
            }

            return formatResult(response.getBody());

        } catch (Exception e) {
            logger.error("select-job-by-executor 异常, jobGroup:{}, error:{}", jobGroup, e.getMessage(), e);
            return "[select-job-by-executor] 查询异常: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 结果格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatResult(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int total = root.path("recordsFiltered").asInt(root.path("recordsTotal").asInt(0));
            JsonNode data = root.path("data");

            StringBuilder sb = new StringBuilder();
            sb.append("[select-job-by-executor] 共 ").append(total).append(" 个任务");

            if (!data.isArray() || data.isEmpty()) {
                sb.append("\n(无任务)");
                return sb.toString();
            }

            sb.append(", 本次返回 ").append(data.size()).append(" 个:\n");
            for (JsonNode job : data) {
                sb.append("\n---\n");
                sb.append("id: ").append(job.path("id").asText()).append("\n");
                sb.append("任务描述: ").append(job.path("jobDesc").asText()).append("\n");
                sb.append("JobHandler: ").append(job.path("executorHandler").asText()).append("\n");
                sb.append("Cron: ").append(job.path("scheduleConf").asText()).append("\n");
                sb.append("状态: ").append(translateTriggerStatus(job.path("triggerStatus").asInt(-1))).append("\n");
                sb.append("作者: ").append(job.path("author").asText()).append("\n");
                String param = job.path("executorParam").asText();
                if (param != null && !param.isBlank()) {
                    sb.append("参数: ").append(param).append("\n");
                }
            }
            return sb.toString();

        } catch (Exception e) {
            logger.warn("select-job-by-executor 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }
    }

    private static String translateTriggerStatus(int status) {
        return switch (status) {
            case 1 -> "运行中";
            case 0 -> "已停止";
            default -> "未知(" + status + ")";
        };
    }
}