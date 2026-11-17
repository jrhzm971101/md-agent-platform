package com.md.agentplatform.server.workspace;

import java.util.List;

/**
 * 工作区管理面的视图模型 —— 页面 JSON 的形状，与 Controller 一一对应。
 *
 * <p>单独一个类而不是把 record 散在 Controller 里：这里是<b>对外契约</b>，
 * 字段改名等于接口破坏，放在一处才能一眼看全"页面上到底有哪些列"。
 */
public final class WorkspaceViews {

    private WorkspaceViews() {}

    /**
     * 一个 HARNESS agent 的工作区概览。
     *
     * @param filesystemType 实际装配出来的 filesystem 实现类名。它是本页唯一的「自证字段」：
     *                       {@code fsBackend} 只是配置，这个才是真正生效的东西 ——
     *                       排查「配了 REMOTE 但文件还落在磁盘」时先看它。
     */
    public record AgentRow(String agentName, String fsBackend, String workspacePath,
                           String filesystemType) {
    }

    /**
     * 目录里的一项。
     *
     * @param path     工作区相对路径（不含前导 {@code /}），页面拿它继续下钻
     * @param writable 是否在可写白名单内；false 的条目页面必须禁掉编辑与删除入口
     */
    public record EntryRow(String name, String path, boolean directory, long size,
                           String modifiedAt, boolean writable) {
    }

    /** 目录列表。 */
    public record DirView(String agentName, String userId, String path, List<EntryRow> entries) {
    }

    /**
     * 文件内容。
     *
     * @param truncated 内容超过读取上限被截断。必须显式告诉页面 ——
     *                  否则用户会以为"文件就这么长"，然后照着一份残缺内容做判断。
     */
    public record FileView(String agentName, String userId, String path, String content, long size,
                           boolean writable, boolean truncated) {
    }

    /** 删除结果。 */
    public record DeleteResult(String path, boolean deleted) {
    }

    /**
     * 「隔离维度」下拉框的一个候选值。
     *
     * @param userId       工作区命名空间里的那一段（{@code agents/<id>/users/<uid>}）
     * @param runs         这个身份在这个 agent 上跑过多少次；下拉框用它区分"日常在用"与"只留了一条历史"
     * @param lastActiveMs 最近一次执行的开始时刻（epoch millis），排序与展示都用它；
     *                     时间戳而不是格式化好的字符串，格式化归页面做
     */
    public record UserRow(String userId, long runs, long lastActiveMs) {
    }
}
