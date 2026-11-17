package com.md.agentplatform.server.environment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.environment.store.EnvironmentStore;
import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceBindingEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceConfigEntity;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 环境目录的<b>读模型</b> —— 工具与页面都从这里拿数据，不直接碰 {@link EnvironmentStore}。
 *
 * <h2>一、为什么是"整份快照 + 显式失效"，不是逐次查库</h2>
 * 环境信息的读取特征：<b>极高频、极小量、极少变</b>。一次工具调用可能解析环境好几次，
 * 而整份目录（环境数 × 11 组 × 少量键）也就几百行、几十 KB。
 * 逐次查库会让"每次工具调用"都背上 4 条 SQL 的网络往返，而这些数据一周都未必改一次。
 * 所以：首次读时加载整份，写路径显式调 {@link #invalidate()}。
 *
 * <p>为什么不用 {@code @Cacheable} / 事件监听去自动失效：环境目录的写入点只有
 * {@code EnvironmentAdminService} 一个，显式调用比"某个注解在什么条件下失效"更好追。
 * 这条判断与 {@code DbModelFactory} 的内容戳是同一个取向：让失效条件肉眼可见。
 *
 * <h2>二、环境信息只从数据库读取</h2>
 * <ol>
 *   <li><b>从未成功读过，且这次失败</b>（典型：{@code db/env-schema.sql} 还没执行）：
 *       直接抛出带数据库错误原因的异常。{@code application.yml} 不再作为兜底，
 *       避免用一份过时配置悄悄替换线上真实配置。</li>
 *   <li><b>已经成功读过，这次失败</b>（典型：运行中数据库抖了一下）：
 *       <b>继续用上一次的快照</b>，只 ERROR。<b>绝不</b>在这时退回配置文件 ——
 *       那等于用一份可能是八天前的配置悄悄替换掉线上的真实配置，
 *       工具会连到一个早已不存在的地址上，而日志里只有一行 WARN。</li>
 * </ol>
 *
 * <h2>三、强类型投影为什么允许"多出来的键"</h2>
 * 模板可以随时加键，加完不该让已有的
 * {@code K8s} / {@code Nacos} 投影集体抛异常，所以投影用的 mapper 关掉了未知字段检查。
 */
@Component
public class EnvironmentCatalog {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentCatalog.class);

    /** 单实例时代的默认实例标识；所有未显式指定实例的旧调用都解析到它。 */
    public static final String DEFAULT_INSTANCE = "default";

    /** 一个环境：主表行 + 分组 → 实例 → 键 → 值。实例配置属于实例本身，所有绑定它的环境共享。 */
    public record EnvData(EnvironmentEntity meta,
                          Map<String, Map<String, Map<String, String>>> values) {

        /** 某个分组下全部实例的值；环境里没有这一组时返回空 Map。 */
        public Map<String, Map<String, String>> group(String groupCode) {
            Map<String, Map<String, String>> values = this.values.get(groupCode);
            return values == null ? Map.of() : values;
        }

        /** 某个分组下某个实例的值；任一层不存在时返回空 Map。 */
        public Map<String, String> instance(String groupCode, String instanceCode) {
            Map<String, String> values = group(groupCode).get(instanceCode);
            return values == null ? Map.of() : values;
        }

    }

    /** 一份完整快照。所有字段都是只读的（构造后不再改）。 */
    public record Snapshot(Map<String, EnvData> environments,
                           List<EnvGroupEntity> groups, Map<String, List<EnvGroupKeyEntity>> keysByGroup,
                           Map<String, List<EnvGroupInstanceEntity>> instancesByGroup,
                           Map<String, Map<String, Map<String, String>>> instanceConfigs,
                           Map<String, Map<String, Set<String>>> bindingsByEnv) {
    }

    private static final Snapshot EMPTY = new Snapshot(
            Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private final EnvironmentStore store;

    /**
     * 投影用的 mapper。单独一份而不是共用 Spring 容器里那个：容器里那个是给 HTTP 用的，
     * 它的 {@code FAIL_ON_UNKNOWN_PROPERTIES} 等开关可能被别人按需调整，
     * 而这条"模板加键不能让投影炸"的约定不该被远程影响。
     */
    private final ObjectMapper projector = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Object lock = new Object();

    private volatile Snapshot snapshot;
    private volatile boolean dirty = true;

    public EnvironmentCatalog(EnvironmentStore store) {
        this.store = store;
    }

    // ==================================================================================
    // 快照生命周期
    // ==================================================================================

    /** 下一次读取时重新加载。写路径（含播种）改完数据必须调它。 */
    public void invalidate() {
        dirty = true;
    }

    private Snapshot current() {
        if (!dirty && snapshot != null) {
            return snapshot;
        }
        synchronized (lock) {
            if (!dirty && snapshot != null) {
                return snapshot;
            }
            Snapshot loaded = load();
            if (loaded != null) {
                snapshot = loaded;
            }
            dirty = false;
            return snapshot == null ? EMPTY : snapshot;
        }
    }

    /** @return 新快照；返回 null 表示"保留旧的"。 */
    private Snapshot load() {
        try {
            return fromDatabase();
        } catch (RuntimeException e) {
            if (snapshot != null) {
                log.error("[Env] 环境目录重载失败，继续沿用上一次快照（{} 个环境）：{}",
                        snapshot.environments().size(), e.toString());
                return null;
            }
            throw new IllegalStateException("环境目录不可用：数据库读取失败。"
                    + "环境信息只从数据库加载，请确认数据库连接及 md_environment 等环境表已初始化", e);
        }
    }

    private Snapshot fromDatabase() {
        List<EnvironmentEntity> environments = store.listEnvironments();
        List<EnvGroupEntity> groups = store.listGroups();
        List<EnvGroupKeyEntity> keys = store.listGroupKeys();
        List<EnvGroupInstanceEntity> instances = store.listGroupInstances();
        List<EnvInstanceBindingEntity> bindings = store.listInstanceBindings();
        List<EnvInstanceConfigEntity> configs = store.listInstanceConfigs();

        Map<String, Map<String, Map<String, String>>> instanceConfigs = new LinkedHashMap<>();
        for (EnvInstanceConfigEntity config : configs) {
            instanceConfigs
                    .computeIfAbsent(config.getGroupCode(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(normalizeInstance(config.getInstanceCode()), k -> new LinkedHashMap<>())
                    .put(config.getKeyCode(), config.getConfigValue());
        }

        Map<String, Map<String, Set<String>>> bindingsByEnv = new LinkedHashMap<>();
        for (EnvInstanceBindingEntity binding : bindings) {
            bindingsByEnv
                    .computeIfAbsent(binding.getEnvCode(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(binding.getGroupCode(), k -> new LinkedHashSet<>())
                    .add(normalizeInstance(binding.getInstanceCode()));
        }

        Map<String, EnvData> envData = new LinkedHashMap<>();
        for (EnvironmentEntity meta : environments) {
            envData.put(meta.getEnvCode(), new EnvData(meta, new LinkedHashMap<>()));
        }
        for (Map.Entry<String, EnvData> entry : envData.entrySet()) {
            Map<String, Set<String>> boundGroups = bindingsByEnv.get(entry.getKey());
            if (boundGroups == null) {
                continue;
            }
            for (Map.Entry<String, Set<String>> group : boundGroups.entrySet()) {
                Map<String, Map<String, String>> groupConfigs = instanceConfigs.get(group.getKey());
                if (groupConfigs == null) {
                    continue;
                }
                for (String instanceCode : group.getValue()) {
                    Map<String, String> values = groupConfigs.get(instanceCode);
                    if (values == null || values.isEmpty()) {
                        continue;
                    }
                    entry.getValue().values()
                            .computeIfAbsent(group.getKey(), k -> new LinkedHashMap<>())
                            .put(instanceCode, new LinkedHashMap<>(values));
                }
            }
        }
        log.info("[Env] 环境目录已从库加载：{} 个环境 / {} 个分组 / {} 个实例 / {} 条实例配置 / {} 条绑定",
                envData.size(), groups.size(), instances.size(), configs.size(), bindings.size());
        return new Snapshot(envData, groups, indexKeys(keys), indexInstances(instances),
                instanceConfigs, bindingsByEnv);
    }

    private static Map<String, List<EnvGroupKeyEntity>> indexKeys(List<EnvGroupKeyEntity> keys) {
        Map<String, List<EnvGroupKeyEntity>> byGroup = new LinkedHashMap<>();
        for (EnvGroupKeyEntity key : keys) {
            byGroup.computeIfAbsent(key.getGroupCode(), k -> new ArrayList<>()).add(key);
        }
        return byGroup;
    }

    private static Map<String, List<EnvGroupInstanceEntity>> indexInstances(
            List<EnvGroupInstanceEntity> instances) {
        Map<String, List<EnvGroupInstanceEntity>> byGroup = new LinkedHashMap<>();
        for (EnvGroupInstanceEntity instance : instances) {
            byGroup.computeIfAbsent(instance.getGroupCode(), k -> new ArrayList<>()).add(instance);
        }
        return byGroup;
    }

    private static String normalizeInstance(String instanceCode) {
        return instanceCode == null || instanceCode.isBlank() ? DEFAULT_INSTANCE : instanceCode.trim();
    }

    // ==================================================================================
    // 读：环境
    // ==================================================================================

    /** 全部环境主表行（按快照顺序）。 */
    public List<EnvironmentEntity> listEnvironments() {
        List<EnvironmentEntity> result = new ArrayList<>();
        for (EnvData data : current().environments().values()) {
            result.add(data.meta());
        }
        return result;
    }

    /** 全部环境名。用于错误提示里的"可用环境"与页面的下拉。 */
    public List<String> listNames() {
        return new ArrayList<>(current().environments().keySet());
    }

    /**
     * 按环境名取主表行，取不到就抛 —— 消息里带上全部可用环境名。
     *
     * <p>这条消息的形状是从改造前原样保留下来的（见 {@code EnvironmentRegistry}），
     * 因为工具失败时它是模型唯一能看到的线索：模型据此知道"我该填哪个环境"，
     * 而只回一句"NPE"就什么也教不了它。
     */
    public EnvironmentEntity requireEnvironment(String envCode) {
        if (envCode == null || envCode.isBlank()) {
            throw new IllegalArgumentException("environment 不能为空，可用环境: " + listNames());
        }
        EnvData data = current().environments().get(envCode.trim());
        if (data == null) {
            throw new IllegalArgumentException("环境不存在: " + envCode + "，可用环境: " + listNames());
        }
        return data.meta();
    }

    // ==================================================================================
    // 读：分组与键
    // ==================================================================================

    public List<EnvGroupEntity> listGroups() {
        return current().groups();
    }

    public List<EnvGroupKeyEntity> keysOf(String groupCode) {
        List<EnvGroupKeyEntity> keys = current().keysByGroup().get(groupCode);
        return keys == null ? List.of() : keys;
    }

    // ==================================================================================
    // 读：值
    // ==================================================================================

    /**
     * 某个环境某个分组的默认实例值。
     *
     * <p>解析规则：显式实例优先；未指定时优先 {@code default}；没有 default 但只有一个实例时
     * 自动使用它；有多个实例却既没有 default 也没指定，则抛异常并列出可用实例。
     * 这样旧工具只传 {@code (env, group)} 时仍能工作，又不会在多实例下悄悄选错一套。
     */
    public Map<String, String> groupValues(String envCode, String groupCode) {
        return instanceValues(envCode, groupCode, null);
    }

    /** 某个环境某个分组下指定实例的值；实例不存在时抛异常并列出可用实例。 */
    public Map<String, String> instanceValues(String envCode, String groupCode, String instanceCode) {
        EnvData data = dataOf(envCode);
        String resolved = resolveInstanceCode(data, groupCode, instanceCode, envCode);
        return resolved == null ? Map.of() : data.instance(groupCode, resolved);
    }

    /** 某个实例自身的固定配置值。配置不属于环境，所有绑定它的环境读到的是同一份。 */
    public Map<String, String> instanceConfigValues(String groupCode, String instanceCode) {
        Map<String, Map<String, String>> groupConfigs = current().instanceConfigs().get(groupCode);
        if (groupConfigs == null) {
            return Map.of();
        }
        Map<String, String> values = groupConfigs.get(normalizeInstance(instanceCode));
        return values == null ? Map.of() : values;
    }

    /**
     * 某个分组的实例元数据，按 sort_order、instance_code 排序。
     *
     * <p>实例定义属于分组，所有环境共享，且 {@code md_env_group_instance} 是唯一来源。
     * 配置值里出现但实例表没有声明的编码不会被反向合成为实例 —— 否则手工删掉元数据后，
     * 页面会继续从旧值里“复活”实例，与环境配置下拉框的声明语义不一致。
     */
    public List<EnvGroupInstanceEntity> instancesOf(String groupCode) {
        Snapshot snapshot = current();
        List<EnvGroupInstanceEntity> declared = snapshot.instancesByGroup().get(groupCode);
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        List<EnvGroupInstanceEntity> result = new ArrayList<>(declared);
        result.sort(Comparator
                .comparingInt((EnvGroupInstanceEntity item) ->
                        item.getSortOrder() == null ? 0 : item.getSortOrder())
                .thenComparing(item -> item.getInstanceCode() == null ? "" : item.getInstanceCode()));
        return result;
    }

    /**
     * 把某个分组的值投影成强类型对象（{@code EnvironmentProperties.K8s} 这类）。
     *
     * <p>这是"通用存储 + 强类型出口"两条腿里的后一条：工具代码仍然写
     * {@code environmentRegistry.k8s(env).getUrl()}，IDE 有补全、字段改名能被编译期发现；
     * 而库里存的是通用行。中间那层映射由这里一次性完成，靠的是 key_code 与字段名同名。
     *
     * @throws IllegalArgumentException 该分组下一条值都没有（"未配置"是运维事实，要能一眼看出）
     */
    public <T> T project(String envCode, String groupCode, Class<T> type) {
        T projected = projectOrNull(envCode, groupCode, type);
        if (projected == null) {
            throw new IllegalArgumentException("环境 " + envCode + " 未配置 " + groupCode);
        }
        return projected;
    }

    /** 指定实例的强类型投影；实例不存在 / 未配置时抛异常。 */
    public <T> T project(String envCode, String groupCode, String instanceCode, Class<T> type) {
        T projected = projectOrNull(envCode, groupCode, instanceCode, type);
        if (projected == null) {
            String target = instanceCode == null || instanceCode.isBlank()
                    ? groupCode
                    : groupCode + " 实例 " + instanceCode.trim();
            throw new IllegalArgumentException("环境 " + envCode + " 未配置 " + target);
        }
        return projected;
    }

    /**
     * 同 {@link #project}，但"这一组一条值都没有"时返回 null 而不抛异常。
     *
     * <p>为什么单独开一个方法，而不是让调用方 try/catch IllegalArgumentException：
     * {@code ObjectMapper#convertValue} 在值格式不对时同样抛 IllegalArgumentException，
     * 两者用同一个异常类型。用 catch 区分的话，"类型不匹配"会被误判成"未配置"，
     * 于是真正的配置错误变成静默的 null —— 那是最难查的一类问题。
     *
     * <p>需要允许“某组件未配置”时使用这个语义：null 是合法业务状态，不是错误。
     */
    public <T> T projectOrNull(String envCode, String groupCode, Class<T> type) {
        return projectOrNull(envCode, groupCode, null, type);
    }

    /** 同 {@link #projectOrNull(String, String, Class)}，但可以指定实例。 */
    public <T> T projectOrNull(String envCode, String groupCode, String instanceCode, Class<T> type) {
        Map<String, String> values = instanceValues(envCode, groupCode, instanceCode);
        return values.isEmpty() ? null : projector.convertValue(values, type);
    }

    private EnvData dataOf(String envCode) {
        return envCode == null ? null : current().environments().get(envCode.trim());
    }

    /**
     * 解析实例。返回 null 表示这个分组完全没有实例（等价于“未配置”）。
     * 显式实例不存在、或有多实例但无法确定默认时，抛出带可用清单的异常。
     */
    private String resolveInstanceCode(EnvData data, String groupCode, String requested,
                                       String envCode) {
        if (data == null) {
            return null;
        }
        List<String> available = new ArrayList<>(boundInstanceCodes(envCode, groupCode));
        if (requested != null && !requested.isBlank()) {
            String code = requested.trim();
            if (!available.contains(code)) {
                throw new IllegalArgumentException("环境 " + envCode + " 的分组 " + groupCode
                        + " 未绑定实例 " + code + "，已绑定实例: " + available);
            }
            return code;
        }
        if (available.isEmpty()) {
            return null;
        }
        if (available.contains(DEFAULT_INSTANCE)) {
            return DEFAULT_INSTANCE;
        }
        if (available.size() == 1) {
            return available.get(0);
        }
        throw new IllegalArgumentException("环境 " + envCode + " 的分组 " + groupCode
                + " 绑定了多个实例，请明确指定 instance；已绑定实例: " + available);
    }

    /** 某个环境在某个分组下绑定了哪些实例；没有绑定时返回空列表。 */
    public List<String> boundInstanceCodes(String envCode, String groupCode) {
        Map<String, Set<String>> groups = current().bindingsByEnv().get(envCode);
        if (groups == null) {
            return List.of();
        }
        Set<String> bound = groups.get(groupCode);
        return bound == null ? List.of() : new ArrayList<>(bound);
    }
}
