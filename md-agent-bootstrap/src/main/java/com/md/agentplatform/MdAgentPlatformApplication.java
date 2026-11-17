package com.md.agentplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Md-Agent-Platform 入口。
 *
 * <p>依赖方向：bootstrap → server → model，单向无倒置。业务组件
 * （messaging / agent / tool / service / config）位于 server 模块；
 * 本模块只承担两件事：启动入口 + {@code controller} 包下的全部 HTTP 控制器
 * （controller/agent、controller/trace 等）。
 * 控制器集中在此处，server 模块因此不再出现任何 Web 注解，"HTTP 边界"只有一个落点。
 */
@SpringBootApplication
@EnableAsync
public class MdAgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(MdAgentPlatformApplication.class, args);
    }
}
