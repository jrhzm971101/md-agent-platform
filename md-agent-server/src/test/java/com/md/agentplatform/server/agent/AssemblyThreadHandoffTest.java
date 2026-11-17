package com.md.agentplatform.server.agent;

import org.junit.jupiter.api.Test;
import reactor.core.scheduler.NonBlocking;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 装配线程交接（{@link ConfigurableAgentBuilder#onAssemblyThread}）的防回归测试。
 *
 * <h2>它守的是哪次故障</h2>
 * 2026-09-24：页面「发布」出来的 agent 少了<b>全部</b> MCP 工具，启动期装配的却正常。
 * 原因是装配过程会调用框架内部的 {@code Mono#block()}（MCP 握手），而 Reactor 禁止在
 * 事件循环线程上 block；发布是同步 handler、正好跑在那条线程上，于是每个 MCP 都在
 * {@code DbMcpClientFactory.resolveAll} 里被逐条吞成 ERROR 日志，agent 却照样建得出来
 * —— 工具少了只在模型行为上体现，页面和接口都看不出来。
 *
 * <h2>为什么不用真 MCP</h2>
 * 这里不连任何网络端点（否则测试依赖外网、还会慢）。Reactor 判定「这条线程能不能 block」
 * 看的就是 {@link NonBlocking} 这个标记接口，而 Netty 的 {@code reactor-http-nio-N}
 * 线程正是通过实现它来声明禁止阻塞的 —— 用同一条规则复刻，等价且稳定。
 */
class AssemblyThreadHandoffTest {

    /** 复刻事件循环线程：Reactor 靠这个标记接口认定「此线程禁止阻塞」。 */
    private static final class EventLoopLikeThread extends Thread implements NonBlocking {
        private EventLoopLikeThread(Runnable body) {
            super(body, "test-event-loop");
            setDaemon(true);
        }
    }

    /** 在「事件循环线程」上跑 body，并把它内部抛出的异常与本线程同步出来。 */
    private static Throwable runOnEventLoopLikeThread(Runnable body) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new EventLoopLikeThread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join(10_000);
        assertFalse(thread.isAlive(), "被测线程应已结束（10s 内没结束说明装配卡住了）");
        return failure.get();
    }

    @Test
    void handsAssemblyOffWhenCallerIsEventLoopThread() throws Exception {
        AtomicReference<Thread> assemblyThread = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();

        Throwable failure = runOnEventLoopLikeThread(() ->
                result.set(ConfigurableAgentBuilder.onAssemblyThread(() -> {
                    // 断言写在回调里：失败会被交接逻辑原样带回调用线程（见下面的断言）
                    assertFalse(Schedulers.isInNonBlockingThread(),
                            "装配必须在允许阻塞的线程上跑，否则框架的 block() 会被 Reactor 拦掉");
                    assemblyThread.set(Thread.currentThread());
                    return "built";
                }, "demo-agent")));

        assertNull(failure, "事件循环线程上装配不该抛异常，但抛了: " + failure);
        assertEquals("built", result.get());
        assertNotSame(Thread.currentThread(), assemblyThread.get());
        assertFalse(assemblyThread.get() instanceof NonBlocking,
                "交接后的线程不能是事件循环线程");
    }

    @Test
    void runsAssemblyInlineWhenCallerMayBlock() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> assemblyThread = new AtomicReference<>();

        // JUnit / 启动期（main）都在这条路径上：不做多余的线程搬运，省一次上下文切换
        String result = ConfigurableAgentBuilder.onAssemblyThread(() -> {
            assemblyThread.set(Thread.currentThread());
            return "built";
        }, "demo-agent");

        assertEquals("built", result);
        assertSame(caller, assemblyThread.get(), "调用方本来就允许阻塞时不该换线程");
    }

    @Test
    void propagatesRuntimeExceptionUnchanged() throws Exception {
        // 装配失败必须原样抛回：AgentManager / ControlPlanePublishService 依赖异常类型与消息
        // 去决定「旧的运行时实例要不要保留」，包一层就全失效了
        IllegalStateException boom = new IllegalStateException("MCP workwx-mail-mcp 握手失败");

        Throwable failure = runOnEventLoopLikeThread(() ->
                ConfigurableAgentBuilder.onAssemblyThread(() -> {
                    throw boom;
                }, "demo-agent"));

        assertSame(boom, failure, "异常必须原样抛出，不能被包装成别的类型");
    }

    @Test
    void propagatesErrorUnchanged() throws Exception {
        AssertionError boom = new AssertionError("装配期断言");

        Throwable failure = runOnEventLoopLikeThread(() ->
                ConfigurableAgentBuilder.onAssemblyThread(() -> {
                    throw boom;
                }, "demo-agent"));

        assertSame(boom, failure);
    }

    /**
     * 并发的两次装配（页面「全量重载」就是这个形状）互不串线。
     * 守卫的是「用共享字段传结果」这种改法 —— 一旦有人把 AtomicReference 提成字段就会挂。
     */
    @Test
    void concurrentAssembliesDoNotCrossTalk() throws Exception {
        List<String> results = new CopyOnWriteArrayList<>();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String name = "agent-" + i;
            threads.add(new EventLoopLikeThread(() ->
                    results.add(ConfigurableAgentBuilder.onAssemblyThread(() -> name, name))));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertEquals(4, results.size());
        for (int i = 0; i < 4; i++) {
            org.junit.jupiter.api.Assertions.assertTrue(results.contains("agent-" + i),
                    "缺少 agent-" + i + " 的装配结果，说明并发装配串线了: " + results);
        }
    }
}