package com.md.agentplatform.server.messaging;

import com.md.agentplatform.server.channel.ChannelRouteTable;
import com.md.agentplatform.server.channel.RouteDecision;
import com.md.agentplatform.server.messaging.render.ReplyRenderer;
import com.md.agentplatform.server.messaging.render.ReplyRendererRegistry;
import com.md.agentplatform.server.messaging.render.ReplyStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 交汇点 —— "消息从哪个通道来" 与 "谁来回答、怎么回复" 在此汇合。
 *
 * <h2>一、路由结论从哪来（本次改造的核心变化）</h2>
 * 改造前：{@code defaultAgent} 与 {@code replyStyle} 都来自 {@code application.yml} 的
 * {@code messaging.bots[]}，本类自己遍历配置找一条匹配的。
 * 改造后：两者都来自 {@link ChannelRouteTable} —— 一份由 {@code ChannelRuntime}
 * 在每次装配/重载后整体替换的只读快照，内容源自库里的 {@code md_channel} 与
 * {@code md_channel_binding}。
 *
 * <p>命中的 agent 由<b>官方 {@code ChannelRouter}</code></b> 算出来（七层优先级：
 * explicit &gt; peer &gt; parentPeer &gt; guild+roles &gt; guild &gt; team &gt; account &gt; channel &gt;
 * channel-default &gt; global-default），本类不自己写匹配逻辑 ——
 * 自己写一份必然与官方漂移，而漂移的表现是"绑定配了却不生效"，极难定位。
 *
 * <h2>二、为什么这里只取结论、不做降级猜测</h2>
 * 消息已经到了，此时能做的只有两件事：交给某个 agent，或者明确报错。
 * 所以：
 * <ul>
 *   <li>渠道不在路由表里（库里没有/刚删）→ 官方 router 仍给出结论（落到全局兜底），
 *       日志按 warn 记录，消息照常处理；</li>
 *   <li>结论里的 agent 名在运行时不存在 → <b>不</b>替换成别的 agent，
 *       让下游明确报 "Agent 不存在: X"，那行错误指向的正是"去渠道页/Gateway 页把绑定改对"。</li>
 * </ul>
 *
 * <h2>三、线程模型没变</h2>
 * {@code render} 内部的消息构建（如企微图片下载）是阻塞的，所以仍然交给
 * {@code agentExecutor}；{@code render} 自身 subscribe 后立即返回，不占住线程等整条流。
 */
@Component
public class MessageRouter implements InboundMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    /** 路由结论为空时的最后兜底；正常路径不会走到（见类注释第二节）。 */
    private static final String DEFAULT_AGENT = "rag_agent";

    private final ChannelRouteTable routeTable;
    private final ReplyRendererRegistry rendererRegistry;
    private final Executor executor;

    /**
     * 构造器注入（不再是字段 {@code @Autowired}）。
     *
     * <p>这里能收敛成构造器注入，是因为依赖环已经解掉：改造前
     * {@code BotInstanceManager → MessageRouter → BotInstanceManager} 这个环
     * 靠字段注入"先建好再回填"才成立；现在 {@link BotInstanceManager} 是纯注册表
     * （不注入本类），而路由结论来自 {@link ChannelRouteTable}，
     * 依赖图是单向的：{@code MessageRouter → ChannelRouteTable → AgentGatewayRuntime}。
     * 单向图能写构造器注入，意味着"依赖不全就建不出来"这件事由编译器保证。
     */
    public MessageRouter(ChannelRouteTable routeTable,
                         ReplyRendererRegistry rendererRegistry,
                         @Qualifier("agentExecutor") Executor executor) {
        this.routeTable = routeTable;
        this.rendererRegistry = rendererRegistry;
        this.executor = executor;
    }

    @Override
    public void handle(InboundMessage msg) {
        String channelId = msg.botName();
        ChannelRouteTable.CachedChannelRoute cached = routeTable.find(channelId).orElse(null);
        if (cached == null) {
            // 走到这里说明"消息来了但库里没有这个渠道"：可能是刚被删除，或渠道行改了名。
            // 不丢消息 —— 下面仍然能算出全局兜底 agent，且这行日志是唯一的线索。
            log.warn("[Router] 渠道 {} 不在路由表里（库里没有或刚删），按全局兜底处理", channelId);
        }

        RouteDecision decision = routeTable.route(channelId, msg);
        String agentName = (decision.agentId() == null || decision.agentId().isBlank())
                ? DEFAULT_AGENT : decision.agentId();
        ReplyStyle style = resolveStyle(msg, cached);

        log.info("[Router] 入站 channel={} platform={} user={} chatType={} → agent={} matchedBy={} style={} sessionKey={}",
                channelId, msg.platform(), msg.userId(), msg.chatType(),
                agentName, decision.matchedBy(), style, decision.sessionKey());

        try {
            ReplyRenderer renderer = rendererRegistry.get(style);
            executor.execute(() -> {
                try {
                    renderer.render(msg, agentName);
                } catch (Exception e) {
                    log.error("[Router] 处理异常 channel={} platform={}: {}",
                            channelId, msg.platform(), e.getMessage(), e);
                }
            });
        } catch (RuntimeException e) {
            // 风格取自库里的自由文本列，理论上可能出现"渲染器没注册"这种情况；
            // 在入口兜住并留下可执行的错误文案，好过让它从 WS 线程里抛出去
            // （那时的现象是"线程死了、消息静默丢失"）。
            log.error("[Router] 无法渲染 channel={} style={}：{}（请检查渠道的回复风格配置）",
                    channelId, style, e.getMessage(), e);
        }
    }

    /**
     * 解析回复风格：渠道行上配了就用它，没配（或值不认识）就按平台推断 —— 与改造前一致。
     *
     * <p>渠道行不存在时（{@code cached == null}）等价于"没配"。
     */
    private ReplyStyle resolveStyle(InboundMessage msg, ChannelRouteTable.CachedChannelRoute cached) {
        String raw = cached == null ? null : cached.replyStyle();
        ReplyStyle parsed = ReplyStyle.parse(raw);
        if (raw != null && !raw.isBlank() && parsed == null) {
            log.warn("[Router] 渠道 {} 的回复风格无法识别: {}（按平台默认处理）", msg.botName(), raw);
        }
        return ReplyStyle.resolve(msg.platform(), parsed);
    }
}
