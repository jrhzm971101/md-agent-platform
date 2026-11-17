package com.md.agentplatform.server.overview;

import com.md.agentplatform.server.trace.TraceRunSummary;

import java.util.List;

/**
 * 概览页的接口形状 —— 与前端 {@code src/api/overview.ts} 的 OverviewPayload 一一对应。
 *
 * <h2>一、这里的每个数字都必须能追到某个 SQL</h2>
 * 概览页是"一眼看全平台"的地方，也是<b>最容易长出编造数字</b>的地方
 *（上一版前端就是写死的：今日执行 128 次、缓存命中 61%、最慢 agent workwx_batch_agent）。
 * 那些数字看起来无害，实际后果是"对着假数据排查真问题"。所以本文件里凡是数值字段，
 * 都必须在 {@link OverviewService} 里能找到它的来源；做不到的就<b>不放进载荷</b>。
 *
 * <h2>二、比率与增量的归一化</h2>
 * <ul>
 *   <li>分母为 0 时一律回 0 而不是抛异常，也不用负数或 null 表达"没数据"：
 *       健康度条形图的宽度取的就是这个比率，null 会让页面渲染成 0，效果一样但没有类型歧义；</li>
 *   <li>「今日 vs 昨日」的增量<b>不</b>在后端拼成 "+12%" 这种字符串：把两个原始数字都给前端，
 *       口径（今日/昨日、是否含进行中）才留在页面上可见，而不是藏在一个后端拼好的百分数里。</li>
 * </ul>
 */
public final class OverviewViews {

    private OverviewViews() {
    }

    /** Agent 数量与生命周期分布。 */
    public record AgentStats(int total, int harness, int react, int published, int enabled) {
    }

    /**
     * 能力资源规模。
     *
     * @param local      本地工具数（容器里带 {@code @Tool} 的 bean）
     * @param mcpServers MCP 服务端条目数
     * @param mcpEnabled 其中启用的数量
     */
    public record ToolStats(int local, int mcpServers, int mcpEnabled) {
    }

    /**
     * 今日执行情况。
     *
     * @param yesterdayTotal 昨日同一口径的执行次数：页面用它自己算增量（见类注释第二节）
     */
    public record ExecutionStats(long total, long success, long failed, long running, long yesterdayTotal) {
    }

    /**
     * 今日耗时。
     *
     * @param averageMs 平均耗时；窗口内没有执行时是 0（页面据 total=0 显示"—"）
     * @param p95Ms     P95 耗时
     * @param slowestAgent 单次最慢的那个 agent 名；没有执行时为 null
     * @param slowestMs 最慢那次耗时
     */
    public record LatencyStats(long averageMs, long p95Ms, String slowestAgent, long slowestMs) {
    }

    /** 今日 token 与缓存命中率。 */
    public record TokenStats(long today, String cacheHitRate) {
    }

    /**
     * 模型规模。
     *
     * @param defaultKey 当前兜底模型键；null = 走 application.yml 的兜底 Bean（页面上要如实显示）
     */
    public record ModelStats(int total, int enabled, String defaultKey) {
    }

    /** 指标卡的全部数据。 */
    public record Metrics(AgentStats agents, ToolStats tools, ExecutionStats executionsToday,
                          LatencyStats latency, TokenStats tokens, ModelStats models) {
    }

    /** 健康度条形图的一行：比率用于画条，value 是人读的"12 / 14"。 */
    public record HealthBar(String label, double ratio, String value) {
    }

    /**
     * 「需要关注」的一条。
     *
     * @param tone err | wait（与前端 StatusDot 的取值一致，页面上不做二次映射）
     */
    public record AttentionItem(String title, String meta, String detail, String tone) {
    }

    /**
     * 「最近变更」的一条 —— 来源是 {@code md_resource_version}，也就是<b>发布与回滚</b>事件。
     *
     * <p>为什么不叫"最近编辑"：草稿保存不写快照（见 ResourceVersionEntity 的类注释），
     * 所以这里看不到"谁改了个备注"。把标题写成"最近变更"而内容是发布流水，是刻意的取舍 ——
     * 报一个不存在的口径比少一个口径更糟。
     *
     * @param atMs 发生时刻（epoch millis）；页面自己格式化成相对时间
     */
    public record ChangeItem(String subject, String resourceType, String action, String note,
                             long atMs, String actor) {
    }

    /**
     * 概览页的整个载荷。
     *
     * @param runs 最近若干次执行（与 {@code GET /api/traces} 同一形状，点行可跳链路详情）
     */
    public record Overview(Metrics metrics, List<HealthBar> health, List<AttentionItem> attention,
                           List<ChangeItem> changes, List<TraceRunSummary> runs, long generatedAtMs) {
    }
}