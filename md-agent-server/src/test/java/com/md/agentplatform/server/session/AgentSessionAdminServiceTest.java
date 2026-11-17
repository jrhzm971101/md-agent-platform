package com.md.agentplatform.server.session;

import com.md.agentplatform.server.state.AgentStateAdminService;
import com.md.agentplatform.server.state.AgentStateViews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话管理服务的单测 —— 只钉"改坏了线上才会发现"的几条。
 *
 * <p>不启 Spring、不连库：Mapper 与状态服务都是 Mockito 替身，因此这里断言的是本类自己的契约
 * （筛选口径、跨库对齐、降级、分页、词表翻译）。SQL 正确性由真库上的端到端调用负责 ——
 * {@code GROUP_CONCAT} 的顺序、{@code user_id <=> ?} 的 NULL 语义、{@code LIMIT} 的位置，
 * 这些只能对着真 MySQL 才验得出来。
 *
 * <p>列在前面的几条都有一个共同点：它们错了<b>不会有任何报错</b>，只会让页面安静地少几行、
 * 或者安静地显示成另一种意思 —— 这正是单测最该覆盖的那一类。
 */
class AgentSessionAdminServiceTest {

    private AgentSessionMapper mapper;
    private AgentStateAdminService stateService;
    private AgentSessionAdminService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentSessionMapper.class);
        stateService = mock(AgentStateAdminService.class);
        service = new AgentSessionAdminService(mapper, stateService);
        // 默认"这个库里没有任何记忆"，需要记忆的用例各自覆写。
        when(stateService.slotTables()).thenReturn(Map.of());
    }

    // ==================== agent 是筛选维度，不是归属维度 ====================

    /**
     * 这条是整个「会话记录」页立论的基础：同一个 sessionId 会被多个 agent 服务
     * （实测：企微里同一条会话先交给 workwx_batch_agent 又交给 deployment_agent，sessionId 没变）。
     *
     * <p>如果哪天有人把 agents 改成"取第一个"或"取最后一个"，页面会开始撒谎 ——
     * 而它看起来完全正常：一列一个名字，谁会怀疑少了几个。所以必须钉死。
     */
    @Test
    void 同一会话被多个agent服务时agents是完整列表且各自都能筛到它() {
        when(mapper.aggregateSessions()).thenReturn(List.of(
                aggregated("wo7U1", "single-abc", 6, 1, 1200,
                        "deployment_agent,workwx_batch_agent", "wecom", 1_000L, 9_000L)));

        AgentSessionViews.SessionPage page = service.list(null, null, null, null, null, null, 20, 0);
        assertEquals(1, page.total());
        assertEquals(List.of("deployment_agent", "workwx_batch_agent"), page.items().get(0).agents());

        // 两个 agent 各自筛都能命中同一条会话
        assertEquals(1, service.list("workwx_batch_agent", null, null, null, null, null, 20, 0).total());
        assertEquals(1, service.list("deployment_agent", null, null, null, null, null, 20, 0).total());
        // 没参与过的 agent 一条都不该命中（用 contains 而不是 equals 的边界也在这里被钉住）
        assertEquals(0, service.list("git_agent", null, null, null, null, null, 20, 0).total());
        // 子串不算命中：agentId 是精确匹配
        assertEquals(0, service.list("deployment", null, null, null, null, null, 20, 0).total());
    }

    // ==================== 跨库对齐 ====================

    /**
     * 记忆标记靠 {@code userId + ":" + sessionId} 拼出的 slotId 与状态表对齐。
     *
     * <p>这条键是 run 表与状态表之间唯一的桥：run 表把用户与会话分两列存，
     * 状态表把它们拼成一列。拼错了不会报错，只会让所有会话都显示"没有记忆"。
     */
    @Test
    void 记忆标记用用户与会话拼成的slotId对齐状态库() {
        when(mapper.aggregateSessions()).thenReturn(List.of(
                aggregated("wo7U1", "single-abc", 2, 0, 10, "a", "wecom", 1L, 2L),
                aggregated("wo7U1", "single-xyz", 1, 0, 10, "a", "wecom", 1L, 2L)));
        when(stateService.slotTables()).thenReturn(
                Map.of("wo7U1:single-abc", List.of("workwx_batch_agent")));

        List<AgentSessionViews.Session> items = service.list(null, null, null, null, null, null, 20, 0).items();
        AgentSessionViews.Session withState = items.stream()
                .filter(row -> "single-abc".equals(row.sessionId())).findFirst().orElseThrow();
        AgentSessionViews.Session withoutState = items.stream()
                .filter(row -> "single-xyz".equals(row.sessionId())).findFirst().orElseThrow();

        assertTrue(withState.hasState());
        assertEquals(List.of("workwx_batch_agent"), withState.stateTables());
        assertFalse(withoutState.hasState());
        assertEquals(List.of(), withoutState.stateTables());
    }

    /**
     * 有没有记忆是一个<b>服务端</b>筛选维度，不是页面在当页数据上筛。
     *
     * <p>会话是分页返回的：让前端在当页那 20 行里筛，用户看到的是"共 57 个会话、筛出 3 条" ——
     * 那 3 条是全部符合的，还是只是第一页里符合的，页面自己也不确定。
     * 这条断言钉的是 hasState 真的参与了过滤，而且"只看没有记忆的"(FALSE) 与
     * "不限"(null) 不会互相混淆 —— 这两者在 Java 里只差一个自动拆箱。
     */
    @Test
    void 记忆筛选在服务端生效且不与不限混淆() {
        when(mapper.aggregateSessions()).thenReturn(List.of(
                aggregated("u1", "s-keep", 3, 0, 5, "a", "wecom", 1L, 2L),
                aggregated("u1", "s-empty", 1, 0, 5, "a", "wecom", 1L, 2L)));
        when(stateService.slotTables()).thenReturn(Map.of("u1:s-keep", List.of("a")));

        assertEquals(List.of("s-keep"), sessionIdsOf(true));
        assertEquals(List.of("s-empty"), sessionIdsOf(false));
        // null = 不限：两条都在
        assertEquals(List.of("s-keep", "s-empty"), sessionIdsOf(null));
        // allTotal 永远是不带筛选的总数，不会被筛选影响 —— 页面用它显示"共 N 个会话"
        assertEquals(2, service.list(null, null, null, null, null, true, 20, 0).allTotal());
    }

    /** 筛一遍，把命中的 sessionId 按返回顺序取出来。 */
    private List<String> sessionIdsOf(Boolean hasState) {
        return service.list(null, null, null, null, null, hasState, 20, 0).items()
                .stream().map(AgentSessionViews.Session::sessionId).toList();
    }

    /**
     * 状态库读不到时，列表必须照常返回，只是不带记忆标记。
     *
     * <p>典型诱因是状态库名配错、或那个库还没建出来（框架首次使用时才建表）。
     * 反过来做（让它冒出去）等于"一个辅助标记能让整页 500" —— 列表的其余内容明明都在业务库里。
     */
    @Test
    void 状态库读失败时降级为无记忆而不是整页失败() {
        when(mapper.aggregateSessions()).thenReturn(List.of(
                aggregated("u1", "s1", 1, 0, 5, "a", null, 1L, 2L)));
        when(stateService.slotTables()).thenThrow(new IllegalStateException("state db down"));

        AgentSessionViews.Session row = service.list(null, null, null, null, null, null, 20, 0).items().get(0);
        assertFalse(row.hasState());
        assertEquals(List.of(), row.stateTables());
        // GROUP_CONCAT 全为 NULL 时回 null → 空列表，而不是一个装着 "null" 的列表
        assertEquals(List.of(), row.sources());
    }

    // ==================== 筛选口径 ====================

    @Test
    void 时间窗比的是最近一次活动且关键字同时匹配用户与会话() {
        when(mapper.aggregateSessions()).thenReturn(List.of(
                aggregated("u1", "s-old", 2, 0, 5, "a", "wecom", 1_000L, 2_000L),
                aggregated("u2", "s-new", 2, 0, 5, "a", "feishu", 1_000L, 8_000L)));

        // 下界 5s：只有最近活动在 5s 之后的那条命中
        List<AgentSessionViews.Session> inWindow =
                service.list(null, null, 5_000L, null, null, null, 20, 0).items();
        assertEquals(1, inWindow.size());
        assertEquals("s-new", inWindow.get(0).sessionId());
        // 但它的 runs 仍是全量（2 次），不是"窗口内那几次" —— 会话是聚合单位
        assertEquals(2, inWindow.get(0).runs());

        assertEquals(0, service.list(null, null, null, 1_500L, null, null, 20, 0).total());
        assertEquals("u2", service.list(null, null, null, null, "u2", null, 20, 0).items().get(0).userId());
        assertEquals("s-old", service.list(null, null, null, null, "s-old", null, 20, 0).items().get(0).sessionId());
        assertEquals("s-new", service.list(null, null, null, null, "NEW", null, 20, 0).items().get(0).sessionId());
        // 来源筛选同理：sources 是列表，按项精确匹配
        assertEquals(1, service.list(null, "feishu", null, null, null, null, 20, 0).total());
        assertEquals(0, service.list(null, "console", null, null, null, null, 20, 0).total());
    }

    @Test
    void 分页在内存里切片且总数是过滤后的数() {
        when(mapper.aggregateSessions()).thenReturn(List.of(
                aggregated("u", "a", 1, 0, 1, "x", null, 3L, 3_000L),
                aggregated("u", "b", 1, 0, 1, "x", null, 2L, 2_000L),
                aggregated("u", "c", 1, 0, 1, "x", null, 1L, 1_000L)));

        AgentSessionViews.SessionPage first = service.list(null, null, null, null, null, null, 2, 0);
        assertEquals(3, first.total());
        assertEquals(3, first.allTotal());
        assertEquals(List.of("a", "b"), first.items().stream().map(AgentSessionViews.Session::sessionId).toList());

        assertEquals(1, service.list(null, null, null, null, null, null, 2, 2).items().size());
        // offset 越界回空列表而不是抛越界 —— 前端翻到最后一页之后总会碰到
        assertEquals(0, service.list(null, null, null, null, null, null, 2, 99).items().size());
        // limit 非法值回落默认（20），而不是"一次返回全表"
        assertEquals(3, service.list(null, null, null, null, null, null, 0, 0).items().size());
        assertEquals(3, service.list(null, null, null, null, null, null, -1, 0).items().size());
        // allTotal 不受筛选影响：页面要靠它显示「筛选后 X / 共 Y」
        assertEquals(3, service.list("nobody", null, null, null, null, null, 2, 0).allTotal());
    }

    // ==================== 详情 ====================

    @Test
    void 详情翻译状态词表并给进行中的run算至今耗时() {
        when(mapper.listRuns("u1", "s1", AgentSessionAdminService.MAX_RUNS_PER_SESSION))
                .thenReturn(List.of(
                        run("r-done", "DONE", 1_000L, 1_500L, 30, null),
                        run("r-bad", "ERROR", 2_000L, 2_100L, 7, "boom"),
                        run("r-running", "RUNNING", System.currentTimeMillis() - 50L, null, 0, null)));
        when(stateService.sessionStates("u1", "s1")).thenReturn(List.of(
                new AgentStateViews.SessionStateRow("workwx_batch_agent", "workwx_batch_agent", 1, 2_048L,
                        "2026-09-22T10:00:00")));

        AgentSessionViews.SessionDetail detail = service.detail("u1", "s1");
        assertEquals("u1", detail.userId());
        assertEquals(3, detail.runs().size());

        AgentSessionViews.SessionRun done = detail.runs().get(0);
        assertEquals("success", done.status());
        assertEquals("ok", done.tone());
        assertEquals(500L, done.durationMs());
        assertEquals("wecom", done.source());

        AgentSessionViews.SessionRun failed = detail.runs().get(1);
        assertEquals("failed", failed.status());
        assertEquals("err", failed.tone());
        assertEquals("boom", failed.error());

        // 进行中：耗时是"至今"，不是 0（0 看起来像"瞬间跑完了"，而事实正相反）
        AgentSessionViews.SessionRun running = detail.runs().get(2);
        assertEquals("running", running.status());
        assertEquals("run", running.tone());
        assertTrue(running.durationMs() >= 0L);

        assertEquals(1, detail.states().size());
        assertEquals("workwx_batch_agent", detail.states().get(0).tableName());
        assertEquals(2_048L, detail.states().get(0).bytes());
    }

    /**
     * userId 为空（库里有 user_id 为 NULL 的会话）与 sessionId 里的非常规字符都要能查下去。
     *
     * <p>空 userId 必须原样传成 null —— 传成空串会走 {@code user_id <=> ''}，
     * 那批会话的详情会永远查不出东西，而现象是"列表有、点进去空的"，
     * 很难联系到"空串与 NULL 不是一回事"上。
     */
    @Test
    void 空userId原样传null且状态服务故障时详情照常返回() {
        when(mapper.listRuns(null, "s1", AgentSessionAdminService.MAX_RUNS_PER_SESSION))
                .thenReturn(List.of());
        when(stateService.sessionStates(null, "s1"))
                .thenThrow(new IllegalStateException("state db down"));

        AgentSessionViews.SessionDetail detail = service.detail("  ", "s1");
        assertNull(detail.userId());
        assertEquals(List.of(), detail.runs());
        assertEquals(List.of(), detail.states());
        verify(mapper).listRuns(null, "s1", AgentSessionAdminService.MAX_RUNS_PER_SESSION);
    }

    // ==================== 造数据 ====================

    private static SessionAggregateRow aggregated(String userId, String sessionId, long runs, long failed,
                                                  long tokens, String agents, String sources,
                                                  long firstMs, long lastMs) {
        SessionAggregateRow row = new SessionAggregateRow();
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setRuns(runs);
        row.setFailed(failed);
        row.setTokens(tokens);
        row.setAgents(agents);
        row.setSources(sources);
        row.setFirstMs(firstMs);
        row.setLastMs(lastMs);
        return row;
    }

    private static SessionRunRow run(String runId, String status, long startedAtMs, Long endedAtMs,
                                     long tokens, String error) {
        SessionRunRow row = new SessionRunRow();
        row.setRunId(runId);
        row.setAgentId("workwx_batch_agent");
        row.setAgentKind("harness");
        row.setSource("wecom");
        row.setStatus(status);
        row.setStartedAtMs(startedAtMs);
        row.setEndedAtMs(endedAtMs);
        row.setTokens(tokens);
        row.setError(error);
        return row;
    }
}
