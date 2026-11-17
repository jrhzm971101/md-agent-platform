package com.md.agentplatform.server.workflow.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunEntity;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunNodeEntity;
import com.md.agentplatform.server.workflow.store.mapper.WorkflowRunMapper;
import com.md.agentplatform.server.workflow.store.mapper.WorkflowRunNodeMapper;

import java.util.List;

/**
 * {@link WorkflowRunStore} 的 MySQL 实现（MyBatis-Plus）。
 *
 * <p>本类只做三件事：拼查询条件、把行交给 Mapper、把 limit 夹到安全范围。
 * "什么时候该写、写什么状态"一律不在这里 —— 那是 {@code WorkflowEngine} 的职责，
 * 否则将来换存储（比如换成与状态存储同一个库）就得连带改业务规则。
 */
public class MyBatisWorkflowRunStore implements WorkflowRunStore {

    /**
     * 列表查询的硬上限。
     *
     * <p>接口层的参数已经被控制器夹过一次，这里再兜一次底：本类是 public 的，
     * 将来别处直接调用时不该因为忘了传 limit 就把整张表捞出来。
     */
    private static final int MAX_RECENT = 200;

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunNodeMapper nodeMapper;

    public MyBatisWorkflowRunStore(WorkflowRunMapper runMapper, WorkflowRunNodeMapper nodeMapper) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
    }

    @Override
    public void insertRun(WorkflowRunEntity run) {
        if (run == null || run.getRunId() == null) {
            return;
        }
        runMapper.insert(run);
    }

    @Override
    public void updateRun(WorkflowRunEntity run) {
        if (run == null || run.getRunId() == null) {
            return;
        }
        // 用 updateById 而不是"先查再决定插还是改"：run 行的插入时机是确定的
        //（引擎在开始执行时插一次），所以这里不存在存不存在的问题。
        // 与执行链路那套不同 —— 那边一条 run 会被三个不同代码路径刷，所以它才需要先查。
        runMapper.updateById(run);
    }

    @Override
    public WorkflowRunEntity findRun(String runId) {
        return runId == null || runId.isBlank() ? null : runMapper.selectById(runId);
    }

    @Override
    public List<WorkflowRunEntity> findRecent(String workflowKey, int limit) {
        int capped = limit <= 0 ? 20 : Math.min(limit, MAX_RECENT);
        return runMapper.selectList(Wrappers.<WorkflowRunEntity>lambdaQuery()
                .eq(WorkflowRunEntity::getWorkflowKey, workflowKey)
                .orderByDesc(WorkflowRunEntity::getStartedAtMs)
                .last("LIMIT " + capped));
    }

    @Override
    public void insertNode(WorkflowRunNodeEntity node) {
        if (node == null || node.getRunId() == null) {
            return;
        }
        nodeMapper.insert(node);
    }

    @Override
    public void updateNode(WorkflowRunNodeEntity node) {
        if (node == null || node.getId() == null) {
            // 按主键改：node 是先插再改，所以主键一定在。
            // 没有主键说明调用方拿的是一个还没落库的对象 —— 静默返回而不是抛异常，
            // 因为这只可能是代码写错（会立刻在页面上表现为"节点状态不动"），
            // 抛异常反而会把一次正常的执行整条打断。
            return;
        }
        nodeMapper.updateById(node);
    }

    @Override
    public List<WorkflowRunNodeEntity> findNodes(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return nodeMapper.selectList(Wrappers.<WorkflowRunNodeEntity>lambdaQuery()
                .eq(WorkflowRunNodeEntity::getRunId, runId)
                .orderByAsc(WorkflowRunNodeEntity::getNodeKey));
    }

    @Override
    public WorkflowRunNodeEntity findNode(String runId, String nodeKey) {
        if (runId == null || nodeKey == null) {
            return null;
        }
        return nodeMapper.selectOne(Wrappers.<WorkflowRunNodeEntity>lambdaQuery()
                .eq(WorkflowRunNodeEntity::getRunId, runId)
                .eq(WorkflowRunNodeEntity::getNodeKey, nodeKey));
    }
}