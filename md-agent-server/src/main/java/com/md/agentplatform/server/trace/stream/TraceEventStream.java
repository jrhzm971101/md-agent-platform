package com.md.agentplatform.server.trace.stream;

import com.md.agentplatform.server.config.AgentPlatformProperties;
import com.md.agentplatform.server.trace.AgentRunEvent;
import com.md.agentplatform.server.trace.AgentRunEventTranslator;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行链路事件流 —— SSE 端点的数据面。本类只产出<b>领域事件</b>（{@link AgentRunEvent}），
 * 不认识 HTTP、SSE 帧、心跳，那些是 bootstrap 层的事。
 *
 * <h2>一、结构照搬官方 {@code SessionEventLog#subscribe(sessionId, afterSeq)}</h2>
 * 官方那一版是：{@code Flux.merge(马上读一次, 低频兜底轮询, 通知唤醒)} → {@code concatMap(按游标读库)}
 * → {@code concatMapIterable(推进游标)}。这里一一对应，只把"读库"换成"读 Registry"（内存优先、不足回库）。
 *
 * <p>为什么不是"把通知里的事件直接发给前端"：
 * <ol>
 *   <li>通知可能丢（订阅者缓冲满、订阅比写入晚一步），一旦丢了，那条事件就永远补不回来 ——
 *       而前端的 {@code mergeContiguousEvents} 只放行从游标起的<b>连续前缀</b>，
 *       缺一个号就永久卡在那里，后面的事件全部积压不渲染；</li>
 *   <li>通知可能重，而重读是幂等的：游标没推进就一条都不发。</li>
 * </ol>
 * 换句话说：<b>通知负责"什么时候读"，游标负责"读到哪"，两者不能混。</b>
 *
 * <h2>二、三条唤醒来源，缺一不可</h2>
 * <ul>
 *   <li><b>订阅时立即读一次</b>：否则页面已经拿到的历史与"从现在起的增量"之间会空一段，
 *       直到下一条事件发生才补上；</li>
 *   <li><b>通知唤醒</b>：正常路径，事件一写入就推，延迟是毫秒级；</li>
 *   <li><b>低频兜底轮询</b>：通知丢了的保险，也是"run 已经不在内存里"（被环形缓冲淘汰，
 *       只能回 MySQL 读）时唯一的发现途径。它让前端不必用短轮询去补洞。</li>
 * </ul>
 *
 * <h2>三、两个容易被"优化"掉、但必须保留的接线</h2>
 * <ul>
 *   <li>{@code onBackpressureLatest()} + {@code concatMap}：唤醒信号只需要"最新有没有人写"，
 *       积压 100 个唤醒和积压 1 个等价。用 {@code concatMap}（并发 1）而不是 {@code flatMap}：
 *       <b>读必须串行</b>，否则两次读会各自读到同一批事件、各自推进同一个游标，发出重复帧。</li>
 *   <li>{@code subscribeOn(Schedulers.boundedElastic())}：读 Registry 在内存未命中时是<b>阻塞</b>的
 *       数据库查询。项目跑在 Netty 上，把阻塞调用留在事件循环线程里会拖住同一条线程上的所有连接 ——
 *       一个观测页面的慢查询能拖慢业务接口。放到弹性线程池，专属这类"我就是要阻塞"的任务。</li>
 * </ul>
 *
 * <h2>四、本流不会自己结束</h2>
 * 只要客户端还在，流就一直在（等下一次唤醒）。结束只有两种方式：客户端断开，或服务端关停。
 * "run 跑完了就关流"会带来一个坏后果：前端的重连逻辑（官方同款退避重连）会在 run 结束的瞬间
 * 按 1s→2s→…→30s 不停重连一个已经结束的 run。所以收口信息本身也是事件
 * （{@code run.completed} / {@code error.raised}），由前端按事件内容判断"不用再等了"。
 *
 * <h2>五、已知代价</h2>
 * 每次读都要把整个 run 重新翻译一遍（{@link AgentRunEventTranslator#translate} 是纯函数，
 * 树的 path 依赖整棵树的形态）。一次 run 几十到几百条事件，代价是微秒级；
 * 换来的是"没有需要失效的缓存"。真正需要留意的是兜底轮询的间隔别调到 1 秒以下（见配置项）。
 */
@Component
public class TraceEventStream {

    private final AgentTraceRegistry traceRegistry;
    private final TraceStreamNotifier notifier;

    /**
     * 兜底轮询间隔（毫秒）。
     *
     * <p>取配置而不是常量：读一个没有内存副本的 run（每次 2 条 SQL 回库）与读内存是两种量级的成本，
     * 部署方需要能按"页面开着不管多久"去调它。
     */
    private final long recoveryIntervalMs;

    @Autowired
    public TraceEventStream(AgentTraceRegistry traceRegistry, TraceStreamNotifier notifier,
                            AgentPlatformProperties properties) {
        this.traceRegistry = traceRegistry;
        this.notifier = notifier;
        // 兜底轮询最短 1 秒：它是保险，不是主通道。把保险调得比主通道（通知）还频繁是纯浪费。
        this.recoveryIntervalMs = Math.max(1_000L, properties.getTraceStreamRecoveryIntervalMs());
    }

    /**
     * 订阅一次 run 的增量事件，从 {@code afterSeq}（不含）之后开始。
     *
     * @param afterSeq 客户端游标：已经拿到的最大 seq。0 表示"我什么都没有，把已有的都给我"。
     *                 客户端断线重连时带的就是它（HTTP 头 Last-Event-ID）。
     * @return 事件流。run 不存在时既不报错也不结束，只是永远不会产出事件
     *         —— 是否 404 由 HTTP 层在订阅前判定（见 AgentTraceController）。
     */
    public Flux<AgentRunEvent> stream(String runId, int afterSeq) {
        // 游标必须"每个订阅一份"：两个页面同时看一个 run，各自的游标独立推进。
        AtomicLong cursor = new AtomicLong(Math.max(0, afterSeq));

        Flux<Long> wakeups = Flux.merge(
                        // ① 订阅即读：先把"客户端游标"与"当前状态"之间的差补齐
                        Flux.just(0L),
                        // ② 兜底轮询：保证通知不可靠时最终仍能一致
                        Flux.interval(Duration.ofMillis(recoveryIntervalMs)),
                        // ③ 通知唤醒：元素值无意义，只用来触发一次读
                        notifier.wakeups(runId).map(ignored -> 0L))
                // 唤醒可以合并：只要还有一次未处理的唤醒，就不需要再攒更多
                .onBackpressureLatest();

        return wakeups
                // AtomicLong 是为了"读写都原子"，而 seq 本身是 int（AgentTraceEvent.seq() 的宽度），
                // 取值时窄化回 int：这样 readAfter 的签名与它消费的字段类型一致，不用在两头各转一次。
                .concatMap(ignored -> Mono.fromCallable(() -> readAfter(runId, (int) cursor.get()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .concatMapIterable(events -> {
                    // 先推进游标、再交给下游发射：万一发射途中下游取消（前端关了页面），
                    // 也不会出现"事件已经发出去、游标还停在旧值"的错位。
                    if (!events.isEmpty()) {
                        cursor.set(events.get(events.size() - 1).seq());
                    }
                    return events;
                });
    }

    /**
     * 读出游标之后的事件。运行在弹性线程池上，允许阻塞。
     *
     * <p>读的是 {@link AgentTraceRegistry#get(String)}（内存优先、未命中回 MySQL），
     * 所以"run 被环形缓冲淘汰之后页面还能接着看"这件事，本类一行额外代码都不用写。
     */
    private List<AgentRunEvent> readAfter(String runId, int afterSeq) {
        AgentTraceRun run = traceRegistry.get(runId);
        if (run == null) {
            return List.of();
        }
        List<AgentRunEvent> all = AgentRunEventTranslator.translate(run);
        return all.subList(AgentRunEventTranslator.indexAfterSeq(all, afterSeq), all.size());
    }
}
