package com.md.agentplatform.server.tool.grafana;

import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.GrafanaProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Grafana 客户端按 environment 解析地址与 Basic Auth。 */
class GrafanaClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/clusters/global/grafana/api/search", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{\"ok\":true}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/clusters/global/grafana/";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void resolvesUrlAndBasicAuthFromEnvironment() {
        EnvironmentProperties.Grafana grafana = new EnvironmentProperties.Grafana();
        grafana.setUrl(baseUrl);
        grafana.setUsername("alice");
        grafana.setPassword("secret");
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.grafana("cameroon-uat")).thenReturn(grafana);

        GrafanaClient client = new GrafanaClient(new GrafanaProperties(), registry);
        client.init();

        assertEquals(true, client.get("cameroon-uat", "/api/search").path("ok").asBoolean());
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, authorization.get());
    }

    @Test
    void treatsMissingPasswordAsEmpty() {
        EnvironmentProperties.Grafana grafana = new EnvironmentProperties.Grafana();
        grafana.setUrl(baseUrl);
        grafana.setUsername("alice");
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.grafana("cameroon-uat")).thenReturn(grafana);

        GrafanaClient client = new GrafanaClient(new GrafanaProperties(), registry);
        client.init();

        client.get("cameroon-uat", "/api/search");

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, authorization.get());
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
