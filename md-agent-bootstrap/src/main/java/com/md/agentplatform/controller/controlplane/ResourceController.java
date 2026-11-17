package com.md.agentplatform.controller.controlplane;

import com.md.agentplatform.server.controlplane.ControlPlanePublishService;
import com.md.agentplatform.server.controlplane.MiddlewareCatalog;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ResourceType;
import com.md.agentplatform.server.controlplane.store.ResourceVersionInfo;
import com.md.agentplatform.server.controlplane.store.entity.McpServerEntity;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import com.md.agentplatform.server.controlplane.store.entity.SkillMetaEntity;
import io.agentscope.core.skill.AgentSkill;
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

import java.util.List;
import java.util.Map;

/**
 * 能力资源治理接口 —— prompt / skill / mcp 三类资源 + 只读的中间件目录。
 *
 * <h2>一、三类资源同构，为什么还是写三套方法</h2>
 * 路径段与载荷类型都不同（prompt 直接收实体、skill 收「元数据 + 正文 + 附属文件」、mcp 收连接配置），
 * 硬做成泛型只会把差异藏进 {@code instanceof} 分支里。真正重复的是「删除 + 引用保护 + 版本 + 回滚」
 * 这四件事的<b>流程</b>，所以它们抽成私有方法，按类型分派（见 {@link #deleteChecked}）。
 *
 * <h2>二、删除为什么回 409 而不是 400</h2>
 * 400 = "你提交的内容不合法"，409 = "内容合法，但当前状态不允许"。
 * 删一个被引用的 prompt 属于后者：请求本身没问题，是关系上不允许。
 * 而且 409 的 body 里带上<b>引用方清单</b>，页面可以直接列出来让人先去解绑。
 *
 * <h2>三、operator 从哪来</h2>
 * 取自请求头 {@code X-Operator}，缺省 {@code console}。这次不做登录，
 * 但不留空 —— 审计字段（{@code created_by / updated_by}）一旦允许为空，
 * 后面再想补"谁改的"就永远补不回来了。
 */
@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    @Autowired
    private ControlPlaneStore store;

    @Autowired
    private ControlPlanePublishService publishService;

    @Autowired
    private MiddlewareCatalog middlewareCatalog;

    // ==================================================================================
    // Prompt
    // ==================================================================================

    /** 列表：含状态 / 版本 / 更新时间，也含正文（页面预览要用，几十条无所谓）。 */
    @GetMapping("/prompts")
    public List<PromptEntity> listPrompts() {
        return store.listPrompts();
    }

    @GetMapping("/prompts/{key}")
    public ResponseEntity<?> getPrompt(@PathVariable String key) {
        PromptEntity prompt = store.findPrompt(key);
        return prompt == null ? notFound("prompt", key) : ResponseEntity.ok(prompt);
    }

    @PostMapping("/prompts")
    public ResponseEntity<?> createPrompt(@RequestBody PromptEntity body,
                                          @RequestHeader(value = "X-Operator", required = false) String operator) {
        boolean created = store.createPrompt(body, operator(operator));
        return created ? ResponseEntity.ok(body) : conflict("prompt-key 已存在: " + body.getPromptKey());
    }

    @PutMapping("/prompts/{key}")
    public ResponseEntity<?> savePrompt(@PathVariable String key, @RequestBody PromptEntity body,
                                        @RequestHeader(value = "X-Operator", required = false) String operator) {
        body.setPromptKey(key); // 路径是权威：body 里的 key 只用于展示，防止改名绕过
        return store.savePrompt(body, operator(operator)) ? ResponseEntity.ok(body) : notFound("prompt", key);
    }

    @DeleteMapping("/prompts/{key}")
    public ResponseEntity<?> deletePrompt(@PathVariable String key) {
        return deleteChecked(ResourceType.PROMPT, key, () -> store.deletePrompt(key));
    }

    @PostMapping("/prompts/{key}/publish")
    public ResponseEntity<?> publishPrompt(@PathVariable String key,
                                          @RequestHeader(value = "X-Operator", required = false) String operator,
                                          @RequestBody(required = false) PublishRequest body) {
        return publishChecked(ResourceType.PROMPT, key, operator, body);
    }

    @GetMapping("/prompts/{key}/versions")
    public List<ResourceVersionInfo> promptVersions(@PathVariable String key) {
        return store.listVersions(ResourceType.PROMPT, key);
    }

    @PostMapping("/prompts/{key}/rollback")
    public ResponseEntity<?> rollbackPrompt(@PathVariable String key, @RequestBody VersionRequest body,
                                            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return rollbackChecked(ResourceType.PROMPT, key, body, operator);
    }

    // ==================================================================================
    // Skill（内容在官方表，治理元数据在 md_skill_meta）
    // ==================================================================================

    /** 列表：只回治理元数据（正文要回查官方表，列表页不需要，设计文档 §4.3）。 */
    @GetMapping("/skills")
    public List<SkillMetaEntity> listSkills() {
        return store.listSkillMetas();
    }

    /** 详情：元数据 + 正文 + 附属文件（页面编辑抽屉的三块内容）。 */
    @GetMapping("/skills/{name}")
    public ResponseEntity<?> getSkill(@PathVariable String name) {
        SkillMetaEntity meta = store.findSkillMeta(name);
        if (meta == null) {
            return notFound("skill", name);
        }
        AgentSkill content = store.findSkillContent(name);
        return ResponseEntity.ok(Map.of(
                "meta", meta,
                // 内容表里没有（漂移，§10.7）时给空串而不是 500：页面能看到 meta、也知道正文丢了
                "body", content == null || content.getSkillContent() == null ? "" : content.getSkillContent(),
                "resources", content == null || content.getResources() == null ? Map.of() : content.getResources()));
    }

    @PostMapping("/skills")
    public ResponseEntity<?> createSkill(@RequestBody SkillUpsert body,
                                         @RequestHeader(value = "X-Operator", required = false) String operator) {
        boolean created = store.createSkill(body.meta(), body.body(), body.resources(), operator(operator));
        return created ? ResponseEntity.ok(body) : conflict("skill 已存在: " + skillName(body));
    }

    @PutMapping("/skills/{name}")
    public ResponseEntity<?> saveSkill(@PathVariable String name, @RequestBody SkillUpsert body,
                                       @RequestHeader(value = "X-Operator", required = false) String operator) {
        if (body.meta() != null) {
            body.meta().setSkillName(name);
        }
        return store.saveSkill(body.meta(), body.body(), body.resources(), operator(operator))
                ? ResponseEntity.ok(body) : notFound("skill", name);
    }

    @DeleteMapping("/skills/{name}")
    public ResponseEntity<?> deleteSkill(@PathVariable String name) {
        return deleteChecked(ResourceType.SKILL, name, () -> store.deleteSkill(name));
    }

    @PostMapping("/skills/{name}/publish")
    public ResponseEntity<?> publishSkill(@PathVariable String name,
                                          @RequestHeader(value = "X-Operator", required = false) String operator,
                                          @RequestBody(required = false) PublishRequest body) {
        return publishChecked(ResourceType.SKILL, name, operator, body);
    }

    @GetMapping("/skills/{name}/versions")
    public List<ResourceVersionInfo> skillVersions(@PathVariable String name) {
        return store.listVersions(ResourceType.SKILL, name);
    }

    @PostMapping("/skills/{name}/rollback")
    public ResponseEntity<?> rollbackSkill(@PathVariable String name, @RequestBody VersionRequest body,
                                           @RequestHeader(value = "X-Operator", required = false) String operator) {
        return rollbackChecked(ResourceType.SKILL, name, body, operator);
    }

    // ==================================================================================
    // MCP
    // ==================================================================================

    @GetMapping("/mcp")
    public List<McpServerEntity> listMcp() {
        return store.listMcpServers();
    }

    @GetMapping("/mcp/{name}")
    public ResponseEntity<?> getMcp(@PathVariable String name) {
        McpServerEntity server = store.findMcpServer(name);
        return server == null ? notFound("mcp", name) : ResponseEntity.ok(server);
    }

    @PostMapping("/mcp")
    public ResponseEntity<?> createMcp(@RequestBody McpServerEntity body,
                                       @RequestHeader(value = "X-Operator", required = false) String operator) {
        boolean created = store.createMcpServer(body, operator(operator));
        return created ? ResponseEntity.ok(body) : conflict("MCP 已存在: " + body.getServerName());
    }

    @PutMapping("/mcp/{name}")
    public ResponseEntity<?> saveMcp(@PathVariable String name, @RequestBody McpServerEntity body,
                                     @RequestHeader(value = "X-Operator", required = false) String operator) {
        body.setServerName(name);
        return store.saveMcpServer(body, operator(operator)) ? ResponseEntity.ok(body) : notFound("mcp", name);
    }

    @DeleteMapping("/mcp/{name}")
    public ResponseEntity<?> deleteMcp(@PathVariable String name) {
        return deleteChecked(ResourceType.MCP, name, () -> store.deleteMcpServer(name));
    }

    /** 发布 MCP：除了落库，还会失效 MCP 连接缓存并重建引用它的 agent（见 ControlPlanePublishService）。 */
    @PostMapping("/mcp/{name}/publish")
    public ResponseEntity<?> publishMcp(@PathVariable String name,
                                        @RequestHeader(value = "X-Operator", required = false) String operator,
                                        @RequestBody(required = false) PublishRequest body) {
        return publishChecked(ResourceType.MCP, name, operator, body);
    }

    @GetMapping("/mcp/{name}/versions")
    public List<ResourceVersionInfo> mcpVersions(@PathVariable String name) {
        return store.listVersions(ResourceType.MCP, name);
    }

    @PostMapping("/mcp/{name}/rollback")
    public ResponseEntity<?> rollbackMcp(@PathVariable String name, @RequestBody VersionRequest body,
                                         @RequestHeader(value = "X-Operator", required = false) String operator) {
        return rollbackChecked(ResourceType.MCP, name, body, operator);
    }

    // ==================================================================================
    // 只读目录
    // ==================================================================================

    /** 中间件 bean 目录：页面上的「中间件」多选框选项来自这里（中间件与工具都不落库，见 MiddlewareCatalog）。 */
    @GetMapping("/middleware/catalog")
    public List<MiddlewareCatalog.MiddlewareMeta> middlewareCatalog() {
        return middlewareCatalog.list();
    }

    /**
     * 当前全局生效的中间件链。
     *
     * <p>本项目只有一个全局中间件（调用链路观测），其余中间件都是按 agent 声明挂载的 ——
     * 所以这里"全局链"很短，逐 agent 的链要看 agent 详情。把这件事在接口层说清楚，
     * 比返回一个看起来像"全量链"的列表更有用。
     */
    @GetMapping("/middleware")
    public Map<String, Object> middlewareChain() {
        return Map.of(
                "global", List.of("AgentRunTraceMiddleware"),
                "note", "其余中间件按 agent 的 md_agent_ref.middleware 声明挂载，见 agent 详情");
    }

    // ==================================================================================
    // 共用流程
    // ==================================================================================

    /**
     * 删除前的引用保护 + 删除。
     *
     * <p>顺序固定为「存在性 → 引用 → 删」：先判存在是为了把"删一个不存在的资源"与
     * "删一个被引用的资源"分开成 404 / 409 两种明确答复；引用检查必须在真正删除之前，
     * 否则删完再查引用已经晚了（引用方会指向一个不存在的名字）。
     */
    private ResponseEntity<?> deleteChecked(ResourceType type, String name, Runnable deleter) {
        if (!exists(type, name)) {
            return notFound(type.name().toLowerCase(), name);
        }
        List<String> referencing = store.findReferencingAgents(type, name);
        if (!referencing.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "被 " + referencing.size() + " 个 agent 引用，不能删除",
                    "referencingAgents", referencing));
        }
        deleter.run();
        return ResponseEntity.ok(Map.of("deleted", name));
    }

    private boolean exists(ResourceType type, String name) {
        return switch (type) {
            case PROMPT -> store.findPrompt(name) != null;
            case SKILL -> store.findSkillMeta(name) != null;
            case MCP -> store.findMcpServer(name) != null;
            // 模型有自己的控制器（ModelController）—— 它比另外几类多两件事：
            // 打码/明文两种读形状、连通性探测。这里保留一个分支只是为了 switch 的穷尽性。
            case MODEL -> store.findModel(name) != null;
            case AGENT -> store.findAgent(name) != null;
            // 渠道的 CRUD 有自己的控制器（ChannelController）—— 它比另外四类多两件事：
            // 运行时状态、以及"发布完要立刻热生效"。这里保留一个分支只是为了 switch 的穷尽性，
            // 让"将来再加一类资源"时编译器仍然能拦住漏改。
            case CHANNEL -> store.findChannel(name) != null;
            // 工作流的 CRUD 有自己的控制器（WorkflowController）—— 它比另外几类多两件事：
            // 画布用的 spec 校验、以及"跑一次"的入口。这里保留一个分支只是为了 switch 的穷尽性，
            // 让"将来再加一类资源"时编译器仍然能拦住漏改。
            case WORKFLOW -> store.findWorkflow(name) != null;
        };
    }

    private ResponseEntity<?> publishChecked(ResourceType type, String name, String operator,
                                             PublishRequest body) {
        if (!exists(type, name)) {
            return notFound(type.name().toLowerCase(), name);
        }
        String note = body == null ? null : body.changeNote();
        return ResponseEntity.ok(publishService.publish(type, name, operator(operator), note));
    }

    private ResponseEntity<?> rollbackChecked(ResourceType type, String name, VersionRequest body,
                                              String operator) {
        if (!exists(type, name)) {
            return notFound(type.name().toLowerCase(), name);
        }
        return ResponseEntity.ok(publishService.rollback(type, name, body.version(), operator(operator)));
    }

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }

    private static ResponseEntity<?> notFound(String kind, String name) {
        return ResponseEntity.status(404).body(Map.of("error", kind + " 不存在: " + name));
    }

    /** 审计字段的操作者；缺省 console。 */
    private static String operator(String raw) {
        return raw == null || raw.isBlank() ? "console" : raw.trim();
    }

    private static String skillName(SkillUpsert body) {
        return body.meta() == null ? "(未命名)" : body.meta().getSkillName();
    }

    /** 发布请求体：change-note 可空。 */
    public record PublishRequest(String changeNote) {}

    /** 回滚请求体：目标版本号。 */
    public record VersionRequest(int version) {}

    /**
     * skill 的写入载荷：治理元数据 + 正文 + 附属文件。
     *
     * <p>为什么把三者放一个对象而不是像 prompt 那样直接收实体：
     * skill 的内容与治理元数据<b>物理上分处两个 schema</b>（设计文档 §4.3.1），
     * 但逻辑上是一次提交，拆成三个接口会让页面出现"正文存了、meta 没存"的中间态。
     */
    public record SkillUpsert(SkillMetaEntity meta, String body, Map<String, String> resources) {}
}
