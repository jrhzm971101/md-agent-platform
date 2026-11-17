package com.md.agentplatform.server.tool.xxljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.XxlJobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;

/**
 * XXL-JOB Admin 工具公共基类 —— 封装登录态 Cookie、RestTemplate、ObjectMapper、
 * URL 拼接与参数默认值等公共逻辑，供各 XXL-JOB 工具复用。
 *
 * <p>consoleUrl 按 environment 参数从环境目录解析；loginIdentity 由 xxl-job-login 获取后
 * 经工具参数传入，以支持 Agent 在单次会话中对比多个 XXL-JOB 实例（如 xxl-job A 与 xxl-job B）的差异。
 */
public abstract class AbstractXxlJobTool {

    /** 各子类独立的日志记录器（按运行时类型命名） */
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /** XXL-JOB Admin 登录态 Cookie 名称 */
    protected static final String LOGIN_COOKIE_NAME = "XXL_JOB_LOGIN_IDENTITY";

    @Autowired
    protected XxlJobProperties xxlJobProperties;

    @Autowired
    protected EnvironmentRegistry environmentRegistry;

    protected final RestTemplate restTemplate = new RestTemplate();
    protected final ObjectMapper objectMapper = new ObjectMapper();

    /** 控制台地址：按环境名从环境目录解析 */
    protected String consoleUrlOf(String environment) {
        return environmentRegistry.xxljob(environment).getConsoleUrl();
    }

    /** 组装登录态 Cookie 头（loginIdentity 由 xxl-job-login 获取后经工具参数传入） */
    protected String buildCookie(String loginIdentity) {
        return LOGIN_COOKIE_NAME + "=" + (loginIdentity == null ? "" : loginIdentity.trim());
    }

    /** 拼接接口完整地址，兼容 consoleUrl 末尾带不带斜杠 */
    protected String buildUrl(String consoleUrl, String path) {
        String base = consoleUrl == null ? "" : consoleUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    /** 返回非空参数值，空值回退到默认值 */
    protected static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}