package com.md.agentplatform.controller.model;

import com.md.agentplatform.server.controlplane.ControlPlanePublishService;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ResourceType;
import com.md.agentplatform.server.controlplane.store.ResourceVersionInfo;
import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;
import com.md.agentplatform.server.model.DbModelFactory;
import com.md.agentplatform.server.model.ModelAdminService;
import com.md.agentplatform.server.model.ModelViews;
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

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 模型治理接口 —— 与前端「模型」页（{@code /models}）一一对应。
 *
 * <ul>
 *   <li>GET    /api/models                    列表（密钥打码 + 引用数 + 近 7 日用量）</li>
 *   <li>GET    /api/models/defaults           调用参数默认值（兜底来自库还是 application.yml）</li>
 *   <li>GET    /api/models/{key}              详情（<b>明文密钥</b>，供编辑表单回填）</li>
 *   <li>POST   /api/models                    新建草稿（重名 409）</li>
 *   <li>PUT    /api/models/{key}              保存草稿（不存在 404）</li>
 *   <li>DELETE /api/models/{key}              删除（被引用 409 + 引用方清单）</li>
 *   <li>POST   /api/models/{key}/publish      发布（落快照 + 重建引用它的 agent）</li>
 *   <li>POST   /api/models/{key}/rollback     回滚内容到某版本（只写草稿，不热装配）</li>
 *   <li>GET    /api/models/{key}/versions     版本历史</li>
 *   <li>POST   /api/models/{key}/test         连通性探测（真发一次 GET {baseUrl}/models）</li>
 * </ul>
 *
 * <h2>一、为什么列表打码、详情明文</h2>
 * 沿用渠道凭据那套约定（{@code ChannelController} 的类注释）：列表是"扫一眼/截屏"的场景，
 * 详情是"编辑表单回填"的场景。若详情也给打码值，用户一保存就会把真密钥覆盖成 {@code ******} ——
 * 那是一次点击就永久损坏凭据的事故。
 *
 * <h2>二、保存与发布的关系（与 prompt / mcp 完全一致）</h2>
 * 「保存」只落草稿，线上行为不变；「发布」才落快照 + 重建引用它的 agent。
 * 端点是会轮换的凭据，改它属于线上变更，所以这里不提供"保存即生效"的捷径 ——
 * 那会让"我改了个备注"和"我换了端点"变成同一个动作。
 *
 * <h2>三、删除为什么要先查引用</h2>
 * 删掉一个被引用的模型，引用它的 agent 会在下次装配时炸（模型不存在）。
 * 那种失败发生在发布/重启路径上，离"我删了个模型"很远，所以在这里直接挡住并列出引用方。
 *
 * <h2>四、为什么每个方法都 subscribeOn(boundedElastic)</h2>
 * 本项目是 WebFlux，而下面全是阻塞的 JDBC 与一次阻塞的 HTTP 探测。
 * 在 Netty 事件循环线程上做阻塞调用会把整条事件循环卡住（同 SessionController 的约定）。
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelAdminService modelAdmin;
    private final ControlPlaneStore store;
    private final ControlPlanePublishService publishService;
    private final DbModelFactory modelFactory;

    public ModelController(ModelAdminService modelAdmin, ControlPlaneStore store,
                           ControlPlanePublishService publishService, DbModelFactory modelFactory) {
        this.modelAdmin = modelAdmin;
        this.store = store;
        this.publishService = publishService;
        this.modelFactory = modelFactory;
    }

    // ==================================================================================
    // 读
    // ==================================================================================

    @GetMapping
    public Mono<List<ModelViews.ModelRow>> list() {
        return blocking(modelAdmin::list);
    }

    @GetMapping("/defaults")
    public Mono<ModelViews.ModelDefaults> defaults() {
        return blocking(modelAdmin::defaults);
    }

    @GetMapping("/{modelKey}")
    public Mono<ResponseEntity<?>> detail(@PathVariable String modelKey) {
        return blocking(() -> {
            ModelViews.ModelDetail detail = modelAdmin.detail(modelKey);
            // 404 走 notFound(modelKey)：带 { error } 文案，页面直接显示"哪个模型不存在"
            return detail == null ? notFound(modelKey) : ResponseEntity.ok(detail);
        });
    }

    @GetMapping("/{modelKey}/versions")
    public Mono<List<ResourceVersionInfo>> versions(@PathVariable String modelKey) {
        return blocking(() -> store.listVersions(ResourceType.MODEL, modelKey));
    }

    // ==================================================================================
    // 写
    // ==================================================================================

    @PostMapping
    public Mono<ResponseEntity<?>> create(@RequestBody ModelEntity body,
                                          @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> modelAdmin.create(body, operator(operator))
                ? ResponseEntity.ok(body)
                : conflict("model-key 已存在: " + body.getModelKey()));
    }

    /** 路径上的 key 是权威：body 里的 modelKey 只用于展示（改名 = 删除+新建，不是保存）。 */
    @PutMapping("/{modelKey}")
    public Mono<ResponseEntity<?>> save(@PathVariable String modelKey, @RequestBody ModelEntity body,
                                        @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> modelAdmin.save(modelKey, body, operator(operator))
                ? ResponseEntity.ok(body)
                : notFound(modelKey));
    }

    @DeleteMapping("/{modelKey}")
    public Mono<ResponseEntity<?>> delete(@PathVariable String modelKey) {
        return blocking(() -> {
            if (store.findModel(modelKey) == null) {
                return notFound(modelKey);
            }
            List<String> referencing = modelAdmin.referencingAgents(modelKey);
            if (!referencing.isEmpty()) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "被 " + referencing.size() + " 个 agent 引用，不能删除",
                        "referencingAgents", referencing));
            }
            store.deleteModel(modelKey);
            // 行都没了，缓存里的实例也不该留着（改内容的失效靠 updated_at 内容戳，见 DbModelFactory）
            modelFactory.invalidate(modelKey);
            return ResponseEntity.ok(Map.of("deleted", modelKey));
        });
    }

    @PostMapping("/{modelKey}/publish")
    public Mono<ResponseEntity<?>> publish(@PathVariable String modelKey,
                                           @RequestHeader(value = "X-Operator", required = false) String operator,
                                           @RequestBody(required = false) PublishRequest body) {
        return blocking(() -> {
            if (store.findModel(modelKey) == null) {
                return notFound(modelKey);
            }
            return ResponseEntity.ok(publishService.publish(ResourceType.MODEL, modelKey,
                    operator(operator), body == null ? null : body.changeNote()));
        });
    }

    @PostMapping("/{modelKey}/rollback")
    public Mono<ResponseEntity<?>> rollback(@PathVariable String modelKey, @RequestBody VersionRequest body,
                                            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> {
            if (store.findModel(modelKey) == null) {
                return notFound(modelKey);
            }
            return ResponseEntity.ok(publishService.rollback(ResourceType.MODEL, modelKey,
                    body.version(), operator(operator)));
        });
    }

    /**
     * 连通性探测。
     *
     * <p>只探测、不落库：它是一次"现在通不通"的现场检查（例如刚填完密钥想确认），
     * 而不是模型内容的一部分。
     */
    @PostMapping("/{modelKey}/test")
    public Mono<ModelViews.ConnectionTest> test(@PathVariable String modelKey) {
        return blocking(() -> modelAdmin.testConnection(modelKey));
    }

    // ==================================================================================
    // 共用
    // ==================================================================================

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }

    private static ResponseEntity<?> notFound(String modelKey) {
        return ResponseEntity.status(404).body(Map.of("error", "模型不存在: " + modelKey));
    }

    /** 审计字段的操作者；缺省 console（本阶段没有登录，但不留空）。 */
    private static String operator(String raw) {
        return raw == null || raw.isBlank() ? "console" : raw.trim();
    }

    /**
     * 把阻塞调用挪到弹性线程池。
     *
     * <p>{@code fromCallable} 的另一个好处：服务层抛的异常会变成 {@code onError}
     * 而不是直接冒到事件循环上，异常从响应式管道里出来时 {@code @ExceptionHandler} 依然生效
     * （模型校验失败要回 400 + violations 就依赖这一点）。
     */
    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }

    /** 发布请求体：change-note 可空。 */
    public record PublishRequest(String changeNote) {
    }

    /** 回滚请求体：目标版本号。 */
    public record VersionRequest(int version) {
    }
}