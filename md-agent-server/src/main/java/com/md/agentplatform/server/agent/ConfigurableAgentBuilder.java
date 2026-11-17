package com.md.agentplatform.server.agent;

import com.md.agentplatform.server.agent.middleware.AgentRunTraceMiddleware;
import com.md.agentplatform.server.config.AgentPlatformProperties;
import com.md.agentplatform.server.model.DbModelFactory;
import com.md.agentplatform.server.trace.AgentTraceRun;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Agent 装配器 —— 由 {@link AgentDefinition} typed 字段直接调用框架 builder setter。
 *
 * <p>REACT 与 HARNESS 分两条装配链。新增框架参数 = AgentDefinition 加 typed 字段 +
 * 本类加一行 setter 调用 + {@link AgentDefinitionResolver} 加映射，无反射兜底。
 */
@Component
public class ConfigurableAgentBuilder {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableAgentBuilder.class);

    /**
     * 状态表名白名单：字母 / 数字 / 下划线 / 连字符。
     *
     * <p>长度上限取 64 —— MySQL 表名上限就是这个数，放行一个超长的名字只会在建表时炸，
     * 不如在这里一次说清。连字符是允许的（框架会用反引号包标识符），反引号本身不允许。
     */
    private static final Pattern STATE_TABLE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AgentPlatformProperties platformProperties;

    @Autowired
    private DataSource dataSource;

    /**
     * 全局运行过程记录中间件。本身无状态，可安全共享给所有 agent 与子 agent
     * （框架会把非 HarnessRuntimeMiddleware 的中间件复制给子 agent）。
     *
     * <p>改为容器注入：它现在依赖 AgentTraceRegistry（把各阶段写进调用链路观测表），
     * 自己 new 拿不到那个依赖。全局单例共享的语义不变。
     */
    @Autowired
    private AgentRunTraceMiddleware runTraceMiddleware;

    /** MCP 工厂：按名从控制面表（{@code md_mcp_server}）解析端点并建链。 */
    @Autowired
    private DbMcpClientFactory dbMcpClientFactory;

    /** 技能内容的唯一权威仓库（官方 MySQL 实现）；按名收窄后交给 builder。 */
    @Autowired
    private AgentSkillRepository skillContentRepository;

    /**
     * 工作区文件 KV。只有 {@code fs_backend=REMOTE} 的 HARNESS agent 会用到它，
     * 所以"bean 在、表里没数据"是正常状态，不代表装配漏了。
     */
    @Autowired
    private BaseStore workspaceKvStore;

    /**
     * 模型解析入口（DB 优先、Bean 兜底）。
     *
     * <p>改造前这里是 {@code getBean(modelRef, Model.class)} 一句话，模型只能来自
     * application.yml 里的那一个全局 Bean。现在交由 {@link DbModelFactory} 解析：
     * {@code model_ref} 填 md_model.model_key，旧值（Bean 名）仍然可装配。
     */
    @Autowired
    private DbModelFactory modelFactory;

    /**
     * 唯一装配入口。subAgentResolver 由 AgentManager 提供（agents::get，支持运行时新建即路由）。
     *
     * <p>顺带在这里登记 agent 类型：观测中间件的回调只会拿到内层 ReActAgent
     * （原因见 {@link AgentRunTraceMiddleware} 里 agentKinds 的注释），
     * 「这个 run 是 react 还是 harness」这件事只有装配期知道，登记与分派写在一处才不会漏。
     *
     * <p>登记放在 build 成功之后：build 抛异常时不该用新类型覆盖掉仍在服役的旧实例，
     * 否则一个装配失败就会把老 agent 的后续 run 全标错。
     *
     * <p><b>本方法可以在任何线程上被调用</b>（启动期是 main，页面发布/重载是 Netty 事件循环
     * 线程），内部的线程交接见 {@link #onAssemblyThread}。
     */
    public Agent build(AgentDefinition def, Function<String, Agent> subAgentResolver) {
        return onAssemblyThread(() -> {
            boolean harness = def.getType() == AgentDefinition.Type.HARNESS;
            Agent agent = harness ? buildHarness(def, subAgentResolver) : buildReact(def);
            runTraceMiddleware.registerAgentKind(def.getName(),
                    harness ? AgentTraceRun.AGENT_KIND_HARNESS : AgentTraceRun.AGENT_KIND_REACT);
            return agent;
        }, def.getName());
    }

    /** 装配的兜底超时：只为「线程永远挂住」这种情况兜底，正常装配是秒级。 */
    private static final long ASSEMBLY_TIMEOUT_MS = Duration.ofMinutes(5).toMillis();

    /**
     * 在「允许阻塞的线程」上执行装配，等它结束后把结果交回调用方。
     *
     * <h2>为什么必须有这一步（2026-09-24 的真实故障）</h2>
     * 装配过程会调用框架侧的 {@code Mono#block()}：MCP 握手（{@code DbMcpClientFactory}）、
     * Toolkit 注册 MCP 客户端（框架 {@code Toolkit.registration().apply()}）等。
     * 而 Reactor 在<b>事件循环线程</b>上调用 {@code block()} 会直接抛
     * {@code IllegalStateException: block()/blockFirst()/blockLast() are blocking,
     * which is not supported in thread reactor-http-nio-N} —— 框架内的 block() 我们改不了，
     * 只能保证装配不跑在那条线程上。
     *
     * <p>踩坑的入口是「页面发布 → 热装配」：{@code AgentController} 的 publish / reload 是同步
     * handler，跑在 Netty 事件循环线程上。于是同一份定义，启动期装配（main 线程）一切正常，
     * 页面发布出来的实例却少了全部 MCP 工具 —— 因为 {@code DbMcpClientFactory.resolveAll}
     * 把每个 MCP 的异常吞成一行 ERROR 后继续装配，agent 照样建成功，只有工具悄悄少了。
     *
     * <p>为什么放在这里而不是逐个控制器加 {@code subscribeOn(boundedElastic)}：装配入口只有一个，
     * 在这里交接对所有触发方（发布任何资源、重载、导入、启动）一次生效，将来新增入口也不会漏。
     * 代价是调用方在等结果期间被占住（控制面的人工操作，秒级，可接受）；要让事件循环彻底不阻塞，
     * 那是控制器层 {@code Mono.fromCallable(...).subscribeOn(boundedElastic())} 的整理
     * （约定见 {@code ModelController} §4），与本方法的正确性无关。
     */
    static <T> T onAssemblyThread(Supplier<T> work, String agentName) {
        if (!Schedulers.isInNonBlockingThread()) {
            // 启动期（main）与弹性线程：直接用当前线程，不做多余的线程搬运
            return work.get();
        }
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Schedulers.boundedElastic().schedule(() -> {
            try {
                value.set(work.get());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(ASSEMBLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("装配超时（" + ASSEMBLY_TIMEOUT_MS + "ms）: " + agentName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("装配被中断: " + agentName, e);
        }
        Throwable failure = error.get();
        if (failure != null) {
            // 原样抛出：AgentManager / ControlPlanePublishService 的异常处理依赖异常类型与消息
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("装配失败: " + agentName, failure);
        }
        return value.get();
    }

    // ==================== REACT ====================

    private Agent buildReact(AgentDefinition def) {
        validateTools(def);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(def.getName())
                .model(modelFactory.resolve(def.getModelRef()))
                .toolkit(buildToolkit(def));

        if (notBlank(def.getDescription())) {
            builder.description(def.getDescription());
        }
        if (notBlank(def.getSysPrompt())) {
            builder.sysPrompt(def.getSysPrompt());
        }
        AgentSkillRepository repo = resolveSkillRepository(def);
        if (repo != null) {
            builder.skillRepository(repo);
        }
        applyStateStore(builder, def);
        applyTuning(builder, def);
        applyTrace(builder);
        return builder.build();
    }

    // ==================== HARNESS ====================

    private Agent buildHarness(AgentDefinition def, Function<String, Agent> subAgentResolver) {
        validateTools(def);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(def.getName())
                .workspace(Path.of(platformProperties.getWorkspaceRoot(), resolveHarnessWorkspace(def)))
                .model(modelFactory.resolve(def.getModelRef()));

        applyFilesystem(builder, def);
        applySkillSourcePolicy(builder, def);

        // 工具 bean / MCP 任一非空才显式给 toolkit：空 toolkit 会冲掉 harness 框架默认工具集
        if (hasToolkit(def)) {
            builder.toolkit(buildToolkit(def));
        }
        if (notBlank(def.getDescription())) {
            builder.description(def.getDescription());
        }
        if (notBlank(def.getSysPrompt())) {
            builder.sysPrompt(def.getSysPrompt());
        }
        AgentSkillRepository repo = resolveSkillRepository(def);
        if (repo != null) {
            builder.skillRepository(repo);
        }
        applyStateStore(builder, def);
        applyTuning(builder, def);
        applyTrace(builder);
        for (String subAgentName : def.getSubAgents()) {
            builder.subagentFactory(subAgentName, subAgentResolver);
        }
        return builder.build();
    }

    // ==================== HARNESS 文件后端 ====================

    /**
     * HARNESS 的技能来源治理：把框架的 4 层技能叠加压到「只来自页面选中的 skillNames」。
     *
     * <p>框架的技能源是 4 层叠加、<b>同名 later wins</b>（见 HarnessSkillMiddleware 类注释）。
     * 本平台只保留 L2，其余三层全部不启用：
     * <ol>
     *   <li>L1 {@code projectGlobalSkillsDir} —— 不再接线，恒为无；</li>
     *   <li>L2 页面选中的 {@code skillNames} → {@code NamedSkillRepository}(DB) —— <b>唯一保留的一层</b>；</li>
     *   <li>L3 {@code <workspace>/skills} 本地目录 —— 用下面这行关掉；</li>
     *   <li>L4 filesystem 命名空间视图的 {@code skills/} —— 用下面这行关掉。</li>
     * </ol>
     *
     * <p>不关的后果实测过：{@code workspace/harness/<agent>/skills/<name>/SKILL.md} 会
     * <b>静默覆盖</b> DB 里的同名技能 —— 页面上改了内容、跑起来还是旧的，且全程没有任何报错。
     * 技能只在页面维护是明确约定，所以这里是<b>无条件</b>关闭，不看 DB 字段。工作区里已有的
     * 技能文件只保留为历史数据，<b>不会删除，也不会再被加载</b>。
     */
    private void applySkillSourcePolicy(HarnessAgent.Builder builder, AgentDefinition def) {
        builder.disableDefaultWorkspaceSkills();
        logLegacyWorkspaceSkillsPresent(def);
    }

    /**
     * 工作区里的历史技能目录只提示、不参与装配。
     *
     * <p>技能初始化已改为 MySQL 单源，L3 扫描也已关闭；但目录仍在盘上，容易被误认为
     * "文件里的技能还在生效"。这里把目录名打出来，明确它是历史数据、不会被加载。
     * <b>只读不改盘</b>：workspace 中的技能与 prompt 数据不允许删除。
     */
    private void logLegacyWorkspaceSkillsPresent(AgentDefinition def) {
        Path skillsDir = Path.of(platformProperties.getWorkspaceRoot(),
                resolveHarnessWorkspace(def), "skills");
        if (!Files.isDirectory(skillsDir)) {
            return;
        }
        try (var entries = Files.list(skillsDir)) {
            List<String> leftovers = entries
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
            if (!leftovers.isEmpty()) {
                log.info("[Agent] {} 的工作区里存在历史技能目录 {}（{}）—— 已按 MySQL 单源策略忽略，"
                                + "文件保留但不再参与加载。",
                        def.getName(), skillsDir.toAbsolutePath(), leftovers);
            }
        } catch (IOException e) {
            log.warn("[Agent] {} 检查工作区技能目录失败: {}", def.getName(), e.getMessage());
        }
    }

    /**
     * 会话事件重放日志的 tenant 段。
     *
     * <p>框架侧由 {@code TranscriptRef} 的规范构造器兜底成 {@code "default"}
     * （{@code HarnessAgent.Builder.transcriptTenant()} 不配就是它）。
     * 这里必须与框架取值一致，否则 events 目录的路由会失配、又落回本地盘。
     * 将来若把 transcriptTenant 提到 AgentDefinition 上，这里要一并改成读它。
     */
    private static final String TRANSCRIPT_TENANT = "default";

    /**
     * 工作区文件后端装配。
     *
     * <p><b>LOCAL（默认）</b>：什么都不做 —— 走框架默认的 {@code LocalFilesystemWithShell}，
     * 本地磁盘 + 能跑 shell，单进程语义。这是历史行为，保持零改动。
     *
     * <p><b>REMOTE</b>：工作区数据进 {@code workspace-kv-database} 的 KV，多副本天然共享；
     * 代价是 composite 模式<b>没有 shell</b>（框架的设计如此，要 shell 只能上沙箱 spec）。
     *
     * <p>下面三条额外路由是 REMOTE 模式下必须补的：官方内置路由只覆盖 10 个固定前缀，
     * 落在这三条路径上的写入会<b>绕过 KV 直接写本地盘</b>，形成"一半在库、一半在盘"的分裂视图。
     * 逐条理由与完整映射表见 docs/develop/2026-09-23_harness-workspace-to-mysql.md。
     */
    private void applyFilesystem(HarnessAgent.Builder builder, AgentDefinition def) {
        if (!isRemoteFs(def)) {
            return;
        }
        if (workspaceKvStore == null) {
            throw new IllegalStateException("agent " + def.getName()
                    + " 声明了 fs_backend=REMOTE，但工作区 KV 不可用（workspaceKvStore bean 缺失）");
        }
        NamespaceFactory base = IsolationScope.USER.toNamespaceFactory();
        builder.filesystem(new RemoteFilesystemSpec(workspaceKvStore)
                .isolationScope(IsolationScope.USER));
        // ① 会话事件重放日志：key 首段是 tenant（default/…），匹配不上内置的 agents/<id>/sessions/
        builder.filesystemRoute(TRANSCRIPT_TENANT + "/", remoteFor(base, "transcripts"));
        // ② 消息总线 + 异步工具登记：busRoot 在框架里写死为 ".agentscope/bus"，同样不在内置路由里
        builder.filesystemRoute(".agentscope/bus/", remoteFor(base, "bus"));
        // ③ git 工作区必须留本地：GitlabWorkspaceManager 会把 archive 解压到本地目录，
        //    文件分析工具也直接读本地磁盘，不经过 filesystem 抽象。不补这条，代码目录会落到 KV，
        //    agent 与 git 工具看到的视图分裂，git agent 直接失效。
        builder.filesystemRoute("code/", new LocalFilesystem(
                Path.of(platformProperties.getWorkspaceRoot(), "code"), false, 10, base));
        log.info("[Agent] {} 工作区文件后端 = REMOTE（KV）；注意：该模式没有 shell", def.getName());
    }

    /**
     * fs_backend 判定：只认显式的 REMOTE。
     *
     * <p>null / 空 / LOCAL / 拼错的值统统按 LOCAL 处理。"没配"与"配了不认识的值"
     * 都不该把一个生产 agent 悄悄切到无 shell 的形态上 —— 那种故障极难定位。
     */
    private static boolean isRemoteFs(AgentDefinition def) {
        return "REMOTE".equalsIgnoreCase(def.getFsBackend());
    }

    /**
     * 按路由段生成一个 KV 后端：命名空间 = 隔离段（agents/&lt;id&gt;/users/&lt;uid&gt;）+ 路由段。
     *
     * <p>追加路由段是必须的：这些路径不属于任一内置路由，若不追加，它们的 key 会直接落在
     * 隔离段根上，与其它路由的 key 混在同一个命名空间里。
     */
    private AbstractFilesystem remoteFor(NamespaceFactory base, String segment) {
        return new RemoteFilesystem(workspaceKvStore, rc -> {
            List<String> namespace = new ArrayList<>(base.getNamespace(rc));
            namespace.add(segment);
            return namespace;
        });
    }

    // ==================== 共用参数 ====================

    private void applyStateStore(ReActAgent.Builder builder, AgentDefinition def) {
        AgentStateStore store = resolveStateStore(def);
        if (store != null) {
            builder.stateStore(store);
        }
    }

    /**
     * HARNESS 的状态存储。
     *
     * <p>agentscope 2.1 的 HarnessAgent 默认使用 EVENT_LOG 会话历史：该模式下 ReActAgent
     * 只把 checkpoint 写进 SessionLogStore，不会走 stateStore。这里显式切回 LEGACY，
     * 否则 md_agent_runtime.state_store 配了也不生效，会话不会留下记忆。
     */
    private void applyStateStore(HarnessAgent.Builder builder, AgentDefinition def) {
        AgentStateStore store = resolveStateStore(def);
        if (store != null) {
            builder.stateStore(store);
            builder.legacySessionHistory(true);
        }
    }

    /**
     * 运行过程记录：所有 agent 无条件挂载，等价于 hook 时代的全局兜底
     * （框架没有 addSystemMiddleware 之类的全局注册口，本类是所有 agent 的唯一装配入口）。
     *
     * <p>在 {@code build()} 之前注册 ⇒ 排在框架内建中间件之前 ⇒ 位于链最外层，
     * 因此 {@code onAgent} 看到的是压缩、上下文注入之前的原始输入。
     */
    private void applyTrace(ReActAgent.Builder builder) {
        builder.middleware(runTraceMiddleware);
    }

    private void applyTrace(HarnessAgent.Builder builder) {
        builder.middleware(runTraceMiddleware);
    }

    /**
     * 调优参数装配。框架侧 maxIters/enableMetaTool 等是原始类型，AgentDefinition 里是包装类型，
     * 须判空再拆箱；仅装配当前 builder 支持的字段。REACT 与 HARNESS 的 builder 类型互不继承、
     * 又不共享接口，共有参数在两条链重复 typed 调用（可接受的小幅重复，换取编译期类型安全）。
     */
    private void applyTuning(ReActAgent.Builder builder, AgentDefinition def) {

        if (def.getMaxIters() != null) {
            builder.maxIters(def.getMaxIters());
        }
        if (def.getMaxRetries() != null) {
            builder.maxRetries(def.getMaxRetries());
        }
        if (notBlank(def.getDefaultSessionId())) {
            builder.defaultSessionId(def.getDefaultSessionId());
        }
        if (def.getEnableMetaTool() != null) {
            builder.enableMetaTool(def.getEnableMetaTool());
        }
        if (def.getEnablePendingToolRecovery() != null) {
            builder.enablePendingToolRecovery(def.getEnablePendingToolRecovery());
        }
        // 多中间件按声明顺序逐个注册：框架按注册顺序包洋葱，顺序即执行先后
        for (String middlewareBean : def.getMiddleware()) {
            builder.middleware(getBean(middlewareBean, MiddlewareBase.class));
        }
        if (def.getCheckRunning() != null) {
            builder.checkRunning(def.getCheckRunning());
        }
        if (def.getEnableTaskList() != null) {
            builder.enableTaskList(def.getEnableTaskList());
        }
        if (def.getStopOnReject() != null) {
            builder.stopOnReject(def.getStopOnReject());
        }
        if (notBlank(def.getFallbackModelRef())) {
            builder.fallbackModel(modelFactory.resolve(def.getFallbackModelRef()));
        }
        if (def.getDynamicSkillsEnabled() != null) {
            builder.dynamicSkillsEnabled(def.getDynamicSkillsEnabled());
        }
        if (def.getSkillCodeExecutionEnabled() != null) {
            builder.skillCodeExecutionEnabled(def.getSkillCodeExecutionEnabled());
        }
        if (notBlank(def.getSkillWorkDir())) {
            builder.skillWorkDir(Path.of(platformProperties.getWorkspaceRoot(), def.getSkillWorkDir()));
        }
        // 状态冲突策略：列里存字符串（OVERWRITE / FAIL / APPEND_MERGE）。
        // 非法值直接抛而不是降级 —— 这是个"配错了会静默改状态合并行为"的项，必须让它响
        if (notBlank(def.getConflictPolicy())) {
            builder.conflictPolicy(ConflictPolicy.valueOf(def.getConflictPolicy().trim().toUpperCase()));
        }
        builder.permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build());
    }

    private void applyTuning(HarnessAgent.Builder builder, AgentDefinition def) {
        if (def.getMaxIters() != null) {
            builder.maxIters(def.getMaxIters());
        }
        if (def.getMaxRetries() != null) {
            builder.maxRetries(def.getMaxRetries());
        }
        if (notBlank(def.getDefaultSessionId())) {
            builder.defaultSessionId(def.getDefaultSessionId());
        }
        if (def.getEnableMetaTool() != null) {
            builder.enableMetaTool(def.getEnableMetaTool());
        }
        if (def.getEnablePendingToolRecovery() != null) {
            builder.enablePendingToolRecovery(def.getEnablePendingToolRecovery());
        }
        // 多中间件按声明顺序逐个注册：框架按注册顺序包洋葱，顺序即执行先后
        for (String middlewareBean : def.getMiddleware()) {
            builder.middleware(getBean(middlewareBean, MiddlewareBase.class));
        }
        if (def.getCheckRunning() != null) {
            builder.checkRunning(def.getCheckRunning());
        }
        if (def.getEnableTaskList() != null) {
            builder.enableTaskList(def.getEnableTaskList());
        }
        if (def.getStopOnReject() != null) {
            builder.stopOnReject(def.getStopOnReject());
        }
        if (notBlank(def.getFallbackModelRef())) {
            builder.fallbackModel(modelFactory.resolve(def.getFallbackModelRef()));
        }
        if (notBlank(def.getAgentId())) {
            builder.agentId(def.getAgentId());
        }
        if (notBlank(def.getEnvironment())) {
            builder.environment(def.getEnvironment());
        }
        if (notBlank(def.getEnvironmentMemory())) {
            builder.environmentMemory(def.getEnvironmentMemory());
        }
        // project-global-skills-dir 不再接线：技能内容的唯一来源是按 skill-names 从 MySQL 解析，
        // 文件目录只作为历史数据保留，不参与装配。
        if (def.getMaxContextTokens() != null) {
            builder.maxContextTokens(def.getMaxContextTokens());
        }
        if (def.getEnableAgentTracingLog() != null) {
            builder.enableAgentTracingLog(def.getEnableAgentTracingLog());
        }
        if (def.getSkillsEnabled() != null) {
            builder.skillsEnabled(def.getSkillsEnabled());
        }
        if (!def.getEnableSkills().isEmpty()) {
            builder.enableSkills(def.getEnableSkills().toArray(new String[0]));
        }
        if (!def.getDisableSkills().isEmpty()) {
            builder.disableSkills(def.getDisableSkills().toArray(new String[0]));
        }
        if (def.getEnablePlanMode() != null) {
            builder.enablePlanMode(def.getEnablePlanMode());
        }
        if (notBlank(def.getPlanFileDirectory())) {
            builder.planFileDirectory(def.getPlanFileDirectory());
        }
        if (def.getPlanModeAllowShell() != null) {
            builder.allowShellInPlanMode(def.getPlanModeAllowShell());
        }
        if (def.getUseLegacyXmlWorkspaceContext() != null) {
            builder.useLegacyXmlWorkspaceContext(def.getUseLegacyXmlWorkspaceContext());
        }
        applyAdditionalContextFiles(builder, def);
        applySkillManageTool(builder, def);
        applyDisableFlags(builder, def);
        applyMemory(builder, def);
    }

    private String resolveHarnessWorkspace(AgentDefinition def) {
        return (def.getWorkspace() != null && !def.getWorkspace().isBlank())
                ? def.getWorkspace().trim() : "harness/" + def.getName();
    }

    // ==================== HARNESS 扩展装配（压缩 / 记忆 / 驱逐 / 三态开关） ====================

    /** 附加上下文文件：逐个透传（框架侧是追加语义，可多次调用）。 */
    private void applyAdditionalContextFiles(HarnessAgent.Builder builder, AgentDefinition def) {
        for (String file : def.getAdditionalContextFiles()) {
            if (notBlank(file)) {
                builder.additionalContextFile(file.trim());
            }
        }
    }

    /**
     * 技能管理工具（让 agent 自己起草 / 晋升技能）。
     *
     * <p>配置项存在即视为开启：页面上填了 drafts-dir 却因为开关没勾而不生效，
     * 是典型的"配了看不出效果"。开关字段只在<b>没有配置</b>时才单独生效。
     */
    private void applySkillManageTool(HarnessAgent.Builder builder, AgentDefinition def) {
        Map<String, Object> raw = def.getSkillManageConfig();
        if (raw != null && !raw.isEmpty()) {
            SkillManageConfig.Builder cfg = SkillManageConfig.builder();
            if (raw.get("autoPromote") instanceof Boolean b) {
                cfg.autoPromote(b);
            }
            if (raw.get("securityScan") instanceof Boolean b) {
                cfg.securityScan(b);
            }
            if (raw.get("draftsDir") instanceof String s && notBlank(s)) {
                cfg.draftsDir(s);
            }
            if (raw.get("mainDir") instanceof String s && notBlank(s)) {
                cfg.mainDir(s);
            }
            builder.enableSkillManageTool(cfg.build());
            return;
        }
        if (def.getSkillManageToolEnabled() != null) {
            builder.enableSkillManageTool(def.getSkillManageToolEnabled());
        }
    }

    /**
     * 13 个 {@code disableXxx()} 三态开关。
     *
     * <p>三态语义：{@code TRUE} = 调 {@code disableXxx()}；{@code FALSE} = 显式要求开启；
     * {@code null} = 未设置。后两者在这里都是「不调用任何方法」——
     * 框架这批开关的默认值各不相同（有的默认开、有的默认关），所以「显式开启」的唯一正确表达
     * 就是「什么都不做，用框架默认」，不能自作主张传 false。
     *
     * <p>这也是页面必须能区分 null 与 false 的原因（设计文档 §10.4）：
     * 否则会把「当次框架默认值」写死进库，将来框架改默认值，库里还留着旧语义。
     */
    private void applyDisableFlags(HarnessAgent.Builder builder, AgentDefinition def) {
        if (Boolean.TRUE.equals(def.getDisableFilesystemTools())) {
            builder.disableFilesystemTools();
        }
        if (Boolean.TRUE.equals(def.getDisableShellTool())) {
            builder.disableShellTool();
        }
        if (Boolean.TRUE.equals(def.getDisableMemoryTools())) {
            builder.disableMemoryTools();
        }
        if (Boolean.TRUE.equals(def.getDisableMemoryHooks())) {
            builder.disableMemoryHooks();
        }
        if (Boolean.TRUE.equals(def.getDisableTranscript())) {
            builder.disableTranscript();
        }
        if (Boolean.TRUE.equals(def.getDisableSessionPersistence())) {
            builder.disableSessionPersistence();
        }
        if (Boolean.TRUE.equals(def.getDisableWorkspaceContext())) {
            builder.disableWorkspaceContext();
        }
        if (Boolean.TRUE.equals(def.getDisableAtPathExpansion())) {
            builder.disableAtPathExpansion();
        }
        if (Boolean.TRUE.equals(def.getDisableSubagents())) {
            builder.disableSubagents();
        }
        if (Boolean.TRUE.equals(def.getDisableDynamicSubagents())) {
            builder.disableDynamicSubagents();
        }
        if (Boolean.TRUE.equals(def.getDisableDynamicSkills())) {
            builder.disableDynamicSkills();
        }
        // disable_default_workspace_skills 刻意不在这里接线：HARNESS 的技能来源统一由
        // applySkillSourcePolicy 决定（无条件关闭工作区技能扫描），DB 字段对 HARNESS 已无意义。
        if (Boolean.TRUE.equals(def.getDisableToolsConfig())) {
            builder.disableToolsConfig();
        }
    }

    /** 上下文压缩 / 记忆 / 工具结果驱逐 —— 三块同属"上下文怎么收敛"，一起装配。 */
    private void applyMemory(HarnessAgent.Builder builder, AgentDefinition def) {
        applyCompaction(builder, def);
        applyMemoryConfig(builder, def);
        applyToolResultEviction(builder, def);
    }

    /**
     * 上下文压缩。
     *
     * <p>三条互斥路径，顺序不能颠倒：
     * <ol>
     *   <li>{@code compaction-disabled=true} → 只调 {@code disableCompaction()}，
     *       后面那批阈值<b>全部忽略</b>（框架侧 disable 优先，传了也不生效）；</li>
     *   <li>没有任何阈值 → <b>什么都不传</b>，让框架用自己的默认 config。
     *       这里如果自作主张传一个空 builder，等于把框架默认值抄进我们的代码，框架升级就漂移；</li>
     *   <li>有阈值 → 建 builder，只 set 显式配过的项（builder 未 set 的字段保持框架默认）。</li>
     * </ol>
     */
    private void applyCompaction(HarnessAgent.Builder builder, AgentDefinition def) {
        if (Boolean.TRUE.equals(def.getCompactionDisabled())) {
            builder.disableCompaction();
            return;
        }
        if (!hasCompactionConfig(def)) {
            return;
        }
        CompactionConfig.Builder cfg = CompactionConfig.builder();
        if (def.getTriggerMessages() != null) {
            cfg.triggerMessages(def.getTriggerMessages());
        }
        if (def.getTriggerTokens() != null) {
            cfg.triggerTokens(def.getTriggerTokens());
        }
        if (def.getReserved() != null) {
            cfg.reserved(def.getReserved());
        }
        if (def.getKeepMessages() != null) {
            cfg.keepMessages(def.getKeepMessages());
        }
        if (def.getKeepTokens() != null) {
            cfg.keepTokens(def.getKeepTokens());
        }
        if (def.getKeepTokensMin() != null) {
            cfg.keepTokensMin(def.getKeepTokensMin());
        }
        if (def.getKeepTokensMax() != null) {
            cfg.keepTokensMax(def.getKeepTokensMax());
        }
        if (def.getKeepTokensRatio() != null) {
            cfg.keepTokensRatio(def.getKeepTokensRatio().doubleValue());
        }
        if (notBlank(def.getSummaryPrompt())) {
            cfg.summaryPrompt(def.getSummaryPrompt());
        }
        if (def.getFlushBeforeCompact() != null) {
            cfg.flushBeforeCompact(def.getFlushBeforeCompact());
        }
//        if (def.getOffloadBeforeCompact() != null) {
//            cfg.offloadBeforeCompact(def.getOffloadBeforeCompact());
//        }
        if (notBlank(def.getCompactionModelRef())) {
            cfg.model(modelFactory.resolve(def.getCompactionModelRef()));
        }
        CompactionConfig.TruncateArgsConfig truncate = buildTruncateArgs(def.getTruncateArgs());
        if (truncate != null) {
            cfg.truncateArgs(truncate);
        }
        CompactionConfig.PruneConfig prune = buildPrune(def.getPrune());
        if (prune != null) {
            cfg.prune(prune);
        }
        builder.compaction(cfg.build());
    }

    /** {@code TruncateArgsConfig}：JSON 列 → 框架内部类。全空返回 null，交给调用方走"什么都不传"。 */
    private static CompactionConfig.TruncateArgsConfig buildTruncateArgs(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        CompactionConfig.TruncateArgsConfig.TruncateArgsBuilder cfg =
                CompactionConfig.TruncateArgsConfig.builder();
        Integer v;
        if ((v = intOf(raw, "triggerMessages")) != null) {
            cfg.triggerMessages(v);
        }
        if ((v = intOf(raw, "triggerTokens")) != null) {
            cfg.triggerTokens(v);
        }
        if ((v = intOf(raw, "keepMessages")) != null) {
            cfg.keepMessages(v);
        }
        if ((v = intOf(raw, "keepTokens")) != null) {
            cfg.keepTokens(v);
        }
        if ((v = intOf(raw, "maxArgLength")) != null) {
            cfg.maxArgLength(v);
        }
        if (raw.get("truncationText") instanceof String s && notBlank(s)) {
            cfg.truncationText(s);
        }
        return cfg.build();
    }

    /** {@code PruneConfig}：JSON 列 → 框架内部类，语义同 {@link #buildTruncateArgs(Map)}。 */
    private static CompactionConfig.PruneConfig buildPrune(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        CompactionConfig.PruneConfig.PruneBuilder cfg = CompactionConfig.PruneConfig.builder();
        Integer v;
        if ((v = intOf(raw, "protectTokens")) != null) {
            cfg.protectTokens(v);
        }
        if ((v = intOf(raw, "minimumTokens")) != null) {
            cfg.minimumTokens(v);
        }
        if ((v = intOf(raw, "maxOutputChars")) != null) {
            cfg.maxOutputChars(v);
        }
        if (raw.get("excludedTools") instanceof List<?> list) {
            Set<String> names = new LinkedHashSet<>();
            for (Object item : list) {
                if (item != null) {
                    names.add(String.valueOf(item));
                }
            }
            cfg.excludedTools(names);
        }
        return cfg.build();
    }

    /** 记忆整理配置。与压缩同样的三种情形：没配就不传。 */
    private void applyMemoryConfig(HarnessAgent.Builder builder, AgentDefinition def) {
        if (!hasMemoryConfig(def)) {
            return;
        }
        MemoryConfig.Builder cfg = MemoryConfig.builder();
        if (notBlank(def.getMemoryModelRef())) {
            cfg.model(modelFactory.resolve(def.getMemoryModelRef()));
        }
        if (notBlank(def.getFlushPrompt())) {
            cfg.flushPrompt(def.getFlushPrompt());
        }
        if (notBlank(def.getConsolidationPrompt())) {
            cfg.consolidationPrompt(def.getConsolidationPrompt());
        }
        if (def.getConsolidationMaxTokens() != null) {
            cfg.consolidationMaxTokens(def.getConsolidationMaxTokens());
        }
        if (def.getConsolidationMinGapMs() != null) {
            cfg.consolidationMinGap(Duration.ofMillis(def.getConsolidationMinGapMs()));
        }
        if (def.getDailyFileRetentionDays() != null) {
            cfg.dailyFileRetentionDays(def.getDailyFileRetentionDays());
        }
//        if (def.getSessionRetentionDays() != null) {
//            cfg.sessionRetentionDays(def.getSessionRetentionDays());
//        }
        MemoryConfig.FlushTrigger trigger = buildFlushTrigger(def.getFlushTrigger());
        if (trigger != null) {
            cfg.flushTrigger(trigger);
        }
        builder.memory(cfg.build());
    }

    /** {@code flushTrigger}：{@code mode=ALWAYS|NEVER|THROTTLED}（THROTTLED 另需 minGapMs）。未知 mode 视作"没配"。 */
    private static MemoryConfig.FlushTrigger buildFlushTrigger(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Object modeValue = raw.get("mode");
        String mode = modeValue == null ? "" : String.valueOf(modeValue).trim().toUpperCase();
        return switch (mode) {
            case "ALWAYS" -> MemoryConfig.FlushTrigger.always();
            case "NEVER" -> MemoryConfig.FlushTrigger.never();
            case "THROTTLED" -> {
                Integer gapMs = intOf(raw, "minGapMs");
                yield MemoryConfig.FlushTrigger.throttled(Duration.ofMillis(gapMs == null ? 0L : gapMs));
            }
            default -> null;
        };
    }

    /** 工具结果驱逐：大结果转存到磁盘、上下文只留预览。关掉与没配同样是两条路径。 */
    private void applyToolResultEviction(HarnessAgent.Builder builder, AgentDefinition def) {
        if (Boolean.TRUE.equals(def.getEvictDisabled())) {
            builder.disableToolResultEviction();
            return;
        }
        if (!hasEvictConfig(def)) {
            return;
        }
        ToolResultEvictionConfig.Builder cfg = ToolResultEvictionConfig.builder();
        if (def.getEvictMaxResultChars() != null) {
            cfg.maxResultChars(def.getEvictMaxResultChars());
        }
        if (def.getEvictPreviewChars() != null) {
            cfg.previewChars(def.getEvictPreviewChars());
        }
        if (notBlank(def.getEvictPath())) {
            cfg.evictionPath(def.getEvictPath());
        }
        if (!def.getEvictExcludedTools().isEmpty()) {
            cfg.excludedToolNames(new LinkedHashSet<>(def.getEvictExcludedTools()));
        }
        builder.toolResultEviction(cfg.build());
    }

    /** 是否配了任何一项压缩阈值（决定"不传"还是"建 builder 传"）。 */
    private static boolean hasCompactionConfig(AgentDefinition def) {
        return def.getTriggerMessages() != null || def.getTriggerTokens() != null
                || def.getReserved() != null || def.getKeepMessages() != null
                || def.getKeepTokens() != null || def.getKeepTokensMin() != null
                || def.getKeepTokensMax() != null || def.getKeepTokensRatio() != null
                || notBlank(def.getSummaryPrompt()) || def.getFlushBeforeCompact() != null
                || def.getOffloadBeforeCompact() != null || notBlank(def.getCompactionModelRef())
                || (def.getTruncateArgs() != null && !def.getTruncateArgs().isEmpty())
                || (def.getPrune() != null && !def.getPrune().isEmpty());
    }

    /** 是否配了任何一项记忆整理参数。 */
    private static boolean hasMemoryConfig(AgentDefinition def) {
        return notBlank(def.getMemoryModelRef()) || notBlank(def.getFlushPrompt())
                || notBlank(def.getConsolidationPrompt()) || def.getConsolidationMaxTokens() != null
                || def.getConsolidationMinGapMs() != null || def.getDailyFileRetentionDays() != null
                || def.getSessionRetentionDays() != null
                || (def.getFlushTrigger() != null && !def.getFlushTrigger().isEmpty());
    }

    /** 是否配了任何一项驱逐参数。 */
    private static boolean hasEvictConfig(AgentDefinition def) {
        return def.getEvictMaxResultChars() != null || def.getEvictPreviewChars() != null
                || notBlank(def.getEvictPath()) || !def.getEvictExcludedTools().isEmpty();
    }

    /** JSON 列里的数字统一按 {@link Number} 取（Jackson 反序列化可能是 Integer / Long / Double）。 */
    private static Integer intOf(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value instanceof Number n ? n.intValue() : null;
    }

    // ==================== 解析 ====================

    private void validateTools(AgentDefinition def) {
        for (String toolName : def.getTools()) {
            if (!applicationContext.containsBean(toolName)) {
                throw new IllegalStateException(
                        "Agent [" + def.getName() + "] 引用的工具 bean 不存在: " + toolName);
            }
        }
    }

    /** 工具集 = Spring 工具 bean + MCP 远端工具（MCP 名与 skills 同源，按名解析）。 */
    private Toolkit buildToolkit(AgentDefinition def) {
        Toolkit toolkit = new Toolkit();
        for (String name : def.getTools()) {
            toolkit.registerTool(getBean(name, Object.class));
        }
        registerMcpClients(toolkit, resolveMcpBindings(def));
        return toolkit;
    }

    /**
     * 把 MCP 客户端注册进工具集，按<b>每个 MCP 自己的</b> enable / disable 名单过滤。
     *
     * <p>过滤发生在<b>注册期</b>：未入选的工具不会进 Toolkit，也就不进模型 schema，
     * 省的是 prompt token 而不只是可见性。两个名单都留空 = 注册该 MCP 的全部工具；
     * enable 非空时以它为准（框架侧 enable 覆盖 disable）。
     *
     * <p>名单的归属在改造后从 <b>agent</b> 下沉到 <b>MCP 服务端</b>（设计文档 D10）：
     * 同一个 MCP 被多个 agent 引用时只该有一份名单，否则会出现「同一 MCP 在不同 agent 里
     * 看到的工具不一样」这种在页面上无法解释的状态。名单随绑定一起传进来（见 {@link McpServerBinding}）。
     */
    static void registerMcpClients(Toolkit toolkit, List<McpServerBinding> bindings) {
        for (McpServerBinding binding : bindings) {
            if (binding.filtered()) {
                log.info("[MCP] {} 工具过滤: enable={} disable={}",
                        binding.name(), binding.enableTools(), binding.disableTools());
            }
            toolkit.registration()
                    .mcpClient(binding.client())
                    .enableTools(binding.enableTools())
                    .disableTools(binding.disableTools())
                    .apply();
        }
    }

    /** 是否显式提供 toolkit：声明了工具 bean 或 MCP 即算。 */
    private static boolean hasToolkit(AgentDefinition def) {
        return !def.getTools().isEmpty() || !def.getMcpNames().isEmpty();
    }

    /**
     * 解析 agent 声明的 MCP：建链并带上该 MCP 自己的工具名单。单个失败只告警跳过。
     *
     * <p>名单的归属只有一处：{@code md_mcp_server} 行（随绑定一起传进来，见 {@link McpServerBinding}）。
     * 历史上还有过「名单挂在 agent 定义上」的第二条路（Nacos / Local 源），已随 Nacos 数据源一起删除。
     */
    private List<McpServerBinding> resolveMcpBindings(AgentDefinition def) {
        List<String> mcpNames = def.getMcpNames();
        if (mcpNames == null || mcpNames.isEmpty()) {
            return List.of();
        }
        List<McpServerBinding> bindings = dbMcpClientFactory.resolveAll(mcpNames);
        if (bindings.size() < mcpNames.size()) {
            // resolveAll 对单个 MCP 只逐条 ERROR 后跳过，「少挂了」这件事必须在这里再说一次：
            // 工具少了只在模型行为上体现，页面与接口都看不出来（2026-09-24 的事故就是这样藏了两天）。
            log.warn("[Agent] {} 的 MCP 未全部挂载: 成功 {}/{}，缺失原因见上方的 [MCP] 行",
                    def.getName(), bindings.size(), mcpNames.size());
        }
        log.info("[Agent] {} 挂载 MCP: {} (成功 {} 个)", def.getName(), mcpNames, bindings.size());
        return bindings;
    }

    /**
     * 技能仓库的唯一装配路径：按 {@code skill-names} 从 MySQL 解析。
     *
     * <p>{@code skills}（本地目录）是历史字段，装配期<b>完全忽略</b>：目录存不存在都不影响结果，
     * 也不再有任何文件兜底。工作区里的技能文件只作为历史数据保留。
     *
     * <ul>
     *   <li>{@code skill-names} 非空 → 官方 MySQL 仓库外包一层名字白名单
     *       （见 {@link NamedSkillRepository}）；</li>
     *   <li>{@code skill-names} 为空 → 不挂技能仓库（不是挂一个空仓库，语义不同）。</li>
     * </ul>
     */
    private AgentSkillRepository resolveSkillRepository(AgentDefinition def) {
        return resolveSkillRepository(def, skillContentRepository);
    }

    /** 包可见重载：便于单测注入仓库，验证「本地目录存在也不会被读取」。 */
    AgentSkillRepository resolveSkillRepository(AgentDefinition def, AgentSkillRepository contentRepository) {
        List<String> skillNames = def.getSkillNames();
        if (skillNames == null || skillNames.isEmpty()) {
            if (notBlank(def.getSkills())) {
                log.warn("[Agent] {} 声明了历史技能目录 {}，但 skill-names 为空 —— "
                                + "技能只从 MySQL 按名加载，本 agent 不会挂载技能",
                        def.getName(), def.getSkills());
            }
            return null;
        }
        if (contentRepository == null) {
            throw new IllegalStateException("agent " + def.getName()
                    + " 声明了 skill-names，但 MySQL 技能仓库不可用");
        }
        // 关键：必须包一层名字白名单。框架会对传进来的仓库调 getAllSkills() 拼
        // <available_skills> 提示词段，直接把全库仓库交出去 = 每个 agent 都看到全部技能。
        log.info("[Agent] {} 挂载技能仓库（MySQL 按名收窄）: {}", def.getName(), skillNames);
        return new NamedSkillRepository(contentRepository, skillNames);
    }

    /**
     * 状态存储：库取配置、表取定义里冻结的 {@code state_table}。
     *
     * <p>三处刻意如此：
     *
     * <ol>
     *   <li><b>库名不再写死</b>。原来是字面量 {@code "md-agent-platform"}，于是 state 表和
     *       治理元数据挤在同一个 schema 里；两者体量与生命周期完全不同（state 是
     *       单行最大 1.65 MB 的大对象、可整体丢弃，元数据丢了平台就起不来），
     *       分开之后能各自定备份与保留策略。</li>
     *   <li><b>表名优先取 {@code state_table}</b>，为空才回落 agent 名 —— 存量行不必先刷数据，
     *       但新写入的一定带冻结值，改名才不会换表。</li>
     *   <li><b>表名要过白名单校验</b>。它现在是"页面可改的数据"，而框架会把它拼进
     *       SQL 的标识符位置（用反引号包裹）。反引号本身能被人为闭合，所以一个
     *       形如 {@code a`; DROP ...} 的表名就是一条注入路径 —— 校验放在装配期，
     *       让非法值在启动/重载时立刻暴露，而不是等某次对话去执行它。</li>
     * </ol>
     */
    private AgentStateStore resolveStateStore(AgentDefinition def) {
        if (!notBlankStateStore(def.getStateStore())) {
            return null;
        }
        String table = notBlank(def.getStateTable()) ? def.getStateTable().trim() : def.getName();
        if (table == null || !STATE_TABLE_PATTERN.matcher(table).matches()) {
            throw new IllegalStateException(
                    "agent " + def.getName() + " 的 state_table 非法（只允许字母、数字、下划线、连字符，1-64 位）: " + table);
        }
        String database = platformProperties.getStateStoreDatabase();
        log.info("[Agent] {} 状态存储: MySQL {}.{}", def.getName(), database, table);
        return new MysqlAgentStateStore(dataSource, database, table, true);
    }

    /** state_store 列的判定：语义是"是否启用"，值是什么都不影响。 */
    private static boolean notBlankStateStore(String value) {
        return value != null && !value.isBlank();
    }

    // ==================== 工具方法 ====================

    private <T> T getBean(String name, Class<T> type) {
        try {
            return applicationContext.getBean(name, type);
        } catch (Exception e) {
            throw new IllegalStateException("Bean 不存在: " + name, e);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 空名单归一为 null：框架把 null 与空列表都当作「不过滤」，显式归一到 null 便于日志区分。 */
    private static List<String> emptyToNull(List<String> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }
}
