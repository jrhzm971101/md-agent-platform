package com.md.agentplatform.server.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区 KV 库连接串派生的契约测试。
 *
 * <p>这条逻辑出错的代价很特别：它<b>不抛异常</b>，只是连到一个不存在的库上，
 * 然后在下游以一句「Identifier name ... is too long」收场 —— 从报错完全看不出
 * 问题出在连接串拼接上。2026-09-23 真踩过一次（replaceFirst 里写错了正则组号），
 * 所以在这里钉住。
 *
 * <p>纯字符串逻辑，不起 Spring 容器：直接调包内可见的静态方法。
 */
class ControlPlaneStoreConfigTest {

    /**
     * 真实的那一串（业务库带连字符，查询参数一长串）。
     *
     * <p>断的是<b>全等</b>而不是「包含 md_agent_workspace」—— 出问题的那次结果里
     * 确实"包含"这个库名，只不过它是被拼在旧库名前面的
     * （{@code .../md_agent_workspacemd-agent-platformuseUnicode=true...}）。
     */
    @Test
    void replacesOnlyTheDatabaseSegmentAndKeepsQueryParameters() {
        String main = "jdbc:mysql://106.53.145.158:30306/md-agent-platform"
                + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

        String derived = ControlPlaneStoreConfig.deriveDatabaseUrl(main, "md_agent_workspace");

        assertEquals("jdbc:mysql://106.53.145.158:30306/md_agent_workspace"
                + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", derived);
    }

    /** 不带查询参数也要能用（本机直连时常见）。 */
    @Test
    void worksWithoutQueryParameters() {
        assertEquals("jdbc:mysql://127.0.0.1:3306/md_agent_workspace",
                ControlPlaneStoreConfig.deriveDatabaseUrl(
                        "jdbc:mysql://127.0.0.1:3306/md-agent-platform", "md_agent_workspace"));
    }

    /** 查询参数里含 '/' 时，库名不能被定位到参数里去。 */
    @Test
    void queryParameterContainingSlashDoesNotConfuseTheDatabaseSegment() {
        assertEquals("jdbc:mysql://h:3306/kv?resource=/a/b",
                ControlPlaneStoreConfig.deriveDatabaseUrl("jdbc:mysql://h:3306/biz?resource=/a/b", "kv"));
    }

    /** 只写到 host:port（没写库名）时必须大声失败，而不是拼出一个畸形 url。 */
    @Test
    void rejectsUrlWithoutDatabaseSegment() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ControlPlaneStoreConfig.deriveDatabaseUrl("jdbc:mysql://h:3306", "kv"));
        assertTrue(error.getMessage().contains("没有库名"), error.getMessage());
    }

    /** 空值同样要大声失败。 */
    @Test
    void rejectsBlankUrl() {
        assertThrows(IllegalStateException.class,
                () -> ControlPlaneStoreConfig.deriveDatabaseUrl("   ", "kv"));
    }
}