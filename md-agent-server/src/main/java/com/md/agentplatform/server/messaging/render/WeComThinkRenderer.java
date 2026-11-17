package com.md.agentplatform.server.messaging.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.md.agentplatform.server.messaging.BotPlatform;
import com.md.agentplatform.server.messaging.InboundMessage;
import com.md.agentplatform.server.service.WorkWxReplyService;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.TraceRunIdGenerator;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业微信流式回复：think 折叠 + 答案展示。
 *
 * <p>迁自原 {@code MessageRouter.handleWeCom}，仅做两处改造：
 * <ol>
 *   <li>Gson 图片内容解析 → {@link JsonUtil}</li>
 *   <li>{@code blockLast()} → {@code subscribe()} + {@code doOnComplete}/{@code doOnError}，
 *       流式处理不再同步阻塞调用线程。</li>
 * </ol>
 */
@Component
public class WeComThinkRenderer extends AbstractReplyRenderer {

    private static final Logger log = LoggerFactory.getLogger(WeComThinkRenderer.class);

    /** 思考/推理/工具结果各段的截断长度。 */
    private static final int THINK_TRUNCATE = 1000;

    @Autowired
    private WorkWxReplyService workWxReplyService;

    @Override
    public ReplyStyle style() {
        return ReplyStyle.WECOM_THINK;
    }

    @Override
    public BotPlatform platform() {
        return BotPlatform.WECOM;
    }

    @Override
    public void render(InboundMessage msg, String agentName) {
        String reqId = msg.replyId();
        String streamId = UUID.randomUUID().toString().replace("-", "");
        // 这次对话的 runId：由本类先定，中间件照用（见 AgentTraceRegistry.attachRunId）。
        // 企微这边没有页面可以把 id 回执回来，所以"刚才那条回复是哪棵树跑的"唯一线索
        // 就是下面这行日志 —— 它必须和中间件的 RUN_START / 执行链路页上的 id 是同一个。
        String runId = TraceRunIdGenerator.next();

        List<Msg> msgs = buildMessages(msg);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(resolveSessionId(msg))
                .userId(msg.userId())
                .put(AgentTraceRegistry.RUN_ID_ATTR, runId)
                // 来源只影响观测页的筛选与展示，不参与任何业务判断（见 AgentTraceRegistry.SOURCE_ATTR）。
                .put(AgentTraceRegistry.SOURCE_ATTR, AgentTraceRun.SOURCE_WECOM)
                .build();
        log.info("[WeCom] 开始 run={} bot={} agent={} user={}",
                runId, msg.botName(), agentName, msg.userId());

        StringBuilder thinkBuf = new StringBuilder();
        StringBuilder thinkingBuf = new StringBuilder();
        StringBuilder textBuf = new StringBuilder();
        StringBuilder toolResultBuf = new StringBuilder();
        List<String> toolCallNames = new ArrayList<>();
        AtomicInteger thinkingNum = new AtomicInteger(0);
        AtomicInteger reasoningNum = new AtomicInteger(0);
        AtomicInteger toolNum = new AtomicInteger(0);
        String[] finalAnswer = {null};
        String[] lastTextContent = {null};

        workWxReplyService.replyStream(msg.botName(), reqId, streamId,
                "<think>⏳ 正在处理您的请求...</think>", false);

        Flux<AgentEvent> stream = agentManager.stream(agentName, msgs, ctx);

        stream.doOnNext(event -> {
            if (event instanceof AgentResultEvent result) {
                String text = result.getResult() != null ? result.getResult().getTextContent() : null;
                if (text != null && !text.isBlank()) {
                    finalAnswer[0] = text;
                }
                return;
            }
            if (event instanceof ThinkingBlockDeltaEvent td && td.getDelta() != null) {
                thinkingBuf.append(td.getDelta());
            } else if (event instanceof ThinkingBlockEndEvent) {
                if (!thinkingBuf.isEmpty()) {
                    int n = thinkingNum.incrementAndGet();
                    thinkBuf.append("\n\n---\n**🧠 思考过程 (").append(n).append(")**\n")
                            .append(truncate(thinkingBuf.toString(), THINK_TRUNCATE));
                    workWxReplyService.replyStream(msg.botName(), reqId, streamId,
                            "<think>" + thinkBuf + "</think>", false);
                    thinkingBuf.setLength(0);
                }
            } else if (event instanceof TextBlockDeltaEvent td && td.getDelta() != null) {
                textBuf.append(td.getDelta());
            } else if (event instanceof TextBlockEndEvent) {
                if (!textBuf.isEmpty()) {
                    lastTextContent[0] = textBuf.toString();
                    int n = reasoningNum.incrementAndGet();
                    thinkBuf.append("\n\n---\n**💬 推理输出 (").append(n).append(")**\n")
                            .append(truncate(textBuf.toString(), THINK_TRUNCATE));
                    workWxReplyService.replyStream(msg.botName(), reqId, streamId,
                            "<think>" + thinkBuf + "</think>", false);
                    textBuf.setLength(0);
                }
            } else if (event instanceof ToolCallStartEvent tc) {
                toolCallNames.add(tc.getToolCallName());
            } else if (event instanceof ToolResultTextDeltaEvent trd && trd.getDelta() != null) {
                if (!toolResultBuf.isEmpty()) {
                    toolResultBuf.append("\n");
                }
                toolResultBuf.append("[").append(trd.getToolCallName()).append("] ").append(trd.getDelta());
            } else if (event instanceof ToolResultEndEvent) {
                if (!toolCallNames.isEmpty()) {
                    int n = toolNum.incrementAndGet();
                    thinkBuf.append("\n\n---\n**🔧 工具调用 (").append(n).append(")**: ")
                            .append(String.join(", ", toolCallNames)).append("\n\n");
                    if (!toolResultBuf.isEmpty()) {
                        thinkBuf.append("**📋 工具结果**\n").append(truncate(toolResultBuf.toString(), THINK_TRUNCATE));
                    }
                    workWxReplyService.replyStream(msg.botName(), reqId, streamId,
                            "<think>" + thinkBuf + "</think>", false);
                    toolCallNames.clear();
                    toolResultBuf.setLength(0);
                }
            }
        }).doOnComplete(() -> {
            String answer;
            if (finalAnswer[0] != null && !finalAnswer[0].isBlank()) {
                answer = finalAnswer[0];
            } else if (lastTextContent[0] != null && !lastTextContent[0].isBlank()) {
                answer = lastTextContent[0];
            } else {
                answer = "⚠️ 抱歉，处理您的请求时遇到了问题，请稍后重试。";
            }

            if (thinkBuf.length() > 0) {
                String thinkContent = thinkBuf.toString();
                if (thinkContent.length() > 500) {
                    thinkContent = thinkContent.substring(0, 500) + "\n\n...(已截断)";
                }
                workWxReplyService.replyStream(msg.botName(), reqId, streamId,
                        "<think>" + thinkContent + "</think>\n\n" + answer, true);
            } else {
                workWxReplyService.replyStream(msg.botName(), reqId, streamId, answer, true);
            }
        }).doOnError(e -> {
            log.error("[Router] 企微流式处理异常 bot={} agent={}: {}",
                    msg.botName(), agentName, e.getMessage(), e);
            workWxReplyService.replyStream(msg.botName(), reqId, streamId,
                    "⚠️ 抱歉，处理您的请求时遇到了问题，请稍后重试。", true);
        }).subscribe();
    }

    private List<Msg> buildMessages(InboundMessage msg) {
        List<Msg> messages = new ArrayList<>();
        messages.add(nowMessage());

        String content = msg.content();
        switch (msg.msgType()) {
            case "text", "voice" ->
                    messages.add(Msg.builder().role(MsgRole.USER).textContent(content).build());
            case "image" -> {
                try {
                    JsonNode imageObj = JsonUtil.parse(content);
                    String imageUrl = imageObj.has("url") ? imageObj.get("url").asText() : null;
                    String aeskey = imageObj.has("aeskey") ? imageObj.get("aeskey").asText() : null;
                    if (imageUrl != null) {
                        HttpClient client = HttpClient.newHttpClient();
                        HttpResponse<byte[]> response = client.send(
                                HttpRequest.newBuilder().uri(URI.create(imageUrl)).GET().build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                        byte[] imageBytes = response.body();
                        if (aeskey != null && !aeskey.isEmpty()) {
                            imageBytes = decryptWorkWxFile(imageBytes, aeskey);
                        }
                        String base64 = Base64.getEncoder().encodeToString(imageBytes);
                        messages.add(Msg.builder().content(List.of(
                                io.agentscope.core.message.TextBlock.builder().text("这张图片内容是什么？").build(),
                                io.agentscope.core.message.ImageBlock.builder()
                                        .source(io.agentscope.core.message.Base64Source.builder()
                                                .data(base64).mediaType("image/jpeg").build())
                                        .build())).build());
                    }
                } catch (Exception e) {
                    log.error("下载企微图片失败", e);
                    messages.add(Msg.builder().role(MsgRole.USER)
                            .textContent("用户发送了一张图片，但下载失败: " + e.getMessage()).build());
                }
            }
            case "file" ->
                    messages.add(Msg.builder().role(MsgRole.USER)
                            .textContent("（系统提示：用户刚才发送了一个文件。目前暂不支持处理文件内容，"
                                    + "请你友好地告知用户。）").build());
            default ->
                    messages.add(Msg.builder().role(MsgRole.USER).textContent(content).build());
        }
        return messages;
    }

    private byte[] decryptWorkWxFile(byte[] aesMsg, String aeskey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(aeskey + "=");
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        byte[] iv = Arrays.copyOfRange(keyBytes, 0, 16);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
        byte[] padded = cipher.doFinal(aesMsg);
        int padLen = padded[padded.length - 1] & 0xFF;
        return Arrays.copyOf(padded, padded.length - padLen);
    }

    /**
     * 会话分区键 —— 框架的记忆按 (userId, sessionId) 两维分区，粒度由这里决定。
     *
     * <p>三个必须显式处理、不能交给框架兜底的点：
     * <ul>
     *   <li><b>单聊没有 chatid</b>：{@link InboundMessage#chatId()} 只有群聊才有值，单聊为 {@code null}。
     *       直接透传 null 会被框架静默回落到 {@code defaultSessionId}（配置值，缺省是 agent 名），
     *       所有单聊用户就落到同一个分区里 —— 目前只是靠 userId 也参与分区才没串，属于隐式依赖。</li>
     *   <li><b>日期带在 sessionId 上而不是 userId 上</b>：userId 是框架分区里的身份轴，
     *       也是"谁在和我说话"的唯一来源，掺日期等于每天换一个人 —— 任何按 userId 识别用户
     *       的逻辑（取用户资料、回推 @人）都会失效。要按天重置上下文，正确的维度是 sessionId。</li>
     *   <li><b>分隔符要按「文件名」的标准挑，不能只看 {@code validateSessionId}</b>：
     *       框架只禁止 {@code /} 与 {@code \}（长度上限 255），{@code :} 能过校验；
     *       但 HARNESS agent 会把 sessionId 直接当成任务文件名
     *       （{@code <workspace>/<userId>/agents/<agent>/tasks/<sessionId>.json}），
     *       Windows 下 {@code :} 会让 {@code Path.of} 抛 {@code InvalidPathException}，
     *       表现为 harness 一进 REASONING 就崩。所以统一过 {@link #sanitize}。</li>
     * </ul>
     *
     * <p>想关掉"按天重置"，去掉末尾的 {@code + "-" + LocalDate.now()} 即可。
     */
    static String resolveSessionId(InboundMessage msg) {
        String chatId = msg.chatId();
        String scope = (chatId != null && !chatId.isBlank())
                ? chatId                         // 群聊：一个群一份记忆
                : "single-" + msg.userId();      // 单聊：显式按人，不吃 defaultSessionId 兜底
        return sanitize(scope + "-" + LocalDate.now());
    }

    /**
     * 收敛成文件名安全字符集 —— sessionId 在 HARNESS 下会变成任务文件名，
     * 含 {@code : * ? " < > |} 等字符时 Windows 直接抛 InvalidPathException。
     * {@code . _ -} 保留，其余一律替换成 {@code -}。
     */
    static String sanitize(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
