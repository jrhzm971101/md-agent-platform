package com.md.agentplatform.server.state;

import java.util.List;

/**
 * 状态存储管理接口的响应形状（输出专用，一律 record）。
 *
 * <p>为什么和 {@link StateSlotRow} 那批映射模型分开：映射模型跟着<b>表结构</b>走，
 * 输出模型跟着<b>页面要什么</b>走。把两者合成一个类，就会出现"为了页面好看而给实体加字段"
 * 这种反向依赖 —— 加着加着实体就不再是表的样子了。
 *
 * <p>这里刻意<b>不</b>把 {@code state_data} 原样塞进详情：实测单个会话槽最大 1.65 MB，
 * 直接把大字段返回给浏览器等于每次点详情都传一份完整上下文。详情只给"人看得懂的摘要"
 * （消息条数、压缩摘要、任务、计划），要看原文另有 {@code /api/state/sessions/raw}，
 * 且有独立的体积上限。
 */
public final class AgentStateViews {

    private AgentStateViews() {}

    /** 状态表清单的一项。 */
    public record TableView(
            String tableName,
            String agentName,
            boolean exists,
            long sessions,
            long rows,
            long bytes,
            String lastWriteAt) {}

    /** 会话列表的一页。 */
    public record SessionPage(
            /** 过滤后命中的会话数。 */
            long total,
            /** 该表里全部会话数（不带关键字过滤），用于在页面上显示「筛选后 X / 共 Y」。 */
            long tableTotal,
            List<StateSlotRow> items) {}

    /**
     * 一个会话在某一状态表里的存留情况 —— 「会话记录」页详情抽屉里「记忆」那一块的内容。
     *
     * <p>与 {@link TableView} 的区别：TableView 说的是"整张表有多少"，这里说的是
     * "这张表里属于某一条会话的有多少"。两者共用字段名（rows / bytes / updatedAt）是刻意的 ——
     * 它们本来就是同一个口径，只是分组范围不同。
     */
    public record SessionStateRow(
            String tableName,
            /** 声明使用这张表的 agent；null = 孤儿表。 */
            String agentName,
            /** 本会话在这张表里的行数（state_key × item_index）。 */
            int rows,
            long bytes,
            /** 最近写入时间（ISO-8601）。 */
            String updatedAt) {}

    /** 一个会话的槽位清单 + 解析后的 {@code agent_state}。 */
    public record SessionDetail(
            String slotId,
            String userId,
            String sessionId,
            long totalBytes,
            List<StateSlotRow> slots,
            /** 解析失败或该会话没有 {@code agent_state} 槽位时为 null。 */
            Snapshot snapshot,
            /** 解析失败的原因；成功时为 null。页面要能看见"为什么摘要区是空的"。 */
            String parseError) {}

    /** 从 {@code agent_state} 的 JSON 里抽出来的可读摘要。 */
    public record Snapshot(
            long bytes,
            long version,
            String createdAt,
            String updatedAt,
            /** 滚动压缩摘要；为空说明压缩从未触发。 */
            String summary,
            /** 模型可见的消息条数。 */
            int messages,
            int curIter,
            boolean shutdownInterrupted,
            int tasksTotal,
            List<TaskView> tasks,
            boolean planActive,
            String planFile,
            List<String> activatedToolGroups,
            /** 权限模式：bypass / ask / ... 。 */
            String permissionMode,
            /** 最后若干条消息的预览（已截断）。 */
            List<MessagePreview> preview) {}

    /** 任务清单里的一项（对应框架 {@code io.agentscope.core.state.Task}）。 */
    public record TaskView(String id, String subject, String state, String owner) {}

    /**
     * 一条消息的预览。
     *
     * @param role      USER / ASSISTANT / TOOL / SYSTEM
     * @param kind      text | thinking | tool_use | tool_result | mixed | unknown
     * @param text      已按 {@code PREVIEW_TEXT_LIMIT} 截断并拼好的纯文本（含图标前缀）
     * @param timestamp 消息时间戳原样
     */
    public record MessagePreview(String role, String kind, String text, String timestamp) {}

    /**
     * 会话里的一条消息 —— 对话页「最近会话」点进去之后回放用的那一份。
     *
     * <p>与 {@link MessagePreview} 的三点区别，每一点都是刻意的：
     * <ol>
     *   <li><b>不合成一句话</b>：预览把正文、思考、工具拼成一个字符串（"给人扫一眼"）；
     *       这里三段分开返回，因为对话页要把正文按 Markdown 渲染、把思考折进折叠区、
     *       把工具调用挂在独立的一行上 —— 拼在一起再拆开是不可能的。</li>
     *   <li><b>不截断到 400 字</b>：预览的长度上限是为了让"详情摘要"永远小；
     *       回放要的是原文，截断后的回答在页面上看起来就像模型只答了半句。</li>
     *   <li><b>工具只给名字</b>：工具结果（{@code tool_result}）是过程不是对话，
     *       对话页不渲染它；留一组名字是为了让"这一轮它动过什么"仍然看得见。</li>
     * </ol>
     *
     * @param role      USER / ASSISTANT / TOOL / SYSTEM（框架原值，页面自己决定认哪几种）
     * @param text      正文块拼起来的文本；没有正文块时是空串
     * @param thinking  思考块拼起来的文本；没有思考块时是空串
     * @param tools     这条消息里 {@code tool_use} 的名字，按出现顺序；没有时是空列表
     * @param timestamp 消息时间戳原样
     */
    public record Message(
            String role,
            String text,
            String thinking,
            List<String> tools,
            String timestamp) {}
}
