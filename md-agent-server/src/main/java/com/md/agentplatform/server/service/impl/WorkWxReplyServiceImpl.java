package com.md.agentplatform.server.service.impl;

import com.md.agentplatform.server.messaging.BotInstance;
import com.md.agentplatform.server.messaging.BotInstanceManager;
import com.md.agentplatform.server.messaging.wecom.WeComBotInstance;
import com.md.agentplatform.server.messaging.wecom.WorkWxWsClient;
import com.md.agentplatform.server.service.WorkWxReplyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 企微消息发送服务（多 Bot 感知）。
 *
 * <p>发送链路可靠性保证：
 * <ol>
 *   <li>{@link WorkWxWsClient} 内部同步等待服务端响应，校验 {@code errcode==0}，失败自动重试（最多 3 次）</li>
 *   <li>本层处理连接断开场景 —— 等待重连后继续发送，不丢弃任何消息</li>
 * </ol>
 */
@Component
public class WorkWxReplyServiceImpl implements WorkWxReplyService {

    private static final Logger log = LoggerFactory.getLogger(WorkWxReplyServiceImpl.class);

    /** @Lazy 打破 BotInstanceManager → MessageRouter → 本服务 → BotInstanceManager 的循环。 */
    @Lazy
    @Autowired
    private BotInstanceManager botInstanceManager;

    @Override
    public void replyStream(String botName, String reqId, String streamId, String content, boolean finish) {
        sendStreamWithRetry(botName, reqId, streamId, content, finish);
    }

    @Override
    public void replyText(String botName, String reqId, String content) {
        sendWithRetry(botName, c -> c.replyText(reqId, content), "replyText", reqId, 10);
    }

    @Override
    public void pushMarkdown(String botName, String chatid, String chattype, String content) {
        sendWithRetry(botName, c -> c.pushMarkdown(chatid, chattype, content), "pushMarkdown", chatid, 10);
    }

    @Override
    public void pushStream(String botName, String chatid, String chattype, String streamId,
                           String content, boolean finish) {
        sendWithRetry(botName,
                c -> c.pushStream(chatid, chattype, streamId, content, finish),
                "pushStream", chatid, finish ? 60 : 10);
    }

    // ==================== 带重试的发送 ====================

    private void sendStreamWithRetry(String botName, String reqId, String streamId,
                                     String content, boolean finish) {
        int maxWaitSec = finish ? 60 : 10;
        boolean ok = sendWithRetry(botName,
                c -> c.replyStream(reqId, streamId, content, finish),
                "stream", reqId, maxWaitSec);
        if (ok) {
            log.info("[Wx] replyStream 发送成功 bot={} reqId={} finish={}",
                    botName, reqId, finish);
        } else if (finish) {
            String preview = content != null
                    ? content.substring(0, Math.min(content.length(), 500)) : "<null>";
            log.error("[Wx] ██████ 最终帧发送失败！用户将看不到完整回复！██████\n"
                            + "[Wx]   bot={} reqId={} streamId={}\n"
                            + "[Wx]   content={}",
                    botName, reqId, streamId, preview);
        }
    }

    /**
     * 外层重试：仅处理连接断开场景（等待重连）。
     * 单次发送的可靠性（响应校验 + 协议层重试）已由 {@link WorkWxWsClient} 内部保证。
     */
    private boolean sendWithRetry(String botName, Function<WorkWxWsClient, Boolean> send,
                                  String action, String id, int maxWaitSec) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + maxWaitSec * 1000L;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            WorkWxWsClient c = resolveClient(botName);
            if (c != null && c.isConnected()) {
                boolean ok = send.apply(c);
                if (ok) {
                    if (attempt > 0) {
                        log.info("[Wx] {} 重试成功 bot={} id={} attempt={} elapsed={}ms",
                                action, botName, id, attempt + 1, System.currentTimeMillis() - startTime);
                    }
                    return true;
                }
                log.warn("[Wx] {} 发送失败(attempt={}) bot={} id={} 等待重试...",
                        action, attempt + 1, botName, id);
            } else {
                if (attempt == 0) {
                    log.warn("[Wx] {} 无连接! 开始等待重连 bot={} id={} clientNull={}",
                            action, botName, id, c == null);
                } else if (attempt % 4 == 0) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    log.warn("[Wx] {} 等待重连中... bot={} id={} attempt={} elapsed={}s",
                            action, botName, id, attempt, elapsed);
                }
            }
            attempt++;
            try { Thread.sleep(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        log.error("[Wx] ██████ {} 重试耗尽，消息丢失！██████\n"
                        + "[Wx]   bot={} id={} maxWait={}s totalElapsed={}ms totalAttempts={}",
                action, botName, id, maxWaitSec, System.currentTimeMillis() - startTime, attempt);
        return false;
    }

    private WorkWxWsClient resolveClient(String botName) {
        BotInstance b = botInstanceManager.get(botName);
        if (b instanceof WeComBotInstance wc) {
            return wc.getClient();
        }
        log.warn("[Wx] WeCom bot 不存在或未就绪: {}", botName);
        return null;
    }
}
