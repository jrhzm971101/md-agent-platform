package com.md.agentplatform.server.trace.store;

import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.store.entity.AgentRunEntity;
import com.md.agentplatform.server.trace.store.entity.AgentRunEventEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档队列的契约测试。
 *
 * <p>四条契约里，三条是"错了就只能靠线上数据缺失才发现"的，所以必须钉死：
 * <ol>
 *   <li><b>保序</b>：run 快照与事件必须按入队顺序落库，且 clear 是屏障；</li>
 *   <li><b>不阻塞</b>：队列满时丢数据，绝不阻塞 agent 线程（这是最要紧的一条 ——
 *       阻塞会把观测系统的故障传导成业务故障）；</li>
 *   <li><b>关停刷盘</b>：进程退出前把在途数据写完，否则"最后一次调用"永远查不到；</li>
 *   <li><b>关停后不再收</b>：容器已经关了一半，再入队只会让丢失悄悄发生而不计账。</li>
 * </ol>
 *
 * <p>用假存储（{@link RecordingStore}）而不是真数据库：这里要验的是队列自己的行为，
 * 与存储实现无关。真存储由 {@code MyBatisTraceStoreIT} 单独验证。
 */
class AsyncTraceStoreTest {

    /**
     * 顺序与屏障：run 快照 → 事件 → clear → 事件。
     *
     * <p>为什么 clear 的位置这么关键：它是"清空"按钮的实现。如果 clear 绕过队列直接打库，
     * 队列里排着的旧事件会在清空<b>之后</b>落库，用户看到的就是"点了清空、刷新又冒出来几条"。
     */
    @Test
    @Timeout(20)
    void eventsReachTheDelegateInOrderAndClearActsAsABarrier() {
        RecordingStore delegate = new RecordingStore();
        AsyncTraceStore store = new AsyncTraceStore(delegate, 1000, 500);
        AgentTraceRun run = newRun("t1-aaa");

        store.saveRun(run);
        store.appendEvent(run, event(1));
        store.appendEvent(run, event(2));
        store.clearAll();
        store.appendEvent(run, event(3));
        // 关停即把队列排空，省去在测试里轮询等待 —— 顺便把"关停刷盘"这条契约也一起验了。
        store.shutdown();

        assertEquals(List.of("save:t1-aaa", "event:t1-aaa:1", "event:t1-aaa:2",
                        "clear", "event:t1-aaa:3"),
                delegate.recorded(), "run 快照 / 事件 / 清空必须严格按入队顺序落库");
    }

    /**
     * 队列满 → 丢弃并计数，生产者不被挡住。
     *
     * <p>做法：让存储在下游阻塞住，消费者卡在那里不再出队，于是队列只能装下 capacity 条。
     * 此时连续投递 50 条，如果实现用的是 {@code put()}，这个测试会在这里永远挂住
     * （{@link Timeout} 会把它判成失败），这正是我们要防的回归。
     */
    @Test
    @Timeout(20)
    void fullQueueDropsObservationDataInsteadOfBlockingTheProducer() {
        BlockingStore delegate = new BlockingStore();
        // 容量 2、批量 1：消费者一取到任务就会带着 1 条去调 insertEvents 并阻塞住。
        AsyncTraceStore store = new AsyncTraceStore(delegate, 2, 1);
        AgentTraceRun run = newRun("t2-bbb");

        for (int seq = 1; seq <= 50; seq++) {
            store.appendEvent(run, event(seq));
        }

        assertTrue(store.droppedTasks() >= 40,
                "队列满时必须丢弃并累计计数，实际丢弃 " + store.droppedTasks() + " 条");

        delegate.release();
        store.shutdown();
    }

    /** 关停刷盘：300 条事件在 shutdown() 返回时必须已经全部落库，队列清空。 */
    @Test
    @Timeout(20)
    void shutdownFlushesWhatIsStillQueued() {
        RecordingStore delegate = new RecordingStore();
        AsyncTraceStore store = new AsyncTraceStore(delegate, 1000, 500);
        AgentTraceRun run = newRun("t3-ccc");

        for (int seq = 1; seq <= 300; seq++) {
            store.appendEvent(run, event(seq));
        }
        store.shutdown();

        assertEquals(0, store.pendingTasks(), "关停后队列必须为空");
        assertEquals(300, delegate.recorded().size(), "300 条事件必须一条不少地落库");
        // 顺序也要对：逐条展开后应是 1..300
        for (int seq = 1; seq <= 300; seq++) {
            assertEquals("event:t3-ccc:" + seq, delegate.recorded().get(seq - 1));
        }
    }

    /** 关停之后不再收数据，但要计入丢弃数 —— 静默丢弃是最坏的一种（没有任何证据留下）。 */
    @Test
    @Timeout(20)
    void offersAfterShutdownAreCountedAsDropped() {
        AsyncTraceStore store = new AsyncTraceStore(new RecordingStore(), 1000, 500);
        AgentTraceRun run = newRun("t4-ddd");
        store.shutdown();

        store.appendEvent(run, event(1));
        store.saveRun(run);

        assertEquals(2, store.droppedTasks(), "关停后的两次写入都应计入丢弃数");
    }

    // ==================== 夹具 ====================

    private static AgentTraceRun newRun(String runId) {
        return new AgentTraceRun(runId, "t_agent", AgentTraceRun.AGENT_KIND_REACT, "s1", "u1",
                Map.of("messageCount", 0), AgentTraceRun.SOURCE_CONSOLE);
    }

    /** 造一条 STARTED 事件；stageStartMs 递增，便于在断言里认出是哪一条。 */
    private static AgentTraceEvent event(int seq) {
        return AgentTraceEvent.started(seq, AgentTraceEvent.PHASE_AGENT, "第 " + seq + " 条",
                1_700_000_000_000L + seq, Map.of());
    }

    /** 只把调用按顺序记下来的假存储。 */
    private static class RecordingStore implements TraceStore {
        private final List<String> ops = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void saveRun(AgentTraceRun run) {
            ops.add("save:" + run.getRunId());
        }

        @Override
        public void appendEvent(AgentTraceRun run, AgentTraceEvent event) {
            ops.add("event:" + run.getRunId() + ":" + event.seq());
        }

        @Override
        public void persistRun(AgentRunEntity entity) {
            ops.add("save:" + entity.getRunId());
        }

        @Override
        public void insertEvents(List<AgentRunEventEntity> entities) {
            // 逐条记录：这样"批"与"逐条"在断言里表现一致，顺序才可断言。
            for (AgentRunEventEntity entity : entities) {
                ops.add("event:" + entity.getRunId() + ":" + entity.getSeq());
            }
        }

        @Override
        public void clearAll() {
            ops.add("clear");
        }

        @Override
        public AgentTraceRun findRun(String runId) {
            return null;
        }

        @Override
        public List<AgentTraceRun> findRecent(int limit) {
            return List.of();
        }

        @Override
        public int deleteOlderThan(long cutoffMs) {
            return 0;
        }

        List<String> recorded() {
            synchronized (ops) {
                return List.copyOf(ops);
            }
        }
    }

    /** 会在 insertEvents 上停住的假存储，用来把队列灌满。 */
    private static final class BlockingStore extends RecordingStore {
        private final CountDownLatch gate = new CountDownLatch(1);

        @Override
        public void insertEvents(List<AgentRunEventEntity> entities) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.insertEvents(entities);
        }

        void release() {
            gate.countDown();
        }
    }
}
