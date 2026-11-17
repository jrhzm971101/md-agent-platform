package com.md.agentplatform.server.messaging;

import java.util.Map;

/**
 * 归一化入站消息：所有平台的消息被各 {@link BotInstance} 转成该结构后交给
 * {@link MessageRouter}，实现"消息从哪个通道来"与"谁来回答问题"的解耦。
 *
 * @param botName  消息来源的 Bot 名（对应配置里的 {@code bots[].name}）
 * @param platform 平台
 * @param userId   发送者标识（企微 userid / 飞书 open_id）
 * @param chatId   会话 ID（企微群聊 chatid；单聊/飞书为 null）
 * @param chatType 会话类型（企微 single/group；飞书为 null）
 * @param msgType  消息类型（text/image/file/voice...）
 * @param msgId    消息 ID
 * @param content  原始内容（企微为 msgtype 子对象 JSON，飞书为 content 字段原文）
 * @param inputs   已解析的内容 Map（供构造 Msg 时使用）
 * @param replyId  回写定位：企微为 reqId（replyStream 用）；飞书为 null（卡片在路由内创建）
 */
public record InboundMessage(
        String botName,
        BotPlatform platform,
        String userId,
        String chatId,
        String chatType,
        String msgType,
        String msgId,
        String content,
        Map<String, String> inputs,
        String replyId
) {
}
