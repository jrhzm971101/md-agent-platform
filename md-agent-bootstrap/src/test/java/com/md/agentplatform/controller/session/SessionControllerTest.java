package com.md.agentplatform.controller.session;

// 测试与被测控制器同包同模块：控制器改了而契约测试没跟上，在模块边界上直接可见。
import com.md.agentplatform.server.session.AgentSessionAdminService;
import com.md.agentplatform.server.session.AgentSessionViews;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话接口的 HTTP 契约测试。
 *
 * <p>为什么必须有这一层：服务层单测证明了"数据就在内存里是对的"，而页面拿数据走的是 HTTP。
 * 内存对象与序列化后的 JSON 不是一回事 —— 尤其这一页有两个容易静默出错的地方：
 * <ol>
 *   <li>{@code sessionId} 由各入口自己算，可能含冒号、斜杠、中文，走的是查询参数
 *       而不是路径段。参数名写错、或者哪天有人把它挪进路径，编译期完全看不出来；</li>
 *   <li>{@code from} / {@code to} 是 epoch millis（{@code Long}），
 *       写成字符串接收会在 Spring 的类型转换上表现成 400，而不是"值不对"。</li>
 * </ol>
 *
 * <p>用 {@code WebTestClient.bindToController} 直接绑控制器：不启 Spring 容器、不连库，
 * 因此 CI 上稳定可跑。断言口径按<b>前端契约</b>写
 * （见 {@code agent-contro-plane/src/api/sessions.ts}），不是按后端字段写。
 */
class SessionControllerTest {

    private final AgentSessionAdminService service = mock(AgentSessionAdminService.class);
    private final WebTestClient client =
            WebTestClient.bindToController(new SessionController(service)).build();

    @Test
    void 列表把全部筛选参数透传给服务并返回页面形状() {
        /* hasState 走 Boolean：写成 String 接收的话 "false" 会变成非空字符串，
           在服务层被当成"有个值"从而走错分支，而 HTTP 上看起来完全正常。 */
        when(service.list("workwx_batch_agent", "wecom", 1_000L, 2_000L, "abc", true, 20, 40))
                .thenReturn(new AgentSessionViews.SessionPage(1, 3, List.of(session())));

        client.get()
                .uri("/api/sessions?agentId=workwx_batch_agent&source=wecom&from=1000&to=2000"
                        + "&keyword=abc&hasState=true&limit=20&offset=40")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.allTotal").isEqualTo(3)
                .jsonPath("$.items[0].sessionId").isEqualTo("single-abc")
                .jsonPath("$.items[0].hasState").isEqualTo(true)
                /* agents 是数组而不是逗号串 —— 这是本页"agent 是筛选维度"的界面形态 */
                .jsonPath("$.items[0].agents.length()").isEqualTo(2)
                .jsonPath("$.items[0].agents[1]").isEqualTo("workwx_batch_agent")
                .jsonPath("$.items[0].lastMs").isEqualTo(9_000);
    }

    @Test
    void 不带任何筛选参数时使用默认分页() {
        when(service.list(null, null, null, null, null, null, 20, 0))
                .thenReturn(new AgentSessionViews.SessionPage(0, 0, List.of()));

        client.get().uri("/api/sessions").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(0);
        verify(service).list(null, null, null, null, null, null, 20, 0);
    }

    /**
     * {@code userId} 可以不传（库里有 user_id 为 NULL 的会话），
     * 但 {@code sessionId} 是这个接口的必要条件 —— 少了它必须 400 而不是"查全部"。
     */
    @Test
    void 详情允许缺userId但缺sessionId直接400() {
        when(service.detail(null, "single-abc"))
                .thenReturn(new AgentSessionViews.SessionDetail(null, "single-abc", List.of(), List.of()));

        client.get().uri("/api/sessions/detail?sessionId=single-abc")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo("single-abc")
                .jsonPath("$.runs.length()").isEqualTo(0)
                .jsonPath("$.states.length()").isEqualTo(0);
        verify(service).detail(null, "single-abc");

        client.get().uri("/api/sessions/detail?userId=u1").exchange()
                .expectStatus().isBadRequest();
    }

    private static AgentSessionViews.Session session() {
        return new AgentSessionViews.Session(
                "wo7U1", "single-abc", 6, 1, 1_200,
                List.of("deployment_agent", "workwx_batch_agent"), List.of("wecom"),
                1_000L, 9_000L, true, List.of("workwx_batch_agent"));
    }
}
