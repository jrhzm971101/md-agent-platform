package com.md.agentplatform.server.session;

import com.md.agentplatform.server.state.AgentStateAdminService;
import com.md.agentplatform.server.state.AgentStateViews;
import com.md.agentplatform.server.trace.AgentRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话视图的管理面：列出会话、看一个会话的执行时间线与记忆。
 *
 * <h2>一、这一层做三件事，都不在 SQL 里做</h2>
 * <ol>
 *   <li><b>筛选</b>：agent / 来源 / 时间窗 / 关键字 / 有没有记忆。前四个都能用 SQL 表达，
 *       第五个不能（记忆在另一个库），所以统一在内存里过一次 —— 理由见
 *       {@link AgentSessionMapper} 的类注释。</li>
 *   <li><b>翻译</b>：状态词表（后端 RUNNING/DONE/ERROR → 前端 running/success/failed）、
 *       派生耗时、把 GROUP_CONCAT 的逗号串切成数组。</li>
 *   <li><b>跨库对齐</b>：把 run 表的 (userId, sessionId) 拼成状态表的 slotId，
 *       再问状态服务"这个会话还有没有记忆"。这是本页唯一横跨两个库的地方。</li>
 * </ol>
 *
 * <h2>二、状态库读不到时，页面必须还能用</h2>
 * 「记忆」只是会话行上的一列标记，而列表的其余内容全在业务库里。状态库连不上时
 * 这里吞掉异常、只记日志、把标记降级成"无记忆" —— 与链路观测的既定原则一致
 * （观测是旁路能力，写不进去不该把业务搞挂）。反过来做的话，一个辅助标记就能让整页 500。
 *
 * <h2>三、为什么没有写操作</h2>
 * 会话是 run 的聚合结果，它本身不是一张表 —— 改一个会话等于改它底下的一批 run，
 * 而 run 是历史事实。要动记忆请去状态存储页（那里删的是状态，不是链路）。
 */
@Service
public class AgentSessionAdminService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionAdminService.class);

    /** 列表的默认 / 最大分页大小。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 详情里最多返回几次执行。
     *
     * <p>会话是只增的：一个跑了一年的定时任务会话完全可能累积几千次 run，
     * 全部返回没有意义，还会把抽屉的渲染拖垮。200 次足够看清"这个会话是怎么演化的"。
     */
    public static final int MAX_RUNS_PER_SESSION = 200;

    private final AgentSessionMapper mapper;
    private final AgentStateAdminService stateService;

    public AgentSessionAdminService(AgentSessionMapper mapper, AgentStateAdminService stateService) {
        this.mapper = mapper;
        this.stateService = stateService;
    }

    // ==================================================================================
    // 会话列表
    // ==================================================================================

    /**
     * 会话列表（筛选 + 分页）。
     *
     * @param agentId “被这个 agent 服务过”的会话；null / 空串表示不限
     * @param source  来源筛选，取值见 {@code AgentTraceRun.SOURCE_*}；null / 空串表示不限
     * @param fromMs  时间窗下界，比的是<b>最近一次活动</b>；null 表示不限
     * @param toMs    时间窗上界；null 表示不限
     * @param keyword 同时匹配 userId 与 sessionId 的子串 —— 页面上只有一个输入框，
     *                让用户先选"按哪个字段搜"是没有价值的选择题
     * @param hasState 有没有记忆：TRUE=只看还有记忆的，FALSE=只看已经没有记忆的，null=不限。
     *                它是本层唯一"必须跨库才知道答案"的维度（记忆在 md_agent_state 那个库），
     *                因此只能在内存里过 —— 而且<b>必须在这里过</b>：会话是分页返回的，
     *                让页面自己在当页数据上筛，会让"共 57 个会话"与筛出来的条数互相矛盾，
     *                用户无法判断是筛完了还是没翻完。
     */
    public AgentSessionViews.SessionPage list(String agentId, String source, Long fromMs, Long toMs,
                                              String keyword, Boolean hasState, int limit, int offset) {
        List<SessionAggregateRow> all = mapper.aggregateSessions();
        Map<String, List<String>> memories = memoriesBySlotId();

        String agentNeedle = blankToNull(agentId);
        String sourceNeedle = blankToNull(source);
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);

        List<AgentSessionViews.Session> matched = new ArrayList<>();
        for (SessionAggregateRow row : all) {
            List<String> agents = split(row.getAgents());
            List<String> sources = split(row.getSources());
            if (agentNeedle != null && !agents.contains(agentNeedle)) {
                continue;
            }
            if (sourceNeedle != null && !sources.contains(sourceNeedle)) {
                continue;
            }
            /*
             * 时间窗比的是「最近一次活动」，不是"把窗口内那几次挑出来"。
             * 用户的心智是"最近有没有动静"；按"窗口内有跑过"来筛，会让同一条会话
             * 在两次筛选里显示出不同的执行次数（因为聚合值也跟着被裁了），那更容易误解。
             */
            if (fromMs != null && row.getLastMs() < fromMs) {
                continue;
            }
            if (toMs != null && row.getLastMs() > toMs) {
                continue;
            }
            if (!needle.isEmpty()
                    && !contains(row.getUserId(), needle)
                    && !contains(row.getSessionId(), needle)) {
                continue;
            }

            List<String> tables = memories.getOrDefault(
                    AgentStateAdminService.slotId(row.getUserId(), row.getSessionId()), List.of());
            boolean remembered = !tables.isEmpty();
            /* 记忆筛选必须放在算出 tables 之后 —— 它是本层唯一要等跨库那一步回来才有答案的维度。 */
            if (hasState != null && hasState != remembered) {
                continue;
            }
            matched.add(new AgentSessionViews.Session(
                    row.getUserId(), row.getSessionId(), row.getRuns(), row.getFailed(), row.getTokens(),
                    agents, sources, row.getFirstMs(), row.getLastMs(), remembered, tables));
        }

        int size = Math.max(1, Math.min(limit <= 0 ? DEFAULT_PAGE_SIZE : limit, MAX_PAGE_SIZE));
        int from = Math.max(0, Math.min(offset, matched.size()));
        int to = Math.min(from + size, matched.size());
        return new AgentSessionViews.SessionPage(
                matched.size(), all.size(), new ArrayList<>(matched.subList(from, to)));
    }

    // ==================================================================================
    // 会话详情
    // ==================================================================================

    /**
     * 一个会话的执行时间线 + 记忆清单。
     *
     * <p>为什么把两者放在同一个响应里：页面上它们是同一个抽屉的两块内容，
     * 分两个接口只会让"打开抽屉"变成两次往返 + 两次 loading 状态。
     *
     * <p>{@code userId} 允许为 null（库里有 user_id 为 NULL 的会话），
     * 查询用 NULL 安全等值，见 {@link AgentSessionMapper#listRuns}。
     */
    public AgentSessionViews.SessionDetail detail(String userId, String sessionId) {
        String user = blankToNull(userId);

        List<AgentSessionViews.SessionRun> runs = new ArrayList<>();
        for (SessionRunRow row : mapper.listRuns(user, sessionId, MAX_RUNS_PER_SESSION)) {
            runs.add(toView(row));
        }

        List<AgentSessionViews.StateTable> states = new ArrayList<>();
        try {
            for (AgentStateViews.SessionStateRow row : stateService.sessionStates(user, sessionId)) {
                states.add(new AgentSessionViews.StateTable(
                        row.tableName(), row.agentName(), row.rows(), row.bytes(), row.updatedAt()));
            }
        } catch (RuntimeException e) {
            log.warn("[Session] 状态库读取失败，本次详情不带记忆清单 user={} session={}", user, sessionId, e);
        }

        return new AgentSessionViews.SessionDetail(user, sessionId, runs, states);
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    /**
     * slotId → 状态表清单；状态库读不到时返回空 Map 而不是抛出去。
     *
     * <p>典型失败是状态库名配错、或那个库还没建出来（框架首次使用时才建表）。
     * 这两种情况下页面该显示的是"没有记忆"，而不是一整页红色错误 —— 列表本身是好的。
     */
    private Map<String, List<String>> memoriesBySlotId() {
        try {
            return stateService.slotTables();
        } catch (RuntimeException e) {
            log.warn("[Session] 状态库读取失败，本次列表不带「记忆」标记", e);
            return Map.of();
        }
    }

    /**
     * 映射行 → 输出行：翻译状态词表、算耗时。
     *
     * <p>耗时对"进行中"的 run 是「至今耗时」（与链路列表口径一致）：
     * {@code ended_at_ms} 为 null 时取当前时刻，而不是 0 —— 后者会让页面显示"耗时 0ms"，
     * 看起来像"瞬间跑完了"，而事实正相反。
     */
    private static AgentSessionViews.SessionRun toView(SessionRunRow row) {
        long end = row.getEndedAtMs() == null ? System.currentTimeMillis() : row.getEndedAtMs();
        return new AgentSessionViews.SessionRun(
                row.getRunId(),
                row.getAgentId(),
                row.getAgentKind(),
                row.getSource(),
                AgentRunStatus.wire(row.getStatus()),
                AgentRunStatus.tone(row.getStatus()),
                Math.max(0L, end - row.getStartedAtMs()),
                row.getTokens(),
                row.getStartedAtMs(),
                row.getError());
    }

    /**
     * {@code GROUP_CONCAT} 出来的逗号分隔串 → 列表；null / 空串 → 空列表。
     *
     * <p>去重交给 SQL（{@code DISTINCT}），这里只负责切分与去空。不再排序一遍：
     * SQL 里已经 {@code ORDER BY}，在 Java 再排一次只会让"顺序由谁决定"多出一个答案。
     */
    private static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : joined.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return List.copyOf(parts);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
