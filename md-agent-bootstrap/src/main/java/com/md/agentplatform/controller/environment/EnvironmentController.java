package com.md.agentplatform.controller.environment;

import com.md.agentplatform.server.environment.EnvironmentAdminService;
import com.md.agentplatform.server.environment.EnvironmentCatalog;
import com.md.agentplatform.server.environment.EnvironmentViews;
import com.md.agentplatform.server.environment.EnvironmentViews.EnvironmentRow;
import com.md.agentplatform.server.environment.EnvironmentViews.GroupSaveRequest;
import com.md.agentplatform.server.environment.EnvironmentViews.GroupTemplate;
import com.md.agentplatform.server.environment.EnvironmentViews.InstanceDetail;
import com.md.agentplatform.server.environment.EnvironmentViews.InstanceRow;
import com.md.agentplatform.server.environment.EnvironmentViews.InstanceSaveRequest;
import com.md.agentplatform.server.environment.EnvironmentViews.SaveRequest;
import com.md.agentplatform.server.environment.store.EnvironmentStore;
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
 * 环境目录治理接口 —— 与前端「环境」页（{@code /environments}）一一对应。
 *
 * <ul>
 *   <li>GET    /api/environments                        列表（每个分组的状态 + 完整度，不含量）</li>
 *   <li>GET    /api/environments/{envCode}              详情（只含环境主表字段与已绑定实例，不含配置值）</li>
 *   <li>POST   /api/environments                        新建（env_code 重名 409）</li>
 *   <li>PUT    /api/environments/{envCode}              保存（不存在 404；分组 → 实例绑定<b>整体替换</b>）</li>
 *   <li>DELETE /api/environments/{envCode}              删除（被 agent 引用时 409 + 引用方清单）</li>
 *   <li>GET    /api/env-groups                          分组模板列表（含键声明）</li>
 *   <li>POST   /api/env-groups                          新建分组</li>
 *   <li>PUT    /api/env-groups/{groupCode}              保存分组 + 键声明（整体替换）</li>
 *   <li>DELETE /api/env-groups/{groupCode}              删除（内置分组 409）</li>
 *   <li>GET    /api/env-instances                       分组实例清单（全局元数据）</li>
 *   <li>GET    /api/env-groups/{groupCode}/instances/{instanceCode}  实例详情（含固定配置值）</li>
 *   <li>POST   /api/env-groups/{groupCode}/instances/{instanceCode}  新建实例</li>
 *   <li>PUT    /api/env-groups/{groupCode}/instances/{instanceCode}  更新实例元数据与固定配置值</li>
 *   <li>DELETE /api/env-groups/{groupCode}/instances/{instanceCode}  删除实例及其在所有环境下的值</li>
 * </ul>
 *
 * <h2>一、列表为什么不回值，也就没有"打码"这件事</h2>
 * 模型 / 渠道那两页的约定是"列表打码、详情明文"，因为它们的列表上确实要显示那一格值。
     * 环境页的列表格是"每格一个状态"（已配置 / 缺 Token / 未配置），<b>根本不需要值</b>。
     * 值只在服务端参与状态与完整度的计算（{@code admin.list()} 里读、算完就丢），
     * <b>不进响应体</b>：于是"明文过线"从形状上就不成立，也就不存在打码写错的可能。
     * 注意这里是"不回值"而不是"不读值" —— 状态本身就得靠值算出来。
 *
 * <h2>二、为什么没有"草稿 / 发布"</h2>
 * prompt / mcp / 模型那几类资源有草稿与发布，是因为它们的内容参与 agent 装配 ——
 * "改了还没发布"是一个真实且必要的中间态。环境目录不参与装配：
 * 工具在每次调用时按名字取连接信息，改完下一次调用就生效。
 * 给它加一层草稿，只会让"我明明改了地址，为什么还在连老的"变成一个需要先想明白才可以回答的问题。
 *
 * <h2>三、改动为什么立刻生效，靠什么保证</h2>
 * 服务层每次写完后调 {@code EnvironmentCatalog#invalidate()}，下一次读取重新加载整份快照。
 * 没有 TTL、没有后台刷新任务 —— 失效点只有"写入"这一处，肉眼可追（同 DbModelFactory 的取向）。
 *
 * <h2>四、为什么每个方法都 subscribeOn(boundedElastic)</h2>
 * 本项目是 WebFlux，而下面全是阻塞的 JDBC 调用。在 Netty 事件循环线程上做阻塞调用会卡住整条
 * 事件循环（同 SessionController / ModelController 的约定）。{@code Mono.fromCallable} 还顺带把
 * 服务层抛的校验异常变成 {@code onError}，让 {@code ControlPlaneExceptionHandler} 依然生效。
 */
@RestController
@RequestMapping("/api")
public class EnvironmentController {

    private final EnvironmentAdminService admin;
    private final EnvironmentStore store;

    public EnvironmentController(EnvironmentAdminService admin, EnvironmentStore store) {
        this.admin = admin;
        this.store = store;
    }

    // ==================================================================================
    // 环境
    // ==================================================================================

    @GetMapping("/environments")
    public Mono<List<EnvironmentRow>> list() {
        return blocking(admin::list);
    }

    @GetMapping("/environments/{envCode}")
    public Mono<ResponseEntity<?>> detail(@PathVariable String envCode) {
        return blocking(() -> {
            if (store.findEnvironment(envCode) == null) {
                return notFound(envCode);
            }
            return ResponseEntity.ok(admin.detail(envCode));
        });
    }

    @PostMapping("/environments")
    public Mono<ResponseEntity<?>> create(@RequestBody SaveRequest body,
                                          @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> admin.create(body, operator(operator))
                ? ResponseEntity.ok(Map.of("envCode", body.envCode().trim(), "created", true))
                : conflict("env_code 已存在: " + body.envCode()));
    }

    /** 路径上的 envCode 是权威：body 里的 envCode 只用于展示（改名 = 删除 + 新建）。 */
    @PutMapping("/environments/{envCode}")
    public Mono<ResponseEntity<?>> save(@PathVariable String envCode, @RequestBody SaveRequest body,
                                        @RequestHeader(value = "X-Operator", required = false) String operator) {
        return blocking(() -> admin.save(envCode, body, operator(operator))
                ? ResponseEntity.ok(Map.of("envCode", envCode, "saved", true))
                : notFound(envCode));
    }

    @DeleteMapping("/environments/{envCode}")
    public Mono<ResponseEntity<?>> delete(@PathVariable String envCode) {
        return blocking(() -> {
            if (store.findEnvironment(envCode) == null) {
                return notFound(envCode);
            }
            List<String> referencing = admin.referencingAgents(envCode);
            if (!referencing.isEmpty()) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "被 " + referencing.size() + " 个 agent 的工作区引用，不能删除",
                        "referencingAgents", referencing));
            }
            admin.delete(envCode);
            return ResponseEntity.ok(Map.of("deleted", envCode));
        });
    }

    // ==================================================================================
    // 分组实例
    // ==================================================================================

    /** 全部实例，供页面单独的「分组实例」维护区使用。 */
    @GetMapping("/env-instances")
    public Mono<List<InstanceRow>> instances() {
        return blocking(admin::instances);
    }

    @GetMapping("/env-groups/{groupCode}/instances/{instanceCode}")
    public Mono<ResponseEntity<?>> instanceDetail(@PathVariable String groupCode,
                                                  @PathVariable String instanceCode) {
        return blocking(() -> {
            InstanceDetail detail = admin.instanceDetail(groupCode, instanceCode);
            return detail == null ? instanceNotFound(groupCode, instanceCode) : ResponseEntity.ok(detail);
        });
    }

    @PostMapping("/env-groups/{groupCode}/instances/{instanceCode}")
    public Mono<ResponseEntity<?>> createInstance(@PathVariable String groupCode,
                                                  @PathVariable String instanceCode,
                                                  @RequestBody InstanceSaveRequest body) {
        return blocking(() -> {
            if (store.findGroup(groupCode) == null) {
                return groupNotFound(groupCode);
            }
            return admin.createInstance(groupCode, instanceCode, body)
                    ? ResponseEntity.ok(Map.of("instanceCode", instanceCode.trim(), "created", true))
                    : conflict("实例已存在: " + groupCode + " / " + instanceCode);
        });
    }

    @PutMapping("/env-groups/{groupCode}/instances/{instanceCode}")
    public Mono<ResponseEntity<?>> saveInstance(@PathVariable String groupCode,
                                                @PathVariable String instanceCode,
                                                @RequestBody InstanceSaveRequest body) {
        return blocking(() -> admin.saveInstance(groupCode, instanceCode, body)
                ? ResponseEntity.ok(Map.of("instanceCode", instanceCode.trim(), "saved", true))
                : instanceNotFound(groupCode, instanceCode));
    }

    @DeleteMapping("/env-groups/{groupCode}/instances/{instanceCode}")
    public Mono<ResponseEntity<?>> deleteInstance(@PathVariable String groupCode,
                                                  @PathVariable String instanceCode) {
        return blocking(() -> admin.deleteInstance(groupCode, instanceCode)
                ? ResponseEntity.ok(Map.of("deleted", instanceCode.trim()))
                : instanceNotFound(groupCode, instanceCode));
    }

    // ==================================================================================
    // 分组模板
    // ==================================================================================

    @GetMapping("/env-groups")
    public Mono<List<GroupTemplate>> groups() {
        return blocking(admin::groups);
    }

    @PostMapping("/env-groups")
    public Mono<ResponseEntity<?>> createGroup(@RequestBody GroupSaveRequest body) {
        return blocking(() -> admin.createGroup(body)
                ? ResponseEntity.ok(Map.of("groupCode", body.groupCode().trim(), "created", true))
                : conflict("group_code 已存在: " + body.groupCode()));
    }

    @PutMapping("/env-groups/{groupCode}")
    public Mono<ResponseEntity<?>> saveGroup(@PathVariable String groupCode, @RequestBody GroupSaveRequest body) {
        return blocking(() -> {
            EnvironmentAdminService.GroupSaveResult result = admin.saveGroup(groupCode, body);
            return result == null ? groupNotFound(groupCode) : ResponseEntity.ok(result);
        });
    }

    /** 内置分组（被工具代码引用）不允许删除，见 {@code EnvGroupEntity#builtin} 的注释。 */
    @DeleteMapping("/env-groups/{groupCode}")
    public Mono<ResponseEntity<?>> deleteGroup(@PathVariable String groupCode) {
        return blocking(() -> {
            if (store.findGroup(groupCode) == null) {
                return groupNotFound(groupCode);
            }
            if (admin.isBuiltin(groupCode)) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "内置分组被工具代码引用（EnvironmentRegistry 的强类型访问器），不能删除",
                        "groupCode", groupCode));
            }
            admin.deleteGroup(groupCode);
            return ResponseEntity.ok(Map.of("deleted", groupCode));
        });
    }

    // ==================================================================================
    // 共用
    // ==================================================================================

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }

    private static ResponseEntity<?> notFound(String envCode) {
        return ResponseEntity.status(404).body(Map.of("error", "环境不存在: " + envCode));
    }

    private static ResponseEntity<?> groupNotFound(String groupCode) {
        return ResponseEntity.status(404).body(Map.of("error", "分组不存在: " + groupCode));
    }

    private static ResponseEntity<?> instanceNotFound(String groupCode, String instanceCode) {
        return ResponseEntity.status(404).body(Map.of(
                "error", "实例不存在: " + groupCode + " / " + instanceCode));
    }

    /** 审计字段的操作者；缺省 console（本阶段没有登录，但不留空）。 */
    private static String operator(String raw) {
        return raw == null || raw.isBlank() ? "console" : raw.trim();
    }

    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }
}
