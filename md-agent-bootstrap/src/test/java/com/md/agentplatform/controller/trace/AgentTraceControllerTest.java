package com.md.agentplatform.controller.trace;

// 测试与被测控制器同包同模块：控制器搬到 bootstrap，它的契约测试就一起搬，
// 谁改了控制器却忘了改测试，在模块边界上直接可见。
// 迁出 server 后这些被引用类型不再同包，必须显式 import。
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.AgentPlatformProperties;
import com.md.agentplatform.server.trace.AgentRunEvent;
import com.md.agentplatform.server.trace.AgentRunEventTranslator;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.trace.stream.TraceEventStream;
import com.md.agentplatform.server.trace.stream.TraceStreamNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测接口的 HTTP 契约测试。
 *
 * <p>为什么必须有这一层：middleware 把阶段写进 Registry，只证明了"数据在内存里"；
 * 页面拿数据走的是 HTTP。内存对象与序列化后的 JSON 不是一回事，而这次刚从
 * Spring MVC 切到 WebFlux（编解码链路整条换了实现），中间件级别往上就没人验过。
 * 这层不验，就只能等页面报错时才发现，而那时你会以为是前端的问题。
 *
 * <p>用 {@code WebTestClient.bindToController} 直接绑控制器：不启 Spring 容器，
 * 不连 MySQL / Nacos / Elasticsearch / Redis，因此可以在 CI 里稳定跑。
 *
 * <p>断言口径按<b>前端契约</b>写（见 {@code agent-contro-plane/src/features/traces}），
 * 不是按后端字段写：字段名、状态词表、时间格式任何一项对不上，页面就是空白或报错，
 * 而这种错在编译期完全看不见。
 */
class AgentTraceControllerTest {

    private final AgentTraceRegistry registry = new AgentTraceRegistry();
    private final TraceStreamNotifier notifier = new TraceStreamNotifier();
    /** 心跳 100ms（测试等得起），兜底轮询 60s（见 newController）。 */
    private final AgentTraceController controller = newController(100L);
    private final WebTestClient client = WebTestClient.bindToController(controller).build();

    /**
     * 构造控制器：<b>兜底轮询故意拉到 60 秒</b>。
     *
     * <p>这样"测试里收到的事件"只可能来自"订阅即读"这一次查询，不可能来自轮询 ——
     * 否则测试会在恢复轮询工作、而通知链路已经坏掉的情况下照样通过。
     */
    private AgentTraceController newController(long heartbeatMs) {
        AgentPlatformProperties properties = new AgentPlatformProperties();
        properties.setTraceStreamRecoveryIntervalMs(60_000);
        return new AgentTraceController(registry, new TraceEventStream(registry, notifier, properties),
                new ObjectMapper(), heartbeatMs);
    }

    @Test
    void listReturnsFrontendRunSummary() {
        AgentTraceRun run = run();
        registry.finish(run, AgentTraceRun.STATUS_DONE, 10, 20, 6, null, Map.of());

        client.get().uri("/api/traces?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].runId").isEqualTo(run.getRunId())
                // 前端字段叫 agentId，后端内部叫 agentName —— 这层翻译必须在这里做完
                .jsonPath("$[0].agentId").isEqualTo("t_agent")
                .jsonPath("$[0].sessionId").isEqualTo("s1")
                // 前端状态词表是 running | success | failed | pending，后端是 DONE / ERROR / RUNNING
                .jsonPath("$[0].status").isEqualTo("success")
                .jsonPath("$[0].tone").isEqualTo("ok")
                .jsonPath("$[0].tokens").isEqualTo(30)
                // 时间必须是 ISO-8601，前端直接 Date.parse
                .jsonPath("$[0].startedAt").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    void listShowsRunningRunAsRunning() {
        run();

        client.get().uri("/api/traces")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].status").isEqualTo("running")
                .jsonPath("$[0].tone").isEqualTo("run");
    }

    @Test
    void runInfoCarriesKindAndCacheHitRate() {
        AgentTraceRun run = registry.start("t_agent", AgentTraceRun.AGENT_KIND_HARNESS, "s1", "u1", Map.of());
        registry.finish(run, AgentTraceRun.STATUS_DONE, 100, 7, 61, null, Map.of());

        client.get().uri("/api/traces/{runId}", run.getRunId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runId").isEqualTo(run.getRunId())
                .jsonPath("$.agentId").isEqualTo("t_agent")
                // 页面顶部的 react / harness 徽标靠它，不能是 null
                .jsonPath("$.agentKind").isEqualTo("harness")
                .jsonPath("$.sessionId").isEqualTo("s1")
                .jsonPath("$.user").isEqualTo("u1")
                .jsonPath("$.tokens").isEqualTo(107)
                .jsonPath("$.cacheHitRate").isEqualTo("61%");
    }

    @Test
    void eventsAreContiguousPathAddressedAndVocabularyClean() {
        AgentTraceRun run = registry.start("t_agent", AgentTraceRun.AGENT_KIND_REACT, "s1", "u1", Map.of());
        // 按真实发生顺序写事件：推理先开始 → 工具跑起来 → 工具先结束 → 推理后结束。
        // 这段顺序本身就是被测对象 —— 事件流的 seq 必须跟它走，因为 seq 是客户端的增量游标，
        // 一旦发出就不能再变（旧实现"阶段结束时才写一条"，会让已发出的编号被顶掉）。
        long t0 = run.getStartedAtMs();
        int reasoningStage = registry.beginStage(run, AgentTraceEvent.PHASE_REASONING,
                "第 1 轮推理 · t_agent", t0 + 5, Map.of("iteration", 1));
        int toolStage = registry.beginStage(run, AgentTraceEvent.PHASE_ACTING,
                "工具调用 · timesheet_list", t0 + 1010, Map.of("tool", "timesheet_list"));
        registry.endStage(run, toolStage, 500L,
                Map.of("state", "SUCCESS", "resultLength", 11), null);
        // 模型调用的信息（模型名 + token 用量）由推理节点收口时携带：它被折叠进所属的 REASONING，
        // 本身不产生事件（产生就会占号，而占了号的东西必须输出，否则事件流出现永久空洞）。
        registry.endStage(run, reasoningStage, 1000L,
                Map.of("text", "本月共 3 条", "model", "deepseek-chat", "totalTokens", 150,
                        "inputTokens", 120, "outputTokens", 30, "cachedTokens", 0), null);
        registry.finish(run, AgentTraceRun.STATUS_DONE, 120, 30, 0, null, Map.of("iterations", 1));

        List<AgentRunEvent> events = AgentRunEventTranslator.translate(run);

        // ① seq 从 1 开始且连续 —— 前端的 mergeContiguousEvents 只放行连续前缀，缺号会永久卡住
        assertThat(events).extracting(AgentRunEvent::seq)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, events.size())
                        .boxed().toList());
        // ② 词表闭集：前端 eventPresentation 是查表，表外类型会让页面渲染时抛异常
        assertThat(events).allSatisfy(event -> {
            assertThat(AgentRunEvent.TYPES).contains(event.type());
            assertThat(AgentRunEvent.KINDS).contains(event.kind());
            assertThat(AgentRunEvent.STATUSES).contains(event.status());
            assertThat(event.id()).isEqualTo(run.getRunId() + "-" + event.seq());
        });

        client.get().uri("/api/traces/{runId}/events", run.getRunId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // 根节点：path 固定 1、没有父
                .jsonPath("$.events[0].type").isEqualTo("run.started")
                .jsonPath("$.events[0].path").isEqualTo("1")
                .jsonPath("$.events[0].parentPath").doesNotExist()
                .jsonPath("$.events[0].kind").isEqualTo("agent")
                // 结束事件在最后，且根节点耗时是整轮 run 的耗时
                .jsonPath("$.events[" + (events.size() - 1) + "].type").isEqualTo("run.completed")
                .jsonPath("$.events[" + (events.size() - 1) + "].path").isEqualTo("1")
                // 第 2 条是推理开始（它先发生），不是工具 —— 事件顺序跟发生顺序走，不跟节点分组走
                .jsonPath("$.events[1].type").isEqualTo("reasoning.started");

        List<AgentRunEvent> reasoning = events.stream().filter(e -> "1.1".equals(e.path())).toList();
        assertThat(reasoning).extracting(AgentRunEvent::type)
                .containsExactly("reasoning.started", "reasoning.completed");
        // 推理节点把模型调用折叠了：source 取模型名、tokens 取模型用量的 total
        assertThat(reasoning.get(1).source()).isEqualTo("deepseek-chat");
        assertThat(reasoning.get(1).tokens()).isEqualTo(150);
        // 工具节点认得出工具名（前端靠它显示"工具调用 · xxx"）
        List<AgentRunEvent> tool = events.stream().filter(e -> "1.2".equals(e.path())).toList();
        assertThat(tool).extracting(AgentRunEvent::type)
                .containsExactly("tool.started", "tool.completed");
        assertThat(tool.get(1).source()).isEqualTo("timesheet_list");
        // 失败/成功事件都要成对：只有 completed 没有 started 的节点会被前端丢掉
        assertThat(events.stream().filter(e -> "1.1".equals(e.path())).findFirst().orElseThrow().status())
                .isEqualTo(AgentRunEvent.STATUS_RUN);
        assertThat(reasoning.get(1).status()).isEqualTo(AgentRunEvent.STATUS_OK);
        // MODEL_CALL 不单独成节点：整条流里只有两个阶段节点（推理 + 工具）加根节点。
        // 顺序按事件发生先后：推理开始 → 工具开始 → 工具结束 → 推理结束。
        assertThat(events).extracting(AgentRunEvent::path).containsExactly("1", "1.1", "1.2", "1.2", "1.1", "1");
    }

    @Test
    void eventsHonourLimitAfterAndBeforeCursors() {
        AgentTraceRun run = registry.start("t_agent", AgentTraceRun.AGENT_KIND_REACT, "s1", "u1", Map.of());
        long t0 = run.getStartedAtMs();
        int firstStage = registry.beginStage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理", t0 + 1, Map.of());
        registry.endStage(run, firstStage, 10L, Map.of(), null);
        int secondStage = registry.beginStage(run, AgentTraceEvent.PHASE_REASONING, "第 2 轮推理", t0 + 20, Map.of());
        registry.endStage(run, secondStage, 10L, Map.of(), null);

        // 不带游标：最新的 limit 条（首屏）
        client.get().uri("/api/traces/{runId}/events?limit=2", run.getRunId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.events.length()").isEqualTo(2)
                .jsonPath("$.events[0].seq").isEqualTo(4)
                .jsonPath("$.hasEarlier").isEqualTo(true);

        // after 游标：断线补齐，取 seq > 1 的最前两条
        client.get().uri("/api/traces/{runId}/events?after=1&limit=2", run.getRunId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.events[0].seq").isEqualTo(2)
                .jsonPath("$.events[1].seq").isEqualTo(3);

        // before 游标：向上翻历史，取 seq < 4 的最后两条
        client.get().uri("/api/traces/{runId}/events?before=4&limit=2", run.getRunId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.events[0].seq").isEqualTo(2)
                .jsonPath("$.events[1].seq").isEqualTo(3)
                .jsonPath("$.hasEarlier").isEqualTo(true);
    }

    @Test
    void unknownRunReturns404OnEveryReadEndpoint() {
        // 页面会在 run 被环形缓冲淘汰之后再点进去，404 必须是真的 404 而不是 500
        client.get().uri("/api/traces/{runId}", "no-such-run")
                .exchange()
                .expectStatus().isNotFound()
                // 404 同样要带原因：页面把它直接显示在"链路打不开"的提示里
                .expectBody()
                .jsonPath("$.error").isEqualTo("run 不存在: no-such-run");

        client.get().uri("/api/traces/{runId}/events", "no-such-run")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("run 不存在: no-such-run");
    }

    @Test
    void clearEmptiesTheRegistry() {
        run();

        client.delete().uri("/api/traces")
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/traces")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    // ==================== SSE：增量推送（Step 3） ====================

    /**
     * SSE 的 data 里必须是"与 REST 完全相同的 JSON"。
     *
     * <p>为什么这条值得单独钉死：前端把两条通道的事件合并在同一个数组里（按 id 去重、按 seq 排序）。
     * 只要字段名、事件词表、时间格式与 REST 那一路差一点，合并出来的树就会缺节点或错位，
     * 而两边的代码看起来"各自都对"。字段口径是按前端契约写的，不是按后端类型写的。
     */
    @Test
    void sseFrameCarriesTheSameJsonAsRest() throws Exception {
        AgentTraceRun run = sseRun();

        ServerSentEvent<String> frame = controller.toSse(AgentRunEventTranslator.translate(run).get(0));

        // id 用 seq：原生 EventSource 会把 id 原样当作 Last-Event-ID 回传，而本端点按整数解析它
        assertThat(frame.id()).isEqualTo("1");
        assertThat(frame.event()).isEqualTo(AgentRunEvent.TYPE_RUN_STARTED);

        JsonNode json = new ObjectMapper().readTree(frame.data());
        assertThat(json.get("id").asText()).isEqualTo(run.getRunId() + "-1");
        assertThat(json.get("seq").asInt()).isEqualTo(1);
        assertThat(json.get("runId").asText()).isEqualTo(run.getRunId());
        assertThat(json.get("type").asText()).isEqualTo("run.started");
        assertThat(json.get("path").asText()).isEqualTo("1");
        assertThat(json.get("kind").asText()).isEqualTo("agent");
        assertThat(json.get("status").asText()).isEqualTo("run");
        assertThat(json.get("parentPath").isNull()).isTrue();
        // 时间格式与 REST 那条路逐字节一致，前端两条通道都不做兼容分支
        assertThat(json.get("occurredAt").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    }

    /**
     * 数据帧与心跳帧。
     *
     * <p>两个断言各挡一类故障：数据帧必须<b>不等心跳</b>就出来（订阅即读没生效的话页面会空白到下一次心跳）；
     * 心跳必须<b>不带 data</b>（带了就会被前端当成事件塞进调用树）。
     */
    @Test
    @Timeout(60)
    void sseStreamPushesDataFramesAndHeartbeats() throws InterruptedException {
        AgentTraceRun run = sseRun();

        BlockingQueue<ServerSentEvent<String>> frames = new LinkedBlockingQueue<>();
        Disposable subscription = controller.frames(run.getRunId(), 0).subscribe(frames::add);
        try {
            ServerSentEvent<String> first = frames.poll(20, TimeUnit.SECONDS);
            assertThat(first).as("订阅之后没有立刻收到数据帧").isNotNull();
            assertThat(first.data()).isNotNull();
            assertThat(first.comment()).isNull();

            ServerSentEvent<String> beat = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (beat == null && System.nanoTime() < deadline) {
                ServerSentEvent<String> frame = frames.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null && frame.comment() != null) {
                    beat = frame;
                }
            }
            assertThat(beat).as("心跳帧没有到达").isNotNull();
            assertThat(beat.data()).isNull();
        } finally {
            subscription.dispose();
        }
    }

    /**
     * HTTP 这一层确实把响应推成了流，而且断点续传对整个链路都生效。
     *
     * <p>两点说明：
     * <ul>
     *   <li>只取第一帧就断开 —— 这条流永远不会结束，等它结束的断言会一直挂着；</li>
     *   <li>测试拿到的是<b>事件的 JSON</b>，不是带 {@code data:} 前缀的原始字节：
     *       WebTestClient 在客户端侧用的是 SSE 的 reader，它负责把帧拆开、只把 data 交出来
     *       （前端走的是原生 fetch + 自己切帧，拿到的是原始字节，这一点两边不一样）。
     *       能拆出来本身就说明服务端写出去的帧是合法的 SSE。</li>
     * </ul>
     */
    @Test
    @Timeout(60)
    void sseEndpointResumesFromTheLastEventIdHeader() {
        AgentTraceRun run = sseRun();

        FluxExchangeResult<String> result = client.get()
                .uri("/api/traces/{runId}/events/stream", run.getRunId())
                // 前端的重连就是带这个头（见 api.ts 的 sseStreamRunEvents）
                .header("Last-Event-ID", "2")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        String firstFrame = result.getResponseBody().take(1).blockFirst(Duration.ofSeconds(20));
        // 已经拿过 1、2，第一帧就必须是 3 —— 直接证明游标是从 HTTP 头一路传到数据面的
        assertThat(firstFrame).contains("\"seq\":3");
        assertThat(firstFrame).contains("\"type\":\"reasoning.completed\"");
    }

    /**
     * 不存在的 run 必须是真 404：前端把 404 当作"这个端点不可用、别再重连"的信号。
     *
     * <p>同时钉死 404 时回的是 <b>JSON 错误体</b>（本端点的 produces 是 text/event-stream）：
     * 错误体要是被 SSE 那套编码器接走，页面拿到的就又是一句「请求失败 (404)」。
     */
    @Test
    void unknownRunStreamIs404() {
        client.get().uri("/api/traces/{runId}/events/stream", "no-such-run")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("run 不存在: no-such-run");
    }

    /** 造一次有内容的 run：根节点 + 一个已收口的推理阶段。 */
    private AgentTraceRun sseRun() {
        AgentTraceRun run = registry.start("t_agent", AgentTraceRun.AGENT_KIND_HARNESS, "s1", "u1", Map.of());
        int stage = registry.beginStage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理",
                run.getStartedAtMs() + 1, Map.of());
        registry.endStage(run, stage, 25L, Map.of("content", "hi"), null);
        return run;
    }

    private AgentTraceRun run() {
        return registry.start("t_agent", AgentTraceRun.AGENT_KIND_REACT, "s1", "u1",
                Map.of("messageCount", 1));
    }
}
