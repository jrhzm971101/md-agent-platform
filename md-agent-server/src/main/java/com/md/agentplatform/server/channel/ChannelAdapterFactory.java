package com.md.agentplatform.server.channel;

import io.agentscope.harness.agent.gateway.channel.ChannelConfig;

import java.util.Map;

/**
 * 渠道类型工厂 —— 与官方 {@code ChannelFactory} 同形（channelId + 路由配置 + 凭据 → 通道实例），
 * 区别只在返回的是本项目的 {@link ChannelAdapter}（理由见该接口的类注释）。
 *
 * <h2>为什么用「工厂 + 进程内注册表」而不是一堆 if-else</h2>
 * 装配期需要按 {@code md_channel.channel_type} 造不同的通道，如果写成
 * {@code if ("wecom".equals(type)) ... else if ("feishu".equals(type)) ...}，
 * 那么「加一个平台」要改三处：装配逻辑、页面选项、类型校验。
 * 这里每个平台只贡献一个工厂（连同它自带的描述），其余全部自动成立。
 *
 * <h2>为什么凭据是 Map 而不是强类型对象</h2>
 * 凭据存的是 {@code md_channel.properties}（JSON 列），它的形状<b>按类型而变</b>：
 * 企微两个字段、飞书五个字段。让接口收强类型就等于让接口认识每一个平台，
 * 于是每加一个平台都要改接口签名。工厂自己把 Map 翻成它要的对象（官方实现也是这么做的，
 * 见 {@code WeComChannelProperties.from(channelId, props)}），这一步是本平台最容易出错的地方，
 * 所以约定：<b>翻不动就抛 {@code ChannelValidationException}，把缺哪个 key 说清楚</b>。
 */
public interface ChannelAdapterFactory {

    /** 该类型的描述（含页面表单需要的凭据字段）。 */
    ChannelTypeDescriptor descriptor();

    /**
     * 造一个通道实例。此时<b>还没有启动</b>：官方通道要先 {@code bindGateway}，
     * 长连接通道的 {@code start()} 会建立连接。装配顺序由 {@link ChannelRuntime} 统一控制。
     *
     * @param channelId  渠道唯一名（= md_channel.channel_id）
     * @param routing    路由配置（兜底 agent / dmScope / 绑定规则）
     * @param properties 类型特有凭据（md_channel.properties）
     * @throws ChannelValidationException 凭据缺失或非法
     */
    ChannelAdapter create(String channelId, ChannelConfig routing, Map<String, Object> properties);
}
