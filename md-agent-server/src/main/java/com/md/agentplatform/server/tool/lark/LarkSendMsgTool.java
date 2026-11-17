package com.md.agentplatform.server.tool.lark;

import com.md.agentplatform.model.agent.tool.SendMsgRequest;
import com.md.agentplatform.server.service.LarkOperationService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 飞书消息发送工具（多 Bot 感知）。
 */
@Component
public class LarkSendMsgTool {

    @Autowired
    private LarkOperationService larkOperationService;

    @Tool(
            name = "lark-send-msg",
            description = "发送飞书消息工具, 需要发送消息内容、接收人 open_id，可选 appName（默认 default）"
    )
    public String sendMsg(
            @ToolParam(name = "text", description = "需要发送的飞书消息") String text,
            @ToolParam(name = "open_id", description = "接收人的唯一身份标识 open_id") String openId,
            @ToolParam(name = "appName", description = "飞书 Bot 名，默认 default") String appName
    ) {
        if (appName == null || appName.isBlank()) {
            appName = "default";
        }
        larkOperationService.sendMsg(appName, SendMsgRequest.builder()
                .msg(text).msgType("text").receiveIdType("open_id").identification(openId).build());
        return "发送飞书消息成功";
    }
}
