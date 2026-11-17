package com.md.agentplatform.server.tool.magic;

import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Magic API 数据查询工具 —— 调用远程 Magic API 执行 SQL 查询并返回结果。
 *
 * <p>Magic API 网关地址通过 {@code environment} 参数从环境目录解析，不硬编码。
 */
@Component
public class MagicApiDataSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(MagicApiDataSearchTool.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private EnvironmentRegistry environmentRegistry;

    @Tool(
            name = "magic-api-data-search",
            description = """
                    通过 Magic API 网关对指定数据源执行 SQL 查询，返回查询结果。

                    参数说明：
                    - environment: 环境名（必填），如 cameroon-uat，Magic API 地址从环境目录解析
                    - dataSource: Magic API 中注册的数据源名称，如 lms_mysql_uat
                    - sql: 要执行的 SQL 查询语句（仅支持 SELECT）

                    返回：原始 JSON 响应字符串。
                    """
    )
    public String search(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "dataSource", description = "数据源名称，对应 Magic API 中注册的数据源，如 lms_mysql_uat") String dataSource,
            @ToolParam(name = "sql", description = "要执行的 SQL SELECT 查询语句") String sql
    ) {
        logger.info("magic-api-data-search called, environment:{}, dataSource: {}, sql: {}",
                environment, dataSource, sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);

        if (environment == null || environment.isBlank()) {
            return "[magic-api-data-search] 参数缺失: environment 必填";
        }

        EnvironmentProperties.Magic magic;
        try {
            magic = environmentRegistry.magic(environment);
        } catch (IllegalArgumentException e) {
            return "[magic-api-data-search] 参数错误: " + e.getMessage();
        }

        try {
            String url = magic.getUrl();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = new LinkedHashMap<>();
            body.put("dataSource", dataSource);
            body.put("sql", sql);

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.error("magic-api-data-search 失败, dataSource: {}, httpStatus: {}, body: {}",
                        dataSource, response.getStatusCode(), response.getBody());
                return "[magic-api-data-search] 请求失败, HTTP " + response.getStatusCode()
                        + ", body: " + response.getBody();
            }

            String responseBody = response.getBody();
            return responseBody != null ? responseBody : "(空响应)";

        } catch (Exception e) {
            logger.error("magic-api-data-search 异常, environment:{}, dataSource: {}, error: {}",
                    environment, dataSource, e.getMessage(), e);
            return "[magic-api-data-search] 查询异常: " + e.getMessage();
        }
    }
}
