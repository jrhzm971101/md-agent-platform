package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import com.md.agentplatform.server.environment.EnvironmentViews.EnvironmentDetail;
import com.md.agentplatform.server.environment.EnvironmentViews.EnvironmentRow;
import com.md.agentplatform.server.environment.EnvironmentViews.BoundGroup;
import com.md.agentplatform.server.environment.EnvironmentViews.BoundInstance;
import com.md.agentplatform.server.environment.EnvironmentViews.GroupSaveRequest;
import com.md.agentplatform.server.environment.EnvironmentViews.GroupState;
import com.md.agentplatform.server.environment.EnvironmentViews.GroupStatus;
import com.md.agentplatform.server.environment.EnvironmentViews.GroupTemplate;
import com.md.agentplatform.server.environment.EnvironmentViews.InstanceDetail;
import com.md.agentplatform.server.environment.EnvironmentViews.InstanceSaveRequest;
import com.md.agentplatform.server.environment.EnvironmentViews.InstanceRow;
import com.md.agentplatform.server.environment.EnvironmentViews.KeyTemplate;
import com.md.agentplatform.server.environment.EnvironmentViews.KeyValue;
import com.md.agentplatform.server.environment.EnvironmentViews.SaveRequest;
import com.md.agentplatform.server.environment.store.EnvironmentStore;
import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupInstanceEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceBindingEntity;
import com.md.agentplatform.server.environment.store.entity.EnvInstanceConfigEntity;
import com.md.agentplatform.server.environment.store.entity.EnvironmentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 环境目录的管理用例 —— 页面上的每一个动作都落在这里。
 *
 * <h2>一、校验分两档，这是刻意的</h2>
 * <ul>
 *   <li><b>拒绝写入</b>：格式错、指向不存在的东西（未知分组 / 未声明的键）、类型不匹配
 *     （INT 填了 abc）。它们的共同点是"再存一次也一样错"，拦住是帮忙。</li>
 *   <li><b>只记不拦</b>：必填项没填。新建一个环境时凭据往往还拿不到，
 *       如果必填能阻断保存，用户就只能把值先记在别处、回头再补 —— 那正是这个页面要消灭的东西。
 *       所以必填只影响"完整度"这个展示指标（见 {@link #stateOf}），永不阻断保存。</li>
 * </ul>
 *
 * <h2>二、事务为什么标在服务层（与 Store 的约定不同）</h2>
 * 本项目其余地方的写事务边界在 Store 实现方法上。这里必须往上抬一层：
 * "保存环境"是"写主表 + 整体替换配置行"两个 Store 调用，<b>必须同一个事务</b>，
 * 否则中途失败会留下"主表改了、配置还是旧的"这种半截状态。
 * 判断依据是"这个动作跨了几个 Store 方法"，不是"哪一层更好看"。
 *
 * <h2>三、groupCode / keyCode 的字符集为什么收得这么紧</h2>
 * 组的键会被 Jackson 投影到 {@code EnvironmentProperties.K8s} 这类 POJO 的字段上，
 * 所以 keyCode 必须是<b>合法 Java 标识符</b>（字母开头、只含字母数字下划线）。
 * 放任 {@code k8s-token} 这种带连字符的键名进来，表现是"页面上填了值，工具却读不到"，
 * 而且不报任何错 —— Jackson 只是安静地不认识它。这种错误必须在写入时就挡掉。
 * groupCode 同理收窄到路径安全字符集，它是 {@code (env, group, key)} 唯一键的一段。
 */
@Service
public class EnvironmentAdminService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentAdminService.class);

    /** 环境标识 / 分组标识：路径安全字符集。 */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 键名：必须是合法 Java 标识符，理由见类注释第三节。 */
    private static final Pattern KEY_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private static final List<String> VALUE_TYPES = List.of("STRING", "INT", "BOOL");

    private static final int MAX_VALUE = 4096;

    private final EnvironmentStore store;
    private final EnvironmentCatalog catalog;

    public EnvironmentAdminService(EnvironmentStore store, EnvironmentCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    // ==================================================================================
    // 一、环境：读
    // ==================================================================================

    /** 环境列表。每行带"每个分组的状态 + 完整度"，全部由模板与值算出来，没有一处写死。 */
    public List<EnvironmentRow> list() {
        List<EnvGroupEntity> groups = catalog.listGroups();
        Map<String, List<EnvGroupKeyEntity>> keysByGroup = new LinkedHashMap<>();
        for (EnvGroupEntity group : groups) {
            keysByGroup.put(group.getGroupCode(), catalog.keysOf(group.getGroupCode()));
        }

        List<EnvironmentRow> rows = new ArrayList<>();
        for (EnvironmentEntity env : catalog.listEnvironments()) {
            List<GroupState> states = new ArrayList<>();
            int ready = 0;
            int required = 0;
            Set<String> bound = new LinkedHashSet<>();
            for (EnvInstanceBindingEntity binding : store.listInstanceBindings(env.getEnvCode())) {
                bound.add(instanceKey(binding.getGroupCode(), binding.getInstanceCode()));
            }
            for (EnvGroupEntity group : groups) {
                List<EnvGroupKeyEntity> keys = keysByGroup.get(group.getGroupCode());
                List<EnvGroupInstanceEntity> instances = catalog.instancesOf(group.getGroupCode());
                List<GroupState> instanceStates = new ArrayList<>();
                for (EnvGroupInstanceEntity instance : instances) {
                    // 只统计该环境实际绑定的实例：一个实例都没绑的分组不参与完整度分母。
                    if (!bound.contains(instanceKey(group.getGroupCode(), instance.getInstanceCode()))) {
                        continue;
                    }
                    Map<String, String> instanceValues = catalog.instanceValues(env.getEnvCode(),
                            group.getGroupCode(), instance.getInstanceCode());
                    instanceStates.add(stateOf(group, keys, instanceValues, 1));
                    required += countRequired(keys);
                    ready += countFilledRequired(keys, instanceValues);
                }
                states.add(aggregateState(group, keys, instanceStates));
            }
            rows.add(new EnvironmentRow(env.getEnvCode(), env.getDisplayName(), env.getDescription(),
                    Boolean.TRUE.equals(env.getEnabled()),
                    env.getSortOrder() == null ? 0 : env.getSortOrder(),
                    env.getUpdatedAtMs() == null ? 0L : env.getUpdatedAtMs(), env.getUpdatedBy(),
                    states, ready, required));
        }
        return rows;
    }

    /** 环境详情：编辑表单的数据源，含每个分组的全部键值与声明元数据。<b>值为明文</b>（调试期决定）。 */
    public EnvironmentDetail detail(String envCode) {
        EnvironmentEntity env = catalog.requireEnvironment(envCode);
        return detailOf(env);
    }

    /** 引用了该环境的 agent（删除前的保护检查）。 */
    public List<String> referencingAgents(String envCode) {
        return store.findAgentNamesByEnvironment(envCode);
    }

    // ==================================================================================
    // 二、分组实例：独立维护
    // ==================================================================================

    /** 全部分组实例，供页面单独的「分组实例」维护区使用。 */
    public List<InstanceRow> instances() {
        List<InstanceRow> rows = new ArrayList<>();
        for (EnvGroupEntity group : catalog.listGroups()) {
            for (EnvGroupInstanceEntity instance : catalog.instancesOf(group.getGroupCode())) {
                rows.add(new InstanceRow(group.getGroupCode(), group.getDisplayName(),
                        instance.getInstanceCode(),
                        instance.getDisplayName() == null || instance.getDisplayName().isBlank()
                                ? instance.getInstanceCode() : instance.getDisplayName(),
                        Boolean.TRUE.equals(instance.getEnabled()),
                        instance.getSortOrder() == null ? 0 : instance.getSortOrder()));
            }
        }
        return rows;
    }

    /** 实例编辑弹窗的数据源：元数据 + 实例自己的固定配置值。 */
    public InstanceDetail instanceDetail(String groupCode, String instanceCode) {
        String group = normalize(groupCode);
        String code = normalize(instanceCode);
        EnvGroupEntity groupEntity = store.findGroup(group);
        EnvGroupInstanceEntity instance = store.findGroupInstance(group, code);
        if (groupEntity == null || instance == null) {
            return null;
        }
        return new InstanceDetail(group, groupEntity.getDisplayName(), code,
                instance.getDisplayName() == null || instance.getDisplayName().isBlank()
                        ? code : instance.getDisplayName(),
                Boolean.TRUE.equals(instance.getEnabled()),
                instance.getSortOrder() == null ? 0 : instance.getSortOrder(),
                keyValues(catalog.keysOf(group), catalog.instanceConfigValues(group, code)));
    }

    /** @return false = 实例编码已存在（控制器回 409） */
    @Transactional
    public boolean createInstance(String groupCode, String instanceCode,
                                  InstanceSaveRequest body) {
        String group = normalize(groupCode);
        String code = normalize(instanceCode);
        List<String> violations = new ArrayList<>();
        validateInstanceTarget(group, code, violations);
        validateInstanceHeader(body, code, violations);
        Map<String, String> values = validateInstanceValues(group, body == null ? null : body.values(),
                violations);
        reject(violations);

        EnvGroupInstanceEntity row = new EnvGroupInstanceEntity();
        row.setGroupCode(group);
        row.setInstanceCode(code);
        row.setDisplayName(displayNameOf(body, code));
        row.setEnabled(body == null || body.enabled() == null || body.enabled());
        row.setSortOrder(nextInstanceSortOrder(group, body));
        boolean created = store.createGroupInstance(row);
        if (created) {
            store.replaceInstanceConfigs(group, code, toInstanceConfigRows(values));
            catalog.invalidate();
        }
        return created;
    }

    /** @return false = 实例不存在（控制器回 404） */
    @Transactional
    public boolean saveInstance(String groupCode, String instanceCode,
                                InstanceSaveRequest body) {
        String group = normalize(groupCode);
        String code = normalize(instanceCode);
        List<String> violations = new ArrayList<>();
        validateInstanceTarget(group, code, violations);
        reject(violations);

        EnvGroupInstanceEntity existing = store.findGroupInstance(group, code);
        if (existing == null) {
            return false;
        }
        violations.clear();
        validateInstanceHeader(body, code, violations);
        Map<String, String> values = body == null || body.values() == null
                ? null
                : validateInstanceValues(group, body.values(), violations);
        reject(violations);

        existing.setDisplayName(displayNameOf(body, code));
        if (body != null && body.enabled() != null) {
            existing.setEnabled(body.enabled());
        }
        if (body != null && body.sortOrder() != null && body.sortOrder() > 0) {
            existing.setSortOrder(body.sortOrder());
        }
        boolean saved = store.saveGroupInstance(existing);
        if (saved) {
            if (values != null) {
                store.replaceInstanceConfigs(group, code, toInstanceConfigRows(values));
            }
            catalog.invalidate();
        }
        return saved;
    }

    /** @return false = 实例不存在（控制器回 404） */
    @Transactional
    public boolean deleteInstance(String groupCode, String instanceCode) {
        String group = normalize(groupCode);
        String code = normalize(instanceCode);
        if (store.findGroupInstance(group, code) == null) {
            return false;
        }
        store.deleteGroupInstance(group, code);
        catalog.invalidate();
        return true;
    }

    // ==================================================================================
    // 三、环境：写
    // ==================================================================================

    /** @return false = env_code 已存在（控制器回 409） */
    @Transactional
    public boolean create(SaveRequest body, String operator) {
        List<String> violations = new ArrayList<>();
        validateHeader(body, violations, true);
        Map<String, List<String>> bindings = validateBindings(
                body == null ? null : body.bindings(), violations);
        reject(violations);

        EnvironmentEntity entity = toEntity(body);
        entity.setEnabled(body.enabled() == null || body.enabled());
        entity.setSortOrder(body.sortOrder() == null ? nextSortOrder() : body.sortOrder());
        boolean created = store.createEnvironment(entity, operator);
        if (created) {
            store.replaceInstanceBindings(entity.getEnvCode(), toBindingRows(entity.getEnvCode(), bindings));
            catalog.invalidate();
        }
        return created;
    }

    /** @return false = 环境不存在（控制器回 404） */
    @Transactional
    public boolean save(String envCode, SaveRequest body, String operator) {
        List<String> violations = new ArrayList<>();
        validateHeader(body, violations, false);
        Map<String, List<String>> bindings = validateBindings(
                body == null ? null : body.bindings(), violations);
        reject(violations);

        // 路径上的 envCode 是权威，body 里的 envCode 只用于展示（改名 = 删除 + 新建）。
        // 这里查 Store 而不是查 Catalog：缓存有一拍延迟，而"存不存得下去"必须看库里的真相。
        if (store.findEnvironment(envCode) == null) {
            return false;
        }

        EnvironmentEntity entity = toEntity(body);
        entity.setEnvCode(envCode);
        entity.setEnabled(body.enabled() == null || body.enabled());
        EnvironmentEntity existing = catalog.requireEnvironment(envCode);
        entity.setSortOrder(body.sortOrder() == null ? existing.getSortOrder() : body.sortOrder());
        store.saveEnvironment(entity, operator);
        store.replaceInstanceBindings(envCode, toBindingRows(envCode, bindings));
        catalog.invalidate();
        return true;
    }

    @Transactional
    public void delete(String envCode) {
        store.deleteEnvironment(envCode);
        catalog.invalidate();
    }

    // ==================================================================================
    // 三、分组模板：读 / 写
    // ==================================================================================

    public List<GroupTemplate> groups() {
        List<GroupTemplate> result = new ArrayList<>();
        for (EnvGroupEntity group : catalog.listGroups()) {
            result.add(toTemplate(group, catalog.keysOf(group.getGroupCode())));
        }
        return result;
    }

    /** 一次模板提交的结果；{@code removedKeys} 是"顺带被清掉值的键"，必须告诉用户。 */
    public record GroupSaveResult(String groupCode, boolean saved, List<String> removedKeys) {
    }

    /** 页面新建的分组一律 {@code builtin = false}：内置标记描述的是"被工具代码引用"，不是用户可以勾的属性。 */
    @Transactional
    public boolean createGroup(GroupSaveRequest body) {
        List<String> violations = new ArrayList<>();
        validateGroupHeader(body, violations);
        validateKeys(body == null ? null : body.keys(), violations);
        reject(violations);

        EnvGroupEntity group = new EnvGroupEntity();
        group.setGroupCode(body.groupCode().trim());
        group.setDisplayName(body.displayName().trim());
        group.setIcon(blankToNull(body.icon()));
        group.setDescription(blankToNull(body.description()));
        group.setBuiltin(Boolean.FALSE);
        group.setSortOrder(body.sortOrder() == null ? nextGroupSortOrder() : body.sortOrder());

        boolean created = store.createGroup(group);
        if (created) {
            store.replaceGroupKeys(group.getGroupCode(), toKeyRows(group.getGroupCode(), body.keys()));
            catalog.invalidate();
        }
        return created;
    }

    /** @return null = 分组不存在（控制器回 404） */
    @Transactional
    public GroupSaveResult saveGroup(String groupCode, GroupSaveRequest body) {
        EnvGroupEntity existing = store.findGroup(groupCode);
        if (existing == null) {
            return null;
        }
        List<String> violations = new ArrayList<>();
        validateGroupHeader(body, violations);
        validateKeys(body == null ? null : body.keys(), violations);
        validateReservedKeys(groupCode, existing, body, violations);
        reject(violations);

        EnvGroupEntity group = new EnvGroupEntity();
        group.setGroupCode(groupCode);
        group.setDisplayName(body.displayName().trim());
        group.setIcon(blankToNull(body.icon()));
        group.setDescription(blankToNull(body.description()));
        // builtin 不由页面决定：它是"这段键名被工具代码引用"的事实，跟着代码走。
        group.setBuiltin(Boolean.TRUE.equals(existing.getBuiltin()));
        group.setSortOrder(body.sortOrder() == null ? existing.getSortOrder() : body.sortOrder());
        store.saveGroup(group);

        List<String> before = new ArrayList<>();
        for (EnvGroupKeyEntity key : catalog.keysOf(groupCode)) {
            before.add(key.getKeyCode());
        }
        int cleared = store.replaceGroupKeys(groupCode, toKeyRows(groupCode, body.keys()));

        List<String> after = new ArrayList<>();
        for (KeyTemplate key : body.keys() == null ? List.<KeyTemplate>of() : body.keys()) {
            after.add(key.keyCode().trim());
        }
        List<String> removed = new ArrayList<>();
        for (String code : before) {
            if (!after.contains(code)) {
                removed.add(code);
            }
        }
        if (cleared > 0) {
            log.warn("[Env] 分组 {} 的键声明被收窄，连带清掉了 {} 行配置值（被移除的键：{}）",
                    groupCode, cleared, removed);
        }
        catalog.invalidate();
        return new GroupSaveResult(groupCode, true, removed);
    }

    @Transactional
    public void deleteGroup(String groupCode) {
        store.deleteGroup(groupCode);
        catalog.invalidate();
    }

    /** 内置分组不允许删除，理由见 {@code EnvGroupEntity#builtin} 的注释。 */
    public boolean isBuiltin(String groupCode) {
        EnvGroupEntity group = store.findGroup(groupCode);
        return group != null && Boolean.TRUE.equals(group.getBuiltin());
    }

    // ==================================================================================
    // 四、校验
    // ==================================================================================

    private void validateHeader(SaveRequest body, List<String> violations, boolean creating) {
        if (body == null) {
            violations.add("请求体为空");
            return;
        }
        if (creating) {
            String code = body.envCode();
            if (code == null || code.isBlank()) {
                violations.add("env_code 必填");
            } else if (!CODE.matcher(code.trim()).matches()) {
                violations.add("env_code 只允许字母、数字、下划线、连字符，1-64 位：" + code);
            }
        }
        requireText(body.displayName(), 128, "display_name", violations);
        maxLength(body.description(), 512, "description", violations);
    }

    /**
     * 校验并归一化「分组 → 实例编码」。
     *
     * <p>环境只保存绑定关系，不提交任何配置值。空列表会保留下来，但落库时不会产生绑定行，
     * 因此“分组下面一个实例都没选”等价于这个环境不使用该分组。
     */
    private Map<String, List<String>> validateBindings(Map<String, List<String>> bindings,
                                                       List<String> violations) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        if (bindings == null || bindings.isEmpty()) {
            return normalized;
        }
        List<EnvGroupEntity> declaredGroups = catalog.listGroups();
        for (Map.Entry<String, List<String>> entry : bindings.entrySet()) {
            String groupCode = entry.getKey() == null ? "" : entry.getKey().trim();
            EnvGroupEntity group = null;
            for (EnvGroupEntity candidate : declaredGroups) {
                if (candidate.getGroupCode().equals(groupCode)) {
                    group = candidate;
                    break;
                }
            }
            if (group == null) {
                violations.add("分组未在模板中声明：" + groupCode);
                continue;
            }
            Set<String> declaredInstances = new LinkedHashSet<>();
            for (EnvGroupInstanceEntity instance : catalog.instancesOf(groupCode)) {
                declaredInstances.add(instance.getInstanceCode());
            }
            List<String> normalizedInstances = new ArrayList<>();
            Set<String> seenInstances = new LinkedHashSet<>();
            List<String> submitted = entry.getValue() == null ? List.of() : entry.getValue();
            for (String rawInstanceCode : submitted) {
                String instanceCode = rawInstanceCode == null ? "" : rawInstanceCode.trim();
                if (!CODE.matcher(instanceCode).matches()) {
                    violations.add("实例标识只允许字母、数字、下划线、连字符，1-64 位：" + instanceCode);
                    continue;
                }
                if (!seenInstances.add(instanceCode)) {
                    violations.add("分组 " + groupCode + " 下的实例标识重复：" + instanceCode);
                    continue;
                }
                if (!declaredInstances.contains(instanceCode)) {
                    violations.add("分组 " + groupCode + " 下没有实例 " + instanceCode
                            + "（请先在「分组实例」里新增）");
                    continue;
                }
                normalizedInstances.add(instanceCode);
            }
            normalized.put(groupCode, normalizedInstances);
        }
        return normalized;
    }

    /** 校验并归一化一个实例的键值；空值 = 未配置，不落行。 */
    private Map<String, String> validateInstanceValues(String groupCode, Map<String, String> submitted,
                                                       List<String> violations) {
        Map<String, String> values = new LinkedHashMap<>();
        if (submitted == null || submitted.isEmpty()) {
            return values;
        }
        List<EnvGroupKeyEntity> keys = catalog.keysOf(groupCode);
        for (Map.Entry<String, String> kv : submitted.entrySet()) {
            String keyCode = kv.getKey() == null ? "" : kv.getKey().trim();
            EnvGroupKeyEntity declared = null;
            for (EnvGroupKeyEntity candidate : keys) {
                if (candidate.getKeyCode().equals(keyCode)) {
                    declared = candidate;
                    break;
                }
            }
            if (declared == null) {
                violations.add("分组 " + groupCode + " 下没有声明键 " + keyCode
                        + "（模板可能刚被改过，请刷新页面）");
                continue;
            }
            String value = kv.getValue() == null ? "" : kv.getValue().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_VALUE) {
                violations.add(groupCode + "." + keyCode + " 超长（上限 " + MAX_VALUE + " 字符）");
                continue;
            }
            validateValueType(groupCode, declared, value, violations);
            values.put(keyCode, value);
        }
        return values;
    }

    private void validateInstanceTarget(String groupCode, String instanceCode,
                                        List<String> violations) {
        if (groupCode.isEmpty() || store.findGroup(groupCode) == null) {
            violations.add("分组不存在：" + groupCode);
        }
        if (!CODE.matcher(instanceCode).matches()) {
            violations.add("实例标识只允许字母、数字、下划线、连字符，1-64 位：" + instanceCode);
        }
    }

    private static void validateInstanceHeader(InstanceSaveRequest body, String instanceCode,
                                               List<String> violations) {
        if (body != null) {
            maxLength(body.displayName(), 128, instanceCode + ".displayName", violations);
        }
    }

    private static String displayNameOf(InstanceSaveRequest body, String instanceCode) {
        return body == null || body.displayName() == null || body.displayName().isBlank()
                ? instanceCode : body.displayName().trim();
    }

    private int nextInstanceSortOrder(String groupCode, InstanceSaveRequest body) {
        if (body != null && body.sortOrder() != null && body.sortOrder() > 0) {
            return body.sortOrder();
        }
        int max = 0;
        for (EnvGroupInstanceEntity instance : catalog.instancesOf(groupCode)) {
            max = Math.max(max, instance.getSortOrder() == null ? 0 : instance.getSortOrder());
        }
        return max + 10;
    }

    private void validateValueType(String groupCode, EnvGroupKeyEntity declared, String value,
                                   List<String> violations) {
        String type = declared.getValueType() == null ? "STRING" : declared.getValueType();
        if ("INT".equals(type)) {
            try {
                Long.parseLong(value);
            } catch (NumberFormatException e) {
                violations.add(groupCode + "." + declared.getKeyCode() + " 要求整数，实际是：" + value);
            }
        } else if ("BOOL".equals(type)) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                violations.add(groupCode + "." + declared.getKeyCode() + " 要求 true/false，实际是：" + value);
            }
        }
    }

    private void validateGroupHeader(GroupSaveRequest body, List<String> violations) {
        if (body == null) {
            violations.add("请求体为空");
            return;
        }
        String code = body.groupCode();
        if (code == null || code.isBlank()) {
            violations.add("group_code 必填");
        } else if (!CODE.matcher(code.trim()).matches()) {
            violations.add("group_code 只允许字母、数字、下划线、连字符，1-64 位：" + code);
        }
        requireText(body.displayName(), 128, "display_name", violations);
        maxLength(body.icon(), 64, "icon", violations);
        maxLength(body.description(), 512, "description", violations);
    }

    private void validateKeys(List<KeyTemplate> keys, List<String> violations) {
        if (keys == null) {
            return;
        }
        List<String> seen = new ArrayList<>();
        for (KeyTemplate key : keys) {
            if (key == null) {
                violations.add("键声明里有空项");
                continue;
            }
            String code = key.keyCode() == null ? "" : key.keyCode().trim();
            if (!KEY_CODE.matcher(code).matches()) {
                violations.add("key_code 必须是合法标识符（字母开头，只含字母数字下划线，≤64 位）：" + code);
            } else if (seen.contains(code)) {
                violations.add("key_code 重复：" + code);
            } else {
                seen.add(code);
            }
            requireText(key.label(), 128, code + ".label", violations);
            String type = key.valueType() == null ? "STRING" : key.valueType().trim().toUpperCase();
            if (!VALUE_TYPES.contains(type)) {
                violations.add(code + ".value_type 只允许 " + VALUE_TYPES);
            }
            maxLength(key.placeholder(), 255, code + ".placeholder", violations);
            maxLength(key.exampleValue(), 255, code + ".example_value", violations);
            maxLength(key.description(), 512, code + ".description", violations);
        }
    }

    /**
     * 内置分组里被工具代码强引用的键，<b>不允许改名，也不允许删除</b>。
     *
     * <h2>为什么这条必须拦，而不能只在前端标个红</h2>
     * 把 k8s 的 {@code url} 改名成 {@code clusterUrl}，页面会保存成功、列表依旧全绿，
     * 但工具里那句 {@code environmentRegistry.k8s(env).getUrl()} 从此返回 {@code null}
     * （投影走 Jackson，不认识的键被忽略，POJO 字段保持 null）。
     * 这是一个"改动在 A 处、故障在 B 处、中间隔着一整个工具调用"的故障，
     * 唯一划算的做法是在写入侧挡住。
     *
     * <p>拦的依据来自 {@link EnvironmentSchema}（反射 POJO 得到），不是另一份人工名单 ——
     * 后者会漂移，于是"名单外的字段被改了却没人拦"。
     *
     * <p><b>加字段不受影响</b>：给 k8s 加一个 {@code clusterName} 完全合法，
     * 因为没有任何代码在按名字读它。
     */
    private static void validateReservedKeys(String groupCode, EnvGroupEntity existing,
                                             GroupSaveRequest body, List<String> violations) {
        if (!Boolean.TRUE.equals(existing.getBuiltin())) {
            return;
        }
        Set<String> reserved = EnvironmentSchema.reservedKeys(groupCode);
        if (reserved.isEmpty()) {
            return;
        }
        Set<String> submitted = new LinkedHashSet<>();
        for (KeyTemplate key : body == null || body.keys() == null ? List.<KeyTemplate>of() : body.keys()) {
            if (key != null && key.keyCode() != null) {
                submitted.add(key.keyCode().trim());
            }
        }
        for (String code : reserved) {
            if (!submitted.contains(code)) {
                violations.add("字段 " + code + " 被工具代码强引用（EnvironmentRegistry 对该分组的强类型投影），"
                        + "不能改名或删除；要改字段名必须同步改 EnvironmentProperties 里对应 POJO 的字段名");
            }
        }
    }

    private static void requireText(String value, int max, String field, List<String> violations) {
        if (value == null || value.isBlank()) {
            violations.add(field + " 必填");
        } else if (value.length() > max) {
            violations.add(field + " 超长（上限 " + max + " 字符）");
        }
    }

    private static void maxLength(String value, int max, String field, List<String> violations) {
        if (value != null && value.length() > max) {
            violations.add(field + " 超长（上限 " + max + " 字符）");
        }
    }

    private static void reject(List<String> violations) {
        if (!violations.isEmpty()) {
            throw new ControlPlaneValidationException(violations);
        }
    }

    // ==================================================================================
    // 五、状态与完整度
    // ==================================================================================

    /**
     * 一个分组的健康状态。
     *
     * <p>只统计<b>模板声明过</b>的键：库里可能残留指向已删键的旧行，把它们算进来会让
     * "配了 4 个值"看起来比模板还全。三类状态见 {@code EnvironmentViews.GroupStatus}。
     */
    private static GroupState stateOf(EnvGroupEntity group, List<EnvGroupKeyEntity> keys,
                                      Map<String, String> values, int instanceCount) {
        List<String> missing = new ArrayList<>();
        boolean anyValue = false;
        for (EnvGroupKeyEntity key : keys) {
            String value = values.get(key.getKeyCode());
            boolean filled = value != null && !value.isBlank();
            anyValue = anyValue || filled;
            if (!filled && Boolean.TRUE.equals(key.getRequired())) {
                missing.add(key.getLabel());
            }
        }
        GroupStatus status = !anyValue ? GroupStatus.EMPTY
                : missing.isEmpty() ? GroupStatus.READY
                : GroupStatus.PARTIAL;
        return new GroupState(group.getGroupCode(), group.getDisplayName(), status, missing, instanceCount);
    }

    /**
     * 把一个分组下所有实例的状态汇总成列表页的一格。
     *
     * <p>只要有一个实例缺必填项，整组就是 PARTIAL；一个实例都没配才是 EMPTY。
     * 这样列表不会因为“default 是满的”而掩盖第二套实例还空着。
     */
    private static GroupState aggregateState(EnvGroupEntity group, List<EnvGroupKeyEntity> keys,
                                             List<GroupState> instanceStates) {
        if (instanceStates.isEmpty()) {
            return stateOf(group, keys, Map.of(), 0);
        }
        boolean anyValue = false;
        Set<String> missing = new LinkedHashSet<>();
        for (GroupState state : instanceStates) {
            anyValue = anyValue || state.status() != GroupStatus.EMPTY;
            missing.addAll(state.missing());
        }
        GroupStatus status = !anyValue ? GroupStatus.EMPTY
                : missing.isEmpty() ? GroupStatus.READY
                : GroupStatus.PARTIAL;
        return new GroupState(group.getGroupCode(), group.getDisplayName(), status,
                new ArrayList<>(missing), instanceStates.size());
    }

    private static int countRequired(List<EnvGroupKeyEntity> keys) {
        int count = 0;
        for (EnvGroupKeyEntity key : keys) {
            if (Boolean.TRUE.equals(key.getRequired())) {
                count++;
            }
        }
        return count;
    }

    private static int countFilledRequired(List<EnvGroupKeyEntity> keys, Map<String, String> values) {
        int count = 0;
        for (EnvGroupKeyEntity key : keys) {
            if (!Boolean.TRUE.equals(key.getRequired())) {
                continue;
            }
            String value = values.get(key.getKeyCode());
            if (value != null && !value.isBlank()) {
                count++;
            }
        }
        return count;
    }

    // ==================================================================================
    // 六、视图组装
    // ==================================================================================

    private EnvironmentDetail detailOf(EnvironmentEntity env) {
        List<BoundGroup> groups = new ArrayList<>();
        Set<String> bound = new LinkedHashSet<>();
        for (EnvInstanceBindingEntity binding : store.listInstanceBindings(env.getEnvCode())) {
            bound.add(instanceKey(binding.getGroupCode(), binding.getInstanceCode()));
        }
        for (EnvGroupEntity group : catalog.listGroups()) {
            List<BoundInstance> instances = new ArrayList<>();
            List<EnvGroupInstanceEntity> instanceEntities = catalog.instancesOf(group.getGroupCode());
            for (EnvGroupInstanceEntity instance : instanceEntities) {
                if (!bound.contains(instanceKey(group.getGroupCode(), instance.getInstanceCode()))) {
                    continue;
                }
                instances.add(new BoundInstance(instance.getInstanceCode(),
                        instance.getDisplayName() == null ? instance.getInstanceCode() : instance.getDisplayName(),
                        Boolean.TRUE.equals(instance.getEnabled()),
                        instance.getSortOrder() == null ? 0 : instance.getSortOrder()));
            }
            if (!instances.isEmpty()) {
                groups.add(new BoundGroup(group.getGroupCode(), group.getDisplayName(), instances));
            }
        }
        return new EnvironmentDetail(env.getEnvCode(), env.getDisplayName(), env.getDescription(),
                Boolean.TRUE.equals(env.getEnabled()),
                env.getSortOrder() == null ? 0 : env.getSortOrder(),
                env.getCreatedAtMs() == null ? 0L : env.getCreatedAtMs(),
                env.getUpdatedAtMs() == null ? 0L : env.getUpdatedAtMs(),
                env.getCreatedBy(), env.getUpdatedBy(), groups);
    }

    private static List<KeyValue> keyValues(List<EnvGroupKeyEntity> keys, Map<String, String> values) {
        List<KeyValue> rows = new ArrayList<>();
        for (EnvGroupKeyEntity key : keys) {
            rows.add(new KeyValue(key.getKeyCode(), key.getLabel(), key.getValueType(),
                    Boolean.TRUE.equals(key.getRequired()), Boolean.TRUE.equals(key.getSecret()),
                    values.get(key.getKeyCode())));
        }
        return rows;
    }

    private static GroupTemplate toTemplate(EnvGroupEntity group, List<EnvGroupKeyEntity> keys) {
        List<KeyTemplate> rows = new ArrayList<>();
        /*
         * reserved 只在分组是内置的、且键名确实出现在对应 POJO 里时才为真。
         * 注意不能简单写成"内置分组的键全是 reserved"：内置分组也允许页面自己加字段
         * （比如给 k8s 加一个 clusterName），那种字段没有代码引用它，应当可以随便改、随便删。
         */
        boolean builtin = Boolean.TRUE.equals(group.getBuiltin());
        Set<String> reserved = builtin ? EnvironmentSchema.reservedKeys(group.getGroupCode()) : Set.of();
        for (EnvGroupKeyEntity key : keys) {
            rows.add(new KeyTemplate(key.getKeyCode(), key.getLabel(), key.getValueType(),
                    Boolean.TRUE.equals(key.getRequired()), Boolean.TRUE.equals(key.getSecret()),
                    key.getPlaceholder(), key.getExampleValue(), key.getDescription(),
                    key.getSortOrder() == null ? 0 : key.getSortOrder(),
                    reserved.contains(key.getKeyCode())));
        }
        return new GroupTemplate(group.getGroupCode(), group.getDisplayName(), group.getIcon(),
                group.getDescription(), Boolean.TRUE.equals(group.getBuiltin()),
                group.getSortOrder() == null ? 0 : group.getSortOrder(), rows);
    }

    private static EnvironmentEntity toEntity(SaveRequest body) {
        EnvironmentEntity entity = new EnvironmentEntity();
        entity.setEnvCode(body.envCode() == null ? null : body.envCode().trim());
        entity.setDisplayName(body.displayName() == null ? null : body.displayName().trim());
        // 可空文本一律归一化成空串：MyBatis-Plus 的 updateById 会忽略 null 字段，
        // 传 null 的后果是"页面清空了内容、库里还是旧值"。空串能写进去，语义也清楚。
        entity.setDescription(normalize(body.description()));
        return entity;
    }

    private static List<EnvInstanceConfigEntity> toInstanceConfigRows(Map<String, String> values) {
        List<EnvInstanceConfigEntity> rows = new ArrayList<>();
        for (Map.Entry<String, String> kv : values.entrySet()) {
            EnvInstanceConfigEntity row = new EnvInstanceConfigEntity();
            row.setKeyCode(kv.getKey());
            row.setConfigValue(kv.getValue());
            rows.add(row);
        }
        return rows;
    }

    private static List<EnvInstanceBindingEntity> toBindingRows(
            String envCode, Map<String, List<String>> groups) {
        List<EnvInstanceBindingEntity> rows = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            for (String instanceCode : group.getValue()) {
                EnvInstanceBindingEntity row = new EnvInstanceBindingEntity();
                row.setEnvCode(envCode);
                row.setGroupCode(group.getKey());
                row.setInstanceCode(instanceCode);
                rows.add(row);
            }
        }
        return rows;
    }

    private static String instanceKey(String groupCode, String instanceCode) {
        return groupCode + "\u0000" + instanceCode;
    }

    private static List<EnvGroupKeyEntity> toKeyRows(String groupCode, List<KeyTemplate> keys) {
        List<EnvGroupKeyEntity> rows = new ArrayList<>();
        if (keys == null) {
            return rows;
        }
        int sort = 0;
        for (KeyTemplate key : keys) {
            EnvGroupKeyEntity row = new EnvGroupKeyEntity();
            row.setGroupCode(groupCode);
            row.setKeyCode(key.keyCode().trim());
            row.setLabel(key.label().trim());
            row.setValueType(key.valueType() == null ? "STRING" : key.valueType().trim().toUpperCase());
            row.setRequired(Boolean.TRUE.equals(key.required()));
            row.setSecret(Boolean.TRUE.equals(key.secret()));
            row.setPlaceholder(blankToNull(key.placeholder()));
            row.setExampleValue(blankToNull(key.exampleValue()));
            row.setDescription(blankToNull(key.description()));
            // 0 表示"页面没指定顺序"，按提交顺序自动排；非 0 则尊重页面上的值。
            row.setSortOrder(key.sortOrder() > 0 ? key.sortOrder() : (sort += 10));
            rows.add(row);
        }
        return rows;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int nextSortOrder() {
        int max = 0;
        for (EnvironmentEntity env : catalog.listEnvironments()) {
            max = Math.max(max, env.getSortOrder() == null ? 0 : env.getSortOrder());
        }
        return max + 10;
    }

    private int nextGroupSortOrder() {
        int max = 0;
        for (EnvGroupEntity group : catalog.listGroups()) {
            max = Math.max(max, group.getSortOrder() == null ? 0 : group.getSortOrder());
        }
        return max + 10;
    }

}
