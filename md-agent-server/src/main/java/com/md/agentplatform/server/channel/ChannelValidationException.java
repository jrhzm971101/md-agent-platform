package com.md.agentplatform.server.channel;

/**
 * 渠道配置/凭据不合法 —— 由工厂与校验器抛出，带上"哪个字段、为什么"。
 *
 * <h2>为什么单独一个异常类型，而不用 IllegalArgumentException</h2>
 * 两条路径都要把失败原因<b>带回页面</b>：保存时的校验（{@code ChannelValidator}）
 * 与装配时的构造（各工厂）。用 IllegalArgumentException 时，调用方无法区分
 * "用户填错了"（该回 400 并列出问题）与"代码写错了"（该回 500 并打日志）。
 * 这个区分决定了页面能不能给出可操作的提示。
 */
public class ChannelValidationException extends RuntimeException {

    public ChannelValidationException(String message) {
        super(message);
    }

    public ChannelValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
