package com.md.agentplatform.server.workspace;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工作区文件路径校验。
 *
 * <p>页面传进来的相对路径，必须先过这里，才允许拼成磁盘路径。这是「页面维护工作区」
 * 整个功能里唯一的安全边界：漏一处就是任意文件读写。抽成独立类是为了能对纯字符串
 * 逻辑写单测，不需要起 Spring 容器。
 *
 * <p>三层校验：
 * <ol>
 *   <li>字符白名单 —— 挡 Windows 特性（NTFS 备用数据流、保留设备名、段尾点/空格）
 *   <li>相对路径约束 —— 挡绝对路径、隐藏路径、{@code ..}
 *   <li>落点校验 —— normalize 之后必须仍在工作区根目录内
 * </ol>
 */
@Component
public class WorkspacePathGuard {

    /**
     * 单段允许的字符。刻意不含 {@code :}，用来挡 NTFS 备用数据流
     * （{@code a.txt::$DATA} 是 Windows 上的经典绕过手法）。
     */
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_\\-\\u4e00-\\u9fa5.]+$");

    /**
     * Windows 保留设备名。带扩展名也算设备：{@code con.md} 会被解释成 CON 设备。
     */
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** 页面可维护（<b>可写</b>）的根级文件。 */
    private static final Set<String> MANAGED_ROOT_FILES =
            Set.of("AGENTS.md", "MEMORY.md", "tools.json");

    /**
     * 页面可写的根级目录。
     *
     * <p>刻意<b>不含 {@code skills}</b>：技能的唯一维护入口是「技能」页（内容在 agentscope 库），
     * 工作区的 {@code skills/} 已废弃 —— harness 装配期也不再扫描它。页面若还允许写，
     * 就等于把"两份技能到底哪份生效"这个问题重新制造出来。
     *
     * <p>也刻意不含 {@code agents/}（harness 运行时数据根）与 {@code memory/}：它们的写入者是框架，
     * 页面改完会被下一次写入静默覆盖，表现成"保存成功了但没生效"。这两者归到只读一档。
     */
    private static final Set<String> MANAGED_ROOT_DIRS = Set.of("subagents", "knowledge");

    /**
     * 页面上「只读可见」的根级目录。
     *
     * <p>读放开是为了能<b>看见</b>运行时产物（{@code memory/}、{@code agents/}、{@code plans/}）
     * 与历史遗留目录（{@code skills/}）—— 排查"agent 当时到底读到了什么"只有这一条路。
     *
     * <p>注意这一档<b>只放开了读</b>：写不放开的原因见 {@link #MANAGED_ROOT_DIRS}，
     * 删也不放开（{@code WorkspaceAdminService#delete} 走的是 {@link #assertManaged}）。
     * 换句话说「可见」不等于「可清理」：删掉 memory/ 这类运行时产物是「会话记录 / 状态存储」
     * 两页的职责，在文件管理页上再开一个入口，只会让人分不清该在哪一页动手。
     */
    private static final Set<String> READONLY_ROOT_DIRS =
            Set.of("memory", "plans", "agents", "skills");

    /** 可见目录 = 可写 ∪ 只读。求并集而不是再手写一份清单，避免两处漂移。 */
    private static final Set<String> VISIBLE_ROOT_DIRS =
            Stream.concat(MANAGED_ROOT_DIRS.stream(), READONLY_ROOT_DIRS.stream())
                    .collect(Collectors.toUnmodifiableSet());

    private static final int MAX_SEGMENT_LEN = 120;
    private static final int MAX_SEGMENTS = 12;

    /**
     * 校验并解析成工作区内的绝对路径。
     *
     * @param workspaceRoot 该 agent 的工作区根目录
     * @param rawRelPath    页面传来的相对路径
     * @throws WorkspacePathException 路径为空 / 绝对 / 隐藏 / 含 {@code ..} / 越界 / 含非法字符
     */
    public Path resolve(Path workspaceRoot, String rawRelPath) {
        String rel = validate(rawRelPath);
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path target = root.resolve(rel).normalize();
        // normalize() 之后再比较，否则 "a/../../b" 能绕过
        if (target.equals(root) || !target.startsWith(root)) {
            throw new WorkspacePathException("路径越界: " + rawRelPath);
        }
        return target;
    }

    /** 在 {@link #resolve} 之上再限制为「页面该管的文件」。 */
    public Path resolveManaged(Path workspaceRoot, String rawRelPath) {
        assertManaged(rawRelPath);
        return resolve(workspaceRoot, rawRelPath);
    }

    /** 只做范围校验，不解析路径。 */
    public void assertManaged(String rawRelPath) {
        assertWithin(rawRelPath, MANAGED_ROOT_DIRS, "可写范围");
    }

    /**
     * 写文件用的范围校验：在 {@link #assertManaged} 之上再拒掉「裸的目录名」。
     *
     * <p>为什么单独一档：写操作的语义是"把这个路径当文件覆盖写"。而裸名这一档为了
     * 让"列目录 / 删空目录"能表达出来，放行了目录名 —— 于是路径写成 {@code subagents}
     * 时保护校验会通过，真去写才在框架层撞上一个同名目录，报错既不解释原因也晚了。
     * 在入口处直接拦下来，顺带把正确写法说清楚。
     */
    public void assertWritableFile(String rawRelPath) {
        String rel = validate(rawRelPath);
        if (MANAGED_ROOT_DIRS.contains(rel)) {
            throw new WorkspacePathException(
                    "这是目录名，不是文件路径。要写文件请写成 " + rel + "/<文件名>: " + rawRelPath);
        }
        assertManaged(rel);
    }

    /**
     * 只做「可见范围」校验，不解析路径。可见 = 可写 ∪ 只读。
     *
     * <p>读取走这一档：既能看到页面维护的文件，也能看到运行时产物与历史遗留目录，但不能写。
     */
    public void assertVisible(String rawRelPath) {
        assertWithin(rawRelPath, VISIBLE_ROOT_DIRS, "可见范围");
    }

    /**
     * 范围校验的公共实现。
     *
     * <p>路径分两种形态，判定必须分开：
     *
     * <ul>
     *   <li><b>裸名</b>（{@code AGENTS.md}、{@code subagents}）—— 它既可能是根级文件，
     *       也可能是<b>根级目录的裸名</b>："进入 subagents"、"删掉空的 subagents"
     *       传过来的就是这个名字本身。所以裸名要按「文件白名单 ∪ 本档目录白名单」判；</li>
     *   <li><b>带层级</b>（{@code subagents/planner.md}）—— 只要求第一段属于本档目录。</li>
     * </ul>
     *
     * <p>2026-09-23 修：原先裸名只查 {@link #MANAGED_ROOT_FILES}，于是 {@code subagents}、
     * {@code knowledge}、{@code skills} 这些目录名一律被拒。表现是"根目录列得出来、点进去 400"——
     * 页面等于只能看不能进。当时单测全带层级，正好绕过了这条分支；
     * 这一版补了裸名用例（{@code acceptsBareRootDirectoryNames}）。
     */
    private void assertWithin(String rawRelPath, Set<String> dirs, String scope) {
        String rel = validate(rawRelPath);
        int slash = rel.indexOf('/');
        if (slash < 0) {
            if (MANAGED_ROOT_FILES.contains(rel) || dirs.contains(rel)) {
                return;
            }
            throw new WorkspacePathException("不在" + scope + "，根级只允许文件 "
                    + MANAGED_ROOT_FILES + " 与目录 " + dirs + ": " + rel);
        }
        String top = rel.substring(0, slash);
        if (!dirs.contains(top)) {
            throw new WorkspacePathException(
                    "不在" + scope + "，不允许目录 " + top + "/（允许：" + dirs + "）");
        }
    }

    /**
     * 范围判断（不抛异常）—— 供「列目录时过滤条目」使用。
     *
     * <p>列目录不能因为遇到一个不该看的条目就整页失败：工作区根下必然混着
     * {@code code/}、{@code .index/} 这类东西，正确行为是跳过它们、把能看的返回。
     * 所以判定逻辑必须和 {@link #assertManaged} 共用同一份白名单，不能另写一套。
     */
    public boolean isManaged(String rawRelPath) {
        return within(rawRelPath, MANAGED_ROOT_DIRS);
    }

    /** @see #isManaged 只读档的对应版本。 */
    public boolean isVisible(String rawRelPath) {
        return within(rawRelPath, VISIBLE_ROOT_DIRS);
    }

    private boolean within(String rawRelPath, Set<String> dirs) {
        try {
            assertWithin(rawRelPath, dirs, "范围");
            return true;
        } catch (WorkspacePathException e) {
            return false;
        }
    }

    /**
     * 工作区根级布局的只读视图。
     *
     * <p>暴露给服务层是必要的：工作区根<b>不能</b>用 {@code ls("/")} 来发现有哪些子目录 ——
     * REMOTE 模式下根目录走的是 CompositeFilesystem 的 defaultBackend（本地盘），
     * 而 {@code memory/}、{@code agents/} 这些在 KV 路由上，列不出来。
     * 所以根级清单必须由这份白名单驱动，下层才用 ls 下钻。
     */
    public Set<String> managedRootFiles() {
        return MANAGED_ROOT_FILES;
    }

    /** @see #managedRootFiles() 可写目录。 */
    public Set<String> managedRootDirs() {
        return MANAGED_ROOT_DIRS;
    }

    /** @see #managedRootFiles() 全部可见目录（可写 ∪ 只读）。 */
    public Set<String> visibleRootDirs() {
        return VISIBLE_ROOT_DIRS;
    }

    /**
     * 纯字符串校验，不碰磁盘。返回归一化后的相对路径（分隔符统一为 {@code /}）。
     *
     * <p>不 trim 首尾空白，而是拒绝：静默归一化会让「用户以为存了 A、实际存了 B」
     * 这种情况难以排查，安全边界上宁可报错。
     */
    public String validate(String rawRelPath) {
        if (rawRelPath == null || rawRelPath.isBlank()) {
            throw new WorkspacePathException("路径不能为空");
        }
        String rel = rawRelPath.replace('\\', '/');
        if (!rel.equals(rel.strip())) {
            throw new WorkspacePathException("路径首尾不能有空白字符");
        }
        if (rel.startsWith("/")) {
            throw new WorkspacePathException("不允许绝对路径");
        }
        if (rel.startsWith(".")) {
            throw new WorkspacePathException("不允许隐藏路径");
        }
        if (rel.endsWith("/")) {
            throw new WorkspacePathException("路径不能以 / 结尾");
        }

        String[] segments = rel.split("/", -1);
        if (segments.length > MAX_SEGMENTS) {
            throw new WorkspacePathException("路径层级过深，最多 " + MAX_SEGMENTS + " 层");
        }
        for (String seg : segments) {
            if (seg.isEmpty()) {
                throw new WorkspacePathException("路径含空段");
            }
            if (".".equals(seg) || "..".equals(seg)) {
                throw new WorkspacePathException("路径含相对段: " + seg);
            }
            if (seg.length() > MAX_SEGMENT_LEN) {
                throw new WorkspacePathException("路径段过长: " + seg);
            }
            if (seg.endsWith(".") || seg.endsWith(" ")) {
                throw new WorkspacePathException("路径段不能以点或空格结尾: " + seg);
            }
            if (!SEGMENT.matcher(seg).matches()) {
                throw new WorkspacePathException("路径段含非法字符: " + seg);
            }
            if (RESERVED.contains(baseName(seg))) {
                throw new WorkspacePathException("路径段是系统保留名: " + seg);
            }
        }
        return rel;
    }

    private static String baseName(String segment) {
        int dot = segment.indexOf('.');
        return (dot < 0 ? segment : segment.substring(0, dot)).toLowerCase(Locale.ROOT);
    }
}
