package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.md.agentplatform.server.config.EnvironmentProperties.Gitlab;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.GitlabProperties;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 GitLab API 全链路测试。
 *
 * <p>默认跳过。设置 GITLAB_LIVE_TEST=true 且提供 GITLAB_TOKEN 后运行：
 * <pre>
 * $env:GITLAB_LIVE_TEST="true"; $env:GITLAB_TOKEN="***"
 * mvn -pl md-agent-server -Dtest=GitlabApiToolsLiveTest test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "GITLAB_LIVE_TEST", matches = "true")
class GitlabApiToolsLiveTest {

    private static final String ENV = "live-test";
    private static final String API_URL = System.getenv().getOrDefault("GITLAB_API_URL",
            "https://git.example.com/api/v4");
    private static final String PROJECT = "1367";

    @TempDir
    Path tempDir;

    private GitlabWorkspaceManager manager;
    private GitlabProperties gitlabProperties;
    private EnvironmentRegistry environmentRegistry;
    private GitlabTokenProvider gitlabTokenProvider;
    private Path baseRoot;

    @BeforeEach
    void setUp() throws Exception {
        String token = System.getenv("GITLAB_TOKEN");
        assertTrue(token != null && !token.isBlank(), "必须设置 GITLAB_TOKEN");

        String configuredBase = System.getenv("GITLAB_LIVE_BASE_DIR");
        Path baseDir = configuredBase == null || configuredBase.isBlank()
                ? tempDir : Path.of(configuredBase).toAbsolutePath().normalize();
        baseRoot = baseDir;

        gitlabProperties = new GitlabProperties();
        gitlabProperties.setBaseDir(baseDir.toString());
        gitlabProperties.setTimeoutMs(30_000);
        gitlabProperties.setArchiveTimeoutMs(300_000);

        Gitlab gitlab = new Gitlab();
        gitlab.setUrl(API_URL);
        gitlab.setToken(token);
        environmentRegistry = mock(EnvironmentRegistry.class);
        when(environmentRegistry.gitlab(ENV)).thenReturn(gitlab);
        gitlabTokenProvider = new GitlabTokenProvider(environmentRegistry, gitlabProperties);

        manager = new GitlabWorkspaceManager();
        set(manager, "gitlabProperties", gitlabProperties);
        set(manager, "gitlabTokenProvider", gitlabTokenProvider);
        manager.init();
    }

    @Test
    void allGitToolsWorkThroughGitLabApi() throws Exception {
        GitClone clone = configured(new GitClone());
        String cloneResult = clone.clone(ENV, PROJECT, "lms", "master", "1");
        assertTrue(cloneResult.contains("已创建 API 快照"), cloneResult);
        Path repoRoot = baseRoot.resolve("lms");
        Path masterDir = repoRoot.resolve("master");
        assertTrue(Files.isRegularFile(masterDir.resolve(GitlabWorkspaceManager.MANIFEST_FILE)),
                masterDir.toString());

        GitFetch fetch = configured(new GitFetch());
        String fetchResult = fetch.fetch(ENV, "lms", "origin", "true", null, "true", null);
        JsonNode fetchJson = JsonUtil.parse(fetchResult);
        assertTrue(fetchJson.path("success").asBoolean(), fetchResult);
        assertTrue(fetchJson.path("branchCount").asInt() > 0, fetchResult);

        GitLog log = configured(new GitLog());
        String logResult = log.log(ENV, "lms", "master", null, null, null,
                null, null, "5", "0", "false");
        JsonNode logJson = JsonUtil.parse(logResult);
        assertTrue(logJson.path("success").asBoolean(), logResult);
        assertTrue(logJson.path("commits").size() > 0, logResult);
        String commitId = logJson.path("commits").get(0).path("commitId").asText();

        GitShowCommit showCommit = configured(new GitShowCommit());
        String showResult = showCommit.show(ENV, "lms", commitId, null, "20", "true");
        JsonNode showJson = JsonUtil.parse(showResult);
        assertTrue(showJson.path("success").asBoolean(), showResult);
        assertTrue(showJson.path("files").size() > 0, showResult);

        GitShowDiff showDiff = configured(new GitShowDiff());
        String diffResult = showDiff.diff(ENV, "lms", commitId, null, null, "3", "true", "200000");
        assertTrue(diffResult.startsWith("diff --git "), diffResult.substring(0, Math.min(200, diffResult.length())));

        // 镜像 tag -> pipeline -> ref/sha -> 精确代码目录
        GitlabPipelineDetail pipelineDetail = configured(new GitlabPipelineDetail());
        String pipelineJson = pipelineDetail.detail(ENV, "1367", "109478", null);
        JsonNode pipeline = JsonUtil.parse(pipelineJson);
        assertTrue(pipeline.path("success").asBoolean(), pipelineJson);
        assertEquals("mtn-main/cameroon-airtime", pipeline.path("ref").asText());
        assertEquals("5ec23276f56b4a76db1d84017556e178a739c65b", pipeline.path("sha").asText());
        assertEquals("failed", pipeline.path("status").asText());

        GitlabPipelines pipelines = configured(new GitlabPipelines());
        String pipelinesJson = pipelines.list(ENV, "1367", "mtn-main/cameroon-airtime",
                null, null, null, "5");
        JsonNode pipelineList = JsonUtil.parse(pipelinesJson);
        assertTrue(pipelineList.path("success").asBoolean(), pipelinesJson);
        assertTrue(pipelineList.path("count").asInt() >= 1, pipelinesJson);

        GitPipelineCheckout pipelineCheckout = configured(new GitPipelineCheckout());
        String pipelineCheckoutResult = pipelineCheckout.checkout(ENV, "lms",
                null, "harbor.example.com/mtn/lms:109478", null);
        assertTrue(pipelineCheckoutResult.contains("已按流水线物化代码"), pipelineCheckoutResult);
        Path pipelineDir = repoRoot.resolve("pipeline-109478");
        assertTrue(Files.isRegularFile(pipelineDir.resolve(GitlabWorkspaceManager.MANIFEST_FILE)),
                pipelineDir.toString());
        JsonNode pipelineManifest = JsonUtil.parse(Files.readString(
                pipelineDir.resolve(GitlabWorkspaceManager.MANIFEST_FILE)));
        assertEquals("5ec23276f56b4a76db1d84017556e178a739c65b",
                pipelineManifest.path("commitSha").asText());

        // 并行物化两条不同分支，验证目录隔离与并行能力
        GitWorktreeAdd addCameroon = configured(new GitWorktreeAdd());
        GitWorktreeAdd addCongo = configured(new GitWorktreeAdd());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> addCameroon.add(ENV,
                    "lms", "MTN-Feature/Cameroon", null, "false", "true", "true"));
            Future<String> second = pool.submit(() -> addCongo.add(ENV,
                    "lms", "MTN-Feature/CongoB", null, "false", "true", "true"));
            String r1 = first.get();
            String r2 = second.get();
            assertTrue(r1.contains("工作区已就绪"), r1);
            assertTrue(r2.contains("工作区已就绪"), r2);
        } finally {
            pool.shutdownNow();
        }

        Path cameroon = repoRoot.resolve("MTN-Feature-Cameroon");
        Path congo = repoRoot.resolve("MTN-Feature-CongoB");
        assertTrue(Files.isDirectory(cameroon), cameroon.toString());
        assertTrue(Files.isDirectory(congo), congo.toString());
        assertEquals(repoRoot, cameroon.getParent());
        assertEquals(repoRoot, congo.getParent());
        assertNotEquals(cameroon, congo);

        GitCheckout checkout = configured(new GitCheckout());
        String checkoutResult = checkout.checkout(ENV, "lms", "MTN-Feature/Cameroon");
        assertTrue(checkoutResult.contains("分支快照已就绪"), checkoutResult);

        GitWorktreeList list = configured(new GitWorktreeList());
        String listResult = list.list(ENV, "lms");
        assertTrue(listResult.contains("MTN-Feature/Cameroon"), listResult);
        assertTrue(listResult.contains("MTN-Feature/CongoB"), listResult);

        GitPull pull = configured(new GitPull());
        String pullResult = pull.pull(ENV, cameroon.toString(), null);
        assertTrue(pullResult.contains("已是最新") || pullResult.contains("重新物化"), pullResult);

        GitWorktreeRemove remove = configured(new GitWorktreeRemove());
        String removeResult = remove.remove(ENV, "lms", congo.toString(), "true");
        assertTrue(removeResult.contains("已删除工作区"), removeResult);
        assertFalse(Files.exists(congo));

        GitWorktreePrune prune = configured(new GitWorktreePrune());
        String pruneResult = prune.prune(ENV, "lms", "true");
        assertTrue(pruneResult.contains("git-worktree-prune"), pruneResult);
    }

    private <T> T configured(T tool) throws Exception {
        set(tool, "gitlabProperties", gitlabProperties);
        setIfPresent(tool, "workspaceManager", manager);
        setIfPresent(tool, "gitlabTokenProvider", gitlabTokenProvider);
        try {
            tool.getClass().getSuperclass().getDeclaredMethod("initRestTemplate").invoke(tool);
        } catch (NoSuchMethodException ignored) {
            // 非 API 查询工具没有 RestTemplate
        }
        return tool;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        if (field == null) {
            throw new NoSuchFieldException(name);
        }
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setIfPresent(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        if (field != null) {
            field.setAccessible(true);
            field.set(target, value);
        }
    }

    private static Field findField(Class<?> type, String name) {
        while (type != null) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}
