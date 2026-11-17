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
 * XXL-JOB 执行器新增工具 —— 调用 XXL-JOB Admin 控制台的 jobgroup/save 接口，
 * 创建新的执行器（JobGroup）。
 */
@Component
public class AddExecutor extends AbstractXxlJobTool {

    /**
     * XXL-JOB Admin 控制台 jobgroup/save 接口相对路径
     */
    private static final String JOB_GROUP_SAVE_PATH = "/jobgroup/save";

    @Tool(
            name = "add-executor",
            description = """
                    在 XXL-JOB 调度平台中新增一个执行器（JobGroup）。

                    适用场景:
                    - 用户说"帮我创建一个执行器"
                    - 为新接入的服务注册 XXL-JOB 执行器

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，控制台地址从环境目录解析
                    - loginIdentity: 登录态 Cookie payload（必填），由 xxl-job-login 获取
                    - appname: 执行器 AppName（必填），需与执行器服务配置的 appname 一致，如 test-executor
                    - title: 执行器名称（必填），如 测试执行器
                    - addressType: 注册方式，0=自动注册、1=手动录入（默认 1）
                    - addressList: 执行器地址列表（手动录入时必填），多个地址逗号分隔，如 http://localhost:9999/jinran

                    返回: 创建结果（成功/失败及原因）。
                    """
    )
    public String add(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "loginIdentity", description = "登录态 Cookie 值 XXL_JOB_LOGIN_IDENTITY 的 payload（必填）") String loginIdentity,
            @ToolParam(name = "appname", description = "执行器 AppName（必填），如 test-executor") String appname,
            @ToolParam(name = "title", description = "执行器名称（必填），如 测试执行器") String title,
            @ToolParam(name = "addressType", description = "注册方式，0=自动注册、1=手动录入，默认 1") String addressType,
            @ToolParam(name = "addressList", description = "执行器地址列表（手动录入时必填），多个地址逗号分隔，如 http://localhost:9999/jinran") String addressList
    ) {
        logger.info("add-executor called, environment:{}, appname:{}, title:{}, addressType:{}, addressList:{}",
                environment, appname, title, addressType, addressList);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set(HttpHeaders.COOKIE, buildCookie(loginIdentity));
            headers.set("x-requested-with", "XMLHttpRequest");

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("appname", appname == null ? "" : appname.trim());
            body.add("title", title == null ? "" : title.trim());
            body.add("addressType", defaultIfBlank(addressType, "1"));
            body.add("addressList", addressList == null ? "" : addressList.trim());

            String url = buildUrl(consoleUrlOf(environment), JOB_GROUP_SAVE_PATH);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.error("add-executor 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return "[add-executor] 请求失败, HTTP " + response.getStatusCode()
                        + ", body: " + response.getBody();
            }

            return formatResult(appname, title, response.getBody());

        } catch (Exception e) {
            logger.error("add-executor 异常, appname:{}, error:{}", appname, e.getMessage(), e);
            return "[add-executor] 创建异常: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 结果格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatResult(String appname, String title, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int code = root.path("code").asInt(-1);
            if (code == 200) {
                return "[add-executor] ✅ 执行器创建成功: " + title + " (appname: " + appname + ")";
            }
            String msg = root.path("msg").asText();
            return "[add-executor] ❌ 创建失败 (code=" + code + "): "
                    + (msg == null || msg.isBlank() ? responseBody : msg);
        } catch (Exception e) {
            logger.warn("add-executor 解析响应失败，返回原始 JSON, error:{}", e.getMessage());
            return responseBody;
        }
    }
}