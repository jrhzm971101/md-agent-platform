package com.md.agentplatform.controller.chat;

// 控制器住在 bootstrap（HTTP 边界层），领域对象与服务在 server 模块，跨模块必须显式 import。
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.chat.ChatStreamEvent;
import com.md.agentplatform.server.chat.ChatStreamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/**
 * 页面对话 API —— 控制台里「直接用一下这个 agent」的入口。
 *
 * <ul>
 *   <li>POST /api/agents/{agentId}/chat   一次对话，SSE 流式返回</li>
 * </ul>
 *
 * <h2>为什么是 POST 而不是 GET</h2>
 * 消息正文是任意文本，塞进 query 会撞 URL 长度上限，也会把用户输入原样写进访问日志。
 * 代价是用不了原生 {@code EventSource}（它只发 GET），所以前端用 fetch + ReadableStream
 * 自己读流 —— 执行链路页已经是这套读法，两边共用同一个解析思路，不引入第二套。
 *
 * <h2>帧格式与 <code>features/chat/api.ts</code> 的约定</h2>
 * <ol>
 *   <li>{@code data:} 行放整帧 JSON（{@code {type, data}}），{@code event:} 行冗余写 type
 *       ——只为 curl 直读时一眼看出在推什么，前端以 JSON 里的 type 为准。</li>
 *   <li>心跳走 SSE 注释行（{@code :keep-alive}），前端天然忽略。</li>
 *   <li>不写 {@code id:} 行：本接口不支持断点续传。SSE 的 id 会被客户端当作
 *       Last-Event-ID 回传，写一个我们并不处理的游标只会让后来的人以为可以续传。</li>
 *   <li><b>流一定会结束</b>：正常收口发 {@code done}，失败发 {@code error} 后仍发 {@code done}。
 *       这点与执行链路 SSE 恰好相反 —— 那边是"订阅式观测"，run 结束也不关流（关了前端会
 *       对着结束的 run 反复重连）；这里是"一问一答"，必须收口，否则连接会一直挂着。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/agents")
public class AgentChatController {

    private final ChatStreamService chatStreamService;
    private final ObjectMapper objectMapper;
    private final long heartbeatMs;

    /**
     * 构造器注入而非字段注入：本控制器可以脱离容器被直接 new 出来，
     * 用 WebTestClient 做 HTTP 契约测试（见 AgentChatControllerTest）。
     *
     * <p>这里只注入 {@link ChatStreamService}，不直接注入 AgentManager：
     * 「这个 agent 在不在」这件事服务侧已经有 {@code hasAgent}，再拿一份映射来判
     * 就等于同一个判断有两个出口，迟早会有一边漏改。
     */
    public AgentChatController(ChatStreamService chatStreamService,
                               ObjectMapper objectMapper,
                               @Value("${agent-platform.chat-heartbeat-ms:15000}") long heartbeatMs) {
        this.chatStreamService = chatStreamService;
        this.objectMapper = objectMapper;
        // 比 1 秒更密的心跳除了白占带宽没有任何作用
        this.heartbeatMs = Math.max(1_000L, heartbeatMs);
    }

    /**
     * 一次对话。
     *
     * <p><b>两个校验必须在返回流之前做完</b>：SSE 一旦开始写响应体就改不了状态码了，
     * 那时候再发现 agentId 打错，前端只会收到一条 200 的空流，排查时完全看不出原因。
     * 这两步都是纯内存判断（{@code agents} 是 ConcurrentHashMap），不需要切调度器。
     *
     * <p>失败时回的是 <b>JSON 错误体</b>（见 {@link #error}），不是 SSE 帧：
     * 前端 {@code useChat} 把 {@code error} 字段原样显示在气泡下方，
     * 所以文案要写成"用户看得懂的那一句原因"，而不是状态码的复述。
     */
    @PostMapping(value = "/{agentId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> chat(
            @PathVariable("agentId") String agentId,
            @RequestBody ChatRequest request) {

        if (request == null || request.message() == null || request.message().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "消息内容不能为空");
        }
        if (!chatStreamService.hasAgent(agentId)) {
            // 顺带说清下一步：名字是人工填的、发布是另一个动作，只说"不存在"会让人以为要重建
            return error(HttpStatus.NOT_FOUND,
                    "agent 不存在或未装配: " + agentId + "（到 Agent 列表确认它已发布，或点一次「重载」）");
        }
        return ResponseEntity.ok(frames(agentId, request.message(), request.sessionId(), request.userId()));
    }

    /**
     * 错误响应：<b>必须带 body</b>。
     *
     * <p>为什么不能再用 {@code ResponseEntity.notFound().build()}：空 body 传到页面只剩
     * 「请求失败 (404)」。本平台的资源全是人工在控制台里建的，"名字打错一个字符"是常态，
     * 所以这句必须说清是哪个名字、下一步该做什么 —— 这也是前端 {@code readApiFailure}
     * 唯一读取的字段（见 {@code agent-contro-plane/src/lib/apiClient.ts}）。
     *
     * <p>为什么显式写 content-type：本端点的 {@code produces} 是 {@code text/event-stream}，
     * 而错误体是 JSON，不指定的话写 body 时会去挑 SSE 那套帧编码器。
     */
    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }

    /**
     * 数据帧与心跳帧的合流。
     *
     * <p>为什么单独抽出来而不是直接写在端点里：端点一旦返回流，这层就没法用 WebTestClient
     * 验了 —— 断言要一直等一条不会结束的流。抽出来之后测试可以直接订阅，
     * 验「首帧是 run 帧、末帧是 done 帧、心跳帧格式对」。
     *
     * <p>{@code takeUntil} 是这里唯一一处容易写错的地方：{@code Flux.interval} 永远不结束，
     * 直接 merge 出去连接就永远不会关（页面表现为"答完了但还在转圈"）。
     */
    Flux<ServerSentEvent<String>> frames(String agentId, String message, String sessionId, String userId) {
        Flux<ServerSentEvent<String>> data =
                chatStreamService.chat(agentId, message, sessionId, userId).map(this::toSse);
        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofMillis(heartbeatMs))
                .map(tick -> ServerSentEvent.<String>builder().comment("keep-alive").build());
        return Flux.merge(data, heartbeats)
                .takeUntil(frame -> ChatStreamEvent.TYPE_DONE.equals(frame.event())
                        || ChatStreamEvent.TYPE_ERROR.equals(frame.event()));
    }

    /**
     * 一帧 → 一帧 SSE。
     *
     * <p>序列化失败<b>不能吞</b>：吞掉等于这一帧静默消失，页面上表现为"回答突然断了一半"
     * 却没有任何错误提示。抛出去终止这条流，前端的 catch 会把连接错误显示出来，
     * 日志里也留下了完整堆栈。
     */
    ServerSentEvent<String> toSse(ChatStreamEvent frame) {
        try {
            return ServerSentEvent.<String>builder()
                    .event(frame.type())
                    .data(objectMapper.writeValueAsString(frame))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("对话帧序列化失败 type=" + frame.type(), e);
        }
    }

    /**
     * 请求体。
     *
     * <p>会话键可选（初版只传 message 也能跑）：为空时后端现生成一个并回执在 run 帧里。
     * 它一旦给定，就必须原样带回，否则多轮对话会散成多个互不相干的会话。
     *
     * <p>userId 同样可选，为空时后端用默认值（{@code console}），并在 run 帧里回执实际生效的值。
     * 它决定记忆分区：框架按 (userId, sessionId) 分区，本项目里两者拼成
     * {@code <userId>:<sessionId>} 存进 per-agent 状态表的 {@code session_id} 列 ——
     * 所以换 userId 等于换一个人（另起一份记忆），不是"给同一个会话贴个标签"。
     */
    public record ChatRequest(String message, String sessionId, String userId) {
    }
}
