package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code md_gateway} 的行模型 —— 网关配置（单行，id 固定 1）。
 *
 * <h2>为什么它不 implements GovernedRow</h2>
 * 受治理资源的那 8 个字段（草稿/发布/版本/快照）在这里没有意义：网关不是一个"发布出去的资源"，
 * 它是<b>一次装配的输入</b>——改了主 agent 就等于要重新装配，不存在"草稿态"。
 * 硬套 GovernedRow 会造出"网关 v3 已发布"这种没人能解释的状态。
 * （渠道就不同：渠道有线上形态，改错一个 botId 会断掉入口，所以它是受治理资源。）
 *
 * <h2>为什么是单行表</h2>
 * 见 {@code channel-schema.sql} 里这张表的注释：一个部署只有一套网关，
 * 多行会引入"哪行生效"这个必须靠约定回答的问题。
 */
@Getter
@Setter
@TableName(value = "md_gateway", autoResultMap = true)
public class GatewayEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    /** 网关主 agent；null = 未设置（此时不装配网关，绑定与渠道兜底都命中不到时的消息会被明确拒绝）。 */
    private String mainAgentId;

    private String description;

    private Long updatedAtMs;

    private String updatedBy;
}
