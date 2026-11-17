package com.md.agentplatform.server.state;

/**
 * 状态存储管理接口的领域异常。
 *
 * <p>带 {@link Kind} 而不是让 controller 去解析消息文本：状态码是契约的一部分，
 * 靠字符串判断"这是 404 还是 400"是最容易在改文案时被悄悄改坏的东西。
 */
public class StateStoreException extends RuntimeException {

    public enum Kind {
        /** 参数非法（表名不合法等）→ 400。 */
        INVALID,
        /** 目标不存在（表或会话）→ 404。 */
        NOT_FOUND,
        /** 目标内容过大，拒绝整包返回 → 413。 */
        TOO_LARGE
    }

    private final Kind kind;

    public StateStoreException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
