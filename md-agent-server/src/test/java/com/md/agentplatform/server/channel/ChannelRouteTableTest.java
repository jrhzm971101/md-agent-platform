package com.md.agentplatform.server.channel;

import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 路由表的<b>写入语义</b>：单渠道热生效不许把别的渠道一起抹掉。
 *
 * <h2>为什么专门为三个 setter 写一个测试类</h2>
 * 线上形态是：企微消息进来后报 {@code Agent 不存在: main}。追下去发现长连接是活的、
 * 库里也有这个渠道（{@code default_agent_id} 配了、已发布），但路由表里查不到这条渠道 ——
 * 于是 {@link ChannelRouteTable#route} 退回一个空配置（无绑定规则、{@code defaultAgentId} 为 null），
 * 再叠上网关没设主 agent，就一路掉到官方 {@code ChannelRouter} 构造器里那个字面量兜底 {@code "main"}。
 *
 * <p>根因不在读侧，而在这里：路由表原先<b>只有全量重载（{@code replaceAll}）会写</b>，
 * 而"发布渠道"走的是 {@code ChannelRuntime.apply(channelId)} 这条单渠道的路。
 * 修法就是给单渠道补上 {@link ChannelRouteTable#upsert} / {@link ChannelRouteTable#remove}。
 *
 * <p>而这两个方法最容易写错的地方，恰恰是它们<b>看起来</b>可以偷懒实现成
 * "拼一个单元素列表去调 {@code replaceAll}" —— 那样在单渠道的本地环境测试全绿，
 * 一到多渠道部署就变成"发布 A 渠道，B 渠道的消息开始报 Agent 不存在"。
 * 所以下面第 1、2 个用例就是钉死这件事：<b>写一条，其它条必须原封不动</b>。
 *
 * <p>第 3 个用例反过来钉住 {@code replaceAll} 仍然是"以库为准的全量快照"，
 * 避免有人为了修上面那个 bug 把全量语义也改掉（那会让"库里删了渠道"永远刷不下去）。
 */
class ChannelRouteTableTest {

    /*
     * 路由表只把 AgentGatewayRuntime 用在 route() 上（那才是真正需要查网关的一步）。
     * upsert / remove / replaceAll 是纯内存快照操作，所以这里给一个 mock 就够，
     * 既不必造网关、也顺带声明了"本次测的这几个方法不碰网关"。
     */
    private final ChannelRouteTable table = new ChannelRouteTable(mock(AgentGatewayRuntime.class));

    /**
     * 核心回归：全量装载两个渠道之后，单渠道 upsert 只能覆盖它自己。
     *
     * <p>这正是线上场景的形状 —— 启动时库里还没有 A 渠道（全量装载只装到了 B），
     * 之后新建并发布 A，落到 {@code ChannelRuntime.apply(A)}。
     * 如果 {@code upsert} 借道 {@code replaceAll}，B 会在这里凭空消失。
     */
    @Test
    void upsertOverwritesOnlyItsOwnChannel() {
        table.replaceAll(List.of(entry("wecom_a", "agent_a"), entry("feishu_b", "agent_b")));

        table.upsert(entry("wecom_a", "agent_a_v2"));

        // 写进去的那条按新值生效
        assertThat(table.find("wecom_a")).get()
                .extracting(ChannelRouteTable.CachedChannelRoute::channelId)
                .isEqualTo("wecom_a");
        // 另外一条必须还在 —— 这是本测试存在的全部理由
        assertThat(table.find("feishu_b")).isPresent();
        assertThat(table.all()).hasSize(2);
    }

    /** 路由表原本为空时，单渠道 upsert 也要能把它加进去（不是只能覆盖已有的）。 */
    @Test
    void upsertWorksOnAnEmptyTable() {
        table.upsert(entry("wecom_a", "agent_a"));

        assertThat(table.find("wecom_a")).isPresent();
        assertThat(table.all()).hasSize(1);
    }

    /** 全量装载保持"以库为准"：没有的渠道要被刷掉，否则删除过的渠道会永远留在路由表里。 */
    @Test
    void replaceAllIsStillAFullSnapshot() {
        table.replaceAll(List.of(entry("wecom_a", "agent_a"), entry("feishu_b", "agent_b")));

        table.replaceAll(List.of(entry("feishu_b", "agent_b")));

        assertThat(table.find("wecom_a")).isEmpty();
        assertThat(table.all()).hasSize(1);
    }

    /** 删除渠道：只摘掉自己，且重复调用无害（删除、再重载、再删除都会走到这里）。 */
    @Test
    void removeDropsOnlyItsOwnChannelAndIsIdempotent() {
        table.replaceAll(List.of(entry("wecom_a", "agent_a"), entry("feishu_b", "agent_b")));

        table.remove("wecom_a");
        table.remove("wecom_a");
        table.remove(null);
        table.remove("never_existed");

        assertThat(table.find("wecom_a")).isEmpty();
        assertThat(table.find("feishu_b")).isPresent();
        assertThat(table.all()).hasSize(1);
    }

    /** 快照是读侧无锁的不可变副本：调用方拿到的 list 改不动内部状态。 */
    @Test
    void allReturnsACopyThatCannotMutateTheSnapshot() {
        table.replaceAll(List.of(entry("wecom_a", "agent_a")));

        List<ChannelRouteTable.CachedChannelRoute> snapshot = table.all();
        snapshot.clear();

        assertThat(table.all()).hasSize(1);
    }

    /**
     * 造一条路由缓存。
     *
     * <p>{@code config} 必须是真的 {@link ChannelConfig}（不能为 null）：
     * 写入走的是 {@code Map.copyOf}，它不容忍 null 值 —— 这里刻意不绕过，
     * 让"造了一条残缺数据"在测试阶段就炸出来，而不是等它在路由时才 NPE。
     */
    private static ChannelRouteTable.CachedChannelRoute entry(String channelId, String defaultAgentId) {
        return new ChannelRouteTable.CachedChannelRoute(
                channelId,
                "wecom-bot",
                "bot-" + channelId,
                null,
                ChannelConfig.builder(channelId).defaultAgentId(defaultAgentId).build());
    }
}