package com.md.agentplatform.server.workflow.store;

import com.md.agentplatform.server.workflow.store.entity.WorkflowRunEntity;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunNodeEntity;

import java.util.List;

/**
 * 工作流执行的<b>读写契约</b>。
 *
 * <h2>一、为什么与 ControlPlaneStore 分开</h2>
 * 两者回答的是完全不同的问题，而且频率差几个数量级：
 * <ul>
 *   <li>{@code ControlPlaneStore} 管<b>定义</b>：一天改几次，每次都是人手动点的；</li>
 *   <li>本接口管<b>运行</b>：一次执行里每个节点至少写两次（开始 / 收口），
 *       而一个工作流一天可能被触发几百次。</li>
 * </ul>
 * 混在一起的直接后果是"发布一个工作流的代码路径"与"每个节点都要走的写入路径"
 * 挤在同一个类里，而后者需要的是极简的写（少一次查询、少一次事务开销）。
 *
 * <h2>二、为什么不做成"只有 save"两个方法</h2>
 * run 行与 node 行的写入时机不同、且形态不同：
 * run 是"先插后改"（开始插 RUNNING、终态再改），node 是"先插 PENDING、
 * 派发时改 RUNNING、收口时改终态"。把两类生命周期压成同一个 save，
 * 调用方就得自己判断"现在这条记录存不存在"，而那个判断在本类实现里只是一次
 * {@code selectById}。让存储层承担它，是为了让引擎的主循环里没有"先查一下有没有"这种噪音。
 *
 * <h2>三、同步写，刻意不做异步队列</h2>
 * 执行链路的归档是异步的（观测数据丢一条不影响结论），这里<b>不是</b>：
 * node 的状态是"这次执行到底跑到哪了"的唯一真相。异步丢失会让页面出现
 * "run 已结束、某个节点永远是 RUNNING"，而排查的人会去怀疑引擎。
 * 一次执行只有几个节点，同步写的代价是几百微秒，换来确定性。
 */
public interface WorkflowRunStore {

    // ==================== run ====================

    /** 插入 run 行（开始执行时调用一次）。 */
    void insertRun(WorkflowRunEntity run);

    /** 整行覆盖 run 行（终态时调用）。 */
    void updateRun(WorkflowRunEntity run);

    /**
     * 读一次执行的 run 行。
     *
     * @return 不存在时返回 null —— 让调用方区分"没有这条执行"与"有但没节点"
     */
    WorkflowRunEntity findRun(String runId);

    /**
     * 某个工作流最近的执行，新的在前。
     *
     * <p>刻意<b>不带节点</b>：列表页只显示"什么时候、谁触发的、跑成什么样"，
     * 而带节点意味着 N+1 次查询（每次执行再查一遍节点表），这些数据在列表页一个都不显示。
     * 需要看明细时页面会再请求一次 run 详情。
     */
    List<WorkflowRunEntity> findRecent(String workflowKey, int limit);

    // ==================== node ====================

    /** 插入一个节点行（PENDING 状态，在执行开始前一次性把所有节点种下去）。 */
    void insertNode(WorkflowRunNodeEntity node);

    /** 按 (runId, nodeKey) 整行覆盖一个节点行。 */
    void updateNode(WorkflowRunNodeEntity node);

    /** 一次执行的<b>全部</b>节点，按 nodeKey 排序（页面按 key 稳定渲染）。 */
    List<WorkflowRunNodeEntity> findNodes(String runId);

    /**
     * 单个节点行；不存在返回 null。
     *
     * <p>单独给出这个方法是因为引擎主循环里每个节点都要读一次自己 ——
     * 让它每次去全量查一遍再过滤，会让"节点数 × 节点数"次比较白跑。
     */
    WorkflowRunNodeEntity findNode(String runId, String nodeKey);
}