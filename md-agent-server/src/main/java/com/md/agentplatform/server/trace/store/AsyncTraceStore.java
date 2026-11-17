package com.md.agentplatform.server.trace.store;

import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.store.entity.AgentRunEntity;
import com.md.agentplatform.server.trace.store.entity.AgentRunEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 归档写入器 —— 把"写库"从 agent 线程上摘下来：调用线程只做「取快照 + 入队」，
 * 单消费者线程负责攒批、JSON 编码与 SQL。
 *
 * <h2>一、为什么必须是「有界队列 + 单消费者 + 批量刷盘」</h2>
 * <ul>
 *   <li><b>异步</b>：观测数据不值得让 agent 线程等一次数据库往返。企微一次问答可能有几十条事件，
 *       同步写就是几十次往返，全部打在用户感知的延迟上。</li>
 *   <li><b>有界</b>：无界队列在数据库变慢时就是一个会吃光堆内存的漏洞。
 *       队列满的处置是<b>丢尾部并告警</b>，不是阻塞生产者 —— 丢一条观测数据只让页面少一个节点，
 *       而阻塞会让一次真实的 agent 调用卡住，代价完全不对等。</li>
 *   <li><b>单消费者</b>：多消费者并发写同一批 run 会让事件行乱序落库（seq 明明有序，
 *       插入顺序却乱了），而页面靠 (run_id, seq) 排序读 —— 顺序错了页面就错。
 *       顺带的好处是不用任何锁：写入串行，天然没有竞争。</li>
 *   <li><b>批量</b>：一次 {@code drainTo} 拿到多少就写多少，把 N 次往返压成 1 次。
 *       实测粒度见配置项 {@code agent-platform.trace-archive-batch-size}。</li>
 * </ul>
 *
 * <h2>二、为什么不用 Redis / MQ 中转</h2>
 * 生产者与消费者在<b>同一个 JVM</b>里，中间加一跳反而引入新的失败模式（Redis 挂了、网络抖了、
 * 序列化不兼容），却并没有解决真正的问题（进程被 kill 时队列里的数据照样丢）。
 * 真正需要跨进程缓冲时再换，那时代价与收益才匹配。
 *
 * <h2>三、顺序是怎么保住的</h2>
 * FIFO 队列 + 单消费者，只能保证"入队顺序 = 出队顺序"。但攒批会打乱它：
 * 如果先把一批里的 run 快照全写了、再写事件，那么这一批里的 run 收口快照就会
 * 跑到它自己的事件前面去（页面会看到"已完成但没有事件"的瞬间）。
 * 所以 {@link #flush} 是<b>按顺序扫描、遇到非事件任务就先把已攒的事件写掉</b> ——
 * 批量化只发生在"连续的事件"之间，语义顺序完全不被打乱。
 *
 * <h2>四、clear 为什么要走队列</h2>
 * "清空"如果直接打数据库，队列里还排着的旧事件会在清空之后才落库，表现为
 * "点了清空、刷新又冒出来几条"。所以清空也作为一个任务入队，与其它写入共享同一条顺序线。
 */
public class AsyncTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(AsyncTraceStore.class);

    /** 出队等待上限：既不至于空转烧 CPU，也不会让"队列里只有一条"时干等太久。 */
    private static final long POLL_TIMEOUT_MS = 200L;
    /** 关停时最多等多久把队列排空 —— 超时就放弃剩余任务（观测数据，宁可丢也不拖住进程退出）。 */
    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10_000L;

    /** 下游：真正干活的存储实现（生产环境是 {@link MyBatisTraceStore}）。 */
    private final TraceStore delegate;

    /**
     * 有界队列。用 {@link LinkedBlockingQueue} 而不是 ArrayBlockingQueue：
     * 唯一区别是锁的粒度，而这里只有一个消费者、生产者也不多，构造成本也无关紧要，
     * 选它只是因为 {@code drainTo} 的语义最直白。
     */
    private final BlockingQueue<Task> queue;
    private final int batchSize;
    /** 队列容量，只用于日志与测试断言 —— 队列满了无法从 queue.size() 反推容量。 */
    private final int queueCapacity;

    /** 消费者线程。有名字，便于线程转储时一眼认出是谁在写库。 */
    private final ExecutorService consumer;

    /** 关停闸：置 false 后不再接受新任务，消费者把队列排空即退出。 */
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    /** 丢弃计数。丢一条只记日志不够 —— 页面会静默少一个节点，必须有个可观测的累计值。 */
    private final AtomicLong droppedTasks = new AtomicLong();

    public AsyncTraceStore(TraceStore delegate, int queueCapacity, int batchSize) {
        this.delegate = delegate;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.queue = new LinkedBlockingQueue<>(this.queueCapacity);
        this.batchSize = Math.max(1, batchSize);
        // 单线程：写入必须串行（见类注释第一节）。
        // daemon = true：本线程不该成为阻止 JVM 退出的理由；正常关停由 @PreDestroy 负责刷盘。
        this.consumer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "trace-archive-writer");
            thread.setDaemon(true);
            return thread;
        });
        this.consumer.execute(this::consumeLoop);
    }

    // ==================================================================================
    // 写：调用线程只做"取快照 + 入队"
    // ==================================================================================

    @Override
    public void saveRun(AgentTraceRun run) {
        if (run == null) {
            return;
        }
        // 快照必须在调用线程上取（AgentRunEntity.of 的注释解释了为什么），入队的只是行模型。
        offer(new SaveRun(AgentRunEntity.of(run)));
    }

    @Override
    public void appendEvent(AgentTraceRun run, AgentTraceEvent event) {
        if (run == null || event == null) {
            return;
        }
        offer(new AppendEvent(List.of(AgentRunEventEntity.of(run.getRunId(), event))));
    }

    // ==================================================================================
    // 写（行级）：同样入队，与领域写入共享同一条顺序线
    // ==================================================================================

    @Override
    public void persistRun(AgentRunEntity entity) {
        offer(new SaveRun(entity));
    }

    @Override
    public void insertEvents(List<AgentRunEventEntity> entities) {
        if (entities == null) {
            return;
        }
        if (entities.isEmpty()) {
            return;
        }
        // 一个任务带一批，而不是拆成 N 个任务：调用方已经攒好批时，拆开只是白白占用 N 个队列名额
        // （队列是满则丢弃的，占名额等于降低可承受的突发量），合并只会发生在 drain 之后，结果一样。
        offer(new AppendEvent(List.copyOf(entities)));
    }

    @Override
    public void clearAll() {
        offer(new Clear());
    }

    // ==================================================================================
    // 读：同步直通下游
    // ==================================================================================
    // 读路径不能经过队列：它承担的是"内存没有就查库补齐"，调用方（Registry）在
    // 阻塞式查询里等结果，异步化只会让它拿到 null。

    @Override
    public AgentTraceRun findRun(String runId) {
        return delegate.findRun(runId);
    }

    @Override
    public List<AgentTraceRun> findRecent(int limit) {
        return delegate.findRecent(limit);
    }

    @Override
    public int deleteOlderThan(long cutoffMs) {
        // 保留期清理直接打下游：它是低频的定时维护动作，不需要与实时写入争队列，
        // 而且清理的是"很久以前的 run"，与队列里排队的"刚刚发生的 run"没有交集。
        return delegate.deleteOlderThan(cutoffMs);
    }

    // ==================================================================================
    // 消费者
    // ==================================================================================

    /**
     * 消费循环：一直跑到"关停闸已放下 且 队列已排空"。
     *
     * <p>循环体刻意不抛异常：任何一条脏数据导致的异常如果逃逸，单线程执行器就会
     * 静默地再无消费者（Executors 的 submit 会吞掉后续任务），表现是"观测页面从某一刻起
     * 再也没有新数据"，而日志里只有一次堆栈。所以每个下游调用都被 {@code quiet*} 系列包住。
     */
    private void consumeLoop() {
        List<Task> batch = new ArrayList<>(batchSize);
        while (accepting.get() || !queue.isEmpty()) {
            try {
                Task first = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                // -1：first 已经占了一个名额。
                queue.drainTo(batch, batchSize - 1);
                flush(batch);
            } catch (InterruptedException e) {
                // 关停时会走到这里（shutdownNow 中断）。恢复中断标记、跳出循环即可 ——
                // 剩余任务由 shutdown() 的宽限等待负责，不在中断路径上做重活。
                Thread.currentThread().interrupt();
                log.warn("trace 归档线程被中断，剩余待写任务 {} 条", queue.size());
                return;
            } catch (Throwable t) {
                // 兜底：队列/批处理层面的意外（例如 OOM 之外的 RuntimeException）。
                log.error("trace 归档批次处理失败，丢弃本批 {} 条", batch.size(), t);
            }
        }
        log.info("trace 归档线程退出，累计丢弃 {} 条", droppedTasks.get());
    }

    /**
     * 保序刷盘：顺序扫描，攒住连续的事件，遇到非事件任务就先落掉已攒的事件。
     *
     * <p>为什么连 run 快照也不攒批：run 行一次调用只写 3 次，攒批省不下什么，
     * 反而要多一份"取最后一条快照"的逻辑。
     */
    private void flush(List<Task> tasks) {
        List<AgentRunEventEntity> pendingEvents = new ArrayList<>();
        for (Task task : tasks) {
            if (task instanceof AppendEvent append) {
                pendingEvents.addAll(append.entities());
                continue;
            }
            // 非事件任务：先落地已攒的事件，保证"事件一定落在它前后两个 run 快照之间"。
            flushEvents(pendingEvents);
            if (task instanceof SaveRun save) {
                quietPersistRun(save.entity());
            } else if (task instanceof Clear) {
                quietClear();
            }
        }
        flushEvents(pendingEvents);
    }

    /** 写掉一批事件并把缓冲清空；空批直接跳过（不发无意义的空 batch）。 */
    private void flushEvents(List<AgentRunEventEntity> pendingEvents) {
        if (pendingEvents.isEmpty()) {
            return;
        }
        List<AgentRunEventEntity> copy = List.copyOf(pendingEvents);
        pendingEvents.clear();
        try {
            delegate.insertEvents(copy);
        } catch (Throwable t) {
            log.error("trace 事件批量落库失败，本批 {} 条已丢弃（run={}..{})",
                    copy.size(), copy.get(0).getRunId(), copy.get(copy.size() - 1).getRunId(), t);
        }
    }

    private void quietPersistRun(AgentRunEntity entity) {
        try {
            delegate.persistRun(entity);
        } catch (Throwable t) {
            log.error("trace run 落库失败，已丢弃（run={}）", entity.getRunId(), t);
        }
    }

    private void quietClear() {
        try {
            delegate.clearAll();
        } catch (Throwable t) {
            log.error("trace 归档清空失败", t);
        }
    }

    // ==================================================================================
    // 入队与关停
    // ==================================================================================

    /**
     * 入队。<b>只用 offer（非阻塞）</b>，绝不用 put（阻塞）：
     * 队列满说明数据库已经跟不上，此刻阻塞生产者等于把观测系统的故障传导成业务故障。
     * 丢尾部 + 计数告警是刻意的取舍，见类注释第一节。
     */
    private void offer(Task task) {
        if (!accepting.get()) {
            droppedTasks.incrementAndGet();
            return;
        }
        if (queue.offer(task)) {
            return;
        }
        long dropped = droppedTasks.incrementAndGet();
        // 告警但不刷屏：队列满时每秒可能丢几百条，逐条打日志会把日志文件挤爆并掩盖真正的错误。
        // 第一次必打，之后每 1000 条打一次，其余靠累计值（日志里能算出总丢弃量）。
        if (dropped == 1L || dropped % 1000L == 0L) {
            log.error("trace 归档队列已满（容量 {}），开始丢弃观测数据，累计丢弃 {} 条 ——"
                            + " 通常是数据库写入变慢，请检查 md_agent_run / md_agent_run_event 的写入延迟",
                    queueCapacity, dropped);
        }
    }

    /**
     * 关停：放下闸门、等在途任务落库。由 Spring 在容器关闭时调用。
     *
     * <p>为什么必须显式刷盘：队列里可能还有几百条事件，进程直接退出就全丢了；
     * 而"最后一次调用恰好丢在关停瞬间"是最难解释给用户听的一种数据缺失。
     */
    @PreDestroy
    public void shutdown() {
        accepting.set(false);
        consumer.shutdown();
        try {
            if (!consumer.awaitTermination(SHUTDOWN_FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                consumer.shutdownNow();
                log.warn("trace 归档线程未在 {}ms 内排空队列，剩余 {} 条被放弃",
                        SHUTDOWN_FLUSH_TIMEOUT_MS, queue.size());
            }
        } catch (InterruptedException e) {
            consumer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 累计丢弃条数 —— 暴露出来是为了让测试与运维能回答"到底丢了多少"。 */
    public long droppedTasks() {
        return droppedTasks.get();
    }

    /** 待写任务数（队列长度），供测试断言"关停后队列确实空了"。 */
    public int pendingTasks() {
        return queue.size();
    }

    // ==================================================================================
    // 队列里的三种任务
    // ==================================================================================

    /**
     * 归档任务。用 sealed + record：任务种类是闭集，新增一种时编译器会在
     * {@link #flush} 的分支上提示（配合 switch 模式匹配），不会漏改。
     */
    private sealed interface Task permits SaveRun, AppendEvent, Clear {
    }

    /** 写一行 run 快照。 */
    private record SaveRun(AgentRunEntity entity) implements Task {
    }

    /** 追加一批事件行（单条也是一批，省掉一种任务类型）。 */
    private record AppendEvent(List<AgentRunEventEntity> entities) implements Task {
    }

    /** 清空归档（与写入共享顺序线，原因见类注释第四节）。 */
    private record Clear() implements Task {
    }
}
