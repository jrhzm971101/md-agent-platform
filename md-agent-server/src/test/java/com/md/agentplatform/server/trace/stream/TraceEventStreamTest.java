package com.md.agentplatform.server.trace.stream;

import com.md.agentplatform.server.config.AgentPlatformProperties;
import com.md.agentplatform.server.trace.AgentRunEvent;
import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 数据面的契约测试。
 *
 * <h2>一、为什么不用 StepVerifier</h2>
 * 本模块的测试依赖只有 spring-boot-starter-test（<b>不含 reactor-test</b>），而这条流又
 * <b>永远不会结束</b> —— StepVerifier 默认那套"等完成再断言"的写法在这里也不适用。
 * 直接在订阅端挂一个 BlockingQueue、用带超时的 poll 断言，更贴近"页面到底收到了什么"。
 *
 * <h2>二、必须钉死的三件事，以及为什么它们要分成不同的测试</h2>
 * <ol>
 *   <li><b>游标是权威</b>：afterSeq 之前的一条不重发、之后的一条不落；</li>
 *   <li><b>通知链路真的通</b>：把兜底轮询调到 60 秒，此时还能收到新事件，
 *       只可能是"写入即唤醒"起了作用；</li>
 *   <li><b>通知丢了也不会漏</b>：唤醒器压根没接（模拟通知丢失）时，兜底轮询必须兜住。</li>
 * </ol>
 * 第 2、3 条一定要分开测：合在一个测试里，"通知已经坏了、轮询兜住了"会表现成<b>通过</b>，
 * 而线上最怕的就是这种"看起来在实时推送、其实是 5 秒轮询"的静默降级。
 */
class TraceEventStreamTest {

    /** ① 订阅即读：游标 0 时把已有的全给出来，一条不少、顺序与 seq 一致。 */
    @Test
    @Timeout(30)
    void existingEventsAreEmittedImmediately() throws Exception {
        AgentTraceRegistry registry = new AgentTraceRegistry();
        AgentTraceRun run = runWithOneStage(registry);
        TraceEventStream stream = newStream(registry, new TraceStreamNotifier(), 60_000L);

        try (Feed feed = subscribe(stream, run.getRunId(), 0)) {
            List<AgentRunEvent> initial = take(feed, 3);
            assertThat(initial).extracting(AgentRunEvent::seq).containsExactly(1, 2, 3);
            assertThat(initial.get(0).type()).isEqualTo(AgentRunEvent.TYPE_RUN_STARTED);
        }
    }

    /** ② 游标语义：已经拿过的绝不重发，没拿过的一条不落。 */
    @Test
    @Timeout(30)
    void cursorSkipsEventsTheClientAlreadyHas() throws Exception {
        AgentTraceRegistry registry = new AgentTraceRegistry();
        AgentTraceRun run = runWithOneStage(registry);
        TraceEventStream stream = newStream(registry, new TraceStreamNotifier(), 60_000L);

        try (Feed feed = subscribe(stream, run.getRunId(), 2)) {
            assertThat(take(feed, 1)).extracting(AgentRunEvent::seq).containsExactly(3);
            // 1、2 不能再补一遍：前端虽然按 id 去重，但重复帧会让它的游标合并白跑一轮
            assertThat(feed.queue().poll(500, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    /**
     * ③ 通知链路：兜底轮询被调到 60 秒之后，新事件仍然立刻到 —— 只可能是唤醒器起作用了。
     *
     * <p>Registry 的唤醒器是字段注入，这里用 ReflectionTestUtils 手工接上：
     * 测试跑在 Spring 容器之外，没有容器来替我们做这件事。
     */
    @Test
    @Timeout(30)
    void newEventsArriveThroughTheNotifier() throws Exception {
        AgentTraceRegistry registry = new AgentTraceRegistry();
        TraceStreamNotifier notifier = new TraceStreamNotifier();
        ReflectionTestUtils.setField(registry, "streamNotifier", notifier);
        AgentTraceRun run = runWithOneStage(registry);
        TraceEventStream stream = newStream(registry, notifier, 60_000L);

        try (Feed feed = subscribe(stream, run.getRunId(), 0)) {
            // 先把"订阅即读"那 3 条取干净：取完之后还有新事件，就只可能来自通知
            assertThat(take(feed, 3)).extracting(AgentRunEvent::seq).containsExactly(1, 2, 3);

            registry.beginStage(run, AgentTraceEvent.PHASE_ACTING, "工具调用",
                    run.getStartedAtMs() + 10, Map.of());

            AgentRunEvent live = feed.queue().poll(10, TimeUnit.SECONDS);
            assertThat(live).as("写入事件之后没有被唤醒（那就会等到 60 秒后的兜底轮询才到）").isNotNull();
            assertThat(live.seq()).isEqualTo(4);
            // 断言类型是为了确认拿到的是"新写进去的那一条"，而不是历史里的某一条：
            // 不带工具名时动作阶段是 acting.*（带工具名会变成 tool.*，那是翻译器的职责，由它的测试去管）
            assertThat(live.type()).isEqualTo(AgentRunEvent.TYPE_ACTING_STARTED);
        }
    }

    /** ④ 通知不是唯一来源：唤醒器没接上时，兜底轮询必须把新事件带回来（配置的 200ms 会被实现抬到 1 秒）。 */
    @Test
    @Timeout(30)
    void eventsStillArriveWhenWakeupsAreLost() throws Exception {
        AgentTraceRegistry registry = new AgentTraceRegistry();
        AgentTraceRun run = runWithOneStage(registry);
        TraceEventStream stream = newStream(registry, new TraceStreamNotifier(), 200L);

        try (Feed feed = subscribe(stream, run.getRunId(), 0)) {
            assertThat(take(feed, 3)).extracting(AgentRunEvent::seq).containsExactly(1, 2, 3);

            registry.beginStage(run, AgentTraceEvent.PHASE_ACTING, "工具调用",
                    run.getStartedAtMs() + 10, Map.of());

            AgentRunEvent later = feed.queue().poll(10, TimeUnit.SECONDS);
            assertThat(later).as("兜底轮询没有把新事件带回来").isNotNull();
            assertThat(later.seq()).isEqualTo(4);
        }
    }

    /**
     * ⑤ run 不存在时：不发事件、也不报错。
     *
     * <p>"是否 404"是 HTTP 层的事（见 AgentTraceController）：数据面的职责只是"没东西可发"。
     * 它还有一层作用 —— run 被保留期清理掉之后，页面上的那条流会安静地待着，而不是把错误糊到已经渲染好的链路上。
     */
    @Test
    @Timeout(30)
    void unknownRunEmitsNothingAndDoesNotFail() throws Exception {
        AgentTraceRegistry registry = new AgentTraceRegistry();
        TraceEventStream stream = newStream(registry, new TraceStreamNotifier(), 60_000L);

        try (Feed feed = subscribe(stream, "no-such-run", 0)) {
            assertThat(feed.queue().poll(1, TimeUnit.SECONDS)).isNull();
        }
    }

    // ==================================================================================
    // 测试脚手架
    // ==================================================================================

    /** 造一个 run：根节点 + 一个已收口的推理阶段 —— 一共 3 条事件，seq 恰好是 1、2、3。 */
    private static AgentTraceRun runWithOneStage(AgentTraceRegistry registry) {
        AgentTraceRun run = registry.start("t_agent", AgentTraceRun.AGENT_KIND_HARNESS, "s1", "u1", Map.of());
        int stage = registry.beginStage(run, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理",
                run.getStartedAtMs() + 1, Map.of());
        registry.endStage(run, stage, 25L, Map.of("content", "hi"), null);
        return run;
    }

    private static TraceEventStream newStream(AgentTraceRegistry registry, TraceStreamNotifier notifier,
                                              long recoveryIntervalMs) {
        AgentPlatformProperties properties = new AgentPlatformProperties();
        properties.setTraceStreamRecoveryIntervalMs(recoveryIntervalMs);
        return new TraceEventStream(registry, notifier, properties);
    }

    private static Feed subscribe(TraceEventStream stream, String runId, int afterSeq) {
        BlockingQueue<AgentRunEvent> queue = new LinkedBlockingQueue<>();
        Disposable subscription = stream.stream(runId, afterSeq).subscribe(queue::add);
        return new Feed(queue, subscription);
    }

    /** 按顺序取 count 条；超时算失败（比"等流结束"更早、更明确地暴露问题）。 */
    private static List<AgentRunEvent> take(Feed feed, int count) throws InterruptedException {
        List<AgentRunEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AgentRunEvent event = feed.queue().poll(10, TimeUnit.SECONDS);
            assertThat(event).as("第 " + (i + 1) + " 条事件没有在 10 秒内到达").isNotNull();
            events.add(event);
        }
        return events;
    }

    /**
     * 一条订阅：接收队列 + 取消句柄。
     *
     * <p>做成 AutoCloseable 是为了让每个测试都用 try-with-resources 收尾 —— 这条流不会自己结束，
     * 忘了 dispose 的话 {@code Flux.interval} 会在测试结束后继续跑（IDEA 里表现为"测试跑完了线程还在"）。
     */
    private record Feed(BlockingQueue<AgentRunEvent> queue, Disposable subscription) implements AutoCloseable {
        @Override
        public void close() {
            subscription.dispose();
        }
    }
}
