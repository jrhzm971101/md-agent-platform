package com.md.agentplatform.server.workflow.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunNodeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code md_workflow_run_node} 的 Mapper。
 *
 * <p>只走 {@link BaseMapper} 的默认实现：按 run_id 取全部节点、按 (run_id, node_key)
 * 更新单个节点，两种形态都能用 lambda 查询表达，不需要手写 SQL。
 */
@Mapper
public interface WorkflowRunNodeMapper extends BaseMapper<WorkflowRunNodeEntity> {
}