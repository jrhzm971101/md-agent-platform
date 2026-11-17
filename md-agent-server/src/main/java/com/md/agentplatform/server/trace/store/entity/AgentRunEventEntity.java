package com.md.agentplatform.server.trace.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.md.agentplatform.server.trace.AgentTraceEvent;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * {@code md_agent_run_event} 的行模型 —— 一条阶段事件一行。
 *
 * <h2>为什么自增主键而不是 (run_id, seq) 复合主键</h2>
 * seq 是本 run 内的序号，它天然唯一（写入侧保证），但把它当主键会把"序号"和"物理行标识"
 * 绑死：以后要支持事件修正（例如补齐运行中进程被 kill 掉留下的半截阶段）就得改主键。
 * 用自增 BIGINT 做物理主键、(run_id, seq) 做业务唯一键，两者各司其职 ——
 * 重复投递时唯一键会把重复数据挡在门外（抛 DuplicateKeyException），
 * 而自增主键让"第 3 条事件"这种说法不会与业务语义混淆。
 *
 * <p>JSON 列的两个必要条件（autoResultMap + typeHandler）与原因见 {@link AgentRunEntity} 的类注释。
 */
@Getter
@Setter
@TableName(value = "md_agent_run_event", autoResultMap = true)
public class AgentRunEventEntity {

    /** 物理主键，数据库自增 —— 批量插入时不需要回填（观测数据没有业务主键需求）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String runId;

    /** 事件序号：同一 run 内从 1 开始、连续、产生即冻结。 */
    private int seq;

    /** 所属阶段标识（= 该阶段 STARTED 事件的 seq）。 */
    private int stageSeq;

    /** STARTED | COMPLETED。 */
    private String eventType;

    /** AGENT | REASONING | ACTING。 */
    private String phase;

    private String title;

    /** RUNNING | OK | ERROR。 */
    private String status;

    /** 阶段开始时刻：两种事件都带，父子关系靠它算。 */
    private long stageStartMs;

    /** 阶段耗时；STARTED 恒为 0。 */
    private long costMs;

    @TableField(value = "input", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> input;

    @TableField(value = "output", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> output;

    private String error;

    /** 落库时刻 —— 用来回答"事件比 run 晚多久落盘"，与业务时间（stageStartMs）不是一回事。 */
    private long createdAtMs;

    /**
     * 事件 → 行快照。
     *
     * <p>runId 由外部传入而不是从事件里取：{@link com.md.agentplatform.server.trace.AgentTraceEvent}
     * 是"阶段内部"的原子单位，刻意不带 runId（带了就要在每个阶段里重复一份、
     * 并给出一致性负担）；归属关系只有 Registry 知道。
     *
     * <p>{@code createdAtMs} 用"落库时刻"而不是事件的发生时刻：它是<b>运行期指标</b>
     * （这个消费线程有没有积压），与业务时间正交。混用同一个字段会让"事件为什么延迟 3 秒"
     * 这种问题失去唯一可对照的基准。
     */
    public static AgentRunEventEntity of(String runId, AgentTraceEvent event) {
        AgentRunEventEntity entity = new AgentRunEventEntity();
        entity.setRunId(runId);
        entity.setSeq(event.seq());
        entity.setStageSeq(event.stageSeq());
        entity.setEventType(event.eventType());
        entity.setPhase(event.phase());
        entity.setTitle(event.title());
        entity.setStatus(event.status());
        entity.setStageStartMs(event.stageStartMs());
        entity.setCostMs(event.costMs());
        entity.setInput(event.input());
        entity.setOutput(event.output());
        entity.setError(event.error());
        entity.setCreatedAtMs(System.currentTimeMillis());
        return entity;
    }
}
