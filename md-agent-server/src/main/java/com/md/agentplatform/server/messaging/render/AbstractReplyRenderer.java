package com.md.agentplatform.server.messaging.render;

import com.md.agentplatform.server.agent.AgentManager;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 渲染器公共基类：注入 {@link AgentManager} 并提供共享的截断、时间注入工具。
 */
public abstract class AbstractReplyRenderer implements ReplyRenderer {

    @Autowired
    protected AgentManager agentManager;

    /** 原 {@code MessageRouter.truncate}，迁入此处供各渲染器复用。 */
    protected static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[+" + (s.length() - max) + " chars]";
    }

    /** 时间注入消息（原 buildXxxMessages 里重复的 LocalDateTime 逻辑）。 */
    protected static Msg nowMessage() {
        return Msg.builder().role(MsgRole.USER)
                .textContent("现在时间是: " + LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy年M月d号, H点m分s秒. ")))
                .build();
    }
}
