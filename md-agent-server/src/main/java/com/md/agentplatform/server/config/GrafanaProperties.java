package com.md.agentplatform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Grafana 查询配置。
 *
 * <p>地址与 Basic Auth 按 {@code environment} 从环境目录读取；这里只保留超时和默认数据源 UID。
 */
@Component
@ConfigurationProperties(prefix = "grafana")
public class GrafanaProperties {

    /** 建立连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;

    /** 读取超时（毫秒）。Prometheus rules 响应可达 1MB 以上，留足时间。 */
    private int readTimeoutMs = 60000;

    /** 默认 Prometheus 数据源 UID。 */
    private String defaultDatasourceUid = "DmKRmGQvk";

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

    public String getDefaultDatasourceUid() {
        return defaultDatasourceUid;
    }

    public void setDefaultDatasourceUid(String defaultDatasourceUid) {
        this.defaultDatasourceUid = defaultDatasourceUid;
    }
}
