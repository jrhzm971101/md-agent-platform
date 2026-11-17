package com.md.agentplatform.server.tool.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计算组件镜像明细格式化测试。
 *
 * <p>覆盖四种真实 K8s 结构：Deployment 多容器 + initContainer、Pod 运行态镜像、
 * CronJob 嵌套模板，以及没有容器的兜底输出。
 */
class ComputeComponentFormatterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void formatsDeploymentContainersAndInitContainersWithNames() throws Exception {
        JsonNode item = objectMapper.readTree("""
                {
                  "metadata": {"name": "lms-backend", "namespace": "cmr-uat"},
                  "spec": {
                    "replicas": 2,
                    "template": {
                      "spec": {
                        "initContainers": [
                          {"name": "migration", "image": "busybox:1.36"}
                        ],
                        "containers": [
                          {"name": "api", "image": "harbor.mindigitaltech.com/mtn/lms:109478"},
                          {"name": "agent", "image": "harbor.mindigitaltech.com/mtn/agent:109478"}
                        ]
                      }
                    }
                  },
                  "status": {"readyReplicas": 2, "availableReplicas": 2, "updatedReplicas": 2}
                }
                """);

        String text = ComputeComponentFormatter.formatItem(item, ComputeComponent.DEPLOYMENT);

        assertTrue(text.contains("镜像:\n"));
        assertTrue(text.contains("  - api: harbor.mindigitaltech.com/mtn/lms:109478"));
        assertTrue(text.contains("  - agent: harbor.mindigitaltech.com/mtn/agent:109478"));
        assertTrue(text.contains("  - migration: busybox:1.36 (init)"));
    }

    @Test
    void formatsPodSpecAndRunningImageWithImageId() throws Exception {
        JsonNode item = objectMapper.readTree("""
                {
                  "metadata": {"name": "lms-backend-7d9f", "namespace": "cmr-uat"},
                  "spec": {
                    "nodeName": "node-1",
                    "containers": [{"name": "api", "image": "harbor.mindigitaltech.com/mtn/lms:109478"}]
                  },
                  "status": {
                    "phase": "Running",
                    "containerStatuses": [
                      {
                        "name": "api",
                        "ready": true,
                        "image": "harbor.mindigitaltech.com/mtn/lms:109478",
                        "imageID": "docker-pullable://harbor.mindigitaltech.com/mtn/lms@sha256:abc123"
                      }
                    ]
                  }
                }
                """);

        String text = ComputeComponentFormatter.formatItem(item, ComputeComponent.POD);

        assertTrue(text.contains("  - api: harbor.mindigitaltech.com/mtn/lms:109478"));
        assertTrue(text.contains("运行镜像:\n"));
        assertTrue(text.contains("[imageID: docker-pullable://harbor.mindigitaltech.com/mtn/lms@sha256:abc123]"));
    }

    @Test
    void formatsCronJobImageFromJobTemplate() throws Exception {
        JsonNode item = objectMapper.readTree("""
                {
                  "metadata": {"name": "daily-report", "namespace": "cmr-uat"},
                  "spec": {
                    "schedule": "0 1 * * *",
                    "jobTemplate": {
                      "spec": {
                        "template": {
                          "spec": {
                            "containers": [{"name": "report", "image": "mtn/report:109478"}]
                          }
                        }
                      }
                    }
                  },
                  "status": {}
                }
                """);

        String text = ComputeComponentFormatter.formatItem(item, ComputeComponent.CRONJOB);

        assertTrue(text.contains("  - report: mtn/report:109478"));
        assertTrue(text.contains("调度: 0 1 * * *"));
    }

    @Test
    void keepsPlaceholderWhenNoContainerFound() throws Exception {
        JsonNode item = objectMapper.readTree("""
                {
                  "metadata": {"name": "empty", "namespace": "cmr-uat"},
                  "spec": {},
                  "status": {}
                }
                """);

        String text = ComputeComponentFormatter.formatItem(item, ComputeComponent.DEPLOYMENT);

        assertTrue(text.contains("镜像:\n  - -\n"));
    }
}
