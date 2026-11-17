package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目提交工具测试 —— 覆盖提交映射（stats / 提交信息字数 / 本地时间）、人员与关键字过滤。
 */
class GitlabProjectCommitsTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Test
    void toCommitMapsStatsAndMessageChars() {
        ObjectNode commit = GitlabProjectCommits.toCommit(JsonUtil.parse("""
                {"id":"89b0418843526c93e8750157d545799e3aa062ed","short_id":"89b04188",
                 "title":"[fix] 客户关怀页面新增利息字段 build:only",
                 "message":"[fix] 客户关怀页面新增利息字段 build:only\\n",
                 "author_name":"RuihengMa","author_email":"maruiheng@mindigitalgroup.com",
                 "authored_date":"2026-09-11T10:14:54.000Z","committed_date":"2026-09-11T10:14:54.000Z",
                 "committer_name":"RuihengMa","parent_ids":["2cb54b7b45398447050e5781998f553d5ab21f37"],
                 "stats":{"additions":15,"deletions":1,"total":16}}
                """), BEIJING, true);

        assertEquals("89b0418843526c93e8750157d545799e3aa062ed", commit.path("id").asText());
        assertEquals("89b04188", commit.path("shortId").asText());
        assertEquals(15, commit.path("additions").asInt());
        assertEquals(1, commit.path("deletions").asInt());
        assertEquals(16, commit.path("total").asInt());
        assertEquals("2026-09-11 18:14:54", commit.path("commitDate").asText(), "UTC 时间要转本地时区");
        assertEquals("[fix] 客户关怀页面新增利息字段 build:only".length(), commit.path("messageChars").asInt(),
                "提交信息字数不含首尾空白");
        assertEquals(1, commit.path("parentCount").asInt());
        assertFalse(commit.has("committerName"), "提交人与作者相同时不重复输出");
    }

    @Test
    void toCommitWithoutStatsOmitsLineCounts() {
        ObjectNode commit = GitlabProjectCommits.toCommit(JsonUtil.parse("""
                {"id":"abc12345","title":"t","message":"t\\n","author_name":"zhi.yang",
                 "committer_name":"someone-else"}
                """), BEIJING, false);

        assertFalse(commit.has("additions"));
        assertFalse(commit.has("deletions"));
        assertEquals("someone-else", commit.path("committerName").asText(), "提交人与作者不同则输出提交人");
        assertFalse(commit.path("parentCount").isInt(), "没有 parent_ids 时不输出父提交数");
    }

    @Test
    void matchesPersonCoversNameEmailAndCommitter() {
        assertTrue(GitlabProjectCommits.matchesPerson("maruiheng", "RuihengMa",
                "maruiheng@mindigitalgroup.com", "RuihengMa"));
        assertTrue(GitlabProjectCommits.matchesPerson("MARUIHENG@mindigitalgroup.com", "RuihengMa",
                "maruiheng@mindigitalgroup.com", "RuihengMa"));
        assertTrue(GitlabProjectCommits.matchesPerson("ruiheng", "RuihengMa", "", ""));
        assertFalse(GitlabProjectCommits.matchesPerson("wangpeicai", "RuihengMa",
                "maruiheng@mindigitalgroup.com", "RuihengMa"));
    }

    @Test
    void matchesKeywordLooksAtTitleAndMessage() {
        ObjectNode commit = (ObjectNode) JsonUtil.parse("""
                {"title":"[fix] 日终计息定时任务逻辑优化 build:only","message":"[fix] 日终计息定时任务逻辑优化 build:only\\n\\n详见 #123"}
                """);

        assertTrue(GitlabProjectCommits.matchesKeyword(commit, "计息"));
        assertTrue(GitlabProjectCommits.matchesKeyword(commit, "BUILD:ONLY"));
        assertTrue(GitlabProjectCommits.matchesKeyword(commit, "#123"), "关键字也要能在完整 message 里命中");
        assertTrue(GitlabProjectCommits.matchesKeyword(commit, "  "), "空关键字不过滤");
        assertFalse(GitlabProjectCommits.matchesKeyword(commit, "客户关怀"));
    }
}