package com.md.agentplatform.server.service;

/**
 * 企业微信消息回复能力接口（多 Bot 感知）。
 *
 * <p>所有发送方法都以 {@code botName} 定位目标 Bot 的 WebSocket 连接，
 * 由实现类经 {@code BotInstanceManager} 解析对应 {@code WeComBotInstance} 的客户端。
 */
public interface WorkWxReplyService {

    /** 流式回复消息。每次调用携带累积全文，finish=true 时结束流。 */
    void replyStream(String botName, String reqId, String streamId, String content, boolean finish);

    /** 回复纯文本消息（仅限 enter_chat 欢迎语场景）。 */
    void replyText(String botName, String reqId, String content);

    /** 主动推送 Markdown 消息（独立气泡，不依赖流式）。 */
    void pushMarkdown(String botName, String chatid, String chattype, String content);

    /** 主动推送 Stream 消息（finish=true 时作为一次性消息，享受完整 markdown 渲染）。 */
    void pushStream(String botName, String chatid, String chattype, String streamId,
                    String content, boolean finish);
}
