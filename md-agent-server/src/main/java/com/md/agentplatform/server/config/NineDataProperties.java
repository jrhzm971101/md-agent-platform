package com.md.agentplatform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * NineData OpenAPI 配置。
 *
 * <p>这里只放与具体环境无关的通用参数。调用地址和 AK/SK 必须来自数据库环境目录中
 * 指定环境的 {@code ninedata} 分组；工具调用时 {@code environment} 必填，
 * 不从 {@code application.yml} 读取，也不存在全局凭据兜底。
 *
 * <p>在 application.yml / Nacos 中配置:
 * <pre>
 * ninedata:
 *   source-client: md-agent-platform
 *   default-page-size: 50
 *   timeout-ms: 120000
 * </pre>
 *
 * <p><b>AK/SK 不允许写进代码或日志。</b>它们属于环境级配置，只参与本地签名计算，
 * 不会随请求发送。
 */
@Component
@ConfigurationProperties(prefix = "ninedata")
public class NineDataProperties {

    /** 请求来源标识，写入 NineData 审计链路。 */
    private String source = "NINEDATA_SKILL";

    /** 调用方客户端标识，写入 NineData 审计链路。 */
    private String sourceClient = "md-agent-platform";

    /** 默认结果预览条数。 */
    private int defaultPageSize = 50;

    /** 默认语言标识（NineData OpenAPI 字段 language）。 */
    private String defaultLanguage = "enus";

    /** 单次请求超时（毫秒）。 */
    private int timeoutMs = 120000;

    /** 网络异常、429 或 5xx 时的最大重试次数（不含首次请求）。 */
    private int maxRetries = 2;

    /** 首次重试等待毫秒数，后续按 2 倍指数退避。 */
    private long retryBackoffMs = 500;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceClient() {
        return sourceClient;
    }

    public void setSourceClient(String sourceClient) {
        this.sourceClient = sourceClient;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }
}
