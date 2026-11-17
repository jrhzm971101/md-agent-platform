package com.md.agentplatform.controller.workflow;

import com.md.agentplatform.server.controlplane.ControlPlanePublishService;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ResourceType;
import com.md.agentplatform.server.controlplane.store.ResourceVersionInfo;
import com.md.agentplatform.server.controlplane.store.entity.WorkflowEntity;
import com.md.agentplatform.server.workflow.WorkflowEngine;
import com.md.agentplatform.server.workflow.WorkflowSpec;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunEntity;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 工作流治理与运行接口 —— 第六类受治理资源的 CRUD、发布，外加"跑一次"。
 *
 * <h2>一、为什么它不像 ResourceController 那样被并进大 switch</h2>
 * 另外五类资源的接口形状是「实体 + 发布/回滚/版本」，逐字相同，所以并得起来。
 * 工作流比它们多两件事：
 * <ul>
 *   <li><b>spec 校验</b>：它不是"字段级校验"，而是对<b>一张图</b>的校验
 *       （有环、入口唯一、全部可达、引用是否存在），要单独给页面一个「校验」按钮；</li>
 *   <li><b>执行</b>：只有它有"把定义跑起来"这个动作，而这属于运行层，不属于治理层。</li>
 * </ul>
 * 硬并进去会让那个类的 switch 里出现两个只在一种类型下成立的分支，
 * 反而更难读。所以它有独立控制器，但沿用同一套约定（operator 头、409 带清单、404 带 error）。
 *
 * <h2>二、为什么每个方法都 subscribeOn(boundedElastic)</h2>
 * 本项目是 WebFlux，而这里全是阻塞调用：MyBatis 的 JDBC 读写，以及
 * {@link WorkflowEngine#run} —— 它<b>同步</b>跑完整条流程（可能几分钟）。
 * 在 Netty 事件循环线程上做这件事会把整条事件循环卡死，所以全部切到弹性线程池
 * （同 ModelController / SessionController 的约定）。
 *
 * <h2>三、运行详情为什么挂在 /api/workflow-runs 而不是 /api/workflows/{key}/runs/{runId}</h2>
 * 那会与「某个工作流下的运行列表」{@code /api/workflows/{key}/runs} 构成
 * 「变量-字面量」互换的歧义模式（{@code workflows/{key}/runs} 与
 * {@code workflows/runs/{runId}} 的段数、变量数完全相同），Spring 的路径匹配在这种
 * 情形下只能靠内部排序决定谁优先，规则不直观且随版本变化。
 * run 的唯一身份是 runId，与它属于哪个工作流无关（run 行里已经存了 workflow_key），
 * 所以给它一个自己的顶层前缀，歧义从根上消失。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    /** 运行列表的缺省条数；上限由存储层再兜一次（见 MyBatisWorkflowRunStore.MAX_RECENT）。 */
    private static final int DEFAULT_RUN_LIMIT = 20;

    @Autowired
    private ControlPlaneStore store;

    @Autowired
    private ControlPlanePublishService publishService;

    @Autowired
    private WorkflowEngine engine;

    // ==================================================================================
    // 定义 CRUD
    // ==================================================================================

    /**
     * 列表：只回治理字段 + 节点/边数量，<b>不回 spec</b>。
     *
     * <p>列表页显示的是"有哪些工作流、各自长什么样、发布到哪一版"，
     * 一个都不需要骨架本身；而把 spec 一起回出去意味着一次列表请求要传几十 KB JSON
     *（每个工作流都可能有一张几十节点的图）。
     */
    @GetMapping
    public Mono<List<WorkflowRow>> list() {
        return blocking(() -> store.listWorkflows().stream().map(WorkflowRow::of).toList());
    }

    /** 详情：整行（含 spec），页面把它当成编辑器的初始内容。 */
    @GetMapping("/{key}")
    public Mono<ResponseEntity<?>> detail(@PathVariable String key) {
        return blocking(() -> {
            WorkflowEntity entity = store.findWorkflow(key);
            // 404 走 notFound(key)：带 { error } 文案，页面直接显示"哪个 workflow 不存在"
            return entity == null ? notFound(key) : ResponseEntity.ok(entity);
        });
    }

    /**
     * 新建草稿。
     *
     * <p>409 用 {@code workflow-key 已存在} 这种说法而不是复用 notFound 的格式：
     * 新建冲突是"你要的名字被占了"，与"删一个不存在的"完全是两回事。
     */
    @PostMapping
    public Mono<ResponseEntity<?>> create(@RequestBody WorkflowEntity body,
                                          @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> store.createWorkflow(body, operator(operator))
                ? ResponseEntity.ok(body)
                : conflict("workflow-key 已存在: " + body.getWorkflowKey()));
    }

    /**
     * 保存草稿。
     *
     * <p>{@code key} 以<b>路径</b>为准（同 ResourceController 的约定）：请求体里的 key 只用于展示，
     * 否则页面可以通过改 body 里的 key 把内容写到另一个工作流上。
     */
    @PutMapping("/{key}")
    public Mono<ResponseEntity<?>> save(@PathVariable String key, @RequestBody WorkflowEntity body,
                                        @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> {
            body.setWorkflowKey(key);
            return store.saveWorkflow(body, operator(operator)) ? ResponseEntity.ok(body) : notFound(key);
        });
    }

    /**
     * 删除定义。
     *
     * <p>这里<b>没有</b>引用保护检查，与 prompt / model 那几个删除接口不同 ——
     * 因为方向不一样：那些是"资源被 agent 引用"，删掉会让 agent 装配不出来；
     * 工作流是引用方（它引用 agent），没有任何别的东西引用它。
     * 将来若做出 {@code subrun} 节点，子工作流会变成"被引用方"，那时这里要补检查。
     *
     * <p>删定义<b>不删历史运行记录</b>：运行记录是观测数据，
     * "这个工作流上个月跑失败过三次"这件事不该因为今天删了定义就消失。
     */
    @DeleteMapping("/{key}")
    public Mono<ResponseEntity<?>> delete(@PathVariable String key) {
        return blocking(() -> {
            if (store.findWorkflow(key) == null) {
                return notFound(key);
            }
            store.deleteWorkflow(key);
            return ResponseEntity.ok(Map.of("deleted", key));
        });
    }

    // ==================================================================================
    // 校验 / 发布 / 回滚 / 版本
    // ==================================================================================

    /**
     * 校验：只查问题、不落库。
     *
     * <p>请求体可选地带上 {@code spec} —— 带了就校验它（页面上"我改完想先看看有没有错"），
     * 没带就校验库里存的草稿（"我存完了想确认能不能发"）。
     * 两种用法的差别只在取哪份内容，检查清单完全一样，所以不拆成两个接口。
     *
     * <p>回的是 {@code problems} 列表而不是异常：校验不通过是<b>一次正常的询问结果</b>，
     * 不是请求出错。若这里抛 400，页面就得把"接口挂了"与"图里有问题"两种情况混在一起处理。
     */
    @PostMapping("/{key}/validate")
    public Mono<ResponseEntity<?>> validate(@PathVariable String key,
                                            @RequestBody(required = false) ValidateRequest body) {
        return blocking(() -> {
            WorkflowSpec spec = body == null ? null : body.spec();
            if (spec == null) {
                WorkflowEntity entity = store.findWorkflow(key);
                if (entity == null) {
                    return notFound(key);
                }
                spec = entity.getSpec();
            }
            List<String> problems = store.checkWorkflowSpec(spec);
            return ResponseEntity.ok(Map.of("ok", problems.isEmpty(), "problems", problems));
        });
    }

    /** 发布：校验通过才生成快照并让新版本生效（校验在 Store 的 publish 里做，见那里的注释）。 */
    @PostMapping("/{key}/publish")
    public Mono<ResponseEntity<?>> publish(@PathVariable String key,
                                           @RequestHeader(value = "X-Operator", required = false) String operator,
                                           @RequestBody(required = false) PublishRequest body) {
        return blocking(() -> {
            if (store.findWorkflow(key) == null) {
                return notFound(key);
            }
            return ResponseEntity.ok(publishService.publish(ResourceType.WORKFLOW, key, operator(operator),
                    body == null ? null : body.changeNote()));
        });
    }

    /** 回滚：把旧快照写成新草稿，线上不动（要再点一次发布才生效）。 */
    @PostMapping("/{key}/rollback")
    public Mono<ResponseEntity<?>> rollback(@PathVariable String key, @RequestBody VersionRequest body,
                                            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> {
            if (store.findWorkflow(key) == null) {
                return notFound(key);
            }
            return ResponseEntity.ok(publishService.rollback(ResourceType.WORKFLOW, key, body.version(),
                    operator(operator)));
        });
    }

    /** 版本快照列表（只有头信息，不含 spec）。 */
    @GetMapping("/{key}/versions")
    public Mono<List<ResourceVersionInfo>> versions(@PathVariable String key) {
        return blocking(() -> store.listVersions(ResourceType.WORKFLOW, key));
    }

    // ==================================================================================
    // 运行
    // ==================================================================================

    /**
     * 跑一次（手动触发）。
     *
     * <p>这是整个控制器里唯一会<b>长时间占用线程</b>的接口：它同步等整条流程跑完才回。
     * 之所以第一批不做成"提交即返回 + 页面轮询 run 状态"：运行记录已经落在库里、
     * 页面也能查，做成异步只会多一层"任务提交到哪了"的状态。等这一批的语义稳定了，
     * 再决定要不要改成异步提交。
     *
     * <p>两个前置检查刻意放在这里而不是让引擎抛异常：
     * 「工作流不存在」是 404、「没有已发布的版本」是 409（内容合法但状态不允许），
     * 而 {@code ControlPlaneValidationException} 统一翻译成 400 —— 那是给"字段填错了"用的。
     */
    @PostMapping("/{key}/runs")
    public Mono<ResponseEntity<?>> run(@PathVariable String key,
                                       @RequestBody(required = false) RunRequest body,
                                       @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> {
            if (store.findWorkflow(key) == null) {
                return notFound(key);
            }
            if (store.loadPublishedWorkflow(key) == null) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "工作流 " + key + " 还没有已发布的版本，无法执行（先去设计器点一次「发布」）"));
            }
            Map<String, Object> input = body == null ? null : body.input();
            String userId = body == null ? null : body.userId();
            String triggerRef = body == null ? null : body.triggerRef();
            WorkflowRunEntity run = engine.run(key, input,
                    // 页面上没登录态，发起人取操作者头：审计里能看到"谁点的运行"，
                    // 比留空或写死一个 console 有用得多。
                    userId == null || userId.isBlank() ? operator(operator) : userId,
                    WorkflowRunEntity.TRIGGER_MANUAL, triggerRef);
            return ResponseEntity.ok(run);
        });
    }

    /** 某个工作流最近的执行（不含节点明细）。 */
    @GetMapping("/{key}/runs")
    public Mono<List<WorkflowRunEntity>> runs(@PathVariable String key,
                                              @RequestParam(value = "limit", required = false)
                                              Integer limit) {
        int capped = limit == null || limit <= 0 ? DEFAULT_RUN_LIMIT : Math.min(limit, 200);
        return blocking(() -> engine.recentRuns(key, capped));
    }

    // 运行详情的读接口不在本类，在 WorkflowRunController（前缀 /api/workflow-runs）——
    // 它属于运行层，且那样才能避开 /{key}/runs 与 /runs/{runId} 的路径歧义（见类注释第三节）。

    // ==================================================================================
    // 共用
    // ==================================================================================

    /**
     * 把阻塞调用挪到弹性线程池。
     *
     * <p>与 ModelController 里那个同名方法逐字相同，包括"异常会变成 onError
     * 从而仍被 {@code @ExceptionHandler} 接住"这一点。
     *
     * <p>为什么不去共享一个工具类：两个控制器各自只有一处需要这个三行方法，
     * 抽成公共类意味着将来"某处想换个调度器"要在两个屏幕上跳来跳去。
     * 等第三个地方需要它时再抽。
     */
    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }

    private static ResponseEntity<?> notFound(String key) {
        return ResponseEntity.status(404).body(Map.of("error", "workflow 不存在: " + key));
    }

    /** 审计字段的操作者；缺省 console。 */
    private static String operator(String raw) {
        return raw == null || raw.isBlank() ? "console" : raw.trim();
    }

    /**
     * 列表行：治理字段 + 节点/边数量。
     *
     * <p>数量由 spec 现算 —— 列表页要显示"几个节点"，而 {@code nodeCount} 这种
     * 派生值不该在库里单独占一列（会和 spec 不一致）。
     */
    public record WorkflowRow(String workflowKey, String displayName, String description, String status,
                              Integer version, Integer publishedVersion, Long publishedAtMs,
                              Long updatedAtMs, int nodeCount, int edgeCount) {

        static WorkflowRow of(WorkflowEntity entity) {
            WorkflowSpec spec = entity.getSpec();
            return new WorkflowRow(entity.getWorkflowKey(), entity.getDisplayName(), entity.getDescription(),
                    entity.getStatus(), entity.getVersion(), entity.getPublishedVersion(),
                    entity.getPublishedAtMs(), entity.getUpdatedAtMs(),
                    spec == null ? 0 : spec.nodesOrEmpty().size(),
                    spec == null ? 0 : spec.edgesOrEmpty().size());
        }
    }

    /** 校验请求体：spec 可空（为空表示"校验库里存的草稿"）。 */
    public record ValidateRequest(WorkflowSpec spec) {}

    /** 发布请求体：change-note 可空。 */
    public record PublishRequest(String changeNote) {}

    /** 回滚请求体：目标版本号。 */
    public record VersionRequest(int version) {}

    /**
     * 运行请求体：全部字段可空。
     *
     * <p>{@code userId} 可空是因为它默认取操作者头；留出这个字段是为了将来
     * "以某个业务用户的身份跑一次"（比如复现某次企微会话的问题）。
     */
    public record RunRequest(Map<String, Object> input, String userId, String triggerRef) {}
}