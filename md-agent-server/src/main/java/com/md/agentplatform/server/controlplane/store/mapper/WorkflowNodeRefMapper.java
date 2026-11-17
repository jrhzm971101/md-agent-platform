package com.md.agentplatform.server.controlplane.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.md.agentplatform.server.controlplane.store.entity.WorkflowNodeRefEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code md_workflow_node_ref} 的 Mapper —— 工作流引用的<b>反查</b>入口。
 *
 * <h2>为什么这个 Mapper 存在的唯一理由是两条 SQL</h2>
 * 增删改基本都由 {@link BaseMapper} 覆盖。真正需要手写的是反查那条：
 * 它是"删除 agent 之前的保护检查"，而保护检查的正确性直接决定一次误删的后果，
 * 值得被一条带索引的等值查询钉住，而不是把 spec 全文捞出来在内存里 JSON 解析。
 *
 * <p>{@code ref_type = 'AGENT'} 直接写在 SQL 里而不是当参数传：第一批只有 agent 一种被引用物，
 * 多传一个参数只会多一处传错的机会。将来 condition 引用知识库时，
 * 这里加一个方法而不是给这个方法加参数 —— 两种引用方的查询形态本来就不同。
 */
@Mapper
public interface WorkflowNodeRefMapper extends BaseMapper<WorkflowNodeRefEntity> {

    /**
     * 反查：哪些<b>已发布</b>的工作流引用了这个 agent。
     *
     * <p>返回工作流 key 的去重列表，按 key 排序（让 409 的 body 顺序稳定，便于比对与测试）。
     * 刻意只返回 key：调用方（删除保护）只需要"有哪些流程要先去改"，
     * 更细的定位（哪一版、哪个节点）留给工作流详情页 —— 那边本来就按 revision 存着引用。
     */
    @Select("""
            SELECT DISTINCT workflow_key
            FROM md_workflow_node_ref
            WHERE ref_type = 'AGENT' AND ref_id = #{agentName}
            ORDER BY workflow_key
            """)
    List<String> findWorkflowKeysByAgent(@Param("agentName") String agentName);

    /**
     * 删除某个工作流的全部引用行（删工作流时调用）。
     *
     * <p>不按 revision 过滤：工作流被删掉之后，它所有历史版本的引用都失去意义，
     * 留着只会让"删除 agent 前的检查"报出一个已经不存在的流程名。
     */
    @Delete("DELETE FROM md_workflow_node_ref WHERE workflow_key = #{workflowKey}")
    int deleteByWorkflow(@Param("workflowKey") String workflowKey);

    /**
     * 删除某个工作流<b>某一版</b>的引用行（发布时先清后插，让重复发布幂等）。
     *
     * <p>为什么按 revision 而不是像其它资源那样用 {@code delete + lambdaQuery}：
     * 发布路径在事务内，这条 SQL 每删一次就要重算一次条件，写成注解更直观，
     * 也顺手带上了那条 {@code uk_ref} 唯一键对应的精确范围。
     */
    @Delete("DELETE FROM md_workflow_node_ref WHERE workflow_key = #{workflowKey} AND revision = #{revision}")
    int deleteByWorkflowRevision(@Param("workflowKey") String workflowKey,
                                 @Param("revision") int revision);
}