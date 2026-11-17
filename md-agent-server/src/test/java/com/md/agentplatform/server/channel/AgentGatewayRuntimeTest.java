package com.md.agentplatform.server.channel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.controlplane.store.entity.GatewayEntity;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由落到「谁都没接住」那一层时，日志必须能自己解释自己。
 *
 * <h2>为什么专门测一条日志</h2>
 * 现象是：企微发一条消息，回复流已经推了一半，然后 ERROR「Agent 不存在: main」。
 * 这句错的每一个字都是对的、也每一个字都没用 —— 它既没说"没有绑定规则命中"，
 * 也没说"网关没设主 agent"，只留下一个运行时里根本不存在的名字。
 * 现场排查时唯一能走的路是回去读 {@code ChannelRouter} 的构造器，看那个 {@code "main"}
 * 是哪儿来的。
 *
 * <p>所以这里钉两件相反的事：
 * <ol>
 *   <li>兜底名指向不存在的 agent（= 没设主 agent）时，<b>必须</b>留下一条把病因说全的 warn；</li>
 *   <li>兜底是真实存在的主 agent、或渠道兜底 agent 接住了时，<b>不该</b>刷 warn ——
 *       否则这条日志会退化成噪声，第 1 条也就没人看了。</li>
 * </ol>
 */
class AgentGatewayRuntimeTest {

    private static final String CHANNEL = "test_channel";

    private Logger routeLogger;
    private ListAppender<ILoggingEvent> appender;

    /*
     * 挂到本类的 logger 上而不是 root：只收本类打的日志，测试之间不会互相看见。
     */
    @BeforeEach
    void attachAppender() {
        routeLogger = (Logger) LoggerFactory.getLogger(AgentGatewayRuntime.class);
        appender = new ListAppender<>();
        appender.start();
        routeLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        routeLogger.detachAppender(appender);
    }

    /**
     * 没设主 agent：兜底名就是官方构造器的常量，warn 必须把三件事说全
     * （哪个渠道、渠道兜底是空的、兜底名指向的不是真实 agent）。
     */
    @Test
    void warnsWithTheFullReasonWhenNoMainAgentIsConfigured() {
        AgentGatewayRuntime runtime = runtimeWithMainAgent(null);

        RouteDecision decision =
                runtime.route(ChannelConfig.builder(CHANNEL).build(), dm("u1"));

        // 先钉住现象：命中的是最后那一层，agentId 是官方常量（运行时里必然不存在）
        assertThat(decision.matchedBy()).isEqualTo("global-default");
        assertThat(decision.agentId()).isEqualTo("main");
        // 再钉住解释：一句里同时给出渠道、渠道兜底为空、以及"这是个不存在的名字"
        assertThat(warnings())
                .singleElement()
                .asString()
                .contains(CHANNEL)
                .contains("渠道兜底 agent=（空）")
                .contains("网关主 agent 未设置")
                .contains("main");
    }

    /** 设了主 agent：落到它就是设计内的兜底，一条 warn 都不该有。 */
    @Test
    void staysQuietWhenTheGlobalFallbackIsARealMainAgent() {
        AgentGatewayRuntime runtime = runtimeWithMainAgent("smoke_harness");

        RouteDecision decision =
                runtime.route(ChannelConfig.builder(CHANNEL).build(), dm("u1"));

        assertThat(decision.matchedBy()).isEqualTo("global-default");
        assertThat(decision.agentId()).isEqualTo("smoke_harness");
        assertThat(warnings()).isEmpty();
    }

    /** 渠道兜底 agent 接住了：命中层级是 channel-default，同样不该有 warn。 */
    @Test
    void staysQuietWhenTheChannelDefaultCatchesIt() {
        AgentGatewayRuntime runtime = runtimeWithMainAgent(null);

        RouteDecision decision =
                runtime.route(ChannelConfig.builder(CHANNEL).defaultAgentId("test_agent").build(), dm("u1"));

        assertThat(decision.matchedBy()).isEqualTo("channel-default");
        assertThat(decision.agentId()).isEqualTo("test_agent");
        assertThat(warnings()).isEmpty();
    }

    private static InboundMessage dm(String userId) {
        return InboundMessage.dm(CHANNEL, userId, List.of());
    }

    /**
     * 造一个已"装配"过的运行时。
     *
     * <p>必须走 {@code reload()} 而不是直接 new：兜底名是在装配时由
     * {@code new ChannelRouter(mainAgentId)} 定下来的，跳过装配就测不到这条路径。
     * 桩 agent 注册表是空的（本类只关心"主 agent 设没设"），于是 reload() 会走到
     * "没有已注册的 HARNESS agent → 网关未装配"，而 router 的兜底名照旧生效 ——
     * 这正是线上那种"网关没装配、长连接通道照常收消息"的形态。
     */
    private static AgentGatewayRuntime runtimeWithMainAgent(String mainAgentId) {
        AgentGatewayRuntime runtime =
                new AgentGatewayRuntime(stubAgentManager(), stubSettings(mainAgentId));
        runtime.reload();
        return runtime;
    }

    private static AgentManager stubAgentManager() {
        return new AgentManager() {
            @Override
            public Map<String, Agent> snapshot() {
                return Map.of();
            }
        };
    }

    private static GatewaySettingsStore stubSettings(String mainAgentId) {
        GatewayEntity settings = new GatewayEntity();
        settings.setMainAgentId(mainAgentId);
        return new GatewaySettingsStore(null) {
            @Override
            public GatewayEntity load() {
                return settings;
            }
        };
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
