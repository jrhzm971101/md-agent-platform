package com.md.agentplatform.server.trace.stream;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 运行链路事件的"叫醒服务"—— 通知<b>只唤醒读的人，不承载数据</b>。
 *
 * <h2>一、为什么通知里不带事件数据</h2>
 * 订阅者（SSE 端点）被叫醒之后，永远从自己的游标重新读一次 Registry，而不是直接消费通知里的内容。
 * 这个取舍换来两条重要性质：
 * <ul>
 *   <li><b>通知可以漏，也可以重</b>：漏了由低频兜底轮询补上（见 {@link TraceEventStream}）；
 *       重了只是多读一次，而读是幂等的（游标没动就什么都不发）。
 *       于是这条通道不需要任何可靠性保证 —— 用"可能丢"的通道去送"不能丢"的数据是设计事故，
 *       用它叫醒读的人则完全安全。</li>
 *   <li><b>订阅方与写入方解耦</b>：写入方（agent 线程）只调用一次 {@link #publish(String)}，
 *       不关心有没有人在看、有几个人在看。</li>
 * </ul>
 *
 * <h2>二、只做进程内通知，没有跨实例广播</h2>
 * 官方 agentscope-service 的同类组件（{@code SessionEventNotifier}）在 PostgreSQL 上额外挂了
 * LISTEN/NOTIFY，用来唤醒<b>别的副本</b>里的订阅者 —— 因为它的写入可能发生在 A 副本、观看的人在 B 副本。
 * 本项目是单实例部署、事件也只在进程内产生，所以这里只用一个进程内 {@link Sinks}：
 * 再加一层数据库通知，只多出一份要维护的连接与重连逻辑，换不来任何正确性。
 *
 * <p>哪天真要水平扩容，缺口就在<b>本类</b>：让 {@link #publish(String)} 再往一个共享通道投一份
 * （MySQL 没有 LISTEN/NOTIFY，可用 Redis pub/sub），读侧一行都不用改 —— 因为读侧本来就不信任通知内容。
 */
@Component
public class TraceStreamNotifier {

    /**
     * 唤醒信号的缓冲上限。
     *
     * <p>值本身不敏感：唤醒的语义是"去看一眼"，攒下 256 个"看一眼"和攒 1 个效果完全一样
     * （读一次就是最新状态）。设上限只是为了订阅者卡死时不会把内存撑爆。
     */
    private static final int WAKEUP_BUFFER = 256;

    /**
     * 唤醒通道：多播、不重放、不阻塞。
     *
     * <p>载荷用 runId 而不是"某个事件"：订阅者要按 run 过滤，而一个订阅者只关心一个 run。
     * {@code autoCancel=false} 的意思是"最后一个订阅者退订了也别把这个 sink 关掉" ——
     * 页面上从一个 run 切到另一个 run，旧订阅退、新订阅上，sink 本身必须一直活着。
     */
    private final Sinks.Many<String> wakeups =
            Sinks.many().multicast().onBackpressureBuffer(WAKEUP_BUFFER, false);

    /**
     * 唤醒正在看这个 run 的订阅者。
     *
     * <p>本方法在 <b>agent 线程</b>上被调用，所以绝不能阻塞：{@code tryEmitNext} 是同步非阻塞的，
     * 缓冲打满时立刻返回失败而不是等待 —— 为了一个观测页面把 agent 的响应拖慢是本末倒置。
     *
     * <p>返回值刻意丢弃且不记 error 级日志：漏一次唤醒由兜底轮询补上，而"没人看页面"时
     * 每次 publish 都会失败，记 warn 只会让日志被刷满、把真正的问题淹掉。
     */
    public void publish(String runId) {
        if (runId == null) {
            return;
        }
        wakeups.tryEmitNext(runId);
    }

    /**
     * 订阅某个 run 的唤醒信号。
     *
     * <p>返回流的元素值（runId）没有意义，拿到就该去重读一次事件 —— 这是本类与"数据通道"的边界。
     */
    public Flux<String> wakeups(String runId) {
        return wakeups.asFlux().filter(runId::equals);
    }
}
