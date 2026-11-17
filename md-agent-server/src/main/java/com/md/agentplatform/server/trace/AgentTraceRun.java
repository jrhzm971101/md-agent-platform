package com.md.agentplatform.server.trace;

import lombok.Getter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次完整的 agent run —— 观测页面的根节点。
 *
 * <p>本类在内存里扮演"运行期聚合根"：事件按产生顺序累积，页面读的就是它。
 * 同时它也是天然的行模型 —— run 一张表、event 一张表，落库时逐字段对得上。
 *
 * <p><b>事件而不是阶段</b>：本类持有的是 {@link AgentTraceEvent}（阶段开始 / 阶段收口各一条），
 * 不是"一个阶段一条"。原因见 {@code AgentTraceEvent} 的类注释 —— 简言之，seq 一旦发出就不能再变，
 * 而阶段是"未完成时缺字段"的可变模型，两者不可调和。
 */
@Getter
public class AgentTraceRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_ERROR = "ERROR";

    /** agent 类型词表，取值与前端 TraceRunInfo.agentKind（'react' | 'harness'）一致。 */
    public static final String AGENT_KIND_REACT = "react";
    public static final String AGENT_KIND_HARNESS = "harness";

    /**
     * 来源词表 —— 这次 run 是「谁」发起的。四个入口各写一个，别处不再新造字面量。
     *
     * <p>为什么要把它记成一列，而不是查询时按 sessionId 前缀反推：sessionId 由每个入口
     * 自己算（企微按天重置、页面用随机短 id、飞书直接用 userId、定时任务用作业名），
     * 格式上互不相干 —— 反推既覆盖不全，也迟早会因为某个入口改了生成规则而算错。
     * 「这条链路是从哪来的」是排查时的第一个问题，值得一个准确的值。
     */
    public static final String SOURCE_CONSOLE = "console";
    public static final String SOURCE_WECOM = "wecom";
    public static final String SOURCE_FEISHU = "feishu";
    public static final String SOURCE_SCHEDULE = "schedule";
    /**
     * 由工作流节点发起。
     *
     * <p>它多带两个归属字段（{@code workflowRunId} / {@code workflowNodeKey}），
     * 所以页面从"工作流运行详情"点进某条链路时，能反过来定位到是哪次编排的哪个节点；
     * 其余来源这两个字段都是 null。取值与另外几个来源一样是历史事实，写一次不再变。
     */
    public static final String SOURCE_WORKFLOW = "workflow";

    /** 来源列宽，与 md_agent_run.source VARCHAR(32) 对齐 —— 超长就在这里截，别丢给数据库报错。 */
    private static final int SOURCE_MAX_LENGTH = 32;

    private final String runId;
    private final String agentName;
    /**
     * 这次 run 是哪种 agent 跑的。
     *
     * <p>在 run 建立的那一刻就定下来，而不是查询时再去反查 AgentDefinition ——
     * 定义会被改、会被删，而"这次执行当时用的是哪种 agent"是历史事实，只能当场记。
     */
    private final String agentKind;
    private final String sessionId;
    private final String userId;
    /**
     * 触发来源（见 {@link #SOURCE_CONSOLE} 等常量）；null = 入口方没标注。
     *
     * <p>在 run 建立那一刻定下来，此后不再变：这是历史事实，与 agent 类型同理。
     */
    private final String source;
    /**
     * 这次调用属于哪次工作流运行（{@code md_workflow_run.run_id}）；null = 不是工作流发起的。
     *
     * <p><b>NULL 的语义与 source 相反，别混</b>：这里 null = 确实不属于任何工作流，
     * 而 source 的 null = 入口方没标注。绝大多数 run 都是前者（页面对话 / 企微 / 飞书 / 定时任务），
     * 所以页面拿它当筛选条件时，"为空"是一等公民而不是异常值。
     *
     * <p>与 agentKind / source 一样在 run 建立那一刻冻结：工作流运行与它的节点都是历史事实。
     */
    private final String workflowRunId;

    /** 这次调用是工作流里的哪个节点（{@code md_workflow_run_node.node_key}）；null = 不是工作流发起的。 */
    private final String workflowNodeKey;

    /**
     * 派发出这条 run 的父 run（{@code md_agent_run.run_id}）；null = 用户直接发起的。
     *
     * <p><b>什么时候会有值</b>：只有"父 run 已经收口、子 agent 才真正开跑"这一种情况 ——
     * 异步子 agent（{@code agent_spawn} 的 {@code timeout_seconds=0}，或同步等待超时后被
     * promote 成后台任务）会在父 run 结束之后继续跑。中间件看到上下文里的 RunState 已经
     * finished，只能给它新开一条 run，此时把那个已收口的父 run 记在这里。
     *
     * <p><b>NULL 的语义</b>：null = 这是一次用户/通道/工作流直接发起的 run，头上没有别人。
     * 与 {@code source} 的 null（= 入口没标注）不是一回事，别混。
     *
     * <p>同步子 agent 没有这条线：它与父 agent 共用同一个 RunState，也就是同一棵调用树、
     * 同一条 run，压根不会另开一条。所以"parentRunId 为空"是绝大多数 run 的常态。
     */
    private final String parentRunId;

    /**
     * 框架 {@code TaskRepository} 里那个后台任务的号（形如 {@code task_<uuid>}）；null = 不是后台任务跑起来的。
     *
     * <p>它回答的是"这条孤立链路对应父页面上哪个 task_id"：父侧的派发节点把 task_id 记在事件载荷里，
     * 这里记在 run 行上，两边就能对上。取不到时保持 null —— 宁可空着，也不要写一个看起来像真号的假值。
     */
    private final String taskId;

    /** 开始时刻。不是 final：从存储回放时需要还原成当时的真实值，而不是"进程启动这一刻"。 */
    private long startedAtMs;

    /** 顶层输入摘要（用户/上游传进来的消息），便于"这次到底问的什么"。 */
    private final Map<String, Object> input;

    private volatile long endedAtMs;
    private volatile String status = STATUS_RUNNING;
    private volatile int inputTokens;
    private volatile int outputTokens;
    private volatile int cachedTokens;
    private volatile String error;

    private final AtomicInteger seq = new AtomicInteger();
    /**
     * 本 run 的事件，保持产生顺序（= seq 升序）。
     *
     * <p>用 CopyOnWriteArrayList：读多写少（页面每次查询读全量，写入只在阶段边界发生），
     * 且读侧要拿到稳定快照 —— 翻译期间有并发写入时，遍历不会抛 ConcurrentModificationException。
     */
    private final List<AgentTraceEvent> events = new CopyOnWriteArrayList<>();

    /**
     * 本轮生效的系统提示词。
     *
     * <p>为什么不作为事件：系统提示词是 run 级属性（整轮只生效一个，且发生在任何阶段之前），
     * 放进调用树会让它多出一个没有任何兄弟的节点。它也不占 seq —— 占号就必须输出，
     * 否则会在事件流里留下永久空洞。落库时它是 run 表上的一列。
     */
    private volatile String systemPrompt;

    public AgentTraceRun(String runId, String agentName, String agentKind, String sessionId, String userId,
                         Map<String, Object> input, String source) {
        this(runId, agentName, agentKind, sessionId, userId, input, source, null, null, null, null);
    }

    /**
     * 完整形状：额外带上"属于哪次工作流运行的哪个节点"。
     *
     * <p>保留 7 参构造器并让它转发到这里，而不是让所有调用点都改成 9 参：
     * 四个入口（页面对话 / 企微 / 飞书 / 定时任务）与工作流毫无关系，
     * 让它们各自写两个 null 只会让"这条链路与工作流无关"这件事显得像是漏填。
     */
    public AgentTraceRun(String runId, String agentName, String agentKind, String sessionId, String userId,
                         Map<String, Object> input, String source, String workflowRunId,
                         String workflowNodeKey) {
        this(runId, agentName, agentKind, sessionId, userId, input, source, workflowRunId, workflowNodeKey,
                null, null);
    }

    /**
     * 最全形状：再带上"是哪个父 run 派发出这条 run 的、对应哪个后台任务"。
     *
     * <p>为什么不把它们塞进 {@link #restoreState}：派生关系与 source 一样是<b>不可变</b>的
     * 历史事实（run 一旦建立，父是谁、任务号是什么就定了），而 restoreState 服务的全是
     * "运行期算出来、重启后要读回来"的可变字段（状态、结束时刻、token）。把不可变字段也塞进去，
     * 会让"哪些字段可变"这条界限变模糊 —— 而这条界限正是回放路径的正确性依据。
     */
    public AgentTraceRun(String runId, String agentName, String agentKind, String sessionId, String userId,
                         Map<String, Object> input, String source, String workflowRunId,
                         String workflowNodeKey, String parentRunId, String taskId) {
        this.runId = runId;
        this.agentName = agentName;
        this.agentKind = agentKind;
        this.sessionId = sessionId;
        this.userId = userId;
        this.input = input;
        this.source = normalizeSource(source);
        // 空白串一律收敛成 null：调用方可能会从上下文字典里取到一个空值，
        // 而"空串"与"null"在页面的筛选器里会表现成两种值，它们其实是同一件事。
        this.workflowRunId = blankToNull(workflowRunId);
        this.workflowNodeKey = blankToNull(workflowNodeKey);
        this.parentRunId = blankToNull(parentRunId);
        this.taskId = blankToNull(taskId);
        this.startedAtMs = System.currentTimeMillis();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 来源归一化：去空白、转小写、按列宽截断；空串一律收敛成 null。
     *
     * <p>为什么在领域模型的构造口做，而不是让四个入口各做一遍：入口是四处，规则是一套。
     * 分开写迟早会有一处漏掉 trim 或大小写，于是筛选器里同时出现 "WeCom" 和 "wecom"，
     * 而页面会把它当成两个来源。收敛到唯一的创建口，入口只需要写对常量。
     */
    private static String normalizeSource(String source) {
        if (source == null) {
            return null;
        }
        String trimmed = source.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= SOURCE_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, SOURCE_MAX_LENGTH);
    }

    /** 下一个事件序号，由 Registry 调用，保证并发下不重号。 */
    public int nextSeq() {
        return seq.incrementAndGet();
    }

    public void addEvent(AgentTraceEvent event) {
        events.add(event);
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void finish(String finalStatus, int inTokens, int outTokens, int cached, String errorMessage) {
        this.status = finalStatus;
        this.inputTokens = inTokens;
        this.outputTokens = outTokens;
        this.cachedTokens = cached;
        this.error = errorMessage;
        this.endedAtMs = System.currentTimeMillis();
    }

    /**
     * 从归档存储还原 run 级状态 —— <b>只允许在读路径上调用一次</b>，构造完即当作只读。
     *
     * <p>为什么需要它：{@link #finish} 之类的写入方法只能表达"刚刚发生"，
     * 而回放要的是"当时是什么"。差异有三处：开始 / 结束时刻、状态、token 汇总
     * —— 这些在内存里是运行期算出来的，重启后必须从库里读回来，否则列表页的
     * 耗时与状态会全部退化成 0 和 RUNNING。
     *
     * <p>不用"追加一个 15 参数的构造函数"：restore 是低频且只有存储层会用的路径，
     * 让构造函数为它膨胀，会让真正高频的"新建一次 run"变得更难读。
     */
    public void restoreState(long startedAtMs, long endedAtMs, String restoredStatus,
                             int inTokens, int outTokens, int cached, String errorMessage,
                             String systemPromptText) {
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.status = restoredStatus;
        this.inputTokens = inTokens;
        this.outputTokens = outTokens;
        this.cachedTokens = cached;
        this.error = errorMessage;
        this.systemPrompt = systemPromptText;
    }

    /**
     * 从归档存储还原事件列表，并把 seq 计数器推到已有事件之后。
     *
     * <p>seq 计数器这一步不能省：回放出来的 run 如果被继续写入（当前不会，但读路径不该
     * 依赖"写入已经结束"这个巧合），新的 nextSeq() 必须从 max(seq)+1 开始，
     * 否则会与已落库的事件撞号，而 (run_id, seq) 上有唯一键 —— 撞号的表现是那条事件被静默丢弃
     * （异步写入失败只记日志），页面上表现为"少了一个节点"，极难排查。
     */
    public void restoreEvents(List<AgentTraceEvent> restoredEvents) {
        events.addAll(restoredEvents);
        int maxSeq = 0;
        for (AgentTraceEvent event : restoredEvents) {
            maxSeq = Math.max(maxSeq, event.seq());
        }
        seq.set(maxSeq);
    }

    public long getCostMs() {
        long end = endedAtMs > 0 ? endedAtMs : System.currentTimeMillis();
        return end - startedAtMs;
    }
}
