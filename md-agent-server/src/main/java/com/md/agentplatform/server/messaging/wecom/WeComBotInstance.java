package com.md.agentplatform.server.messaging.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.messaging.BotInstance;
import com.md.agentplatform.server.messaging.BotPlatform;
import com.md.agentplatform.server.messaging.InboundMessage;
import com.md.agentplatform.server.messaging.InboundMessageHandler;
import com.md.agentplatform.server.messaging.config.WeComBotCredentials;
import com.md.agentplatform.server.util.JsonUtil;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 企业微信 Bot 实例 —— 吸收原 {@code WorkWxEventListener} 的连接生命周期逻辑。
 *
 * <p>每个实例独立持有 OkHttp 连接、心跳、退避重连和 {@link WorkWxWsClient}，
 * 故障隔离：一个 Bot 挂了不影响其他。收到消息时归一化为 {@link InboundMessage} 交给
 * {@link InboundMessageHandler}（即 {@code MessageRouter}）。
 */
public class WeComBotInstance implements BotInstance, WorkWxWsClient.Listener {

    private static final Logger log = LoggerFactory.getLogger(WeComBotInstance.class);
    private static final String WS_URL = "wss://openws.work.weixin.qq.com";
    private static final int HEARTBEAT_SEC = 30;
    private static final int PONG_TIMEOUT_SEC = 90;
    private static final int RECONNECT_MIN_SEC = 2;
    private static final int RECONNECT_MAX_SEC = 30;

    private final String name;
    private final WeComBotCredentials config;
    private final InboundMessageHandler handler;

    /** 订阅状态发生变化（成功/失败）时的健康上报回调，由 {@code BotInstanceManager} 注入，可为 null。 */
    private final Runnable onHealthChange;

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();

    private volatile WorkWxWsClient client;
    private volatile boolean running;
    private volatile boolean subscribed;
    private volatile CountDownLatch closeLatch = new CountDownLatch(1);
    private final AtomicLong lastPong = new AtomicLong(System.currentTimeMillis());
    private volatile int backoff = RECONNECT_MIN_SEC;

    private final ExecutorService sender;
    private final ExecutorService worker;
    private ScheduledExecutorService heartbeat;

    public WeComBotInstance(String name, WeComBotCredentials config,
                            InboundMessageHandler handler, Runnable onHealthChange) {
        this.name = name;
        this.config = config;
        this.handler = handler;
        this.onHealthChange = onHealthChange;
        this.sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wecom-sender-" + name); t.setDaemon(true); return t;
        });
        this.worker = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wecom-worker-" + name); t.setDaemon(true); return t;
        });
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public BotPlatform getPlatform() {
        return BotPlatform.WECOM;
    }

    /** 当前有效连接，供发送服务直接使用。 */
    public WorkWxWsClient getClient() {
        return this.client;
    }

    // ==================== 生命周期 ====================

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        new Thread(this::connect, "wecom-connect-" + name).start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        shutdown();
    }

    @Override
    public boolean isAlive() {
        WorkWxWsClient c = this.client;
        return running && subscribed && c != null && c.isConnected();
    }

    private void shutdown() {
        stopHeartbeat();
        if (client != null) try { client.close(); } catch (Exception ignored) {}
        sleep(1);
        gracefulShutdown(sender);
        gracefulShutdown(worker);
    }

    private static void gracefulShutdown(ExecutorService pool) {
        pool.shutdown();
        try { pool.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        pool.shutdownNow();
    }

    private void submitSend(Runnable task) {
        try { sender.submit(task); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    // ==================== 连接循环 ====================

    private void connect() {
        log.info("[{}] Connect loop started", name);
        while (running) {
            closeLatch = new CountDownLatch(1);
            try {
                if (this.client != null) {
                    try { this.client.close(); } catch (Exception ignored) {}
                    sleep(2);
                }
                WorkWxWsClient c = new WorkWxWsClient(okHttpClient, WS_URL, this);
                if (!c.connectBlocking(30, TimeUnit.SECONDS)) {
                    log.error("[{}] 连接失败! {}s 后重试 (退避={}s)", name, backoff, backoff);
                    if (running) {
                        sleep(backoff);
                    }
                    backoff = Math.min(backoff * 2, RECONNECT_MAX_SEC);
                    continue;
                }
                this.client = c;
                backoff = RECONNECT_MIN_SEC;
                log.info("[{}] WS 连接成功", name);
                closeLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("[{}] 连接异常，{}s 后重试: {}", name, backoff, e.getMessage(), e);
            }
            if (running) { sleep(backoff); backoff = Math.min(backoff * 2, RECONNECT_MAX_SEC); }
        }
        log.info("[{}] Connect loop ended", name);
    }

    // ==================== WorkWxWsClient.Listener ====================

    @Override
    public void onConnected(WorkWxWsClient c) { subscribe(c); }

    @Override
    public void onMessageReceived(String msg) {
        try { dispatch(msg); } catch (Exception e) { log.error("[{}] Dispatch error", name, e); }
    }

    @Override
    public void onDisconnected(int code, String reason, boolean remote) {
        log.warn("[{}] WS 断开: code={} reason={} remote={} 即将重连...", name, code, reason, remote);
        subscribed = false; stopHeartbeat(); closeLatch.countDown();
    }

    @Override
    public void onErrorOccurred(Exception ex) {
        log.error("[{}] WS 异常，即将重连: {}", name, ex.getMessage(), ex);
        subscribed = false; stopHeartbeat(); closeLatch.countDown();
    }

    // ==================== 订阅 ====================

    private void subscribe(WorkWxWsClient c) {
        String reqId = WorkWxWsClient.generateReqId();
        ObjectNode body = JsonUtil.newObject();
        body.put("bot_id", config.botId());
        body.put("secret", config.secret());
        submitSend(() -> {
            c.sendEnvelope("aibot_subscribe", reqId, body);
            log.info("[{}] Subscribe sent", name);
        });
    }

    // ==================== 消息分发（归一化 → InboundMessage）====================

    private void dispatch(String raw) {
        JsonNode msg = JsonUtil.parse(raw);
        String cmd = msg.has("cmd") ? msg.get("cmd").asText() : null;

        if (cmd == null) {
            if (!subscribed) {
                handleSubscribeRsp(msg);
            }
            else {
                int errcode = msg.has("errcode") ? msg.get("errcode").asInt() : -1;
                if (errcode == 0) {
                    lastPong.set(System.currentTimeMillis());
                } else if (errcode != 6000) {
                    log.warn("[{}] Ping err: {}", name, errcode);
                }
            }
            return;
        }

        switch (cmd) {
            case "aibot_msg_callback" -> {
                JsonNode body = msg.get("body");
                String msgtype = body.get("msgtype").asText();
                String content = body.has(msgtype) ? body.get(msgtype).toString() : "";
                worker.submit(() -> {
                    InboundMessageHandler h = this.handler;
                    if (h == null) {
                        return;
                    }

                    Map<String, String> inputs = new HashMap<>();
                    inputs.put("content", content);
                    inputs.put("msgtype", msgtype);
                    String chatType = body.get("chattype").asText();
                    inputs.put("chattype", chatType);
                    String chatId = body.has("chatid") ? body.get("chatid").asText() : null;
                    if (chatId != null) {
                        inputs.put("chatid", chatId);
                    }

                    h.handle(new InboundMessage(
                            name, BotPlatform.WECOM,
                            body.get("from").get("userid").asText(),
                            chatId,
                            chatType,
                            msgtype,
                            body.get("msgid").asText(),
                            content,
                            inputs,
                            getReqId(msg)));
                });
            }
            case "aibot_event_callback" -> handleEvent(msg);
            default -> log.debug("[{}] Unknown cmd: {}", name, cmd);
        }
    }

    private void handleSubscribeRsp(JsonNode msg) {
        int errcode = msg.has("errcode") ? msg.get("errcode").asInt() : -1;
        if (errcode == 0) {
            subscribed = true; lastPong.set(System.currentTimeMillis());
            log.info("[{}] Subscribe ok", name); startHeartbeat();
        } else {
            log.error("[{}] Subscribe failed: {}", name, errcode);
            if (client != null) try { client.close(); } catch (Exception ignored) {}
        }
        // 订阅结果落地后刷新健康状态（避免启动瞬间订阅未完成时的假阴性）
        if (onHealthChange != null) onHealthChange.run();
    }

    private void handleEvent(JsonNode msg) {
        JsonNode body = msg.get("body");
        String type = body.has("event") && body.get("event").has("eventtype")
                ? body.get("event").get("eventtype").asText() : "unknown";
        log.info("[{}] Event: {}", name, type);
        switch (type) {
            case "enter_chat" -> worker.submit(() -> {
                WorkWxWsClient c = this.client;
                if (c != null) {
                    boolean ok = c.replyWelcomeMsg(getReqId(msg), "您好，有什么可以帮您的？");
                    if (!ok) {
                        log.warn("[{}] Welcome msg send failed", name);
                    }
                }
            });
            case "disconnected_event" -> {
                log.warn("[{}] Server disconnect — reconnecting", name);
                if (client != null) try { client.close(); } catch (Exception ignored) {}
            }
            default -> log.info("[{}] Unhandled event: {}", name, type);
        }
    }

    // ==================== 心跳 ====================

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wecom-heartbeat-" + name); t.setDaemon(true); return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            submitSend(() -> {
                WorkWxWsClient c = this.client;
                if (c != null && c.isConnected()) {
                    c.sendPing();
                }
            });
            if (System.currentTimeMillis() - lastPong.get() > PONG_TIMEOUT_SEC * 1000L) {
                log.warn("[{}] No pong for {}s — reconnecting", name, PONG_TIMEOUT_SEC);
                if (client != null) try { client.close(); } catch (Exception ignored) {}
            }
        }, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeat != null && !heartbeat.isShutdown()) {
            heartbeat.shutdown();
            try { heartbeat.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ==================== 工具方法 ====================

    private static String getReqId(JsonNode msg) {
        if (msg.has("headers") && msg.get("headers").has("req_id")) {
            return msg.get("headers").get("req_id").asText();
        }
        return "";
    }

    private static void sleep(int sec) {
        try { Thread.sleep(sec * 1000L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
