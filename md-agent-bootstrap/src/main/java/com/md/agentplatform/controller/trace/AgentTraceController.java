package com.md.agentplatform.controller.trace;

// 控制器迁入 bootstrap 后，这些类型不再同包，必须显式 import。
// 注意它们全是 server 模块的领域对象/组件 —— 搬走的只有"Web 外壳"，数据面逻辑一行没动。
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.trace.AgentRunEvent;
import com.md.agentplatform.server.trace.AgentRunEventTranslator;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.TraceEventPage;
import com.md.agentplatform.server.trace.TraceRunInfo;
import com.md.agentplatform.server.trace.TraceRunSummary;
import com.md.agentplatform.server.trace.stream.TraceEventStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 调用链路观测 REST API —— 与前端 {@code features/traces} 的契约一一对应。
 *
 * <p>位于 bootstrap 模块（HTTP 边界层）：本类只做协议适配、分页切片与状态码翻译，
 * 事件形态转换在 server 模块的 {@link AgentRunEventTranslator} 里，两边职责不重叠。
 *
 * <ul>
 *   <li>GET    /api/traces?limit=50        最近 N 次 run，新的在前 → List&lt;TraceRunSummary&gt;</li>
 *   <li>GET    /api/traces/{runId}         单次 run 的元信息 → TraceRunInfo</li>
 *   <li>GET    /api/traces/{runId}/events  事件流（游标分页） → TraceEventPage</li>
 *   <li>GET    /api/traces/{runId}/events/stream  事件流（SSE 增量推送，支持 Last-Event-ID 续传）</li>
 *   <li>DELETE /api/traces                 清空（内存 + 归档；只动观测数据，不碰任何业务数据）</li>
 * </ul>
 *
 * <h2>为什么返回"翻译后"的结构，而不是 Registry 里的原始对象</h2>
 * 观测页面的取数是"历史 REST + 增量 SSE"两条通道，两边必须给出同一种事件（同一个 seq、
 * 同一个 path），否则前端合并时对不上号。所以形态转换只发生在后端一处
 * （{@link AgentRunEventTranslator}），控制器只做分页切片，不做任何翻译。
 *
 * <h2>游标语义（与前端 useRunEvents 的三次调用严格对应）</h2>
 * <ul>
 *   <li>不带游标：取最新的 limit 条 —— 首屏 refresh</li>
 *   <li>{@code after=N}：取 seq &gt; N 的最前 limit 条 —— 断线补齐 / 增量拉取</li>
 *   <li>{@code before=N}：取 seq &lt; N 的最后 limit 条 —— 向上翻历史</li>
 * </ul>
 *
 * <h2>SSE 端点的三条约定（与前端 features/traces/api.ts 逐条对应）</h2>
 * <ol>
 *   <li><b>数据只放在 {@code data:} 行里</b>，内容是<b>与 REST 完全相同</b>的 AgentRunEvent JSON。
 *       前端只解析 {@code data:} 行，{@code event:} / {@code id:} / 注释行一律忽略 ——
 *       两条通道给出同一种事件，前端的合并逻辑才不需要分支。</li>
 *   <li><b>从客户端游标的下一个号开始推</b>：请求头 {@code Last-Event-ID} 与查询参数 {@code after}
 *       表达的是同一件事（"我已经拿到这里了"），取两者中更大的那个。帧的 {@code id} 写 seq，
 *       这样即使用原生 EventSource 的客户端也能自动续传。</li>
 *   <li><b>流不会因为 run 结束而关闭</b>：收口本身也是事件（run.completed / error.raised）。
 *       一旦关流，前端的退避重连机制会对着一个已经结束的 run 反复重连。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/traces")
public class AgentTraceController {

    /** 一页事件的上限：防止有人 limit=1000000 把整条 run 拖出来打爆响应体。 */
    private static final int MAX_EVENTS_PER_PAGE = 1_000;
    /** run 列表上限。 */
    private static final int MAX_RUNS_PER_PAGE = 500;

    /**
     * 构造器注入而非字段注入：让本控制器可以脱离 Spring 容器被直接 new 出来，
     * 用 WebTestClient 做 HTTP 契约测试（见 AgentTraceControllerTest）。
     */
    private final AgentTraceRegistry traceRegistry;
    /** SSE 数据面（在 server 模块）：本控制器只负责把它包成 SSE 帧，不碰游标与唤醒逻辑。 */
    private final TraceEventStream eventStream;
    /** 序列化事件用。显式取容器里那一个，避免"测试里的 mapper 与线上不是同一个配置"。 */
    private final ObjectMapper objectMapper;
    /** 心跳间隔（毫秒）。 */
    private final long heartbeatMs;

    @Autowired
    public AgentTraceController(AgentTraceRegistry traceRegistry, TraceEventStream eventStream,
                                ObjectMapper objectMapper,
                                @Value("${agent-platform.trace-stream-heartbeat-ms:15000}") long heartbeatMs) {
        this.traceRegistry = traceRegistry;
        this.eventStream = eventStream;
        this.objectMapper = objectMapper;
        // 比 1 秒更密的心跳除了白占带宽没有任何作用
        this.heartbeatMs = Math.max(1_000L, heartbeatMs);
    }

    /** 最近的 run，新的在前。 */
    @GetMapping
    public List<TraceRunSummary> list(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        int size = Math.max(1, Math.min(limit, MAX_RUNS_PER_PAGE));
        return traceRegistry.recent(size).stream().map(TraceRunSummary::from).toList();
    }

    /**
     * 单次 run 的元信息。
     *
     * <p>内存未命中时 Registry 会回 MySQL 补齐，所以"被环形缓冲淘汰"不再等于 404 ——
     * 只有归档里也没有（被保留期清理掉，或 runId 根本不存在）才会 404。
     */
    @GetMapping("/{runId}")
    public ResponseEntity<?> get(@PathVariable("runId") String runId) {
        AgentTraceRun run = traceRegistry.get(runId);
        return run == null ? notFound(runId) : ResponseEntity.ok(TraceRunInfo.from(run));
    }

    /**
     * 事件流分页。
     *
     * <p>每次请求都从阶段记录重新翻译一遍，而不是把翻译结果缓存起来：
     * 翻译是纯函数（毫秒级，一次 run 几十到几百条），而缓存要处理"新阶段写入后失效"，
     * 省下的那点 CPU 换不来这份复杂度。
     */
    @GetMapping("/{runId}/events")
    public ResponseEntity<?> events(
            @PathVariable("runId") String runId,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "after", required = false) Integer after,
            @RequestParam(name = "before", required = false) Integer before) {
        AgentTraceRun run = traceRegistry.get(runId);
        if (run == null) {
            return notFound(runId);
        }
        int size = Math.max(1, Math.min(limit, MAX_EVENTS_PER_PAGE));
        List<AgentRunEvent> all = AgentRunEventTranslator.translate(run);

        int from;
        int to;
        if (after != null) {
            from = AgentRunEventTranslator.indexAfterSeq(all, after);
            to = Math.min(from + size, all.size());
        } else if (before != null) {
            // 第一个 seq >= before 的位置就是"更早那一段"的右边界
            to = AgentRunEventTranslator.indexAfterSeq(all, before - 1);
            from = Math.max(0, to - size);
        } else {
            to = all.size();
            from = Math.max(0, to - size);
        }
        return ResponseEntity.ok(new TraceEventPage(List.copyOf(all.subList(from, to)), from > 0));
    }

    /**
     * 清空观测数据：内存与归档一起清。
     *
     * <p>为什么连归档一起清：只清内存的话，刷新页面数据又全回来了，用户会认为按钮坏了。
     * 清零的范围严格限定在 {@code md_agent_run / md_agent_run_event} 两张观测表内，
     * 不触碰任何业务数据（agent 定义、会话状态、消息都不在这两张表里）。
     */
    @DeleteMapping
    public void clear() {
        traceRegistry.clear();
    }

    // ==================================================================================
    // SSE：增量推送
    // ==================================================================================

    /**
     * 事件流（SSE）—— 页面上"链路正在长"就是这条流推出来的。
     *
     * <p><b>为什么 404 要在订阅之前判</b>：SSE 一旦开始写响应体就没法再改状态码了。
     * 判存在要读 Registry（内存未命中时是阻塞的数据库查询），所以整段包在
     * {@code Mono.fromCallable + boundedElastic} 里 —— 控制器跑在 Netty 事件循环线程上，
     * 直接阻塞会把同一条线程上的其它连接一起拖住。
     *
     * <p>另外，前端把 404 当作"这个端点不存在、别再重连了"的信号（见 api.ts 的 sseStreamRunEvents），
     * 所以不存在的 runId 必须给真 404，不能给 200 + 空流 —— 那会让页面抱着一条永远没数据的连接不放。
     *
     * <p>404 的 body 里同样要有原因（见 {@link #notFound}）：页面把它显示在"链路打不开"的提示里，
     * 用户据此才能分清是 runId 抄错了、还是这条链路已经过了保留期被清掉。
     */
    @GetMapping(value = "/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<?>> streamEvents(
            @PathVariable("runId") String runId,
            @RequestParam(name = "after", required = false) Integer after,
            @RequestHeader(name = "Last-Event-ID", required = false) Integer lastEventId) {
        int afterSeq = Math.max(after == null ? 0 : after, lastEventId == null ? 0 : lastEventId);
        return Mono.fromCallable(() -> traceRegistry.get(runId))
                .subscribeOn(Schedulers.boundedElastic())
                // 显式写类型见证：成功分支是 ResponseEntity<Flux<ServerSentEvent<String>>>、
                // 失败分支是 JSON 错误体，两者唯一的公共父类型是 ResponseEntity<?> ——
                // 不写它，下一行 defaultIfEmpty 的类型推断会失败。
                .<ResponseEntity<?>>map(run -> ResponseEntity.ok(frames(runId, afterSeq)))
                // 这里必须用 defaultIfEmpty，不能只在 map 里判 null：
                // Mono.fromCallable 的 Callable 返回 null 时给出的是"空 Mono"（不是"发了一个 null"），
                // map 根本不会被调用，而空 Mono 到了 WebFlux 就是 200 + 空响应体 ——
                // 前端会抱着一条永远没有数据的连接不放，还看不出哪里错了（这一条是被集成测试逼出来的）。
                .defaultIfEmpty(notFound(runId));
    }

    /**
     * runId 不存在（打错了，或已过保留期被清掉）：404 + 一句能直接显示给用户的原因。
     *
     * <p>为什么必须带 body：空 body 传到页面只剩「请求失败 (404)」，用户分不清
     * 是 runId 抄错了、链路被清理了，还是后端根本没这个端点。
     *
     * <p>为什么显式写 content-type：本类三个端点共用这一个方法，其中 {@link #streamEvents}
     * 的 produces 是 {@code text/event-stream}，不写 content-type 时序列化 JSON body
     * 会去挑 SSE 那套帧编码器；对另外两个纯 JSON 端点来说，application/json
     * 与协商结果完全一致，没有副作用。
     */
    private static ResponseEntity<?> notFound(String runId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "run 不存在: " + runId));
    }

    /**
     * 数据帧与心跳帧的合流。
     *
     * <p>为什么要单独抽出来而不是直接写在端点里：端点一旦返回流，这层就没法用 WebTestClient 验了
     * —— 那条流不会结束，断言会一直等下去。抽出来之后测试可以直接订阅它，
     * 验"第一帧是数据帧、心跳帧确实会来、两者格式都对"。
     */
    Flux<ServerSentEvent<String>> frames(String runId, int afterSeq) {
        Flux<ServerSentEvent<String>> data = eventStream.stream(runId, afterSeq).map(this::toSse);
        // 心跳用 SSE 注释行（前端只读 data: 行，天然忽略它）：
        // 没有它，代理/负载均衡可能因为长时间没有字节流动而把这条长连接掐掉。
        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofMillis(heartbeatMs))
                .map(tick -> ServerSentEvent.<String>builder().comment("keep-alive").build());
        return Flux.merge(data, heartbeats);
    }

    /**
     * 一条事件 → 一帧 SSE。
     *
     * <p>{@code id} 放 seq 而不是事件 id（{@code runId-seq}）：SSE 协议的 id 会被客户端当作
     * Last-Event-ID 回传，而本端点解析的是整数 seq，用事件 id 会让续传失效。
     * {@code event} 放事件类型纯粹为了调试友好（curl 一眼看出在推什么），前端不依赖它。
     */
    ServerSentEvent<String> toSse(AgentRunEvent event) {
        try {
            return ServerSentEvent.<String>builder()
                    .id(String.valueOf(event.seq()))
                    .event(event.type())
                    .data(objectMapper.writeValueAsString(event))
                    .build();
        } catch (JsonProcessingException e) {
            // 不能吞：吞掉等于"这一帧静默消失"，而前端的游标合并只放行连续前缀，缺一帧页面就永久停住。
            // 抛出去终止这条流，前端按退避重连，日志里也留下了堆栈。
            throw new IllegalStateException(
                    "轨迹事件序列化失败 seq=" + event.seq() + " eventId=" + event.id(), e);
        }
    }
}
