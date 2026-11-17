package com.md.agentplatform.server.schedule;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.config.AgentPlatformProperties;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.TraceRunIdGenerator;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 卡单日报定时任务 —— 每天定点把「日报任务 prompt」交给 {@code trading_anomaly_agent} 跑一遍。
 *
 * <h2>任务 prompt 为什么必须放在库里</h2>
 * 它是业务口会反复改的东西（统计口径、字段、话术），改一次就发一次版是不现实的。
 * 所以正文的权威位置是 {@code md_prompt.body}（页面「提示词」维护），
 * 本类只保留"用哪个 key"这一个引用。
 */
@Slf4j
@Component
public class DailyReportSendWxJob {

    /** 执行任务的 agent 名 —— 运行时的 agent 表（{@code md_agent}）里的主键。 */
    private static final String AGENT_NAME = "trading_anomaly_agent";

    @Autowired
    private AgentManager agentManager;

    /** 只用来取 prompt-key（{@code agent-platform.daily-report-prompt-key}）。 */
    @Autowired
    private AgentPlatformProperties agentPlatformProperties;

    /**
     * 提示词正文的唯一来源。
     *
     * <p>为什么用 {@link ControlPlaneStore} 而不是自己写 SQL、也不是继续读 Nacos：
     * agent 装配期解析 {@code prompt-key → sysPrompt} 走的正是这个接口
     * （{@code AgentDefinitionResolver#applyPrompt}）。共用同一个入口，
     * 「页面上看到的 prompt」「agent 生效的 prompt」「本任务用的 prompt」才是同一份内容 ——
     * 这三者一旦分叉，排查"为什么模型行为不对"时最难发现。
     */
    @Autowired
    private ControlPlaneStore controlPlaneStore;

    @XxlJob("dailyReportSendWxJob")
    public void run() {
        log.info("[DailyReport] 定时任务开始执行");

        ReActAgent tradingAnomalyAgent = (ReActAgent) agentManager.get(AGENT_NAME);
        if (tradingAnomalyAgent == null) {
            // 没有这一句的话，下一行会直接 NPE，再被下面的 catch 兜成一条"执行异常"的堆栈；
            // 而堆栈里不会出现"是哪个 agent 不见了"，排查得从异常行反着读代码。
            // 运行时的 agent 只来自控制面表 md_agent，
            // 所以这句日志本身就是修法提示。
            log.error("[DailyReport] 运行时没有名为 {} 的 agent，本次跳过（请在 Agents 页面确认它已创建且已发布）",
                    AGENT_NAME);
            return;
        }

        String prompt = loadPrompt();
        if (prompt == null) {
            // 拿不到 prompt 就发车，等于让 agent 在没有任务描述的情况下自由发挥，
            // 而它的输出会被原样推到企微 / 飞书群。宁可这一次不发。
            log.warn("[DailyReport] 未取到任务 prompt，本次跳过");
            return;
        }

        Msg timeMsg = Msg.builder()
                .textContent("现在时间是: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年M月d号, H点m分s秒. ")))
                .role(MsgRole.USER)
                .build();

        Msg msg = Msg.builder()
                .textContent(prompt)
                .role(MsgRole.USER)
                .build();

        long t0 = System.currentTimeMillis();
        // 这次执行的 runId：由本类先定，中间件照用（见 AgentTraceRegistry.attachRunId）。
        // 定时任务没有"调用方"能回执 id，日志里这行就是它与执行链路页之间唯一的桥。
        String runId = TraceRunIdGenerator.next();
        try {
            Msg result = tradingAnomalyAgent.call(
                    List.of(timeMsg, msg),
                    RuntimeContext.builder()
                            .sessionId("xxl-job-daily-repor-"
                                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH")))
                            .userId("xxl-job")
                            .put(AgentTraceRegistry.RUN_ID_ATTR, runId)
                            // 来源只影响观测页的筛选与展示，不参与任何业务判断。
                            .put(AgentTraceRegistry.SOURCE_ATTR, AgentTraceRun.SOURCE_SCHEDULE)
                            .build()
            ).block();

            long elapsed = System.currentTimeMillis() - t0;

            if (result == null) {
                log.warn("[DailyReport] Agent 返回 null run={} elapsed={}ms", runId, elapsed);
                return;
            }

            String textContent = result.getTextContent();
            log.info("[DailyReport] 定时任务执行完成 run={} textLen={} elapsed={}ms",
                    runId, textContent != null ? textContent.length() : 0, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            log.error("[DailyReport] 定时任务执行异常 run={} elapsed={}ms err={}",
                    runId, elapsed, e.getMessage(), e);
        }
    }

    /**
     * 读取任务主体 prompt —— <b>每次执行都重新查库</b>，页面改完发布即生效，不用重启服务。
     *
     * <h2>改造前的两条来源都已废弃</h2>
     * 原实现是「Nacos AI Prompt 中心 → workspace 下的本地文件」，
     * 两者都是"代码/机器侧"的东西：Nacos 那侧页面看不到，本地文件那侧要靠登录服务器改。
     * 结果就是定时任务实际用的 prompt 与页面上维护的那份<b>是两份内容</b>，
     * 而且这个偏差没有任何地方会报出来。现在只剩 {@code md_prompt} 一个来源，
     * 取不到就明确报错，不再静默兜底。
     *
     * <p>包级可见（而不是 private）只是为了单测能直接钉住下面几条分支：
     * 走 {@code run()} 那一层还得先真的起一个 agent，代价远大于这几行判断。
     *
     * <p>本方法不抛异常：定时任务的失败应该表现为"这次没发"，而不是"调度器报错"。
     * 取不到内容时返回 {@code null}，由调用方决定怎么办（当前是跳过本次执行）。
     */
    String loadPrompt() {
        String promptKey = agentPlatformProperties.getDailyReportPromptKey();
        if (promptKey == null || promptKey.isBlank()) {
            log.error("[DailyReport] 未配置 agent-platform.daily-report-prompt-key，无法确定要读哪条 prompt");
            return null;
        }

        PromptEntity prompt = controlPlaneStore.findPrompt(promptKey);
        if (prompt == null) {
            log.error("[DailyReport] md_prompt 里没有 prompt-key={}，请在「提示词」页面创建并发布它", promptKey);
            return null;
        }
        if (prompt.getBody() == null || prompt.getBody().isBlank()) {
            log.error("[DailyReport] prompt-key={} 的正文为空，请在「提示词」页面补上内容", promptKey);
            return null;
        }

        log.info("[DailyReport] 从 md_prompt 加载 prompt: key={} version={} status={} len={}",
                promptKey, prompt.getVersion(), prompt.getStatus(), prompt.getBody().length());
        return prompt.getBody();
    }
}
