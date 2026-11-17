package com.md.agentplatform.server.controlplane.store;

import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.GovernedRow;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 治理字段推进规则的<b>纯单测</b>（不连库）。
 *
 * <h2>为什么这几条规则要单独测</h2>
 * 「新建 / 保存草稿 / 发布 / 回滚」四种操作对 8 个治理字段的改动各不相同，
 * 而它们出错的方式都是<b>静默</b>的：漏改一个字段，表现只是"某张表发布后
 * published_version 还是 null"、"保存草稿后创建时间变成了刚才"，
 * 要等到页面上点开详情才可能注意到，而那时已经分不清是哪一次操作写坏的。
 *
 * <p>把它们固定在单测里，比写在注释里管用：{@link GovernedRow} 是四个实体的公共行为，
 * 改一次影响四张表，正是最需要护栏的地方。
 */
class ControlPlaneGovernanceTest {

    @Test
    void initGovernanceStartsAtVersionOneDraft() {
        PromptEntity prompt = new PromptEntity();
        prompt.setPromptKey("itest-p1");

        prompt.initGovernance("alice", 1_000L);

        assertEquals(GovernedRow.STATUS_DRAFT, prompt.getStatus());
        assertEquals(1, prompt.getVersion().intValue());
        assertNull(prompt.getPublishedVersion(), "没发布过的资源 published_version 必须是 null，而不是 0");
        assertNull(prompt.getPublishedAtMs());
        assertEquals(1_000L, prompt.getCreatedAtMs().longValue());
        assertEquals(1_000L, prompt.getUpdatedAtMs().longValue());
        assertEquals("alice", prompt.getCreatedBy());
        assertEquals("alice", prompt.getUpdatedBy());
    }

    @Test
    void advanceDraftKeepsCreationAndPublicationInfo() {
        PromptEntity existing = new PromptEntity();
        existing.setPromptKey("itest-p1");
        existing.initGovernance("alice", 1_000L);
        existing.markPublished(2_000L, "alice");

        PromptEntity edited = new PromptEntity();
        edited.setPromptKey("itest-p1");
        edited.advanceDraft(existing, "bob", 5_000L);

        assertEquals(2, edited.getVersion().intValue(), "保存草稿必须推进版本号（哪怕内容没变）");
        assertEquals(GovernedRow.STATUS_DRAFT, edited.getStatus(), "保存后回到 DRAFT，发布才生效");
        assertEquals(1, edited.getPublishedVersion().intValue(), "线上仍是 v1，改草稿不该动它");
        assertEquals(2_000L, edited.getPublishedAtMs().longValue());
        assertEquals(1_000L, edited.getCreatedAtMs().longValue(), "创建时刻描述的是这条资源，不随内容改版而变");
        assertEquals("alice", edited.getCreatedBy(), "创建人不该被后来编辑的人覆盖");
        assertEquals(5_000L, edited.getUpdatedAtMs().longValue());
        assertEquals("bob", edited.getUpdatedBy());
    }

    @Test
    void markPublishedStampsVersionWithoutBumpingIt() {
        AgentEntity agent = new AgentEntity();
        agent.setAgentName("itest-a1");
        agent.initGovernance("alice", 1_000L);
        agent.setVersion(7);

        agent.markPublished(3_000L, "bob");

        assertEquals(7, agent.getVersion().intValue(), "发布不是内容变更，不该推进 version");
        assertEquals(7, agent.getPublishedVersion().intValue(), "published_version 指向刚发布的这一版");
        assertEquals(GovernedRow.STATUS_PUBLISHED, agent.getStatus());
        assertEquals(3_000L, agent.getPublishedAtMs().longValue());
        assertEquals(1_000L, agent.getCreatedAtMs().longValue());
        assertEquals("alice", agent.getCreatedBy());
        assertEquals("bob", agent.getUpdatedBy());
    }

    @Test
    void rollbackRunThroughAdvanceDraftLeavesOnlineVersionUntouched() {
        // 回滚复用的就是 advanceDraft（见 MyBatisControlPlaneStore#rollback），
        // 所以这里固定的是"回滚后长什么样"：旧内容 + 新版本号 + DRAFT，且线上那一版不变。
        PromptEntity online = new PromptEntity();
        online.setPromptKey("itest-p1");
        online.initGovernance("alice", 1_000L);
        online.setVersion(4);
        online.markPublished(2_000L, "alice");

        PromptEntity restoredFromSnapshot = new PromptEntity();
        restoredFromSnapshot.setPromptKey("itest-p1");
        restoredFromSnapshot.advanceDraft(online, "carol", 9_000L);

        assertEquals(5, restoredFromSnapshot.getVersion().intValue());
        assertEquals(GovernedRow.STATUS_DRAFT, restoredFromSnapshot.getStatus());
        assertEquals(4, restoredFromSnapshot.getPublishedVersion().intValue(),
                "回滚只准备草稿，线上仍是 v4，要再发布一次才切过去");
    }

    @Test
    void governanceNeverFillsNullableToggles() {
        // 三态开关（enabled、一批 disable-xxx）不属于治理字段：
        // NULL 代表"未设置，用框架默认值"，绝不能被治理逻辑顺手写成 false。
        AgentEntity agent = new AgentEntity();
        agent.setAgentName("itest-a2");

        agent.initGovernance("alice", 1_000L);
        assertNull(agent.getEnabled(), "initGovernance 不该填 enabled —— 它的 null 是有语义的");

        AgentEntity edited = new AgentEntity();
        edited.advanceDraft(agent, "alice", 2_000L);
        assertNull(edited.getEnabled(), "advanceDraft 同样不该填");
    }
}