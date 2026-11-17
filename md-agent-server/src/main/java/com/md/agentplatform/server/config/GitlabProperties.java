package com.md.agentplatform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitLab REST API 配置属性。
 *
 * <p>GitLab 地址和鉴权信息不再放在这里，而是按 {@code environment} 从环境目录的
 * {@code gitlab} 分组读取。本类只保留与具体 GitLab 实例无关的本地工作区与超时配置。
 *
 * <p>在 application.yml 中配置:
 * <pre>
 * gitlab:
 *   base-dir: workspace/code     # API 快照工作区根目录
 *   timeout-ms: 30000           # 普通 API 请求超时
 *   archive-timeout-ms: 300000  # archive 下载/大仓库读取超时
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "gitlab")
public class GitlabProperties {

    /**
     * 本地代码仓库根目录。clone/checkout/pull 传入的相对 repoDir 会拼接到此目录下；
     * repoDir 传绝对路径时则直接使用，不受此配置影响。
     */
    private String baseDir = "workspace/code";

    /** 普通 API 请求超时（毫秒）。 */
    private int timeoutMs = 30000;

    /** archive 下载超时（毫秒）。大仓库按分支物化时需要更长时间。 */
    private int archiveTimeoutMs = 300000;

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getArchiveTimeoutMs() {
        return archiveTimeoutMs;
    }

    public void setArchiveTimeoutMs(int archiveTimeoutMs) {
        this.archiveTimeoutMs = archiveTimeoutMs;
    }
}
