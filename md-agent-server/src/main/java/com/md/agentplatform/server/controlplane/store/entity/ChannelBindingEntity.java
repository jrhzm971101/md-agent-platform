package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * {@code md_channel_binding} 的行模型 —— 一条「什么消息交给哪个 agent」的规则。
 *
 * <h2>一、字段为什么与官方 ChannelBinding record 逐一对应</h2>
 * 路由求值<b>直接交给官方 {@code ChannelRouter}</code>（见 {@code ChannelRouteResolver}），
 * 所以本实体不是"参考官方设计"，而是官方 record 的持久化形态：
 * 字段少一个，路由就少一层匹配；字段名改一个，映射表就多一处人工翻译。
 *
 * <h2>二、它是子行，不是受治理资源</h2>
 * 与 {@code md_agent_sub_agent} 同理：绑定的生命周期完全属于它所属的渠道
 * （渠道发布 = 渠道行 + 绑定行一起成为线上形态），因此它没有自己的
 * 状态 / 版本 / 发布列，也不需要单独的 `md_resource_version` 快照 ——
 * 快照里它作为渠道 bundle 的一部分被一起序列化（见 {@code ChannelSnapshot}）。
 *
 * <h2>三、为什么逐字段建列而不用一个 JSON 数组</h2>
 * 与凭据列相反，绑定<b>在页面上就是一个可增删的表格</b>：要按 sort_order 排序、
 * 要按 agent_id 反查「这个 agent 被哪些渠道绑着」（删除保护用）、
 * 要逐列校验（peer 与 guild 不能同时为空，否则这条规则永远不会命中）。
 * 这些能力塞进 JSON 就全丢了。
 */
@Getter
@Setter
@TableName(value = "md_channel_binding", autoResultMap = true)
public class ChannelBindingEntity {

    /** 自增主键；新增行时后端忽略页面传来的值。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String channelId;

    private String agentId;

    /** 精确 peer key，形如 {@code direct:u_42}（最高优先级层）。 */
    private String peer;

    /** 父 peer key（线程继承层）。 */
    private String parentPeer;

    private String guild;

    /** 角色 id 数组；与 guild 同时给出时为 guild+roles 层。 */
    @TableField(value = "roles", typeHandler = JacksonTypeHandler.class)
    private List<String> roles;

    private String team;

    private String account;

    private String channel;

    /** 该绑定的会话范围覆盖；null = 用渠道级 dm_scope。 */
    private String sessionScope;

    /** 同层内的匹配顺序，从 0 开始。 */
    private Integer sortOrder;
}
