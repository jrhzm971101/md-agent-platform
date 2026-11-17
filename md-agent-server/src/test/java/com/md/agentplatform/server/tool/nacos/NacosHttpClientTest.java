package com.md.agentplatform.server.tool.nacos;

import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nacos OpenAPI 客户端测试 —— 用 JDK 自带 HttpServer 起本地桩，
 * 验证「登录换 token、GET 读配置、配置列表翻页、POST 发布配置、token 缓存、地址规范化」。
 */
class NacosHttpClientTest {

    private HttpServer server;
    private String baseUrl;

    private final AtomicInteger loginCount = new AtomicInteger();
    private final AtomicReference<CapturedRequest> loginRequest = new AtomicReference<>();
    private final AtomicReference<CapturedRequest> getRequest = new AtomicReference<>();
    private final AtomicReference<CapturedRequest> listRequest = new AtomicReference<>();
    private final AtomicReference<CapturedRequest> publishRequest = new AtomicReference<>();

    private volatile int loginStatus;
    private volatile String loginBody;
    private volatile int getStatus;
    private volatile String getBody;
    private volatile int listStatus;
    private volatile String listPage1Body;
    private volatile String listPage2Body;
    private volatile int publishStatus;
    private volatile String publishBody;

    @BeforeEach
    void setUp() throws IOException {
        loginStatus = 200;
        loginBody = "{\"accessToken\":\"tok-1\",\"tokenTtl\":1800,\"username\":\"nacos\"}";
        getStatus = 200;
        getBody = "server:\n  port: 8080";
        listStatus = 200;
        listPage1Body = "{\"totalCount\":3,\"pageNumber\":1,\"pagesAvailable\":2,\"pageItems\":["
                + "{\"dataId\":\"a\",\"group\":\"DEFAULT_GROUP\",\"type\":\"yaml\",\"appName\":\"app-a\"},"
                + "{\"dataId\":\"b\",\"group\":\"DEFAULT_GROUP\",\"type\":\"properties\",\"appName\":\"\"}]}";
        listPage2Body = "{\"totalCount\":3,\"pageNumber\":2,\"pagesAvailable\":2,\"pageItems\":["
                + "{\"dataId\":\"c\",\"group\":\"DEFAULT_GROUP\",\"type\":\"text\",\"appName\":\"app-c\"}]}";
        publishStatus = 200;
        publishBody = "true";
        loginCount.set(0);
        loginRequest.set(null);
        getRequest.set(null);
        listRequest.set(null);
        publishRequest.set(null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nacos/v1/auth/users/login", exchange -> {
            loginCount.incrementAndGet();
            loginRequest.set(capture(exchange));
            respond(exchange, loginStatus, loginBody);
        });
        server.createContext("/nacos/v1/cs/configs", exchange -> {
            CapturedRequest request = capture(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> params = query(exchange.getRequestURI());
                if (params.containsKey("search")) {
                    listRequest.set(request);
                    boolean firstPage = !"2".equals(params.get("pageNo"));
                    respond(exchange, listStatus, firstPage ? listPage1Body : listPage2Body);
                } else {
                    getRequest.set(request);
                    respond(exchange, getStatus, getBody);
                }
            } else {
                publishRequest.set(request);
                respond(exchange, publishStatus, publishBody);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getConfigLogsInAndSendsQueryAndToken() {
        TestNacosTool tool = new TestNacosTool();

        String content = tool.get(baseUrl, "ns-1", "nacos", "pwd", "demo-data", "DEFAULT_GROUP", 3000);

        assertEquals("server:\n  port: 8080", content);
        assertEquals(1, loginCount.get());
        assertEquals("demo-data", query(getRequest.get().uri()).get("dataId"));
        assertEquals("DEFAULT_GROUP", query(getRequest.get().uri()).get("group"));
        assertEquals("ns-1", query(getRequest.get().uri()).get("tenant"));
        assertEquals("tok-1", getRequest.get().headers().getFirst("accessToken"));
        assertEquals("nacos", form(loginRequest.get().body()).get("username"));
        assertEquals("pwd", form(loginRequest.get().body()).get("password"));
    }

    @Test
    void reusesTokenAcrossCalls() {
        TestNacosTool tool = new TestNacosTool();

        tool.get(baseUrl, null, "nacos", "pwd", "demo-data", "DEFAULT_GROUP", 3000);
        tool.get(baseUrl, null, null, null, "demo-data", "DEFAULT_GROUP", 3000);

        assertEquals(1, loginCount.get());
    }

    @Test
    void returnsNullWhenConfigMissing() {
        getStatus = 404;
        getBody = "";
        TestNacosTool tool = new TestNacosTool();

        assertNull(tool.get(baseUrl, null, "nacos", "pwd", "missing", "DEFAULT_GROUP", 3000));
    }

    @Test
    void publishConfigSendsFormAndToken() {
        TestNacosTool tool = new TestNacosTool();

        boolean ok = tool.publish(baseUrl, "ns-1", "nacos", "pwd",
                "demo-data", "DEFAULT_GROUP", "a: b\nc: d", "yaml", 3000);

        assertTrue(ok);
        Map<String, String> form = form(publishRequest.get().body());
        assertEquals("demo-data", form.get("dataId"));
        assertEquals("DEFAULT_GROUP", form.get("group"));
        assertEquals("a: b\nc: d", form.get("content"));
        assertEquals("yaml", form.get("type"));
        assertEquals("ns-1", form.get("tenant"));
        assertEquals("tok-1", publishRequest.get().headers().getFirst("accessToken"));
    }

    @Test
    void publishConfigReturnsFalseWhenServerRejects() {
        publishBody = "false";
        TestNacosTool tool = new TestNacosTool();

        assertFalse(tool.publish(baseUrl, null, "nacos", "pwd",
                "demo-data", "DEFAULT_GROUP", "a: b", "yaml", 3000));
    }

    @Test
    void continuesWithoutTokenWhenAuthIsDisabled() {
        loginStatus = 403;
        loginBody = "auth disabled";
        TestNacosTool tool = new TestNacosTool();

        String content = tool.get(baseUrl, null, "nacos", "pwd", "demo-data", "DEFAULT_GROUP", 3000);

        assertEquals("server:\n  port: 8080", content);
        assertNull(getRequest.get().headers().getFirst("accessToken"));
    }

    @Test
    void normalizesEnvironmentServerAddr() {
        assertEquals("https://cmr-uat-nacos.mindigitaltech.com:443/nacos",
                AbstractNacosTool.normalizeBaseUrl("cmr-uat-nacos.mindigitaltech.com:443"));
        assertEquals("https://nacos-test.mindigitaltech.com/nacos",
                AbstractNacosTool.normalizeBaseUrl("https://nacos-test.mindigitaltech.com/"));
        assertEquals("https://nacos-test.mindigitaltech.com/nacos",
                AbstractNacosTool.normalizeBaseUrl("https://nacos-test.mindigitaltech.com/nacos/"));
        assertEquals("http://127.0.0.1:8848/nacos",
                AbstractNacosTool.normalizeBaseUrl("http://127.0.0.1:8848"));
        assertEquals("https://host/custom-context",
                AbstractNacosTool.normalizeBaseUrl("https://host/custom-context"));
        assertThrows(IllegalArgumentException.class, () -> AbstractNacosTool.normalizeBaseUrl(" "));
    }

    @Test
    void usesEnvironmentNamespace() throws Exception {
        GetSingleConfig tool = new GetSingleConfig();
        injectRegistry(tool, "env", "env-ns");

        String result = tool.get("env", "demo-data", "DEFAULT_GROUP", "3000");

        assertTrue(result.contains("server:"));
        assertEquals("env-ns", query(getRequest.get().uri()).get("tenant"));
    }

    @Test
    void listConfigsAggregatesAllPages() {
        TestNacosTool tool = new TestNacosTool();

        AbstractNacosTool.ConfigList result = tool.list(baseUrl, "ns-1", "nacos", "pwd", null, null, 3000);

        assertEquals(3, result.totalCount());
        assertEquals(List.of("a", "b", "c"),
                result.items().stream().map(AbstractNacosTool.ConfigItem::dataId).toList());
        assertFalse(result.truncated());
        Map<String, String> lastQuery = query(listRequest.get().uri());
        assertEquals("2", lastQuery.get("pageNo"));
        assertEquals("ns-1", lastQuery.get("tenant"));
        assertEquals("accurate", lastQuery.get("search"));
    }

    @Test
    void listConfigsUsesBlurWhenFiltersProvided() {
        TestNacosTool tool = new TestNacosTool();

        tool.list(baseUrl, null, "nacos", "pwd", "tenant", "DEFAULT_GROUP", 3000);

        Map<String, String> lastQuery = query(listRequest.get().uri());
        assertEquals("blur", lastQuery.get("search"));
        assertEquals("tenant", lastQuery.get("dataId"));
        assertEquals("DEFAULT_GROUP", lastQuery.get("group"));
    }

    @Test
    void configListToolUsesEnvironmentNamespace() throws Exception {
        GetConfigList tool = new GetConfigList();
        injectRegistry(tool, "env", "env-ns");

        String result = tool.list("env", null, null, "3000");

        assertTrue(result.contains("共 3 个配置"));
        assertTrue(result.contains("- a  [DEFAULT_GROUP]  type=yaml  app=app-a"));
        assertEquals("env-ns", query(listRequest.get().uri()).get("tenant"));
    }

    private void injectRegistry(AbstractNacosTool tool, String environment, String namespace) throws Exception {
        EnvironmentProperties.Nacos nacos = new EnvironmentProperties.Nacos();
        nacos.setServerAddr(baseUrl);
        nacos.setNamespace(namespace);
        nacos.setUsername("nacos");
        nacos.setPassword("pwd");
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.nacos(environment)).thenReturn(nacos);
        Field field = AbstractNacosTool.class.getDeclaredField("environmentRegistry");
        field.setAccessible(true);
        field.set(tool, registry);
    }

    private static CapturedRequest capture(HttpExchange exchange) throws IOException {
        return new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getRequestHeaders(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(URI uri) {
        return parse(uri.getRawQuery());
    }

    private static Map<String, String> form(String body) {
        return parse(body);
    }

    private static Map<String, String> parse(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int idx = pair.indexOf('=');
            String name = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            values.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private record CapturedRequest(String method, URI uri, Headers headers, String body) {
    }

    private static final class TestNacosTool extends AbstractNacosTool {
        String get(String serverAddr, String namespace, String username, String password,
                   String dataId, String group, long timeoutMs) {
            return getConfig(serverAddr, namespace, username, password, dataId, group, timeoutMs);
        }

        boolean publish(String serverAddr, String namespace, String username, String password,
                        String dataId, String group, String content, String type, long timeoutMs) {
            return publishConfig(serverAddr, namespace, username, password, dataId, group, content, type, timeoutMs);
        }

        ConfigList list(String serverAddr, String namespace, String username, String password,
                        String dataId, String group, long timeoutMs) {
            return listConfigs(serverAddr, namespace, username, password, dataId, group, timeoutMs);
        }
    }
}
