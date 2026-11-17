package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.EnvironmentProperties.Gitlab;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.GitlabProperties;
import com.md.agentplatform.server.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按环境解析 GitLab 鉴权信息，并在没有静态 token 时动态换取 token。
 *
 * <h2>一、静态优先，动态兜底</h2>
 * <ol>
 *   <li>{@code gitlab.token} 非空：直接使用 Personal Access Token，走 {@code PRIVATE-TOKEN}；</li>
 *   <li>{@code gitlab.token} 为空：按 {@code gitlab.authMode} 动态换取：
 *     <ul>
 *       <li>{@code session}：{@code POST <api>/session}，用 username/password 换 {@code private_token}；</li>
 *       <li>{@code oauth}：{@code POST <site>/oauth/token} 密码模式换 Bearer token；</li>
 *       <li>{@code auto}（默认）：有 token 用 token；没有 token 但有 password 时先走 session，
 *           session 端点不存在（404/405）时自动回退 OAuth 密码模式。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>二、缓存与刷新</h2>
 * <p>动态 token 按“环境 + API 地址 + 模式 + 用户名”缓存。距离过期不足 60 秒时视为失效；
 * 服务端返回 401 时调用方可通过 {@link #invalidate(String)} 清缓存并重试一次。
 * {@code gitlab.tokenCacheSeconds=0} 可关闭缓存，每次调用都重新换取。
 *
 * <h2>三、并发</h2>
 * <p>同一个缓存键使用 {@link ReentrantLock} 串行化首次换取，避免多个分支并行分析时同时登录。
 */
@Component
public class GitlabTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(GitlabTokenProvider.class);

    private static final String MODE_AUTO = "auto";
    private static final String MODE_TOKEN = "token";
    private static final String MODE_SESSION = "session";
    private static final String MODE_OAUTH = "oauth";

    private static final long DEFAULT_CACHE_SECONDS = 3600L;
    private static final long EXPIRY_SKEW_SECONDS = 60L;

    private final EnvironmentRegistry environmentRegistry;
    private final RestTemplate restTemplate;

    private final ConcurrentMap<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired
    public GitlabTokenProvider(EnvironmentRegistry environmentRegistry, GitlabProperties gitlabProperties) {
        this(environmentRegistry, buildRestTemplate(gitlabProperties));
    }

    /** 供单元测试注入自定义 RestTemplate。 */
    GitlabTokenProvider(EnvironmentRegistry environmentRegistry, RestTemplate restTemplate) {
        this.environmentRegistry = environmentRegistry;
        this.restTemplate = restTemplate;
    }

    /**
     * 解析一次调用所需的 API 根地址与鉴权信息。
     *
     * <p>静态 token 不进入缓存；动态 token 命中缓存时不会发起登录请求。
     */
    public GitlabAccess access(String environment) {
        Config config = config(environment);
        if (MODE_TOKEN.equals(config.mode())) {
            return new GitlabAccess(config.baseUrl(), config.token(),
                    GitlabAccess.AuthScheme.PRIVATE_TOKEN, false);
        }
        return dynamicAccess(config);
    }

    /** 清掉某个环境的动态 token 缓存，下一次 {@link #access(String)} 会重新换取。 */
    public void invalidate(String environment) {
        if (!notBlank(environment)) {
            return;
        }
        String prefix = environment.trim() + "|";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ==================== 配置解析 ====================

    private Config config(String environment) {
        if (!notBlank(environment)) {
            throw new IllegalArgumentException("environment 不能为空（GitLab 地址和鉴权信息从环境目录读取）");
        }
        Gitlab gitlab;
        try {
            gitlab = environmentRegistry.gitlab(environment);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("环境 " + environment
                    + " 未配置 gitlab 分组（至少需要 url，以及 token 或 username/password）: "
                    + e.getMessage(), e);
        }

        String baseUrl = resolveBaseUrl(gitlab);
        if (!notBlank(baseUrl)) {
            throw new IllegalArgumentException("环境 " + environment + " 的 gitlab.url 为空");
        }

        String requestedMode = normalizeMode(gitlab.getAuthMode());
        String token = trim(gitlab.getToken());
        String username = trim(gitlab.getUsername());
        String password = trim(gitlab.getPassword());

        String mode;
        boolean autoDynamic = false;
        if (MODE_TOKEN.equals(requestedMode)) {
            if (!notBlank(token)) {
                throw new IllegalArgumentException("环境 " + environment
                        + " 的 gitlab.token 为空（gitlab.authMode=token）");
            }
            mode = MODE_TOKEN;
        } else if (MODE_SESSION.equals(requestedMode) || MODE_OAUTH.equals(requestedMode)) {
            requirePassword(environment, requestedMode, username, password);
            mode = requestedMode;
        } else {
            // auto：静态 token 优先；没有 token 时才用 username/password 动态换取。
            if (notBlank(token)) {
                mode = MODE_TOKEN;
            } else if (notBlank(password)) {
                requirePassword(environment, MODE_SESSION, username, password);
                mode = MODE_SESSION;
                autoDynamic = true;
            } else {
                throw new IllegalArgumentException("环境 " + environment
                        + " 的 gitlab.token 为空，且未配置动态鉴权"
                        + "（gitlab.authMode=session/oauth + username/password）");
            }
        }

        return new Config(environment.trim(), baseUrl, resolveSiteUrl(gitlab, baseUrl), mode,
                autoDynamic, token, username, password, trim(gitlab.getOauthClientId()),
                trim(gitlab.getOauthClientSecret()), trim(gitlab.getOauthScope()),
                trim(gitlab.getOauthTokenUrl()), cacheSeconds(gitlab.getTokenCacheSeconds()));
    }

    private static void requirePassword(String environment, String mode, String username, String password) {
        if (!notBlank(username) || !notBlank(password)) {
            throw new IllegalArgumentException("环境 " + environment
                    + " 的动态鉴权需要同时配置 gitlab.username 和 gitlab.password（authMode="
                    + mode + "）");
        }
    }

    private static String normalizeMode(String raw) {
        if (!notBlank(raw)) {
            return MODE_AUTO;
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (mode) {
            case MODE_AUTO -> MODE_AUTO;
            case "token", "pat", "private-token", "static" -> MODE_TOKEN;
            case "session", "password", "login" -> MODE_SESSION;
            case "oauth", "oauth2", "oauth-password", "password-grant" -> MODE_OAUTH;
            default -> throw new IllegalArgumentException("gitlab.authMode 不支持: " + raw
                    + "，可选 auto / token / session / oauth");
        };
    }

    private static String resolveBaseUrl(Gitlab gitlab) {
        String value = notBlank(gitlab.getApiUrl()) ? gitlab.getApiUrl() : gitlab.getUrl();
        if (!notBlank(value)) {
            return "";
        }
        String trimmed = trimTrailingSlash(value.trim());
        if (!trimmed.endsWith("/api/v4")) {
            trimmed = trimmed + "/api/v4";
        }
        return trimmed;
    }

    private static String resolveSiteUrl(Gitlab gitlab, String baseUrl) {
        String value = notBlank(gitlab.getUrl()) ? gitlab.getUrl().trim() : baseUrl;
        String site = trimTrailingSlash(value);
        if (site.endsWith("/api/v4")) {
            site = site.substring(0, site.length() - "/api/v4".length());
        }
        return site;
    }

    private static long cacheSeconds(Integer configured) {
        if (configured == null || configured < 0) {
            return DEFAULT_CACHE_SECONDS;
        }
        return configured;
    }

    // ==================== 动态 token ====================

    private GitlabAccess dynamicAccess(Config config) {
        String key = cacheKey(config);
        CachedToken cached = cache.get(key);
        if (isValid(cached)) {
            return cached.toAccess(config.baseUrl());
        }

        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            cached = cache.get(key);
            if (isValid(cached)) {
                return cached.toAccess(config.baseUrl());
            }
            CachedToken fetched = login(config, cached);
            if (config.cacheSeconds() > 0) {
                cache.put(key, fetched);
            } else {
                cache.remove(key);
            }
            return fetched.toAccess(config.baseUrl());
        } finally {
            lock.unlock();
        }
    }

    private CachedToken login(Config config, CachedToken previous) {
        if (previous != null && notBlank(previous.refreshToken())) {
            try {
                return oauthToken(config, refreshForm(config, previous.refreshToken()),
                        previous.refreshToken());
            } catch (RuntimeException e) {
                log.warn("GitLab OAuth refresh 失败，回退密码模式: environment={}, error={}",
                        config.environment(), e.getMessage());
            }
        }
        if (MODE_OAUTH.equals(config.mode())) {
            return oauthToken(config, passwordForm(config), null);
        }
        if (config.autoDynamic()) {
            try {
                return sessionLogin(config);
            } catch (SessionUnsupportedException e) {
                log.info("GitLab session 端点不可用，自动回退 OAuth 密码模式: environment={}",
                        config.environment());
                return oauthToken(config, passwordForm(config), null);
            }
        }
        return sessionLogin(config);
    }

    private CachedToken sessionLogin(Config config) {
        ObjectNode body = JsonUtil.newObject();
        body.put("login", config.username());
        body.put("password", config.password());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    URI.create(config.baseUrl() + "/session"),
                    new HttpEntity<>(body.toString(), headers), String.class);
            JsonNode node = parseBody(response.getBody(), "session");
            String token = firstText(node, "private_token", "token", "access_token");
            if (!notBlank(token)) {
                throw new IllegalStateException("GitLab session 登录成功，但响应中缺少 private_token");
            }
            return new CachedToken(token, null, expiresAt(node, config),
                    GitlabAccess.AuthScheme.PRIVATE_TOKEN);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 405) {
                throw new SessionUnsupportedException("GitLab session 端点不可用（HTTP " + status + "）", e);
            }
            throw new IllegalStateException("GitLab session 登录失败（HTTP "
                    + status + "）：" + abbreviate(e.getResponseBodyAsString())
                    + "；请检查 username/password，或改用 authMode=oauth / token", e);
        } catch (RestClientException e) {
            throw new IllegalStateException("GitLab session 登录请求失败: " + e.getMessage(), e);
        }
    }

    private MultiValueMap<String, String> passwordForm(Config config) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", config.username());
        form.add("password", config.password());
        addClient(form, config);
        if (notBlank(config.scope())) {
            form.add("scope", config.scope());
        }
        return form;
    }

    private MultiValueMap<String, String> refreshForm(Config config, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        addClient(form, config);
        return form;
    }

    private static void addClient(MultiValueMap<String, String> form, Config config) {
        if (notBlank(config.clientId())) {
            form.add("client_id", config.clientId());
        }
        if (notBlank(config.clientSecret())) {
            form.add("client_secret", config.clientSecret());
        }
    }

    private CachedToken oauthToken(Config config, MultiValueMap<String, String> form,
                                   String fallbackRefreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    URI.create(oauthEndpoint(config)), new HttpEntity<>(form, headers), String.class);
            JsonNode node = parseBody(response.getBody(), "oauth");
            if (node.hasNonNull("error")) {
                throw new IllegalStateException("GitLab OAuth 返回错误: "
                        + text(node, "error") + " " + text(node, "error_description"));
            }
            String token = text(node, "access_token");
            if (!notBlank(token)) {
                throw new IllegalStateException("GitLab OAuth 响应中缺少 access_token");
            }
            String refreshToken = text(node, "refresh_token");
            if (!notBlank(refreshToken)) {
                refreshToken = fallbackRefreshToken;
            }
            return new CachedToken(token, refreshToken, expiresAt(node, config),
                    GitlabAccess.AuthScheme.BEARER);
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException("GitLab OAuth 登录失败（HTTP "
                    + e.getStatusCode().value() + "）：" + abbreviate(e.getResponseBodyAsString())
                    + "；请确认 GitLab 已开启 password grant，或改用 authMode=session/token", e);
        } catch (RestClientException e) {
            throw new IllegalStateException("GitLab OAuth 登录请求失败: " + e.getMessage(), e);
        }
    }

    private static String oauthEndpoint(Config config) {
        if (notBlank(config.oauthTokenUrl())) {
            return config.oauthTokenUrl();
        }
        return trimTrailingSlash(config.siteUrl()) + "/oauth/token";
    }

    // ==================== 缓存 ====================

    private static String cacheKey(Config config) {
        return config.environment() + "|" + config.baseUrl() + "|" + config.mode()
                + "|" + config.username();
    }

    private static boolean isValid(CachedToken token) {
        return token != null && token.isValid(Instant.now().getEpochSecond());
    }

    private static long expiresAt(JsonNode node, Config config) {
        long now = Instant.now().getEpochSecond();
        long ttl = node.path("expires_in").asLong(0);
        if (ttl > 0) {
            return now + ttl;
        }

        String expiresAt = text(node, "expires_at");
        if (notBlank(expiresAt)) {
            try {
                return Instant.parse(expiresAt).getEpochSecond();
            } catch (DateTimeParseException ignored) {
                try {
                    return OffsetDateTime.parse(expiresAt).toInstant().getEpochSecond();
                } catch (DateTimeParseException ignoredAgain) {
                    // 继续使用配置的兜底 TTL
                }
            }
        }
        long fallback = config.cacheSeconds() > 0 ? config.cacheSeconds() : DEFAULT_CACHE_SECONDS;
        return now + Math.max(1, fallback);
    }

    private static JsonNode parseBody(String body, String action) {
        if (!notBlank(body)) {
            throw new IllegalStateException("GitLab " + action + " 响应为空");
        }
        try {
            return JsonUtil.parse(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("GitLab " + action + " 响应不是合法 JSON："
                    + abbreviate(body), e);
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (notBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String abbreviate(String value) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static RestTemplate buildRestTemplate(GitlabProperties properties) {
        int timeout = properties == null ? 30000 : properties.getTimeoutMs();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    private record Config(String environment, String baseUrl, String siteUrl, String mode,
                          boolean autoDynamic, String token, String username, String password,
                          String clientId, String clientSecret, String scope,
                          String oauthTokenUrl, long cacheSeconds) {
    }

    /** session 端点在该 GitLab 版本/部署上不存在，供 auto 模式回退 OAuth。 */
    private static class SessionUnsupportedException extends IllegalStateException {
        SessionUnsupportedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record CachedToken(String token, String refreshToken, long expiresAt,
                               GitlabAccess.AuthScheme scheme) {

        boolean isValid(long now) {
            long remaining = expiresAt - now;
            if (remaining <= 0) {
                return false;
            }
            long skew = Math.min(EXPIRY_SKEW_SECONDS, Math.max(1, remaining / 5));
            return remaining > skew;
        }

        GitlabAccess toAccess(String baseUrl) {
            return new GitlabAccess(baseUrl, token, scheme, true);
        }
    }
}
