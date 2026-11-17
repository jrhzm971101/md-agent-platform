package com.md.agentplatform.server.session;

import lombok.Getter;
import lombok.Setter;

/**
 * 会话详情里的一次执行 —— 映射模型，字段与 {@code md_agent_run} 的列一一对应。
 *
 * <p>这里保持"库里的原样"：{@code status} 是后端词表（RUNNING / DONE / ERROR）、
 * 时间是 epoch millis、耗时要靠首尾相减算出来。翻译与派生都在服务层做
 * （见 {@code AgentSessionViews.SessionRun}），因为那些规则与表结构无关、只与页面有关。
 */
@Getter
@Setter
public class SessionRunRow {

    private String runId;

    /** agent 名（页面上叫 agentId）。 */
    private String agentId;

    /** react | harness，在 run 建立那一刻冻结。 */
    private String agentKind;

    /** 触发来源；null = 入口方没标注。 */
    private String source;

    /** 后端词表：RUNNING | DONE | ERROR。 */
    private String status;

    private long startedAtMs;

    /** 进行中为 null。用包装类型：null（还没结束）与 0（1970 年）语义完全不同。 */
    private Long endedAtMs;

    private long tokens;

    private String error;
}
