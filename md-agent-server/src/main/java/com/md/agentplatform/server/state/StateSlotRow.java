package com.md.agentplatform.server.state;

import lombok.Getter;
import lombok.Setter;

/**
 * 状态表里一行（或一组行）的样子。
 *
 * <p><b>为什么一个类服务三种查询</b>：
 * <ol>
 *   <li><b>按会话聚合</b>（列表）：一行 = 一个 session_id，{@code stateKey}/{@code itemIndex} 为空；</li>
 *   <li><b>按会话明细</b>（详情）：一行 = 一个 {@code (state_key, item_index)} 槽位；</li>
 *   <li><b>按表统计</b>（清单）：只有 rows/bytes/updatedAt 被填。</li>
 * </ol>
 * 三者字段高度重叠，拆成三个类会让"加一个统计字段"变成改三处。代价是每个查询只填一部分字段，
 * 所以下面按用途标注了哪些字段在哪些场景下有效 —— 这正是消费方需要知道的信息。
 *
 * <p><b>{@code slotId} 是什么</b>：库里 {@code session_id} 列的真实值，形如
 * {@code <userId>:<sessionId>} —— 框架把"谁"和"哪个会话"拼成一列当主键用
 * （见 {@code MysqlAgentStateStore.slotId}）。页面上要把它拆开显示，因为
 * "哪个用户的哪个会话"才是人能读的东西。
 */
@Getter
@Setter
public class StateSlotRow {

    /** 库里的 session_id 列原值。所有查询都填。 */
    private String slotId;

    /** 由 slotId 拆出的用户标识。服务层填。 */
    private String userId;

    /** 由 slotId 拆出的会话标识。服务层填。 */
    private String sessionId;

    /** 状态键（框架恒为 {@code agent_state}）。仅明细查询填。 */
    private String stateKey;

    /** 列表型状态的序号，单值状态恒为 0。仅明细查询填。 */
    private Integer itemIndex;

    /** 该会话/该槽位的 state_data 字节数。 */
    private Long bytes;

    /** 行数。仅「按表统计」与「按会话聚合」两种查询填；明细查询不填（它一行就是一行）。 */
    private Long rows;

    /** 乐观锁版本号。 */
    private Long version;

    /** 首次写入时间（ISO-8601）。仅明细查询填。 */
    private String createdAt;

    /** 最近写入时间（ISO-8601）。所有查询都填。 */
    private String updatedAt;
}
