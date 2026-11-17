package com.md.agentplatform.server.trace.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 唤醒通道的两条契约。
 *
 * <p>这个类只有 30 行实现，但它跑在 <b>agent 线程</b>上，写错了会把观测系统的故障传导成业务故障
 * （publish 阻塞 = agent 变慢；publish 抛异常 = 整轮调用失败），所以两条都值得钉死。
 */
class TraceStreamNotifierTest {

    @Test
    @Timeout(30)
    void wakeupsAreDeliveredOnlyToTheRunTheyBelongTo() throws Exception {
        TraceStreamNotifier notifier = new TraceStreamNotifier();
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Disposable subscription = notifier.wakeups("run-a").subscribe(queue::add);
        try {
            notifier.publish("run-b");
            notifier.publish("run-a");

            assertThat(queue.poll(10, TimeUnit.SECONDS)).isEqualTo("run-a");
            // 别人家的唤醒不能把我叫醒：否则"任意一个 run 写了一条事件"会惊动所有打开着的页面，
            // 每个页面都去做一次翻译（O(run 长度)），白白烧 CPU
            assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            subscription.dispose();
        }
    }

    /**
     * 没人订阅时 publish 既不抛也不阻塞。
     *
     * <p>这是最常见的情况 —— 页面上根本没人在看。连续发 300 条（超过唤醒缓冲的 256）是为了
     * 把"缓冲满了以后会怎样"也走到：契约是"丢唤醒、不报错"，因为丢了的唤醒由兜底轮询补，
     * 而报错会一路冒到 agent 的执行链上。
     */
    @Test
    @Timeout(30)
    void publishWithoutSubscribersIsHarmless() {
        TraceStreamNotifier notifier = new TraceStreamNotifier();

        assertThatCode(() -> {
            for (int i = 0; i < 300; i++) {
                notifier.publish("run-" + i);
            }
            notifier.publish(null);
        }).doesNotThrowAnyException();
    }

    /** 退订之后 publish 也不能抛：页面切走时正好有一条事件写入，是最容易踩到的时序。 */
    @Test
    @Timeout(30)
    void publishAfterTheLastSubscriberLeftIsHarmless() {
        TraceStreamNotifier notifier = new TraceStreamNotifier();
        Disposable subscription = notifier.wakeups("run-a").subscribe();
        subscription.dispose();

        assertThatCode(() -> notifier.publish("run-a")).doesNotThrowAnyException();
    }
}
