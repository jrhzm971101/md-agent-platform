package com.md.agentplatform.server.messaging.render;

import com.md.agentplatform.server.messaging.BotPlatform;
import com.md.agentplatform.server.messaging.InboundMessage;

/**
 * 回复渲染器 —— 把"消息从哪个通道来"到"最终回复怎么写"的完整渲染链路抽成一个策略。
 *
 * <p>每个实现负责：构造输入消息（平台特有的归一化/下载/解密）、启动 Agent 流、
 * 把 {@code Thinking/Text/Tool/Final} 事件渲染成该平台/风格的输出。
 */
public interface ReplyRenderer {

    ReplyStyle style();

    BotPlatform platform();

    /** 渲染并输出一条消息的回复。实现必须非阻塞（内部用 subscribe，不 block）。 */
    void render(InboundMessage msg, String agentName);
}
