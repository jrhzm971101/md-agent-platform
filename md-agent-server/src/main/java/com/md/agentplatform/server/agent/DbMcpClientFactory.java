package com.md.agentplatform.server.agent;

import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.entity.McpServerEntity;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端工厂 —— 端点、传输协议、鉴权头与工具名单全部来自控制面表 {@code md_mcp_server}。
 *
 * <p>说明：这里原本是「Nacos 版 + DB 版」两份同构实现，Nacos 那份已随 Nacos 数据源整体
 * 删除（见 docs/develop/2026-09-29_nacos-ai-removal.md），现在只剩这一份，也就是唯一路径。
 *
 * <h2>一、两处刻意设计</h2>
 * <ol>
 *   <li><b>enabled 开关</b>：{@code md_mcp_server.enabled=0} 的 MCP 不建链，
 *       引用它的 agent 装配时拿到的是「少一个 MCP」而不是「启动失败」——
 *       停用是运维动作，不该把平台带下去。</li>
 *   <li><b>可失效的缓存</b>：端点由页面改，进程重启不会自动生效，
 *       所以必须提供 {@link #invalidate(String)} ——
 *       否则「页面改了 URL、发布、agent 重建」之后，agent 用的还是老连接。
 *       这一步由发布流程显式调用（见 {@code ControlPlanePublishService}）。</li>
 * </ol>
 *
 * <h2>二、缓存与生命周期的边界</h2>
 * 同名 MCP 被多个 agent 引用时复用同一 client（一个 client = 一次会话）。
 * 因此 client 的关闭权<b>不在</b> agent 手里：agent 重建不能让别人的 MCP 断线。
 * 只有两处会关：{@link #invalidate(String)}（重新建链前）和 {@link #close()}（容器销毁）。
 *
 * <h2>三、stdio 一律拒绝</h2>
 * {@code md_mcp_server} 里保留 command / args / env 三列（字段对齐官方 McpServerConfig），
 * 但平台<b>只支持远端 MCP</b>：stdio 要在本机拉起子进程，
 * 在「控制面管理 + 多 agent 共享」的模型下等于把宿主机 shell 权限开放给配置页面。
 * 配了就明确报错，不做静默降级。
 */
@Component
public class DbMcpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DbMcpClientFactory.class);

    /** 建链 + 首轮 listTools 的默认超时；未配 {@code timeout_ms} 时用它。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** mcpName → 连接与名单。装配期并发建 agent 时按名去重，避免同名 MCP 重复建链。 */
    private final Map<String, McpServerBinding> bindings = new ConcurrentHashMap<>();

    @Autowired
    private ControlPlaneStore store;

    /**
     * 按名取（或首次构建）MCP 绑定。
     *
     * @throws IllegalStateException 名字为空、库里没有该 MCP、已停用、transport 非法或建链失败
     */
    public McpServerBinding get(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("MCP 名不能为空");
        }
        String name = serverName.trim();
        return bindings.computeIfAbsent(name, this::create);
    }

    /**
     * 批量解析 MCP 名列表。单个失败只告警不中断 —— 与技能名同理，
     * 一个写错的名字不该拖垮整个 agent（乃至平台启动），失败项在日志里明确报出。
     */
    public List<McpServerBinding> resolveAll(List<String> mcpNames) {
        List<McpServerBinding> result = new ArrayList<>();
        if (mcpNames == null || mcpNames.isEmpty()) {
            return result;
        }
        for (String mcpName : mcpNames) {
            try {
                result.add(get(mcpName));
            } catch (Exception e) {
                log.error("[MCP] {} 挂载失败，已跳过: {}", mcpName, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 丢掉某个 MCP 的连接缓存（下一次 {@link #get} 会重新建链并重新握手）。
     *
     * <p>MCP 定义发布后必须调用：agent 重建只换 agent 实例，不会重建 MCP 连接，
     * 不失效的话「页面改了端点」这件事永远不生效。
     */
    public void invalidate(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return;
        }
        McpServerBinding removed = bindings.remove(serverName.trim());
        if (removed == null) {
            return;
        }
        try {
            removed.client().close();
        } catch (Exception e) {
            log.warn("[MCP] 关闭旧连接失败: {} — {}", serverName, e.getMessage());
        }
        log.info("[MCP] 已失效连接缓存，下次装配将重新建链: {}", serverName);
    }

    // ==================== 构建 ====================

    private McpServerBinding create(String name) {
        McpServerEntity row = store.findMcpServer(name);
        if (row == null) {
            throw new IllegalStateException("md_mcp_server 里不存在 MCP: " + name);
        }
        if (Boolean.FALSE.equals(row.getEnabled())) {
            throw new IllegalStateException("MCP " + name + " 已停用（enabled=0）");
        }
        String transport = normalizeTransport(row.getTransport());
        if ("stdio".equals(transport)) {
            throw new IllegalStateException(
                    "MCP " + name + " 是 stdio 类型（本地进程），平台只支持远端 MCP");
        }
        String url = row.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("MCP " + name + " 没有配置 url（transport=" + transport + "）");
        }

        Duration timeout = row.getTimeoutMs() != null && row.getTimeoutMs() > 0
                ? Duration.ofMillis(row.getTimeoutMs()) : DEFAULT_TIMEOUT;
        McpClientBuilder builder = McpClientBuilder.create(name).timeout(timeout);
        if (row.getInitTimeoutMs() != null && row.getInitTimeoutMs() > 0) {
            builder.initializationTimeout(Duration.ofMillis(row.getInitTimeoutMs()));
        }
        Map<String, String> headers = nonNullMap(row.getHeaders());
        if (!headers.isEmpty()) {
            builder.headers(headers);
        }
        Map<String, String> queryParams = nonNullMap(row.getQueryParams());
        if (!queryParams.isEmpty()) {
            builder.queryParams(queryParams);
        }
        McpClientWrapper client;
        if ("stateless-http".equals(transport)) {
            // 无状态网关（贝壳 building.ke.com/mcp）不实现 initialize：
            // 官方 SDK 会在首次 listTools 前隐式握手并失败，只能走这条直连路径。
            client = new StatelessHttpMcpClient(name, url.trim(), headers, timeout);
        } else {
            if ("sse".equals(transport)) {
                builder.sseTransport(url.trim());
            } else {
                builder.streamableHttpTransport(url.trim());
            }
            client = builder.buildSync();
        }
        try {
            // 先握手：只有真正连上的 client 才进缓存，避免坏 client 被后续 agent 复用
            client.initialize().block(timeout);
        } catch (Exception e) {
            client.close();
            throw new IllegalStateException("MCP " + name + " 握手失败: " + e.getMessage(), e);
        }
        log.info("[MCP] {} 已建链: {} (transport={}, headers={}, enableTools={}, disableTools={})",
                name, url, transport, headers.keySet(), row.getEnableTools(), row.getDisableTools());
        return new McpServerBinding(name, client, row.getEnableTools(), row.getDisableTools());
    }

    /**
     * transport 归一：库里存 {@code sse} / {@code streamable-http} /
     * {@code stateless-http} / {@code stdio} 四种。
     * 兼容历史写法（Nacos 的 {@code mcp-streamable}、{@code http}、大小写混写），
     * 默认按 streamable-http 处理 —— 这是当下绝大多数远端 MCP 的形态。
     */
    static String normalizeTransport(String raw) {
        if (raw == null || raw.isBlank()) {
            return "streamable-http";
        }
        String t = raw.trim().toLowerCase();
        if (t.contains("stdio")) {
            return "stdio";
        }
        if (t.contains("stateless")) {
            return "stateless-http";
        }
        if (t.contains("sse")) {
            return "sse";
        }
        return "streamable-http";
    }

    private static Map<String, String> nonNullMap(Map<String, String> src) {
        return src == null ? new LinkedHashMap<>() : src;
    }

    /** 容器销毁时统一断开所有 MCP 会话。 */
    @PreDestroy
    public void close() {
        bindings.forEach((name, binding) -> {
            try {
                binding.client().close();
            } catch (Exception e) {
                log.warn("[MCP] 关闭 {} 失败: {}", name, e.getMessage());
            }
        });
        bindings.clear();
    }
}
