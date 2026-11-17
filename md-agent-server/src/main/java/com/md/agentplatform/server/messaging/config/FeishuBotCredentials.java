package com.md.agentplatform.server.messaging.config;

import java.util.Map;

/**
 * 飞书长连接机器人（{@code ChannelKind.BOT}）的连接凭据 —— 由 {@code md_channel.properties}
 * 的 JSON 列翻译而来。
 *
 * <h2>与官方 {@code FeishuChannelProperties} 的差别（只差在尾部两个字段）</h2>
 * <ul>
 *   <li>{@code verificationToken / encryptKey}：两者同名同义，官方用来校验 HTTP 回调，这里用来校验 WS 事件；</li>
 *   <li>官方是 {@code callbackPath}（把回调地址暴露到哪里），这里是 {@code baseUrl}
 *       （REST 调用根地址，{@code searchWiki} 等直接拼在它后面）。</li>
 * </ul>
 * 语义不同因此不共用；共用会造出"这个字段在另一种模式下是死的"这种模糊字段。
 *
 * <h2>为什么 verificationToken / encryptKey 不是必填</h2>
 * 飞书允许"不加密 + 不校验 token"的事件订阅（仅限内网/测试环境），
 * 官方 properties 也把它们当可空。这里保持一致，不额外收紧 ——
 * 收紧只会让本来能跑的环境报一个它无法理解的前置条件。
 *
 * @param appId             应用 app id（也是绑定规则 account 层的账号标识）
 * @param appSecret         应用密钥
 * @param verificationToken 事件订阅校验 token；空 = 不校验
 * @param encryptKey        事件加密 key；空 = 明文事件
 * @param baseUrl           REST 调用根地址；空 = {@link #DEFAULT_BASE_URL}
 */
public record FeishuBotCredentials(
        String appId, String appSecret, String verificationToken, String encryptKey, String baseUrl) {

    /** 飞书开放平台默认根地址。 */
    public static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    public FeishuBotCredentials {
        appId = require(appId, "appId");
        appSecret = require(appSecret, "appSecret");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }

    /** 从库里的凭据 Map 翻译；key 必须与 {@code FeishuBotChannelFactory} 的描述逐字一致。 */
    public static FeishuBotCredentials from(Map<String, Object> properties) {
        Map<String, Object> p = properties == null ? Map.of() : properties;
        return new FeishuBotCredentials(
                text(p, "appId"),
                text(p, "appSecret"),
                text(p, "verificationToken"),
                text(p, "encryptKey"),
                text(p, "baseUrl"));
    }

    private static String text(Map<String, Object> p, String key) {
        Object value = p.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("飞书长连接凭据缺少 " + key + "，请在渠道的凭据里补上");
        }
        return value.trim();
    }
}
