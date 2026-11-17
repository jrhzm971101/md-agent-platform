package com.md.agentplatform.server.tool.ninedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.NineDataProperties;
import com.md.agentplatform.server.util.JsonUtil;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NineData OpenAPI 客户端测试 —— 用 JDK 自带 HttpServer 起一个本地桩，
 * 完整验证「签名头、query 编码、JSON 请求体、错误体解析」四件事。
 *
 * <p>不引入 MockWebServer：项目测试依赖里没有它，为了一个测试再加一个网络库不划算；
 * {@code com.sun.net.httpserver} 在 JDK 里就有，足够覆盖到真实 HTTP 层。
 */
class NineDataClientTest {

    private static final String AK = "ak-test";
    private static final String SK = "sk-test";

    private HttpServer server;
    private NineDataProperties properties;
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile int failFirstN;
    private volatile int failStatus = 500;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "{\"success\":true,\"requestId\":\"req-default\"}";
        captured.set(null);
        requestCount.set(0);
        failFirstN = 0;
        failStatus = 500;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            captured.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    exchange.getRequestHeaders(),
                    new String(requestBody, StandardCharsets.UTF_8)));
            boolean failing = requestCount.incrementAndGet() <= failFirstN;
            int status = failing ? failStatus : responseStatus;
            String body = failing ? "{\"success\":false,\"message\":\"temporary failure\"}" : responseBody;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        properties = new NineDataProperties();
        properties.setTimeoutMs(3000);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================== GET /datasource/list ====================

    @Test
    void listDatasourcesSendsSignedGetWithEncodedQuery() {
        responseBody = "{\"success\":true,\"requestId\":\"req-list\",\"data\":{\"list\":[]}}";

        JsonNode response = new NineDataClient(properties)
                .listDatasources(target(), "MySQL", "订单 test", 2, 10);

        assertEquals("req-list", response.path("requestId").asText());

        CapturedRequest request = captured.get();
        assertEquals("GET", request.method());
        assertEquals("/openapi/v1/datasource/list", request.uri().getPath());

        String decodedQuery = URLDecoder.decode(
                request.uri().getRawQuery() == null ? "" : request.uri().getRawQuery(),
                StandardCharsets.UTF_8);
        assertTrue(decodedQuery.contains("datasourceType=MySQL"));
        assertTrue(decodedQuery.contains("keyword=订单 test"), "中文与空格必须正确编码: " + decodedQuery);
        assertTrue(decodedQuery.contains("current=2"));
        assertTrue(decodedQuery.contains("pageSize=10"));

        assertEquals(AK, request.headers().getFirst("access-key-id"));
        String timestamp = request.headers().getFirst("timestamp");
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
                "timestamp 必须是 UTC 字面量 Z: " + timestamp);
        assertEquals(NineDataSigner.sign("/openapi/v1/datasource/list", SK, timestamp),
                request.headers().getFirst("signature"));
    }

    @Test
    void listDatasourcesOmitsBlankFilters() {
        responseBody = "{\"success\":true}";

        new NineDataClient(properties).listDatasources(target(), null, "  ", null, null);

        String rawQuery = captured.get().uri().getRawQuery();
        assertTrue(rawQuery == null || rawQuery.isEmpty(),
                "未传过滤条件时不应拼出空参数: " + rawQuery);
    }

    @Test
    void explicitTargetControlsEndpointAndCredentials() {
        responseBody = "{\"success\":true}";
        NineDataTarget target = new NineDataTarget(
                "http://127.0.0.1:" + server.getAddress().getPort(), "ak-env", "sk-env");

        new NineDataClient(properties).listDatasources(target, null, null, null, null);

        CapturedRequest request = captured.get();
        assertEquals("/openapi/v1/datasource/list", request.uri().getPath());
        assertEquals("ak-env", request.headers().getFirst("access-key-id"));
        assertEquals(NineDataSigner.sign("/openapi/v1/datasource/list", "sk-env",
                        request.headers().getFirst("timestamp")),
                request.headers().getFirst("signature"));
    }

    // ==================== POST /sql/execute ====================

    @Test
    void executeSqlPostsJsonBodyWithSignedHeaders() {
        responseBody = "{\"success\":true,\"requestId\":\"req-exec\",\"data\":{\"decision\":\"EXECUTED\"}}";
        ObjectNode body = JsonUtil.newObject()
                .put("datasourceId", "ds_1")
                .put("databaseName", "app_db")
                .put("sql", "select 1");

        JsonNode response = new NineDataClient(properties).executeSql(target(), body);

        assertEquals("EXECUTED", response.path("data").path("decision").asText());

        CapturedRequest request = captured.get();
        assertEquals("POST", request.method());
        assertEquals("/openapi/v1/sql/execute", request.uri().getPath());
        assertTrue(request.headers().getFirst("content-type").startsWith("application/json"));

        JsonNode sent = JsonUtil.parse(request.body());
        assertEquals("ds_1", sent.path("datasourceId").asText());
        assertEquals("app_db", sent.path("databaseName").asText());
        assertEquals("select 1", sent.path("sql").asText());

        assertEquals(NineDataSigner.sign("/openapi/v1/sql/execute", SK, request.headers().getFirst("timestamp")),
                request.headers().getFirst("signature"));
    }

    // ==================== 错误处理 ====================

    @Test
    void httpErrorCarriesStatusRequestIdAndPlatformMessage() {
        responseStatus = 403;
        responseBody = "{\"success\":false,\"requestId\":\"req-denied\",\"message\":\"no permission\"}";

        NineDataException ex = assertThrows(NineDataException.class,
                () -> new NineDataClient(properties).executeSql(target(), JsonUtil.newObject()));

        assertEquals(403, ex.getHttpStatus());
        assertEquals("req-denied", ex.getRequestId());
        assertTrue(ex.getMessage().contains("no permission"), "必须透出 NineData 的平台错误文案");
        assertTrue(ex.getResponseBody().contains("req-denied"));
    }

    @Test
    void nonJsonResponseIsReportedAsOpenApiError() {
        responseBody = "<html>502 Bad Gateway</html>";

        NineDataException ex = assertThrows(NineDataException.class,
                () -> new NineDataClient(properties).listDatasources(target(), null, null, null, null));

        assertTrue(ex.getMessage().contains("非 JSON"), ex.getMessage());
        assertTrue(ex.getResponseBody().contains("Bad Gateway"));
    }

    @Test
    void emptyResponseIsReportedAsOpenApiError() {
        responseBody = "";

        NineDataException ex = assertThrows(NineDataException.class,
                () -> new NineDataClient(properties).listDatasources(target(), null, null, null, null));

        assertTrue(ex.getMessage().contains("空响应"), ex.getMessage());
    }

    // ==================== 重试 ====================

    @Test
    void retriesTransientServerErrorsWithBackoff() {
        failFirstN = 2;
        properties.setMaxRetries(3);
        properties.setRetryBackoffMs(1);
        responseBody = "{\"success\":true,\"requestId\":\"req-after-retry\"}";

        JsonNode response = new NineDataClient(properties).listDatasources(target(), null, null, null, null);

        assertEquals("req-after-retry", response.path("requestId").asText());
        assertEquals(3, requestCount.get(), "两次 5xx 后第三次应成功");
    }

    @Test
    void doesNotRetryClientErrors() {
        failFirstN = 1;
        failStatus = 400;
        properties.setMaxRetries(3);
        properties.setRetryBackoffMs(1);

        NineDataException ex = assertThrows(NineDataException.class,
                () -> new NineDataClient(properties).listDatasources(target(), null, null, null, null));

        assertEquals(400, ex.getHttpStatus());
        assertEquals(1, requestCount.get(), "4xx 是请求本身的问题，不应重试");
    }

    @Test
    void stopsAfterMaxRetries() {
        failFirstN = 10;
        failStatus = 503;
        properties.setMaxRetries(2);
        properties.setRetryBackoffMs(1);

        NineDataException ex = assertThrows(NineDataException.class,
                () -> new NineDataClient(properties).listDatasources(target(), null, null, null, null));

        assertEquals(503, ex.getHttpStatus());
        assertEquals(3, requestCount.get(), "首次请求 + 2 次重试");
    }

    // ==================== 测试夹具 ====================

    private NineDataTarget target() {
        return new NineDataTarget("http://127.0.0.1:" + server.getAddress().getPort(), AK, SK);
    }

    private record CapturedRequest(String method, URI uri, Headers headers, String body) {
    }
}
