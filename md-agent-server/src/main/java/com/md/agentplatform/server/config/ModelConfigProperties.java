package com.md.agentplatform.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型（Ark/OpenAI/DeepSeek 等 OpenAI 兼容厂商）配置。前缀 {@code spring.ai.model}。
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "spring.ai.model")
public class ModelConfigProperties {

    private String apiKey;
    private String baseUrl;
    private String modelName;
    private String reasoningEffort;
    private int maxIdleConnections = 10;
    private long keepAliveDuration = 60000;
    private String tracingEndpoint;
    private String embeddingModel = "doubao-embedding-vision-250615";
}