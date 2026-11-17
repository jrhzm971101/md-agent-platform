package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MR 列表工具测试 —— 覆盖输出映射（时间本地化）与人员匹配、按天归桶。
 *
 * <p>用例结构取自真实 GitLab（11.5）响应。
 */
class GitlabMergeRequestsTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Test
    void toMergeRequestMapsCoreFieldsInLocalTime() {
        ObjectNode mr = GitlabMergeRequests.toMergeRequest(JsonUtil.parse("""
                {"id":1101,"iid":57,"project_id":1367,"title":"Mtn feature/ghana merge ug2.1 offline",
                 "state":"merged","created_at":"2026-09-08T10:00:00.000Z","updated_at":"2026-09-09T03:32:58.000Z",
                 "merged_at":"2026-09-09T03:32:58.000Z","closed_at":null,
                 "author":{"username":"xumaoxin","name":"moshin"},
                 "assignee":{"username":"jinran","name":"金冉"},
                 "merged_by":{"username":"xumaoxin","name":"moshin"},
                 "source_branch":"MTN-Feature/Ghana-merge-ug2.1-offline",
                 "target_branch":"MTN-Feature/Ghana",
                 "merge_commit_sha":"0592736b4689b6ed4a24082b3f6cf000e38928cb",
                 "labels":["feature","ghana"],"work_in_progress":false,"user_notes_count":3,
                 "web_url":"http://git.huojintech.com/mtn/lms/merge_requests/57"}
                """), BEIJING);

        assertEquals(57, mr.path("iid").asInt());
        assertEquals(1367, mr.path("projectId").asInt());
        assertEquals("merged", mr.path("state").asText());
        assertEquals("xumaoxin", mr.path("author").asText());
        assertEquals("moshin", mr.path("authorName").asText());
        assertEquals("jinran", mr.path("assignee").asText());
        assertEquals("xumaoxin", mr.path("mergedBy").asText());
        assertEquals("2026-09-09 11:32:58", mr.path("mergedAt").asText(), "UTC 时间要转成本地时区展示");
        assertEquals("2026-09-08 18:00:00", mr.path("createdAt").asText());
        assertFalse(mr.has("closedAt"), "未关闭的 MR 不输出关闭时间");
        assertEquals("0592736b", mr.path("mergeCommitSha").asText());
        assertEquals("MTN-Feature/Ghana", mr.path("targetBranch").asText());
        assertEquals(2, mr.path("labels").size());
        assertEquals(3, mr.path("userNotesCount").asInt());
    }

    @Test
    void matchesPersonByUsernameOrDisplayName() {
        assertTrue(GitlabMergeRequests.matchesPerson("XUMAOXIN", "xumaoxin", "moshin"));
        assertTrue(GitlabMergeRequests.matchesPerson("mosh", "xumaoxin", "moshin"));
        assertTrue(GitlabMergeRequests.matchesPerson("金冉", "jinran", "金冉"));
        assertFalse(GitlabMergeRequests.matchesPerson("wangpeicai", "xumaoxin", "moshin"));
    }

    @Test
    void dayOfUsesLocalZone() {
        assertEquals("2026-09-09", GitlabMergeRequests.dayOf("2026-09-09T03:32:58.000Z", BEIJING));
        // UTC 9/8 20:00 = 北京 9/9 04:00，按本地日期应归到 9/9
        assertEquals("2026-09-09", GitlabMergeRequests.dayOf("2026-09-08T20:00:00.000Z", BEIJING));
        assertEquals("", GitlabMergeRequests.dayOf(null, BEIJING));
        assertEquals("", GitlabMergeRequests.dayOf("not-a-time", BEIJING));
    }

    @Test
    void shortShaIsIdempotentForShortValues() {
        assertEquals("0592736b", GitlabMergeRequests.shortSha("0592736b4689b6ed4a24082b3f6cf000e38928cb"));
        assertEquals("abc", GitlabMergeRequests.shortSha("abc"));
        assertEquals("", GitlabMergeRequests.shortSha(""));
    }
}