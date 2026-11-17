package com.md.agentplatform.server.config;

import com.md.agentplatform.server.workflow.store.MyBatisWorkflowRunStore;
import com.md.agentplatform.server.workflow.store.WorkflowRunStore;
import com.md.agentplatform.server.workflow.store.mapper.WorkflowRunMapper;
import com.md.agentplatform.server.workflow.store.mapper.WorkflowRunNodeMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工作流<b>运行层</b>存储的装配（{@code md_workflow_run} / {@code md_workflow_run_node}）。
 *
 * <h2>一、为什么单独一个配置类，不塞进 {@link ControlPlaneStoreConfig}</h2>
 * 两者管理的不是同一类东西：
 * <ul>
 *   <li>{@code ControlPlaneStore} 管的是<b>定义</b>（agent / prompt / skill / mcp / channel / model / workflow），
 *       它的读写在控制面，生命周期是"草稿-发布-回滚"；</li>
 *   <li>本类管的是<b>运行</b>（一次执行跑成什么样），只在执行期写、只在观测页读，
 *       生命周期是"开始-结束"，没有版本概念。</li>
 * </ul>
 * 混进一个类里会让人以为"删工作流定义"要顺带处理运行记录 —— 那是另一个问题
 * （运行记录属于观测数据，与定义各自独立保留，删定义不删历史运行）。
 *
 * <h2>二、为什么是显式 @Bean 而不是给实现类标 @Component</h2>
 * 与 {@link TraceStoreConfig} 同一个理由：这里是唯一该知道"运行记录存在 MySQL"的地方。
 * 将来若要把它换成"与状态存储同一个库"或加一层异步写入，改动只落在本方法里。
 *
 * <h2>三、没有开关</h2>
 * 与链路归档不同（{@code trace-archive-enabled=false} 时纯内存也能跑），
 * 工作流的运行记录是<b>引擎自己的状态机载体</b>：节点跑到哪一步、跳过还是失败都记在这两张表里。
 * 关掉它等于让引擎没有地方记账，所以不给开关 —— 要么有工作流能力，要么没有。
 */
@Configuration
public class WorkflowStoreConfig {

    /** 工作流运行记录存储：run 主表 + node 明细表。 */
    @Bean
    public WorkflowRunStore workflowRunStore(WorkflowRunMapper runMapper,
                                             WorkflowRunNodeMapper nodeMapper) {
        return new MyBatisWorkflowRunStore(runMapper, nodeMapper);
    }
}