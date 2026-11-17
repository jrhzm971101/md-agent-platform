package com.md.agentplatform.server.workflow;

import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工作流节点类型。
 *
 * <h2>一、为什么第一批只有三类，以及为什么把「暂不支持」写在枚举里</h2>
 * 官方一共定义 8 类节点（agent / team / condition / join / approval / timer / signal / subrun）。
 * 第一批只做三类，切分依据不是"哪三个简单"，而是<b>哪三个不需要引入新的运行机制</b>：
 * <ul>
 *   <li>{@link #AGENT} —— 调用一个已装配的 agent，复用现成的调用链与观测；</li>
 *   <li>{@link #CONDITION} —— 纯计算，改的是"下一步走哪条边"，不产生新状态；</li>
 *   <li>{@link #JOIN} —— 纯汇合，等依赖到齐，同样不产生新状态。</li>
 * </ul>
 * 剩下五类各自都要引入一个新机制：{@code team} 需要 Team 实体（本项目还没有）、
 * {@code approval} 需要人工关口的持久化与回执、{@code timer} 与 {@code signal}
 * 需要"挂起 + 跨进程恢复"（这是最贵的一块）、{@code subrun} 需要 revision 锁版本先立住。
 *
 * <p>因此本枚举额外认识那五个名字，遇到时给出的是
 * 「timer 节点属于第二批，当前版本暂不支持」而不是「未知节点类型」——
 * 前者能让人知道是自己用早了还是写错了，后者只会让人以为拼错了单词。
 * 这份名单必须与 {@link #UNSUPPORTED} 手工保持一致，官方新增节点类型时先加到这里。
 *
 * <h2>二、枚举名即 spec 里的取值</h2>
 * spec 是页面提交的 JSON，{@code type} 字段写的就是这些名字（小写，见 {@link #fromSpec}）。
 * 库里 {@code md_workflow_run_node.node_type} 也存这个名字，导出来能用眼睛读。
 */
public enum WorkflowNodeType {

    /** 交给一个已装配的 agent 执行。 */
    AGENT,

    /** 按表达式选择出边；不调用模型、不产生执行链路。 */
    CONDITION,

    /** 汇合多条边；等依赖到齐后放行。 */
    JOIN;

    /**
     * 官方有、但本版本未实现的节点类型。
     *
     * <p>单独列出来只为了一件事：把「用早了」和「写错了」在报错信息里分开。
     */
    public static final Set<String> UNSUPPORTED =
            new LinkedHashSet<>(java.util.List.of("team", "approval", "timer", "signal", "subrun"));

    /**
     * JSON 里的 {@code type} 取值 → 枚举。
     *
     * <p>三种失败分别给出不同的说法，因为处理方式完全不同：
     * <ul>
     *   <li>空 —— "节点缺少 type"（页面少填一个字段）；</li>
     *   <li>在 {@link #UNSUPPORTED} 里 —— 明确说是哪个批次的事；</li>
     *   <li>其余 —— 直接回显原值，让人对着能改。</li>
     * </ul>
     * 大小写不敏感：页面的下拉框给出的是大写枚举名，而手写 JSON 的人多半写小写。
     */
    public static WorkflowNodeType fromSpec(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ControlPlaneValidationException("节点缺少 type 字段");
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (WorkflowNodeType type : values()) {
            if (type.name().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        if (UNSUPPORTED.contains(normalized)) {
            throw new ControlPlaneValidationException(
                    normalized + " 节点属于后续批次，当前版本暂不支持（本版本支持：agent / condition / join）");
        }
        throw new ControlPlaneValidationException(
                "未知的节点类型: " + raw + "（本版本支持：agent / condition / join）");
    }

    /**
     * 该类型是否调用模型 —— 决定它有没有执行链路、要不要填 agent。
     *
     * <p>放在枚举上而不是写成两处 if：页面要据此决定"这个节点该显示哪些配置项"，
     * 引擎要据此决定"这个节点要不要走 agent 执行器"，两处必须永远一致。
     */
    public boolean invokesAgent() {
        return this == AGENT;
    }
}