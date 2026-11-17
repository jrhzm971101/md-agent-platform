package com.md.agentplatform.controller.chat;

// 测试与被测控制器同包同模块：控制器住在 bootstrap，
// 谁改了控制器却忘了改测试，在模块边界上直接可见。
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.chat.ChatStreamEvent;
import com.md.agentplatform.server.chat.ChatStreamService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话接口的 HTTP 契约测试。
 *
 * <p>为什么必须有这一层：服务侧把事件翻译成帧，只证明了"帧在内存里"；
 * 页面拿数据走的是 HTTP + SSE。内存对象与线上字节流不是一回事 ——
 * 帧的分隔、{@code event:} 字段、content-type、流什么时候结束，
 * 这些全都只在 HTTP 这一层才成立。这层不验，就只能等页面"一直转圈"时才发现。
 *
 * <p>用 {@code WebTestClient.bindToController} 直接绑控制器：不启 Spring 容器，
 * 不连 MySQL / Nacos / 模型，因此可以在 CI 里稳定跑。
 *
 * <p>断言口径按<b>前端契约</b>写（见 {@code agent-contro-plane/src/features/chat/api.ts}）：
 * 帧的类型词表、字段名、收口语义任何一项对不上，页面就是空白或死等。
 */
class AgentChatControllerTest {

    private static final String AGENT = "t_agent";

    /** 心跳 100ms（测试等得起）。注意最小值被构造器抬到 1000ms，见 newController。 */
    private final AgentChatController controller = newController(
            new TextBlockDeltaEvent("r1", "b1", "你好"),
            new TextBlockDeltaEvent("r1", "b1", "，世界"));

    private final WebTestClient client = WebTestClient.bindToController(controller).build();

    private static AgentChatController newController(AgentEvent... events) {
        ChatStreamService service = new ChatStreamService(new AgentManager() {
            @Override
            public boolean exists(String name) {
                return AGENT.equals(name);
            }

            @Override
            public Flux<AgentEvent> stream(String name, List<Msg> msgs, RuntimeContext ctx) {
                return Flux.fromArray(events);
            }
        });
        // 心跳给 100ms：流本来就秒级结束，心跳不该出现 —— 这本身也是一条断言。
        return new AgentChatController(service, new ObjectMapper(), 100L);
    }

    // ==================================================================================
    // 状态码：必须在开始写响应体之前定下来
    // ==================================================================================

    /**
     * 空消息是调用方的错，不是「agent 没回话」。
     *
     * <p>顺带钉死"body 里有原因"：前端把 {@code error} 原样显示在气泡下方，
     * 空 body 会退化成「请求失败 (400)」，用户看不出该改什么。
     */
    @Test
    void blankMessageIsBadRequest() {
        client.post().uri("/api/agents/{agentId}/chat", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "   "))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("消息内容不能为空");
    }

    /**
     * 打错 agentId 必须是真 404。
     *
     * <p>之所以单拎出来测：SSE 一旦开始写响应体就改不了状态码，
     * 这时候要是走到"返回空流"那条路，前端只会看到 200 + 空响应体，
     * 页面上表现成「agent 不回话」，排查时会先去怀疑模型。
     */
    @Test
    void unknownAgentIsNotFound() {
        client.post().uri("/api/agents/{agentId}/chat", "no-such-agent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "hi"))
                .exchange()
                .expectStatus().isNotFound()
                // body 必须说清是哪个 agent：页面显示的就是这句话，不显示状态码
                .expectBody()
                .jsonPath("$.error").value(org.hamcrest.Matchers.containsString("no-such-agent"));
    }

    // ==================================================================================
    // 帧序列：首帧 run、末帧 done、且一定会结束
    // ==================================================================================

    /**
     * 帧级断言（不走 HTTP），因为这里要验的是「流会结束」——
     * 挂在端点上断言的话，真要是不结束，测试会一直挂着而不是失败。
     *
     * <p>{@code @Timeout} 是故意的：万一 takeUntil 写漏了，
     * 失败信息应该是「超时」，而不是一条永远不返回的测试。
     */
    @Test
    @Timeout(30)
    void framesStartWithRunAndEndWithDone() {
        List<ServerSentEvent<String>> frames = controller.frames(AGENT, "hi", "s-1", null)
                .collectList()
                .block(Duration.ofSeconds(20));

        assertThat(frames).as("对话流没有在预期时间内收口").isNotNull();
        assertThat(frames).extracting(ServerSentEvent::event)
                .containsExactly("run", "delta", "delta", "done");
        // 心跳是注释帧（data 为 null）；流这么短不该出现心跳，否则说明心跳间隔写错了
        assertThat(frames).allSatisfy(frame -> assertThat(frame.data()).isNotNull());
        // 首帧回执生效的会话键 —— 前端丢掉它，多轮对话就会散成多个会话
        assertThat(frames.get(0).data()).contains("\"sessionId\":\"s-1\"");
        assertThat(frames.get(0).data()).contains("\"agentId\":\"t_agent\"");
        // 请求没带 userId 时回执默认值，前端据此知道这次落在哪个用户分区
        assertThat(frames.get(0).data()).contains("\"userId\":\"console\"");
        // runId：页面上「这条回答 → 那条链路」的凭据，形状与 TraceRunIdGenerator 一致
        assertThat(frames.get(0).data()).containsPattern("\"runId\":\"[0-9a-z]{8}-[0-9a-z]{6}\"");
        assertThat(frames.get(3).data()).contains("\"status\":\"DONE\"");
    }

    /** 请求不带会话键时，后端现生成一个并回执 —— 前端第 2 轮要把它带回来。 */
    @Test
    @Timeout(30)
    void runFrameCarriesGeneratedSessionId() {
        List<ServerSentEvent<String>> frames = controller.frames(AGENT, "hi", null, null)
                .collectList()
                .block(Duration.ofSeconds(20));

        assertThat(frames).isNotNull();
        assertThat(frames.get(0).data()).containsPattern("\"sessionId\":\"console-[0-9a-f]{8}\"");
    }

    /**
     * 请求带了 userId 时，run 帧回执的必须是消毒后的那个值。
     *
     * <p>这一条和页面的关系是：userId 决定「这次对话落在哪个工作区目录」，
     * 回执给错，排查时就会去翻一个根本没有写入的目录。
     */
    @Test
    @Timeout(30)
    void runFrameCarriesSanitizedUserId() {
        List<ServerSentEvent<String>> frames = controller.frames(AGENT, "hi", null, "user:7/a")
                .collectList()
                .block(Duration.ofSeconds(20));

        assertThat(frames).isNotNull();
        assertThat(frames.get(0).data()).contains("\"userId\":\"user-7-a\"");
    }

    /**
     * HTTP 这一层确实把响应推成了流，且内容是 JSON 帧。
     *
     * <p>说明：测试拿到的是帧的 <b>data 部分</b>，不是带 {@code data:} 前缀的原始字节 ——
     * WebTestClient 在客户端侧用的是 SSE reader，它负责把帧拆开、只把 data 交出来
     * （前端走的是原生 fetch + 自己切帧，拿到的是原始字节，这一点两边不一样）。
     * 能拆出来本身就说明服务端写出去的帧是合法 SSE。
     */
    @Test
    @Timeout(60)
    void sseEndpointPushesJsonFrames() {
        FluxExchangeResult<String> result = client.post().uri("/api/agents/{agentId}/chat", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "hi", "sessionId", "s-9", "userId", "u-9"))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);

        String firstFrame = result.getResponseBody().take(1).blockFirst(Duration.ofSeconds(20));

        assertThat(firstFrame).isNotNull();
        assertThat(firstFrame).contains("\"type\":\"run\"");
        assertThat(firstFrame).contains("\"sessionId\":\"s-9\"");
        // userId 在 HTTP 这一层确实被反序列化并透传进 run 帧（字段名写错在这里才会暴露）
        assertThat(firstFrame).contains("\"userId\":\"u-9\"");
    }

    /** 帧序列化必须给出 {type, data} 两段结构（前端按它分派渲染）。 */
    @Test
    void toSseKeepsTypeAndPayload() {
        ServerSentEvent<String> frame = controller.toSse(ChatStreamEvent.delta("你好"));

        assertThat(frame.event()).isEqualTo("delta");
        assertThat(frame.data()).contains("\"type\":\"delta\"");
        assertThat(frame.data()).contains("\"text\":\"你好\"");
        // 不写 id：本接口不支持断点续传，写一个不处理的游标会让人以为可以续传
        assertThat(frame.id()).isNull();
    }
}
