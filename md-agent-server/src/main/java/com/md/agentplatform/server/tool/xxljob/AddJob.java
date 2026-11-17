package com.md.agentplatform.server.tool.xxljob;

import com.fasterxml.jackson.databind.JsonNode;
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
 * XXL-JOB 任务新增工具 —— 调用 XXL-JOB Admin 控制台的 jobinfo/add 接口，
 * 创建新的定时任务。
 */
@Component
public class AddJob extends AbstractXxlJobTool {

    /**
     * XXL-JOB Admin 控制台 jobinfo/add 接口相对路径
     */
    private static final String JOB_INFO_ADD_PATH = "/jobinfo/add";

    @Tool(
            name = "add-job",
            description = """
                    在 XXL-JOB 调度平台中新增一个定时任务（Job）。

                    适用场景:
                    - 用户说"帮我创建一个定时任务"
                    - 为某个 JobHandler 配置 Cron 调度

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，控制台地址从环境目录解析
                    - loginIdentity: 登录态 Cookie payload（必填），由 xxl-job-login 获取
                    - jobGroup: 执行器 ID（必填），先通过 select-all-executor 查询，如 9
                    - jobDesc: 任务描述（必填），如 测试任务
                    - executorHandler: JobHandler 名称（必填），需与执行器中 @XxlJob 注解一致，如 tradingAnomalyAgent
                    - scheduleType: 调度类型，CRON/FIX_RATE/FIX_DELAY/NONE（默认 CRON）
                    - scheduleConf: 调度表达式（CRON 类型必填），如 0 0 8 * * ?
                    - author: 作者（选填），如 jinran
                    - executorParam: 任务参数（选填），执行时传给 JobHandler

                    返回: 创建结果（成功/失败及原因）。
                    """
    )
    public String add(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "loginIdentity", description = "登录态 Cookie 值 XXL_JOB_LOGIN_IDENTITY 的 payload（必填）") String loginIdentity,
            @ToolParam(name = "jobGroup", description = "执行器 ID（必填），先通过 select-all-executor 查询，如 9") String jobGroup,
            @ToolParam(name = "jobDesc", description = "任务描述（必填），如 测试任务") String jobDesc,
            @ToolParam(name = "executorHandler", description = "JobHandler 名称（必填），需与执行器中 @XxlJob 注解一致，如 tradingAnomalyAgent") String executorHandler,
            @ToolParam(name = "scheduleType", description = "调度类型，CRON/FIX_RATE/FIX_DELAY/NONE，默认 CRON") String scheduleType,
            @ToolParam(name = "scheduleConf", description = "调度表达式（CRON 类型必填），如 0 0 8 * * ?") String scheduleConf,
            @ToolParam(name = "author", description = "作者（选填），如 jinran") String author,
            @ToolParam(name = "executorParam", description = "任务参数（选填），执行时传给 JobHandler") String executorParam
    ) {
        logger.info("add-job called, environment:{}, jobGroup:{}, jobDesc:{}, executorHandler:{}, scheduleType:{}, scheduleConf:{}",
                environment, jobGroup, jobDesc, executorHandler, scheduleType, scheduleConf);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set(HttpHeaders.COOKIE, buildCookie(loginIdentity));
            headers.set("x-requested-with", "XMLHttpRequest");

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("jobGroup", jobGroup == null ? "" : jobGroup.trim());
            body.add("jobDesc", jobDesc == null ? "" : jobDesc.trim());
            body.add("author", author == null ? "" : author.trim());
            body.add("alarmEmail", "");
            body.add("scheduleType", defaultIfBlank(scheduleType, "CRON"));
            body.add("scheduleConf", scheduleConf == null ? "" : scheduleConf.trim());
            body.add("glueType", "BEAN");
            body.add("executorHandler", executorHandler == null ? "" : executorHandler.trim());
            body.add("executorParam", executorParam == null ? "" : executorParam.trim());
            body.add("executorRouteStrategy", "FIRST");
            body.add("childJobId", "");
            body.add("misfireStrategy", "DO_NOTHING");
            body.add("executorBlockStrategy", "SERIAL_EXECUTION");
            body.add("executorTimeout", "0");
            body.add("executorFailRetryCount", "0");

            String url = buildUrl(consoleUrlOf(environment), JOB_INFO_ADD_PATH);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.error("add-job 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return "[add-job] 请求失败, HTTP " + response.getStatusCode()
                        + ", body: " + response.getBody();
            }

            return formatResult(jobDesc, response.getBody());

        } catch (Exception e) {
            logger.error("add-job 异常, jobDesc:{}, error:{}", jobDesc, e.getMessage(), e);
            return "[add-job] 创建异常: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 结果格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatResult(String jobDesc, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int code = root.path("code").asInt(-1);
            if (code == 200) {
                return "[add-job] ✅ 任务创建成功: " + jobDesc;
            }
            String msg = root.path("msg").asText();
            return "[add-job] ❌ 创建失败 (code=" + code + "): "
                    + (msg == null || msg.isBlank() ? responseBody : msg);
        } catch (Exception e) {
            logger.warn("add-job 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }
    }
}