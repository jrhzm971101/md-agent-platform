package com.md.agentplatform.server.workspace;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.controlplane.store.entity.AgentWorkspaceEntity;
import com.md.agentplatform.server.controlplane.store.mapper.AgentWorkspaceMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工作区管理面：列出有工作区的 agent、浏览目录、读写文件、删除。
 *
 * <h2>一、为什么全部走 {@code AbstractFilesystem}，而不是直接拼磁盘路径</h2>
 * 工作区有两种后端（{@code fs_backend=LOCAL|REMOTE}），LOCAL 的文件在磁盘、REMOTE 的在 MySQL KV。
 * 框架的 {@code AbstractFilesystem} 把这两者统一成同一套 {@code ls/read/write/delete}，
 * 所以本类<b>不需要知道 fs_backend</b>，更不需要复刻 {@code RemoteFilesystemSpec} 的路由表 ——
 * 命名空间拼接、路由匹配、overlay 回落都由框架完成。
 *
 * <p>自己拼磁盘路径的代价不是"多写点代码"，而是<b>视图会分叉</b>：一旦框架调整内置路由，
 * 页面看到的目录树就和 agent 真实看到的不再是同一棵树，而这种错位不会报错，只会让人得出错误结论。
 *
 * <h2>二、安全边界放在 {@link WorkspacePathGuard}，不在本类</h2>
 * 本类每个入口都先过 Guard：读走 {@code assertVisible}（可写 ∪ 只读），写和删走 {@code assertManaged}
 * （只有可写）。这条边界不能省 —— LOCAL 模式下框架的 filesystem 下层挂的是<b>项目根目录</b>
 * （Claude Code 风格的 two-layer），也就是说不校验的话，agent 能读的文件页面就能读。
 *
 * <h2>三、为什么覆盖写不用 {@code filesystem.write}</h2>
 * 框架的 {@code write} 是<b>创建语义</b>：文件已存在会直接返回 error。页面上的"保存"绝大多数是覆盖，
 * 走它必然一直失败。覆盖写要用 {@code WorkspaceManager#writeUtf8WorkspaceRelative} ——
 * 它内部投到 {@code filesystem.uploadFiles}（last-write-wins），并且 filesystem 优先、不写本地盘。
 */
@Service
public class WorkspaceAdminService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAdminService.class);

    /**
     * userId 白名单。它会被拼进 KV 的命名空间（{@code agents/<id>/users/<uid>}），
     * 而命名空间段是用不可见分隔符拼成一整串存的 —— 段里混进分隔符就能伪造出别人的命名空间。
     * 校验放在入口，让非法值立刻 400。
     */
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    /** 单次读取上限（字符数）。超了截断并显式标记，绝不静默截断。 */
    private static final int MAX_READ_CHARS = 512 * 1024;

    /** 单次写入上限。与读取同量级：页面上的编辑器不是用来传大文件的。 */
    private static final int MAX_WRITE_CHARS = 1024 * 1024;

    /** 目录在前，其余按名字不区分大小写 —— 与文件管理器的习惯一致。 */
    private static final Comparator<WorkspaceViews.EntryRow> ENTRY_ORDER =
            Comparator.comparing((WorkspaceViews.EntryRow row) -> !row.directory())
                    .thenComparing(WorkspaceViews.EntryRow::name, String.CASE_INSENSITIVE_ORDER);

    private final AgentManager agentManager;
    private final AgentWorkspaceMapper workspaceMapper;
    private final WorkspaceUserMapper userMapper;
    private final WorkspacePathGuard guard;

    public WorkspaceAdminService(AgentManager agentManager, AgentWorkspaceMapper workspaceMapper,
                                 WorkspaceUserMapper userMapper, WorkspacePathGuard guard) {
        this.agentManager = agentManager;
        this.workspaceMapper = workspaceMapper;
        this.userMapper = userMapper;
        this.guard = guard;
    }

    // ==================== 列表 ====================

    /**
     * 列出所有有工作区的 agent（即已装配的 HARNESS 实例）。
     *
     * <p>用 {@code snapshot()} 而不是 {@code listAll()}：前者保留名字，页面按名字下钻，
     * 而 name 一旦靠 {@code Agent#getName()} 反推，就会出现"路由名字"与"agent 自报名"两套来源。
     */
    public List<WorkspaceViews.AgentRow> listAgents() {
        List<WorkspaceViews.AgentRow> rows = new ArrayList<>();
        for (Map.Entry<String, Agent> entry : agentManager.snapshot().entrySet()) {
            if (!(entry.getValue() instanceof HarnessAgent harness)) {
                continue; // REACT 没有工作区概念
            }
            WorkspaceManager manager = harness.getWorkspaceManager();
            AgentWorkspaceEntity meta = workspaceMapper.selectById(entry.getKey());
            rows.add(new WorkspaceViews.AgentRow(
                    entry.getKey(),
                    meta != null ? meta.getFsBackend() : null,
                    manager != null && manager.getWorkspace() != null
                            ? manager.getWorkspace().toAbsolutePath().toString()
                            : null,
                    manager != null && manager.getFilesystem() != null
                            ? manager.getFilesystem().getClass().getSimpleName()
                            : null));
        }
        rows.sort(Comparator.comparing(WorkspaceViews.AgentRow::agentName));
        return rows;
    }

    // ==================== 浏览 ====================

    /**
     * 列出目录。
     *
     * <p>根目录（{@code path} 为空或 {@code /}）走白名单探测而不是 {@code ls("/")}：
     * REMOTE 模式下根目录落在 CompositeFilesystem 的 defaultBackend（本地盘）上，
     * 而 {@code memory/}、{@code agents/} 这些在 KV 路由里，{@code ls("/")} 根本列不到它们。
     */
    /**
     * 「隔离维度」下拉框的候选值：这个 agent 被哪些身份用过。
     *
     * <p>先过 {@link #harnessOf}，理由与其它入口一致 —— 只有 HARNESS 有工作区，
     * 对一个 REACT agent 列出"用过它的身份"是给不存在的下拉框准备选项。
     *
     * <p>候选值要再过一遍 {@link #USER_ID} 白名单：这个名单最终会作为 {@code userId}
     * 从页面传回 {@link #listDir} 之类的入口，那里是第一道校验。若这里放行了不合规的值，
     * 用户在下拉框里选中它就是一个 400 —— 与其让人以为"这个用户的工作区坏了"，
     * 不如从一开始就不把它列出来。
     *
     * <p>过滤掉的数量只记 debug：它是历史数据的形状问题，不是需要每次都喊出来的异常。
     */
    public List<WorkspaceViews.UserRow> listUsers(String agentName) {
        /*
         * harnessOf 只当闸门用，返回值不参与查询：run 表里的 agent_name 是**装配名**
         * （也就是页面上传进来的这个名字），而 Agent#getName() 是 agent 自报名 —— 两者
         * 不保证一致，用后者去查会查不到任何东西，且现象只是"下拉框空着"。
         * 同一取舍见本类 listAgents()：那一份名单也从注册表拿，不从 Agent 反推。
         */
        harnessOf(agentName);
        List<WorkspaceViews.UserRow> rows = new ArrayList<>();
        int skipped = 0;
        for (WorkspaceUserRow row : userMapper.listUsers(agentName)) {
            String uid = row.getUserId();
            if (uid == null || !USER_ID.matcher(uid).matches()) {
                skipped++;
                continue;
            }
            rows.add(new WorkspaceViews.UserRow(uid, row.getRuns(), row.getLastMs()));
        }
        if (skipped > 0) {
            log.debug("[Workspace] {} 有 {} 个隔离维度不符合 userId 白名单，已从下拉框中略过", agentName, skipped);
        }
        return rows;
    }
    public WorkspaceViews.DirView listDir(String agentName, String userId, String rawPath) {
        HarnessAgent harness = harnessOf(agentName);
        String uid = normalizeUserId(userId);
        AbstractFilesystem fs = filesystemOf(harness, uid);
        RuntimeContext rc = RuntimeContext.empty();

        String rel = normalizeDirPath(rawPath);
        List<WorkspaceViews.EntryRow> entries =
                rel.isEmpty() ? listRootEntries(fs, rc) : listSubEntries(fs, rc, rel);
        return new WorkspaceViews.DirView(agentName, uid, rel, entries);
    }

    private List<WorkspaceViews.EntryRow> listRootEntries(AbstractFilesystem fs, RuntimeContext rc) {
        List<WorkspaceViews.EntryRow> rows = new ArrayList<>();
        // 根级文件：只报存在性。size 留 0 而不是先 read 一遍 —— 那是为了显示一个数字
        // 把 AGENTS.md 整份读进内存，代价与收益不成比例；页面把 0 显示成 "-"。
        for (String file : guard.managedRootFiles()) {
            if (fs.exists(rc, "/" + file)) {
                rows.add(new WorkspaceViews.EntryRow(file, file, false, 0L, "", true));
            }
        }
        for (String dir : guard.visibleRootDirs()) {
            LsResult probe = fs.ls(rc, "/" + dir);
            if (probe.isSuccess()) {
                rows.add(new WorkspaceViews.EntryRow(dir, dir, true, 0L, "",
                        guard.isManaged(dir)));
            }
        }
        rows.sort(ENTRY_ORDER);
        return rows;
    }

    private List<WorkspaceViews.EntryRow> listSubEntries(AbstractFilesystem fs, RuntimeContext rc,
                                                        String rel) {
        guard.assertVisible(rel);
        LsResult result = fs.ls(rc, "/" + rel);
        if (!result.isSuccess()) {
            throw new WorkspaceAdminException("列目录失败: " + rel + "（" + result.error() + "）");
        }
        List<WorkspaceViews.EntryRow> rows = new ArrayList<>();
        for (FileInfo info : nullSafe(result.entries())) {
            String childRel = stripLeadingSlash(info.path());
            if (childRel.isEmpty()) {
                continue;
            }
            // 根下必然混着 code/、.index/ 这类不该看的东西。跳过，而不是让整页失败。
            if (!guard.isVisible(childRel)) {
                continue;
            }
            String name = childRel.substring(childRel.lastIndexOf('/') + 1);
            rows.add(new WorkspaceViews.EntryRow(name, childRel, info.isDirectory(),
                    info.size(), info.modifiedAt(), guard.isManaged(childRel)));
        }
        rows.sort(ENTRY_ORDER);
        return rows;
    }

    /**
     * 读文件内容。
     *
     * <p>{@code limit=0} 是框架的"不限行数"约定（见官方 skill 仓库读全文的用法）。
     * 超出 {@link #MAX_READ_CHARS} 时截断并把 {@code truncated} 置位 ——
     * 静默截断会让用户照着一份残缺内容做判断，那比报错更糟。
     */
    public WorkspaceViews.FileView readFile(String agentName, String userId, String rawPath) {
        HarnessAgent harness = harnessOf(agentName);
        String uid = normalizeUserId(userId);
        String rel = guard.validate(rawPath);
        guard.assertVisible(rel);
        AbstractFilesystem fs = filesystemOf(harness, uid);

        ReadResult result = fs.read(RuntimeContext.empty(), "/" + rel, 0, 0);
        if (!result.isSuccess() || result.fileData() == null) {
            throw new WorkspaceAdminException("读取失败: " + rel + "（" + result.error() + "）");
        }
        String content = result.fileData().content() == null ? "" : result.fileData().content();
        boolean truncated = content.length() > MAX_READ_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_READ_CHARS);
        }
        return new WorkspaceViews.FileView(agentName, uid, rel, content,
                content.length(), guard.isManaged(rel), truncated);
    }

    // ==================== 写入 ====================

    /** 覆盖写（没有则创建）。返回最终的相对路径，页面据此刷新并定位到该文件。 */
    public String writeFile(String agentName, String userId, String rawPath, String content) {
        HarnessAgent harness = harnessOf(agentName);
        String uid = normalizeUserId(userId);
        String rel = guard.validate(rawPath);
        // 写要额外挡掉「裸的目录名」（subagents / knowledge）：那一档对列目录、删空目录是必需的。
        guard.assertWritableFile(rel);
        if (content == null) {
            throw new WorkspaceAdminException("内容不能为 null；要清空请传空字符串");
        }
        if (content.length() > MAX_WRITE_CHARS) {
            throw new WorkspaceAdminException(
                    "内容过长（" + content.length() + " 字符，上限 " + MAX_WRITE_CHARS + "）");
        }
        WorkspaceManager view = viewOf(harness, uid);
        view.writeUtf8WorkspaceRelative(RuntimeContext.empty(), rel, content);
        log.info("[Workspace] 写入 {} / {} / {}（{} 字符）", agentName, uid, rel, content.length());
        return rel;
    }

    // ==================== 删除 ====================

    /**
     * 删除一个文件，或一个<b>空</b>目录。
     *
     * <p>目录只允许空目录：框架的 {@code delete} 对目录是递归语义，一次误点就能带走整个
     * {@code knowledge/}。工作区文件没有回收站，这种操作不能提供"顺手"的入口。
     */
    public WorkspaceViews.DeleteResult delete(String agentName, String userId, String rawPath) {
        HarnessAgent harness = harnessOf(agentName);
        String uid = normalizeUserId(userId);
        String rel = guard.validate(rawPath);
        guard.assertManaged(rel);
        AbstractFilesystem fs = filesystemOf(harness, uid);
        RuntimeContext rc = RuntimeContext.empty();

        /*
         * 先确认目标存在。
         *
         * 框架的 delete 对「根本不存在的路径」也返回成功，于是页面会对一个早已不存在的东西
         * 弹「已删除」—— 看着无害，但那是假话：两个标签页同时开着这一页时，
         * 第二个人永远不知道自己删的是空气。这里回 404，页面据此提示刷新。
         */
        if (!fs.exists(rc, "/" + rel)) {
            throw new WorkspaceAdminException("目标不存在（可能已被删除）: " + rel, true);
        }
        LsResult probe = fs.ls(rc, "/" + rel);
        if (probe.isSuccess() && !nullSafe(probe.entries()).isEmpty()) {
            throw new WorkspaceAdminException(
                    "目录非空，本页只允许删除空目录（避免误删整个目录）: " + rel);
        }
        WriteResult result = fs.delete(rc, "/" + rel);
        if (!result.isSuccess()) {
            throw new WorkspaceAdminException("删除失败: " + rel + "（" + result.error() + "）");
        }
        log.info("[Workspace] 删除 {} / {} / {}", agentName, uid, rel);
        return new WorkspaceViews.DeleteResult(rel, true);
    }

    // ==================== 内部 ====================

    private HarnessAgent harnessOf(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            throw new WorkspaceAdminException("agentName 不能为空");
        }
        Agent agent = agentManager.get(agentName);
        if (agent == null) {
            throw new WorkspaceAdminException("agent 不存在或未装配: " + agentName, true);
        }
        if (!(agent instanceof HarnessAgent harness)) {
            throw new WorkspaceAdminException("只有 HARNESS agent 有工作区: " + agentName);
        }
        return harness;
    }

    /**
     * 取绑定到 (userId) 的工作区视图。
     *
     * <p>{@code sessionId} 传 null 是刻意的：工作区的隔离在 USER 作用域下只认 userId，
     * 顺手传一个假 sessionId 反而会让命名空间多出一段、指到一个根本不存在的位置。
     */
    private WorkspaceManager viewOf(HarnessAgent harness, String userId) {
        return harness.workspaceFor(userId, null);
    }

    private AbstractFilesystem filesystemOf(HarnessAgent harness, String userId) {
        WorkspaceManager view = viewOf(harness, userId);
        AbstractFilesystem fs = view != null ? view.getFilesystem() : null;
        if (fs == null) {
            throw new WorkspaceAdminException("该 agent 没有可用的 filesystem（工作区未装配）");
        }
        return fs;
    }

    /** 目录路径归一化：空 / {@code /} 表示根；其余过 Guard 的纯字符串校验。 */
    private String normalizeDirPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath.trim())) {
            return "";
        }
        return guard.validate(rawPath);
    }

    private static String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            // 留空 = 工作区基线（模板层 / 无命名空间层）。这是合法视图，不是错误。
            return null;
        }
        String trimmed = userId.trim();
        if (!USER_ID.matcher(trimmed).matches()) {
            throw new WorkspaceAdminException(
                    "userId 非法（只允许字母、数字、下划线、连字符，1-64 位）: " + userId);
        }
        return trimmed;
    }

    private static String stripLeadingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static List<FileInfo> nullSafe(List<FileInfo> entries) {
        return entries == null ? List.of() : entries;
    }
}
