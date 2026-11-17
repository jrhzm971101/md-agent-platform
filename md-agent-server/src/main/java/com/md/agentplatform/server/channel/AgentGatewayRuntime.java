package com.md.agentplatform.server.channel;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.controlplane.store.entity.GatewayEntity;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 网关运行时 —— 用「库里的主 agent + 运行时已注册的 HARNESS agent」装配官方
 * {@link GatewayBootstrap}，并对外提供唯一的路由入口。
 *
 * <h2>一、它到底是什么</h2>
 * 官方 {@code GatewayBootstrap} 把三件事捆在一起：<b>agent 注册表</b>、
 * <b>channel 注册表</b>（{@link ChannelManager}）、<b>路由（含会话键）</b>。
 * 本类负责前两者的「装配」，路由则由 {@link ChannelRouteTable} 读取本类暴露的
 * {@link ChannelRouter} 来完成 —— 每次重新装配都会换一把新 router
 * （因为官方 router 的兜底 agent 是构造时确定的）。
 *
 * <h2>二、为什么只有 HARNESS agent 能纳管</h2>
 * 官方的 {@code Gateway#registerAgent} 只接受 {@link HarnessAgent}（它要管理会话、
 * 子 agent 与工作区）。REACT agent 走的是另一条链：由本项目的回复渲染器直接
 * {@code agentManager.stream(...)}。这不是本项目的取舍，是框架的形状 ——
 * 所以页面上「纳管 agent」的候选集就是"运行时里类型为 HARNESS 的那些"。
 *
 * <h2>三、不装配的三种情况都要说清楚，而不是抛异常</h2>
 * <ol>
 *   <li><b>没有已注册的 HARNESS agent</b>：官方 builder 会直接抛
 *       {@code At least one agent must be registered}。这里提前判掉，页面显示"未装配 + 原因"；</li>
 *   <li><b>没设主 agent</b>：官方 builder 会在 agents 里随便挑第一个当主 agent。
 *       那是"隐式决定路由兜底"，而兜底 agent 决定了"没命中任何规则的客户消息发给谁"，
 *       不该由 map 的遍历顺序决定 —— 所以这里要求显式设置；</li>
 *   <li><b>设了主 agent 但它不在已注册的 HARNESS 里</b>（改名了 / 停用了 / 还没发布）：
 *       这是配置与运行时不符，页面必须显式提示，而不是静默换成别人。</li>
 * </ol>
 *
 * <p>四种情况都不影响应用启动：网关没装配时，长连接通道照常收消息，
 * 路由退化为「渠道兜底 → 全局兜底」。但要注意：<b>没设主 agent</b> 时"全局兜底"
 * 并不是库里的某个名字，而是官方 {@link ChannelRouter} 构造器硬编码的常量 {@code "main"}，
 * 运行时里不可能存在它 —— 于是下游 {@code MessageRouter} 抛的是「Agent 不存在: main」。
 * 那句话从字面上看不出病因（既没提"没有规则命中"，也没提"没设主 agent"），
 * 所以 {@link #route} 落到这一层时会额外打一条能解释它的 warn（见 {@link #warnIfNothingCaught}）。
 */
@Component
public class AgentGatewayRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentGatewayRuntime.class);

    private final AgentManager agentManager;
    private final GatewaySettingsStore settingsStore;

    /** 装配结果；null = 未装配（原因在 {@link #snapshot} 里）。 */
    private volatile GatewayBootstrap bootstrap;

    /** 路由求值器；兜底 agent 随每次装配更新。未装配时兜底为 "main"（一个几乎必然不存在的主键）。 */
    private volatile ChannelRouter router = new ChannelRouter(null);

    private volatile ManagedGateway snapshot =
            new ManagedGateway(false, null, List.of(), null, "尚未装配", null);

    public AgentGatewayRuntime(AgentManager agentManager, GatewaySettingsStore settingsStore) {
        this.agentManager = agentManager;
        this.settingsStore = settingsStore;
    }

    /**
     * 启动时装配一次。
     *
     * <p>放在 {@code @PostConstruct} 而不是惰性首次使用：页面一打开就要看到网关状态，
     * 而"第一次调用时才装配"会把装配失败推迟到用户第一次发消息时，
     * 那时错误现场离原因已经很远了。依赖上 {@link AgentManager} 已经在
     * {@code @PostConstruct} 里完成装载（Spring 保证它先于本类初始化）。
     */
    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 重新装配：重读库里的主 agent 与运行时的 HARNESS agent，重建 bootstrap 与 router。
     *
     * <p><b>不动任何 channel</b> —— 通道的生命周期归 {@link ChannelRuntime}。
     * 于是"改主 agent"必须走 {@code ChannelRuntime.applyAll()}
     * （它会先停掉旧通道、再调本方法、再把通道注册进新的 ChannelManager），
     * 只调本方法会让正在运行的通道仍然挂在旧 bootstrap 上。
     */
    public synchronized ManagedGateway reload() {
        GatewayEntity settings = settingsStore.load();
        String mainAgentId = blankToNull(settings.getMainAgentId());

        Map<String, HarnessAgent> managed = new LinkedHashMap<>();
        for (Map.Entry<String, Agent> entry : agentManager.snapshot().entrySet()) {
            if (entry.getValue() instanceof HarnessAgent harnessAgent) {
                managed.put(entry.getKey(), harnessAgent);
            }
        }
        List<String> agentIds = new ArrayList<>(managed.keySet());
        agentIds.sort(String::compareTo);
        long now = System.currentTimeMillis();

        // 先把旧 bootstrap 摘掉：装配失败时不能留着半旧的实例继续服务，
        // 否则页面显示"未装配"、实际还在按旧配置路由。
        this.bootstrap = null;
        this.router = new ChannelRouter(mainAgentId);

        if (managed.isEmpty()) {
            return finish(false, mainAgentId, agentIds,
                    "没有已注册的 HARNESS agent（REACT agent 不参与网关），网关未装配", now);
        }
        if (mainAgentId == null) {
            return finish(false, null, agentIds,
                    "未设置主 agent：没有绑定与渠道兜底命中时，消息无处可去。请在下方选择并保存", now);
        }
        if (!managed.containsKey(mainAgentId)) {
            return finish(false, mainAgentId, agentIds,
                    "主 agent「" + mainAgentId + "」不在已注册的 HARNESS agent 里（可能已停用或改名），网关未装配", now);
        }

        try {
            GatewayBootstrap.Builder builder = GatewayBootstrap.builder();
            managed.forEach(builder::agent);
            // 不调 start()：本项目的通道由 ChannelRuntime 按库注册/启动，
            // 一次性 startAll() 会启动"库里不存在"的通道（builder 里注册的那些）。
            GatewayBootstrap built = builder.mainAgent(mainAgentId).build();
            this.bootstrap = built;
            log.info("网关已装配：mainAgent={} 纳管 {} 个 HARNESS agent: {}", mainAgentId, agentIds.size(), agentIds);
            return finish(true, mainAgentId, agentIds, null, now);
        } catch (RuntimeException e) {
            log.error("网关装配失败: {}", e.getMessage(), e);
            return finish(false, mainAgentId, agentIds, "装配失败: " + e.getMessage(), now);
        }
    }

    private ManagedGateway finish(boolean built, String mainAgentId, List<String> agentIds, String note, long now) {
        ManagedGateway result = new ManagedGateway(built, mainAgentId, agentIds, mainAgentId, note, built ? now : null);
        this.snapshot = result;
        return result;
    }

    /** 已装配的 gateway；未装配时为空（官方通道此时不能启动，见 OfficialChannelAdapter 注释）。 */
    public Optional<Gateway> gateway() {
        GatewayBootstrap current = bootstrap;
        return current == null ? Optional.empty() : Optional.of(current.gateway());
    }

    /** 当前 bootstrap 的 ChannelManager；未装配时为空。 */
    public Optional<ChannelManager> channelManager() {
        GatewayBootstrap current = bootstrap;
        return current == null ? Optional.empty() : Optional.of(current.channelManager());
    }

    public ManagedGateway snapshot() {
        return snapshot;
    }

    /**
     * 官方路由求值 —— 全平台唯一的"这条消息给谁"实现。
     *
     * <p>返回官方 {@link RouteResult} 而不是只回 agentId：调用方要的
     * {@code matchedBy} 与 {@code MsgContext} 都在里面，丢掉它们等于把排查线索扔掉。
     */
    public RouteDecision route(ChannelConfig config, io.agentscope.harness.agent.gateway.channel.InboundMessage message) {
        RouteResult result = router.resolveRoute(config, message);
        warnIfNothingCaught(config, result);
        return new RouteDecision(config.channelId(), result.agentId(), result.matchedBy(),
                result.context().canonicalKey());
    }

    /**
     * 「谁都没接住」时补一句人话。
     *
     * <h2>为什么这句话必须由这里说</h2>
     * 消息落到官方最后那一层（{@code matchedBy=global-default}）时，{@code agentId} 就是
     * {@link ChannelRouter} 构造时定的兜底值；而<b>没设主 agent</b> 时它是官方构造器里的
     * 字面量 {@code "main"}。运行时里没有这个名字，于是用户唯一能看到的现象是下游
     * {@code MessageRouter} 抛的「Agent 不存在: main」—— 它既没说是"没有规则命中"，
     * 也没说是"网关没设主 agent"，只能靠读源码反推。这条 warn 就是补上这个缺口。
     *
     * <p>只加日志、不改路由结果：这层"谁都没接住"是设计内的行为（比如回调式渠道可以只靠
     * 网关主 agent 兜底），它<b>不</b>该变成异常；只有当兜底名指向一个不存在的 agent 时才是配置问题。
     */
    private void warnIfNothingCaught(ChannelConfig config, RouteResult result) {
        if (!"global-default".equals(result.matchedBy())) {
            return;
        }
        ManagedGateway current = snapshot;
        String mainAgentId = current == null ? null : current.mainAgentId();
        if (mainAgentId != null && !mainAgentId.isBlank()) {
            // 设了主 agent 时落到它就是设计内的兜底，没什么可说的
            return;
        }
        String channelDefault = config.defaultAgentId();
        log.warn("[Route] 渠道 {} 没命中任何绑定规则，渠道兜底 agent={}，网关主 agent 未设置"
                        + " → 已回落到官方构造器的常量兜底「{}」，运行时里不存在这个 agent，"
                        + "下游必然报「Agent 不存在: {}」。修法二选一：给渠道填「兜底 Agent」并发布，"
                        + "或在 Gateway 页设置主 agent。",
                config.channelId(),
                channelDefault == null || channelDefault.isBlank() ? "（空）" : channelDefault,
                result.agentId(), result.agentId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
