package com.md.agentplatform.server.controlplane.store;

import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentMemoryEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRefEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRuntimeEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentSubAgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentWorkspaceEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 agent 的<b>全部内容行</b>：md_agent 主表 + 5 张关联表读出来的一束。
 *
 * <h2>一、它同时服务于三件事</h2>
 * <ol>
 *   <li><b>装配读</b>：{@code AgentDefinitionResolver} 拿它拼出 {@code AgentDefinition}
 *       （转换只发生在那一处，见 {@code AgentEntity} 的类注释）；</li>
 *   <li><b>保存写</b>：控制器把页面提交的内容装进这个束，交给 Store 整体替换；</li>
 *   <li><b>发布快照</b>：发布时整个束被序列化成
 *       {@code md_resource_version.snapshot_json}，回滚时反序列化写回 —— 所以它必须能被 Jackson
 *       无参构造 + setter 还原（{@code @Data} 之外不要再加带参构造器）。</li>
 * </ol>
 *
 * <h2>二、四个关联对象<b>允许为 null</b>，这不是"没查出来"</h2>
 * 「一个 REACT agent 没有 harness 工作区配置」是正确的业务状态，不是数据缺失。
 * 所以本束用 null 表达「这张表里没有这个 agent 的行」，Store 保存时也据此决定
 * 「删掉这张表的行」还是「写入新行」。判空处一律先问自己：这是"没有配置"还是"查漏了"？
 *
 * <h2>三、子 agent 是有序的一对多</h2>
 * 顺序即页面上的表格顺序（{@code sort_order}），不能换成无序集合 ——
 * 顺序会影响 harness 给模型呈现子 agent 的先后。
 */
@Data
public class AgentDefinitionBundle {

    /** md_agent 主表行；<b>不可为 null</b> —— 没有主表行就不存在这个 agent。 */
    private AgentEntity agent;

    /** md_agent_ref：引用（prompt-key / tools / middleware / skills / mcp / knowledges）。 */
    private AgentRefEntity ref;

    /** md_agent_runtime：通用运行时旋钮 + 模型。 */
    private AgentRuntimeEntity runtime;

    /** md_agent_memory：上下文压缩 / 记忆 / 工具结果驱逐。 */
    private AgentMemoryEntity memory;

    /** md_agent_workspace：Harness 工作区与能力开关。 */
    private AgentWorkspaceEntity workspace;

    /** md_agent_sub_agent：子 agent 声明，按 sort_order 升序。 */
    private List<AgentSubAgentEntity> subAgents = new ArrayList<>();

    /** 主键便捷读法：多处需要 agentName，避免每处都写 getAgent().getAgentName() 并考虑 agent 为 null。 */
    public String agentName() {
        return agent == null ? null : agent.getAgentName();
    }
}
