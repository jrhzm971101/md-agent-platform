package com.md.agentplatform.server.tool.mysql;

/**
 * 一次 MySQL 直连调用的目标。
 *
 * <p>只承载连接参数，不包含任何日志或返回值逻辑；密码不会出现在 {@link #toString()} 里。
 */
public record MySqlTarget(
        String url,
        String username,
        String password,
        String defaultDatabase,
        boolean allowWrite,
        int maxRows,
        int queryTimeoutSeconds) {

    @Override
    public String toString() {
        return "MySqlTarget{url='" + url + "', username='" + username
                + "', defaultDatabase='" + defaultDatabase
                + "', allowWrite=" + allowWrite
                + ", maxRows=" + maxRows
                + ", queryTimeoutSeconds=" + queryTimeoutSeconds + '}';
    }
}
