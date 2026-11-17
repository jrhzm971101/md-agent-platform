package com.md.agentplatform.server.tool.git;

import com.md.agentplatform.server.config.GitlabProperties;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API 工作区管理器的纯本地单元测试。
 *
 * <p>不访问 GitLab，不依赖 JGit：只验证目录命名、URL 编码、ZIP 安全解压和清单读取。
 */
class GitlabWorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void safeNameFlattensBranchAndRemovesIllegalChars() {
        assertEquals("MTN-Feature-CDI", GitlabWorkspaceManager.safeName("MTN-Feature/CDI"));
        assertEquals("repo", GitlabWorkspaceManager.safeName("  "));
        assertEquals("a-b-c", GitlabWorkspaceManager.safeName("a\\b:c"));
        assertTrue(GitlabWorkspaceManager.safeName("x".repeat(200)).length() <= 120);
    }

    @Test
    void projectAndRefEncodingKeepsSlashAsEscapedValue() {
        assertEquals("1367", GitlabWorkspaceManager.encodeProject("1367"));
        assertEquals("mtn%2Flms", GitlabWorkspaceManager.encodeProject("mtn/lms"));
        assertEquals("MTN-Feature%2FCameroon",
                GitlabWorkspaceManager.urlEncode("MTN-Feature/Cameroon"));
        assertEquals("ac26ce73", GitlabWorkspaceManager.shortHash(
                "ac26ce732ff86199a6c2c4fce1b37c027797b039"));
    }

    @Test
    void extractZipStripsSingleArchiveRoot() throws Exception {
        Path zip = tempDir.resolve("repo.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            addEntry(out, "lms-master-abc/src/App.java", "class App {}\n");
            addEntry(out, "lms-master-abc/pom.xml", "<project/>\n");
        }

        Path target = tempDir.resolve("workspace");
        Files.createDirectories(target);
        GitlabWorkspaceManager.extractZip(zip, target);

        assertEquals("class App {}\n",
                Files.readString(target.resolve("src/App.java"), StandardCharsets.UTF_8));
        assertTrue(Files.exists(target.resolve("pom.xml")));
    }

    @Test
    void extractZipRejectsPathTraversal() throws Exception {
        Path zip = tempDir.resolve("bad.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            addEntry(out, "root/../../evil.txt", "bad");
        }
        Path target = tempDir.resolve("bad-workspace");
        Files.createDirectories(target);

        assertThrows(RuntimeException.class,
                () -> GitlabWorkspaceManager.extractZip(zip, target));
        assertTrue(!Files.exists(tempDir.resolve("evil.txt")));
    }

    @Test
    void readManifestParsesWorkspaceMetadata() throws Exception {
        Path workspace = tempDir.resolve("lms@master");
        Files.createDirectories(workspace);
        String json = """
                {
                  "version": "1",
                  "projectId": 1367,
                  "projectPath": "mtn/lms",
                  "alias": "lms",
                  "branch": "master",
                  "commitSha": "ac26ce732ff86199a6c2c4fce1b37c027797b039",
                  "commitDate": "2025-09-09T09:31:42Z",
                  "refType": "branch",
                  "kind": "workspace",
                  "fetchedAt": "2026-11-04T10:00:00Z"
                }
                """;
        Files.writeString(workspace.resolve(GitlabWorkspaceManager.MANIFEST_FILE),
                json, StandardCharsets.UTF_8);

        GitlabWorkspaceManager.WorkspaceManifest manifest =
                new GitlabWorkspaceManager().readManifest(workspace);

        assertNotNull(manifest);
        assertEquals(1367, manifest.projectId());
        assertEquals("mtn/lms", manifest.projectPath());
        assertEquals("lms", manifest.alias());
        assertEquals("master", manifest.branch());
        assertEquals("workspace", manifest.kind());
    }

    @Test
    void workspacePathUsesRepoThenBranchLayout() throws Exception {
        GitlabProperties properties = new GitlabProperties();
        properties.setBaseDir(tempDir.toString());
        GitlabWorkspaceManager manager = new GitlabWorkspaceManager();
        Field field = GitlabWorkspaceManager.class.getDeclaredField("gitlabProperties");
        field.setAccessible(true);
        field.set(manager, properties);

        Path repoRoot = manager.repoRoot("lms");
        assertEquals(tempDir.resolve("lms").toAbsolutePath().normalize(), repoRoot);
        assertEquals(repoRoot.resolve("master"),
                manager.resolveWorkspacePath("lms/master"));
        assertEquals(repoRoot.resolve("MTN-Feature-Cameroon"),
                manager.resolveWorkspacePath("lms/MTN-Feature-Cameroon"));
    }

    @Test
    void deleteRecursivelyRemovesNestedFiles() throws Exception {
        Path nested = tempDir.resolve("nested/a/b");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("f.txt"), "x");
        GitlabWorkspaceManager.deleteRecursively(tempDir.resolve("nested"));
        assertTrue(!Files.exists(tempDir.resolve("nested")));
    }

    private static void addEntry(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
    @Test
    void aliasesAreScopedByEnvironment() throws Exception {
        GitlabProperties properties = new GitlabProperties();
        properties.setBaseDir(tempDir.toString());
        GitlabWorkspaceManager manager = new GitlabWorkspaceManager();
        setField(manager, "gitlabProperties", properties);

        GitlabWorkspaceManager.Project project = new GitlabWorkspaceManager.Project(
                1367, "mtn/lms", "master",
                "https://git.example.com/mtn/lms.git", "https://git.example.com/mtn/lms");
        manager.registerAlias("env-a", "lms", project);

        assertNotNull(manager.lookupAlias("env-a", "lms"));
        assertNull(manager.lookupAlias("env-b", "lms"));
    }

    @Test
    void listWorkspacesHidesSnapshotsFromAnotherEnvironment() throws Exception {
        GitlabProperties properties = new GitlabProperties();
        properties.setBaseDir(tempDir.toString());
        GitlabWorkspaceManager manager = new GitlabWorkspaceManager();
        setField(manager, "gitlabProperties", properties);

        Path branchDir = tempDir.resolve("lms/master");
        Files.createDirectories(branchDir);
        String json = """
                {
                  "version": "3",
                  "environment": "env-a",
                  "projectId": 1367,
                  "projectPath": "mtn/lms",
                  "alias": "lms",
                  "branch": "master",
                  "commitSha": "ac26ce732ff86199a6c2c4fce1b37c027797b039",
                  "kind": "base"
                }
                """;
        Files.writeString(branchDir.resolve(GitlabWorkspaceManager.MANIFEST_FILE), json,
                StandardCharsets.UTF_8);
        GitlabWorkspaceManager.Project project = new GitlabWorkspaceManager.Project(
                1367, "mtn/lms", "master",
                "https://git.example.com/mtn/lms.git", "https://git.example.com/mtn/lms");

        assertEquals(1, manager.listWorkspaces("env-a", project).size());
        assertTrue(manager.listWorkspaces("env-b", project).isEmpty());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
