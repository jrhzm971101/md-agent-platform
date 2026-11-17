package com.md.agentplatform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Confluence Data Center REST API 配置属性。
 *
 * <p>在 application.yml 中配置:
 * <pre>
 * confluence:
 *   baseUrl: http://wiki.huojintech.com/confluence
 *   username: jinran
 *   password: ...
 *   pageSize: 25
 *   maxPagesPerSpace: 500
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "confluence")
public class ConfluenceProperties {

    /** Confluence 实例的 base URL，如 http://wiki.huojintech.com/confluence */
    private String baseUrl = "http://wiki.huojintech.com";

    /** Basic Auth 用户名 */
    private String username;

    /** Basic Auth 密码 */
    private String password;

    /** 每页拉取条数，默认 200（Confluence REST API 单次最大支持量） */
    private int pageSize = 200;

    /**
     * 单个空间最大拉取页面数（安全阀），防止超大空间拉取时间过长。
     * 设为 -1 表示不限制，完全依赖 Confluence API 分页（_links.next）决定何时停止。
     */
    private int maxPagesPerSpace = -1;

    /**
     * 全量同步时只拉取最近 N 个月内修改过的页面（基于 lastmodified）。
     * 设为 0 或负数表示不限制。默认 12（近 1 年）。
     * 当 wikiSyncYearStep > 0 时此配置被忽略（走按年份分片模式）。
     */
    private int wikiFilterMonths = 0;

    /** 全量同步起始年份（包含），默认 2018 */
    private int wikiSyncStartYear = 2018;

    /** 全量同步结束年份（包含），默认当前年 */
    private int wikiSyncEndYear = LocalDateTime.now().getYear();

    /** 全量同步年份分片步长，默认 2 年一片。设为 0 表示不分片，走 wikiFilterMonths 模式 */
    private int wikiSyncYearStep = 2;

    /** 请求间隔毫秒数，防止触发 Confluence 限流 */
    private long requestIntervalMs = 500;

    // ── getter/setter ──

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public int getMaxPagesPerSpace() { return maxPagesPerSpace; }
    public void setMaxPagesPerSpace(int maxPagesPerSpace) { this.maxPagesPerSpace = maxPagesPerSpace; }

    public int getWikiFilterMonths() { return wikiFilterMonths; }
    public void setWikiFilterMonths(int wikiFilterMonths) { this.wikiFilterMonths = wikiFilterMonths; }

    public int getWikiSyncStartYear() { return wikiSyncStartYear; }
    public void setWikiSyncStartYear(int wikiSyncStartYear) { this.wikiSyncStartYear = wikiSyncStartYear; }

    public int getWikiSyncEndYear() { return wikiSyncEndYear; }
    public void setWikiSyncEndYear(int wikiSyncEndYear) { this.wikiSyncEndYear = wikiSyncEndYear; }

    public int getWikiSyncYearStep() { return wikiSyncYearStep; }
    public void setWikiSyncYearStep(int wikiSyncYearStep) { this.wikiSyncYearStep = wikiSyncYearStep; }

    public long getRequestIntervalMs() { return requestIntervalMs; }
    public void setRequestIntervalMs(long requestIntervalMs) { this.requestIntervalMs = requestIntervalMs; }
}