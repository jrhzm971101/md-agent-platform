package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单个 MR 详情工具测试 —— 覆盖 include 解析、diff 行数统计、改动过滤与截断。
 */
class GitlabMergeRequestDetailTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Test
    void parseIncludeDefaultsToChanges() {
        assertEquals(List.of("changes"), GitlabMergeRequestDetail.parseInclude(null));
        assertEquals(List.of("changes"), GitlabMergeRequestDetail.parseInclude("   "));
        assertEquals(List.of("changes"), GitlabMergeRequestDetail.parseInclude("unknown"));
        assertEquals(List.of("changes", "commits", "versions"), GitlabMergeRequestDetail.parseInclude("all"));
        assertEquals(List.of("commits", "versions"),
                GitlabMergeRequestDetail.parseInclude("commits,VERSIONS,commits"));
    }

    @Test
    void diffStatIgnoresFileHeaders() {
        String diff = "--- a/x\n+++ b/x\n@@ -1,2 +1,3 @@\n context\n-old\n+new\n+new2\n";
        int[] stat = GitlabMergeRequestDetail.diffStat(diff);
        assertEquals(2, stat[0], "只统计以 + 开头且不是 +++ 的行");
        assertEquals(1, stat[1], "只统计以 - 开头且不是 --- 的行");
    }

    @Test
    void diffStatHandlesEmptyDiff() {
        int[] stat = GitlabMergeRequestDetail.diffStat("");
        assertEquals(0, stat[0]);
        assertEquals(0, stat[1]);
    }

    @Test
    void buildChangesFiltersTruncatesAndSumsAllMatches() {
        ObjectNode mr = (ObjectNode) JsonUtil.parse("""
                {"iid":57,"changes_count":"3","changes":[
                  {"old_path":"lms-domain/src/main/resources/mapper/mysql/BulletLoanCareQueryMapper.xml",
                   "new_path":"lms-domain/src/main/resources/mapper/mysql/BulletLoanCareQueryMapper.xml",
                   "new_file":false,"deleted_file":false,"renamed_file":false,
                   "diff":"--- a/x\\n+++ b/x\\n@@\\n-old\\n+new\\n+new2\\n"},
                  {"old_path":"lms-business/src/main/java/BulletLoanCareServiceImpl.java",
                   "new_path":"lms-business/src/main/java/BulletLoanCareServiceImpl.java",
                   "new_file":false,"deleted_file":false,"renamed_file":false,
                   "diff":"@@\\n-old\\n+new\\n"},
                  {"old_path":"lms-domain/src/main/resources/mapper/mysql/LoanMapper.xml",
                   "new_path":"lms-domain/src/main/resources/mapper/mysql/LoanMapper.xml",
                   "new_file":false,"deleted_file":false,"renamed_file":false,
                   "diff":"@@\\n+added\\n"}]}
                """);

        ObjectNode changes = GitlabMergeRequestDetail.buildChanges(mr, "mapper", 1, true, 100);

        assertEquals("3", changes.path("changesCount").asText());
        assertEquals(2, changes.path("total").asInt(), "只统计路径命中关键字(含旧路径)的文件");
        assertEquals(1, changes.path("returned").asInt());
        assertTrue(changes.path("truncated").asBoolean(), "命中文件多于 maxFiles 时应标记截断");
        assertEquals(3, changes.path("additions").asInt(), "行数按全部命中文件累加");
        assertEquals(1, changes.path("deletions").asInt());
        assertEquals(1, changes.path("files").size());
    }

    @Test
    void buildChangesCanOmitDiffText() {
        ObjectNode mr = (ObjectNode) JsonUtil.parse("""
                {"iid":1,"changes":[{"old_path":"a.txt","new_path":"a.txt",
                 "new_file":true,"deleted_file":false,"renamed_file":false,
                 "diff":"@@\\n+hello\\n"}]}
                """);

        ObjectNode changes = GitlabMergeRequestDetail.buildChanges(mr, null, 10, false, 100);

        assertEquals(1, changes.path("returned").asInt());
        assertFalse(changes.path("files").get(0).has("diff"), "includeDiff=false 时不返回 diff 文本");
        assertTrue(changes.path("files").get(0).path("newFile").asBoolean());
    }

    @Test
    void toDiffFileTruncatesLongDiff() {
        ObjectNode change = (ObjectNode) JsonUtil.parse("""
                {"old_path":"a.txt","new_path":"b.txt","new_file":false,"deleted_file":false,
                 "renamed_file":true,"diff":"@@\\n+0123456789\\n"}
                """);

        ObjectNode file = GitlabMergeRequestDetail.toDiffFile(change, new int[]{1, 0}, true, 5);

        assertEquals("b.txt", file.path("newPath").asText());
        assertEquals("a.txt", file.path("oldPath").asText(), "重命名时保留旧路径");
        assertTrue(file.path("renamedFile").asBoolean());
        assertEquals(1, file.path("additions").asInt());
        assertTrue(file.path("diff").asText().startsWith("@@\n+"), "截断后仍保留原始前缀");
        assertTrue(file.path("diff").asText().contains("已截断"));
    }

    @Test
    void toVersionShortensShas() {
        ObjectNode version = GitlabMergeRequestDetail.toVersion(JsonUtil.parse("""
                {"id":24935,"head_commit_sha":"66083732abcdef0123456789","base_commit_sha":"9fab1e2199caf63bd1dc6e761f5a421ee1d4e467",
                 "start_commit_sha":"9fab1e2199caf63bd1dc6e761f5a421ee1d4e467",
                 "created_at":"2026-09-09T03:32:58.000Z","state":"collected","real_size":"21"}
                """), BEIJING);

        assertEquals("24935", version.path("id").asText());
        assertEquals("66083732", version.path("headCommitSha").asText());
        assertEquals("9fab1e21", version.path("baseCommitSha").asText());
        assertEquals("2026-09-09 11:32:58", version.path("createdAt").asText());
        assertEquals("21", version.path("realSize").asText());
    }
}