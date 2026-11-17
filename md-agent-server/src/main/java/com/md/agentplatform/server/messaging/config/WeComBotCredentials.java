package com.md.agentplatform.server.messaging.config;

import java.util.Map;

/**
 * 企微长连接机器人（{@code ChannelKind.BOT}）的连接凭据 —— 由 {@code md_channel.properties}
 * 的 JSON 列翻译而来。
 *
 * <h2>为什么不再用 {@code MessagingProperties.WeComConfig}</h2>
 * 改造前凭据来自 {@code application.yml} 的 {@code agent-platform.messaging.bots[]}，
 * 于是"改一个 botId"要改配置文件 + 重启进程。改造后渠道是受治理资源（草稿/发布/回滚都在页面上），
 * 配置文件的 {@code bots[]} 段落被彻底删除 —— 凭据只能来自库。
 *
 * <h2>为什么与官方 {@code WeComChannelProperties} 是两个类</h2>
 * 同一平台的两套接入方式，凭据集合本就不同：官方回调式需要 corpId/agentId/token/encodingAesKey
 * （自建应用 + 公网回调地址），长连接机器人只要 botId/secret。
 * 硬合成一个类就会得到一堆"在另一种模式下没人读"的字段，
 * 而字段一旦存在，就会有人以为它有用。
 *
 * @param botId  机器人 id（企微管理后台"智能机器人"里的一串 id；也是绑定规则 account 层的账号标识）
 * @param secret 机器人 secret
 */
public record WeComBotCredentials(String botId, String secret) {

    public WeComBotCredentials {
        botId = require(botId, "botId");
        secret = require(secret, "secret");
    }

    /**
     * 从库里的凭据 Map 翻译。
     *
     * <p>key 必须与 {@code WeComBotChannelFactory} 的 {@link
     * com.md.agentplatform.server.channel.ChannelPropertySpec} 逐字一致，
     * 否则现象是"页面明明填了、装配却说没填"。
     */
    public static WeComBotCredentials from(Map<String, Object> properties) {
        Map<String, Object> p = properties == null ? Map.of() : properties;
        return new WeComBotCredentials(text(p, "botId"), text(p, "secret"));
    }

    private static String text(Map<String, Object> p, String key) {
        Object value = p.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("企微长连接凭据缺少 " + key + "，请在渠道的凭据里补上");
        }
        return value.trim();
    }
}
