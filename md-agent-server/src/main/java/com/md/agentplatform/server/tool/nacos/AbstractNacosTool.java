package com.md.agentplatform.server.tool.nacos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos 配置中心工具公共基类 —— 直接调用 Nacos OpenAPI（HTTP），不再依赖 nacos-client。
 *
 * <p>统一封装：serverAddr 规范化、登录换取 accessToken（进程内缓存）、
 * 读取/发布配置、超时与错误处理。连接信息全部通过方法参数传入，
 * 由调用方从 {@link EnvironmentRegistry} 解析环境目录得到。
 */
public abstract class AbstractNacosTool {

    /** 各子类独立的日志记录器（按运行时类型命名） */
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /** 环境目录解析服务 —— 按 environment 名取 Nacos 连接信息 */
    @Autowired
    protected EnvironmentRegistry environmentRegistry;

    /** Nacos 默认分组 */
    protected static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /** 默认请求超时（毫秒） */
    protected static final long DEFAULT_TIMEOUT_MS = 5000;

    /** 默认发布超时（毫秒）—— 配置内容可能较大，留更宽的上传时间 */
    protected static final long DEFAULT_PUBLISH_TIMEOUT_MS = 15000;

    private static final String CONFIG_PATH = "/v1/cs/configs";
    private static final String LOGIN_PATH = "/v1/auth/users/login";
    private static final long DEFAULT_TOKEN_TTL_SECONDS = 3600;

    /** 列表接口单页大小（Nacos 允许的上限） */
    private static final int CONFIG_LIST_PAGE_SIZE = 500;

    /** 列表工具单次最多返回的配置数，避免超大命名空间撑爆上下文 */
    private static final int CONFIG_LIST_MAX_ITEMS = 2000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** accessToken 进程内缓存，避免每次工具调用都重新登录 */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /**
     * 读取单个配置内容。
     *
     * @return 配置内容；配置不存在返回 {@code null}
     */
    protected String getConfig(String serverAddr, String namespace, String username, String password,
                               String dataId, String group, long timeoutMs) {
        String baseUrl = normalizeBaseUrl(serverAddr);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(CONFIG_PATH)
                .queryParam("dataId", dataId.trim())
                .queryParam("group", group);
        if (notBlank(namespace)) {
            builder.queryParam("tenant", namespace.trim());
        }
        URI uri = builder.build().encode(StandardCharsets.UTF_8).toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON));
        String token = accessToken(baseUrl, username, password, timeoutMs);
        if (notBlank(token)) {
            headers.set("accessToken", token);
        }

        try {
            ResponseEntity<String> response = restTemplate(timeoutMs)
                    .exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * 查询命名空间下的配置列表；自动翻页聚合，最多返回 {@link #CONFIG_LIST_MAX_ITEMS} 条。
     *
     * <p>{@code dataId} / {@code group} 非空时使用模糊匹配，均为空时返回命名空间下全部配置。
     */
    protected ConfigList listConfigs(String serverAddr, String namespace, String username, String password,
                                     String dataId, String group, long timeoutMs) {
        String baseUrl = normalizeBaseUrl(serverAddr);
        String token = accessToken(baseUrl, username, password, timeoutMs);
        List<ConfigItem> items = new ArrayList<>();
        int totalCount = 0;
        int pageNo = 1;
        int pagesAvailable = 1;
        boolean truncated = false;

        while (true) {
            JsonNode root = fetchConfigPage(baseUrl, namespace, token, dataId, group,
                    pageNo, CONFIG_LIST_PAGE_SIZE, timeoutMs);
            totalCount = root.path("totalCount").asInt(0);
            pagesAvailable = root.path("pagesAvailable").asInt(pageNo);

            int pageItemCount = 0;
            for (JsonNode item : root.path("pageItems")) {
                pageItemCount++;
                if (items.size() >= CONFIG_LIST_MAX_ITEMS) {
                    truncated = true;
                    break;
                }
                items.add(new ConfigItem(
                        item.path("dataId").asText(""),
                        item.path("group").asText(""),
                        item.path("type").asText(""),
                        item.path("appName").asText("")));
            }

            if (truncated || pageItemCount == 0 || pageNo >= pagesAvailable) {
                break;
            }
            pageNo++;
        }

        if (items.size() < totalCount) {
            truncated = true;
        }
        return new ConfigList(List.copyOf(items), totalCount, truncated);
    }

    /** 请求一页配置列表，返回 Nacos 原始 JSON。 */
    private JsonNode fetchConfigPage(String baseUrl, String namespace, String token,
                                     String dataId, String group, int pageNo, int pageSize, long timeoutMs) {
        String search = notBlank(dataId) || notBlank(group) ? "blur" : "accurate";
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(CONFIG_PATH)
                .queryParam("dataId", defaultIfBlank(dataId, ""))
                .queryParam("group", defaultIfBlank(group, ""))
                .queryParam("appName", "")
                .queryParam("config_tags", "")
                .queryParam("pageNo", pageNo)
                .queryParam("pageSize", pageSize)
                .queryParam("search", search);
        if (notBlank(namespace)) {
            builder.queryParam("tenant", namespace.trim());
        }
        URI uri = builder.build().encode(StandardCharsets.UTF_8).toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (notBlank(token)) {
            headers.set("accessToken", token);
        }

        ResponseEntity<String> response = restTemplate(timeoutMs)
                .exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        if (!notBlank(body)) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Nacos 配置列表返回非 JSON: " + body, e);
        }
    }

    /**
     * 新增或更新一个配置。
     *
     * @return Nacos 返回的成功标记
     */
    protected boolean publishConfig(String serverAddr, String namespace, String username, String password,
                                    String dataId, String group, String content, String type, long timeoutMs) {
        String baseUrl = normalizeBaseUrl(serverAddr);
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(CONFIG_PATH)
                .build().encode(StandardCharsets.UTF_8).toUri();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("dataId", dataId.trim());
        form.add("group", group);
        form.add("content", content);
        form.add("type", type);
        if (notBlank(namespace)) {
            form.add("tenant", namespace.trim());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String token = accessToken(baseUrl, username, password, timeoutMs);
        if (notBlank(token)) {
            headers.set("accessToken", token);
        }

        ResponseEntity<String> response = restTemplate(timeoutMs)
                .postForEntity(uri, new HttpEntity<>(form, headers), String.class);
        String body = response.getBody();
        return response.getStatusCode().is2xxSuccessful()
                && body != null && "true".equalsIgnoreCase(body.trim());
    }

    /**
     * 用环境目录里的用户名/口令换取 accessToken；未配置用户名视为未开启鉴权，直接返回 null。
     *
     * <p>登录失败时返回 null，让后续配置请求以服务端返回的 401/403 为准，
     * 避免把「未开启鉴权但保留了口令配置」的环境误判为不可用。
     */
    private String accessToken(String baseUrl, String username, String password, long timeoutMs) {
        if (!notBlank(username)) {
            return null;
        }
        String user = username.trim();
        String passwordValue = password == null ? "" : password;
        String cacheKey = baseUrl + "|" + user + "|" + Integer.toHexString(passwordValue.hashCode());

        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
            return cached.token();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", user);
        form.add("password", passwordValue);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate(timeoutMs)
                    .postForEntity(baseUrl + LOGIN_PATH, new HttpEntity<>(form, headers), String.class);
            String body = response.getBody();
            if (!notBlank(body)) {
                return null;
            }
            JsonNode node = OBJECT_MAPPER.readTree(body);
            String token = node.path("accessToken").asText("");
            if (!notBlank(token)) {
                return null;
            }
            long ttl = node.path("tokenTtl").asLong(DEFAULT_TOKEN_TTL_SECONDS);
            long safeTtlSeconds = Math.max(60, ttl - 60);
            tokenCache.put(cacheKey, new CachedToken(token, System.currentTimeMillis() + safeTtlSeconds * 1000));
            return token;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == HttpStatus.NOT_FOUND.value() || status == HttpStatus.METHOD_NOT_ALLOWED.value()) {
                logger.debug("Nacos 登录接口不存在，按未开启鉴权处理, status:{}", status);
            } else {
                logger.warn("Nacos 登录失败，将不带 token 继续请求, status:{}", status);
            }
            return null;
        } catch (Exception e) {
            logger.warn("Nacos 登录异常，将不带 token 继续请求: {}", e.getMessage());
            return null;
        }
    }

    private RestTemplate restTemplate(long timeoutMs) {
        int timeout = timeoutMs > 0 && timeoutMs <= Integer.MAX_VALUE
                ? (int) timeoutMs : (int) DEFAULT_TIMEOUT_MS;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    /**
     * 规范化 Nacos 根地址：补协议、去末尾斜杠、补 {@code /nacos} 上下文路径。
     *
     * <p>环境目录里的地址通常是 {@code host:443} 或 {@code https://host}；
     * Nacos OpenAPI 固定挂在 {@code /nacos} 下，因此这里统一补齐。
     * 已经带自定义上下文路径（如 {@code /nacos-config}）时原样保留。
     */
    static String normalizeBaseUrl(String serverAddr) {
        if (!notBlank(serverAddr)) {
            throw new IllegalArgumentException("nacos.serverAddr 不能为空");
        }
        String addr = serverAddr.trim();
        String lower = addr.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            addr = "https://" + addr;
        }
        while (addr.endsWith("/")) {
            addr = addr.substring(0, addr.length() - 1);
        }
        int schemeEnd = addr.indexOf("://") + 3;
        int pathStart = addr.indexOf('/', schemeEnd);
        if (pathStart < 0) {
            return addr + "/nacos";
        }
        return addr;
    }

    /** 配置列表项（不含配置内容） */
    protected record ConfigItem(String dataId, String group, String type, String appName) {
    }

    /** 配置列表查询结果 */
    protected record ConfigList(List<ConfigItem> items, int totalCount, boolean truncated) {
    }

    private record CachedToken(String token, long expiresAtMillis) {
    }

    /** 非空字符串判断 */
    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 返回非空参数值，空值回退到默认值 */
    protected static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
