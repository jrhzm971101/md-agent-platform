package com.md.agentplatform.server.chat;

import com.md.agentplatform.server.agent.AgentManager;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话流翻译层的单元测试。
 *
 * <p>测的是「AgentEvent 事件流 → ChatStreamEvent 帧流」这条映射，不碰任何模型 / 网络：
 * 用匿名子类把 {@code AgentManager} 换成一个能吐指定事件的桩，
 * 因此这里跑的是真链路（含 defer、concatWith、onErrorResume 的组合语义），
 * 唯一被替换掉的只有「事件从哪来」。
 *
 * <p>为什么值得为这一层单独写测试：帧的<b>顺序与条数</b>是前端的硬契约 ——
 * 首帧必须是 run（前端靠它拿会话键）、末帧必须是 done（前端靠它收尾）。
 * 这类错误在编译期看不见，在页面上表现为"一直转圈"或"会话记不住"，
 * 而那时候你八成会先去怀疑前端。
 */
class ChatStreamServiceTest {

    private static final String AGENT = "t_agent";

    // ==================================================================================
    // 会话键
    // ==================================================================================

    /**
     * harness 会把会话键当成任务文件名。带冒号的键能过框架自己的校验，
     * 但在 Windows 上会让 Path.of 抛异常 —— 这条坑在企微链路上已经踩过一次。
     */
    @Test
    void sessionIdIsFileNameSafe() {
        String sessionId = ChatStreamService.resolveSessionId("single:user/1");

        assertFalse(sessionId.matches(".*[:*?\"<>|/\\\\].*"),
                "会话键含 Windows 文件名非法字符，harness 写任务文件时会崩: " + sessionId);
        assertTrue(sessionId.matches("[A-Za-z0-9._-]+"), "字符集被收敛后应当是安全集: " + sessionId);
    }

    @Test
    void sessionIdFallsBackToGeneratedAndDiffersPerCall() {
        String first = ChatStreamService.resolveSessionId(null);
        String second = ChatStreamService.resolveSessionId("   ");

        assertTrue(first.startsWith("console-"), "没给会话键时应现生成一个: " + first);
        assertNotEquals(first, second, "两次调用必须是两个会话，否则所有用户的对话会串进同一份记忆");
    }

    /** 会话键会落到磁盘路径上，必须限长（框架上限 255，这里收紧到 120 留余量）。 */
    @Test
    void sessionIdIsTruncated() {
        String sessionId = ChatStreamService.resolveSessionId("a".repeat(300));

        assertEquals(120, sessionId.length());
    }

    // ==================================================================================
    // 用户键
    // ==================================================================================

    /** 请求没带 userId 时用固定默认值：页面发起的对话在磁盘上与企微 / 飞书的天然分开。 */
    @Test
    void userIdFallsBackToConsoleWhenAbsent() {
        assertEquals(ChatStreamService.CONSOLE_USER_ID, ChatStreamService.resolveUserId(null));
        assertEquals(ChatStreamService.CONSOLE_USER_ID, ChatStreamService.resolveUserId("   "));
    }

    /**
     * userId 会成为 harness 工作区下的一级目录，消毒标准与会话键完全一致 ——
     * 一个带斜杠的值就能让这次对话的产物写到工作区外面去。
     */
    @Test
    void userIdIsFileNameSafe() {
        String userId = ChatStreamService.resolveUserId("user:123/a\\b");

        assertFalse(userId.matches(".*[:*?\"<>|/\\\\].*"),
                "userId 消毒后不该残留路径分隔符: " + userId);
        assertTrue(userId.matches("[A-Za-z0-9._-]+"), "字符集被收敛后应当是安全集: " + userId);
    }

    /**
     * 点号序列是唯一一条「过得了字符集校验、但仍然危险」的值：
     * {@code ..} 作为目录名会让路径解析跳到上一层。直接回落默认用户，不做静默改写
     * （改写后 {@code ..} 会变成别的东西，磁盘上更难对上号）。
     */
    @Test
    void userIdFallsBackWhenDotsOnly() {
        assertEquals(ChatStreamService.CONSOLE_USER_ID, ChatStreamService.resolveUserId("."));
        assertEquals(ChatStreamService.CONSOLE_USER_ID, ChatStreamService.resolveUserId(".."));
        assertEquals(ChatStreamService.CONSOLE_USER_ID, ChatStreamService.resolveUserId("...."));
    }

    /** userId 同样要限长：它会变成目录名。 */
    @Test
    void userIdIsTruncated() {
        assertEquals(120, ChatStreamService.resolveUserId("a".repeat(300)).length());
    }

    /** run 帧要把生效的 userId 回执出去 —— 请求没带时前端看不到后端到底用了什么默认值。 */
    @Test
    void runFrameCarriesResolvedUserId() {
        ChatStreamService service = serviceWith(new AgentResultEvent(msg("x")));

        List<ChatStreamEvent> frames = service.chat(AGENT, "hi", "s-1", "user:42").collectList().block();

        assertEquals("user-42", frames.get(0).data().get("userId"),
                "run 帧要回执送进去的 userId 经过消毒后的实际值");
    }

    // ==================================================================================
    // run 回执
    // ==================================================================================

    /**
     * run 帧必须回执 runId —— 它是页面上「这条回答 → 那条链路」唯一的凭据。
     *
     * <p>少回执一个字段不会报错、页面照样渲染，只是那个链接点下去是 404。
     * 这类"看起来没坏"的缺失只有在真机上点一下才会暴露，所以按契约钉死。
     */
    @Test
    void runFrameCarriesRunId() {
        ChatStreamService service = serviceWith(new AgentResultEvent(msg("x")));

        List<ChatStreamEvent> frames = service.chat(AGENT, "hi", "s-1", null).collectList().block();

        Object runId = frames.get(0).data().get("runId");
        assertTrue(runId instanceof String, "run 帧必须带 runId: " + frames.get(0).data());
        assertTrue(String.valueOf(runId).matches("[0-9a-z]{8}-[0-9a-z]{6}"),
                "runId 形状应与 TraceRunIdGenerator 一致: " + runId);
    }

    /**
     * 两次对话是两个 run，runId 必须各是各的。
     *
     * <p>这条专门盯着一个容易踩的改动：把生成 runId 的那行从 {@code Flux.defer} 里提到
     * 方法体里。那样同一个 Flux 被订阅两次会共用同一个 id，页面上两条回答指向同一条链路，
     * 而编译、单次调用都看不出问题。
     */
    @Test
    void eachConversationGetsItsOwnRunId() {
        ChatStreamService service = serviceWith(new AgentResultEvent(msg("x")));
        Flux<ChatStreamEvent> conversation = service.chat(AGENT, "hi", "s-1", null);

        Object first = conversation.collectList().block().get(0).data().get("runId");
        Object second = conversation.collectList().block().get(0).data().get("runId");

        assertNotEquals(first, second, "两次订阅必须产生两个 runId");
    }

    // ==================================================================================
    // 帧映射
    // ==================================================================================

    @Test
    void textDeltasBecomeDeltaFramesBetweenRunAndDone() {
        ChatStreamService service = serviceWith(
                new TextBlockDeltaEvent("r1", "b1", "你好"),
                new TextBlockDeltaEvent("r1", "b1", "，世界"),
                new AgentResultEvent(msg("你好，世界")));

        List<ChatStreamEvent> frames = service.chat(AGENT, "hi", "s-1", null).collectList().block();

        assertEquals(List.of("run", "delta", "delta", "done"), types(frames));
        assertEquals("你好", frames.get(1).data().get("text"));
        // 已经流过增量时，AgentResultEvent 不能把整段再补一遍 —— 否则页面上会出现两份回答
        assertEquals(4, frames.size(), "整段结果在流过增量时不该再发一次: " + types(frames));
        // 首帧带回生效的会话键，前端靠它让第 2 轮落回同一个会话
        assertEquals("s-1", frames.get(0).data().get("sessionId"));
        assertEquals(AGENT, frames.get(0).data().get("agentId"));
        assertEquals(ChatStreamEvent.STATUS_DONE, frames.get(3).data().get("status"));
    }

    /**
     * 兜底分支：有的模型 / 配置不产生 TextBlockDelta，只在最后一次性给出全文。
     * 只认 delta 的话页面会一直空白到 done，看起来像「agent 没回话」。
     */
    @Test
    void wholeResultIsEmittedWhenNoDeltaArrived() {
        ChatStreamService service = serviceWith(new AgentResultEvent(msg("完整回答")));

        List<ChatStreamEvent> frames = service.chat(AGENT, "hi", null, null).collectList().block();

        assertEquals(List.of("run", "delta", "done"), types(frames));
        assertEquals("完整回答", frames.get(1).data().get("text"));
    }

    /**
     * 工具帧必须带 callId：同一轮里并行调多个工具时，结束事件不保证与开始事件相邻，
     * 页面要靠 id 才能把「结束了哪一个」对上号。
     */
    @Test
    void toolEventsCarryCallIdAndPhase() {
        ChatStreamService service = serviceWith(
                new ToolCallStartEvent("r1", "call-1", "k8s_check"),
                new ToolResultEndEvent("r1", "call-1", "k8s_check", ToolResultState.SUCCESS));

        List<ChatStreamEvent> frames = service.chat(AGENT, "hi", null, null).collectList().block();

        assertEquals(List.of("run", "tool", "tool", "done"), types(frames));
        assertEquals("call-1", frames.get(1).data().get("callId"));
        assertEquals("k8s_check", frames.get(1).data().get("name"));
        assertEquals(ChatStreamEvent.TOOL_PHASE_START, frames.get(1).data().get("phase"));
        assertEquals(ChatStreamEvent.TOOL_PHASE_END, frames.get(2).data().get("phase"));
    }

    /** 思考增量与答案走不同的帧：页面上两者的展示位不同，混在一起就没法分开渲染。 */
    @Test
    void thinkingDeltaHasItsOwnFrameType() {
        ChatStreamService service = serviceWith(
                new ThinkingBlockDeltaEvent("r1", "b1", "先想一下"),
                new TextBlockDeltaEvent("r1", "b2", "答案"));

        List<ChatStreamEvent> frames = service.chat(AGENT, "hi", null, null).collectList().block();

        assertEquals(List.of("run", "thinking", "delta", "done"), types(frames));
        assertEquals("先想一下", frames.get(1).data().get("text"));
    }

    /**
     * 失败必须转成 error 帧后<b>照常收口</b>。
     *
     * <p>这点与链路 SSE 刚好相反：那边把异常抛出去是为了让前端重连补齐；
     * 这里是「一问一答」，连接断了前端只能重发请求，而重发等于把 agent 再跑一遍
     * （有副作用、要花 token）。所以异常在服务里就地转成帧，别流到连接层。
     */
    @Test
    void failureBecomesErrorFrameFollowedByErrorDone() {
        ChatStreamService service = serviceWithError(new IllegalStateException("模型超时"));

        List<ChatStreamEvent> frames = service.chat(AGENT, "hi", null, null).collectList().block();

        assertEquals(List.of("run", "error", "done"), types(frames));
        assertTrue(String.valueOf(frames.get(1).data().get("message")).contains("模型超时"),
                "错误摘要要带原始信息，否则页面上只看到一句无用的类名");
        assertEquals(ChatStreamEvent.STATUS_ERROR, frames.get(2).data().get("status"));
    }

    /** 打错 agentId 要立刻失败，而不是回一条空流 —— 空流在页面上表现为「agent 不回话」。 */
    @Test
    void unknownAgentFailsFast() {
        ChatStreamService service = serviceWith(new AgentResultEvent(msg("x")));

        assertFalse(service.hasAgent("nope"));
        assertThrows(IllegalStateException.class, () -> service.chat("nope", "hi", null, null));
    }

    // ==================================================================================
    // 测试替身
    // ==================================================================================

    private static List<String> types(List<ChatStreamEvent> frames) {
        return frames.stream().map(ChatStreamEvent::type).toList();
    }

    private static Msg msg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static ChatStreamService serviceWith(AgentEvent... events) {
        return new ChatStreamService(stubManager(Flux.fromArray(events)));
    }

    private static ChatStreamService serviceWithError(Throwable error) {
        return new ChatStreamService(stubManager(Flux.error(error)));
    }

    /**
     * 桩 AgentManager：只替换「事件从哪来」。
     *
     * <p>用匿名子类而不是 mock 框架：本模块依赖里没有 mockito，
     * 而这里要覆盖的两个方法签名都很小，手写更直白。
     */
    private static AgentManager stubManager(Flux<AgentEvent> events) {
        return new AgentManager() {
            @Override
            public boolean exists(String name) {
                return AGENT.equals(name);
            }

            @Override
            public Flux<AgentEvent> stream(String name, List<Msg> msgs, RuntimeContext ctx) {
                return events;
            }
        };
    }
}
