package com.md.agentplatform.server.tool.container;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 计算组件结果的通用格式化 —— 供列表查询与单组件详情两个工具复用，
 * 按组件类型输出名称、镜像、副本数/状态、条件、资源规格等关键信息。
 */
final class ComputeComponentFormatter {

    private ComputeComponentFormatter() {
    }

    /** 格式化单个计算组件（以 "---" 分隔块开头） */
    static String formatItem(JsonNode item, ComputeComponent component) {
        StringBuilder sb = new StringBuilder();
        appendItem(sb, item, component);
        return sb.toString();
    }

    private static void appendItem(StringBuilder sb, JsonNode item, ComputeComponent component) {
        JsonNode meta = item.path("metadata");
        JsonNode specNode = item.path("spec");
        JsonNode status = item.path("status");

        String name = meta.path("name").asText("");
        String namespace = meta.path("namespace").asText("");
        String createdAt = meta.path("creationTimestamp").asText("");

        sb.append("\n---\n");
        sb.append("名称: ").append(name).append("\n");
        sb.append("类型: ").append(component.displayName).append("\n");
        sb.append("命名空间: ").append(namespace).append("\n");

        // 镜像明细（兼容 Pod 直接挂载容器与 workload/Job/CronJob 模板容器）
        appendImages(sb, specNode, status, component);

        appendStatus(sb, component, specNode, status);

        if (!createdAt.isBlank()) {
            sb.append("创建时间: ").append(createdAt).append("\n");
        }
    }

    private static void appendStatus(StringBuilder sb, ComputeComponent component, JsonNode specNode, JsonNode status) {
        switch (component) {
            case POD -> {
                String phase = status.path("phase").asText("");
                sb.append("状态: ").append(blankAs(phase, "Unknown")).append("\n");

                JsonNode containerStatuses = status.path("containerStatuses");
                if (containerStatuses.isArray() && !containerStatuses.isEmpty()) {
                    int ready = 0;
                    for (JsonNode cs : containerStatuses) {
                        if (cs.path("ready").asBoolean(false)) {
                            ready++;
                        }
                    }
                    sb.append("容器: 就绪 ").append(ready).append(" / ").append(containerStatuses.size()).append("\n");
                }

                String node = specNode.path("nodeName").asText("");
                if (!node.isBlank()) {
                    sb.append("节点: ").append(node).append("\n");
                }
            }
            case JOB -> {
                int completions = specNode.path("completions").asInt(0);
                int succeeded = status.path("succeeded").asInt(0);
                int active = status.path("active").asInt(0);
                int failed = status.path("failed").asInt(0);
                sb.append("完成: ").append(succeeded).append("/").append(completions > 0 ? completions : "-")
                        .append("，活跃 ").append(active).append("，失败 ").append(failed).append("\n");
                appendConditions(sb, status);
            }
            case CRONJOB -> {
                String schedule = specNode.path("schedule").asText("");
                JsonNode activeJobs = status.path("active");
                int active = activeJobs.isArray() ? activeJobs.size() : 0;
                String lastSchedule = status.path("lastScheduleTime").asText("");
                if (!schedule.isBlank()) {
                    sb.append("调度: ").append(schedule).append("\n");
                }
                sb.append("活跃 Job: ").append(active).append("\n");
                if (!lastSchedule.isBlank()) {
                    sb.append("上次调度: ").append(lastSchedule).append("\n");
                }
            }
            default -> {
                // Deployment / StatefulSet / DaemonSet / ReplicaSet / ReplicationController
                int desired = specNode.path("replicas").asInt(0);
                int ready = status.path("readyReplicas").asInt(0);
                int available = status.path("availableReplicas").asInt(0);
                int updated = status.path("updatedReplicas").asInt(0);
                int unavailable = status.path("unavailableReplicas").asInt(0);

                boolean healthy = desired > 0 && ready >= desired;

                sb.append("副本: 期望 ").append(desired)
                        .append(" / 就绪 ").append(ready)
                        .append(" / 可用 ").append(available)
                        .append(" / 已更新 ").append(updated)
                        .append(" / 不可用 ").append(unavailable).append("\n");
                sb.append("健康: ").append(healthy ? "✅ 正常" : "⚠️ 未就绪").append("\n");
                appendConditions(sb, status);

                // 资源规格（取首个容器）
                JsonNode containers = specNode.path("template").path("spec").path("containers");
                if (containers.isArray() && !containers.isEmpty()) {
                    JsonNode resources = containers.get(0).path("resources");
                    String cpuReq = resources.path("requests").path("cpu").asText("");
                    String memReq = resources.path("requests").path("memory").asText("");
                    String cpuLim = resources.path("limits").path("cpu").asText("");
                    String memLim = resources.path("limits").path("memory").asText("");
                    if (!cpuReq.isBlank() || !memReq.isBlank() || !cpuLim.isBlank() || !memLim.isBlank()) {
                        sb.append("资源: cpu ").append(blankAs(cpuReq, "-")).append("→").append(blankAs(cpuLim, "-"))
                                .append(", 内存 ").append(blankAs(memReq, "-")).append("→").append(blankAs(memLim, "-"))
                                .append("\n");
                    }
                }
            }
        }
    }

    /** 状态条件（Available / Progressing / Ready 等） */
    private static void appendConditions(StringBuilder sb, JsonNode status) {
        JsonNode conditions = status.path("conditions");
        if (conditions.isArray() && !conditions.isEmpty()) {
            sb.append("状态:\n");
            for (JsonNode cond : conditions) {
                String type = cond.path("type").asText("");
                String condStatus = cond.path("status").asText("");
                String reason = cond.path("reason").asText("");
                String message = cond.path("message").asText("");
                sb.append("  - ").append(type).append("=").append(condStatus);
                if (!reason.isBlank()) {
                    sb.append(" (").append(reason).append(")");
                }
                if (!message.isBlank()) {
                    sb.append(": ").append(message);
                }
                sb.append("\n");
            }
        }
    }

    /**
     * 输出容器级镜像明细。
     *
     * <p>为什么不是一行逗号拼接：同一个 Pod / Deployment 可能有多个容器，排查部署版本时
     * 需要知道“哪个容器用了哪个镜像”。镜像 tag 往往就是流水线号，容器名是把它映射回
     * 服务的必要信息；initContainer 也要单独标出，避免把初始化镜像误当成业务镜像。
     */
    private static void appendImages(StringBuilder sb, JsonNode specNode, JsonNode status,
                                     ComputeComponent component) {
        JsonNode podSpec = podSpec(specNode, component);
        List<JsonNode> containers = arrayItems(podSpec.path("containers"));
        List<JsonNode> initContainers = arrayItems(podSpec.path("initContainers"));

        sb.append("镜像:\n");
        if (containers.isEmpty() && initContainers.isEmpty()) {
            sb.append("  - -\n");
        } else {
            for (JsonNode c : containers) {
                appendImageLine(sb, c, false);
            }
            for (JsonNode c : initContainers) {
                appendImageLine(sb, c, true);
            }
        }

        // workload 的 spec 是期望镜像；Pod 的 status 才是节点上真正拉起的镜像与 digest
        if (component == ComputeComponent.POD) {
            appendRunningImages(sb, status);
        }
    }

    private static void appendImageLine(StringBuilder sb, JsonNode container, boolean init) {
        String name = container.path("name").asText("");
        String image = container.path("image").asText("");
        sb.append("  - ").append(blankAs(name, "-")).append(": ").append(blankAs(image, "-"));
        if (init) {
            sb.append(" (init)");
        }
        sb.append("\n");
    }

    /** Pod 实际运行镜像：便于确认真实部署版本，imageID 里通常带镜像 digest。 */
    private static void appendRunningImages(StringBuilder sb, JsonNode status) {
        List<String> lines = new ArrayList<>();
        List<JsonNode> running = new ArrayList<>();
        running.addAll(arrayItems(status.path("initContainerStatuses")));
        running.addAll(arrayItems(status.path("containerStatuses")));

        for (JsonNode cs : running) {
            String name = cs.path("name").asText("");
            String image = cs.path("image").asText("");
            String imageID = cs.path("imageID").asText("");
            if (image.isBlank() && imageID.isBlank()) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append("  - ").append(blankAs(name, "-")).append(": ").append(blankAs(image, "-"));
            if (!imageID.isBlank()) {
                line.append(" [imageID: ").append(imageID).append("]");
            }
            lines.add(line.toString());
        }

        if (!lines.isEmpty()) {
            sb.append("运行镜像:\n");
            for (String line : lines) {
                sb.append(line).append("\n");
            }
        }
    }

    /** workload / Job 取 template.spec，CronJob 取 jobTemplate.spec.template.spec，Pod 直接用 spec。 */
    private static JsonNode podSpec(JsonNode specNode, ComputeComponent component) {
        JsonNode podSpec = switch (component) {
            case POD -> specNode;
            case CRONJOB -> specNode.path("jobTemplate").path("spec").path("template").path("spec");
            default -> specNode.path("template").path("spec");
        };
        return podSpec.isObject() ? podSpec : specNode;
    }

    private static List<JsonNode> arrayItems(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(items::add);
        }
        return items;
    }

    private static String blankAs(String value, String placeholder) {
        return (value == null || value.isBlank()) ? placeholder : value;
    }
}