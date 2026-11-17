package com.md.agentplatform.server.state;

import lombok.Getter;
import lombok.Setter;

/**
 * 「一个 agent 的状态表」在页面上的样子。
 *
 * <p>它同时是 MyBatis 的映射目标与接口的响应体 —— 字段少、没有派生逻辑，
 * 再拆一个 DTO 只会多一处需要同步的字段表。
 *
 * <p><b>{@code exists} 为什么是必要的</b>：清单有两个来源，一个是"库里真实存在的表"
 * （information_schema），一个是"库里声明要用的表"（{@code md_agent_runtime.state_store} 非空）。
 * 两者对不上的两种情况都要能看见：
 * <ul>
 *   <li>{@code exists=false}：配了状态存储但一次都没跑过（表还没被框架建出来）——
 *       没有这一行，用户会以为配置没生效；</li>
 *   <li>{@code agentName=null}：表在、但没有任何 agent 认领它（改过名或删过 agent 的残留）——
 *       这是清理孤儿表的入口。</li>
 * </ul>
 */
@Getter
@Setter
public class StateTableRow {

    /** 物理表名（冻结值）。 */
    private String tableName;

    /** 声明使用它的 agent；{@code null} = 孤儿表。 */
    private String agentName;

    /** 表是否已存在。false 时下面的统计字段全为 0/null。 */
    private boolean exists = true;

    /** 不同 session_id 的个数（= 会话数）。 */
    private Long sessions;

    /** 总行数（一个会话可能有多个 state_key，所以行数 ≥ 会话数）。 */
    private Long rows;

    /** state_data 的字节总量。 */
    private Long bytes;

    /** 最近一次写入时间（ISO-8601）。 */
    private String lastWriteAt;
}
