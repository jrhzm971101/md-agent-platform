package com.md.agentplatform.server.controlplane.store;

/**
 * 版本快照的<b>列表投影</b> —— 只带页面上「版本历史」要显示的列。
 *
 * <h2>为什么不让页面直接拿 {@code ResourceVersionEntity}</h2>
 * 快照表每行都带一个 {@code snapshot_json}：agent 的快照是 6 张表的整行 JSON，
 * 动辄几十 KB。一个 agent 发布过 20 次，版本列表就要拖回几百 KB 的正文，
 * 而页面这一屏只显示「版本号 / 变更说明 / 时间 / 操作人」四列。
 * 回滚时页面只需要版本号，正文由后端按版本号再取一次（见 {@code ControlPlaneStore#rollback}）。
 *
 * <p>用 record 而不是实体：它是只读投影，没有行模型的注解与可空语义要表达。
 */
public record ResourceVersionInfo(
        /** 版本号，单调递增。 */
        int version,
        /** 本次变更说明，可为 null。 */
        String changeNote,
        /** 快照落库时刻（epoch millis）。 */
        long createdAtMs,
        /** 操作人。 */
        String createdBy) {
}
