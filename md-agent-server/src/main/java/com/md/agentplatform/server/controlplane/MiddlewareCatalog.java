package com.md.agentplatform.server.controlplane;

import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 中间件只读目录 —— 扫容器里实现了 {@link MiddlewareBase} 的 bean。
 *
 * <h2>为什么中间件和 tool 一样<b>不</b>落库</h2>
 * 中间件是<b>代码对象</b>：它带着构造参数、依赖注入、生命周期，不是一个能描述成几列的配置。
 * 落库只能存一个 bean 名，而"bean 名"这种东西在代码里改名就会失效 ——
 * 存进去的是一份会腐烂的引用。所以保持"扫容器"，DB 里只存 agent 引用了哪些 bean 名，
 * 校验时拿这份目录去比对（设计文档 §3.1 的只读目录一节）。
 *
 * <h2>与 {@code AgentManager.listAvailableTools()} 的分工</h2>
 * 那个扫 {@code @Tool} 注解（工具方法级），这个扫类型（中间件类级）。两者都只读、都不落库，
 * 但一个 agent 引用工具时用的是<b>方法上的工具名</b>，引用中间件时用的是 <b>bean 名</b>，
 * 所以两边的"名字"来源不同，不能合并成一个方法。
 */
@Component
public class MiddlewareCatalog {

    @Autowired
    private ApplicationContext applicationContext;

    /** 全部中间件 bean，按 bean 名排序（页面上的顺序要稳定，否则每次刷新都在跳）。 */
    public List<MiddlewareMeta> list() {
        List<MiddlewareMeta> result = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception ignored) {
                continue; // 惰性/未就绪的 bean，跳过
            }
            if (bean instanceof MiddlewareBase) {
                Class<?> type = bean.getClass();
                result.add(new MiddlewareMeta(beanName, type.getSimpleName(), type.getName()));
            }
        }
        result.sort(Comparator.comparing(MiddlewareMeta::beanName));
        return result;
    }

    /**
     * 一个中间件 bean。
     *
     * @param beanName   页面保存时要写进 {@code md_agent_ref.middleware} 的那个名字
     * @param simpleName 类简名（日志/展示用）
     * @param className  全限定类名（用来判断"这是本项目的还是框架内置的"）
     */
    public record MiddlewareMeta(String beanName, String simpleName, String className) {}
}
