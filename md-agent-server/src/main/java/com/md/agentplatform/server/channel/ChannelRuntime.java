package com.md.agentplatform.server.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.controlplane.store.ChannelDefinitionBundle;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;
import com.md.agentplatform.server.messaging.BotInstanceManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道运行时 —— 把「库里的渠道」变成「活着的通道」，并对外报告每个渠道的状态。
 *
 * <h2>一、数据流（这是整个改造的核心）</h2>
 * <pre>
 *   md_channel / md_channel_binding（唯一真相，页面维护）
 *        │  applyAll() 读全量
 *        ▼
 *   ChannelValidator 校验（类型已注册 / 凭据齐全 / 网关可用）
 *        │
 *        ├─► ChannelRouteTable 路由快照（消息进来时用它算 agentId）
 *        │       全量重载写 replaceAll(...)，发布/单渠道重载写 upsert(...)，删除写 remove(...)
 *        │
 *        └─► ChannelTypeRegistry.create(...) 造通道
 *                 │
 *                 ├─ BOT 族的适配器 → BotInstanceManager 注册（供发送侧取连接）
 *                 └─ OFFICIAL 族的适配器 → bindGateway + 官方 ChannelManager 注册
 * </pre>
 *
 * <h2>二、为什么不每次重载都全部重启</h2>
 * 用内容指纹比对（{@code channelId → 序列化后的整束内容}）决定"这个渠道要不要动"：
 * 改一个渠道的绑定，不应该把企微的长连接掐断重连（企微订阅是异步握手，
 * 重启意味着几十秒内收不到消息）。指纹没变且实例还活着就跳过 ——
 * 与官方 {@code SchedulerChannelRuntime} 的做法一致。
 *
 * <h2>三、失败是"记在状态里"，不是"抛给调用方"</h2>
 * 单个渠道装配失败（凭据填错、网关没装配、连不上）不影响其它渠道，也不回滚数据库：
 * 库里的状态是自洽的（用户确实这么填的），失败的是"这次没换成新实例"。
 * 于是错误被记进 {@link #errors}，由 {@link #statuses()} 带到页面那一行上。
 * 反过来说，如果在这里抛异常，一次手滑就会让"其它渠道也跟着不工作"，
 * 而故障点却在一个跟它们无关的渠道上。
 *
 * <h2>四、不做定时轮询</h2>
 * 官方那套是「控制面 + 调度面两个进程」，只能靠定时拉配置。本项目在一个进程里，
 * 于是可以在「发布渠道」「保存网关」「点重载」这些明确动作上触发 ——
 * 变更即刻生效，也不必为"轮询间隔内改了两次"设计合并策略。
 * 代价是：绕过控制面直接改库的人需要自己点一次重载（页面上有按钮）。
 */
@Component
public class ChannelRuntime {

    private static final Logger log = LoggerFactory.getLogger(ChannelRuntime.class);

    private final ControlPlaneStore store;
    private final ChannelTypeRegistry registry;
    private final ChannelValidator validator;
    private final AgentGatewayRuntime gatewayRuntime;
    private final BotInstanceManager botRegistry;
    private final ChannelRouteTable routeTable;

    /** 活着的通道，key = channelId。 */
    private final Map<String, LiveChannel> live = new ConcurrentHashMap<>();

    /** 最近一次装配/启动失败的原因，key = channelId；成功时移除。 */
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    /** 最近一次成功装配的时刻，key = channelId。 */
    private final Map<String, Long> appliedAt = new ConcurrentHashMap<>();

    /**
     * 最近一次装配用的 Gateway 实例（可能为 null = 网关未装配）。
     *
     * <p>只用来做"实例是否换过"的比对：官方 {@code Channel.init(gateway)} 只认第一次，
     * 所以网关一旦重建，活着的官方通道就再也换不到新网关上，只能先停后起（见 {@link #applyAll()}）。
     * 用 Object 而不是 Gateway 类型，是因为这里的语义是"引用标识"，不需要它的任何方法。
     */
    private volatile Object boundGateway;

    /**
     * 专用于指纹比对的序列化器，与 {@code MyBatisControlPlaneStore} 里那份快照序列化器同理：
     * 容器的 ObjectMapper 会被别人改配置，而指纹只要求"同一进程内、同一内容、同一结果"，
     * 不该受那些偏好影响（否则会出现"没人改内容、渠道却每次重载都在重启"）。
     */
    private final ObjectMapper fingerprintMapper = new ObjectMapper();

    public ChannelRuntime(ControlPlaneStore store, ChannelTypeRegistry registry, ChannelValidator validator,
                          AgentGatewayRuntime gatewayRuntime, BotInstanceManager botRegistry,
                          ChannelRouteTable routeTable) {
        this.store = store;
        this.registry = registry;
        this.validator = validator;
        this.gatewayRuntime = gatewayRuntime;
        this.botRegistry = botRegistry;
        this.routeTable = routeTable;
    }

    private record LiveChannel(ChannelAdapter adapter, String fingerprint) {}

    /**
     * 启动时按库装配一次。
     *
     * <p>为什么是 {@code @PostConstruct} 而不是等第一次用到：与改造前的行为保持一致 ——
     * 之前 Bot 就是在启动时连接的，只是配置来自 yml。放在这里也保证
     * "页面打开时看到的状态"与"实际在跑的通道"一致。
     *
     * <p>企微订阅是异步握手，启动瞬间必然 not alive，所以随后安排一次延迟健康快照
     * （沿用 {@code BotInstanceManager} 原来那段逻辑）。
     */
    @PostConstruct
    public void startFromDatabase() {
        try {
            applyAll();
        } catch (RuntimeException e) {
            // 装配整体失败（例如库连不上）不能让应用起不来：应用本身还有页面、链路等能力，
            // 而"渠道不可用"应该表现为页面上的红字 + 日志，而不是进程启动失败。
            log.error("[Channel] 启动装配失败，渠道将不可用；修好后可在页面点「全量重载」", e);
        }
        botRegistry.scheduleHealthSnapshot();
    }

    @PreDestroy
    public void shutdown() {
        for (String channelId : new ArrayList<>(live.keySet())) {
            stopNow(channelId);
        }
    }

    /**
     * 全量装配：重读库 → 重建网关 → 重建路由表 → 按需重启通道。
     *
     * @return 装配后的网关状态（页面要顺带展示它）
     */
    public synchronized ManagedGateway applyAll() {
        ManagedGateway gateway = gatewayRuntime.reload();

        // 网关换了实例就必须重建官方通道：官方 Channel 的 init(gateway) 只认第一次
        //（WeComChannel.init 里是 `if (this.gateway == null)`），而通道是靠持有的 gateway
        // 去查 agent 注册表的 —— 不重建就会留着"指向旧注册表"的活实例，
        // 表现为改了主 agent / 停用了某个 agent 之后，回调式通道仍在按旧注册表路由。
        // 回调式通道的 stop/start 只是注册表增删（没有网络连接），重建的代价可以忽略。
        Object currentGateway = gatewayRuntime.gateway().orElse(null);
        if (currentGateway != boundGateway) {
            for (Map.Entry<String, LiveChannel> entry : new ArrayList<>(live.entrySet())) {
                if (entry.getValue().adapter() instanceof OfficialChannelAdapter) {
                    log.info("[Channel] {} 需要重新绑定网关（网关已重建），先停后起", entry.getKey());
                    stopNow(entry.getKey());
                }
            }
            boundGateway = currentGateway;
        }

        List<ChannelDefinitionBundle> bundles = store.loadAllChannelBundles();
        // 路由表覆盖**全部**渠道（含已停用的）：路由是"知识"，与"该不该在收消息"是两件事。
        // 停用某个渠道后，若仍有残留在途的消息进来（长连接刚断、回调重放），
        // 有路由就能算出该给谁、并在链路里显示命中了哪层；没有路由就只能掉进全局兜底。
        routeTable.replaceAll(bundles.stream()
                .map(bundle -> ChannelRouteTable.toCacheEntry(bundle.channel(), bundle.bindings()))
                .toList());

        List<String> enabled = new ArrayList<>();
        for (ChannelDefinitionBundle bundle : bundles) {
            if (isEnabled(bundle.channel())) {
                enabled.add(bundle.channel().getChannelId());
            }
        }
        // 先停：库里删掉的、被停用的、以及类型改了导致要重建的旧实例。
        for (String channelId : new ArrayList<>(live.keySet())) {
            if (!enabled.contains(channelId)) {
                log.info("[Channel] {} 已不在启用列表（删除或停用），停止其运行实例", channelId);
                stopNow(channelId);
            }
        }
        for (ChannelDefinitionBundle bundle : bundles) {
            if (isEnabled(bundle.channel())) {
                applyOne(bundle);
            } else {
                log.info("[Channel] {} 已停用，跳过装配", bundle.channel().getChannelId());
            }
        }
        log.info("[Channel] 全量装配完成：库中 {} 个渠道，启用 {} 个，运行中 {} 个，失败 {} 个",
                bundles.size(), enabled.size(), live.size(), errors.size());
        return gateway;
    }

    /**
     * 重新应用一个渠道（发布后热生效、页面上点「重载」）。
     *
     * <p>库里的行不存在时<b>停掉同名实例并记一条错误</b>，而不是静默什么都不做 ——
     * 后者会出现"页面上已经删了、通道还在收消息"这种最难解释的状态。
     *
     * <h2>它必须同时刷路由表（2026-09-29 修）</h2>
     * 路由表原先<b>只有 {@link #applyAll()} 会刷</b>，而"发布渠道"走的正是本方法。
     * 于是出现过一种很难解释的状态：<b>长连接是活的，路由表里却没有这个渠道</b>。
     * 现象是企微消息进来后报 {@code Agent 不存在: main} ——
     * 因为 {@link ChannelRouteTable} 查不到渠道时会退回一个<b>空配置</b>
     * （绑定规则为空、{@code defaultAgentId} 为 null），再叠加"网关没设主 agent"，
     * 就一路掉到官方 {@code ChannelRouter} 构造时那个字面量兜底。
     *
     * <p>触发条件是「服务启动时库里还没有这个渠道」：启动期建出的路由表是空的，
     * 之后新建并发布，{@code applyOne} 把连接拉起来了，路由表却还是启动时那份空的。
     * 所以单渠道这条路必须和全量重载做同一件事，见下面的 upsert / remove。
     */
    public synchronized void apply(String channelId) {
        ChannelDefinitionBundle bundle = store.loadChannelBundle(channelId);
        if (bundle == null) {
            stopNow(channelId);
            // 路由表里也要摘掉：applyAll 的 replaceAll 是"以库为准"，
            // 单渠道这条路必须做到同一件事，否则会留下"库里已删、路由表还留着"的僵尸条目。
            routeTable.remove(channelId);
            errors.put(channelId, "渠道不存在（可能已被删除）");
            return;
        }
        // 先刷路由、再动实例。顺序不能反：路由是"知识"，与"该不该在收消息"无关，
        // 所以停用的渠道也要留在路由表里（与 applyAll 覆盖"全部渠道（含已停用的）"同一口径），
        // 这一句因此刻意放在启用判断之前。
        routeTable.upsert(ChannelRouteTable.toCacheEntry(bundle.channel(), bundle.bindings()));
        if (!isEnabled(bundle.channel())) {
            stopNow(channelId);
            // 停用不是错误，清掉历史错误避免页面一直显示旧失败
            errors.remove(channelId);
            return;
        }
        applyOne(bundle);
    }

    /**
     * 渠道被<b>删除</b>后的收尾：停实例 + 从路由表摘掉。
     *
     * <p>为什么不直接复用 {@link #apply(String)}：那条路在渠道不存在时会记一条
     * "渠道不存在（可能已被删除）"的错误，而"用户主动删除"是正常操作，不该在状态里留红字。
     * 两者的区别只是"记不记错误"，所以这里单独一个方法，顺便让调用点的意图在名字上就看得见。
     *
     * <p>删掉路由条目这件事不能省：留着会让后续残留在途的消息按一份已经删掉的配置去路由，
     * 而对应的通道已经停了 —— 现象是"消息静默无回应"，比报错更难查。
     */
    public synchronized void forget(String channelId) {
        stopNow(channelId);
        routeTable.remove(channelId);
        errors.remove(channelId);
    }

    /**
     * 装配一个渠道。
     *
     * <p>顺序是固定的，每一步失败都有自己的错误文案：
     * <ol>
     *   <li>校验（类型/凭据/网关）→ 失败则停掉旧实例并记原因；</li>
     *   <li>指纹比对 → 内容没变且实例活着就直接返回（不重启长连接）；</li>
     *   <li>停旧实例 → 造新实例（官方族要先 bindGateway）→ 启动；</li>
     *   <li>BOT 族还要向 {@code BotInstanceManager} 注册，让发送侧能按名字取到连接。</li>
     * </ol>
     */
    private void applyOne(ChannelDefinitionBundle bundle) {
        ChannelEntity channel = bundle.channel();
        String channelId = channel.getChannelId();

        List<String> problems = validator.problems(bundle);
        if (!problems.isEmpty()) {
            fail(channelId, "配置不合法: " + String.join("；", problems));
            return;
        }
        ChannelTypeDescriptor descriptor = registry.find(channel.getChannelType())
                .map(ChannelAdapterFactory::descriptor).orElse(null);
        String blocked = validator.gatewayBlockReason(descriptor);
        if (blocked != null) {
            fail(channelId, blocked);
            return;
        }

        String fingerprint = fingerprintOf(bundle);
        LiveChannel existing = live.get(channelId);
        if (existing != null && existing.fingerprint().equals(fingerprint) && existing.adapter().isAlive()) {
            log.debug("[Channel] {} 内容未变且运行正常，跳过重启", channelId);
            return;
        }
        if (existing != null) {
            // 内容变了（或实例已不健康）：先停再起。这里"先停"是必须的 ——
            // 同一个渠道名不能同时有两个活实例（企微同一 botId 双连接会被服务端踢掉）。
            stopNow(channelId);
        }

        ChannelConfig routing = ChannelConfigAssembler.assemble(channel, bundle.bindings());
        ChannelAdapter adapter;
        try {
            adapter = registry.create(channelId, channel.getChannelType(), routing, channel.getProperties());
        } catch (RuntimeException e) {
            fail(channelId, "装配失败: " + rootMessage(e));
            return;
        }
        if (adapter instanceof OfficialChannelAdapter official) {
            Gateway gateway = gatewayRuntime.gateway().orElse(null);
            if (gateway == null) {
                // 走到这里说明上面的 gatewayBlockReason 应该已经拦住了；留这层是为了防止
                // 将来有人改描述（把 requiresGateway 改成 false）却忘了这里的前置条件。
                fail(channelId, "官方通道需要网关，但当前网关未装配");
                return;
            }
            official.bindGateway(gateway);
        }
        try {
            adapter.start();
        } catch (RuntimeException e) {
            fail(channelId, "启动失败: " + rootMessage(e));
            return;
        }
        live.put(channelId, new LiveChannel(adapter, fingerprint));
        if (adapter instanceof BotChannelAdapter bot) {
            botRegistry.register(bot.bot());
        }
        errors.remove(channelId);
        appliedAt.put(channelId, System.currentTimeMillis());
        log.info("[Channel] {} ({}) 已启动", channelId, channel.getChannelType());
    }

    /**
     * 停掉一个实例（幂等）。
     *
     * <p>{@code stop()} 本身就允许抛（关闭连接的各种超时/中断），所以这里兜住异常只打日志：
     * 停不掉的连接最终会随进程退出而释放，而"因为停不掉所以不更新内部状态"
     * 会让页面永远显示"运行中"，那比泄漏一个 socket 更糟。
     */
    public synchronized void stopNow(String channelId) {
        LiveChannel removed = live.remove(channelId);
        if (removed == null) {
            return;
        }
        try {
            removed.adapter().stop();
        } catch (Exception e) {
            log.warn("[Channel] {} 停止时异常（忽略，实例已从运行时摘除）: {}", channelId, e.getMessage());
        }
        if (removed.adapter() instanceof BotChannelAdapter bot) {
            botRegistry.unregister(bot.bot().getName());
        }
        appliedAt.remove(channelId);
        log.info("[Channel] {} 已停止", channelId);
    }

    /**
     * 页面用的状态清单：库里有哪些渠道 + 每个现在跑成什么样。
     *
     * <p>以<b>库</b>为骨架而不是以运行时为骨架：这样"库里配了但没启动起来（或没启用）"
     * 也会出现在列表里 —— 那正是用户最需要看到的一类状态。
     */
    public List<ChannelRuntimeStatus> statuses() {
        List<ChannelRuntimeStatus> result = new ArrayList<>();
        for (ChannelDefinitionBundle bundle : store.loadAllChannelBundles()) {
            result.add(statusOf(bundle));
        }
        return result;
    }

    /**
     * 单个渠道的运行时状态。
     *
     * <p>公开是为了让控制器在「详情 / 发布 / 重载」之后返回<b>同一个形状</b>：
     * 让调用方自己拼一遍，就等于维护两份口径，而两份口径迟早会不一致
     * （典型现象：列表页显示"运行中"、详情页显示"未启动"）。
     */
    public ChannelRuntimeStatus statusOf(ChannelDefinitionBundle bundle) {
        ChannelEntity channel = bundle.channel();
        String channelId = channel.getChannelId();
        ChannelTypeDescriptor descriptor = registry.find(channel.getChannelType())
                .map(ChannelAdapterFactory::descriptor).orElse(null);
        LiveChannel liveChannel = live.get(channelId);
        return new ChannelRuntimeStatus(
                channelId,
                channel.getChannelType(),
                descriptor == null ? null : descriptor.label(),
                descriptor == null ? null : descriptor.kind(),
                isEnabled(channel),
                channel.getStatus(),
                channel.getPublishedVersion(),
                liveChannel != null,
                liveChannel != null && liveChannel.adapter().isAlive(),
                errors.get(channelId),
                bundle.bindings().size(),
                appliedAt.get(channelId));
    }

    /** 当前运行中的渠道数（健康检查用）。 */
    public int runningCount() {
        return live.size();
    }

    private void fail(String channelId, String reason) {
        log.warn("[Channel] {} 未启动: {}", channelId, reason);
        errors.put(channelId, reason);
        stopNow(channelId);
    }

    private boolean isEnabled(ChannelEntity channel) {
        // 三态列不可为 null（库里 NOT NULL，保存时也会补默认），但历史数据可能没有值；
        // 缺值时按"启用"处理而不是"停用"：静默停掉一个用户以为在用的渠道，
        // 比多起一个实例更难发现。
        return channel.getEnabled() == null || Boolean.TRUE.equals(channel.getEnabled());
    }

    private String fingerprintOf(ChannelDefinitionBundle bundle) {
        try {
            return fingerprintMapper.writeValueAsString(bundle);
        } catch (Exception e) {
            // 序列化失败只影响"要不要重启"的判断，退化成"每次都重启"是安全的
            return String.valueOf(bundle.hashCode());
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    /** 供控制器在响应里附带一句"当前网关是否就绪"，避免页面再查一次。 */
    public ManagedGateway gatewaySnapshot() {
        return gatewayRuntime.snapshot();
    }

}
