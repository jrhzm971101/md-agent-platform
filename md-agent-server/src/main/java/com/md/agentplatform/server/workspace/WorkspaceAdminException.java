package com.md.agentplatform.server.workspace;

/**
 * 工作区管理面的业务异常 —— 用来表达「参数/状态不对」，而不是「代码崩了」。
 *
 * <p>与 {@link WorkspacePathException} 分开：后者专指路径安全边界被触发（一定是 400，
 * 且必须留痕），本异常覆盖「agent 不存在 / 不是 HARNESS / 目录非空」这类普通拒绝，
 * 两者在 Controller 里映射成不同的响应语义。
 */
public class WorkspaceAdminException extends RuntimeException {

    /**
     * 是否属于「你要找的东西不在了」（404）而不是「你给的参数不对」（400）。
     *
     * <p>两者对页面的含义完全不同：404 该提示"刷新后重试"，400 该提示"改一下输入"。
     * 用布尔而不是再拆一个异常类，是因为除状态码外没有任何行为差异。
     */
    private final boolean notFound;

    public WorkspaceAdminException(String message) {
        this(message, false);
    }

    public WorkspaceAdminException(String message, boolean notFound) {
        super(message);
        this.notFound = notFound;
    }

    public boolean isNotFound() {
        return notFound;
    }
}
