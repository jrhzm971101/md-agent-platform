package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * {@code md_agent_runtime} 的行模型 —— 通用运行时旋钮 + 模型 + REACT 专有项。
 *
 * <h2>三态开关：NULL 不是 false</h2>
 * 本表绝大多数列可空，三种取值各有所指：
 * <ul>
 *   <li>{@code NULL} = <b>未设置</b>：装配时不下发对应的 setter，用框架默认值；</li>
 *   <li>{@code 0/false} = 显式关闭；</li>
 *   <li>{@code 1/true} = 显式开启。</li>
 * </ul>
 * 这不是洁癖：框架升级改了某个默认值（比如默认 maxIters 从 10 变 20），
 * 只有 NULL 的语义能让平台跟着变。若页面把"没填"存成 0/false，框架的演进就被平台悄悄钉死了，
 * 而且没有任何地方会提示这件事。因此本类所有开关字段都用包装类型 {@code Boolean}，
 * 不用原始类型 —— 后者会把 NULL 读成 false，三态直接退化成两态。
 *
 * <h2>结构化配置为什么用 JSON 列</h2>
 * {@code generate_options} 等五项对应框架的配置对象（GenerateOptions / ExecutionConfig /
 * RetrieveConfig / PermissionContextState）。它们字段少、没有按子字段查询的需求，
 * 拆成五张子表只会把装配期变成五次额外 join，换不来任何查询能力。
 */
@Getter
@Setter
@TableName(value = "md_agent_runtime", autoResultMap = true)
public class AgentRuntimeEntity {

    @TableId(value = "agent_name", type = IdType.INPUT)
    private String agentName;

    // ==================== 模型 ====================

    /** 主模型 bean 名；空则用框架默认（openAIChatModel）。 */
    private String modelRef;

    /** fallback 模型 bean 名；空则<b>不</b>套任何默认模型（与 modelRef 的默认行为不对称，是刻意的）。 */
    private String fallbackModelRef;

    /** low | medium | high。 */
    private String reasoningEffort;

    // ==================== 迭代与重试 ====================

    private Integer maxIters;

    private Integer maxRetries;

    /** HARNESS 最大上下文 token；NULL 表示框架默认。 */
    private Integer maxContextTokens;

    // ==================== 行为开关（三态） ====================

    private Boolean checkRunning;

    private Boolean stopOnReject;

    private Boolean enableMetaTool;

    private Boolean enableTaskList;

    private Boolean enablePendingToolRecovery;

    // ==================== 状态与权限 ====================

    /** REACT 专有：OVERWRITE | FAIL | APPEND_MERGE。 */
    private String conflictPolicy;

    // ==================== REACT 专有的技能策略 ====================

    /**
     * REACT 专有：是否允许动态加载技能。
     *
     * <p>这三项都出自 {@code ReActAgent.Builder}（dynamicSkillsEnabled / skillCodeExecutionEnabled /
     * skillWorkDir），{@code HarnessAgent.Builder} 上没有对应方法 —— 框架在 HarnessAgent 内部
     * 还会主动 {@code dynamicSkillsEnabled(false)} 把动态技能抑制掉，改用 SkillManageConfig。
     *
     * <p>它们原本在 {@code md_agent_workspace} 里，2026-09-22 迁到本表。这不是"就近放"：
     * 迁走之后那张表才是纯 HARNESS 表，可以整表归属于「Harness 工作台」页；本表则成为
     * "两链共有调优 + REACT 专有项"的家 —— conflictPolicy 本来就在这儿，三项与它同族。
     * 反过来说，如果留在 workspace 表，那个表就只能按列拆所有权，而列级所有权是
     * 没人能长期记住的规则（改一次代码就要重新对一遍谁写哪几列）。
     */
    private Boolean dynamicSkillsEnabled;

    /** REACT 专有：是否允许技能代码执行。 */
    private Boolean skillCodeExecutionEnabled;

    /** REACT 专有：技能执行工作目录（相对 workspace-root；装配时拼成绝对路径）。 */
    private String skillWorkDir;

    /** 状态存储标记：非空 = 启用 MysqlAgentStateStore（建表在 {@code agent-platform.state-store-database} 指定的库里）。 */
    private String stateStore;

    /**
     * 状态表名 —— <b>创建时冻结，之后再改 agent 名也不动它</b>。
     *
     * <p>为什么要单独一列而不是直接用 agent 名：表名一旦等于 agent 名，"改个名字"
     * 就会让这个 agent 指向一张空表 —— 表现是"改名之后它把之前聊的全忘了"，
     * 而旧数据还留在库里成了一张没人认识的孤儿表。这类事故排查成本极高
     * （页面显示一切正常，只有上下文莫名清空），所以把表名做成显式数据、与名字解耦。
     *
     * <p>为空时回落 {@code agent_name}，兼容存量行；新页面一律显式写入。
     */
    private String stateTable;

    private String defaultSessionId;

    /** AGENT_CONTROL | STATIC_CONTROL | BOTH。 */
    private String longTermMemoryMode;

    private Boolean longTermMemoryAsync;

    private String ragMode;

    // ==================== 结构化配置（JSON 列） ====================

    @TableField(value = "generate_options", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> generateOptions;

    @TableField(value = "model_execution_config", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> modelExecutionConfig;

    @TableField(value = "tool_execution_config", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> toolExecutionConfig;

    @TableField(value = "retrieve_config", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> retrieveConfig;

    @TableField(value = "permission", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> permission;
}
