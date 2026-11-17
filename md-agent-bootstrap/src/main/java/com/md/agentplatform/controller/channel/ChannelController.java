package com.md.agentplatform.controller.channel;

import com.md.agentplatform.server.channel.ChannelKind;
import com.md.agentplatform.server.channel.ChannelPropertySpec;
import com.md.agentplatform.server.channel.ChannelRuntime;
import com.md.agentplatform.server.channel.ChannelRuntimeStatus;
import com.md.agentplatform.server.channel.ChannelTypeDescriptor;
import com.md.agentplatform.server.channel.ChannelTypeRegistry;
import com.md.agentplatform.server.channel.ChannelValidator;
import com.md.agentplatform.server.channel.ManagedGateway;
import com.md.agentplatform.server.controlplane.store.ChannelDefinitionBundle;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ResourceType;
import com.md.agentplatform.server.controlplane.store.ResourceVersionInfo;
import com.md.agentplatform.server.controlplane.store.entity.ChannelBindingEntity;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渠道治理接口 —— 渠道（{@code md_channel} + {@code md_channel_binding}）的增删改查 + 发布/回滚 + 热生效。
 *
 * <h2>一、渠道是"受治理资源"的第 5 类</h2>
 * 走与 prompt / skill / mcp / agent 完全相同的「草稿 → 发布 → 版本快照 → 回滚」。
 * 理由：渠道与 agent 不同 —— agent 改错了下一次对话才体现，而<b>渠道改错一个 botId 会直接断掉线上入口</b>
 * （企微/飞书连不上，用户发消息没有任何反应）。最需要"一键回到上一版"的恰恰是它。
 *
 * <h2>二、发布 = 落库 + 立刻热生效（与其它四类资源的唯一差异）</h2>
 * 别的资源发布后走 {@code ControlPlanePublishService} 去"重建引用它的 agent"；
 * 渠道没有引用方（引用是单向的：渠道绑 agent，agent 不认识渠道），
 * 它的生效方式是<b>重新装配自己</b>，所以这里直接调 {@code ChannelRuntime.apply}：
 * <ol>
 *   <li>{@code store.publish} 落快照并置 PUBLISHED；</li>
 *   <li>{@code channelRuntime.apply} 读新内容 → 指纹比对 → 需要就重启通道。</li>
 * </ol>
 * 顺序不能反：先热生效再落库，一旦落库失败，就把"跑着新内容、库里还是旧内容"这个状态留在线上，
 * 而渠道的装配源是库 —— 下次重载会把它悄悄改回去。
 *
 * <h2>三、没有"运行时停一下"的接口（故意的）</h2>
 * 停一个渠道的正确做法是把它 {@code enabled} 置 0 再发布 —— 那是<b>持久</b>的状态。
 * 如果做一个"只停运行时、不动库"的接口，这个状态活不过下一次「全量重载」
 * （重载会把所有 enabled 的渠道重新拉起来），于是页面显示"已停止"而实际在跑，
 * 或者反过来。不提供这种接口，是为了让"渠道在不在跑"永远能从库里推出来。
 * 单个渠道的重连需求由「重载」覆盖：内容没变但实例不健康时，{@code applyOne} 会重启它。
 *
 * <h2>四、凭据的打码取舍</h2>
 * <ul>
 *   <li><b>列表</b>把敏感字段（{@link ChannelPropertySpec#secret()}）替换成固定串 ——
 *       列表是"扫一眼"的场景，截屏/共享屏幕的概率最高；</li>
 *   <li><b>详情</b>返回明文，因为编辑表单要回填。这是刻意的取舍：本控制面目前没有登录
 *       （{@code X-Operator} 只是个审计字段），"打码但可绕过"只会造出安全错觉。
 *       真正需要收紧时，正确做法是给详情接口加鉴权，而不是在返回值里做一半的遮掩。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    /** 列表里敏感字段的替身；页面据此显示"已配置"，并禁止在这一栏回传。 */
    private static final String MASK = "******";

    @Autowired
    private ControlPlaneStore store;

    @Autowired
    private ChannelRuntime channelRuntime;

    @Autowired
    private ChannelTypeRegistry typeRegistry;

    @Autowired
    private ChannelValidator validator;

    // ==================================================================================
    // 读
    // ==================================================================================

    /**
     * 列表：库内容 + 运行时状态，敏感凭据打码。
     *
     * <p>数据源是 {@code loadAllChannelBundles()}（2 次查询：渠道 + 全部绑定），
     * 而不是"每个渠道查一次绑定"—— 渠道数量是个位数，但 N+1 的写法会随渠道数线性变慢。
     */
    @GetMapping
    public List<ChannelView> list() {
        Map<String, ChannelRuntimeStatus> runtime = channelRuntime.statuses().stream()
                .collect(Collectors.toMap(ChannelRuntimeStatus::channelId, status -> status));
        List<ChannelView> rows = new ArrayList<>();
        for (ChannelDefinitionBundle bundle : store.loadAllChannelBundles()) {
            rows.add(view(bundle, runtime.get(bundle.channel().getChannelId()), true));
        }
        return rows;
    }

    /**
     * 渠道类型清单 —— 页面的「类型」下拉与动态凭据表单的<b>唯一来源</b>。
     *
     * <p>每种类型自带它需要的凭据字段（含中文标签、是否必填、是否敏感），
     * 所以「加一个平台」在前端是零改动：新平台只是多返回一条描述。
     */
    @GetMapping("/types")
    public List<ChannelTypeDescriptor> types() {
        return typeRegistry.descriptors();
    }

    /** 详情：含绑定规则，敏感凭据<b>明文</b>（编辑表单要回填，理由见类注释第四节）。 */
    @GetMapping("/{channelId}")
    public ResponseEntity<?> get(@PathVariable String channelId) {
        ChannelDefinitionBundle bundle = store.loadChannelBundle(channelId);
        return bundle == null ? notFound(channelId) : ResponseEntity.ok(view(bundle, false));
    }

    /** 版本历史（不含快照正文）。 */
    @GetMapping("/{channelId}/versions")
    public ResponseEntity<?> versions(@PathVariable String channelId) {
        if (store.findChannel(channelId) == null) {
            return notFound(channelId);
        }
        return ResponseEntity.ok(store.listVersions(ResourceType.CHANNEL, channelId));
    }

    // ==================================================================================
    // 写（草稿）
    // ==================================================================================

    /**
     * 新建（草稿）。
     *
     * <p>新建成功后<b>不装配</b>：草稿态的东西不该跑到线上。
     * 渠道要真的收消息，得先发布 —— 见 {@link #publish}。
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody ChannelUpsert body,
                                    @RequestHeader(value = "X-Operator", required = false) String operator) {
        ChannelDefinitionBundle bundle = toBundle(body, null);
        if (bundle == null) {
            return badRequest(List.of("请求体缺少 channel 内容"));
        }
        List<String> problems = validator.problems(bundle);
        if (!problems.isEmpty()) {
            return badRequest(problems);
        }
        if (!store.createChannel(bundle, operator(operator))) {
            return conflict("渠道已存在: " + bundle.channel().getChannelId());
        }
        return ResponseEntity.ok(view(store.loadChannelBundle(bundle.channel().getChannelId()), false));
    }

    /**
     * 保存草稿（整体替换渠道行 + 全部绑定行）。
     *
     * <p>{@code channelId} 以路径为准（请求体里那份被覆盖）：改名是"删除 + 新建"，
     * 而不是"保存"，否则运行时的注册键会在用户没意识到的情况下变掉。
     */
    @PutMapping("/{channelId}")
    public ResponseEntity<?> save(@PathVariable String channelId, @RequestBody ChannelUpsert body,
                                  @RequestHeader(value = "X-Operator", required = false) String operator) {
        ChannelDefinitionBundle bundle = toBundle(body, channelId);
        if (bundle == null) {
            return badRequest(List.of("请求体缺少 channel 内容"));
        }
        List<String> problems = validator.problems(bundle);
        if (!problems.isEmpty()) {
            return badRequest(problems);
        }
        if (!store.saveChannel(bundle, operator(operator))) {
            return notFound(channelId);
        }
        return ResponseEntity.ok(view(store.loadChannelBundle(channelId), false));
    }

    /**
     * 删除渠道（连同它的绑定与版本快照）。
     *
     * <p>删完必须把运行实例停掉、并把路由条目一并摘掉：否则会出现"页面上已经没了、
     * 机器人还在收消息"（停实例漏了），或"消息按一份已删除的配置去路由、而通道已经停了"
     * ——后者表现为消息静默无回应，比报错更难查。这两件事都归 {@code ChannelRuntime}（它持有实例与路由表）。
     *
     * <p>用 {@code forget} 而不是 {@code stopNow}：删除是有意操作，不该在状态里留下
     * "渠道不存在"的红字（那条错误文案是留给「重载一个已消失的渠道」这种异常路径的）。
     */
    @DeleteMapping("/{channelId}")
    public ResponseEntity<?> delete(@PathVariable String channelId) {
        if (store.findChannel(channelId) == null) {
            return notFound(channelId);
        }
        store.deleteChannel(channelId);
        channelRuntime.forget(channelId);
        return ResponseEntity.ok(Map.of("deleted", channelId));
    }

    // ==================================================================================
    // 发布 / 回滚 / 热生效
    // ==================================================================================

    /**
     * 发布：落快照 + 置 PUBLISHED + <b>立刻热生效</b>。
     *
     * <p>发布前再跑一次类型/凭据校验（保存时也校验过，但两次校验的用途不同：
     * 保存时是为了"别把明显填错的东西存下来"，这里是为了"别把明显填错的东西推到线上"。
     * 中间隔着用户可能手工改了库 —— 所以不能省这一次）。
     */
    @PostMapping("/{channelId}/publish")
    public ResponseEntity<?> publish(@PathVariable String channelId,
                                     @RequestBody(required = false) PublishRequest body,
                                     @RequestHeader(value = "X-Operator", required = false) String operator) {
        ChannelDefinitionBundle bundle = store.loadChannelBundle(channelId);
        if (bundle == null) {
            return notFound(channelId);
        }
        List<String> problems = validator.problems(bundle);
        if (!problems.isEmpty()) {
            return badRequest(problems);
        }
        store.publish(ResourceType.CHANNEL, channelId, operator(operator),
                body == null ? null : body.changeNote());
        channelRuntime.apply(channelId);
        return ResponseEntity.ok(view(store.loadChannelBundle(channelId), false));
    }

    /**
     * 回滚：把历史快照写回草稿（{@code version} 继续 +1）。
     *
     * <p>与其它资源一致：<b>回滚不触发热生效</b>。回滚的语义是"准备一个旧内容的新版本"，
     * 线上仍在跑原版本 —— 这是防止"手滑点错回滚把线上切回三个月前"的最后一道闸。
     * 想真的切回去，再点一次「发布」。
     */
    @PostMapping("/{channelId}/rollback")
    public ResponseEntity<?> rollback(@PathVariable String channelId, @RequestBody VersionRequest body,
                                      @RequestHeader(value = "X-Operator", required = false) String operator) {
        if (store.findChannel(channelId) == null) {
            return notFound(channelId);
        }
        store.rollback(ResourceType.CHANNEL, channelId, body.version(), operator(operator));
        return ResponseEntity.ok(Map.of(
                "note", "已回滚到 v" + body.version() + " 并置为草稿；线上仍是原版本，需再点一次「发布」才生效",
                "channel", view(store.loadChannelBundle(channelId), false)));
    }

    /**
     * 重新装配一个渠道（读库 + 指纹比对，必要时重启）。
     *
     * <p>什么时候要用它：绕过控制面直接改了库；或者通道处于不健康状态想强制拉一次
     * （{@code applyOne} 在实例 {@code isAlive() == false} 时会重启，所以"点一下就好"）。
     */
    @PostMapping("/{channelId}/reload")
    public ResponseEntity<?> reload(@PathVariable String channelId) {
        if (store.findChannel(channelId) == null) {
            return notFound(channelId);
        }
        channelRuntime.apply(channelId);
        return ResponseEntity.ok(view(store.loadChannelBundle(channelId), false));
    }

    /**
     * 全量重载：重读库 → 重建网关 → 重建路由表 → 按需重启全部通道。
     *
     * <p>把网关状态一并返回：重建网关会影响路由兜底（"没命中任何规则时给谁"），
     * 而那是渠道行为的组成部分，分成两个接口返回会让页面出现"重载完了但不知道兜底变成了谁"。
     */
    @PostMapping("/reload-all")
    public ResponseEntity<?> reloadAll() {
        ManagedGateway gateway = channelRuntime.applyAll();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("gateway", gateway);
        response.put("channels", list());
        return ResponseEntity.ok(response);
    }

    // ==================================================================================
    // 私有：行形状与校验辅助
    // ==================================================================================

    private ChannelView view(ChannelDefinitionBundle bundle, boolean maskSecrets) {
        ChannelEntity channel = bundle.channel();
        return view(bundle, channelRuntime.statusOf(bundle), maskSecrets);
    }

    private ChannelView view(ChannelDefinitionBundle bundle, ChannelRuntimeStatus runtime, boolean maskSecrets) {
        ChannelEntity channel = bundle.channel();
        ChannelTypeDescriptor descriptor = typeRegistry.find(channel.getChannelType())
                .map(factory -> factory.descriptor()).orElse(null);
        return new ChannelView(
                channel.getChannelId(),
                channel.getChannelType(),
                descriptor == null ? null : descriptor.label(),
                descriptor == null ? null : descriptor.kind(),
                channel.getDisplayName(),
                channel.getDescription(),
                channel.getOwnerId(),
                channel.getDefaultAgentId(),
                channel.getDmScope(),
                channel.getReplyStyle(),
                maskSecrets ? mask(channel.getProperties(), descriptor) : channel.getProperties(),
                channel.getEnabled() == null || Boolean.TRUE.equals(channel.getEnabled()),
                channel.getStatus(),
                channel.getVersion(),
                channel.getPublishedVersion(),
                channel.getPublishedAtMs(),
                channel.getCreatedAtMs(),
                channel.getUpdatedAtMs(),
                channel.getCreatedBy(),
                channel.getUpdatedBy(),
                bundle.bindings(),
                runtime);
    }

    /**
     * 把敏感字段的值换成固定串。
     *
     * <p>"哪些字段敏感"来自类型描述而不是硬编码字段名 —— 否则每加一个平台都要回来改这里，
     * 而漏改的表现是"凭据明文出现在列表里"，没人会注意到。
     */
    private Map<String, Object> mask(Map<String, Object> properties, ChannelTypeDescriptor descriptor) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        Set<String> secretKeys = descriptor == null
                ? Set.of()
                : descriptor.properties().stream()
                        .filter(ChannelPropertySpec::secret)
                        .map(ChannelPropertySpec::key)
                        .collect(Collectors.toSet());
        Map<String, Object> masked = new HashMap<>(properties);
        for (String key : secretKeys) {
            Object value = masked.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                masked.put(key, MASK);
            }
        }
        return masked;
    }

    /** 请求体 → bundle；{@code forcedId} 非空时以它为准（保存路径）。 */
    private static ChannelDefinitionBundle toBundle(ChannelUpsert body, String forcedId) {
        if (body == null || body.channel() == null) {
            return null;
        }
        ChannelEntity channel = body.channel();
        if (forcedId != null && !forcedId.isBlank()) {
            channel.setChannelId(forcedId);
        }
        return new ChannelDefinitionBundle(channel, body.bindings());
    }

    private static ResponseEntity<?> badRequest(List<String> violations) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "校验未通过（" + violations.size() + " 条）",
                "violations", violations));
    }

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("error", message));
    }

    private static ResponseEntity<?> notFound(String channelId) {
        return ResponseEntity.status(404).body(Map.of("error", "渠道不存在: " + channelId));
    }

    /** 审计字段的操作者；缺省 console（与 {@code ResourceController} 同一约定）。 */
    private static String operator(String raw) {
        return raw == null || raw.isBlank() ? "console" : raw.trim();
    }

    // ==================================================================================
    // 请求 / 响应形状
    // ==================================================================================

    /** 写入载荷：渠道行 + 它的全部绑定行（一次提交，与 {@code ChannelDefinitionBundle} 同形）。 */
    public record ChannelUpsert(ChannelEntity channel, List<ChannelBindingEntity> bindings) {}

    /** 发布请求体：change-note 可空。 */
    public record PublishRequest(String changeNote) {}

    /** 回滚请求体：目标版本号。 */
    public record VersionRequest(int version) {}

    /**
     * 页面用的渠道行 —— 库内容 + 运行时状态的合并结果。
     *
     * <p>为什么把两处合成一个对象：页面一行要同时显示"库里配了什么"（类型/兜底 agent/绑定数）
     * 与"现在跑成什么样"（started / alive / error）。分成两个接口返回，前端就要自己按 id 拼，
     * 而拼错了的表现是"状态显示串行"—— 这种 bug 在页面上看起来像数据问题，实际是接口设计问题。
     *
     * <p>{@code typeLabel} / {@code kind} 为 null = 该类型未注册（工厂被删了或类型名写错了），
     * 页面应当显式提示，而不是显示空白。
     */
    public record ChannelView(
            String channelId,
            String channelType,
            String typeLabel,
            ChannelKind kind,
            String displayName,
            String description,
            String ownerId,
            String defaultAgentId,
            String dmScope,
            String replyStyle,
            Map<String, Object> properties,
            boolean enabled,
            String status,
            Integer version,
            Integer publishedVersion,
            Long publishedAtMs,
            Long createdAtMs,
            Long updatedAtMs,
            String createdBy,
            String updatedBy,
            List<ChannelBindingEntity> bindings,
            ChannelRuntimeStatus runtime) {}
}
