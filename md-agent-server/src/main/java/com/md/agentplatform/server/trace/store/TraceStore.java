package com.md.agentplatform.server.trace.store;

import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.trace.AgentTraceRun;

import java.util.List;

/**
 * 运行链路的<b>归档存储</b>契约：内存是热数据 / 读缓存，这里是权威副本。
 *
 * <h2>一、读写职责不对称，接口把它们分开</h2>
 * <ul>
 *   <li><b>写</b>（saveRun / appendEvent）：由 {@code AgentTraceRegistry} 在事件产生的那一刻调用，
 *       实现方可以是"直接写库"，也可以是"入队后由后台线程批量写"。本接口不承诺同步落盘 ——
 *       观测数据不值得让 agent 线程等一次网络往返。</li>
 *   <li><b>读</b>（findRun / findRecent）：必须<b>同步</b>返回结果，因为它承担的是
 *       "内存里没有就回存储补齐"的职责。读路径不能被异步队列挡住。</li>
 * </ul>
 *
 * <h2>二、两个读方法为什么不对称（一个带事件、一个不带）</h2>
 * {@link #findRecent} 是给"最近执行"列表用的，列表只要 run 级字段
 * （谁跑的、什么时候、多久、多少 token、什么状态）。若它也把事件一起装回来，
 * 20 条 run 就要多查 20 次事件表、多构造上千个对象，而这些数据在列表页一个都不显示。
 * 需要看明细时页面会再请求 {@code /api/traces/{runId}/events}，那一次走 {@link #findRun}。
 *
 * <p>因此返回值上有一条约定的差异，<b>调用方必须知道</b>：{@link #findRecent} 返回的 run
 * 事件列表为空，只能用于 run 级摘要；要翻译成调用树（需要事件）必须走 {@link #findRun}。
 */
public interface TraceStore extends TraceRowWriter {

    /** 写入 / 更新 run 行（幂等：同一个 run 会被反复覆盖，取最后一次的快照）。 */
    void saveRun(AgentTraceRun run);

    /** 追加一条事件（只增不改）。 */
    void appendEvent(AgentTraceRun run, AgentTraceEvent event);

    /**
     * 读取一次 run 的完整明细（run 行 + 全部事件，事件按 seq 升序）。
     *
     * @return 不存在时返回 null —— 让调用方区分"没有这条 run"与"有但没事件"
     */
    AgentTraceRun findRun(String runId);

    /** 最近 limit 次 run，新的在前；<b>不含事件</b>（原因见类注释第二节）。 */
    List<AgentTraceRun> findRecent(int limit);

    /** 删除开始时间早于 cutoffMs 的 run 及其事件，返回删除的 run 行数。<=0 表示不清理。 */
    int deleteOlderThan(long cutoffMs);
}
