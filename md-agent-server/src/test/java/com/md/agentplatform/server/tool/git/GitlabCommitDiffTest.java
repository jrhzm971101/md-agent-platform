package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提交改动工具测试 —— 覆盖区间/单次两种模式的解析、文件过滤与截断。
 */
class GitlabCommitDiffTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private static final String DIFFS = """
            [{"old_path":"lms-business/a.java","new_path":"lms-business/a.java",
              "new_file":false,"deleted_file":false,"renamed_file":false,
              "diff":"--- a/a.java\\n+++ b/a.java\\n@@\\n-old\\n+new\\n+new2\\n"},
             {"old_path":"docs/readme.md","new_path":"docs/readme.md",
              "new_file":false,"deleted_file":false,"renamed_file":false,
              "diff":"@@\\n+line\\n"}]
            """;

    @Test
    void buildFilesSumsAllMatchesButLimitsList() {
        ObjectNode files = GitlabCommitDiff.buildFiles(JsonUtil.parse(DIFFS), "lms-business", 5, true, 100);

        assertEquals(1, files.path("total").asInt());
        assertEquals(1, files.path("returned").asInt());
        assertFalse(files.path("truncated").asBoolean());
        assertEquals(2, files.path("additions").asInt());
        assertEquals(1, files.path("deletions").asInt());
    }

    @Test
    void buildFilesTruncatesWhenExceedingMaxFiles() {
        ObjectNode files = GitlabCommitDiff.buildFiles(JsonUtil.parse(DIFFS), null, 1, false, 100);

        assertEquals(2, files.path("total").asInt());
        assertEquals(1, files.path("returned").asInt());
        assertTrue(files.path("truncated").asBoolean());
        assertEquals(3, files.path("additions").asInt(), "行数按全部命中文件累加");
        assertEquals(1, files.path("deletions").asInt());
        assertFalse(files.path("list").get(0).has("diff"), "includeDiff=false 时不返回 diff");
    }

    @Test
    void buildFilesHandlesEmptyOrMissingArray() {
        ObjectNode files = GitlabCommitDiff.buildFiles(null, null, 10, true, 100);

        assertEquals(0, files.path("total").asInt());
        assertEquals(0, files.path("returned").asInt());
        assertEquals(0, files.path("list").size());
    }

    @Test
    void toCommitInfoCarriesStatsAndPipelineStatus() {
        ObjectNode info = GitlabCommitDiff.toCommitInfo(JsonUtil.parse("""
                {"id":"89b0418843526c93e8750157d545799e3aa062ed","title":"[fix] 客户关怀页面新增利息字段 build:only",
                 "message":"[fix] 客户关怀页面新增利息字段 build:only\\n","author_name":"RuihengMa",
                 "author_email":"maruiheng@mindigitalgroup.com","authored_date":"2026-09-11T10:14:54.000Z",
                 "committed_date":"2026-09-11T10:14:54.000Z","stats":{"additions":15,"deletions":1,"total":16},
                 "status":"success","last_pipeline":{"id":104704,"status":"success"}}
                """), BEIJING);

        assertEquals("89b04188", info.path("shortId").asText());
        assertEquals(15, info.path("additions").asInt());
        assertEquals(16, info.path("total").asInt());
        assertEquals("2026-09-11 18:14:54", info.path("commitDate").asText());
        assertEquals("success", info.path("pipelineStatus").asText());
    }

    @Test
    void toCommitBriefKeepsCompareEssentials() {
        ObjectNode brief = GitlabCommitDiff.toCommitBrief(JsonUtil.parse("""
                {"id":"2cb54b7b45398447050e5781998f553d5ab21f37","title":"[fix] 日终计息定时任务逻辑优化 build:only",
                 "author_name":"RuihengMa","committed_date":"2026-09-11T08:12:12.000Z",
                 "message":"[fix] 日终计息定时任务逻辑优化 build:only\\n","stats":{"additions":321,"deletions":90}}
                """), BEIJING);

        assertEquals("2cb54b7b", brief.path("shortId").asText());
        assertEquals("RuihengMa", brief.path("authorName").asText());
        assertEquals("2026-09-11 16:12:12", brief.path("commitDate").asText());
        assertFalse(brief.has("message"), "区间模式下只要精简信息");
    }

    @Test
    void matchesPathFilterIsCaseInsensitiveOnBothPaths() {
        assertTrue(GitlabCommitDiff.matchesPathFilter("A/B.java", "a/b.java", "b.java"));
        assertTrue(GitlabCommitDiff.matchesPathFilter("new/name.java", "old/name.java", "old"));
        assertTrue(GitlabCommitDiff.matchesPathFilter("x.java", "x.java", null));
        assertFalse(GitlabCommitDiff.matchesPathFilter("x.java", "y.java", "mapper"));
    }

    @Test
    void diffStatCountsChangedLinesOnly() {
        int[] stat = GitlabCommitDiff.diffStat("--- a/x\n+++ b/x\n@@\n context\n-old\n+new\n");
        assertEquals(1, stat[0]);
        assertEquals(1, stat[1]);
    }
}