package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * GitLab 项目事件工具测试 —— 覆盖两处最容易出错的纯逻辑：
 * 本地时间窗口 → GitLab UTC 日期参数的换算，以及事件归类。
 *
 * <p>用例数据取自真实 GitLab（11.5）响应，避免自造结构导致的误判。
 */
class GitlabProjectEventsTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private static Date beijing(int year, int month, int day, int hour, int minute, int second) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(BEIJING).toInstant());
    }

    private static ObjectNode event(String json, ZoneId zone) {
        return GitlabProjectEvents.toEvent(JsonUtil.parse(json), zone);
    }

    // ==================== 时间窗口换算 ====================

    @Test
    void apiWindowCoversBeijingDayAcrossTwoUtcDays() {
        // 北京 9/11 一整天 = UTC 9/10 16:00 ~ 9/11 15:59，横跨两个 UTC 自然日
        String[] window = GitlabProjectEvents.apiDateWindow(
                beijing(2026, 9, 11, 0, 0, 0), beijing(2026, 9, 11, 23, 59, 59));

        assertEquals("2026-09-09", window[0], "after 需再往前放宽一天");
        assertEquals("2026-09-12", window[1], "before 需再往后放宽一天");
    }

    @Test
    void apiWindowForUtcAlignedDay() {
        // 单一 UTC 自然日：UTC 9/11 全天
        String[] window = GitlabProjectEvents.apiDateWindow(
                Date.from(LocalDateTime.of(2026, 9, 11, 0, 0).atZone(ZoneId.of("UTC")).toInstant()),
                Date.from(LocalDateTime.of(2026, 9, 11, 23, 59, 59).atZone(ZoneId.of("UTC")).toInstant()));

        assertEquals("2026-09-10", window[0]);
        assertEquals("2026-09-12", window[1]);
    }

    // ==================== 项目标识编码 ====================

    @Test
    void encodeProjectAcceptsIdAndPath() {
        assertEquals("1367", GitlabProjectEvents.encodeProject("1367"));
        assertEquals("1367", GitlabProjectEvents.encodeProject(" 1367 "));
        assertEquals("mtn%2Flms", GitlabProjectEvents.encodeProject("mtn/lms"));
        assertEquals("mtn%2Flms", GitlabProjectEvents.encodeProject("mtn%2Flms"), "已编码的入参不应被二次编码");
    }

    // ==================== 事件归类：push 类 ====================

    @Test
    void branchCreatedEvent() {
        ObjectNode ev = event("""
                {"action_name":"pushed new","target_type":null,"created_at":"2026-09-10T09:42:15.391Z",
                 "author":{"name":"马瑞珩","username":"maruiheng"},"author_username":"maruiheng",
                 "push_data":{"commit_count":1641,"action":"created","ref_type":"branch",
                              "ref":"MTN-Feature/CongoB-Feature/BL-20260910",
                              "commit_to":"12d31c0cefba98bc50126a59a62d0e37d40422a8",
                              "commit_title":"[feat] 打包 build:only"}}
                """, BEIJING);

        assertEquals("BRANCH_CREATED", ev.path("action").asText());
        assertEquals("新建分支", ev.path("actionDesc").asText());
        assertEquals("MTN-Feature/CongoB-Feature/BL-20260910", ev.path("targetName").asText());
        assertEquals("Branch", ev.path("targetType").asText());
        assertEquals("maruiheng", ev.path("operator").asText());
        assertEquals("马瑞珩", ev.path("operatorName").asText());
        // UTC 09:42 → 北京 17:42
        assertEquals("2026-09-10 17:42:15", ev.path("time").asText());
        assertEquals("1641", ev.path("commitCount").asText());
        assertEquals("12d31c0c", ev.path("commitTo").asText(), "commit hash 取前 8 位");
        assertEquals("新建分支 MTN-Feature/CongoB-Feature/BL-20260910", ev.path("detail").asText());
    }

    @Test
    void pushedEventCarriesCommitCount() {
        ObjectNode ev = event("""
                {"action_name":"pushed to","created_at":"2026-09-10T10:11:55.304Z",
                 "author":{"name":"even","username":"wangpeicai"},"author_username":"wangpeicai",
                 "push_data":{"commit_count":1,"action":"pushed","ref_type":"branch",
                              "ref":"MTN-Feature/daily-interest-update-20260907",
                              "commit_to":"ff2113be21569fcdf729723f8e85c3aed33c72d4"}}
                """, BEIJING);

        assertEquals("PUSHED", ev.path("action").asText());
        assertEquals("推送代码", ev.path("actionDesc").asText());
        assertEquals("MTN-Feature/daily-interest-update-20260907", ev.path("targetName").asText());
        assertEquals("Branch", ev.path("targetType").asText());
        assertEquals("推送代码到分支 MTN-Feature/daily-interest-update-20260907（1 个提交）",
                ev.path("detail").asText());
    }

    @Test
    void branchDeletedEvent() {
        ObjectNode ev = event("""
                {"action_name":"deleted","created_at":"2026-09-10T10:11:55.304Z",
                 "author":{"name":"even","username":"wangpeicai"},"author_username":"wangpeicai",
                 "push_data":{"commit_count":0,"action":"removed","ref_type":"branch",
                              "ref":"MTN-Feature/tmp-20260801"}}
                """, BEIJING);

        assertEquals("BRANCH_DELETED", ev.path("action").asText());
        assertEquals("删除分支", ev.path("actionDesc").asText());
        assertEquals("Branch", ev.path("targetType").asText());
    }

    @Test
    void tagCreatedEvent() {
        ObjectNode ev = event("""
                {"action_name":"pushed new","created_at":"2026-09-10T10:11:55.304Z",
                 "author":{"name":"even","username":"wangpeicai"},"author_username":"wangpeicai",
                 "push_data":{"commit_count":1,"action":"created","ref_type":"tag","ref":"v1.0.0"}}
                """, BEIJING);

        assertEquals("TAG_CREATED", ev.path("action").asText());
        assertEquals("Tag", ev.path("targetType").asText());
        assertEquals("新建标签 v1.0.0", ev.path("detail").asText());
    }

    // ==================== 事件归类：非 push 类 ====================

    @Test
    void mergeRequestMergedEvent() {
        // GitLab 11.5 的 MR 合并事件 action_name 是 accepted，且不带 push_data
        ObjectNode ev = event("""
                {"action_name":"accepted","target_iid":184,"target_type":"MergeRequest",
                 "target_title":"Mtn feature/20260821 跑批运营名单需求","created_at":"2026-09-10T07:02:48.000Z",
                 "author":{"name":"moshin","username":"xumaoxin"},"author_username":"xumaoxin"}
                """, BEIJING);

        assertEquals("MR_MERGED", ev.path("action").asText());
        assertEquals("合并合并请求", ev.path("actionDesc").asText());
        assertEquals("MergeRequest", ev.path("targetType").asText());
        assertEquals("Mtn feature/20260821 跑批运营名单需求", ev.path("targetName").asText());
        assertEquals("184", ev.path("targetIid").asText());
        assertEquals("2026-09-10 15:02:48", ev.path("time").asText());
        assertEquals("合并合并请求 !184：Mtn feature/20260821 跑批运营名单需求", ev.path("detail").asText());
        assertFalse(ev.has("ref"), "非 push 事件不应有 ref 字段");
    }

    @Test
    void mergeRequestOpenedEvent() {
        ObjectNode ev = event("""
                {"action_name":"opened","target_iid":185,"target_type":"MergeRequest",
                 "target_title":"[feature][mtn]：跑批名单运营需求","created_at":"2026-09-11T10:00:00.000Z",
                 "author":{"name":"杨治","username":"yangzhi"},"author_username":"yangzhi"}
                """, BEIJING);

        assertEquals("MR_OPENED", ev.path("action").asText());
        assertEquals("新建合并请求", ev.path("actionDesc").asText());
    }

    @Test
    void issueAndCommentEvents() {
        ObjectNode issue = event("""
                {"action_name":"opened","target_iid":12,"target_type":"Issue","target_title":"线上问题",
                 "created_at":"2026-09-11T10:00:00.000Z",
                 "author":{"name":"杨治","username":"yangzhi"},"author_username":"yangzhi"}
                """, BEIJING);
        assertEquals("ISSUE_OPENED", issue.path("action").asText());
        assertEquals("新建议题 #12：线上问题", issue.path("detail").asText());

        ObjectNode comment = event("""
                {"action_name":"commented on","target_iid":12,"target_type":"Note","target_title":null,
                 "created_at":"2026-09-11T10:00:00.000Z",
                 "author":{"name":"杨治","username":"yangzhi"},"author_username":"yangzhi"}
                """, BEIJING);
        assertEquals("COMMENTED", comment.path("action").asText());
    }

    @Test
    void unknownActionNameFallsBackGracefully() {
        ObjectNode ev = event("""
                {"action_name":"approved something","target_type":null,"created_at":"2026-09-11T10:00:00.000Z",
                 "author":{"name":"x","username":"u"},"author_username":"u"}
                """, BEIJING);

        assertEquals("APPROVED_SOMETHING", ev.path("action").asText());
        assertEquals("approved something", ev.path("actionDesc").asText());
        assertEquals("approved something", ev.path("detail").asText());
    }
}
