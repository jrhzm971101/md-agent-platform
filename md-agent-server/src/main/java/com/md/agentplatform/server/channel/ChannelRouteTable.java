package com.md.agentplatform.server.channel;

import com.md.agentplatform.server.controlplane.store.entity.ChannelBindingEntity;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;
import com.md.agentplatform.server.messaging.BotPlatform;
import com.md.agentplatform.server.messaging.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.Peer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 路由表 —— 把「库里的渠道配置」与「这条消息该给谁」连起来的一层只读缓存。
 *
 * <h2>一、为什么单独一个 Bean，而不是让 MessageRouter 直接问 ChannelRuntime</h2>
 * 这是一个<b>刻意的解环</b>：长连接通道在构造时需要 {@code InboundMessageHandler}（即 MessageRouter），
 * 而 MessageRouter 需要路由结论，路由结论又来自"已装配的渠道"——如果 MessageRouter 直接依赖
 * ChannelRuntime（它持有通道实例），Spring 的依赖图就会出现
 * {@code ChannelRuntime → BotInstanceManager → MessageRouter → ChannelRuntime} 这个环。
 *
 * <p>拆开之后方向是单向的：
 * {@code ChannelRuntime → ChannelRouteTable ← MessageRouter}，
 * 写入只有 ChannelRuntime 一处（每次重载时整体替换），读取只有 MessageRouter 一处。
 * 环没了，也不必靠 {@code @Lazy} 去掩盖。
 *
 * <h2>二、为什么缓存而不是每条消息都查库</h2>
 * 消息是热路径：企微群里聊十句就是十次查询，而渠道配置是冷数据（改一次要人去点发布）。
 * 缓存的生命周期由 ChannelRuntime 控制：库变了就整体替换 —— 不做单条失效，
 * 因为替换的是不可变快照，读侧无锁、也不需要"读到半新半旧"的一致性推理。
 *
 * <h2>三、这一层同时承担"旧结构与官方路由模型"的翻译</h2>
 * 本项目的入站消息（{@code messaging.InboundMessage}）是平台无关的扁平结构，
 * 官方路由要的是带 {@link Peer} 的消息。翻译只写在这里，是因为它属于
 * 「路由」这件事的知识：写到别处（比如各 BotInstance）就会出现"每条通道各翻一套"。
 */
@Component
public class ChannelRouteTable {

    private final AgentGatewayRuntime gatewayRuntime;

    /** 不可变快照：重载时整体替换，读侧无锁。 */
    private volatile Map<String, CachedChannelRoute> routes = Map.of();

    public ChannelRouteTable(AgentGatewayRuntime gatewayRuntime) {
        this.gatewayRuntime = gatewayRuntime;
    }

    /**
     * 一个渠道的路由缓存。
     *
     * @param channelId   渠道名
     * @param channelType 类型（wecom / feishu / wecom-bot ...），仅用于日志与观测
     * @param accountId   账号标识（企微 botId / 飞书 appId），供绑定规则的 account 层匹配
     * @param replyStyle  回复渲染风格；null = 按平台推断（沿用改造前的语义）
     * @param config      官方路由配置（已含绑定规则）
     */
    public record CachedChannelRoute(
            String channelId, String channelType, String accountId, String replyStyle, ChannelConfig config) {}

    /** 由 ChannelRuntime 在全量装配/重载后调用：整体替换路由快照。 */
    public void replaceAll(List<CachedChannelRoute> entries) {
        Map<String, CachedChannelRoute> map = new LinkedHashMap<>();
        for (CachedChannelRoute entry : entries) {
            map.put(entry.channelId(), entry);
        }
        swap(map);
    }

    /**
     * 单渠道新增/覆盖一条路由。
     *
     * <h2>为什么必须有这个方法，而不是让调用方拼一个单元素列表去调 replaceAll</h2>
     * {@link #replaceAll(List)} 的语义是「以库为准的全量快照」——传进去什么，路由表就只剩什么。
     * 单渠道热生效（发布一个渠道、页面上点「重载」）如果借道它，就会把**其它所有渠道**
     * 从路由表里抹掉，现象是「发布 A 渠道，结果 B 渠道的消息开始报 Agent 不存在」，
     * 而且只在多渠道的部署上才会出现，本地单渠道测不出来。
     *
     * <p>所以单渠道的写入必须走这里：<b>读现快照 → 改一条 → 整体换掉</b>。
     * 与 replaceAll 共用 {@link #swap}，保证两条路写出来的都是同一种"不可变快照"。
     */
    public void upsert(CachedChannelRoute entry) {
        Objects.requireNonNull(entry, "entry");
        Map<String, CachedChannelRoute> map = new LinkedHashMap<>(routes);
        map.put(entry.channelId(), entry);
        swap(map);
    }

    /**
     * 单渠道移除（渠道被删除时调用）。
     *
     * <p>不做「不存在就直接返回」以外的判断：幂等，重复移除无害。
     * 渠道只是被<b>停用</b>时不该调这里 —— 路由是"知识"，与"该不该在收消息"是两件事：
     * 长连接刚断时进在途的消息、回调重放的消息，都还需要这份配置算出该给谁
     * （与 {@code ChannelRuntime.applyAll} 覆盖"全部渠道（含已停用的）"是同一口径）。
     */
    public void remove(String channelId) {
        if (channelId == null || !routes.containsKey(channelId)) {
            return;
        }
        Map<String, CachedChannelRoute> map = new LinkedHashMap<>(routes);
        map.remove(channelId);
        swap(map);
    }

    /**
     * 所有写入的唯一出口：把新内容做成不可变快照后整体替换。
     *
     * <p>读侧（{@code MessageRouter} / {@code route}）因此永远看到"完整的某一版"，
     * 不会读到"改了一半"的中间态，也就不需要加锁。
     */
    private void swap(Map<String, CachedChannelRoute> map) {
        this.routes = Map.copyOf(map);
    }

    public Optional<CachedChannelRoute> find(String channelId) {
        return channelId == null ? Optional.empty() : Optional.ofNullable(routes.get(channelId));
    }

    public List<CachedChannelRoute> all() {
        return new ArrayList<>(routes.values());
    }

    /**
     * 解析一条入站消息该交给哪个 agent。
     *
     * <p>渠道不在表里时<b>仍然给出结论</b>（落到全局兜底 agent），而不是抛异常：
     * 消息已经到了，丢掉它比"给一个可能不对的 agent"更糟 —— 前者用户完全看不到回应，
     * 后者至少能在链路里看到"它被交给了谁、命中层级是 global-default"。
     * 这种情况本身是异常信号（库里有渠道、路由表里没有），所以日志按 warn 打。
     */
    public RouteDecision route(String channelId, InboundMessage message) {
        CachedChannelRoute cached = routes.get(channelId);
        ChannelConfig config = cached != null
                ? cached.config()
                : ChannelConfig.builder(channelId).build();
        return gatewayRuntime.route(config, toOfficial(channelId, cached, message));
    }

    /**
     * 旧结构 → 官方路由消息。
     *
     * <p>两个平台的会话标识映射（这是本方法存在的全部理由）：
     * <ul>
     *   <li><b>企微</b>：单聊的会话是发信人（{@code chatType=single}，peer=direct:userid），
     *       群聊的会话是群（{@code chatType=group}，peer=group:chatid）—— 群里的会话键必须落在群上，
     *       否则同一个群里每个人的上下文会被拆成各自的会话；</li>
     *   <li><b>飞书</b>：本项目当前的飞书通道只拿到 {@code open_id}（单聊事件），
     *       所以一律按 direct 处理。</li>
     * </ul>
     * {@code guild / team / roles} 恒为空：这两个平台没有服务器与角色概念，
     * 表结构留了这些列是为了将来接 Discord/Slack 类平台时不必改 DDL。
     */
    private io.agentscope.harness.agent.gateway.channel.InboundMessage toOfficial(
            String channelId, CachedChannelRoute cached, InboundMessage message) {
        Peer peer;
        if (message.platform() == BotPlatform.WECOM && "group".equalsIgnoreCase(message.chatType())
                && message.chatId() != null && !message.chatId().isBlank()) {
            peer = Peer.group(message.chatId());
        } else {
            peer = Peer.direct(message.userId() == null ? "unknown" : message.userId());
        }
        return new io.agentscope.harness.agent.gateway.channel.InboundMessage(
                channelId,
                cached == null ? null : cached.accountId(),
                peer,
                message.userId(),
                null,
                null,
                null,
                java.util.Set.of(),
                // 路由不读消息内容：真正的 Msg 由回复渲染器按平台特性构造
                // （企微要带 msgtype 子对象、飞书要带卡片）。这里给空列表而不是把原始
                // content 硬塞成一条 Msg —— 后者会让"路由"这一层承担它不需要的理解成本。
                List.of(),
                null,
                null);
    }

    /** 供观测/日志：把库里的行直接翻成路由缓存条目。 */
    public static CachedChannelRoute toCacheEntry(ChannelEntity channel, List<ChannelBindingEntity> bindings) {
        return new CachedChannelRoute(
                channel.getChannelId(),
                channel.getChannelType(),
                accountIdOf(channel),
                channel.getReplyStyle(),
                ChannelConfigAssembler.assemble(channel, bindings));
    }

    /**
     * 从凭据里取"账号标识"，用于绑定规则的 account 层。
     *
     * <p>为什么按类型取不同字段：企微机器人用 {@code botId}、飞书应用用 {@code appId}，
     * 而它们都是"同一个平台上区分哪个机器人"的标识。取值失败返回 null（该层规则不匹配），
     * 不影响其它层 —— 这是"少一层匹配"而不是"报错"，因为账号层本来就是可选维度。
     */
    private static String accountIdOf(ChannelEntity channel) {
        Map<String, Object> properties = channel.getProperties();
        if (properties == null) {
            return null;
        }
        for (String key : List.of("botId", "appId")) {
            Object value = properties.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
