package com.md.agentplatform.server.schedule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.config.AgentPlatformProperties;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import io.agentscope.core.ReActAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 定时任务取 prompt 的规则：<b>只认 md_prompt 一个来源</b>，取不到就报错并且这一次不发。
 *
 * <h2>为什么要为"读一行数据"写测试</h2>
 * 改造前它有两条来源（Nacos AI Prompt 中心 → workspace 下的本地文件），两条都在"机器侧"：
 * 页面上看不到、改一次要登服务器。所以最要命的不是"读不到"，而是它<b>读到了别的内容</b> ——
 * 定时任务实际使用的 prompt 与页面上维护的那份不一致，而且没有任何地方会把这个偏差报出来
 * （现象只会是"日报的口径怎么又不对了"）。这次把来源收敛成唯一一个，下面几条断言就是钉住它。
 *
 * <h2>为什么测 loadPrompt() 而不是 run()</h2>
 * {@code run()} 要先真的起一个 agent 才算得动，代价远大于这几行判断；
 * 而这里要钉的恰好是 {@code run()} 之前的那一步（读不读得到 prompt）。
 * {@code run()} 侧只补一条"agent 不存在就别往下走"——那是不该有的 NPE。
 */
class DailyReportSendWxJobTest {

    /** 与 {@code AgentPlatformProperties.dailyReportPromptKey} 的默认值一致。 */
    private static final String KEY = "dailyReportSendWxJob";

    private AgentPlatformProperties properties;
    private ControlPlaneStore store;
    private AgentManager agentManager;
    private DailyReportSendWxJob job;

    private Logger jobLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        properties = new AgentPlatformProperties();
        properties.setDailyReportPromptKey(KEY);
        store = mock(ControlPlaneStore.class);
        agentManager = mock(AgentManager.class);

        job = new DailyReportSendWxJob();
        // 三个依赖都是字段注入（见 TraceEventStreamTest 里同样的手工接线）；
        // 不引 Spring 容器，避免为了几行判断起一个 ApplicationContext。
        ReflectionTestUtils.setField(job, "agentPlatformProperties", properties);
        ReflectionTestUtils.setField(job, "controlPlaneStore", store);

        // 只收本类打的日志：断言"报错有没有报错到点子上"，又不会跟别的测试串味。
        jobLogger = (Logger) LoggerFactory.getLogger(DailyReportSendWxJob.class);
        appender = new ListAppender<>();
        appender.start();
        jobLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(appender);
    }

    /** 正常路径：正文原样返回 —— 不做 trim、不做替换，页面上写什么就是什么。 */
    @Test
    void 取到正文时原样返回且记一条来源日志() {
        String body = "# 卡单日报\n请统计今日卡单并汇总。\n";
        when(store.findPrompt(KEY)).thenReturn(promptRow(body));

        assertThat(job.loadPrompt()).isEqualTo(body);
        assertThat(messages(Level.INFO))
                .singleElement()
                .asString()
                .contains("md_prompt")
                .contains(KEY);
    }

    /** 库里没有这条 prompt：这是新环境最容易撞上的情况（页面还没建），文案必须说清去哪儿建。 */
    @Test
    void prompt在库里不存在时返回null并指向提示词页面() {
        when(store.findPrompt(KEY)).thenReturn(null);

        assertThat(job.loadPrompt()).isNull();
        assertThat(messages(Level.ERROR))
                .singleElement()
                .asString()
                .contains(KEY)
                .contains("md_prompt")
                .contains("提示词");
    }

    /** 行在、正文为空：与"行不存在"分开报，否则会让人去页面里找一个明明存在的 prompt。 */
    @Test
    void 正文为空时返回null并指出是正文的问题() {
        when(store.findPrompt(KEY)).thenReturn(promptRow("   \n"));

        assertThat(job.loadPrompt()).isNull();
        assertThat(messages(Level.ERROR))
                .singleElement()
                .asString()
                .contains(KEY)
                .contains("正文为空");
    }

    /** 连 key 都没配：不该去查库（查了也只会拿到 null，却把配置问题伪装成数据问题）。 */
    @Test
    void 未配置key时返回null且不去查库() {
        properties.setDailyReportPromptKey("  ");

        assertThat(job.loadPrompt()).isNull();
        verifyNoInteractions(store);
        assertThat(messages(Level.ERROR)).singleElement().asString().contains("daily-report-prompt-key");
    }

    /**
     * agent 不在运行时里就不该往下走。
     *
     * <p>没这一句的话，下一行是 {@code agent.call(...)} —— 抛的是 NPE，
     * 再被同一段代码的 catch 兜成一条"定时任务执行异常"的堆栈；堆栈里不会出现
     * "是哪个 agent 不见了"，排查得从异常行反着读代码。
     */
    @Test
    void agent不存在时直接跳过并指名道姓() {
        ReflectionTestUtils.setField(job, "agentManager", agentManager);
        when(agentManager.get("trading_anomaly_agent")).thenReturn(null);

        job.run();

        assertThat(messages(Level.ERROR))
                .singleElement()
                .asString()
                .contains("trading_anomaly_agent")
                .contains("Agents 页面");
        // 连 prompt 都不该去读：没有 agent，读出来也没人能执行它
        verifyNoInteractions(store);
    }

    /** 有 agent 但没 prompt：跳过，且不能把 agent 拉起来空跑（空跑的结果会被推到群里）。 */
    @Test
    void prompt取不到时跳过且不调用agent() {
        ReflectionTestUtils.setField(job, "agentManager", agentManager);
        ReActAgent agent = mock(ReActAgent.class);
        when(agentManager.get("trading_anomaly_agent")).thenReturn(agent);
        when(store.findPrompt(KEY)).thenReturn(null);

        job.run();

        verifyNoInteractions(agent);
        assertThat(messages(Level.WARN)).anySatisfy(msg -> assertThat(msg).contains("跳过"));
    }

    private static PromptEntity promptRow(String body) {
        PromptEntity row = new PromptEntity();
        row.setPromptKey(KEY);
        row.setBody(body);
        row.setVersion(3);
        row.setStatus("PUBLISHED");
        return row;
    }

    private List<String> messages(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}