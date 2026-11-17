package com.md.agentplatform.server.trace.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.md.agentplatform.server.trace.AgentTraceRun;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * {@code md_agent_run} 的行模型 —— 一次 run 一行。
 *
 * <h2>一、为什么单独一层，不直接把 {@link com.md.agentplatform.server.trace.AgentTraceRun} 当实体</h2>
 * 领域对象是<b>活的</b>：它在内存里被并发写入（volatile 字段 + CopyOnWriteArrayList），
 * 字段名是驼峰的、时间字段是 epoch millis。行模型是<b>死的快照</b>：写入即冻结，
 * 字段名与列一一对应。把两者合并，等于让 MyBatis 的反射写到一个正在被 agent 线程改的对象上；
 * 而且以后加一个"仅内存使用、不落库"的字段（例如把事件明细挂在 run 上缓存）时，
 * 就必须回头去给列名打 @TableField(exist = false) —— 那是把存储细节反向渗进领域模型。
 *
 * <h2>二、"写入正常、查回来是 null" 的经典坑（改本类前必读）</h2>
 * JSON 列必须<b>同时</b>满足两个条件才可用：
 * <ol>
 *   <li>实体上的 {@code @TableName} 带 {@code autoResultMap = true}；
 *   <li>字段上显式声明 {@code typeHandler = JacksonTypeHandler.class}。
 * </ol>
 * 少了前者：MyBatis 用默认的 ResultMap 组装结果，typeHandler 不生效，<b>查询回来永远是 null</b>
 * —— 而写入是正常的（写入走的是参数绑定，与 autoResultMap 无关），所以表现为"存进去了但读不出来"，
 * 极难定位。少了后者：参数绑定会用默认的 ObjectTypeHandler，MySQL 直接报
 * "Cannot create JSON value from a Java object"。
 */
@Getter
@Setter
@TableName(value = "md_agent_run", autoResultMap = true)
public class AgentRunEntity {

    /** 主键由业务生成（TraceRunIdGenerator），不让数据库自增/回填，所以是 {@code IdType.INPUT}。 */
    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    private String agentName;

    /** react | harness。 */
    private String agentKind;

    private String sessionId;

    private String userId;

    /** 触发来源（console | wecom | feishu | schedule | workflow）；null = 入口方没标注。 */
    private String source;

    /**
     * 所属工作流运行（md_workflow_run.run_id）；null = 不是工作流发起的。
     *
     * <p>注意它的 NULL 语义与 {@link #source} 相反：这里 null = 确实不属于任何工作流，
     * 而 source 的 null = 入口方没标注。绝大多数 run 都是前者。
     */
    private String workflowRunId;

    /** 所属工作流节点 key；null = 不是工作流发起的。 */
    private String workflowNodeKey;

    /**
     * 派发出这条 run 的父 run；null = 用户/通道/工作流直接发起。
     *
     * <p>只有异步子 agent（父 run 收口之后才开跑）会有值，语义详见
     * {@link com.md.agentplatform.server.trace.AgentTraceRun#getParentRunId()}。
     */
    private String parentRunId;

    /** 框架后台任务号；null = 不是后台任务跑起来的。 */
    private String taskId;

    /** RUNNING | DONE | ERROR。 */
    private String status;

    private long startedAtMs;

    /** 进行中为 null；用包装类型而不是 long：null（还没结束）与 0（1970 年）在语义上完全不同。 */
    private Long endedAtMs;

    private int inputTokens;

    private int outputTokens;

    private int cachedTokens;

    private String error;

    private String systemPrompt;

    @TableField(value = "input", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> input;

    /**
     * 领域对象 → 行快照。
     *
     * <p><b>必须在调用线程上立刻调用完</b>，不能把 {@code AgentTraceRun} 本身丢给后台线程去转换：
     * 那个对象是活的，agent 线程还在往上写（状态、token、结束时刻），后台线程晚几百毫秒再读，
     * 拿到的就是另一个时刻的中间态。这里逐字段拷贝，拷完即与领域对象脱钩。
     *
     * <p>拷的是引用（input 这个 Map），不是深拷贝：它由中间件在阶段开始时构造一次后就不再改，
     * 深拷贝一份几百个消息的摘要没有收益。
     */
    public static AgentRunEntity of(AgentTraceRun run) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(run.getRunId());
        entity.setAgentName(run.getAgentName());
        entity.setAgentKind(run.getAgentKind());
        entity.setSessionId(run.getSessionId());
        entity.setUserId(run.getUserId());
        entity.setSource(run.getSource());
        entity.setWorkflowRunId(run.getWorkflowRunId());
        entity.setWorkflowNodeKey(run.getWorkflowNodeKey());
        entity.setParentRunId(run.getParentRunId());
        entity.setTaskId(run.getTaskId());
        entity.setStatus(run.getStatus());
        entity.setStartedAtMs(run.getStartedAtMs());
        // 领域模型用 0 表示"还没结束"，行模型用 NULL 表示 —— 落库时在边界上翻译一次，
        // 免得 1970-01-01 这种值混进表里，让"平均耗时"这类统计莫名偏大。
        entity.setEndedAtMs(run.getEndedAtMs() > 0 ? run.getEndedAtMs() : null);
        entity.setInputTokens(run.getInputTokens());
        entity.setOutputTokens(run.getOutputTokens());
        entity.setCachedTokens(run.getCachedTokens());
        entity.setError(run.getError());
        entity.setSystemPrompt(run.getSystemPrompt());
        entity.setInput(run.getInput());
        return entity;
    }
}
