package com.md.agentplatform.server.messaging.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 企业微信智能机器人 WebSocket 客户端（基于 OkHttp WebSocket，非 Spring Bean）。
 *
 * <p>所有回复/推送方法均<b>同步阻塞</b>等待服务端返回值，校验 {@code errcode==0}
 * 才确认成功，失败自动重试。发送链路每一步都有 INFO 级别日志，DEBUG 级别可见完整报文。
 *
 * <p>参考：https://developer.work.weixin.qq.com/document/path/101463</p>
 */
public class WorkWxWsClient {

    private static final Logger log = LoggerFactory.getLogger(WorkWxWsClient.class);

    static final String CMD_RESPOND_MSG = "aibot_respond_msg";
    private static final String CMD_RESPOND_WELCOME_MSG = "aibot_respond_welcome_msg";
    static final String CMD_RESPOND_UPDATE_MSG = "aibot_respond_update_msg";
    static final String CMD_SEND_MSG = "aibot_send_msg";
    static final String CMD_PING = "ping";

    private static final String MSGTYPE_TEXT = "text";
    private static final String MSGTYPE_MARKDOWN = "markdown";
    private static final String MSGTYPE_TEMPLATE_CARD = "template_card";
    private static final String MSGTYPE_STREAM = "stream";

    /** 单次等待响应的超时（毫秒） */
    private static final long RESPONSE_TIMEOUT_MS = 10_000;
    /** 失败重试次数（不含首次） */
    private static final int MAX_RETRIES = 3;
    /** 日志中 content 最大长度 */
    private static final int LOG_CONTENT_MAX = 200;

    private volatile WebSocket webSocket;
    private final Listener listener;
    private final CountDownLatch connectLatch = new CountDownLatch(1);

    /** 待响应的请求：reqId → PendingRequest */
    private final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    // ==================== 构造 ====================

    public WorkWxWsClient(OkHttpClient httpClient, String url, Listener listener) {
        this.listener = listener;
        log.info("[WS] 开始创建 WebSocket 连接 url={}", url);
        Request request = new Request.Builder().url(url).build();
        httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                log.info("[WS] onOpen —— 连接已建立 status={} protocol={}",
                        response.code(), response.protocol());
                webSocket = ws;
                connectLatch.countDown();
                listener.onConnected(WorkWxWsClient.this);
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                log.debug("[WS] onMessage raw={}", truncateForLog(text));
                if (tryHandleResponse(text)) {
                    log.debug("[WS] onMessage —— 已被拦截为命令响应");
                    return;
                }
                log.debug("[WS] onMessage —— 转发给 Listener");
                listener.onMessageReceived(text);
            }

            @Override
            public void onClosing(@NotNull WebSocket ws, int code, @NotNull String reason) {
                log.info("[WS] onClosing —— 服务端请求关闭 code={} reason={}", code, reason);
                ws.close(1000, null);
            }

            @Override
            public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                log.info("[WS] onClosed —— 连接已关闭 code={} reason={}", code, reason);
                webSocket = null;
                failAllPending("connection closed code=" + code);
                listener.onDisconnected(code, reason, true);
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t,
                                  @Nullable Response response) {
                int httpCode = response != null ? response.code() : -1;
                log.error("[WS] onFailure —— 连接异常 httpCode={} err={}",
                        httpCode, t.getMessage(), t);
                webSocket = null;
                connectLatch.countDown();
                failAllPending("connection failure: " + t.getMessage());
                Exception ex = t instanceof Exception ? (Exception) t : new Exception(t);
                listener.onErrorOccurred(ex);
                listener.onDisconnected(-1,
                        t.getMessage() != null ? t.getMessage() : "failure", false);
            }
        });
        log.info("[WS] WebSocket 连接请求已提交（异步等待 onOpen）");
    }

    // ==================== 回调接口 ====================

    public interface Listener {
        void onConnected(WorkWxWsClient client);
        void onMessageReceived(String message);
        void onDisconnected(int code, String reason, boolean remote);
        void onErrorOccurred(Exception ex);
    }

    // ==================== 连接管理 ====================

    public boolean connectBlocking(long timeout, TimeUnit unit) throws InterruptedException {
        boolean ok = connectLatch.await(timeout, unit);
        log.info("[WS] connectBlocking 结果={}", ok ? "SUCCESS" : "TIMEOUT");
        return ok;
    }

    public boolean isConnected() {
        return webSocket != null;
    }

    public void close() {
        WebSocket ws = this.webSocket;
        log.info("[WS] close() 主动关闭 wsExists={}", ws != null);
        if (ws != null) {
            ws.close(1000, "bye");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求-响应 关联机制
    // ═══════════════════════════════════════════════════════════════

    private static class PendingRequest {
        final CountDownLatch latch = new CountDownLatch(1);
        final long createTime = System.currentTimeMillis();
        volatile JsonNode response;
    }

    private boolean tryHandleResponse(String text) {
        try {
            JsonNode msg = JsonUtil.parse(text);

            if (msg.has("cmd")) {
                return false;
            }
            if (!msg.has("errcode")) {
                return false;
            }
            if (!msg.has("headers")) {
                return false;
            }
            JsonNode headers = msg.get("headers");
            if (!headers.has("req_id")) {
                return false;
            }

            String reqId = headers.get("req_id").asText();
            int errcode = msg.get("errcode").asInt();
            String errmsg = msg.has("errmsg") ? msg.get("errmsg").asText() : "";
            PendingRequest pending = pendingRequests.remove(reqId);

            if (pending != null) {
                long latency = System.currentTimeMillis() - pending.createTime;
                pending.response = msg;
                pending.latch.countDown();
                log.info("[WS] ◄── 收到响应 reqId={} errcode={} errmsg={} latency={}ms",
                        reqId, errcode, errmsg, latency);
                return true;
            }

            log.debug("[WS] tryHandleResponse —— 未匹配到 pending 请求 reqId={} errcode={} errmsg={}（可能是 subscribe/ping 响应）",
                    reqId, errcode, errmsg);
        } catch (Exception e) {
            log.debug("[WS] tryHandleResponse —— JSON 解析异常: {}", e.getMessage());
        }
        return false;
    }

    private void failAllPending(String reason) {
        String[] keys = pendingRequests.keySet().toArray(new String[0]);
        if (keys.length == 0) {
            return;
        }
        log.warn("[WS] failAllPending —— 释放 {} 个等待中的请求 reason={}", keys.length, reason);
        for (String reqId : keys) {
            PendingRequest p = pendingRequests.remove(reqId);
            if (p != null) {
                long waited = System.currentTimeMillis() - p.createTime;
                log.warn("[WS] failAllPending reqId={} waited={}ms", reqId, waited);
                p.latch.countDown();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 发送核心
    // ═══════════════════════════════════════════════════════════════

    private boolean sendRaw(String text) {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            log.warn("[WS] sendRaw 失败 —— webSocket 为 null（连接已断开）");
            return false;
        }
        try {
            boolean enqueued = ws.send(text);
            long queueSize = ws.queueSize();
            if (enqueued) {
                log.info("[WS] ──► sendRaw 成功 已入队 queueSize={} payloadLen={}",
                        queueSize, text.length());
                log.debug("[WS] ──► sendRaw payload={}", truncateForLog(text));
            } else {
                log.warn("[WS] sendRaw 失败 —— ws.send() 返回 false（队列满或连接已关闭） queueSize={}",
                        queueSize);
            }
            return enqueued;
        } catch (RuntimeException e) {
            log.warn("[WS] sendRaw 异常（连接断开?）: {}", e.getMessage());
            return false;
        }
    }

    @Nullable
    private JsonNode sendEnvelopeAndWait(String cmd, String reqId,
                                         ObjectNode body, long timeoutMs) {
        if (webSocket == null) {
            log.warn("[WS] sendEnvelopeAndWait 快速失败 —— webSocket 已为 null cmd={} reqId={}",
                    cmd, reqId);
            return null;
        }

        ObjectNode msg = JsonUtil.newObject();
        msg.put("cmd", cmd);
        ObjectNode headers = JsonUtil.newObject();
        headers.put("req_id", reqId);
        msg.set("headers", headers);
        msg.set("body", body);
        String payload = JsonUtil.stringify(msg);

        PendingRequest pending = new PendingRequest();
        pendingRequests.put(reqId, pending);

        try {
            boolean sent = sendRaw(payload);
            if (!sent) {
                return null;
            }

            boolean received = pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS);

            if (received) {
                JsonNode resp = pending.response;
                if (resp == null) {
                    return null;
                }
                int errcode = resp.has("errcode") ? resp.get("errcode").asInt() : -999;
                String errmsg = resp.has("errmsg") ? resp.get("errmsg").asText() : "";
                log.info("[WS] sendEnvelopeAndWait 收到响应 reqId={} errcode={} errmsg={}",
                        reqId, errcode, errmsg);
                return resp;
            } else {
                log.warn("[WS] sendEnvelopeAndWait 超时! reqId={} timeout={}ms",
                        reqId, timeoutMs);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[WS] sendEnvelopeAndWait 被中断 reqId={}", reqId);
            return null;
        } finally {
            pendingRequests.remove(reqId);
        }
    }

    private static boolean isSuccess(@Nullable JsonNode resp) {
        if (resp == null) {
            return false;
        }
        if (!resp.has("errcode")) {
            return false;
        }
        return resp.get("errcode").asInt() == 0;
    }

    private boolean sendWithRetry(String cmd, String reqId,
                                  java.util.function.Function<String, ObjectNode> bodyBuilder,
                                  int maxRetries, long timeoutMs) {
        int totalAttempts = maxRetries + 1;
        log.info("[WS] ══════ sendWithRetry 开始 cmd={} reqId={} maxRetries={} totalAttempts={} timeout={}ms ══════",
                cmd, reqId, maxRetries, totalAttempts, timeoutMs);

        for (int i = 0; i < totalAttempts; i++) {
            String attemptReqId = i == 0 ? reqId : generateReqId();
            log.info("[WS] sendWithRetry ── 第 {}/{} 次尝试 cmd={} reqId={} attemptReqId={} ──",
                    i + 1, totalAttempts, cmd, reqId, attemptReqId);

            ObjectNode body = bodyBuilder.apply(attemptReqId);
            JsonNode resp = sendEnvelopeAndWait(cmd, attemptReqId, body, timeoutMs);

            if (isSuccess(resp)) {
                log.info("[WS] sendWithRetry ★ 成功 cmd={} reqId={}", cmd, reqId);
                return true;
            }

            int errcode = -1;
            String errmsg = "no response (null)";
            if (resp != null) {
                errcode = resp.has("errcode") ? resp.get("errcode").asInt() : -2;
                errmsg = resp.has("errmsg") ? resp.get("errmsg").asText() : "no errmsg field";
            }

            if (i < maxRetries) {
                log.warn("[WS] sendWithRetry ✗ 第 {}/{} 次失败 cmd={} reqId={} errcode={} errmsg={} —— 500ms 后重试",
                        i + 1, totalAttempts, cmd, reqId, errcode, errmsg);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                log.error("[WS] sendWithRetry ✗✗✗ 最终失败! cmd={} reqId={} errcode={} errmsg={} 已用尽全部{}次尝试",
                        cmd, reqId, errcode, errmsg, totalAttempts);
            }
        }

        log.error("[WS] ══════ sendWithRetry 失败结束 cmd={} reqId={} ══════", cmd, reqId);
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // 回复消息（同步阻塞 + 重试）
    // ═══════════════════════════════════════════════════════════════

    public boolean replyText(String reqId, String content) {
        return sendWithRetry(CMD_RESPOND_MSG, reqId, attemptReqId -> {
            ObjectNode text = JsonUtil.newObject();
            text.put("content", content);
            ObjectNode body = JsonUtil.newObject();
            body.put("msgtype", MSGTYPE_TEXT);
            body.set(MSGTYPE_TEXT, text);
            return body;
        }, MAX_RETRIES, RESPONSE_TIMEOUT_MS);
    }

    public boolean replyWelcomeMsg(String reqId, String content) {
        return sendWithRetry(CMD_RESPOND_WELCOME_MSG, reqId, attemptReqId -> {
            ObjectNode text = JsonUtil.newObject();
            text.put("content", content);
            ObjectNode body = JsonUtil.newObject();
            body.put("msgtype", MSGTYPE_TEXT);
            body.set(MSGTYPE_TEXT, text);
            return body;
        }, MAX_RETRIES, RESPONSE_TIMEOUT_MS);
    }

    public boolean replyMarkdown(String reqId, String content) {
        return sendWithRetry(CMD_RESPOND_MSG, reqId, attemptReqId -> {
            ObjectNode markdown = JsonUtil.newObject();
            markdown.put("content", content);
            ObjectNode body = JsonUtil.newObject();
            body.put("msgtype", MSGTYPE_MARKDOWN);
            body.set(MSGTYPE_MARKDOWN, markdown);
            return body;
        }, MAX_RETRIES, RESPONSE_TIMEOUT_MS);
    }

    public boolean replyTemplateCard(String reqId, ObjectNode templateCard) {
        return sendWithRetry(CMD_RESPOND_MSG, reqId, attemptReqId -> {
            ObjectNode body = JsonUtil.newObject();
            body.put("msgtype", MSGTYPE_TEMPLATE_CARD);
            body.set(MSGTYPE_TEMPLATE_CARD, templateCard);
            return body;
        }, MAX_RETRIES, RESPONSE_TIMEOUT_MS);
    }

    /**
     * 流式回复。每次调用携带累积全文，finish=true 时结束流。
     * <p>流式消息频率高，中间帧超时和重试次数适当缩减。</p>
     */
    public boolean replyStream(String reqId, String streamId, String content, boolean finish) {
        int retries = finish ? MAX_RETRIES : 1;
        long timeout = finish ? RESPONSE_TIMEOUT_MS : 5_000;
        log.info("[WS] replyStream 调用 reqId={} streamId={} finish={} contentLen={} retries={} timeout={}ms",
                reqId, streamId, finish, content != null ? content.length() : 0, retries, timeout);
        return sendWithRetry(CMD_RESPOND_MSG, reqId, attemptReqId -> {
            ObjectNode stream = JsonUtil.newObject();
            stream.put("id", streamId);
            stream.put("finish", finish);
            if (content != null) {
                stream.put("content", content);
            }
            ObjectNode body = JsonUtil.newObject();
            body.put("msgtype", MSGTYPE_STREAM);
            body.set(MSGTYPE_STREAM, stream);
            return body;
        }, retries, timeout);
    }

    // ==================== 更新模板卡片 ====================

    public boolean updateTemplateCard(String reqId, List<String> userids, ObjectNode templateCard) {
        return sendWithRetry(CMD_RESPOND_UPDATE_MSG, reqId, attemptReqId -> {
            ObjectNode body = JsonUtil.newObject();
            body.put("response_type", "update_template_card");
            if (userids != null && !userids.isEmpty()) {
                ArrayNode arr = JsonUtil.newArray();
                userids.forEach(arr::add);
                body.set("userids", arr);
            }
            body.set(MSGTYPE_TEMPLATE_CARD, templateCard);
            return body;
        }, MAX_RETRIES, RESPONSE_TIMEOUT_MS);
    }

    // ═══════════════════════════════════════════════════════════════
    // 主动推送（同步阻塞 + 重试）
    // ═══════════════════════════════════════════════════════════════

    private boolean pushMessage(String chatid, String chattype, String msgtype,
                                ObjectNode contentBody) {
        String reqId = generateReqId();
        int chatTypeInt = chatTypeInt(chattype);
        return sendWithRetry(CMD_SEND_MSG, reqId, attemptReqId -> {
            ObjectNode body = JsonUtil.newObject();
            body.put("chatid", chatid);
            body.put("chat_type", chatTypeInt);
            body.put("msgtype", msgtype);
            java.util.Iterator<Map.Entry<String, JsonNode>> it = contentBody.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                body.set(e.getKey(), e.getValue());
            }
            return body;
        }, MAX_RETRIES, RESPONSE_TIMEOUT_MS);
    }

    public boolean pushText(String chatid, String chattype, String content) {
        ObjectNode text = JsonUtil.newObject();
        text.put("content", content);
        ObjectNode body = JsonUtil.newObject();
        body.set(MSGTYPE_TEXT, text);
        return pushMessage(chatid, chattype, MSGTYPE_TEXT, body);
    }

    public boolean pushMarkdown(String chatid, String chattype, String content) {
        ObjectNode markdown = JsonUtil.newObject();
        markdown.put("content", content);
        ObjectNode body = JsonUtil.newObject();
        body.set(MSGTYPE_MARKDOWN, markdown);
        return pushMessage(chatid, chattype, MSGTYPE_MARKDOWN, body);
    }

    public boolean pushTemplateCard(String chatid, String chattype, ObjectNode templateCard) {
        return pushMessage(chatid, chattype, MSGTYPE_TEMPLATE_CARD, templateCard);
    }

    public boolean pushStream(String chatid, String chattype, String streamId,
                              String content, boolean finish) {
        ObjectNode stream = JsonUtil.newObject();
        stream.put("id", streamId);
        stream.put("finish", finish);
        if (content != null) {
            stream.put("content", content);
        }
        ObjectNode body = JsonUtil.newObject();
        body.set(MSGTYPE_STREAM, stream);
        return pushMessage(chatid, chattype, MSGTYPE_STREAM, body);
    }

    // ═══════════════════════════════════════════════════════════════
    // 订阅 / 心跳（火焰即忘，不等待响应）
    // ═══════════════════════════════════════════════════════════════

    public void sendEnvelope(String cmd, String reqId, ObjectNode body) {
        ObjectNode msg = JsonUtil.newObject();
        msg.put("cmd", cmd);
        ObjectNode headers = JsonUtil.newObject();
        headers.put("req_id", reqId);
        msg.set("headers", headers);
        msg.set("body", body);
        String payload = JsonUtil.stringify(msg);

        int maxAttempts = "aibot_subscribe".equals(cmd) ? 3 : 1;
        for (int i = 0; i < maxAttempts; i++) {
            boolean sent = sendRaw(payload);
            if (sent) {
                log.info("[WS] sendEnvelope 发送成功 cmd={} reqId={} attempt={}/{}",
                        cmd, reqId, i + 1, maxAttempts);
                return;
            }
            log.warn("[WS] sendEnvelope sendRaw 失败! cmd={} reqId={} attempt={}/{}",
                    cmd, reqId, i + 1, maxAttempts);
            if (i < maxAttempts - 1) {
                try { Thread.sleep(200); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.error("[WS] sendEnvelope 最终失败! cmd={} reqId={} 已用尽{}次尝试",
                cmd, reqId, maxAttempts);
    }

    public void sendPing() {
        ObjectNode msg = JsonUtil.newObject();
        msg.put("cmd", CMD_PING);
        ObjectNode headers = JsonUtil.newObject();
        headers.put("req_id", generateReqId());
        msg.set("headers", headers);
        boolean sent = sendRaw(JsonUtil.stringify(msg));
        if (!sent) {
            log.warn("[WS] sendPing sendRaw 失败!");
        }
    }

    // ==================== 工具方法 ====================

    public static String generateReqId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static int chatTypeInt(String ct) {
        if ("single".equalsIgnoreCase(ct)) {
            return 1;
        }
        if ("group".equalsIgnoreCase(ct)) {
            return 2;
        }
        return 0;
    }

    private static String truncateForLog(String s) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= LOG_CONTENT_MAX) {
            return s;
        }
        return s.substring(0, LOG_CONTENT_MAX) + "...[truncated total=" + s.length() + "]";
    }
}
