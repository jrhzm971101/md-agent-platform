package com.md.agentplatform.server.tool.nacos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.ConfluenceProperties;
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
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Confluence Wiki 页面查询工具 —— 按页面 ID 查询页面内容，返回去除 HTML 标签后的纯文本。
 */
@Component
public class GetWikiPage {

    private static final Logger logger = LoggerFactory.getLogger(GetWikiPage.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ConfluenceProperties confluenceProperties;

    @Tool(
            name = "get-wiki-page",
            description = """
                    查询 Confluence Wiki 页面内容（按页面 ID），返回去除 HTML 标签后的纯文本。

                    参数说明:
                    - pageId: Confluence 页面 ID（必填），如 90398855

                    返回: 页面标题 + 纯文本正文。
                    """
    )
    public String getWikiPage(
            @ToolParam(name = "pageId", description = "Confluence 页面 ID（必填），如 90398855") String pageId
    ) {
        logger.info("get-wiki-page called, pageId:{}", pageId);

        if (pageId == null || pageId.isBlank()) {
            return "[get-wiki-page] 参数缺失: pageId 必填";
        }

        try {
            String baseUrl = trimTrailingSlash(confluenceProperties.getBaseUrl());
            String url = baseUrl + "/rest/api/content/" + pageId.trim() + "?expand=body.view";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            headers.set(HttpHeaders.AUTHORIZATION, basicAuth());

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.error("get-wiki-page 失败, httpStatus:{}, body:{}",
                        response.getStatusCode(), response.getBody());
                return "[get-wiki-page] 请求失败, HTTP " + response.getStatusCode().value();
            }

            return formatResult(response.getBody(), pageId.trim());

        } catch (Exception e) {
            logger.error("get-wiki-page 异常, pageId:{}, error:{}", pageId, e.getMessage(), e);
            return "[get-wiki-page] 查询失败: " + e.getMessage();
        }
    }

    private String basicAuth() {
        String username = confluenceProperties.getUsername() == null ? "" : confluenceProperties.getUsername();
        String password = confluenceProperties.getPassword() == null ? "" : confluenceProperties.getPassword();
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private String formatResult(String responseBody, String pageId) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        String title = root.path("title").asText("");
        String html = root.path("body").path("view").path("value").asText("");

        if (html.isBlank()) {
            return "[get-wiki-page] 页面 " + pageId + "（" + title + "）无正文内容";
        }

        String text = stripHtml(html);
        return "[get-wiki-page] 页面标题: " + title + "\n页面 ID: " + pageId + "\n\n" + text;
    }

    /** 去掉 HTML 标签 / script / style，反转义实体，压缩空白，得到可读纯文本 */
    private String stripHtml(String html) {
        // 1. 去掉 script/style 整块内容
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        // 2. 常见块级标签转成换行，保留段落结构
        s = s.replaceAll("(?i)<(br\\s*/?>|/p>|/div>|/li>|/tr>|/h[1-6]>)", "\n");
        // 3. 去掉剩余所有标签
        s = s.replaceAll("(?s)<[^>]*>", " ");
        // 4. 反转义 HTML 实体（&amp; &lt; &gt; &nbsp; 等）
        s = HtmlUtils.htmlUnescape(s);
        // 5. 压缩空白与多余空行
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
