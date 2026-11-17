package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.config.EnvironmentProperties;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「哪些键被工具代码强引用」的唯一真相 —— 从 {@link EnvironmentProperties} 的 POJO <b>反射</b>出来。
 *
 * <h2>一、这个东西解决什么</h2>
 * 分组模板现在页面可维护，于是出现了一个新的可能：把内置分组 {@code k8s} 的 {@code url}
 * 改名成 {@code clusterUrl}。页面会愉快地保存成功，但工具里那句
 * {@code environmentRegistry.k8s(env).getUrl()} 从此<b>静默返回 null</b> ——
 * 失败点离"我改了个字段名"非常远，这是最难排查的一类故障。
 *
 * <p>所以要在写入侧挡住。挡的依据必须来自<b>真正读这些值的代码</b>，
 * 而不是另一份人工维护的名单 —— 后者迟早会与 POJO 漂移，
 * 于是"名单外的字段被改了却没人拦"，问题只是换了个地方发生。
 *
 * <h2>二、为什么反射 {@code EnvironmentInfo} 而不是硬编码一张表</h2>
 * {@code EnvironmentInfo} 上的每一个字段都恰好是一个分组：
 * 字段名 = {@code group_code}（{@code nacos} / {@code k8s} / {@code rabbitmq} …），
 * 字段类型 = 该分组的强类型投影目标（{@link EnvironmentProperties.K8s} …）。
 * 这份对应关系已经存在于代码里，反射只是把它读出来，因此<b>不可能漂移</b>：
 * 加一个 {@code private Redis redis;} 字段，{@code redis} 分组就自动变成"有强引用"的分组。
 *
 * <p>为什么不去反射 {@code EnvironmentRegistry} 的方法名：方法名（{@code rabbit()}）
 * 与 group_code（{@code rabbitmq}）并不同名，靠方法名反推反而要额外维护一张对照表。
 *
 * <h2>三、reserved 只对内置分组生效</h2>
 * 页面新建的业务分组没有 POJO，也就无所谓"被强引用"——它的字段可以随便增删。
 * 本类只回答"这个名字是否被代码引用"，"这个分组能不能删"由 {@code md_env_group.builtin} 回答，
 * 两者的判定来源不同，不要合并。
 */
public final class EnvironmentSchema {

    private EnvironmentSchema() {
    }

    /** group_code → 强类型投影目标 POJO。顺序沿用 {@code EnvironmentInfo} 的声明顺序。 */
    private static final Map<String, Class<?>> POJOS = scan();

    /** 分组 → 被强引用字段名。分组不存在于 POJO 里时为<b>空集</b>（页面自建分组走这条）。 */
    private static final Map<String, Set<String>> RESERVED = scanKeys();

    /**
     * 该分组里"改了就会让工具静默拿到 null"的键名。
     *
     * @param groupCode 分组标识
     * @return 不可改名 / 不可删除的键名；页面自建分组返回空集
     */
    public static Set<String> reservedKeys(String groupCode) {
        Set<String> keys = groupCode == null ? null : RESERVED.get(groupCode);
        return keys == null ? Collections.emptySet() : keys;
    }

    /** 该分组是否有强类型投影（即：是否内置分组）。 */
    public static boolean hasPojo(String groupCode) {
        return groupCode != null && POJOS.containsKey(groupCode);
    }

    /** 有强类型投影的全部分组标识，按 POJO 声明顺序。用于自检与文档。 */
    public static Set<String> groups() {
        return POJOS.keySet();
    }

    // ==================================================================================
    // 反射
    // ==================================================================================

    private static Map<String, Class<?>> scan() {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (Field field : EnvironmentProperties.EnvironmentInfo.class.getDeclaredFields()) {
            if (skip(field)) {
                continue;
            }
            result.put(field.getName(), field.getType());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Set<String>> scanKeys() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Class<?>> entry : POJOS.entrySet()) {
            Set<String> names = new LinkedHashSet<>();
            for (Field field : entry.getValue().getDeclaredFields()) {
                if (!skip(field)) {
                    names.add(field.getName());
                }
            }
            result.put(entry.getKey(), Collections.unmodifiableSet(names));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 跳过编译器产物（合成字段）与静态字段：它们不是配置项。 */
    private static boolean skip(Field field) {
        return field.isSynthetic() || Modifier.isStatic(field.getModifiers());
    }

    /** 仅用于启动自检/排查：把"有强引用的分组与其字段"打成一行。 */
    public static List<String> describe() {
        return POJOS.keySet().stream()
                .map(code -> code + "[" + String.join(",", RESERVED.get(code)) + "]")
                .toList();
    }
}
