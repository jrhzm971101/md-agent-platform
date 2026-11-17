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
 * XXL-JOB 执行器查询工具 —— 调用 XXL-JOB Admin 控制台的 jobgroup/pageList 接口，
 * 查询已注册的执行器（JobGroup）列表。
 */
@Component
public class SelectAllExecutor extends AbstractXxlJobTool {

    /**
     * XXL-JOB Admin 控制台 jobgroup/pageList 接口相对路径
     */
    private static final String JOB_GROUP_PAGE_LIST_PATH = "/jobgroup/pageList";

    @Tool(
            name = "select-all-executor",
            description = """
                    查询 XXL-JOB 调度平台中已注册的执行器（JobGroup）列表。

                    适用场景:
                    - 用户询问"有哪些执行器"、"执行器列表"
                    - 查询某个定时任务前，先拿到执行器 ID（jobGroup），供 select-job-by-executor 按执行器查任务
                    - 排查执行器的 AppName、注册地址等信息
                    - 对比两个 XXL-JOB 实例（A/B）的执行器差异：分别传入各自的 consoleUrl 和 loginIdentity

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，控制台地址从环境目录解析
                    - loginIdentity: 登录态 Cookie payload（必填），由 xxl-job-login 获取
                    - appname: 执行器 AppName 过滤（选填），如 xxl-job-executor-sample
                    - title: 执行器名称过滤（选填）

                    返回: 执行器列表，包含 id、AppName、名称、注册方式、地址列表等字段。
                    """
    )
    public String query(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat",required = true) String environment,
            @ToolParam(name = "loginIdentity", description = "登录态 Cookie 值 XXL_JOB_LOGIN_IDENTITY 的 payload（必填）",required = true) String loginIdentity,
            @ToolParam(name = "appname", description = "执行器 AppName 过滤（选填），如 xxl-job-executor-sample",required = false) String appname,
            @ToolParam(name = "title", description = "执行器名称过滤（选填）",required = false) String title
    ) {
        logger.info("select-all-executor called, environment:{}, appname:{}, title:{}", environment, appname, title);

        try {
            XxlJobProperties.Page page = xxlJobProperties.getPage();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set(HttpHeaders.COOKIE, buildCookie(loginIdentity));
            headers.set("x-requested-with", "XMLHttpRequest");

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("appname", appname == null ? "" : appname.trim());
            body.add("title", title == null ? "" : title.trim());
            body.add("start", page.getStart());
            body.add("length", page.getLength());

            String url = buildUrl(consoleUrlOf(environment), JOB_GROUP_PAGE_LIST_PATH);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.error("select-all-executor 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return "[select-all-executor] 请求失败, HTTP " + response.getStatusCode()
                        + ", body: " + response.getBody();
            }

            return formatResult(response.getBody());

        } catch (Exception e) {
            logger.error("select-all-executor 异常, appname:{}, error:{}", appname, e.getMessage(), e);
            return "[select-all-executor] 查询异常: " + e.getMessage();
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
            sb.append("[select-all-executor] 共 ").append(total).append(" 个执行器");

            if (!data.isArray() || data.isEmpty()) {
                sb.append("\n(无执行器)");
                return sb.toString();
            }

            sb.append(", 本次返回 ").append(data.size()).append(" 个:\n");
            for (JsonNode group : data) {
                sb.append("\n---\n");
                sb.append("id: ").append(group.path("id").asText()).append("\n");
                sb.append("AppName: ").append(group.path("appname").asText()).append("\n");
                sb.append("名称: ").append(group.path("title").asText()).append("\n");
                sb.append("注册方式: ").append(translateAddressType(group.path("addressType").asInt(-1))).append("\n");
                sb.append("地址列表: ").append(group.path("addressList").asText()).append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            logger.warn("select-all-executor 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }
    }

    private static String translateAddressType(int type) {
        return switch (type) {
            case 0 -> "自动注册";
            case 1 -> "手动录入";
            default -> "未知(" + type + ")";
        };
    }
}