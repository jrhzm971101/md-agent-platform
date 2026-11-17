package com.md.agentplatform.server.overview;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.AgentDefinitionBundle;
import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRefEntity;
import com.md.agentplatform.server.controlplane.store.entity.McpServerEntity;
import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import com.md.agentplatform.server.controlplane.store.entity.ResourceVersionEntity;
import com.md.agentplatform.server.controlplane.store.entity.SkillMetaEntity;
import com.md.agentplatform.server.controlplane.store.mapper.ResourceVersionMapper;
import com.md.agentplatform.server.model.DbModelFactory;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.TraceRunSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 概览页的聚合服务 —— 把散在六张表与一个内存注册表里的东西，收成一份载荷。
 *
 * <h2>一、为什么需要一个"聚合服务"，而不是让页面打十几个接口</h2>
 * 概览页要回答的问题是"这个平台现在怎么样"。拆成十几个接口，页面就得在客户端做 join
 *（agent 表 + 引用表 + prompt 表 + mcp 表 + run 表 + 版本表），而且每次刷新要发十几个请求；
 * 更要命的是<b>它们不在同一个时刻</b> —— 于是"Agent 14 个、已发布 15 个"这种自相矛盾的画面
 * 迟早会出现。一次调用、一次快照，才谈得上"这一屏是同一时刻的平台"。
 *
 * <h2>二、每个数字的口径都写在这里，页面不许再算一遍</h2>
 * <ul>
 *   <li><b>今日</b> = 服务器本地时区的自然日（与 Cron、日志的口径一致），
 *       边界在 Java 里算好再传 SQL —— 让数据库去判断"今天"，会引入它的时区设置这个隐藏变量；</li>
 *   <li><b>成功率 / 耗时 / token</b> 只看今日的 run，进行中的 run 也计入（耗时算到此刻），
 *       与链路列表一致；</li>
 *   <li><b>健康度</b>是"比率"而不是"数量"：条形的长度必须与标签上的数字是同一个含义，
 *       否则 122 个工具画成满格、3/4 个 MCP 也画成满格，读者无法比较；</li>
 *   <li><b>需要关注</b>只给能直接行动的四条规则（见 {@link #attention}），
 *       不做"综合评分"这类无法追溯的结论。</li>
 * </ul>
 *
 * <h2>三、这一页只读</h2>
 * 没有任何写操作：它就是"看一眼"，任何需要动作的事情都该去对应的资源页做
 *（这也是「需要关注」里每条都写明去处的原因）。
 */
@Service
public class OverviewService {

    /** 载荷里带几次最近执行：与页面右侧那块的可见行数一致，多给没用。 */
    private static final int RECENT_RUNS = 8;
    /** 载荷里带几条最近发布/回滚。 */
    private static final int RECENT_CHANGES = 8;
    /** 「需要关注」最多几条 —— 它是个提示列表，不是清单，超过一屏就没人读了。 */
    private static final int ATTENTION_LIMIT = 6;
    /** 「有失败的 agent」最多列几个（按失败次数排序）。 */
    private static final int FAILURE_AGENTS = 3;
    /** 判定"闲置"的窗口。 */
    private static final int IDLE_WINDOW_DAYS = 7;

    private static final long DAY_MS = 86_400_000L;

    private final ControlPlaneStore store;
    private final OverviewMapper mapper;
    private final ResourceVersionMapper versionMapper;
    private final AgentManager agentManager;
    private final AgentTraceRegistry traceRegistry;
    private final DbModelFactory modelFactory;

    public OverviewService(ControlPlaneStore store, OverviewMapper mapper,
                           ResourceVersionMapper versionMapper, AgentManager agentManager,
                           AgentTraceRegistry traceRegistry, DbModelFactory modelFactory) {
        this.store = store;
        this.mapper = mapper;
        this.versionMapper = versionMapper;
        this.agentManager = agentManager;
        this.traceRegistry = traceRegistry;
        this.modelFactory = modelFactory;
    }

    /** 生成一份概览快照。所有查询都是只读的。 */
    public OverviewViews.Overview snapshot() {
        long now = System.currentTimeMillis();
        long todayStart = startOfToday();
        long yesterdayStart = todayStart - DAY_MS;

        List<AgentDefinitionBundle> bundles = store.loadAllAgentBundles();
        List<PromptEntity> prompts = store.listPrompts();
        List<SkillMetaEntity> skills = store.listSkillMetas();
        List<McpServerEntity> mcpServers = store.listMcpServers();
        List<ModelEntity> models = store.listModels();
        int localTools = agentManager.listAvailableTools().size();

        OverviewRows.RunStatsRow today = mapper.statsBetween(todayStart, todayStart + DAY_MS);
        OverviewRows.RunStatsRow yesterday = mapper.statsBetween(yesterdayStart, todayStart);

        return new OverviewViews.Overview(
                metrics(bundles, prompts, skills, mcpServers, models, localTools, today, yesterday,
                        todayStart, now),
                health(bundles, prompts, skills, mcpServers),
                attention(bundles, prompts, skills, mcpServers, now),
                changes(),
                recentRuns(),
                now);
    }

    // ==================================================================================
    // 指标卡
    // ==================================================================================

    private OverviewViews.Metrics metrics(List<AgentDefinitionBundle> bundles, List<PromptEntity> prompts,
                                         List<SkillMetaEntity> skills, List<McpServerEntity> mcpServers,
                                         List<ModelEntity> models, int localTools,
                                         OverviewRows.RunStatsRow today, OverviewRows.RunStatsRow yesterday,
                                         long todayStart, long now) {
        int harness = 0;
        int react = 0;
        int published = 0;
        int enabled = 0;
        for (AgentDefinitionBundle bundle : bundles) {
            AgentEntity agent = bundle.getAgent();
            if (agent == null) {
                continue;
            }
            if ("HARNESS".equalsIgnoreCase(agent.getAgentType())) {
                harness++;
            } else {
                react++;
            }
            if (Boolean.TRUE.equals(agent.getEnabled())) {
                enabled++;
            }
            if (agent.getPublishedVersion() != null) {
                published++;
            }
        }

        int mcpEnabled = 0;
        for (McpServerEntity server : mcpServers) {
            if (!Boolean.FALSE.equals(server.getEnabled())) {
                mcpEnabled++;
            }
        }
        int modelEnabled = 0;
        for (ModelEntity model : models) {
            if (!Boolean.FALSE.equals(model.getEnabled())) {
                modelEnabled++;
            }
        }

        return new OverviewViews.Metrics(
                new OverviewViews.AgentStats(bundles.size(), harness, react, published, enabled),
                new OverviewViews.ToolStats(localTools, mcpServers.size(), mcpEnabled),
                new OverviewViews.ExecutionStats(today.getRuns(), today.getDone(), today.getFailed(),
                        today.getRunning(), yesterday.getRuns()),
                latency(todayStart, now),
                new OverviewViews.TokenStats(today.getTokens(), cacheHitRate(today)),
                new OverviewViews.ModelStats(models.size(), modelEnabled, modelFactory.defaultModelKey()));
    }

    /**
     * 今日耗时分布：平均、P95、最慢的那一个。
     *
     * <p>P95 的取法：把耗时升序排好，取第 {@code ceil(0.95 * n)} 个（1 起算，越界则取最后一个）。
     * 这是"最近秩"定义，样本很少时（例如今日只有 3 次执行）它等于最大值 ——
     * 那正是我们想要的诚实结果：3 个样本的 P95 本来就说明不了什么，硬插值反而会给出一个
     * 从来没发生过的耗时数字。
     */
    private OverviewViews.LatencyStats latency(long todayStart, long now) {
        List<OverviewRows.RunDurationRow> rows = mapper.durationsSince(todayStart, now);
        if (rows.isEmpty()) {
            return new OverviewViews.LatencyStats(0L, 0L, null, 0L);
        }
        long[] durations = new long[rows.size()];
        long sum = 0L;
        String slowestAgent = null;
        long slowestMs = -1L;
        int index = 0;
        for (OverviewRows.RunDurationRow row : rows) {
            long duration = Math.max(0L, row.getDurationMs());
            durations[index++] = duration;
            sum += duration;
            if (duration > slowestMs) {
                slowestMs = duration;
                slowestAgent = row.getAgentName();
            }
        }
        java.util.Arrays.sort(durations);
        int rank = (int) Math.ceil(0.95 * durations.length);
        long p95 = durations[Math.min(Math.max(rank, 1), durations.length) - 1];
        return new OverviewViews.LatencyStats(sum / durations.length, p95, slowestAgent, Math.max(0L, slowestMs));
    }

    /** 缓存命中率 = 命中 token / 输入 token；输入为 0 时回 "0%"（与 TraceRunInfo 同一口径）。 */
    private static String cacheHitRate(OverviewRows.RunStatsRow stats) {
        if (stats.getInputTokens() <= 0) {
            return "0%";
        }
        long rate = Math.round(stats.getCachedTokens() * 100.0 / stats.getInputTokens());
        return rate + "%";
    }

    // ==================================================================================
    // 健康度
    // ==================================================================================

    /**
     * 能力资源健康度 —— 每一条都是"已有多少 / 一共多少"。
     *
     * <p>为什么用"发布率 / 引用率 / 启用率"这三个比率，而不是资源数量：
     * 数量的绝对值只说明规模，说明不了"有没有问题"；而"14 个 agent 里只有 9 个发布过"
     * 或"12 个 prompt 里有 5 个没人引用"是可以直接去处理的线索。
     */
    private List<OverviewViews.HealthBar> health(List<AgentDefinitionBundle> bundles,
                                                 List<PromptEntity> prompts, List<SkillMetaEntity> skills,
                                                 List<McpServerEntity> mcpServers) {
        int published = 0;
        int enabled = 0;
        for (AgentDefinitionBundle bundle : bundles) {
            AgentEntity agent = bundle.getAgent();
            if (agent == null) {
                continue;
            }
            if (agent.getPublishedVersion() != null) {
                published++;
            }
            if (Boolean.TRUE.equals(agent.getEnabled())) {
                enabled++;
            }
        }

        Set<String> usedPrompts = new HashSet<>();
        Set<String> usedSkills = new HashSet<>();
        Set<String> usedMcps = new HashSet<>();
        collectReferences(bundles, usedPrompts, usedSkills, usedMcps);

        long referencedPrompts = prompts.stream().filter(p -> usedPrompts.contains(p.getPromptKey())).count();
        long usedSkillCount = skills.stream().filter(s -> usedSkills.contains(s.getSkillName())).count();
        long enabledMcps = mcpServers.stream().filter(s -> !Boolean.FALSE.equals(s.getEnabled())).count();

        List<OverviewViews.HealthBar> bars = new ArrayList<>();
        bars.add(bar("Agent 发布率", published, bundles.size()));
        bars.add(bar("Agent 启用率", enabled, bundles.size()));
        bars.add(bar("Prompt 引用率", (int) referencedPrompts, prompts.size()));
        bars.add(bar("Skill 引用率", (int) usedSkillCount, skills.size()));
        bars.add(bar("MCP 启用率", (int) enabledMcps, mcpServers.size()));
        return bars;
    }

    private static OverviewViews.HealthBar bar(String label, int numerator, int denominator) {
        double ratio = denominator <= 0 ? 0.0 : (double) numerator / denominator;
        return new OverviewViews.HealthBar(label, ratio, numerator + " / " + denominator);
    }

    // ==================================================================================
    // 需要关注
    // ==================================================================================

    /**
     * 「需要关注」的四条规则 —— 每条都对应一个<b>可以立刻去做</b>的动作。
     *
     * <ol>
     *   <li><b>引用了不存在的资源</b>：装配期必然失败，最严重，排最前
     *       （这种破损通常来自"删了 prompt 但 agent 还写着它"）；</li>
     *   <li><b>近 24 小时有失败执行</b>：可能是模型欠费、工具超时、渠道异常，
     *       去链路页按 agent 过滤就能看到具体阶段；</li>
     *   <li><b>草稿未发布但已启用</b>：能跑，但没有任何版本快照可回滚 ——
     *       这是一颗安静的地雷，平时不疼；</li>
     *   <li><b>近 7 日没有执行</b>：可能是已经不用的 agent（该停用），也可能是渠道断了
     *       （该去看渠道页）。两种情况的处置不同，所以在这里只提示事实，不替用户下结论。</li>
     * </ol>
     */
    private List<OverviewViews.AttentionItem> attention(List<AgentDefinitionBundle> bundles,
                                                        List<PromptEntity> prompts, List<SkillMetaEntity> skills,
                                                        List<McpServerEntity> mcpServers, long now) {
        Set<String> promptKeys = new HashSet<>();
        for (PromptEntity prompt : prompts) {
            promptKeys.add(prompt.getPromptKey());
        }
        Set<String> skillNames = new HashSet<>();
        for (SkillMetaEntity skill : skills) {
            skillNames.add(skill.getSkillName());
        }
        Set<String> mcpNames = new HashSet<>();
        for (McpServerEntity server : mcpServers) {
            mcpNames.add(server.getServerName());
        }

        List<OverviewViews.AttentionItem> errors = new ArrayList<>();
        List<OverviewViews.AttentionItem> waits = new ArrayList<>();

        // 1) 引用完整性
        for (AgentDefinitionBundle bundle : bundles) {
            AgentRefEntity ref = bundle.getRef();
            if (ref == null) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            if (notBlank(ref.getPromptKey()) && !promptKeys.contains(ref.getPromptKey())) {
                missing.add("prompt " + ref.getPromptKey());
            }
            for (String skill : safeList(ref.getSkillNames())) {
                if (!skillNames.contains(skill)) {
                    missing.add("skill " + skill);
                }
            }
            for (String mcp : safeList(ref.getMcpNames())) {
                if (!mcpNames.contains(mcp)) {
                    missing.add("MCP " + mcp);
                }
            }
            if (!missing.isEmpty()) {
                errors.add(new OverviewViews.AttentionItem(
                        label(bundle) + " 引用了不存在的资源",
                        missing.size() + " 项缺失",
                        "装配期会直接失败：" + String.join("、", missing)
                                + "。去该 agent 的引用页解绑，或先把资源补回来。",
                        "err"));
            }
        }

        // 2) 近 24 小时有失败执行
        for (OverviewRows.RunFailureRow row : mapper.failuresSince(now - DAY_MS, FAILURE_AGENTS)) {
            errors.add(new OverviewViews.AttentionItem(
                    row.getAgentName() + " 近 24 小时有失败执行",
                    "失败 " + row.getFailed() + " 次",
                    "去「执行链路」页按 Agent 过滤，能看到具体是哪一步失败与错误原文。",
                    "err"));
        }

        // 3) 草稿未发布但已启用
        for (AgentDefinitionBundle bundle : bundles) {
            AgentEntity agent = bundle.getAgent();
            if (agent == null || !Boolean.TRUE.equals(agent.getEnabled()) || agent.getPublishedVersion() != null) {
                continue;
            }
            waits.add(new OverviewViews.AttentionItem(
                    label(bundle) + " 已启用但从未发布",
                    "当前仅草稿",
                    "实例能跑，但没有任何版本快照可回滚。确认内容无误后发布一次即可消除。",
                    "wait"));
        }

        // 4) 近 7 日没有执行
        Set<String> active = OverviewRows.toSet(mapper.activeAgentsSince(now - IDLE_WINDOW_DAYS * DAY_MS));
        for (AgentDefinitionBundle bundle : bundles) {
            AgentEntity agent = bundle.getAgent();
            if (agent == null || !Boolean.TRUE.equals(agent.getEnabled()) || active.contains(agent.getAgentName())) {
                continue;
            }
            waits.add(new OverviewViews.AttentionItem(
                    label(bundle) + " 近 7 日没有执行记录",
                    "可能已闲置",
                    "如果它还在某个渠道上挂着，说明入口断了（去「渠道」页确认）；确实不用了就停用。",
                    "wait"));
        }

        List<OverviewViews.AttentionItem> merged = new ArrayList<>(errors);
        merged.addAll(waits);
        return merged.size() <= ATTENTION_LIMIT ? merged : new ArrayList<>(merged.subList(0, ATTENTION_LIMIT));
    }

    // ==================================================================================
    // 最近变更 / 最近执行
    // ==================================================================================

    /** 最近发布与回滚 —— 只有这两个动作会写 {@code md_resource_version}。 */
    private List<OverviewViews.ChangeItem> changes() {
        List<OverviewViews.ChangeItem> items = new ArrayList<>();
        for (ResourceVersionEntity row : versionMapper.recentHeaders(RECENT_CHANGES)) {
            items.add(new OverviewViews.ChangeItem(
                    row.getResourceName(),
                    row.getResourceType(),
                    "发布 v" + (row.getVersion() == null ? "?" : row.getVersion()),
                    row.getChangeNote(),
                    row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs(),
                    row.getCreatedBy()));
        }
        return items;
    }

    /**
     * 最近执行 —— 取自内存注册表（{@code AgentTraceRegistry}）而不是 run 表。
     *
     * <p>这里与指标卡的口径不同，是有意的：指标卡要的是"统计"，必须打在表上（见 OverviewMapper）；
     * 最近执行要的是"点进去就能看链路"，而链路详情与 SSE 流都挂在注册表的 runId 上。
     * 用表里的历史行也能拼出同样形状，但那些 run 的事件可能早已被保留期清掉，
     * 点进去会是一个空壳 —— 那比少显示几条更糟。
     */
    private List<TraceRunSummary> recentRuns() {
        List<TraceRunSummary> runs = new ArrayList<>();
        for (AgentTraceRun run : traceRegistry.recent(RECENT_RUNS)) {
            runs.add(TraceRunSummary.from(run));
        }
        return runs;
    }

    // ==================================================================================
    // 内部工具
    // ==================================================================================

    /**
     * 本地时区的"今天 00:00"。
     *
     * <p>用服务器默认时区而不是 UTC：定时任务、日志、运营看的日报都是本地时区的"一天"，
     * 概览页跟它们对不上，人第一反应是"数据错了"，而不是"时区不同"。
     */
    private static long startOfToday() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** agent 的展示名；没有展示名时退回 agent-name（不显示"(未命名)"，那会让人以为是数据坏了）。 */
    private static String label(AgentDefinitionBundle bundle) {
        AgentEntity agent = bundle.getAgent();
        if (agent == null) {
            return String.valueOf(bundle.agentName());
        }
        return notBlank(agent.getDisplayName()) ? agent.getDisplayName() : agent.getAgentName();
    }

    private static void collectReferences(List<AgentDefinitionBundle> bundles, Set<String> prompts,
                                          Set<String> skills, Set<String> mcps) {
        for (AgentDefinitionBundle bundle : bundles) {
            AgentRefEntity ref = bundle.getRef();
            if (ref == null) {
                continue;
            }
            if (notBlank(ref.getPromptKey())) {
                prompts.add(ref.getPromptKey());
            }
            skills.addAll(safeList(ref.getSkillNames()));
            mcps.addAll(safeList(ref.getMcpNames()));
        }
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
