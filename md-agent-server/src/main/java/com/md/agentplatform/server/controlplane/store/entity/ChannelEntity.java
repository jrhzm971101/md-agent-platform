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
 * {@code md_channel} 的行模型 —— 一个已接通的消息入口（企微机器人 / 飞书应用）。
 *
 * <h2>一、为什么凭据放 JSON 列而不是逐字段建列</h2>
 * 判断依据与 {@code AgentSubAgentEntity} 那条相反，这里写清楚免得后来者觉得不一致：
 * <ul>
 *   <li>子 agent <b>在页面上是一个可增删的表格</b> → 需要按列校验与排序 → 逐字段建列；</li>
 *   <li>渠道凭据 <b>在页面上是按类型动态渲染的一组输入框</b>（企微 2 个、飞书 5 个、
 *       将来钉钉又是另一套），不会增删行、不需要按子字段查询 → 塞 JSON 列更省，
 *       而且「加一个平台」不必改 DDL。</li>
 * </ul>
 * 缺字段的校验（比如企微没填 botId）由 {@code ChannelValidator} 在应用层做：
 * 它能一次列出全部问题并说清该填什么，比数据库的 NOT NULL 报错有用得多。
 *
 * <h2>二、dm_scope 直接沿用官方枚举名</h2>
 * 存的是 {@code io.agentscope.harness.agent.gateway.channel.DmScope} 的枚举名字符串，
 * 不自己造一套。这样路由期可以把它原样交回官方 {@code ChannelConfig}，
 * 路由语义（含"未设置时用官方默认值"）就永远不会与本平台的理解漂移。
 *
 * <h2>三、reply_style 是本平台的扩展列</h2>
 * 官方渠道模型里没有它：官方只管"消息怎么进来、交给谁"，回复形态交给通道实现自己决定。
 * 本项目的两种通道实现（企微流式思考卡片 / 飞书卡片）把形态做成了可配项，
 * 所以它落在渠道行上，而不是 agent 行上 —— 同一个 agent 从企微和飞书进来可以长得不一样。
 */
@Getter
@Setter
@TableName(value = "md_channel", autoResultMap = true)
public class ChannelEntity implements GovernedRow {

    @TableId(value = "channel_id", type = IdType.INPUT)
    private String channelId;

    /** wecom | feishu；决定装配期用哪个适配器工厂。 */
    private String channelType;

    /** 展示名；空则页面直接显示 channel_id。 */
    private String displayName;

    private String description;

    /** 归属人，对齐官方 {@code ChannelConfigEntry.ownerId}。 */
    private String ownerId;

    /** 兜底 agent；null = 用网关主 agent。 */
    private String defaultAgentId;

    /** 官方 DmScope 枚举名；null = 官方默认值（MAIN）。 */
    private String dmScope;

    /** 回复渲染风格；null = 按平台推断。 */
    private String replyStyle;

    /** 类型特有凭据（botId/secret 或 appId/appSecret/...）。 */
    @TableField(value = "properties", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> properties;

    /** 是否参与装配（停用但不删除）。 */
    private Boolean enabled;

    /** DRAFT | PUBLISHED。 */
    private String status;

    private Integer version;

    private Integer publishedVersion;

    private Long publishedAtMs;

    private Long createdAtMs;

    private Long updatedAtMs;

    private String createdBy;

    private String updatedBy;
}
