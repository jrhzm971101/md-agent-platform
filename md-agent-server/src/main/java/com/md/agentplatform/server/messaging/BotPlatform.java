package com.md.agentplatform.server.messaging;

/**
 * 交互平台枚举。新增平台（钉钉/Slack/Telegram）只需新增枚举值 +
 * 对应 {@link BotInstance} 实现类，无需改动核心框架。
 */
public enum BotPlatform {
    WECOM,
    FEISHU
}
