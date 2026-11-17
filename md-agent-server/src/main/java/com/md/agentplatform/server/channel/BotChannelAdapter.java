package com.md.agentplatform.server.channel;

import com.md.agentplatform.server.messaging.BotInstance;

/**
 * 长连接机器人通道（{@link ChannelKind#BOT}）—— 包装本项目的 {@link BotInstance}。
 *
 * <p>额外暴露 {@link #accountId()}：绑定规则里的 {@code account} 层需要"哪个机器人账号"这个维度
 * （同一平台配多个机器人时，靠它区分规则）。企微取值是 botId、飞书是 appId ——
 * 由实现决定，因为只有它知道自己的凭据里哪个字段是账号标识。
 */
public interface BotChannelAdapter extends ChannelAdapter {

    BotInstance bot();

    /** 账号标识，用于绑定规则的 account 层匹配；没有这个概念时返回 null。 */
    String accountId();
}
