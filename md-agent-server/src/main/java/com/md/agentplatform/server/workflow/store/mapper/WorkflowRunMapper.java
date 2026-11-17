package com.md.agentplatform.server.workflow.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code md_workflow_run} 的 Mapper。
 *
 * <p>只走 {@link BaseMapper} 的默认实现。"某个工作流最近跑了哪些"由 Store 用
 * lambda 查询拼（带 limit），不在这里手写 SQL —— 那种查询的条件会随页面迭代，
 * 手写 SQL 的结果是每改一次页面就要同步改一处字符串拼接。
 */
@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRunEntity> {
}