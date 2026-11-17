package com.md.agentplatform.server.environment;

import java.util.List;

/**
 * 环境目录对外（前端）的视图模型 —— 与 {@code /api/environments}、{@code /api/env-groups} 一一对应。
 *
 * <h2>一、为什么行模型与视图模型分开</h2>
 * 行模型（{@code EnvInstanceConfigEntity}）是"库里的形状"，视图是"页面要的形状"，两者差得很多：
 * 页面要的是<b>按分组聚好的键值 + 每个分组的状态 + 完整度计数</b>，而库里是平铺的行。
 * 把聚合逻辑塞进实体、或者让前端去拼，都会让同一套逻辑出现两份（页面拼一次、以后 CLI/报表再拼一次）。
 *
 * <h2>二、为什么状态是枚举而不是布尔</h2>
 * "已配置 / 未配置"两态不够用：截图上真实存在第三种情况 —— 分组配了一部分，
 * 但必填的那一项（Token / 密码）是空的。这正是最该被看见的一类故障，
 * 压成布尔就只能显示成红的"未配置"，用户还得逐字段找是哪一项缺了。
 */
public final class EnvironmentViews {

    private EnvironmentViews() {
    }

    /** 一个环境里某个分组的健康状态。 */
    public enum GroupStatus {
        /** 必填项齐了。 */
        READY,
        /** 有值，但还缺必填项（{@code missing} 列出缺哪些）。 */
        PARTIAL,
        /** 一个值都没有。 */
        EMPTY
    }

    /** 分组模板（含键声明）。 */
    public record GroupTemplate(String groupCode, String displayName, String icon, String description,
                                boolean builtin, int sortOrder, List<KeyTemplate> keys) {
    }

    /**
     * 键声明。{@code exampleValue} 只是格式示意，不是默认值。
     *
     * <p>{@code reserved} 是<b>只出现在响应里</b>的派生标记，不是可写字段：
     * 它表示该键名被工具代码强引用（{@link EnvironmentSchema#reservedKeys}），
     * 因此改名的后果是"工具静默拿到 null"，而不是"页面报错"。
     * 请求体里带上它会被忽略 —— 这个标记跟着代码走，不由页面决定。
     */
    public record KeyTemplate(String keyCode, String label, String valueType, boolean required, boolean secret,
                              String placeholder, String exampleValue, String description, int sortOrder,
                              boolean reserved) {
    }

    /** 列表里一格的聚合状态（不含量：列表页不需要值，也就不存在"脱敏"问题）。 */
    public record GroupState(String groupCode, String displayName, GroupStatus status, List<String> missing,
                             int instanceCount) {
    }

    /** 环境列表的一行。 */
    public record EnvironmentRow(String envCode, String displayName, String description, boolean enabled,
                                 int sortOrder,
                                 long updatedAtMs, String updatedBy,
                                 List<GroupState> groups, int readyKeys, int requiredKeys) {
    }

    /** 详情里的一个键值。 */
    public record KeyValue(String keyCode, String label, String valueType, boolean required, boolean secret,
                           String value) {
    }

    /** 环境详情里的一个已绑定实例；不携带任何配置值。 */
    public record BoundInstance(String instanceCode, String displayName, boolean enabled, int sortOrder) {
    }

    /** 环境详情里的一个已绑定分组。 */
    public record BoundGroup(String groupCode, String displayName, List<BoundInstance> instances) {
    }

    /** 环境详情 —— 只服务环境主表单与绑定关系回填，配置值走实例详情接口。 */
    public record EnvironmentDetail(String envCode, String displayName, String description, boolean enabled,
                                    int sortOrder,
                                    long createdAtMs, long updatedAtMs, String createdBy, String updatedBy,
                                    List<BoundGroup> groups) {
    }

    /** 分组实例维护区的一行：实例是分组级元数据，不在环境上重复。 */
    public record InstanceRow(String groupCode, String groupDisplayName,
                              String instanceCode, String displayName,
                              boolean enabled, int sortOrder) {
    }

    /** 实例编辑弹窗的数据源：元数据 + 该实例自己的固定键值。 */
    public record InstanceDetail(String groupCode, String groupDisplayName,
                                 String instanceCode, String displayName,
                                 boolean enabled, int sortOrder, List<KeyValue> values) {
    }

    /**
     * 新建 / 更新实例的请求体。实例编码在路径上，配置值也属于实例本身。
     *
     * <p>{@code values == null} 表示不改配置值；空 Map 表示清空配置。页面保存时总是提交完整值。
     */
    public record InstanceSaveRequest(String displayName, Boolean enabled, Integer sortOrder,
                                      java.util.Map<String, String> values) {
    }

    /**
     * 新建 / 保存环境的请求体：主表字段 + 分组 → 实例编码（<b>整体替换绑定关系</b>）。
     * 环境只选择实例，不保存任何配置值；配置值在实例编辑里维护。
     */
    public record SaveRequest(String envCode, String displayName, String description, Boolean enabled,
                             Integer sortOrder,
                             java.util.Map<String, List<String>> bindings) {
    }

    /** 新建 / 保存分组的请求体：分组字段 + 键声明（整体替换）。 */
    public record GroupSaveRequest(String groupCode, String displayName, String icon, String description,
                                   Integer sortOrder, List<KeyTemplate> keys) {
    }
}
