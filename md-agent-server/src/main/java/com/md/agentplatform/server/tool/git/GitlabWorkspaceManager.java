package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.GitlabProperties;
import com.md.agentplatform.server.util.JsonUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GitLab REST API 工作区管理器。
 *
 * <p>本类不再依赖本地 Git/JGit。本地目录布局为 {@code <gitlab.base-dir>/<repo>/<branch>/}：
 * 一个仓库对应一个目录，仓库目录下按分支建子目录。目录内容来自
 * {@code GET /projects/:id/repository/archive.zip?sha=...}。多个分支使用不同子目录，因此可以并行
 * 下载、并行分析，不存在 Git 索引锁或 worktree 元数据冲突。
 */
@Component
public class GitlabWorkspaceManager {

    private static final Logger logger = LoggerFactory.getLogger(GitlabWorkspaceManager.class);

    static final String MANIFEST_FILE = ".md-agent-workspace.json";
    static final String REGISTRY_FILE = ".md-agent-repos.json";
    private static final String MANIFEST_VERSION = "3";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final String ILLEGAL_NAME_CHARS = "\\/:*?\"<>|";

    @Autowired
    private GitlabProperties gitlabProperties;

    @Autowired
    private GitlabTokenProvider gitlabTokenProvider;

    private RestTemplate apiTemplate;
    private RestTemplate archiveTemplate;

    private final Object registryLock = new Object();
    private final ConcurrentHashMap<Path, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        apiTemplate = buildRestTemplate(gitlabProperties.getTimeoutMs());
        archiveTemplate = buildRestTemplate(Math.max(
                gitlabProperties.getTimeoutMs(), gitlabProperties.getArchiveTimeoutMs()));
    }

    public Path baseDir() {
        String configured = gitlabProperties == null ? null : gitlabProperties.getBaseDir();
        Path base = Path.of(notBlank(configured) ? configured.trim() : "workspace/code")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 gitlab.base-dir: " + base, e);
        }
        return base;
    }

    private GitlabAccess access(String environment) {
        if (gitlabTokenProvider == null) {
            throw new IllegalStateException("GitlabTokenProvider 未初始化");
        }
        return gitlabTokenProvider.access(environment);
    }

    private URI uri(String environment, String path, List<String> query) {
        return uri(access(environment), path, query);
    }

    private static URI uri(GitlabAccess access, String path, List<String> query) {
        String suffix = query == null || query.isEmpty() ? "" : "?" + String.join("&", query);
        return URI.create(access.baseUrl() + path + suffix);
    }

    private JsonNode get(String environment, URI uri) {
        GitlabAccess access = access(environment);
        try {
            return get(access, uri);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 401 && access.dynamic()) {
                gitlabTokenProvider.invalidate(environment);
                return get(access(environment), uri);
            }
            throw e;
        }
    }

    private JsonNode get(GitlabAccess access, URI uri) {
        HttpHeaders headers = new HttpHeaders();
        access.apply(headers);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = apiTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        return notBlank(body) ? JsonUtil.parse(body) : null;
    }

    public Project resolveProject(String environment, String repoRef) {
        String ref = repoRef == null ? "" : repoRef.trim();
        if (!notBlank(ref)) {
            throw new IllegalArgumentException("仓库标识不能为空（可传 projectId、项目路径、仓库 URL 或 git-clone 的 repoDir）");
        }

        Project alias = lookupAlias(environment, ref);
        if (alias != null) {
            return alias;
        }

        Path manifestDir = localManifestDir(ref);
        if (manifestDir != null) {
            WorkspaceManifest manifest = readManifestForEnv(manifestDir, environment);
            return projectById(environment, manifest.projectId());
        }

        if (ref.matches("\\d+")) {
            return projectById(environment, Integer.parseInt(ref));
        }

        String projectPath = extractProjectPath(ref);
        if (projectPath != null) {
            return projectByPath(environment, projectPath);
        }

        if (ref.contains("/")) {
            return projectByPath(environment, ref);
        }

        throw new IllegalArgumentException(
                "未登记的仓库别名: " + ref + "。请先执行 git-clone，或直接传 projectId / 项目路径 / 仓库 URL");
    }

    public Project projectById(String environment, int projectId) {
        JsonNode node = get(environment, uri(environment, "/projects/" + projectId, List.of()));
        return toProject(node, String.valueOf(projectId));
    }

    public Project projectByPath(String environment, String projectPath) {
        JsonNode node = get(environment, uri(environment, "/projects/" + encodeProject(projectPath), List.of()));
        return toProject(node, projectPath);
    }

    public RefCommit resolveRef(String environment, Project project, String ref) {
        String requested = notBlank(ref) ? normalizeBranch(ref) : project.defaultBranch();
        if (!notBlank(requested)) {
            throw new IllegalArgumentException("分支不能为空，且项目没有默认分支");
        }

        JsonNode branch = tryGet(environment, "/projects/" + project.id() + "/repository/branches/"
                + urlEncode(requested));
        if (branch != null && branch.isObject()) {
            JsonNode commit = branch.path("commit");
            return new RefCommit(requested, text(commit, "id"), text(commit, "committed_date"), "branch");
        }

        JsonNode tag = tryGet(environment, "/projects/" + project.id() + "/repository/tags/" + urlEncode(requested));
        if (tag != null && tag.isObject()) {
            JsonNode commit = tag.path("commit");
            return new RefCommit(requested, text(commit, "id"), text(commit, "committed_date"), "tag");
        }

        JsonNode commit = tryGet(environment, "/projects/" + project.id() + "/repository/commits/"
                + urlEncode(requested));
        if (commit != null && commit.isObject()) {
            return new RefCommit(requested, text(commit, "id"), text(commit, "committed_date"), "commit");
        }

        throw new IllegalArgumentException("找不到分支/标签/提交: " + requested);
    }

    public List<Branch> branches(String environment, Project project, String search) {
        List<Branch> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<String> query = new ArrayList<>();
            query.add("per_page=" + PAGE_SIZE);
            query.add("page=" + page);
            if (notBlank(search)) {
                query.add("search=" + urlEncode(search.trim()));
            }
            JsonNode array = get(environment, uri(environment, "/projects/" + project.id() + "/repository/branches", query));
            if (array == null || !array.isArray() || array.isEmpty()) {
                break;
            }
            for (JsonNode item : array) {
                JsonNode commit = item.path("commit");
                result.add(new Branch(text(item, "name"), text(commit, "id"),
                        text(commit, "committed_date"), item.path("merged").asBoolean(false),
                        item.path("protected").asBoolean(false)));
            }
            if (array.size() < PAGE_SIZE) {
                break;
            }
        }
        return result;
    }

    public List<Tag> tags(String environment, Project project) {
        List<Tag> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<String> query = List.of("per_page=" + PAGE_SIZE, "page=" + page);
            JsonNode array = get(environment, uri(environment, "/projects/" + project.id() + "/repository/tags", query));
            if (array == null || !array.isArray() || array.isEmpty()) {
                break;
            }
            for (JsonNode item : array) {
                JsonNode commit = item.path("commit");
                result.add(new Tag(text(item, "name"), text(commit, "id"),
                        text(commit, "committed_date")));
            }
            if (array.size() < PAGE_SIZE) {
                break;
            }
        }
        return result;
    }

    /**
     * 查询单条流水线。pipelineRef 可以是 pipeline id、pipeline URL，或带 tag 的镜像名；
     * tag 为纯数字时直接当作 pipeline id（本项目的镜像版本号与流水线号一致）。
     */
    public Pipeline pipeline(String environment, Project project, String pipelineRef) {
        int pipelineId = parsePipelineId(pipelineRef);
        JsonNode node = get(environment, uri(environment, "/projects/" + project.id() + "/pipelines/" + pipelineId, List.of()));
        return toPipeline(node, project.id(), pipelineId);
    }

    /**
     * 查询流水线列表，支持 ref / sha / status / username 过滤。
     */
    public List<Pipeline> pipelines(String environment, Project project, String ref, String sha, String status,
                                    String username, int maxCount) {
        int limit = Math.max(1, Math.min(maxCount, 200));
        List<Pipeline> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES && result.size() < limit; page++) {
            int perPage = Math.min(PAGE_SIZE, limit - result.size());
            List<String> query = new ArrayList<>();
            query.add("per_page=" + perPage);
            query.add("page=" + page);
            query.add("order_by=id");
            query.add("sort=desc");
            if (notBlank(ref)) {
                query.add("ref=" + urlEncode(ref.trim()));
            }
            if (notBlank(sha)) {
                query.add("sha=" + urlEncode(sha.trim()));
            }
            if (notBlank(status)) {
                query.add("status=" + urlEncode(status.trim()));
            }
            if (notBlank(username)) {
                query.add("username=" + urlEncode(username.trim()));
            }
            JsonNode array = get(environment, uri(environment, "/projects/" + project.id() + "/pipelines", query));
            if (array == null || !array.isArray() || array.isEmpty()) {
                break;
            }
            for (JsonNode item : array) {
                result.add(toPipeline(item, project.id(), item.path("id").asInt(0)));
                if (result.size() >= limit) {
                    break;
                }
            }
            if (array.size() < perPage) {
                break;
            }
        }
        return result;
    }

    private Pipeline toPipeline(JsonNode node, int projectId, int fallbackId) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("GitLab 流水线不存在或无权访问: " + fallbackId);
        }
        int id = node.path("id").asInt(fallbackId);
        if (id <= 0) {
            throw new IllegalArgumentException("GitLab 流水线响应缺少 id: " + fallbackId);
        }
        Integer duration = node.hasNonNull("duration") ? node.path("duration").asInt() : null;
        JsonNode user = node.path("user");
        return new Pipeline(id, projectId, text(node, "ref"), text(node, "sha"),
                text(node, "before_sha"), text(node, "status"), node.path("tag").asBoolean(false),
                text(node, "web_url"), text(node, "created_at"), text(node, "started_at"),
                text(node, "finished_at"), duration, text(user, "name"), text(user, "username"));
    }

    /**
     * 从 pipeline id / URL / 镜像名中解析 pipeline id。
     *
     * <p>支持：
     * <ul>
     *   <li>{@code 109478}</li>
     *   <li>{@code https://git.huojintech.com/mtn/lms/pipelines/109478}</li>
     *   <li>{@code harbor.example.com/mtn/lms:109478}</li>
     *   <li>{@code lms:v109478}、{@code lms:release-109478}</li>
     * </ul>
     */
    public static int parsePipelineId(String pipelineRef) {
        String value = pipelineRef == null ? "" : pipelineRef.trim().replace("\"", "").replace("'", "");
        if (!notBlank(value)) {
            throw new IllegalArgumentException("pipelineId 或 image 至少填一个");
        }
        if (value.matches("\\d+")) {
            return positiveInt(value, pipelineRef);
        }
        int marker = value.indexOf("/pipelines/");
        if (marker >= 0) {
            String rest = value.substring(marker + "/pipelines/".length());
            int end = 0;
            while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
                end++;
            }
            if (end > 0) {
                return positiveInt(rest.substring(0, end), pipelineRef);
            }
        }
        int at = value.indexOf("@sha256:");
        if (at > 0) {
            value = value.substring(0, at);
        }
        int lastSlash = value.lastIndexOf('/');
        int lastColon = value.lastIndexOf(':');
        String tag;
        if (lastColon > lastSlash) {
            tag = value.substring(lastColon + 1);
        } else if (lastSlash >= 0) {
            throw new IllegalArgumentException("镜像未带 tag，无法解析 pipeline id: " + pipelineRef);
        } else {
            tag = value;
        }
        if (tag.startsWith("v") || tag.startsWith("V")) {
            tag = tag.substring(1);
        }
        String best = "";
        int index = 0;
        while (index < tag.length()) {
            if (!Character.isDigit(tag.charAt(index))) {
                index++;
                continue;
            }
            int start = index;
            while (index < tag.length() && Character.isDigit(tag.charAt(index))) {
                index++;
            }
            String candidate = tag.substring(start, index);
            if (candidate.length() > best.length()) {
                best = candidate;
            }
        }
        if (best.length() < 3) {
            throw new IllegalArgumentException("镜像 tag 中没有可用的 pipeline 编号: " + pipelineRef);
        }
        return positiveInt(best, pipelineRef);
    }

    private static int positiveInt(String digits, String subject) {
        try {
            long value = Long.parseLong(digits);
            if (value <= 0 || value > Integer.MAX_VALUE) {
                throw new NumberFormatException("out of range");
            }
            return (int) value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析 pipeline id: " + subject);
        }
    }

    public Workspace ensureWorkspace(String environment, Project project, String alias, String ref,
                                     String targetName, boolean update, boolean force) {
        RefCommit resolved = resolveRef(environment, project, ref);
        Path target = resolveTarget(alias, resolved.name(), targetName);
        return ensureAt(environment, project, alias, resolved, target, "workspace", update, force);
    }

    /**
     * git-clone 的语义：创建 {@code gitlab.base-dir/<alias>} 仓库目录，并把默认分支（或指定分支）
     * 物化到 {@code gitlab.base-dir/<alias>/<branch>}。仓库目录登记为 repoDir -> GitLab 项目别名。
     */
    public Workspace cloneBase(String environment, Project project, String alias, String ref, boolean force) {
        RefCommit resolved = resolveRef(environment, project, ref);
        Path target = repoRoot(alias).resolve(safeName(resolved.name())).normalize();
        if (!target.startsWith(repoRoot(alias))) {
            throw new IllegalArgumentException("分支目录越界: " + alias + "/" + resolved.name());
        }
        Workspace workspace = ensureAt(environment, project, alias, resolved, target, "base", true, force);
        registerAlias(environment, alias, project);
        return workspace;
    }

    /**
     * git-pull 的语义：按 API 查询 ref 的最新 SHA，若目录已有快照且 SHA 相同直接复用，
     * 否则重新下载 archive 并原子替换。repoDir 可以指向基准目录或任一分支快照目录。
     */
    public Workspace refresh(String environment, Project project, String repoRef, String branch, boolean force) {
        Path manifestDir = localManifestDir(repoRef);
        if (manifestDir != null) {
            WorkspaceManifest existing = readManifestForEnv(manifestDir, environment);
            if (existing == null) {
                throw new IllegalArgumentException("目录不是 API 工作区: " + manifestDir);
            }
            String requested = notBlank(branch) ? normalizeBranch(branch) : existing.branch();
            RefCommit resolved = resolveRef(environment, project, requested);
            if (!existing.branch().equals(resolved.name()) && !force) {
                throw new IllegalArgumentException("该目录绑定分支 " + existing.branch()
                        + "，不能直接拉取到 " + resolved.name()
                        + "。请用 git-worktree-add 为另一个分支创建独立目录，或传 force=true 覆盖");
            }
            return ensureAt(environment, project, existing.alias(), resolved, manifestDir,
                    existing.kind(), true, force);
        }

        String alias = aliasOf(environment, repoRef, project);
        String requested = notBlank(branch) ? normalizeBranch(branch) : currentBranch(environment, repoRef, project);
        Workspace base = baseWorkspace(project, alias);
        if (base == null) {
            throw new IllegalArgumentException("本地还没有该仓库的基准快照，请先执行 git-clone: " + repoRef);
        }
        RefCommit resolved = resolveRef(environment, project, requested);
        if (!base.branch().equals(resolved.name())) {
            return ensureWorkspace(environment, project, alias, requested, null, true, false);
        }
        WorkspaceManifest existing = readManifest(base.path());
        String kind = existing == null ? "base" : existing.kind();
        return ensureAt(environment, project, alias, resolved, base.path(), kind, true, force);
    }

    private Workspace ensureAt(String environment, Project project, String alias, RefCommit resolved, Path target,
                               String kind, boolean update, boolean force) {
        ReentrantLock lock = workspaceLocks.computeIfAbsent(target, ignored -> new ReentrantLock());
        lock.lock();
        try {
            WorkspaceManifest existing = readManifest(target);
            if (Files.exists(target)) {
                if (existing == null) {
                    if (!force) {
                        throw new IllegalArgumentException("目标目录已存在且不是 API 工作区: " + target
                                + "。请换一个 path，或传 force=true 覆盖");
                    }
                } else if (notBlank(existing.environment()) && !existing.environment().equals(environment)) {
                    if (!force) {
                        throw new IllegalArgumentException("目标目录属于环境 " + existing.environment()
                                + "，不能用环境 " + environment + " 覆盖；请换一个 repoDir 别名，或传 force=true");
                    }
                } else if (existing.projectId() != project.id()) {
                    if (!force) {
                        throw new IllegalArgumentException("目标目录属于另一个 GitLab 项目: " + target
                                + "（projectId=" + existing.projectId() + "）");
                    }
                } else if (!existing.branch().equals(resolved.name())) {
                    if (!force) {
                        throw new IllegalArgumentException("目标目录已经绑定分支 "
                                + existing.branch() + "，不能直接改为 " + resolved.name()
                                + "。请换一个 path，或传 force=true 覆盖");
                    }
                } else if (!force) {
                    if (existing.commitSha().equals(resolved.commitId())) {
                        return toWorkspace(project, existing, target, false, false, false,
                                resolved.commitId());
                    }
                    if (!update) {
                        return toWorkspace(project, existing, target, false, false, true,
                                resolved.commitId());
                    }
                }
            }

            WorkspaceManifest created = materialize(environment, project, alias, resolved, target, kind);
            return toWorkspace(project, created, target, true, true, false, resolved.commitId());
        } finally {
            lock.unlock();
        }
    }

    public Workspace checkout(String environment, Project project, String alias, String ref) {
        return ensureWorkspace(environment, project, alias, ref, null, true, false);
    }

    public Workspace pull(String environment, Project project, String alias, String ref) {
        return ensureWorkspace(environment, project, alias, ref, null, true, false);
    }

    public Workspace baseWorkspace(Project project, String alias) {
        Path root = repoRoot(alias);
        // 兼容旧扁平布局：base/<alias> 本身就是一份快照。
        WorkspaceManifest direct = readManifest(root);
        if (direct != null && direct.projectId() == project.id()) {
            return toWorkspace(project, direct, root, false, false, false, direct.commitSha());
        }
        WorkspaceManifest selected = null;
        Path selectedPath = null;
        for (Path dir : branchDirs(root)) {
            WorkspaceManifest manifest = readManifest(dir);
            if (manifest == null || manifest.projectId() != project.id()) {
                continue;
            }
            if (selected == null || "base".equals(manifest.kind())) {
                selected = manifest;
                selectedPath = dir;
            }
            if ("base".equals(manifest.kind())) {
                break;
            }
            if (selectedPath != null && notBlank(project.defaultBranch())
                    && project.defaultBranch().equals(manifest.branch())) {
                selected = manifest;
                selectedPath = dir;
            }
        }
        return selected == null ? null
                : toWorkspace(project, selected, selectedPath, false, false, false, selected.commitSha());
    }

    public List<WorkspaceEntry> listWorkspaces(String environment, Project project) {
        List<WorkspaceEntry> result = new ArrayList<>();
        Path base = baseDir();
        try (var stream = Files.list(base)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .forEach(path -> {
                        // 兼容旧的扁平布局：base/<alias@branch>
                        WorkspaceManifest legacy = readManifest(path);
                        if (legacy != null) {
                            if (legacy.projectId() == project.id() && matchesEnvironment(legacy, environment)) {
                                result.add(toEntry(legacy, path));
                            }
                            return;
                        }
                        for (Path branchDir : branchDirs(path)) {
                            WorkspaceManifest manifest = readManifest(branchDir);
                            if (manifest != null && manifest.projectId() == project.id()
                                    && matchesEnvironment(manifest, environment)) {
                                result.add(toEntry(manifest, branchDir));
                            }
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("读取工作区目录失败: " + base, e);
        }
        result.sort(Comparator.comparing(WorkspaceEntry::alias)
                .thenComparing(WorkspaceEntry::branch)
                .thenComparing(entry -> entry.path().toString()));
        return result;
    }

    private static WorkspaceEntry toEntry(WorkspaceManifest manifest, Path path) {
        return new WorkspaceEntry(manifest.alias(), manifest.branch(), manifest.commitSha(),
                path, manifest.fetchedAt(), manifest.kind());
    }

    public Path resolveWorkspacePath(String pathOrName) {
        if (!notBlank(pathOrName)) {
            throw new IllegalArgumentException("工作区路径不能为空");
        }
        Path raw = Path.of(pathOrName.trim());
        Path resolved = (raw.isAbsolute() ? raw : baseDir().resolve(raw)).normalize().toAbsolutePath();
        Path base = baseDir().toAbsolutePath();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException("工作区必须位于 gitlab.base-dir 下: " + base);
        }
        return resolved;
    }

    public void removeWorkspace(String environment, Project project, Path target, boolean force) {
        Path normalized = target.toAbsolutePath().normalize();
        Path base = baseDir().toAbsolutePath();
        if (!normalized.startsWith(base) || normalized.equals(base)) {
            throw new IllegalArgumentException("拒绝删除 gitlab.base-dir 之外的路径: " + normalized);
        }
        WorkspaceManifest manifest = readManifestForEnv(normalized, environment);
        if (manifest == null) {
            throw new IllegalArgumentException("不是 API 工作区（缺少 " + MANIFEST_FILE + "）: " + normalized);
        }
        if (manifest.projectId() != project.id()) {
            throw new IllegalArgumentException("工作区不属于当前 GitLab 项目: " + normalized);
        }
        if ("base".equals(manifest.kind()) && !force) {
            throw new IllegalArgumentException("这是 git-clone 创建的基准工作区，删除请显式传 force=true: "
                    + normalized);
        }
        try {
            deleteRecursively(normalized);
        } catch (IOException e) {
            throw new IllegalStateException("删除工作区失败: " + normalized, e);
        }

        // 分支目录删完后，仓库目录也空了，顺手清理，保持“一仓库一目录”的整洁。
        Path repoRoot = normalized.getParent();
        if (repoRoot != null && repoRoot.startsWith(base) && !repoRoot.equals(base)) {
            try (var stream = Files.list(repoRoot)) {
                if (stream.findAny().isEmpty()) {
                    Files.deleteIfExists(repoRoot);
                }
            } catch (IOException ignored) {
                // 目录仍然被占用时保留，交给 prune 或人工清理
            }
        }
        if (notBlank(manifest.alias()) && listWorkspaces(environment, project).isEmpty()) {
            unregisterAlias(environment, manifest.alias());
        }
    }

    public List<String> pruneTempDirs(boolean dryRun) {
        List<String> removed = new ArrayList<>();
        Path base = baseDir();
        Instant cutoff = Instant.now().minusSeconds(600);
        // 临时项可能在 base 顶层（旧布局）或仓库目录下（新布局），最多扫描两层。
        try (var stream = Files.walk(base, 2)) {
            for (Path path : stream.toList()) {
                if (path.equals(base)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (!name.contains(".tmp-") && !name.contains(".old-") && !name.endsWith(".zip")) {
                    continue;
                }
                try {
                    Instant modified = Files.getLastModifiedTime(path).toInstant();
                    if (modified.isAfter(cutoff)) {
                        continue;
                    }
                } catch (IOException ignored) {
                    continue;
                }
                if (!dryRun) {
                    try {
                        deleteRecursively(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("删除临时路径失败: " + path, e);
                    }
                }
                removed.add(base.relativize(path).toString());
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描临时工作区失败: " + base, e);
        }
        return removed;
    }

    public void registerAlias(String environment, String alias, Project project) {
        String key = safeName(alias);
        if (!notBlank(key)) {
            return;
        }
        synchronized (registryLock) {
            ObjectNode registry = loadRegistry();
            ObjectNode value = JsonUtil.newObject();
            value.put("environment", environment);
            value.put("projectId", project.id());
            value.put("pathWithNamespace", project.pathWithNamespace());
            value.put("defaultBranch", project.defaultBranch());
            value.put("httpUrlToRepo", project.httpUrlToRepo());
            value.put("webUrl", project.webUrl());
            value.put("updatedAt", Instant.now().toString());
            registry.set(scopedRegistryKey(environment, key), value);
            saveRegistry(registry);
        }
    }

    public Project lookupAlias(String environment, String alias) {
        if (!notBlank(alias)) {
            return null;
        }
        synchronized (registryLock) {
            JsonNode registry = loadRegistry();
            JsonNode node = registry.path(scopedRegistryKey(environment, safeName(alias)));
            if (!node.isObject()) {
                // 兼容旧版本没有环境作用域的别名；只有环境为空或一致时才复用
                JsonNode legacy = registry.path(safeName(alias));
                if (legacy.isObject()
                        && (!notBlank(text(legacy, "environment"))
                        || environment.equals(text(legacy, "environment")))) {
                    node = legacy;
                }
            }
            if (!node.isObject()) {
                return null;
            }
            int id = node.path("projectId").asInt(0);
            if (id <= 0) {
                return null;
            }
            return new Project(id, text(node, "pathWithNamespace"), text(node, "defaultBranch"),
                    text(node, "httpUrlToRepo"), text(node, "webUrl"));
        }
    }

    private static String scopedRegistryKey(String environment, String alias) {
        return safeName(environment) + "|" + safeName(alias);
    }

    public String aliasOf(String environment, String repoRef, Project project) {
        Project alias = lookupAlias(environment, repoRef);
        if (alias != null) {
            return safeName(repoRef);
        }
        Path manifestDir = localManifestDir(repoRef);
        if (manifestDir != null) {
            WorkspaceManifest manifest = readManifestForEnv(manifestDir, environment);
            if (manifest != null && notBlank(manifest.alias())) {
                return manifest.alias();
            }
        }
        if (notBlank(repoRef) && repoRef.contains("/")) {
            String path = extractProjectPath(repoRef);
            if (notBlank(path)) {
                return safeName(path.substring(path.lastIndexOf('/') + 1));
            }
        }
        if (notBlank(project.pathWithNamespace())) {
            String path = project.pathWithNamespace();
            return safeName(path.substring(path.lastIndexOf('/') + 1));
        }
        return safeName("repo-" + project.id());
    }

    public String currentBranch(String environment, String repoRef, Project project) {
        Path manifestDir = localManifestDir(repoRef);
        if (manifestDir != null) {
            WorkspaceManifest manifest = readManifestForEnv(manifestDir, environment);
            if (manifest != null) {
                return manifest.branch();
            }
        }
        Workspace base = baseWorkspace(project, aliasOf(environment, repoRef, project));
        return base == null ? project.defaultBranch() : base.branch();
    }

    private WorkspaceManifest materialize(String environment, Project project, String alias, RefCommit ref,
                                           Path target, String kind) {
        Path parent = target.getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("创建父目录失败: " + parent, e);
        }

        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path zip = parent.resolve(target.getFileName() + ".zip.tmp-" + suffix);
        Path tempDir = parent.resolve(target.getFileName() + ".tmp-" + suffix);
        try {
            downloadArchive(environment, project, ref.commitId(), zip);
            Files.createDirectories(tempDir);
            extractZip(zip, tempDir);
            writeManifest(environment, tempDir, project, alias, ref, kind);
            replaceDirectory(tempDir, target);
            logger.info("GitLab 工作区已物化: project={} branch={} commit={} path={}",
                    project.pathWithNamespace(), ref.name(), shortHash(ref.commitId()), target);
            return readManifest(target);
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException("下载 GitLab archive 失败（HTTP "
                    + e.getStatusCode().value() + "）: " + project.pathWithNamespace()
                    + " @" + ref.name() + "，请检查 token 权限和分支是否存在", e);
        } catch (IOException e) {
            throw new IllegalStateException("物化工作区失败: " + target + " -> " + e.getMessage(), e);
        } finally {
            deleteQuietly(zip);
            deleteQuietly(tempDir);
        }
    }

    private void downloadArchive(String environment, Project project, String sha, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            GitlabAccess access = access(environment);
            URI uri = uri(access, "/projects/" + project.id() + "/repository/archive.zip",
                    List.of("sha=" + urlEncode(sha)));
            try {
                archiveTemplate.execute(uri, HttpMethod.GET, request -> {
                    access.apply(request.getHeaders());
                    request.getHeaders().setAccept(List.of(
                            MediaType.APPLICATION_OCTET_STREAM, MediaType.parseMediaType("application/zip")));
                }, response -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IOException("archive 下载失败，HTTP " + response.getStatusCode().value());
                    }
                    try (InputStream body = response.getBody()) {
                        if (body == null) {
                            throw new IOException("archive 响应为空");
                        }
                        Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return null;
                });
                return;
            } catch (HttpStatusCodeException e) {
                if (attempt == 0 && e.getStatusCode().value() == 401 && access.dynamic()) {
                    gitlabTokenProvider.invalidate(environment);
                    continue;
                }
                throw e;
            }
        }
    }

    static void extractZip(Path zipFile, Path targetDir) throws IOException {
        Path root = targetDir.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8)) {
            String archiveRoot = commonArchiveRoot(zip);
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String relative = stripArchiveRoot(entry.getName(), archiveRoot);
                if (!notBlank(relative)) {
                    continue;
                }
                Path out = root.resolve(relative).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("archive 包含越界路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String commonArchiveRoot(ZipFile zip) {
        String first = null;
        boolean same = true;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = normalizeZipName(entries.nextElement().getName());
            if (!notBlank(name) || name.equals("__MACOSX/")) {
                continue;
            }
            int slash = name.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String candidate = name.substring(0, slash);
            if (first == null) {
                first = candidate;
            } else if (!first.equals(candidate)) {
                same = false;
                break;
            }
        }
        return same ? first : null;
    }

    private static String stripArchiveRoot(String rawName, String archiveRoot) {
        String name = normalizeZipName(rawName);
        if (!notBlank(name)) {
            return "";
        }
        if (notBlank(archiveRoot)) {
            String prefix = archiveRoot + "/";
            if (name.equals(prefix)) {
                return "";
            }
            if (!name.startsWith(prefix)) {
                return "";
            }
            name = name.substring(prefix.length());
        }
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("archive 包含绝对路径: " + rawName);
        }
        Path normalized = Path.of(name).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("archive 包含越界路径: " + rawName);
        }
        return normalized.toString().replace('\\', '/');
    }

    private static String normalizeZipName(String name) {
        return name == null ? "" : name.replace('\\', '/').trim();
    }

    private void replaceDirectory(Path tempDir, Path target) throws IOException {
        Path backup = null;
        if (Files.exists(target)) {
            backup = target.getParent().resolve(target.getFileName() + ".old-" + UUID.randomUUID());
            move(target, backup);
        }
        try {
            move(tempDir, target);
            if (backup != null) {
                deleteRecursively(backup);
            }
        } catch (IOException e) {
            if (backup != null && !Files.exists(target) && Files.exists(backup)) {
                move(backup, target);
            }
            throw e;
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target);
        }
    }

    private void writeManifest(String environment, Path dir, Project project, String alias, RefCommit ref, String kind) {
        ObjectNode node = JsonUtil.newObject();
        node.put("version", MANIFEST_VERSION);
        node.put("environment", environment);
        node.put("projectId", project.id());
        node.put("projectPath", project.pathWithNamespace());
        node.put("alias", safeName(alias));
        node.put("layout", "repo/branch");
        node.put("branch", ref.name());
        node.put("commitSha", ref.commitId());
        node.put("commitDate", ref.commitDate());
        node.put("refType", ref.type());
        node.put("kind", kind);
        node.put("source", "gitlab-rest-archive");
        node.put("fetchedAt", Instant.now().toString());
        try {
            Files.writeString(dir.resolve(MANIFEST_FILE), JsonUtil.stringify(node), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入工作区清单失败: " + dir, e);
        }
    }

    WorkspaceManifest readManifest(Path dir) {
        Path file = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode node = JsonUtil.parse(Files.readString(file, StandardCharsets.UTF_8));
            int projectId = node.path("projectId").asInt(0);
            String branch = text(node, "branch");
            String commitSha = text(node, "commitSha");
            if (projectId <= 0 || !notBlank(branch) || !notBlank(commitSha)) {
                return null;
            }
            return new WorkspaceManifest(projectId, text(node, "projectPath"),
                    text(node, "alias"), text(node, "environment"), branch, commitSha,
                    text(node, "commitDate"), text(node, "refType"), text(node, "kind"),
                    text(node, "fetchedAt"));
        } catch (Exception e) {
            logger.warn("工作区清单解析失败: {}", file, e);
            return null;
        }
    }

    /**
     * 读取并校验工作区所属环境。
     *
     * <p>旧版本清单没有 environment 字段，视为“未标记”，允许任意环境继续使用；
     * 新清单一旦标了环境，就禁止另一个环境复用同一目录，避免不同 GitLab 实例的
     * 同名仓库互相覆盖。
     */
    private WorkspaceManifest readManifestForEnv(Path dir, String environment) {
        WorkspaceManifest manifest = readManifest(dir);
        if (manifest != null && notBlank(manifest.environment())
                && !manifest.environment().equals(environment)) {
            throw new IllegalArgumentException("目录 " + dir + " 属于环境 " + manifest.environment()
                    + "，不能用环境 " + environment + " 访问；请换一个 repoDir 别名");
        }
        return manifest;
    }

    private static boolean matchesEnvironment(WorkspaceManifest manifest, String environment) {
        return manifest != null
                && (!notBlank(manifest.environment()) || !notBlank(environment)
                || manifest.environment().equals(environment));
    }

    private Path resolveTarget(String alias, String ref, String targetName) {
        Path repoRoot = repoRoot(alias);
        String name = safeName(notBlank(targetName) ? targetName.trim() : ref);
        Path target = repoRoot.resolve(name).normalize();
        if (!target.startsWith(repoRoot) || target.equals(repoRoot)) {
            throw new IllegalArgumentException("分支目录越界: " + targetName);
        }
        if (!Files.exists(target) || notBlank(targetName)) {
            return target;
        }
        WorkspaceManifest current = readManifest(target);
        if (current != null && current.branch().equals(ref)) {
            return target;
        }
        for (int i = 1; i < 100; i++) {
            Path candidate = repoRoot.resolve(name + "-" + shortHash(ref) + (i == 1 ? "" : "-" + i));
            WorkspaceManifest manifest = readManifest(candidate);
            if (!Files.exists(candidate)
                    || (manifest != null && manifest.branch().equals(ref))) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法为分支分配工作区目录: " + ref);
    }

    /**
     * 仓库根目录：{@code <gitlab.base-dir>/<alias>}。分支目录一律位于该目录下。
     */
    public Path repoRoot(String alias) {
        Path base = baseDir().toAbsolutePath().normalize();
        Path root = base.resolve(safeName(alias)).normalize();
        if (!root.startsWith(base) || root.equals(base)) {
            throw new IllegalArgumentException("仓库目录越界: " + alias);
        }
        return root;
    }

    private List<Path> branchDirs(Path repoRoot) {
        if (!Files.isDirectory(repoRoot)) {
            return List.of();
        }
        try (var stream = Files.list(repoRoot)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> readManifest(path) != null)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取仓库分支目录失败: " + repoRoot, e);
        }
    }

    private Workspace toWorkspace(Project project, WorkspaceManifest manifest, Path path,
                                  boolean created, boolean updated, boolean stale, String latestCommit) {
        return new Workspace(project, manifest.branch(), manifest.commitSha(), latestCommit,
                path, created, updated, stale, manifest.fetchedAt());
    }

    private Path localManifestDir(String repoRef) {
        if (!notBlank(repoRef)) {
            return null;
        }
        try {
            Path direct = Path.of(repoRef.trim());
            if (Files.isDirectory(direct) && Files.isRegularFile(direct.resolve(MANIFEST_FILE))) {
                return direct.toAbsolutePath().normalize();
            }
            Path underBase = baseDir().resolve(repoRef.trim()).normalize();
            if (underBase.startsWith(baseDir()) && Files.isDirectory(underBase)
                    && Files.isRegularFile(underBase.resolve(MANIFEST_FILE))) {
                return underBase;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String extractProjectPath(String ref) {
        String value = ref.trim();
        if (value.startsWith("git@")) {
            int colon = value.indexOf(':');
            if (colon > 0) {
                return trimGitSuffix(value.substring(colon + 1));
            }
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (notBlank(path)) {
                return trimGitSuffix(URLDecoder.decode(path, StandardCharsets.UTF_8));
            }
        }
        return null;
    }

    private static String trimGitSuffix(String path) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith(".git")) {
            value = value.substring(0, value.length() - 4);
        }
        return value;
    }

    private JsonNode tryGet(String environment, String path) {
        try {
            return get(environment, uri(environment, path, List.of()));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw e;
        }
    }

    private Project toProject(JsonNode node, String subject) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("GitLab 项目不存在或无权访问: " + subject);
        }
        int id = node.path("id").asInt(0);
        if (id <= 0) {
            throw new IllegalArgumentException("GitLab 项目响应缺少 id: " + subject);
        }
        return new Project(id, text(node, "path_with_namespace"),
                text(node, "default_branch"), text(node, "http_url_to_repo"),
                text(node, "web_url"));
    }

    private ObjectNode loadRegistry() {
        Path file = baseDir().resolve(REGISTRY_FILE);
        if (!Files.isRegularFile(file)) {
            return JsonUtil.newObject();
        }
        try {
            JsonNode node = JsonUtil.parse(Files.readString(file, StandardCharsets.UTF_8));
            return node.isObject() ? (ObjectNode) node : JsonUtil.newObject();
        } catch (Exception e) {
            logger.warn("仓库别名注册表解析失败，将重建: {}", file, e);
            return JsonUtil.newObject();
        }
    }

    private void saveRegistry(ObjectNode registry) {
        Path file = baseDir().resolve(REGISTRY_FILE);
        try {
            Files.writeString(file, JsonUtil.stringify(registry), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入仓库别名注册表失败: " + file, e);
        }
    }

    private void unregisterAlias(String environment, String alias) {
        synchronized (registryLock) {
            ObjectNode registry = loadRegistry();
            registry.remove(scopedRegistryKey(environment, safeName(alias)));
            JsonNode legacy = registry.path(safeName(alias));
            if (legacy.isObject() && (!notBlank(text(legacy, "environment"))
                    || environment.equals(text(legacy, "environment")))) {
                registry.remove(safeName(alias));
            }
            saveRegistry(registry);
        }
    }

    private static String normalizeBranch(String branch) {
        String value = branch.trim();
        if (value.startsWith("origin/")) {
            return value.substring("origin/".length());
        }
        return value;
    }

    static String safeName(String value) {
        String name = value == null ? "" : value.trim();
        if (!notBlank(name)) {
            return "repo";
        }
        for (char ch : ILLEGAL_NAME_CHARS.toCharArray()) {
            name = name.replace(ch, '-');
        }
        name = name.replace('/', '-').replace('\\', '-')
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.\\s-]+|[.\\s-]+$", "");
        if (!notBlank(name)) {
            name = "repo";
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    static String shortHash(String sha) {
        return sha == null ? "" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }

    static String encodeProject(String project) {
        String value = project.trim();
        if (value.matches("\\d+")) {
            return value;
        }
        String decoded = value.replace("%2F", "/").replace("%2f", "/");
        return urlEncode(decoded);
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (Exception ignored) {
            // 清理失败不影响主流程
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    public record Project(int id, String pathWithNamespace, String defaultBranch,
                          String httpUrlToRepo, String webUrl) {
    }

    public record RefCommit(String name, String commitId, String commitDate, String type) {
    }

    public record Branch(String name, String commitId, String commitDate,
                         boolean merged, boolean protectedBranch) {
    }

    public record Tag(String name, String commitId, String commitDate) {
    }

    public record Pipeline(int id, int projectId, String ref, String sha, String beforeSha,
                           String status, boolean tag, String webUrl, String createdAt,
                           String startedAt, String finishedAt, Integer duration,
                           String userName, String userUsername) {
    }

    public record Workspace(Project project, String branch, String commitSha, String latestCommitSha,
                            Path path, boolean created, boolean updated, boolean stale,
                            String fetchedAt) {
    }

    public record WorkspaceManifest(int projectId, String projectPath, String alias,
                                    String environment, String branch, String commitSha,
                                    String commitDate, String refType, String kind,
                                    String fetchedAt) {
    }

    public record WorkspaceEntry(String alias, String branch, String commitSha, Path path,
                                 String fetchedAt, String kind) {
    }
}
