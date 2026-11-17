package com.md.agentplatform.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无状态 MCP 客户端 —— 只发 tools/list 与 tools/call，不做 initialize 握手。
 *
 * <p>贝壳 https://building.ke.com/mcp 不实现标准初始化：任意参数的 initialize 都返回
 * -32602 Invalid request parameters，但它支持 tools/list 与 tools/call，官方 CLI
 * 也是直接发这两个方法。官方 SDK 会在首次 listTools 前隐式握手，因此无法直连这类网关，
 * 只能走本类的无状态路径：不协商协议版本、不维护 session，每次请求都是独立 JSON-RPC。
 *
 * <p>鉴权头来自 md_mcp_server.headers，贝壳需要 Authorization: Bearer &lt;KEY&gt;。
 * 响应既可能是 application/json，也可能是 SSE（贝壳返回 text/event-stream），两种都支持。
 */
public class StatelessHttpMcpClient extends McpClientWrapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient http;
    private final McpJsonMapper mcpJson = McpJsonMapper.getDefault();
    private final AtomicLong ids = new AtomicLong();

    public StatelessHttpMcpClient(String name, String url, Map<String, String> headers, Duration timeout) {
        super(name);
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 「握手」= 真正打一次 tools/list：无状态网关没有初始化语义，但装配期仍需要
     * 一次会失败的调用，否则 Key 错误要到 agent 运行时才暴露。
     */
    @Override
    public Mono<Void> initialize() {
        return Mono.defer(() -> {
            if (initialized) {
                return Mono.empty();
            }
            return Mono.fromCallable(() -> {
                        cacheTools(fetchTools());
                        initialized = true;
                        return Boolean.TRUE;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .then();
        });
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return Mono.fromCallable(() -> {
                    List<McpSchema.Tool> tools = fetchTools();
                    cacheTools(tools);
                    initialized = true;
                    return tools;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        return Mono.fromCallable(() -> {
                    ObjectNode params = JSON.createObjectNode();
                    params.put("name", toolName);
                    params.set("arguments", JSON.valueToTree(arguments == null ? Map.of() : arguments));
                    if (meta != null && !meta.isEmpty()) {
                        params.set("_meta", JSON.valueToTree(meta));
                    }
                    JsonNode result = rpc("tools/call", params);
                    return mcpJson.readValue(JSON.writeValueAsString(result), McpSchema.CallToolResult.class);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public void close() {
        initialized = false;
        cachedTools.clear();
    }

    // ==================== 协议细节 ====================

    private List<McpSchema.Tool> fetchTools() throws IOException, InterruptedException {
        JsonNode result = rpc("tools/list", JSON.createObjectNode());
        McpSchema.ListToolsResult parsed =
                mcpJson.readValue(JSON.writeValueAsString(result), McpSchema.ListToolsResult.class);
        return parsed.tools() == null ? List.of() : parsed.tools();
    }

    private void cacheTools(List<McpSchema.Tool> tools) {
        cachedTools.clear();
        for (McpSchema.Tool tool : tools) {
            if (tool.name() != null) {
                cachedTools.put(tool.name(), tool);
            }
        }
    }

    /** 发一条 JSON-RPC，返回 result 节点；协议错误转成带上下文的异常。 */
    private JsonNode rpc(String method, ObjectNode params) throws IOException, InterruptedException {
        long id = ids.incrementAndGet();
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.set("params", params == null ? JSON.createObjectNode() : params);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        headers.forEach(request::header);

        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        String contentType = response.headers().firstValue("Content-Type").orElse("");

        JsonNode message;
        try (InputStream in = response.body()) {
            message = contentType.contains("text/event-stream") ? readSse(in, id) : readJson(in);
        }
        if (status >= 400) {
            throw new IOException("MCP " + name + " " + method + " 返回 HTTP " + status + ": " + message);
        }

        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException("MCP " + name + " " + method + " 失败: ["
                    + error.path("code").asText("") + "] "
                    + error.path("message").asText(error.toString()));
        }
        JsonNode result = message.get("result");
        if (result == null || result.isNull()) {
            throw new IllegalStateException("MCP " + name + " " + method + " 响应缺少 result: " + message);
        }
        return result;
    }

    private static JsonNode readJson(InputStream in) throws IOException {
        return JSON.readTree(in.readAllBytes());
    }

    /** 读 SSE：按空行切事件，只认与本请求 id 相同的那条响应（其余是通知）。 */
    private JsonNode readSse(InputStream in, long id) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                JsonNode matched = matchEvent(data, id);
                if (matched != null) {
                    return matched;
                }
                data.setLength(0);
                continue;
            }
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            }
        }
        JsonNode matched = matchEvent(data, id);
        if (matched != null) {
            return matched;
        }
        throw new IllegalStateException("MCP " + name + " SSE 响应中没有 id=" + id + " 的 JSON-RPC 结果");
    }

    private JsonNode matchEvent(StringBuilder data, long id) throws IOException {
        if (data.length() == 0) {
            return null;
        }
        JsonNode node = JSON.readTree(data.toString());
        if (!node.hasNonNull("id")) {
            return null;
        }
        return node.get("id").asLong() == id ? node : null;
    }
}
