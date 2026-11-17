package com.md.agentplatform.server.controlplane.store;

/**
 * 控制面纳管的五类资源 —— 与 {@code md_resource_version.resource_type} 的取值严格同名。
 *
 * <h2>一、为什么用枚举而不是散落的字符串</h2>
 * 这几类资源共用同一套「草稿 / 发布 / 版本快照 / 引用保护」（见 {@link ControlPlaneStore} 第六节），
 * 于是发布、回滚、列版本、查引用这四个方法都以「资源类型」为第一参数。
 * 传字符串的话，写错（例如 "promt"）要等 SQL 查完才发现，而且暴露出来的现象是
 * 「版本列表是空的」而不是「参数非法」—— 这是最难排查的一类错误。
 *
 * <h2>二、枚举名即库中取值</h2>
 * 快照表里存的就是这些名字，导出来的数据要能用眼睛直接读；改名会让历史快照变成孤儿。
 * 因此 {@link #fromDatabase(String)} 把库里的字符串翻回枚举时，遇到不认识的取值
 * <b>抛异常</b>而不是返回 null：快照表里出现未知类型，说明有人绕过控制面改过库，
 * 这种情况必须显式暴露，而不是静默忽略。
 */
public enum ResourceType {

    /** agent 定义（md_agent + 5 张关联表）。 */
    AGENT,

    /** Prompt 正文（md_prompt）。 */
    PROMPT,

    /** Skill（内容在官方 agentscope 库，治理元数据在 md_skill_meta）。 */
    SKILL,

    /** MCP 服务端（md_mcp_server）。 */
    MCP,

    /**
     * 渠道实例（md_channel + md_channel_binding）。
     *
     * <p>它是受治理资源里的第五类，走的是与其它四类完全相同的一套流程：
     * 草稿 / 发布 / 版本快照 / 回滚。之所以要纳入而不是另起一套轻量 CRUD ——
     * 渠道的「发布」是一次线上入口切换，填错一个 botId 会让消息从此进不来，
     * 这类改动比改一个 prompt 更需要能一键回滚。</p>
     */
    CHANNEL,

    /**
     * 模型（md_model）。
     *
     * <p>为什么端点与密钥也要进版本体系：它们和 prompt 正文一样会被改错，但影响面<b>更大</b> ——
     * 一个填错的 base-url 会让所有引用它的 agent 一起失效，而正确的旧值只存在于某次记忆或
     * 某个人的剪贴板里。有快照才能一键退回上一版。</p>
     *
     * <p>注意它与 agent 的引用关系是<b>单向</b>的：agent 声明 model_ref，模型不认识 agent
     *（反查由 {@code md_agent_runtime} 等四张表的 UNION 完成，见 ModelMapper）。</p>
     */
    MODEL,

    /**
     * 工作流（md_workflow + md_workflow_node_ref）。
     *
     * <p>它进版本体系的理由比另外几类更硬：工作流是<b>会被执行</b>的东西。
     * 改错一个提示词最多让某次回答变差，改错一条边会让之后所有执行都走进错误分支，
     * 而这类错误在画布上极难用眼睛看出来 —— 有快照才能一键退回上一版。</p>
     *
     * <p>它的引用方向与 {@link #MODEL} 相反：工作流是引用方（节点引用 agent），
     * 所以这里问的反查是"哪些<b>已发布</b>的工作流引用了这个 agent"，
     * 由 {@code md_workflow_node_ref} 承载 —— 那是发布时投影出来的引用，
     * 为什么要投影见 workflow-schema.sql 的头部注释。</p>
     */
    WORKFLOW;

    /** 库中的字符串 → 枚举；大小写不敏感，无法识别时抛 {@link IllegalArgumentException}。 */
    public static ResourceType fromDatabase(String raw) {
        for (ResourceType type : values()) {
            if (type.name().equalsIgnoreCase(raw)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的资源类型: " + raw);
    }
}
