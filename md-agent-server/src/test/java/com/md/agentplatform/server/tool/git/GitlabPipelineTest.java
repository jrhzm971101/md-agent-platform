package com.md.agentplatform.server.tool.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 流水线 ID 解析测试：镜像 tag / pipeline URL / 纯数字三种入口。
 */
class GitlabPipelineTest {

    @Test
    void parsesNumericPipelineId() {
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId("109478"));
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId(" 109478 "));
    }

    @Test
    void parsesPipelineUrl() {
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId(
                "https://git.huojintech.com/mtn/lms/pipelines/109478"));
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId(
                "https://git.huojintech.com/mtn/lms/pipelines/109478/jobs"));
    }

    @Test
    void parsesImageTagWithRegistryPort() {
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId(
                "harbor.example.com:5000/mtn/lms:109478"));
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId("lms:v109478"));
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId("lms:release-109478"));
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId("lms:109478-1"));
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId("lms:build-109478-1"));
        assertEquals(109478, GitlabWorkspaceManager.parsePipelineId(
                "lms:109478@sha256:abcdef"));
    }

    @Test
    void rejectsImageWithoutUsableTag() {
        assertThrows(IllegalArgumentException.class,
                () -> GitlabWorkspaceManager.parsePipelineId("harbor.example.com/mtn/lms"));
        assertThrows(IllegalArgumentException.class,
                () -> GitlabWorkspaceManager.parsePipelineId("harbor.example.com:5000/mtn/lms"));
        assertThrows(IllegalArgumentException.class,
                () -> GitlabWorkspaceManager.parsePipelineId("lms@sha256:abcdef"));
        assertThrows(IllegalArgumentException.class,
                () -> GitlabWorkspaceManager.parsePipelineId("lms:latest"));
        assertThrows(IllegalArgumentException.class,
                () -> GitlabWorkspaceManager.parsePipelineId(" "));
    }
}
