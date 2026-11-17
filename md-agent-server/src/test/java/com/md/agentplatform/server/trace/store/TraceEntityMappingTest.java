package com.md.agentplatform.server.trace.store;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.store.entity.AgentRunEntity;
import com.md.agentplatform.server.trace.store.entity.AgentRunEventEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行模型 ↔ 领域对象的映射测试。
 *
 * <p>这一层是"重启后数据还在、而且是对的"的最后一公里：SQL 写对了、JSON 列存对了，
 * 但映射漏一个字段（比如 systemPrompt、endedAtMs），页面上就是"某块永远空白"，
 * 而且不报任何错。所以每个字段都断言一遍。
 *
 * <p>另有两条断言专门盯住最容易踩的两个坑：
 * <ul>
 *   <li><b>进行中的 run</b>：领域模型用 {@code 0} 表示"还没结束"、行模型用 {@code null}，
 *       翻译必须在<b>两侧都做</b>，否则表里会出现 1970-01-01（耗时统计凭空变大）；</li>
 *   <li><b>JSON 列</b>：直接调用 {@link JacksonTypeHandler} 的 toJson / parse 走一遍往返，
 *       证明"存进去能读回来"。注意这<b>不能</b>替代 {@code autoResultMap = true} 的检查
 *       —— 那一项只能在真库上验（见 {@code MyBatisTraceStoreIT}）。</li>
 * </ul>
 */
class TraceEntityMappingTest {

    /** 完整字段的往返：running → done，两次都要逐字段对上。 */
    @Test
    void runRoundTripsThroughTheRowModel() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messageCount", 2);
        input.put("messages", List.of("role=user text=\"查一下工时\""));

        AgentTraceRun original = new AgentTraceRun("t3k9f2a1-x7b2q9", "workwx_batch_agent",
                AgentTraceRun.AGENT_KIND_HARNESS, "sess-1", "ou_123", input,
                AgentTraceRun.SOURCE_WECOM);

        // --- 进行中：endedAtMs 必须落成 null ---
        AgentRunEntity running = AgentRunEntity.of(original);
        assertEquals("t3k9f2a1-x7b2q9", running.getRunId());
        assertEquals(AgentTraceRun.STATUS_RUNNING, running.getStatus());
        assertEquals(AgentTraceRun.SOURCE_WECOM, running.getSource(),
                "来源要跟着 run 一起落库：它是这条链路从哪来的唯一记录，事后无法反推");
        assertEquals(original.getStartedAtMs(), running.getStartedAtMs());
        assertNull(running.getEndedAtMs(), "进行中的 run 落库时 ended_at_ms 必须是 NULL，而不是 0");

        // --- 收口：状态 / 结束时刻 / token / 错误摘要 / 系统提示词都要跟着走 ---
        original.finish(AgentTraceRun.STATUS_DONE, 10, 20, 3, null);
        original.setSystemPrompt("you are a timesheet auditor");
        AgentRunEntity finished = AgentRunEntity.of(original);
        assertEquals(AgentTraceRun.STATUS_DONE, finished.getStatus());
        assertEquals(original.getEndedAtMs(), finished.getEndedAtMs().longValue());
        assertEquals(10, finished.getInputTokens());
        assertEquals(20, finished.getOutputTokens());
        assertEquals(3, finished.getCachedTokens());
        assertEquals("you are a timesheet auditor", finished.getSystemPrompt());

        // --- 回放：行模型 → 领域对象，必须还原成"当时的样子" ---
        AgentTraceRun restored = MyBatisTraceStore.toDomain(finished, List.of());
        assertEquals(original.getRunId(), restored.getRunId());
        assertEquals(original.getAgentKind(), restored.getAgentKind());
        assertEquals(original.getStartedAtMs(), restored.getStartedAtMs(),
                "开始时刻必须还原成库里的值，不能取回放这一刻的时间");
        assertEquals(original.getEndedAtMs(), restored.getEndedAtMs());
        assertEquals(AgentTraceRun.STATUS_DONE, restored.getStatus());
        assertEquals(10, restored.getInputTokens());
        assertEquals("you are a timesheet auditor", restored.getSystemPrompt());
        assertEquals(2, restored.getInput().get("messageCount"));
        assertEquals(AgentTraceRun.SOURCE_WECOM, restored.getSource(), "来源必须往返一致");
        assertTrue(restored.getEvents().isEmpty(), "列表路径的 run 不带事件（约定见 TraceStore 注释）");
    }

    /** 结束时刻为 0（还没结束）时，回放不能把它变成非零值。 */
    @Test
    void runningRunKeepsZeroEndedAtAfterRestore() {
        AgentTraceRun original = new AgentTraceRun("t1-a", "a", AgentTraceRun.AGENT_KIND_REACT,
                "s", "u", Map.of(), null);
        AgentTraceRun restored = MyBatisTraceStore.toDomain(AgentRunEntity.of(original), List.of());

        assertEquals(0L, restored.getEndedAtMs(), "进行中的 run 回放后 endedAtMs 仍应为 0");
        assertEquals(AgentTraceRun.STATUS_RUNNING, restored.getStatus());
    }

    /**
     * 事件行往返 + seq 计数器推进。
     *
     * <p>seq 计数器那一条是防"回放出来的 run 再写入时撞号"的：撞号的表现在页面是
     * "少了一个节点"（唯一键把重复行挡掉了，而异步写入失败只记日志）。
     */
    @Test
    void eventsRoundTripAndSeqCounterIsRestored() {
        AgentTraceEvent started = AgentTraceEvent.started(2, AgentTraceEvent.PHASE_REASONING, "第 1 轮推理",
                1_700_000_000_000L, Map.of("iteration", 1));
        AgentTraceEvent completed = AgentTraceEvent.completed(3, 2, AgentTraceEvent.PHASE_REASONING,
                "第 1 轮推理", 1_700_000_000_000L, 42L,
                Map.of("model", "deepseek-v4-flash", "totalTokens", 30), null);

        AgentRunEventEntity startedRow = AgentRunEventEntity.of("t1-a", started);
        AgentRunEventEntity completedRow = AgentRunEventEntity.of("t1-a", completed);

        assertEquals("t1-a", startedRow.getRunId());
        assertEquals(2, startedRow.getSeq());
        assertEquals(2, startedRow.getStageSeq(), "STARTED 事件的 stageSeq 等于自己的 seq");
        assertEquals(AgentTraceEvent.EVENT_STARTED, startedRow.getEventType());
        assertEquals(AgentTraceEvent.STATUS_RUNNING, startedRow.getStatus());
        assertNull(startedRow.getOutput(), "STARTED 只带入参，不带出参");

        assertEquals(3, completedRow.getSeq());
        assertEquals(2, completedRow.getStageSeq(), "COMPLETED 靠 stageSeq 回到它所属的 STARTED");
        assertEquals(AgentTraceEvent.STATUS_OK, completedRow.getStatus());
        assertNull(completedRow.getInput(), "COMPLETED 只带出参，不带入参");
        assertTrue(completedRow.getCreatedAtMs() > 0, "落库时刻必须打上");

        // 行 → 事件：必须逐字段回来
        AgentTraceEvent back = MyBatisTraceStore.toEvent(completedRow);
        assertEquals(completed.seq(), back.seq());
        assertEquals(completed.stageSeq(), back.stageSeq());
        assertEquals(completed.phase(), back.phase());
        assertEquals(completed.title(), back.title());
        assertEquals(completed.status(), back.status());
        assertEquals(completed.stageStartMs(), back.stageStartMs());
        assertEquals(completed.costMs(), back.costMs());
        assertEquals(completed.output(), back.output());

        // seq 计数器：回放 2、3 之后下一个应该是 4
        AgentTraceRun run = new AgentTraceRun("t1-a", "a", AgentTraceRun.AGENT_KIND_REACT, "s", "u",
                Map.of(), null);
        run.restoreEvents(List.of(MyBatisTraceStore.toEvent(startedRow), back));
        assertEquals(4, run.nextSeq(), "nextSeq 必须从已有事件之后继续，否则会与已落库事件撞号");
    }

    /** 错误事件：status 跟着 error 走，两者不会只有一个被写进去。 */
    @Test
    void failedStageCarriesErrorAndErrorStatus() {
        AgentTraceEvent failed = AgentTraceEvent.completed(5, 4, AgentTraceEvent.PHASE_ACTING,
                "工具调用 · git_clone", 1_700_000_000_000L, 7L, Map.of("state", "ERROR"),
                "IllegalStateException: boom");

        AgentRunEventEntity row = AgentRunEventEntity.of("t1-a", failed);
        assertEquals(AgentTraceEvent.STATUS_ERROR, row.getStatus());
        assertEquals("IllegalStateException: boom", row.getError());
        assertEquals(AgentTraceEvent.STATUS_ERROR, MyBatisTraceStore.toEvent(row).status());
    }

    /**
     * JSON 列的往返（直接驱动 typeHandler）。
     *
     * <p>值里刻意放中文与嵌套结构：utf8mb4 的字符集问题、Map 里混类型的问题都会在这里露头。
     */
    @Test
    void jsonColumnSurvivesARoundTrip() {
        JacksonTypeHandler handler = new JacksonTypeHandler(Map.class);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", "smartsheet_records_list");
        payload.put("args", "{\"sheet\":\"MD-timesheet-new\"}");
        payload.put("result", "{\"name\":\"张三\"}");
        payload.put("resultLength", 24);
        payload.put("dataBlocks", List.of("Map"));
        payload.put("cached", true);

        Object restored = handler.parse(handler.toJson(payload));

        assertEquals(payload, restored, "JSON 列必须能原样读回来（值、嵌套、中文）");
    }
}
