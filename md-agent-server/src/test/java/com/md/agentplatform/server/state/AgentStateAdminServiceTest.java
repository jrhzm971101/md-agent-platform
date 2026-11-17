package com.md.agentplatform.server.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.AgentPlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 状态管理服务的单测 —— 覆盖四类"改坏了线上才会发现"的地方。
 *
 * <p>不用 Spring、不连库：Mapper 用 Mockito 替身，因此断言的是本类自己的契约
 * （守卫、拆串、时间归一、摘要裁剪），SQL 正确性由真实库的端到端验证负责
 * （动态表名、跨库子查询、{@code AS `rows`} 这些只能对着真 MySQL 才验得出来）。
 *
 * <p>其中三条来自实测踩坑，不是防御性断言：
 * <ol>
 *   <li><b>摘要必须裁剪</b>：构造一条 5 MB 的 {@code summary} 时，详情接口的响应体
 *       直接涨到 5.4 MB —— 这个接口存在的意义就是"别把 MB 级内容传给浏览器"，
 *       字段级不设上限等于上限不存在；</li>
 *   <li><b>时间必须归一成 ISO</b>：带空格的日期串在部分浏览器上解析成 Invalid Date，
 *       页面会显示"—"而不是时间；</li>
 *   <li><b>表名要先过白名单</b>：表名会以 {@code ${}} 方式拼进 SQL，
 *       注入尝试必须在碰库之前被挡掉（这里连 Mapper 都不该被调到）。</li>
 * </ol>
 */
class AgentStateAdminServiceTest {

    private AgentStateMapper mapper;
    private AgentStateAdminService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentStateMapper.class);
        AgentPlatformProperties properties = new AgentPlatformProperties();
        properties.setStateStoreDatabase("md_agent_state");
        service = new AgentStateAdminService(mapper, properties, new ObjectMapper());
    }

    // ==================== 守卫 ====================

    @Test
    void 表名非法时直接拒绝且不碰数据库() {
        StateStoreException e = assertThrows(StateStoreException.class,
                () -> service.listSessions("bad`;DROP", null, 20, 0));
        assertEquals(StateStoreException.Kind.INVALID, e.getKind());
        // 关键：非法表名必须在拼 SQL 之前就被挡住，绝不能出现"查一次再说"
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void 表名合法但库里没有时回404() {
        when(mapper.countTable("md_agent_state", "ghost")).thenReturn(0);
        StateStoreException e = assertThrows(StateStoreException.class,
                () -> service.listSessions("ghost", null, 20, 0));
        assertEquals(StateStoreException.Kind.NOT_FOUND, e.getKind());
    }

    @Test
    void 删除不存在的会话回404而不是静默成功() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.deleteSession("md_agent_state", "t", "u:s")).thenReturn(0);
        StateStoreException e = assertThrows(StateStoreException.class,
                () -> service.deleteSession("t", "u:s"));
        assertEquals(StateStoreException.Kind.NOT_FOUND, e.getKind());
    }

    // ==================== 拆串与时间归一 ====================

    @Test
    void 会话列表把槽位拆成用户与会话两列并把时间归一成ISO() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.listSessions("md_agent_state", "t")).thenReturn(List.of(
                slot("wo7U1:sub-abc", 10L, 1L, "2026-09-22 15:29:20")));

        AgentStateViews.SessionPage page = service.listSessions("t", null, 20, 0);
        StateSlotRow row = page.items().get(0);
        assertEquals("wo7U1", row.getUserId());
        assertEquals("sub-abc", row.getSessionId());
        assertEquals("2026-09-22T15:29:20", row.getUpdatedAt());
        assertEquals(1, page.total());
    }

    @Test
    void 没有分区前缀的槽位不编造用户而是标注未分区() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.listSessions("md_agent_state", "t")).thenReturn(List.of(
                slot("legacy-session", 10L, 1L, "2026-09-22 15:29:20")));

        StateSlotRow row = service.listSessions("t", null, 20, 0).items().get(0);
        assertEquals("(未分区)", row.getUserId());
        assertEquals("legacy-session", row.getSessionId());
    }

    // ==================== 关键字与分页 ====================

    @Test
    void 关键字同时匹配用户与会话且总数是过滤后的数() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.listSessions("md_agent_state", "t")).thenReturn(List.of(
                slot("codex:conv-a", 1L, 1L, "2026-09-22 15:00:00"),
                slot("codex:conv-b", 1L, 1L, "2026-09-22 15:01:00"),
                slot("wo7U1:other", 1L, 1L, "2026-09-22 15:02:00")));

        AgentStateViews.SessionPage page = service.listSessions("t", "conv-", 20, 0);
        assertEquals(2, page.total());
        assertEquals(3, page.tableTotal());

        AgentStateViews.SessionPage byUser = service.listSessions("t", "wo7U1", 20, 0);
        assertEquals(1, byUser.total());
    }

    @Test
    void 分页在内存里切片且越界不报错() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.listSessions("md_agent_state", "t")).thenReturn(List.of(
                slot("u:a", 1L, 1L, "2026-09-22 15:00:00"),
                slot("u:b", 1L, 1L, "2026-09-22 15:01:00"),
                slot("u:c", 1L, 1L, "2026-09-22 15:02:00")));

        assertEquals(2, service.listSessions("t", null, 2, 0).items().size());
        assertEquals(1, service.listSessions("t", null, 2, 2).items().size());
        // offset 超出范围应当回空列表，而不是抛越界 —— 前端翻页时总会碰到最后一页之后
        assertEquals(0, service.listSessions("t", null, 2, 99).items().size());
        // limit 非法值回落到默认，而不是"一次返回全表"
        assertEquals(3, service.listSessions("t", null, 0, 0).items().size());
        assertEquals(3, service.listSessions("t", null, -5, 0).items().size());
    }

    // ==================== 会话视角（供「会话记录」页） ====================

    /**
     * slotId 是 run 表与状态表之间唯一的桥：run 表把用户与会话分两列存，
     * 状态表把它们拼成一列。拼法写错不会报错，只会让会话页上所有行都显示"没有记忆"。
     */
    @Test
    void slotId按用户与会话拼接且任一段为空时不拼() {
        assertEquals("u:s", AgentStateAdminService.slotId("u", "s"));
        assertNull(AgentStateAdminService.slotId(null, "s"));
        assertNull(AgentStateAdminService.slotId("u", null));
        assertNull(AgentStateAdminService.slotId("", "s"));
        assertNull(AgentStateAdminService.slotId("u", "   "));
    }

    @Test
    void 会话视角把slotId按状态表归组() {
        when(mapper.listExistingTables("md_agent_state"))
                .thenReturn(List.of(table("workwx_batch_agent"), table("deployment_agent")));
        when(mapper.listSlotIds("md_agent_state", "workwx_batch_agent"))
                .thenReturn(List.of("u1:s1", "u2:s2"));
        when(mapper.listSlotIds("md_agent_state", "deployment_agent"))
                .thenReturn(List.of("u1:s1"));

        Map<String, List<String>> tables = service.slotTables();
        // 同一条会话被两个 agent 服务过 → 两张表里都有它，这不是异常而是常态
        assertEquals(List.of("workwx_batch_agent", "deployment_agent"), tables.get("u1:s1"));
        assertEquals(List.of("workwx_batch_agent"), tables.get("u2:s2"));
    }

    /**
     * 表名来自 information_schema，看起来"可信"，但它照样是要拼进 SQL 的串。
     * 白名单必须在拼之前生效 —— 这里连按表的查询都不该被调到。
     */
    @Test
    void 非法表名进不了会话视角的查询() {
        when(mapper.listExistingTables("md_agent_state")).thenReturn(List.of(table("bad`;DROP")));

        assertEquals(Map.of(), service.slotTables());
        assertEquals(List.of(), service.sessionStates("u", "s"));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .listSlotIds(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString());
    }

    @Test
    void 会话状态汇总多行并取最近一次写入() {
        when(mapper.listExistingTables("md_agent_state"))
                .thenReturn(List.of(table("workwx_batch_agent")));
        when(mapper.selectSessionSlots("md_agent_state", "workwx_batch_agent", "u1:s1"))
                .thenReturn(List.of(
                        slot("u1:s1", 100L, 1L, "2026-09-22 10:00:00"),
                        slot("u1:s1", 50L, 2L, "2026-09-22 11:00:00")));

        AgentStateViews.SessionStateRow row = service.sessionStates("u1", "s1").get(0);
        assertEquals("workwx_batch_agent", row.tableName());
        assertEquals("workwx_batch_agent", row.agentName());
        assertEquals(2, row.rows());
        assertEquals(150L, row.bytes());
        // 时间在出口处统一归一成 ISO（带空格的形态在部分浏览器上会解析成 Invalid Date）
        assertEquals("2026-09-22T11:00:00", row.updatedAt());
    }

    @Test
    void 用户或会话为空时不去碰任何状态表() {
        when(mapper.listExistingTables("md_agent_state"))
                .thenReturn(List.of(table("workwx_batch_agent")));

        assertEquals(List.of(), service.sessionStates(null, "s1"));
        assertEquals(List.of(), service.sessionStates("u1", " "));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .selectSessionSlots(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                        org.mockito.Mockito.anyString());
    }

    // ==================== 详情解析 ====================

    @Test
    void 详情解析出消息条数任务计划工具组与预览() throws Exception {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectSessionSlots("md_agent_state", "t", "u:s")).thenReturn(List.of(
                detail("u:s", "agent_state", 0, 123L, 7L)));
        when(mapper.selectStateData("md_agent_state", "t", "u:s", "agent_state", 0))
                .thenReturn("""
                        {"summary":"压缩过的摘要","cur_iter":3,"shutdown_interrupted":false,
                         "tasks_context":{"tasks":[{"id":"t1","subject":"做点事","state":"in_progress","owner":"x"}]},
                         "plan_mode_context":{"plan_active":true,"current_plan_file":"/plans/p.md"},
                         "tool_context":{"activated_groups":["g1","g2"]},
                         "permission_context":{"mode":"bypass"},
                         "context":[{"role":"USER","timestamp":"2026-09-22 10:00:00",
                                     "content":{"type":"text","text":"你好"}},
                                    {"role":"ASSISTANT","timestamp":"2026-09-22 10:00:01",
                                     "content":[{"type":"thinking","thinking":"想一下"},
                                                {"type":"tool_use","name":"mail_send"}]}]}
                        """);

        AgentStateViews.SessionDetail d = service.detail("t", "u:s");
        assertNull(d.parseError());
        assertEquals("u", d.userId());
        assertEquals("s", d.sessionId());
        assertEquals(123L, d.totalBytes());

        AgentStateViews.Snapshot s = d.snapshot();
        assertNotNull(s);
        assertEquals("压缩过的摘要", s.summary());
        assertEquals(2, s.messages());
        assertEquals(3, s.curIter());
        assertEquals(1, s.tasksTotal());
        assertEquals("做点事", s.tasks().get(0).subject());
        assertTrue(s.planActive());
        assertEquals("/plans/p.md", s.planFile());
        assertEquals(List.of("g1", "g2"), s.activatedToolGroups());
        assertEquals("bypass", s.permissionMode());
        assertEquals(2, s.preview().size());
        assertEquals("text", s.preview().get(0).kind());
        // 一条消息里既有思考又有工具调用 → mixed，且两段都被拼进同一行文本
        assertEquals("mixed", s.preview().get(1).kind());
        assertTrue(s.preview().get(1).text().contains("想一下"));
        assertTrue(s.preview().get(1).text().contains("mail_send"));
    }

    @Test
    void 超大摘要被裁剪到上限() throws Exception {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectSessionSlots("md_agent_state", "t", "u:s")).thenReturn(List.of(
                detail("u:s", "agent_state", 0, 100L, 1L)));
        String huge = "x".repeat(5 * 1024 * 1024);
        when(mapper.selectStateData("md_agent_state", "t", "u:s", "agent_state", 0))
                .thenReturn("{\"summary\":\"" + huge + "\"}");

        AgentStateViews.Snapshot s = service.detail("t", "u:s").snapshot();
        assertNotNull(s);
        // 2000 字 + 省略号
        assertEquals(2001, s.summary().length());
        assertTrue(s.summary().endsWith("…"));
    }

    @Test
    void 解析失败时仍返回详情并带上原因() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectSessionSlots("md_agent_state", "t", "u:s")).thenReturn(List.of(
                detail("u:s", "agent_state", 0, 5L, 1L)));
        when(mapper.selectStateData("md_agent_state", "t", "u:s", "agent_state", 0))
                .thenReturn("{不是合法 JSON");

        AgentStateViews.SessionDetail d = service.detail("t", "u:s");
        assertNull(d.snapshot());
        assertNotNull(d.parseError());
        // 解析失败不改变状态码：会话存在，页面必须还能删它
        assertEquals(5L, d.totalBytes());
    }

    @Test
    void 没有agent_state槽位时给出明确原因() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectSessionSlots("md_agent_state", "t", "u:s")).thenReturn(List.of(
                detail("u:s", "memory_messages", 0, 5L, 1L)));

        AgentStateViews.SessionDetail d = service.detail("t", "u:s");
        assertNull(d.snapshot());
        assertTrue(d.parseError().contains("agent_state"));
    }

    // ==================== 原文体积上限 ====================

    @Test
    void 原文超过上限回413而不是截断() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectSessionSlots("md_agent_state", "t", "u:s")).thenReturn(List.of(
                detail("u:s", "agent_state", 0, 5L * 1024 * 1024, 1L)));

        StateStoreException e = assertThrows(StateStoreException.class,
                () -> service.raw("t", "u:s"));
        assertEquals(StateStoreException.Kind.TOO_LARGE, e.getKind());
        // 关键：超限时不读出内容。截断后的 JSON 不是合法文档，前端会报更难懂的错误
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .selectStateData(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    // ==================== 表清单 ====================

    @Test
    void 表清单同时给出已有表与尚未创建的表() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        StateTableRow existing = new StateTableRow();
        existing.setTableName("test_agent");
        existing.setAgentName("test_agent");
        when(mapper.listExistingTables("md_agent_state")).thenReturn(List.of(existing));
        when(mapper.tableStats("md_agent_state", "test_agent")).thenReturn(stats(3L, 100L, "2026-09-22 15:00:00"));
        when(mapper.countSessions("md_agent_state", "test_agent")).thenReturn(2L);

        StateTableRow missing = new StateTableRow();
        missing.setTableName("fresh_agent");
        missing.setAgentName("fresh_agent");
        when(mapper.listDeclaredMissingTables("md_agent_state")).thenReturn(List.of(missing));

        List<AgentStateViews.TableView> views = service.listTables();
        assertEquals(2, views.size());

        AgentStateViews.TableView created = views.get(0);
        assertEquals("fresh_agent", created.tableName());
        assertTrue(!created.exists());

        AgentStateViews.TableView real = views.get(1);
        assertEquals("test_agent", real.tableName());
        assertTrue(real.exists());
        assertEquals(2L, real.sessions());
        assertEquals(3L, real.rows());
        assertEquals(100L, real.bytes());
        assertEquals("2026-09-22T15:00:00", real.lastWriteAt());
    }

    @Test
    void 孤儿表回传空的agentName而不是编一个() {
        StateTableRow orphan = new StateTableRow();
        orphan.setTableName("k8s_agent");
        orphan.setAgentName(null);
        when(mapper.listExistingTables("md_agent_state")).thenReturn(List.of(orphan));
        when(mapper.tableStats("md_agent_state", "k8s_agent")).thenReturn(stats(0L, 0L, null));

        AgentStateViews.TableView view = service.listTables().get(0);
        assertNull(view.agentName());
        assertNull(view.lastWriteAt());
    }

    // ==================== 会话回放（对话页用） ====================

    /**
     * 回放要的是"三段分开"，不是预览那种拼成一行。
     *
     * <p>这条钉的是接口存在的理由：对话页要把正文按 Markdown 渲染、把思考折进折叠区、
     * 把工具名挂成一行提示。拼在一起再拆开是不可能的，所以三段必须各自成字段。
     * 顺带钉住工具结果不进正文 —— 它是"过程"不是"对话"。
     */
    @Test
    void 会话回放把正文思考与工具名分开且丢掉工具结果() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectStateData("md_agent_state", "t", "u:s", "agent_state", 0))
                .thenReturn("{\"context\":["
                        + "{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"帮我看下昨天日报\"}],\"timestamp\":\"t1\"},"
                        + "{\"role\":\"ASSISTANT\",\"content\":["
                        + "{\"type\":\"thinking\",\"thinking\":\"先想一下\"},"
                        + "{\"type\":\"tool_use\",\"name\":\"taskList\"},"
                        + "{\"type\":\"text\",\"text\":\"好的\"}],\"timestamp\":\"t2\"},"
                        + "{\"role\":\"TOOL\",\"content\":[{\"type\":\"tool_result\",\"name\":\"taskList\","
                        + "\"output\":[{\"text\":\"一大段表格结果\"}]}],\"timestamp\":\"t3\"}"
                        + "]}");

        List<AgentStateViews.Message> messages = service.messages("t", "u:s", 200);
        assertEquals(3, messages.size());

        AgentStateViews.Message user = messages.get(0);
        assertEquals("USER", user.role());
        assertEquals("帮我看下昨天日报", user.text());
        assertEquals("", user.thinking());
        assertTrue(user.tools().isEmpty());

        AgentStateViews.Message assistant = messages.get(1);
        assertEquals("ASSISTANT", assistant.role());
        assertEquals("好的", assistant.text());
        assertEquals("先想一下", assistant.thinking());
        assertEquals(List.of("taskList"), assistant.tools());

        // 工具结果整条都不该漏进正文 —— 几千字的查询结果铺满页面会让人找不到对话本身
        for (AgentStateViews.Message message : messages) {
            assertTrue(!message.text().contains("一大段表格结果"),
                    "工具结果的正文漏进回放了: " + message.text());
        }
    }

    /** 只给最近 limit 条：一条跑了一年的定时任务会话有几千条消息，全给没有意义。 */
    @Test
    void 会话回放只取最近若干条() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectStateData("md_agent_state", "t", "u:s", "agent_state", 0))
                .thenReturn("{\"context\":["
                        + "{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"第一条\"}]},"
                        + "{\"role\":\"ASSISTANT\",\"content\":[{\"type\":\"text\",\"text\":\"第二条\"}]},"
                        + "{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"第三条\"}]}"
                        + "]}");

        List<AgentStateViews.Message> messages = service.messages("t", "u:s", 2);
        assertEquals(2, messages.size());
        assertEquals("第二条", messages.get(0).text());
        assertEquals("第三条", messages.get(1).text());
    }

    /** 没有 agent_state 槽位 = 这个会话没跑过或没开状态存储，页面要能区分它和"空会话"。 */
    @Test
    void 会话没有agent_state槽位时回404() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectStateData("md_agent_state", "t", "u:s", "agent_state", 0)).thenReturn(null);

        StateStoreException e = assertThrows(StateStoreException.class,
                () -> service.messages("t", "u:s", 200));
        assertEquals(StateStoreException.Kind.NOT_FOUND, e.getKind());
    }

    /**
     * 解析不了回 400 而不是 500。
     *
     * <p>状态是框架内部对象的序列化结果，形态由框架版本决定 —— 这段文本不是我们认识的
     * 形态（版本错位、被手工改过）属于"库里那段内容不对"，不是服务端故障。
     * 两者的区别就体现在页面上的那句文案，所以状态码必须分开。
     */
    @Test
    void 会话回放解析失败回400而不是500() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectStateData("md_agent_state", "t", "u:s", "agent_state", 0))
                .thenReturn("这不是 JSON");

        StateStoreException e = assertThrows(StateStoreException.class,
                () -> service.messages("t", "u:s", 200));
        assertEquals(StateStoreException.Kind.INVALID, e.getKind());
    }

    /** context 缺失（或不是数组）= 这个会话还没有消息，是正常状态，不是错误。 */
    @Test
    void 会话没有消息时回空列表而不是报错() {
        when(mapper.countTable("md_agent_state", "t")).thenReturn(1);
        when(mapper.selectStateData("md_agent_state", "t", "u:s", "agent_state", 0))
                .thenReturn("{\"session_id\":\"u:s\",\"cur_iter\":0}");

        assertTrue(service.messages("t", "u:s", 200).isEmpty());
    }

    // ==================== 造数据 ====================

    private static StateSlotRow slot(String slotId, Long bytes, Long version, String updatedAt) {
        StateSlotRow row = new StateSlotRow();
        row.setSlotId(slotId);
        row.setBytes(bytes);
        row.setVersion(version);
        row.setUpdatedAt(updatedAt);
        return row;
    }

    private static StateSlotRow detail(String slotId, String key, int index, Long bytes, Long version) {
        StateSlotRow row = new StateSlotRow();
        row.setSlotId(slotId);
        row.setStateKey(key);
        row.setItemIndex(index);
        row.setBytes(bytes);
        row.setVersion(version);
        return row;
    }

    private static StateSlotRow stats(Long rows, Long bytes, String updatedAt) {
        StateSlotRow row = new StateSlotRow();
        row.setRows(rows);
        row.setBytes(bytes);
        row.setUpdatedAt(updatedAt);
        return row;
    }

    /** 一张"库里真实存在、且有 agent 认领"的状态表。 */
    private static StateTableRow table(String tableName) {
        StateTableRow row = new StateTableRow();
        row.setTableName(tableName);
        row.setAgentName(tableName);
        return row;
    }
}
