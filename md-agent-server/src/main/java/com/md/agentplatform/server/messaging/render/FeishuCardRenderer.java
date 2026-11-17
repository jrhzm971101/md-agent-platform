package com.md.agentplatform.server.messaging.render;

import com.md.agentplatform.server.config.AgentPlatformProperties;
import com.md.agentplatform.server.messaging.BotPlatform;
import com.md.agentplatform.server.messaging.InboundMessage;
import com.md.agentplatform.server.service.LarkOperationService;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.TraceRunIdGenerator;
import com.md.agentplatform.server.tools.JsonSchemaConstant;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 飞书卡片流式回复。
 *
 * <p>迁自原 {@code MessageRouter.handleFeishu}，仅做 {@code blockLast()} → {@code subscribe()}
 * 的非阻塞改造，其余逻辑原样保留（verbose 白名单控制"只出结果/出全过程"）。
 */
@Component
public class FeishuCardRenderer extends AbstractReplyRenderer {

    private static final Logger log = LoggerFactory.getLogger(FeishuCardRenderer.class);

    private static final String PREFIX_THINKING = "thinking";
    private static final String PREFIX_REASONING = "reasoning";
    private static final String PREFIX_TOOL_RESULT = "tool";
    private static final String PREFIX_FINAL = "final";

    @Autowired
    private LarkOperationService larkOperationService;

    @Autowired
    private AgentPlatformProperties platformProperties;

    @Override
    public ReplyStyle style() {
        return ReplyStyle.FEISHU_CARD;
    }

    @Override
    public BotPlatform platform() {
        return BotPlatform.FEISHU;
    }

    @Override
    public void render(InboundMessage msg, String agentName) {
        String cardId = larkOperationService.replayCardByMessageId(msg.botName(), msg.msgId());
        boolean isVerbose = platformProperties.verboseTraceUsersAsSet().contains(msg.userId());
        // 这次对话的 runId：由本类先定，中间件照用（见 AgentTraceRegistry.attachRunId）。
        // 卡片上不显示它（那是给用户看的内容），但日志里有 —— 排查时按 run 号去
        // 执行链路页能直接找到同一棵树。
        String runId = TraceRunIdGenerator.next();
        log.info("[Feishu] 开始 run={} bot={} agent={} user={} verbose={}",
                runId, msg.botName(), agentName, msg.userId(), isVerbose);

        List<Msg> msgs = buildMessages(msg);
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(msg.userId())
                .userId(msg.userId())
                .put("cardId", cardId)
                .put(AgentTraceRegistry.RUN_ID_ATTR, runId)
                // 来源只影响观测页的筛选与展示，不参与任何业务判断（见 AgentTraceRegistry.SOURCE_ATTR）。
                .put(AgentTraceRegistry.SOURCE_ATTR, AgentTraceRun.SOURCE_FEISHU)
                .build();

        StringBuilder thinkingBuf = new StringBuilder();
        StringBuilder textBuf = new StringBuilder();
        StringBuilder toolResultBuf = new StringBuilder();
        List<String> toolCallNames = new ArrayList<>();
        AtomicInteger thinkingNum = new AtomicInteger(0);
        AtomicInteger reasoningNum = new AtomicInteger(0);
        AtomicInteger toolNum = new AtomicInteger(0);
        String[] finalResponse = {null};

        if (!isVerbose) {
            larkOperationService.addElementToCard(msg.botName(), cardId,
                    JsonSchemaConstant.getDefaultElement("processing_hint"), "processing_hint");
            larkOperationService.streamUpdateCardElement(msg.botName(), cardId, "processing_hint",
                    "⏳ 正在处理您的请求...");
        }

        Flux<AgentEvent> stream = agentManager.stream(agentName, msgs, ctx);

        stream.doOnNext(event -> {
            if (!isVerbose) {
                if (event instanceof AgentResultEvent result) {
                    finalResponse[0] = result.getResult().getTextContent();
                }
                return;
            }

            if (event instanceof ThinkingBlockDeltaEvent td && td.getDelta() != null) {
                thinkingBuf.append(td.getDelta());
            } else if (event instanceof ThinkingBlockEndEvent) {
                if (!thinkingBuf.isEmpty()) {
                    int n = thinkingNum.incrementAndGet();
                    addCardRoundElement(msg.botName(), cardId, PREFIX_THINKING + "_" + n,
                            "**🧠 思考过程 (" + n + ")**\n" + truncate(thinkingBuf.toString(), 2000));
                    thinkingBuf.setLength(0);
                }
            } else if (event instanceof TextBlockDeltaEvent td && td.getDelta() != null) {
                textBuf.append(td.getDelta());
            } else if (event instanceof TextBlockEndEvent) {
                if (!textBuf.isEmpty()) {
                    int n = reasoningNum.incrementAndGet();
                    addCardRoundElement(msg.botName(), cardId, PREFIX_REASONING + "_" + n,
                            "**💬 推理输出 (" + n + ")**\n" + truncate(textBuf.toString(), 2000));
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
                if (!toolResultBuf.isEmpty() || !toolCallNames.isEmpty()) {
                    int n = toolNum.incrementAndGet();
                    StringBuilder content = new StringBuilder();
                    if (!toolCallNames.isEmpty()) {
                        content.append("**🔧 工具调用 (").append(n).append(")**: ")
                                .append(String.join(", ", toolCallNames)).append("\n\n");
                    }
                    if (!toolResultBuf.isEmpty()) {
                        content.append("**📋 工具结果**\n").append(truncate(toolResultBuf.toString(), 1500));
                    }
                    addCardRoundElement(msg.botName(), cardId, PREFIX_TOOL_RESULT + "_" + n, content.toString());
                    toolCallNames.clear();
                    toolResultBuf.setLength(0);
                }
            } else if (event instanceof AgentResultEvent result) {
                finalResponse[0] = result.getResult().getTextContent();
                if (finalResponse[0] != null && !finalResponse[0].isBlank()) {
                    addCardRoundElement(msg.botName(), cardId, PREFIX_FINAL + "_1",
                            "**✅ 最终回复**\n" + finalResponse[0]);
                }
            }
        }).doOnComplete(() -> {
            if (!isVerbose) {
                if (finalResponse[0] != null && !finalResponse[0].isBlank()) {
                    addCardRoundElement(msg.botName(), cardId, PREFIX_FINAL + "_1",
                            "**✅ 最终回复**\n" + finalResponse[0]);
                } else {
                    larkOperationService.streamUpdateCardElement(msg.botName(), cardId, "processing_hint",
                            "⚠️ 抱歉，处理您的请求时遇到了问题，请稍后重试。");
                }
            }
            larkOperationService.removeCardSequence(cardId);
        }).doOnError(e -> {
            log.error("[Router] 飞书流式处理异常 bot={} agent={}: {}",
                    msg.botName(), agentName, e.getMessage(), e);
            if (!isVerbose) {
                larkOperationService.streamUpdateCardElement(msg.botName(), cardId, "processing_hint",
                        "⚠️ 抱歉，处理您的请求时遇到了问题，请稍后重试。");
            }
            larkOperationService.removeCardSequence(cardId);
        }).subscribe();
    }

    private void addCardRoundElement(String appName, String cardId, String elementId, String content) {
        if (cardId == null) {
            return;
        }
        larkOperationService.addElementToCard(appName, cardId,
                JsonSchemaConstant.getDefaultElement(elementId), elementId);
        larkOperationService.streamUpdateCardElement(appName, cardId, elementId, content);
    }

    private List<Msg> buildMessages(InboundMessage msg) {
        List<Msg> messages = new ArrayList<>();
        messages.add(nowMessage());

        Map<String, String> inputs = msg.inputs();
        if ("text".equals(msg.msgType())) {
            messages.add(Msg.builder().role(MsgRole.USER)
                    .textContent(inputs.getOrDefault("text", msg.content())).build());
        } else {
            messages.add(Msg.builder().role(MsgRole.USER)
                    .textContent("（系统提示：收到类型为 " + msg.msgType() + " 的消息，当前暂不支持该类型，请引导用户改用文字。）").build());
        }
        return messages;
    }
}
