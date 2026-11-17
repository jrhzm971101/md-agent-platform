package com.md.agentplatform.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 平台级配置。
 * <pre>
 * agent-platform:
 *   workspace-root: workspace
 *   verbose-trace-users: [ou_xxx, ou_yyy]
 * </pre>
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "agent-platform")
public class AgentPlatformProperties {

    /** 详细链路输出白名单（openId / userId 列表），其余用户仅展示最终结果。 */
    private List<String> verboseTraceUsers = List.of();

    /** 工作区根目录，存放 prompt / stateStore / skills / agent-definitions 等。 */
    private String workspaceRoot = ".workspace";

    /**
     * 卡单日报定时任务要用的 prompt-key（对应 {@code md_prompt.prompt_key}）。
     *
     * <p>改造前这里指的是 Nacos AI 中心里发布的 Prompt；Nacos 那条读取路径已废弃，
     * 现在这个 key 只在 md_prompt 里解析，由页面「提示词」维护（见 DailyReportSendWxJob）。
     */
    private String dailyReportPromptKey = "dailyReportSendWxJob";

    // ==================== 控制面元数据（Agent / Prompt / Skill / MCP 落库） ====================

    /**
     * skill 内容所在 schema —— 官方 MysqlSkillRepository 的两张内容表建在这里。
     *
     * <p>为什么不能直接用业务库：官方仓库对库名做标识符白名单校验（只允许字母数字下划线），
     * 而业务库名 {@code md-agent-platform} 带连字符，会被它直接拒绝（设计文档 §4.3.1）。
     * 改这个值之前先确认目标库名不含连字符 —— 否则启动即失败，且报错来自框架内部，
     * 信息里只有一个 "Invalid value"，很难联想到是这里配错了。
     */
    private String controlPlaneSkillDatabase = "agentscope";

    /**
     * 状态存储所在的 schema —— 官方 {@code MysqlAgentStateStore} 的表建在这里。
     *
     * <p><b>为什么和业务库分开</b>：state 表是唯一"行数少但单体极大"的对象 ——
     * 实测单个会话槽的 {@code state_data} 最大 1.65 MB、平均 574 KB，整库比控制面
     * 元数据大一个量级。混在业务库里会让备份/恢复/容量评估都被它拖住，
     * 而它偏偏是最不需要精细备份的那部分（丢了只影响上下文，不影响治理数据）。
     *
     * <p><b>为什么这里可以带连字符</b>：该库名只由框架原生 JDBC 使用
     * （它自己会做反引号转义），不经过官方那些做标识符白名单校验的仓库
     * —— 与 {@link #controlPlaneSkillDatabase} 的约束正好相反，别把两条规则搞混。
     */
    private String stateStoreDatabase = "md_agent_state";

    /**
     * 工作区文件 KV 所在 schema —— 只服务 {@code fs_backend=REMOTE} 的 HARNESS agent。
     *
     * <p>框架会在这里建 {@code agentscope_store}（KV 主表）/ {@code agentscope_snapshots} 等表，
     * 库与表都不存在时自动创建，所以新环境不需要额外执行 DDL。
     *
     * <p><b>允许连字符</b>：建库语句由本项目自己拼写、库名进反引号，校验规则是
     * 「字母数字下划线连字符」；与 {@link #stateStoreDatabase} 那套「只由框架 JDBC 使用」
     * 的说明是同一条约束的两种表述，别以为是两回事。
     *
     * <p><b>为什么和业务库分开</b>：工作区 KV 的行数随「用户数 × 会话数 × 文件数」增长 ——
     * 单体小、总量大、可整体丢弃；业务库是治理元数据，丢了平台起不来。两者不该共用一套备份策略。
     */
    private String workspaceKvDatabase = "md_agent_workspace";

    /**
     * 调用链路在<b>内存</b>里保留的 run 条数上限（热数据），超出按最旧淘汰。<=0 时按 200 处理。
     *
     * <p>语义在 Step 2 变过一次，别再按旧理解用：以前它是"能查到多少历史"，
     * 现在历史都在 MySQL 里，这个值只决定"哪些 run 不用查库就能读到"。
     * 调大它只影响内存占用与读延迟，不会影响 {@code /api/traces} 能返回多少条 ——
     * 内存不够时 Registry 会自动回存储补齐。
     */
    private int traceMaxRuns = 200;

    /**
     * 是否把 run 与事件归档进 MySQL。
     *
     * <p>关闭后退化为纯内存（重启即丢），用于"本地不想连库也要看链路"的场景。
     * 关闭时 {@code AgentTraceRegistry} 注入不到存储实现，走的就是 Step 1 的老路径。
     */
    private boolean traceArchiveEnabled = true;

    /** 归档写入队列容量。队列满时<b>丢弃</b>观测数据并告警，绝不阻塞 agent 线程。 */
    private int traceArchiveQueueCapacity = 20_000;

    /** 归档单批刷盘的条数上限：攒够这么多条（或队列暂时空了）就提交一次。 */
    private int traceArchiveBatchSize = 500;

    /** 归档保留天数，<=0 表示永久保留。到期数据由 {@code TraceRetentionJob} 每天清理一次。 */
    private int traceRetentionDays = 30;

    /**
     * SSE 事件流的兜底轮询间隔（毫秒），实现方会把它抬到最小 1000。
     *
     * <p>实时推送的<b>主通道是进程内通知</b>（事件一写入就唤醒，毫秒级），这个轮询是两种情况的保险：
     * 通知丢了、以及 run 已被环形缓冲淘汰只能回 MySQL 读。
     * 调小 = 页面更实时但库压力更大；调大 = 更省库，但"通知丢失"这类异常的恢复变慢。
     */
    private long traceStreamRecoveryIntervalMs = 5_000;

    /**
     * SSE 心跳间隔（毫秒）。
     *
     * <p>心跳帧是 SSE 的注释行（{@code : keep-alive}）：不含数据、前端直接忽略，
     * 唯一作用是让中间的代理 / 负载均衡不因为"长时间没有字节流动"掐掉这条长连接
     * （nginx 的 {@code proxy_read_timeout} 默认 60 秒，就是这个行为）。
     */
    private long traceStreamHeartbeatMs = 15_000;

    public Set<String> verboseTraceUsersAsSet() {
        return new HashSet<>(verboseTraceUsers);
    }
}
