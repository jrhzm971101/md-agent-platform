package com.md.agentplatform.controller.agent;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.agent.ToolMeta;
import com.md.agentplatform.server.controlplane.ControlPlanePublishService;
import com.md.agentplatform.server.controlplane.ControlPlaneValidator;
import com.md.agentplatform.server.controlplane.store.AgentDefinitionBundle;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ResourceType;
import com.md.agentplatform.server.controlplane.store.ResourceVersionInfo;
import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRefEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentSubAgentEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Agent 治理接口 —— 取代改造前那个「把 AgentDefinition 存 JSON 文件」的薄 CRUD。
 *
 * <h2>一、请求/响应的形状 = {@link AgentDefinitionBundle}</h2>
 * 一个 agent 在库里是 6 张表的行束，页面的编辑表单天然按域分区（身份 / 引用 / 运行时 / 记忆 /
 * 工作区 / 子 agent），所以接口直接收发这个行束 —— <b>从页面到库中间不经过 AgentDefinition</b>。
 * 那条「行束 → 定义」的转换只发生在装配期（{@code AgentDefinitionResolver}），
 * 这样"页面存什么"和"装配读什么"共用同一份表结构，不会出现两个方向的映射各自漂移。
 *
 * <h2>二、写路径的三步顺序</h2>
 * <pre>
 *   校验（引用完整性 + 类型与字段匹配）→ 落库（草稿）→ 由页面决定何时发布
 * </pre>
 * 保存<b>不</b>触发热装配：发布才是"把 DB 内容装配进实例"的那个动作（设计文档 §4.4）。
 * 所以保存后 agent 的行为不变，直到点发布。
 *
 * <h2>三、删除为什么要先查子 agent 引用</h2>
 * 删掉一个被当作子 agent 的 agent，会让父 agent 在路由时拿到 null
 * （{@code subagentFactory(name, agents::get)} 解析不到），表现为"编排到某一步就没反应了"。
 * 这种故障现象和原因隔得很远，所以在删除口直接挡掉，并把引用方列出来。
 *
 * <h2>四、为什么每个方法都 subscribeOn(boundedElastic)</h2>
 * 本项目是 WebFlux，下面全是阻塞调用：JDBC 读写，以及发布 / 重载触发的<b>热装配</b>
 * —— 装配里要跟每个 MCP 做一次握手（{@code DbMcpClientFactory}），默认超时 120s。
 * 在 Netty 事件循环线程上做这些事会把整条事件循环卡住（同 ModelController / SessionController 的约定）。
 *
 * <p>2026-09-24 的「MCP 工具全丢」事故就出在这里：发布原本是同步 handler、跑在事件循环线程上，
 * 而框架内部用 {@code Mono#block()} 做 MCP 握手，Reactor 在事件循环线程上会直接把它拦掉，
 * 于是页面上发布的 agent 少了全部 MCP 工具（根因与线程交接见 ConfigurableAgentBuilder#onAssemblyThread）。
 * 装配侧已经统一兜住了正确性，这里再 subscribeOn 是为了不把事件循环本身占住。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    @Autowired
    private ControlPlaneStore store;

    @Autowired
    private ControlPlaneValidator validator;

    @Autowired
    private ControlPlanePublishService publishService;

    @Autowired
    private AgentManager agentManager;

    // ==================================================================================
    // 读
    // ==================================================================================

    /**
     * 列表：主表字段 + 引用计数。
     *
     * <p>计数来自一次全量行束读（6 条全表查询），而不是 14 次逐 agent 读 ——
     * agent 数量是几十的量级，这样最省往返，也不会因为列表页轮询把库打满。
     */
    @GetMapping
    public Mono<List<AgentRow>> list() {
        return blocking(() -> listBlocking());
    }

    /** 列表的真正动作：一次全量行束读，全阻塞，只能被弹性线程调用。 */
    private List<AgentRow> listBlocking() {
        List<AgentRow> rows = new ArrayList<>();
        for (AgentDefinitionBundle bundle : store.loadAllAgentBundles()) {
            rows.add(AgentRow.of(bundle, agentManager.exists(bundle.agentName())));
        }
        return rows;
    }

    /** 工具目录（工具不落库，扫 {@code @Tool} bean）。 */
    @GetMapping("/tools/available")
    public Mono<List<ToolMeta>> availableTools() {
        return blocking(agentManager::listAvailableTools);
    }

    /**
     * 详情：整束行（引用字段就是名字列表，页面拿它直接回显下拉）。
     *
     * <p>不存在时给 404 + 一句原因，而不是空 body：名字是人工填的，打错一个字符很常见，
     * 而页面只能把后端给的那句话原样显示（见前端 readApiFailure 的解析口径）。
     */
    @GetMapping("/{name}")
    public Mono<ResponseEntity<?>> detail(@PathVariable String name) {
        return blocking(() -> {
            AgentDefinitionBundle bundle = store.loadAgentBundle(name);
            return bundle == null
                    ? ResponseEntity.status(404).body(Map.of("error", "agent 不存在: " + name))
                    : ResponseEntity.ok(bundle);
        });
    }

    @GetMapping("/{name}/versions")
    public Mono<List<ResourceVersionInfo>> versions(@PathVariable String name) {
        return blocking(() -> store.listVersions(ResourceType.AGENT, name));
    }

    // ==================================================================================
    // 写
    // ==================================================================================

    @PostMapping
    public Mono<ResponseEntity<?>> create(@RequestBody AgentDefinitionBundle body,
                                          @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> {
            validator.validate(body);
            if (!store.createAgent(body, operator(operator))) {
                return ResponseEntity.status(409)
                        .body(Map.of("error", "agent 已存在: " + body.agentName()));
            }
            return ResponseEntity.ok(body);
        });
    }

    @PutMapping("/{name}")
    public Mono<ResponseEntity<?>> save(@PathVariable String name, @RequestBody AgentDefinitionBundle body,
                                        @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> {
            if (body.getAgent() == null) {
                body.setAgent(new AgentEntity());
            }
            // 路径是权威：防止通过改 body 里的名字把保存变成一个隐式的"另存为"
            body.getAgent().setAgentName(name);
            forceOwnerName(body, name);
            validator.validate(body);
            if (!store.saveAgent(body, operator(operator))) {
                return ResponseEntity.status(404).body(Map.of("error", "agent 不存在: " + name));
            }
            return ResponseEntity.ok(body);
        });
    }

    @DeleteMapping("/{name}")
    public Mono<ResponseEntity<?>> delete(@PathVariable String name) {
        return blocking(() -> deleteBlocking(name));
    }

    /** 删除的真正动作：查两类引用 + 删行 + 摘掉运行时实例，全阻塞，只能被弹性线程调用。 */
    private ResponseEntity<?> deleteBlocking(String name) {
        if (store.findAgent(name) == null) {
            return ResponseEntity.status(404).body(Map.of("error", "agent 不存在: " + name));
        }
        List<String> referencing = store.findReferencingAgents(ResourceType.AGENT, name);
        if (!referencing.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "被 " + referencing.size() + " 个父 agent 当作子 agent 引用，不能删除",
                    "referencingAgents", referencing));
        }
        // 工作流的引用方向与上面这句相反：上面是"有子 agent 引用它"，这里是"有工作流引用它"。
        // 漏掉这一步的后果是：删掉 agent 后，某个已发布的工作流每次执行都会走到那个节点才失败，
        // 而失败现场离"我删了个 agent"很远（见 WorkflowNodeRefMapper 的注释）。
        // 单独一个字段名而不是并进 referencingAgents：页面要能把两种引用方渲染成不同的链接。
        List<String> referencingWorkflows = store.findReferencingWorkflows(name);
        if (!referencingWorkflows.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "被 " + referencingWorkflows.size() + " 个已发布的工作流引用，不能删除",
                    "referencingWorkflows", referencingWorkflows));
        }
        store.deleteAgent(name);
        // 运行时实例要一起摘掉：库里没了、内存里还在，会比不删更危险
        agentManager.unregister(name);
        return ResponseEntity.ok(Map.of("deleted", name));
    }

    // ==================================================================================
    // 发布 / 回滚 / 重载
    // ==================================================================================

    @PostMapping("/{name}/publish")
    public Mono<ResponseEntity<?>> publish(@PathVariable String name,
                                           @RequestHeader(value = "X-Operator", required = false) String operator,
                                           @RequestBody(required = false) PublishRequest body) {
        return blocking(() -> {
            if (store.findAgent(name) == null) {
                return ResponseEntity.status(404).body(Map.of("error", "agent 不存在: " + name));
            }
            // 这一步是热装配：重建实例并跟每个 MCP 握手，最耗时，也最必须离开事件循环
            return ResponseEntity.ok(publishService.publish(ResourceType.AGENT, name, operator(operator),
                    body == null ? null : body.changeNote()));
        });
    }

    @PostMapping("/{name}/rollback")
    public Mono<ResponseEntity<?>> rollback(@PathVariable String name, @RequestBody VersionRequest body,
                                            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> {
            if (store.findAgent(name) == null) {
                return ResponseEntity.status(404).body(Map.of("error", "agent 不存在: " + name));
            }
            // 回滚同样是热装配：换回旧版本的引用（prompt / skill / mcp），要重新握手
            return ResponseEntity.ok(publishService.rollback(ResourceType.AGENT, name, body.version(),
                    operator(operator)));
        });
    }

    /**
     * 只重建实例（DB 内容不变）。
     *
     * <p>用途是"运行期复位"：实例跑脏了、或者 MCP 会话断了，重建一份即可，
     * 不必先去页面上假改一次再发布。
     */
    @PostMapping("/{name}/reload")
    public Mono<ResponseEntity<?>> reload(@PathVariable String name) {
        return blocking(() -> ResponseEntity.ok(Map.of("reloaded", agentManager.reload(name).getName())));
    }

    /** 全量重载：导入完成后让库里新增/修改的 agent 立刻生效。 */
    @PostMapping("/reload-all")
    public Mono<ResponseEntity<?>> reloadAll() {
        return blocking(() -> ResponseEntity.ok(Map.of("agentCount", agentManager.reloadAll())));
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    /**
     * 把行束里所有"从属行"的 agent_name 对齐路径上的名字。
     *
     * <p>从属表都是「以 agent_name 为外键的整体替换」，如果页面漏传或传了错的名字，
     * 会出现"主表写的是 A、关联表写的是 B"这种半错位数据 —— 落库时按主表名删旧行、
     * 按从属行的名字插新行，于是 A 的关联数据被删了、B 的多了一行。这里统一改写成路径名，
     * 让调用方少一个必填字段、也少一类事故。
     */
    private static void forceOwnerName(AgentDefinitionBundle bundle, String name) {
        if (bundle.getRef() != null) {
            bundle.getRef().setAgentName(name);
        }
        if (bundle.getRuntime() != null) {
            bundle.getRuntime().setAgentName(name);
        }
        if (bundle.getMemory() != null) {
            bundle.getMemory().setAgentName(name);
        }
        if (bundle.getWorkspace() != null) {
            bundle.getWorkspace().setAgentName(name);
        }
        if (bundle.getSubAgents() != null) {
            for (var sub : bundle.getSubAgents()) {
                sub.setAgentName(name);
            }
        }
    }

    /**
     * 把阻塞调用挪到弹性线程池 —— 与 ModelController / SessionController 同一套约定。
     *
     * <p>{@code fromCallable} 的另一个好处：服务层抛的异常会变成 {@code onError}
     * 而不是直接冒到事件循环上，从响应式管道里出来时 {@code @ExceptionHandler} 依然生效。
     */
    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }

    private static String operator(String raw) {
        return raw == null || raw.isBlank() ? "console" : raw.trim();
    }

    /** 发布请求体：change-note 可空。 */
    public record PublishRequest(String changeNote) {}

    /** 回滚请求体：目标版本号。 */
    public record VersionRequest(int version) {}

    /**
     * 列表行：主表字段 + 引用名清单 + 「运行时是否已注册」。
     *
     * <h2>为什么回的是名字清单而不是计数</h2>
     * 页面上有两个方向的问题：「这个 agent 用了哪些 prompt / skill / mcp」以及
     * 「这个 prompt 被哪些 agent 引用」。后者是删除保护的依据，也是资源页最想看到的
     * 一列 —— 但反过来查需要逐 agent 读详情（14 个 agent 就是 14 次请求）。
     * 而本方法本来就是全量行束读（6 条全表查询），ref 已经在手里，把名字一起回出去
     * 不增加任何数据库代价，计数则交给页面 {@code names.length}。
     *
     * <p>空列表与 null 统一成 null：页面只需判一次「有没有」，不必区分空数组与缺失。
     *
     * @param loaded 运行时注册表里有没有这个实例。它与 {@code enabled} 不同：
     *               改过、还没发布时 enabled 是 true 但实例仍是旧的；停用则会从注册表里摘掉
     */
    public record AgentRow(String agentName, String displayName, String agentType, String description, String category,
                           List<String> bizTags, String status, Integer version, Integer publishedVersion,
                           Long publishedAtMs, Boolean enabled, Long updatedAtMs,
                           String promptKey, List<String> tools, List<String> middleware,
                           List<String> skillNames, List<String> mcpNames, List<String> subAgentNames,
                           boolean loaded) {

        static AgentRow of(AgentDefinitionBundle bundle, boolean loaded) {
            AgentEntity agent = bundle.getAgent();
            AgentRefEntity ref = bundle.getRef();
            return new AgentRow(
                    agent.getAgentName(), agent.getDisplayName(),
                    agent.getAgentType(), agent.getDescription(), agent.getCategory(),
                    agent.getBizTags(), agent.getStatus(), agent.getVersion(), agent.getPublishedVersion(),
                    agent.getPublishedAtMs(), agent.getEnabled(), agent.getUpdatedAtMs(),
                    blankToNull(ref == null ? null : ref.getPromptKey()),
                    shrink(ref == null ? null : ref.getTools()),
                    shrink(ref == null ? null : ref.getMiddleware()),
                    shrink(ref == null ? null : ref.getSkillNames()),
                    shrink(ref == null ? null : ref.getMcpNames()),
                    bundle.getSubAgents() == null ? List.of()
                            : bundle.getSubAgents().stream()
                                    .map(AgentSubAgentEntity::getSubName).toList(),
                    loaded);
        }

        /** 空数组与 null 都归一成 null，页面只判一次。 */
        private static List<String> shrink(List<String> raw) {
            return raw == null || raw.isEmpty() ? null : List.copyOf(raw);
        }

        /** 空串的 prompt-key 等价于没配，别让页面显示一个空的引用。 */
        private static String blankToNull(String raw) {
            return raw == null || raw.isBlank() ? null : raw;
        }
    }
}
