package com.md.agentplatform.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Web 技术栈护栏：确保本模块真的跑在 WebFlux 上，而不是被静默降级回 Spring MVC。
 *
 * <p>为什么需要这个断言：当 {@code spring-boot-starter-web} 与
 * {@code spring-boot-starter-webflux} 同时出现在 classpath 上时，Spring Boot 会优先
 * 判定为 SERVLET 并启用 MVC，同时把 WebFlux 的自动配置整体关掉 ——
 * <b>不报任何错、不留任何日志</b>。表现是编译通过、启动正常、端口能通，
 * 但任何返回 {@code Flux} 的接口都会退化，SSE 永远推不出数据。
 * 这类失败在运行期极难定位，所以在构建阶段钉死。
 *
 * <p>做法是直接问 Spring Boot 自己：{@code SpringApplication} 在构造时就会按 classpath
 * 推导 web 类型，这里断言推导结果，与运行期走的是同一条判定逻辑。
 * 不启动容器，因此不依赖数据库 / Nacos / Redis 等外部服务即可执行。
 */
class WebFluxStackTest {

    @Test
    void springBootMustDeduceReactiveWebApplication() {
        SpringApplication application = new SpringApplication(WebFluxStackTest.class);
        assertEquals(WebApplicationType.REACTIVE, application.getWebApplicationType(),
                "Spring Boot 把应用推导成了 SERVLET —— 说明有 servlet 容器依赖混入"
                        + "（通常是 spring-boot-starter-web，或其传递依赖 spring-webmvc / tomcat-embed-core），"
                        + "WebFlux 已被静默禁用");
    }

    @Test
    void servletStackMustNotBeOnClasspath() {
        assertFalse(isPresent("org.springframework.web.servlet.DispatcherServlet"),
                "spring-webmvc 不应出现在 classpath 上");
        assertFalse(isPresent("org.apache.catalina.startup.Tomcat"),
                "tomcat-embed-core 不应出现在 classpath 上");
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, WebFluxStackTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
