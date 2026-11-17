package com.md.agentplatform.server.tool.workwx;

import com.md.agentplatform.server.service.WorkWxReplyService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 企业微信消息推送工具（多 Bot 感知）。
 * 通过指定 Bot 的智能机器人 WebSocket 长连接发送 markdown 消息到指定会话。
 */
@Component
public class WorkWxSendMsgTool {

    private static final Logger logger = LoggerFactory.getLogger(WorkWxSendMsgTool.class);

    @Autowired
    private WorkWxReplyService replyService;

    @Tool(
            name = "workwx-send-msg",
            description = "企业微信消息推送工具，通过指定智能机器人向会话发送 markdown 消息。"
                    + "需要消息内容、目标会话 ID、会话类型，可选 botName（默认 default）。"
    )
    public String sendMsg(
            @ToolParam(name = "text", description = "需要发送的消息内容，markdown 格式") String text,
            @ToolParam(name = "chatid", description = "目标会话 ID。群聊填 chatid，单聊填 userid") String chatid,
            @ToolParam(name = "chattype", description = "会话类型：group（群聊）或 single（单聊）") String chattype,
            @ToolParam(name = "botName", description = "企微 Bot 名，默认 default") String botName
    ) {
        long t0 = System.currentTimeMillis();

        if (chatid == null || chatid.isBlank()) {
            return "❌ 发送失败：chatid 不能为空";
        }
        if (chattype == null || chattype.isBlank()) {
            chattype = "group";
        }
        if (botName == null || botName.isBlank()) {
            botName = "default";
        }

        String markdown = buildMarkdownContent(text);
        replyService.pushMarkdown(botName, chatid, chattype, markdown);
        logger.info("企微 WS pushMarkdown 完成, bot:{}, chatid:{}, 耗时:{}ms",
                botName, chatid, System.currentTimeMillis() - t0);
        return "✅ 企业微信消息发送成功";
    }

    private String buildMarkdownContent(String text) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "**生成时间**：" + now + "\n\n"
                + text + "\n\n"
                + "> 🤖 本消息由智能助手自动生成";
    }
}
