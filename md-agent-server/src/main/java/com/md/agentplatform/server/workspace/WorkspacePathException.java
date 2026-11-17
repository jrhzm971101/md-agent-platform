package com.md.agentplatform.server.workspace;

/**
 * 工作区路径被拒绝。语义上等价 HTTP 400：调用方给了非法路径，不是服务端故障。
 */
public class WorkspacePathException extends RuntimeException {

    public WorkspacePathException(String message) {
        super(message);
    }
}
