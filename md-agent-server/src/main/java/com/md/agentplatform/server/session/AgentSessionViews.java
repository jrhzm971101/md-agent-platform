package com.md.agentplatform.server.session;

import java.util.List;

/**
 * 会话页的响应形状（输出专用，一律 record）。
 *
 * <h2>「会话」到底是什么</h2>
 * 一次 <b>会话</b> = 一串共享同一个 sessionId 的 <b>run</b>（一次调用 = 一棵调用树）。
 * 所以这一页是把另外两页串起来的第三层：
 * <ul>
 *   <li>「执行链路」回答"这一次是怎么走的"（run → 事件树）；</li>
 *   <li>「状态存储」回答"agent 现在记得什么"（状态表 → 上下文）；</li>
 *   <li>本页回答<b>"你是谁、聊过几轮、跟哪几个 agent 聊过、还记得多少"</b>，
 *       并把上面两个视角按会话聚合起来。</li>
 * </ul>
 *
 * <h2>agent 是筛选维度，不是归属维度</h2>
 * 这是本页最重要的一个认知，也是它值得单独存在的理由：<b>同一个 sessionId 会被多个 agent 服务</b>
 * （实测：企微里同一条会话先交给 workwx_batch_agent 又交给 deployment_agent，sessionId 没变）。
 * 原因是 sessionId 由入口方算、里面不含 agent。所以 {@link Session#agents} 是<b>列表</b>，
 * 而 {@code agentId} 筛选是"凡是被这个 agent 服务过的会话"。
 *
 * <h2>时间为什么是 epoch millis</h2>
 * 与 run 表一致，且前端要自己排序/比较（相对时间、时间窗）。给 ISO 字符串反而要在前端再解析一次，
 * 而"哪些时间给串、哪些给数"不一致，正是最容易让前后端各写一套格式化逻辑的地方。
 * 唯一的例外是 {@link StateTable#updatedAt}：它来自状态表的 DATETIME 列，
 * 服务层已经统一归一成 ISO（见 {@code AgentStateAdminService} 的说明）。
 */
public final class AgentSessionViews {

    private AgentSessionViews() {}

    /** 会话列表里的一行。 */
    public record Session(
            String userId,
            String sessionId,
            /** 一共跑了多少次（= run 行数）。 */
            long runs,
            /** 其中失败的次数；页面据此显示成功率。 */
            long failed,
            /** token 合计（输入 + 输出）。 */
            long tokens,
            /** 服务过这个会话的 agent，去重、按名排序。可能不止一个。 */
            List<String> agents,
            /** 出现过的来源，去重排序；一次都没标注过时为空列表。 */
            List<String> sources,
            /** 最早一次执行（epoch millis）。 */
            long firstMs,
            /** 最近一次执行（epoch millis）。 */
            long lastMs,
            /** 状态库里还有没有这个会话的记忆（= 下一次对话还记不记得前面说过什么）。 */
            boolean hasState,
            /** 有记忆时落在哪几张状态表里；没有则为空列表。 */
            List<String> stateTables) {}

    /** 会话列表的一页。 */
    public record SessionPage(
            /** 过滤后命中的会话数。 */
            long total,
            /** 库里全部会话数（不带筛选），用于页面显示「筛选后 X / 共 Y」。 */
            long allTotal,
            List<Session> items) {}

    /**
     * 会话详情。
     *
     * <p>{@code runs} 有上限（见 {@code AgentSessionAdminService.MAX_RUNS_PER_SESSION}）：
     * 只增的会话可能有几千次执行，整段返回没有意义。
     */
    public record SessionDetail(
            String userId,
            String sessionId,
            List<SessionRun> runs,
            List<StateTable> states) {}

    /** 会话详情里的一次执行（= 一棵调用树的根）。 */
    public record SessionRun(
            String runId,
            String agentId,
            String agentKind,
            String source,
            /** 前端闭集：running | success | failed。 */
            String status,
            /** 状态色：ok | run | err。 */
            String tone,
            /** 耗时；进行中的 run 是"至今耗时"。 */
            long durationMs,
            long tokens,
            /** 开始时刻（epoch millis）。 */
            long startedAtMs,
            String error) {}

    /** 这个会话的记忆落在哪张状态表里。 */
    public record StateTable(
            String tableName,
            /** 声明使用这张表的 agent；null = 孤儿表（改过名或删过 agent 的残留）。 */
            String agentName,
            /** 这张表里属于本会话的行数（state_key × item_index）。 */
            int rows,
            long bytes,
            /** 最近写入时间（ISO-8601，空格已归一）。 */
            String updatedAt) {}
}
