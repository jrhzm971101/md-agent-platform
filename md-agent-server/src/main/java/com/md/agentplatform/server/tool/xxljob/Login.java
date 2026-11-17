package com.md.agentplatform.server.tool.xxljob;

import com.md.agentplatform.server.config.EnvironmentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

/**
 * XXL-JOB Admin 登录工具 —— 调用控制台 login 接口，从响应头 Set-Cookie 中提取
 * XXL_JOB_LOGIN_IDENTITY 登录态 payload，供其它 XXL-JOB 工具作为 loginIdentity 复用。
 */
@Component
public class Login extends AbstractXxlJobTool {

    /** XXL-JOB Admin 控制台 login 接口相对路径 */
    private static final String LOGIN_PATH = "/login";

    @Tool(
            name = "xxl-job-login",
            description = """
                    登录 XXL-JOB 调度平台控制台，获取登录态 Cookie（XXL_JOB_LOGIN_IDENTITY）。

                    适用场景:
                    - 其它 XXL-JOB 工具（select-job-by-executor 等）报"未登录/无权限"时，先用本工具重新登录拿新 Cookie
                    - 对比多个 XXL-JOB 实例时，分别登录各自实例获取各自的 loginIdentity

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，控制台地址/账号/密码从环境目录解析

                    返回: 登录结果；成功时返回 Set-Cookie 中的 XXL_JOB_LOGIN_IDENTITY payload，
                    将该值作为 loginIdentity 参数传给其它 XXL-JOB 工具即可。
                    """
    )
    public String login(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment
    ) {
        EnvironmentProperties.XxlJob xxljob;
        try {
            xxljob = environmentRegistry.xxljob(environment);
        } catch (IllegalArgumentException e) {
            return "[xxl-job-login] 参数错误: " + e.getMessage();
        }

        logger.info("xxl-job-login called, environment:{}, consoleUrl:{}", environment, xxljob.getConsoleUrl());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("x-requested-with", "XMLHttpRequest");

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("userName", xxljob.getUsername() == null ? "" : xxljob.getUsername().trim());
            body.add("password", xxljob.getPassword() == null ? "" : xxljob.getPassword());

            String url = buildUrl(xxljob.getConsoleUrl(), LOGIN_PATH);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.error("xxl-job-login 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return "[xxl-job-login] 请求失败, HTTP " + response.getStatusCode()
                        + ", body: " + response.getBody();
            }

            String loginIdentity = extractLoginIdentity(response.getHeaders().get(HttpHeaders.SET_COOKIE));
            if (loginIdentity == null) {
                logger.error("xxl-job-login 未在 Set-Cookie 中找到 {}, body:{}", LOGIN_COOKIE_NAME, response.getBody());
                return "[xxl-job-login] ❌ 登录失败（未返回登录态 Cookie）: " + response.getBody();
            }

            return "[xxl-job-login] ✅ 登录成功, 登录态 payload (loginIdentity):\n" + loginIdentity;

        } catch (Exception e) {
            logger.error("xxl-job-login 异常, environment:{}, error:{}", environment, e.getMessage(), e);
            return "[xxl-job-login] 登录异常: " + e.getMessage();
        }
    }

    /**
     * 从 Set-Cookie 头列表中提取 XXL_JOB_LOGIN_IDENTITY 的 payload。
     * 单个头形如 "XXL_JOB_LOGIN_IDENTITY=xxx; Path=/; HttpOnly"。
     */
    private String extractLoginIdentity(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) {
            return null;
        }
        for (String cookie : setCookies) {
            if (cookie == null) {
                continue;
            }
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && LOGIN_COOKIE_NAME.equals(kv[0].trim())) {
                    return kv[1].trim();
                }
            }
        }
        return null;
    }
}