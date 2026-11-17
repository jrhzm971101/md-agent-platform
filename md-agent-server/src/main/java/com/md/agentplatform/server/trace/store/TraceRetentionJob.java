package com.md.agentplatform.server.trace.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 归档保留期清理 —— 每天把超过保留天数的 run 与其事件删掉。
 *
 * <h2>为什么要专门写这个</h2>
 * 观测数据是<b>只增不减</b>的：一次 agent 调用至少 6 条事件（根节点一对 + 每次推理一对 +
 * 每次工具一对），一个接入企微的 agent 每天几百次调用就是几千行。没有清理，
 * 一年后这张表会让运维面对"要不要删"这种本来可以自动决定的问题。
 *
 * <h2>三个刻意的设计取舍</h2>
 * <ol>
 *   <li><b>跑在凌晨</b>（03:10）：清理是成批 DELETE，会持有行锁并产生大量 binlog，
 *       放在业务低谷，避免与白天的实时写入抢 IO。</li>
 *   <li><b>重入保护</b>：一次清理可能跑很久，而下一天的任务照常触发。
 *       用 CAS 挡住重叠执行 —— 两个清理任务同时 DELETE 同一批数据，除了白干还会放大锁竞争。</li>
 *   <li><b>不抛异常</b>：清理失败（比如数据库短暂不可用）不该让调度线程挂掉，
 *       更不该影响任何业务逻辑。记 ERROR 日志，等下一个周期。</li>
 * </ol>
 *
 * <p>本类刻意不标 {@code @Component}：它由 {@code TraceStoreConfig} 在"归档已启用"时才会
 * 注册成 Bean（原因见那个配置类的注释）。
 */
public class TraceRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionJob.class);

    private final TraceStore traceStore;
    private final int retentionDays;
    /** 重入闸：上一次还没跑完就跳过本周期。 */
    private final AtomicBoolean running = new AtomicBoolean();

    public TraceRetentionJob(TraceStore traceStore, int retentionDays) {
        this.traceStore = traceStore;
        this.retentionDays = retentionDays;
    }

    /** 每天 03:10 执行一次（cron 六段式：秒 分 时 日 月 周）。 */
    @Scheduled(cron = "0 10 3 * * ?")
    public void purgeExpiredRuns() {
        // <=0 表示"永久保留"，此时连任务体都不进 —— 配置成 0 的人要的是彻底不清理。
        if (retentionDays <= 0) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("上一次 trace 归档清理尚未结束，跳过本次");
            return;
        }
        try {
            long cutoffMs = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L;
            int deleted = traceStore.deleteOlderThan(cutoffMs);
            // 无论删了 0 条还是 100 万条都打一行 info：这一行是"清理到底有没有在跑"的唯一证据，
            // 静默成功会让人怀疑任务没被调度。
            log.info("trace 归档清理完成：保留 {} 天，cutoffMs={}，删除 run {} 条", retentionDays, cutoffMs, deleted);
        } catch (Throwable t) {
            // 刻意吞掉（只记日志）：清理是后台维护动作，抛出去只会污染调度器的日志、改变不了任何事。
            log.error("trace 归档清理失败，等下一个周期重试", t);
        } finally {
            running.set(false);
        }
    }
}
