package com.md.agentplatform.server.config;

import com.md.agentplatform.server.trace.store.AsyncTraceStore;
import com.md.agentplatform.server.trace.store.MyBatisTraceStore;
import com.md.agentplatform.server.trace.store.TraceStore;
import com.md.agentplatform.server.trace.store.TraceRetentionJob;
import com.md.agentplatform.server.trace.store.mapper.AgentRunEventMapper;
import com.md.agentplatform.server.trace.store.mapper.AgentRunMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 运行链路归档的装配。
 *
 * <h2>为什么这两个 Bean 不直接标 {@code @Component}</h2>
 * 它们有<b>依赖顺序</b>：异步写入器必须包住 MyBatis 实现，而 MyBatis 实现需要 mappers
 * 与 SqlSessionFactory（后者由 MyBatis-Plus 的自动配置在更晚的时机创建）。
 * 用 {@code @Component} + {@code @Autowired} 也能跑，但"谁包谁"就藏进了注解里；
 * 把装配写在一个显式的 {@code @Bean} 方法里，{@code new} 出来的层次一眼可见。
 *
 * <p>另一个好处是这里挂了开关：{@code agent-platform.trace-archive-enabled=false} 时
 * 干脆不创建 {@link TraceStore} Bean，{@code AgentTraceRegistry} 也就注入不到存储，
 * 自动退化为纯内存模式（Step 1 的行为）。用 {@code @ConditionalOnProperty} 表达
 * "有条件地提供能力"，比在 Registry 里写 if 判断要清楚得多。
 */
@Configuration
// 归档关闭时整个配置类都不生效：既没有 TraceStore（Registry 自动退化回纯内存），
// 也不会注册 @EnableScheduling 带来的定时任务基础设施 —— 「关掉归档」应该是真的什么都不做。
@ConditionalOnProperty(name = TraceStoreConfig.ENABLED_KEY, havingValue = "true", matchIfMissing = true)
// 保留期清理需要一个调度器。项目里已有的调度是 xxl-job（见 server/schedule/DailyReportSendWxJob），
// 那需要有人在 Admin 控制台配一个任务才会跑；观测数据的清理是"部署即应生效"的运维动作，
// 不适合依赖一个外部控制台的配置，所以这里用 Spring 自带的 @Scheduled。
// 若将来统一到 xxl-job，删掉本注解、把 TraceRetentionJob 的方法改成 @XxlJob 即可，逻辑无需改动。
@EnableScheduling
public class TraceStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(TraceStoreConfig.class);

    /** 归档开关的配置键，两处（Bean 方法与日志）共用，避免写错一处后静默失效。 */
    static final String ENABLED_KEY = "agent-platform.trace-archive-enabled";

    /**
     * 归档存储：MyBatis 实现外面套一层异步写入器。
     *
     * <p>读走 {@code delegate} 同步返回，写先入队由后台单线程批量刷盘 —— 这条分工写在
     * {@link AsyncTraceStore} 的类注释里，这里不再重复。
     */
    @Bean
    public TraceStore traceStore(AgentRunMapper runMapper, AgentRunEventMapper eventMapper,
                                 SqlSessionFactory sqlSessionFactory,
                                 AgentPlatformProperties properties) {
        MyBatisTraceStore mybatisStore = new MyBatisTraceStore(runMapper, eventMapper, sqlSessionFactory);
        AsyncTraceStore store = new AsyncTraceStore(mybatisStore,
                properties.getTraceArchiveQueueCapacity(), properties.getTraceArchiveBatchSize());
        log.info("运行链路归档已启用：队列容量={} 批量上限={} 保留天数={}（<=0 表示永久保留）",
                properties.getTraceArchiveQueueCapacity(), properties.getTraceArchiveBatchSize(),
                properties.getTraceRetentionDays());
        return store;
    }

    /** 保留期清理任务。放在这里而不是让它自己 {@code @Component}，是为了跟着本配置类一起被开关控制。 */
    @Bean
    public TraceRetentionJob traceRetentionJob(TraceStore traceStore, AgentPlatformProperties properties) {
        return new TraceRetentionJob(traceStore, properties.getTraceRetentionDays());
    }
}
