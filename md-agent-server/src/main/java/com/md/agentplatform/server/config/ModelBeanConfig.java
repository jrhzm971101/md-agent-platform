package com.md.agentplatform.server.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局共享模型 Bean。所有 Agent 通过 {@code openAIChatModel} 引用此模型。
 */
@Configuration
public class ModelBeanConfig {

    @Bean("openAIChatModel")
    public Model getOpenAIModel(ModelConfigProperties modelConfigProperties) {
        return OpenAIChatModel.builder()
                .apiKey(modelConfigProperties.getApiKey())
                .baseUrl(modelConfigProperties.getBaseUrl())
                .modelName(modelConfigProperties.getModelName())
                .formatter(new DeepSeekReasoningBackfillFormatter())
                .generateOptions(GenerateOptions.builder()
                        .reasoningEffort(modelConfigProperties.getReasoningEffort())
                        .build())
                .build();
    }
}
