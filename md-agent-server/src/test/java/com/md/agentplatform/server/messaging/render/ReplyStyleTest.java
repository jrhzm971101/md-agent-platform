package com.md.agentplatform.server.messaging.render;

import com.md.agentplatform.server.messaging.BotPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplyStyleTest {

    @Test
    void resolveUsesOverrideWhenPresent() {
        assertEquals(ReplyStyle.FEISHU_CARD, ReplyStyle.resolve(BotPlatform.WECOM, ReplyStyle.FEISHU_CARD));
        assertEquals(ReplyStyle.WECOM_THINK, ReplyStyle.resolve(BotPlatform.FEISHU, ReplyStyle.WECOM_THINK));
    }

    @Test
    void resolveFallsBackToPlatformDefault() {
        assertEquals(ReplyStyle.WECOM_THINK, ReplyStyle.resolve(BotPlatform.WECOM, null));
        assertEquals(ReplyStyle.FEISHU_CARD, ReplyStyle.resolve(BotPlatform.FEISHU, null));
    }
}
