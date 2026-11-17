package com.md.agentplatform.server.tool.skywalking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.SkyWalkingProperties;
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

/** SkyWalking 客户端按 environment 解析 GraphQL 地址与 Basic Auth。 */
class SkyWalkingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{\"data\":{\"ok\":true}}");
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void resolvesUrlAndBasicAuthFromEnvironment() throws Exception {
        EnvironmentProperties.SkyWalking skywalking = new EnvironmentProperties.SkyWalking();
        skywalking.setUrl(endpoint);
        skywalking.setUsername("alice");
        skywalking.setPassword("secret");
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.skywalking("cameroon-uat")).thenReturn(skywalking);

        SkyWalkingClient client = new SkyWalkingClient(new SkyWalkingProperties(), registry);
        ObjectNode payload = MAPPER.createObjectNode().put("query", "{ listLayers }");

        String response = client.post("cameroon-uat", payload);

        assertEquals(true, MAPPER.readTree(response).path("data").path("ok").asBoolean());
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, authorization.get());
    }

    @Test
    void treatsMissingPasswordAsEmpty() throws Exception {
        EnvironmentProperties.SkyWalking skywalking = new EnvironmentProperties.SkyWalking();
        skywalking.setUrl(endpoint);
        skywalking.setUsername("alice");
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.skywalking("cameroon-uat")).thenReturn(skywalking);

        SkyWalkingClient client = new SkyWalkingClient(new SkyWalkingProperties(), registry);
        client.post("cameroon-uat", MAPPER.createObjectNode().put("query", "{ listLayers }"));

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
