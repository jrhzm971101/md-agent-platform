package com.md.agentplatform.server.trace.store;

import com.md.agentplatform.server.trace.store.entity.AgentRunEntity;
import com.md.agentplatform.server.trace.store.entity.AgentRunEventEntity;

import java.util.List;

/**
 * <b>行级</b>写入契约 —— 只认行模型（实体），不认领域对象。
 *
 * <h2>为什么把它从 {@link TraceStore} 里拆出来</h2>
 * 异步归档写入器要在<b>另一个线程</b>上落库，而那一刻领域对象（{@code AgentTraceRun}）
 * 可能已经被 agent 线程改了（状态、token、结束时刻都在变）。所以它的入参只能是
 * "在调用线程上拷好的不可变快照"——也就是实体。于是它需要一组"吃实体"的写入方法，
 * 而 {@link TraceStore} 上那组方法吃的是领域对象，两者混在一起会让接口语义变浑。
 *
 * <p>拆出来的附带收益：队列的契约（有界、丢尾、保序、关停刷盘）可以用一个假实现来单测，
 * 不必连数据库 —— 见 {@code AsyncTraceStoreTest}。
 */
public interface TraceRowWriter {

    /** 写入 / 更新一行 run 快照（幂等：同一个 runId 反复写，取最后一次）。 */
    void persistRun(AgentRunEntity entity);

    /** 批量追加事件行（只增不改）。调用方保证同一批内 (runId, seq) 不重复。 */
    void insertEvents(List<AgentRunEventEntity> entities);

    /** 清空全部归档数据。 */
    void clearAll();
}
