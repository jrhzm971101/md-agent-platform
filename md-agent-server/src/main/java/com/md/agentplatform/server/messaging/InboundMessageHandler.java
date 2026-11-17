package com.md.agentplatform.server.messaging;

/**
 * 入站消息处理器。各 {@link BotInstance} 收到消息后调用，实际由 {@link MessageRouter} 实现。
 */
@FunctionalInterface
public interface InboundMessageHandler {

    void handle(InboundMessage message);
}
