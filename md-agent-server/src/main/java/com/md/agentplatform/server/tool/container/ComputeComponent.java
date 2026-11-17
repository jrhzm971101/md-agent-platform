package com.md.agentplatform.server.tool.container;

import java.util.ArrayList;
import java.util.List;

/**
 * 支持的计算组件全量枚举 —— 组件名称 / K8s API 相对路径模板 / 展示名全部写死。
 *
 * <p>供列表查询（{@code get-compute-component-list}）与单组件详情（{@code get-component-detail}）两个工具共用。
 */
enum ComputeComponent {

    DEPLOYMENT("deployment", "/apis/apps/v1/namespaces/%s/deployments", "Deployment"),
    STATEFULSET("statefulset", "/apis/apps/v1/namespaces/%s/statefulsets", "StatefulSet"),
    DAEMONSET("daemonset", "/apis/apps/v1/namespaces/%s/daemonsets", "DaemonSet"),
    REPLICASET("replicaset", "/apis/apps/v1/namespaces/%s/replicasets", "ReplicaSet"),
    POD("pod", "/apis/v1/namespaces/%s/pods", "Pod"),
    JOB("job", "/apis/batch/v1/namespaces/%s/jobs", "Job"),
    CRONJOB("cronjob", "/apis/batch/v1/namespaces/%s/cronjobs", "CronJob"),
    REPLICATION_CONTROLLER("replicationcontroller", "/api/v1/namespaces/%s/replicationcontrollers", "ReplicationController");

    final String value;
    final String pathTemplate;
    final String displayName;

    ComputeComponent(String value, String pathTemplate, String displayName) {
        this.value = value;
        this.pathTemplate = pathTemplate;
        this.displayName = displayName;
    }

    static ComputeComponent fromValue(String value) {
        for (ComputeComponent c : values()) {
            if (c.value.equals(value)) {
                return c;
            }
        }
        return null;
    }

    /** 全量枚举的计算组件名称，用于错误提示 */
    static String supportedValues() {
        List<String> list = new ArrayList<>();
        for (ComputeComponent c : ComputeComponent.values()) {
            list.add(c.value);
        }
        return String.join(" / ", list);
    }
}