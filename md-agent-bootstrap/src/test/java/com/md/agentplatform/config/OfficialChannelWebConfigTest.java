package com.md.agentplatform.config;

import io.agentscope.extensions.channel.feishu.FeishuCallbackController;
import io.agentscope.extensions.channel.gitlab.GitLabWebhookController;
import io.agentscope.extensions.channel.wecom.WeComCallbackController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 官方渠道回调 Controller 的「装配契约」测试。
 *
 * <p>为什么需要它：官方 jar 不带任何 Spring 自动装配（理由见
 * {@link OfficialChannelWebConfig}），所以「这些 Controller 是不是 Bean」完全取决于
 * 我们这一份 {@code @ComponentScan}。而它失效时的现象是<b>静默的</b>——
 * 渠道照常装配成功、页面照常显示「运行中」，只有平台把消息回调过来时是 404，
 * 服务端连一行请求日志都没有。靠人肉联调很容易漏，所以在这里钉死。
 *
 * <p>不启整个应用容器（不连 MySQL / 网关 / 模型）：只拿这张配置类去扫描，
 * 因此能在 CI 里稳定跑，也不会因为库连不上而变红。
 */
class OfficialChannelWebConfigTest {

    /** 已发布 2.0.3 里存在回调 Controller 的三个平台（钉钉 2.0.3 是纯 stream，没有回调类）。 */
    private static final List<Class<?>> CALLBACK_CONTROLLERS = List.of(
            WeComCallbackController.class,
            FeishuCallbackController.class,
            GitLabWebhookController.class);

    /**
     * 三个官方回调 Controller 都必须被注册成 Bean。
     *
     * <p>这条断言失败，等于「所有官方渠道都收不到消息」：装配会成功、状态会显示运行中，
     * 但平台回调打进来是 404。
     */
    @Test
    void registersAllOfficialCallbackControllers() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(OfficialChannelWebConfig.class)) {
            for (Class<?> controller : CALLBACK_CONTROLLERS) {
                assertThat(ctx.getBean(controller))
                        .as("%s 没被注册成 Bean —— 检查 OfficialChannelWebConfig 的 @ComponentScan",
                                controller.getSimpleName())
                        .isNotNull();
            }
        }
    }

    /**
     * 这几个类必须真的是 Web 处理器，且路径落在 {@code /api/channels/} 下。
     *
     * <p>这条不是测官方代码，而是把「我们依赖官方路径前缀」这个假设写下来：
     * 平台后台（企微/飞书/GitLab）里填的回调地址就是照这个前缀给的。
     * 上游哪天改了前缀，这里先红，比等线上回调 404 再排查便宜得多。
     */
    @Test
    void callbackControllersKeepTheExpectedPathPrefix() {
        for (Class<?> controller : CALLBACK_CONTROLLERS) {
            assertThat(controller.isAnnotationPresent(RestController.class))
                    .as("%s 应当是 @RestController", controller.getSimpleName())
                    .isTrue();

            RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
            assertThat(mapping)
                    .as("%s 应当带 @RequestMapping", controller.getSimpleName())
                    .isNotNull();
            assertThat(mapping.value())
                    .as("%s 的回调路径前缀变了，平台后台填的回调地址要跟着改",
                            controller.getSimpleName())
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).startsWith("/api/channels/"));
        }
    }
}
