package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code md_agent_memory} 的行模型 —— 上下文压缩 + 记忆 + 工具结果驱逐。
 *
 * <h2>为什么这三块合成一张表</h2>
 * 它们回答同一个问题：<b>上下文随轮次变长时怎么收敛</b>。三者相互影响 ——
 * 压缩阈值调大，工具返回的大结果就更容易撑爆上下文，于是驱逐阈值要跟着调；
 * 调参时必须在同一个界面/同一次查询里看到全部取值，拆三张表会让"看一眼当前配置"变成三次查询。
 * 对应框架侧是 {@code CompactionConfig} / {@code MemoryConfig} / {@code ToolResultEvictionConfig} 三个对象。
 *
 * <h2>优先级：disabled 开关压过内容字段</h2>
 * {@code compaction_disabled=1} 时，本表 compaction 那一组字段全部忽略 ——
 * 框架侧 {@code disableCompaction()} 会清掉已配置的 CompactionConfig，两者同时存在时前者胜出。
 * 装配层必须先判 disabled 再读其余字段，否则会写出"关掉了但还是按配置压"的矛盾行为。
 *
 * <h2>DECIMAL 与 timeout 的类型选择</h2>
 * {@code keep_tokens_ratio} 用 {@link BigDecimal} 而不是 double：列类型是 DECIMAL(6,4)，
 * 用 double 会在"读出来再写回去"时产生尾数差异，虽然不影响功能，但会让快照对比出现假变更。
 * 其余时间类的量（如 {@code consolidation_min_gap_ms}）用 BIGINT 存毫秒，
 * 与框架的 {@code Duration} 在装配层做一次转换，避免把 java.time 类型写进行模型。
 */
@Getter
@Setter
@TableName(value = "md_agent_memory", autoResultMap = true)
public class AgentMemoryEntity {

    @TableId(value = "agent_name", type = IdType.INPUT)
    private String agentName;

    // ==================== 上下文压缩 CompactionConfig ====================

    /** true = 关闭压缩（对应 disableCompaction()），下面整组字段忽略。 */
    private Boolean compactionDisabled;

    private Integer triggerMessages;

    private Integer triggerTokens;

    private Integer reserved;

    private Integer keepMessages;

    private Integer keepTokens;

    private Integer keepTokensMin;

    private Integer keepTokensMax;

    private BigDecimal keepTokensRatio;

    /** 摘要提示词。整段正文，用 MEDIUMTEXT 而不是 VARCHAR，因为它可能很长。 */
    private String summaryPrompt;

    private Boolean flushBeforeCompact;

    private Boolean offloadBeforeCompact;

    /** 压缩用模型；空则用主模型。 */
    private String compactionModelRef;

    /** TruncateArgsConfig：触发阈值 + 单参数截断长度 + 截断占位文本。 */
    @TableField(value = "truncate_args", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> truncateArgs;

    /** PruneConfig：保护区间、最小裁剪量、输出上限、排除的工具。 */
    @TableField(value = "prune", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> prune;

    // ==================== 记忆 MemoryConfig ====================

    private String memoryModelRef;

    private String flushPrompt;

    private String consolidationPrompt;

    private Integer consolidationMaxTokens;

    /** 两次归并的最小间隔（毫秒）；装配时转 Duration。 */
    private Long consolidationMinGapMs;

    private Integer dailyFileRetentionDays;

    private Integer sessionRetentionDays;

    /** FlushTrigger：{mode: ALWAYS|NEVER|THROTTLED, minGapMs: n}。 */
    @TableField(value = "flush_trigger", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> flushTrigger;

    // ==================== 工具结果驱逐 ToolResultEvictionConfig ====================

    /** true = 关闭工具结果驱逐（对应 disableToolResultEviction()）。 */
    private Boolean evictDisabled;

    private Integer evictMaxResultChars;

    private Integer evictPreviewChars;

    private String evictPath;

    @TableField(value = "evict_excluded_tools", typeHandler = JacksonTypeHandler.class)
    private List<String> evictExcludedTools;
}