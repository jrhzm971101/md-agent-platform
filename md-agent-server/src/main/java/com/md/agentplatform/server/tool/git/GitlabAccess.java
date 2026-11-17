package com.md.agentplatform.server.tool.git;

import org.springframework.http.HttpHeaders;

/**
 * 一次 GitLab 调用使用的连接信息。
 *
 * <p>静态 PAT 与 session 登录换到的 {@code private_token} 走 {@code PRIVATE-TOKEN}；
 * OAuth 密码模式换到的 {@code access_token} 走 {@code Authorization: Bearer}。
 *
 * <p>{@code dynamic=true} 表示 token 是运行时获取的。调用方收到 401 时可以先让
 * {@link GitlabTokenProvider#invalidate(String)} 清缓存，再用新的 access 重试一次。
 */
public record GitlabAccess(String baseUrl, String token, AuthScheme scheme, boolean dynamic) {

    public enum AuthScheme {
        /** GitLab Personal Access Token / session private_token。 */
        PRIVATE_TOKEN,
        /** OAuth2 access_token。 */
        BEARER
    }

    /** 避免日志/异常消息意外打印 token。 */
    @Override
    public String toString() {
        return "GitlabAccess[baseUrl=" + baseUrl + ", scheme=" + scheme
                + ", dynamic=" + dynamic + ", token=***]";
    }

    /** 把鉴权信息写入请求头。 */
    public void apply(HttpHeaders headers) {
        if (scheme == AuthScheme.BEARER) {
            headers.setBearerAuth(token);
        } else {
            headers.set("PRIVATE-TOKEN", token);
        }
    }
}
