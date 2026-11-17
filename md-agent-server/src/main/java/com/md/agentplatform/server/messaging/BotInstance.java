package com.md.agentplatform.server.messaging;

/**
 * 单个交互源（Bot）的生命周期抽象。
 *
 * <p>只抽象<b>生命周期</b>（start/stop/isAlive），不抽象<b>消息发送</b>——
 * 企微是 {@code chatid + markdown → WS push}，飞书是 {@code open_id + msgType → REST}，
 * 消息模型完全不同，强统一会导致"漏抽象"。平台特有的发送细节由各自的实现类暴露。
 *
 * <p>每个实例独立连接、独立心跳、独立重连，一个 Bot 挂了不影响其他。
 */
public interface BotInstance {

    String getName();

    BotPlatform getPlatform();

    /** 建立连接并进入运行态（幂等）。 */
    void start();

    /** 关闭连接、释放资源（幂等）。 */
    void stop();

    /** 当前是否处于可用连接态。 */
    boolean isAlive();
}
