package com.md.agentplatform.server.tool.rag;

/**
 * 由框架自动从 RuntimeContext 注入到 @Tool 方法的用户上下文。
 *
 * <p>调用方在构建 RuntimeContext 时通过
 * {@code RuntimeContext.builder().put(UserContext.class, new UserContext(...))}
 * 注册，框架在每次工具调用时自动匹配类型注入，LLM 无需感知此参数。</p>
 *
 * @param userId  当前用户的唯一标识（飞书 openId / 企微 userId）
 * @param userKey 权限过滤用 key（可为 null）
 */
public record UserContext(String userId, String userKey) {
}
