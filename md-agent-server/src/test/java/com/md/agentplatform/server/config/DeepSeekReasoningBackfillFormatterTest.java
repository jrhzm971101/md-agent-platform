package com.md.agentplatform.server.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DeepSeek thinking + tools 场景：带 tool_calls 却没有 reasoning_content 的 assistant 消息必须被回填，
 * 否则请求会被 DeepSeek 以 400 拒绝（The `reasoning_content` in the thinking mode must be passed back to the API.）。
 */
class DeepSeekReasoningBackfillFormatterTest {

    private final OpenAIChatFormatter formatter = new DeepSeekReasoningBackfillFormatter();

    @Test
    void backfillsMissingReasoningForToolCallAssistant() {
        List<Msg> conversation = List.of(
                Msg.builder().role(MsgRole.USER).textContent("读一下花名册").build(),
                Msg.builder().role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("先定位文档。").build()).build(),
                Msg.builder().role(MsgRole.USER).textContent("继续").build(),
                toolCallAssistant("call_1", "doc_search"));

        // 框架默认 formatter 会漏掉 reasoning_content，这正是线上 400 的来源
        assertNull(new OpenAIChatFormatter().format(conversation).get(3).getReasoningContent());

        List<OpenAIMessage> out = formatter.format(conversation);
        OpenAIMessage toolCallMsg = out.get(out.size() - 1);
        assertEquals("assistant", toolCallMsg.getRole());
        assertEquals("", toolCallMsg.getReasoningContent());
        assertEquals("call_1", toolCallMsg.getToolCalls().get(0).getId());
        assertEquals("doc_search", toolCallMsg.getToolCalls().get(0).getFunction().getName());
    }

    @Test
    void keepsReasoningThatModelActuallyProduced() {
        List<OpenAIMessage> out = formatter.format(List.of(
                Msg.builder().role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("先定位文档。").build(),
                                ToolUseBlock.builder().id("call_1").name("doc_search").input(Map.of()).build())
                        .build()));

        assertEquals("先定位文档。", out.get(0).getReasoningContent());
    }

    @Test
    void leavesRequestUntouchedWhenNoThinkingInPayload() {
        List<OpenAIMessage> out = formatter.format(List.of(
                Msg.builder().role(MsgRole.USER).textContent("读一下花名册").build(),
                toolCallAssistant("call_1", "doc_search")));

        assertNull(out.get(out.size() - 1).getReasoningContent());
    }

    @Test
    void backfillsPlainTextAssistantWithoutToolCalls() {
        List<OpenAIMessage> out = formatter.format(List.of(
                Msg.builder().role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("先定位文档。").build()).build(),
                Msg.builder().role(MsgRole.ASSISTANT).textContent("结论如下。").build()));

        assertEquals("", out.get(1).getReasoningContent());
    }

    @Test
    void doesNotTouchUserAndToolMessages() {
        List<OpenAIMessage> out = formatter.format(List.of(
                Msg.builder().role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("先定位文档。").build()).build(),
                Msg.builder().role(MsgRole.TOOL)
                        .content(ToolResultBlock.builder().id("call_1").name("doc_search").build()).build(),
                Msg.builder().role(MsgRole.USER).textContent("继续").build()));

        assertNull(out.get(1).getReasoningContent());
        assertNull(out.get(2).getReasoningContent());
    }

    private static Msg toolCallAssistant(String id, String name) {
        return Msg.builder().role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id(id).name(name).input(Map.of()).build())
                .build();
    }
}
