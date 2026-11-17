package com.md.agentplatform.controller.alert;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenObserve Webhook 接收接口的 HTTP 契约测试。
 *
 * <p>用 {@code bindToController} 直接绑控制器：不启容器、不连数据库，CI 里可稳定跑。
 * 断言口径按 OpenObserve 实际会发出的报文写（企微 text 结构 + 模板渲染后的中文 content），
 * 而不是按内部 DTO 字段写。
 */
class OpenObserveAlertWebhookControllerTest {

    private final WebTestClient client =
            WebTestClient.bindToController(new OpenObserveAlertWebhookController()).build();

    @Test
    void acceptsRenderedWeComTextAlert() {
        client.post().uri("/api/webhooks/openobserve/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "msgtype", "text",
                        "text", Map.of("content",
                                "🚨 【告警通知】\n告警名称: lms-backend-error\n流名称: lms-backend\n"
                                        + "严重程度: Error\n日志信息: boom\n触发时间: 2026-11-06T10:00:00")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.received").isEqualTo(true);
    }

    /**
     * 线上真实报文：日志行里带 {@code [TID:N/A]}、{@code #1-11}、类名冒号和逗号。
     * 这些字符在 JSON 字符串里都是合法的，真正需要关注的是 OpenObserve 是否把换行和引号
     * 转义后再拼进模板 —— 0.15.x 的模板替换会转义 {@code "}、{@code \}、{@code \n}，
     * 所以接收端拿到的是合法 JSON，换行在 Jackson 解码后恢复。
     */
    @Test
    void acceptsRealMultiLineLogContent() {
        String content = """
                🚨 【告警通知】
                告警名称: mtn-risk-open-api
                流名称: cmr_prod_risk_open_api
                严重程度: Error
                日志信息: 2026-11-06 07:32:54.499 ERROR [TID:ece4f9a96bc743acb12fe568795bc98c.178.17939467735930007] [embeddedWorkerExecutor-33] c.m.r.f.e.service.client.madapi.MadApiClient.setUsageFlag:215 [2118549668320747527][2118592438636879880][NORMAL] [MADAPI_OAUTH2] API call failed, status code: 400, response content: {"statusMessage":"Invalid Country Code provided","transactionId":"2118592441371836416","timestamp":"2026-11-06 06:32:54","path":"https://preprod.api.mtn.com/v1","statusCode":"5000","downstreamStatusCode":null,"sequenceNo":"192b84e-7985-59dd-9"}
                触发时间: 2026-11-06T07:32:55
                """;

        client.post().uri("/api/webhooks/openobserve/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("msgtype", "text", "text", Map.of("content", content)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.received").isEqualTo(true);
    }

    @Test
    void toleratesExtraFieldsAddedToTheTemplate() {
        String body = """
                {"msgtype":"text","text":{"content":"告警名称: a"},"mentioned_list":["@all"]}
                """;

        client.post().uri("/api/webhooks/openobserve/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.received").isEqualTo(true);
    }

    @Test
    void rejectsMissingTextContent() {
        client.post().uri("/api/webhooks/openobserve/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("msgtype", "text"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.received").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("text.content 不能为空");
    }

    @Test
    void rejectsBlankTextContent() {
        client.post().uri("/api/webhooks/openobserve/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("msgtype", "text", "text", Map.of("content", "   ")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("text.content 不能为空");
    }

    @Test
    void rejectsMissingMsgtype() {
        client.post().uri("/api/webhooks/openobserve/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", Map.of("content", "hello")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("msgtype 不能为空");
    }

    @Test
    void rejectsEmptyBody() {
        client.post().uri("/api/webhooks/openobserve/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();
    }
}
