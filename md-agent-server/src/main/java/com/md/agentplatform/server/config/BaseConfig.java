package com.md.agentplatform.server.config;

import com.volcengine.ark.runtime.service.ArkService;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.tool.FilesystemTool;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 基础设施配置（裁剪自原 BaseConfig）。
 *
 * <p>原 TOS/MinIO/Lark 全局 Client/XXL-Job/Confluence 均已按"核心平台"范围剔除。
 * Lark Client 现由各 {@code FeishuBotInstance} 独立持有，不再作为全局 Bean。
 * 保留 ArkService（供向量化 embedding 用）。
 */
@Configuration
public class BaseConfig {

    private static final Logger log = LoggerFactory.getLogger(BaseConfig.class);


    @Bean
    public ArkService arkService(ModelConfigProperties modelConfigProperties) {
        ConnectionPool connectionPool = new ConnectionPool(
                modelConfigProperties.getMaxIdleConnections(),
                modelConfigProperties.getKeepAliveDuration(),
                TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        return ArkService.builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .apiKey(modelConfigProperties.getApiKey())
                .build();
    }

    @Bean("filesystemTool")
    public FilesystemTool filesystemTool() {
        return new FilesystemTool(new LocalFilesystem(System.getProperty("user.dir")));
    }
}
