package com.md.agentplatform.server.messaging.render;

import com.md.agentplatform.server.messaging.BotPlatform;

import java.util.Locale;

/**
 * 回复风格枚举 —— 把"回复长什么样"从"平台"里拆出来，成为可插拔、可配置的维度。
 *
 * <p>{@link #resolve(BotPlatform, ReplyStyle)} 提供缺省推断：bot 未显式配置时按平台取默认值，
 * 与改造前行为完全一致。新增平台时编译器会强制补全 switch 分支。
 */
public enum ReplyStyle {
    WECOM_THINK,
    FEISHU_CARD;

    public static ReplyStyle resolve(BotPlatform platform, ReplyStyle override) {
        if (override != null) {
            return override;
        }
        return switch (platform) {
            case WECOM -> WECOM_THINK;
            case FEISHU -> FEISHU_CARD;
        };
    }

    /**
     * 把库里 {@code md_channel.reply_style} 的字符串翻成枚举。
     *
     * <p>空值或不认识的值都返回 {@code null}（= "没配"，让 {@link #resolve} 按平台推断），
     * 而不是抛异常或猜一个：这个字段只影响"回复长什么样"，
     * 为一个不影响消息进出的字段让整条消息发不出去，代价明显不对等。
     * 大小写不敏感（大小写在编辑框里手滑的概率，远高于"故意写小写"的概率）。
     */
    public static ReplyStyle parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
