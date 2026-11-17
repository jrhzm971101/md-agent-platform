package com.md.agentplatform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SkyWalking GraphQL 查询配置。
 *
 * <p>GraphQL 地址与 Basic Auth 按 {@code environment} 从环境目录读取；这里只保留超时。
 */
@Component
@ConfigurationProperties(prefix = "skywalking")
public class SkyWalkingProperties {

    /** 建立连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;

    /** 读取响应超时（毫秒）。trace 查询会返回全部 span，链路长时需要留足时间。 */
    private int readTimeoutMs = 30000;

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
