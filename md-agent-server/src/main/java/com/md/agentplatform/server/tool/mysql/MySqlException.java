package com.md.agentplatform.server.tool.mysql;

/**
 * MySQL 直连异常。保留 SQLState / vendor code，便于模型区分连接、权限、语法与数据问题。
 */
public class MySqlException extends RuntimeException {

    private final String sqlState;
    private final Integer errorCode;

    public MySqlException(String message, String sqlState, Integer errorCode, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
        this.errorCode = errorCode;
    }

    public String getSqlState() {
        return sqlState;
    }

    public Integer getErrorCode() {
        return errorCode;
    }
}
