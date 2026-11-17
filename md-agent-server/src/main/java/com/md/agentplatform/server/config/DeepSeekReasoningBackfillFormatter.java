package com.md.agentplatform.server.config;

import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

import java.util.List;

/**
 * 给「没带 thinking」的 assistant 消息补一个空的 {@code reasoning_content}。
 *
 * <p>DeepSeek 的硬规则：请求带 {@code tools} 时，thinking 模式下**最后一条 user 消息之后**
 * 每一条 assistant 消息都必须回传 {@code reasoning_content}（官方文档原文：
 * "the reasoning_content must be fully passed back to the API in all subsequent requests —
 * even for turns where the model did not perform a tool call"），少一条整个请求 400：
 * {@code The `reasoning_content` in the thinking mode must be passed back to the API.}
 * 注意不只是带 {@code tool_calls} 的那几条——纯文本的收尾回复缺了同样会被拒。
 *
 * <p>框架两侧都不会兜底：解析响应时「空 reasoning」不生成 ThinkingBlock
 * （{@code OpenAIResponseParser}），拼请求时 {@code OpenAIChatFormatter} 只在有 ThinkingBlock
 * 时才写 {@code reasoning_content}、{@code DeepSeekFormatter} 更是只会删不会补。
 * 于是一旦会话里落进一条「带 tool_calls 但没有 thinking」的 assistant 消息，它会一直潜伏
 * （历史轮次 API 不校验），直到 compaction 用 USER 角色的摘要消息把它划进「当前轮」才引爆。
 *
 * <p>DeepSeek 只要求字段存在，空串同样被接受，语义上等价于「这一轮没有记录 CoT」。
 * 只在 payload 里已经出现 {@code reasoning_content}（即确实处于 thinking 模式）时生效，
 * 非 thinking 场景与其他 OpenAI 兼容厂商的请求完全不受影响。
 */
public class DeepSeekReasoningBackfillFormatter extends OpenAIChatFormatter {

    private static final String ROLE_ASSISTANT = "assistant";

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> formatted = super.doFormat(msgs);
        if (!inThinkingMode(formatted)) {
            return formatted;
        }
        for (OpenAIMessage message : formatted) {
            if (needsReasoningBackfill(message)) {
                message.setReasoningContent("");
            }
        }
        return formatted;
    }

    /** thinking 模式的判据与 DeepSeek 一致：payload 里已经出现过 reasoning_content。 */
    private static boolean inThinkingMode(List<OpenAIMessage> messages) {
        for (OpenAIMessage message : messages) {
            if (message.getReasoningContent() != null) {
                return true;
            }
        }
        return false;
    }

    /** 缺 reasoning_content 的 assistant 消息都会被 API 拒绝，与是否带 tool_calls 无关。 */
    private static boolean needsReasoningBackfill(OpenAIMessage message) {
        return ROLE_ASSISTANT.equals(message.getRole())
                && message.getReasoningContent() == null;
    }
}
