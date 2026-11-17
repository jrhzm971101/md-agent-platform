package com.md.agentplatform.server.messaging.feishu;

import com.md.agentplatform.server.messaging.BotInstance;
import com.md.agentplatform.server.util.JsonUtil;
import com.md.agentplatform.server.messaging.BotPlatform;
import com.md.agentplatform.server.messaging.InboundMessage;
import com.md.agentplatform.server.messaging.InboundMessageHandler;
import com.md.agentplatform.server.messaging.config.FeishuBotCredentials;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书 Bot 实例 —— 吸收原 {@code FeishuEventListener} 逻辑。
 *
 * <p>Lark SDK 的 {@link com.lark.oapi.Client} 与 WS {@link com.lark.oapi.ws.Client}
 * 均为多实例友好（每 app 一个实例，内置独立 token 缓存与连接池），因此每个 Bot
 * 独立持有一对 REST + WS 客户端，天然隔离。比企微端简单很多。
 */
public class FeishuBotInstance implements BotInstance {

    private static final Logger log = LoggerFactory.getLogger(FeishuBotInstance.class);

    private final String name;
    private final FeishuBotCredentials config;
    private final InboundMessageHandler handler;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile com.lark.oapi.Client restClient;
    private volatile com.lark.oapi.ws.Client wsClient;

    public FeishuBotInstance(String name, FeishuBotCredentials config,
                             InboundMessageHandler handler) {
        this.name = name;
        this.config = config;
        this.handler = handler;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public BotPlatform getPlatform() {
        return BotPlatform.FEISHU;
    }

    /** REST 客户端（消息/卡片/文档操作）。 */
    public com.lark.oapi.Client getClient() {
        return restClient;
    }

    /** 配置（供 searchWiki 等直接读取 appId/appSecret/baseUrl）。 */
    public FeishuBotCredentials getConfig() {
        return config;
    }

    // ==================== 生命周期 ====================

    @Override
    public synchronized void start() {
        if (started.get()) {
            return;
        }

        this.restClient = new com.lark.oapi.Client.Builder(
                config.appId(), config.appSecret()).logReqAtDebug(false).build();

        this.wsClient = new com.lark.oapi.ws.Client.Builder(
                config.appId(), config.appSecret())
                .eventHandler(buildEventDispatcher())
                .build();
        wsClient.start();
        started.set(true);
        log.info("[{}] Feishu WS client started", name);
    }

    @Override
    public synchronized void stop() {
        // Lark WS Client 无公开 close() 接口（disconnect 为 protected），与原生实现一致，仅置位。
        started.set(false);
        log.info("[{}] Feishu bot stopped", name);
    }

    @Override
    public boolean isAlive() {
        return started.get();
    }

    // ==================== 事件分发 ====================

    private EventDispatcher buildEventDispatcher() {
        return EventDispatcher.newBuilder(
                        config.verificationToken(),
                        config.encryptKey())
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        dispatch(event);
                    }
                })
                .build();
    }

    private void dispatch(P2MessageReceiveV1 event) {
        String messageType = event.getEvent().getMessage().getMessageType();
        String content = event.getEvent().getMessage().getContent();
        String messageId = event.getEvent().getMessage().getMessageId();
        String openId = event.getEvent().getSender().getSenderId().getOpenId();
        log.info("[{}] 收到消息, type={}, openId={}", name, messageType, openId);

        Map<String, String> contentMap = new HashMap<>();
        try {
            contentMap = JsonUtil.parseStringMap(content);
        } catch (Exception e) {
            log.warn("[{}] 解析消息 content 失败: {}", name, e.getMessage());
        }

        InboundMessageHandler h = this.handler;
        if (h != null) {
            h.handle(new InboundMessage(
                    name, BotPlatform.FEISHU, openId, null, null,
                    messageType, messageId, content, contentMap, null));
        }
    }
}
