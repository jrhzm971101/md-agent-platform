package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.GitlabProperties;
import com.md.agentplatform.server.util.JsonUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GitLab REST API 只读工具的公共基类。
 *
 * <p>继承本类的工具都走 GitLab 服务端接口，因此在这里统一下沉四件事：
 * <ul>
 *   <li>鉴权：地址与凭据按 {@code environment} 从环境目录读取；静态 token 优先，
 *       缺失时由 {@link GitlabTokenProvider} 用 username/password 动态换取并缓存；</li>
 *   <li>请求：统一超时（{@code gitlab.timeout-ms}）、统一 JSON 解析、失败时按状态码给出中文原因；</li>
 *   <li>编码：项目/组标识纯数字按 ID 直接用，其余按仓库路径编码（{@code mtn/lms → mtn%2Flms}）；</li>
 *   <li>时间：GitLab 返回的 ISO 时间统一转成本地时区展示，避免调用方自己换算。</li>
 * </ul>
 *
 * <p>本类只提供 {@code GET}，继承者均为只读查询工具，不会修改任何仓库。
 *
 * <p>工具方法必须显式传 {@code environment}：同一个工具可以连 huojintech、cmr-gitlab
 * 等不同 GitLab 实例，不再依赖全局唯一的固定地址和凭据。
 */
public abstract class AbstractGitlabTool extends AbstractGitTool {

    /** 本地时间展示格式 */
    protected static final DateTimeFormatter LOCAL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** GitLab 单页最大条数 */
    protected static final int PAGE_SIZE = 100;

    /** 请求超时取自 gitlab.timeout-ms，注入配置后初始化 */
    protected RestTemplate restTemplate;

    @Autowired
    protected GitlabProperties gitlabProperties;

    @Autowired
    protected GitlabTokenProvider gitlabTokenProvider;

    @PostConstruct
    void initRestTemplate() {
        this.restTemplate = buildRestTemplate(gitlabProperties.getTimeoutMs());
    }

    // ==================== 配置 ====================

    /** 配置检查：缺失时返回给用户看的提示，配置齐全返回 null。 */
    protected String checkConfig(String toolName, String environment) {
        if (!notBlank(environment)) {
            return "[" + toolName + "] 参数缺失: environment 必填";
        }
        try {
            gitlabAccess(environment);
            return null;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "[" + toolName + "] 配置/鉴权失败: " + e.getMessage();
        }
    }

    /**
     * 按环境解析 GitLab API 根地址与鉴权信息。
     *
     * <p>静态 token 优先；没有静态 token 时由 {@link GitlabTokenProvider} 用
     * username/password 动态换取并缓存。调用方不需要感知具体鉴权模式。
     */
    protected GitlabAccess gitlabAccess(String environment) {
        if (gitlabTokenProvider == null) {
            throw new IllegalStateException("GitlabTokenProvider 未初始化");
        }
        return gitlabTokenProvider.access(environment);
    }

    // ==================== 请求 ====================

    /**
     * 拼装请求 URI。
     *
     * <p>调用方传入的 query 必须已手工编码；这里用 {@link URI#create} 而不是
     * {@code UriComponentsBuilder}，避免 {@code %2F} 被二次编码成 {@code %252F}。
     */
    protected URI uri(String environment, String path, List<String> query) {
        return uri(gitlabAccess(environment), path, query);
    }

    protected static URI uri(GitlabAccess access, String path, List<String> query) {
        String suffix = (query == null || query.isEmpty()) ? "" : "?" + String.join("&", query);
        return URI.create(access.baseUrl() + path + suffix);
    }

    /**
     * GET 一个 GitLab 接口；body 为空返回 null。
     *
     * <p>动态 token 返回 401 时清缓存并重试一次，覆盖 token 被提前吊销或缓存在
     * 临界期的情况；静态 token 不重试，避免把无效配置重复打两次。
     */
    protected JsonNode get(String environment, URI uri) {
        GitlabAccess access = gitlabAccess(environment);
        try {
            return get(access, uri);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 401 && access.dynamic()) {
                gitlabTokenProvider.invalidate(environment);
                return get(gitlabAccess(environment), uri);
            }
            throw e;
        }
    }

    protected JsonNode get(GitlabAccess access, URI uri) {
        HttpHeaders headers = new HttpHeaders();
        access.apply(headers);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        return notBlank(body) ? JsonUtil.parse(body) : null;
    }

    /** 把 HTTP 状态码翻译成中文原因，并记录服务端返回体。 */
    protected String describeHttpError(String toolName, HttpStatusCodeException e, String subject) {
        int status = e.getStatusCode().value();
        String reason;
        if (status == 401) {
            reason = "鉴权失败（HTTP 401）：token 无效、已过期或账号无权访问；"
                    + "静态 token 请检查 gitlab.token，动态鉴权请检查 username/password";
        } else if (status == 403) {
            reason = "无权访问（HTTP 403）：请确认账号已加入该项目/组";
        } else if (status == 404) {
            reason = "不存在或无权访问（HTTP 404）：" + subject;
        } else {
            reason = "请求失败（HTTP " + status + "）：" + subject;
        }
        logger.warn("{} 请求失败, subject:{}, status:{}, body:{}", toolName, subject, status,
                e.getResponseBodyAsString());
        return "[" + toolName + "] " + reason;
    }

    // ==================== 通用工具 ====================

    /**
     * 项目/组标识：纯数字按 ID 直接用；其余按路径 URL 编码
     * （{@code mtn/lms → mtn%2Flms}、{@code mtn → mtn}）。
     */
    protected static String encodeProject(String project) {
        String value = project.trim();
        if (value.matches("\\d+")) {
            return value;
        }
        // 容忍调用方传入已编码的路径
        String decoded = value.replace("%2F", "/").replace("%2f", "/");
        return urlEncode(decoded);
    }

    protected static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 本地时间窗口 → GitLab 接受的时间戳（带时区偏移，如 {@code 2026-09-11T00:00:00+08:00}）。 */
    protected static String isoOf(Date date, ZoneId zone) {
        return date.toInstant().atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 解析 GitLab 返回的 ISO 时间（{@code 2026-09-11T11:42:15.304Z}）；失败返回 null。 */
    protected static Instant parseInstant(String iso) {
        if (!notBlank(iso)) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(iso).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    protected static String formatLocal(Instant instant, ZoneId zone) {
        return LOCAL_TIME_FORMAT.format(instant.atZone(zone));
    }

    /** ISO 时间 → 本地时间字符串；解析失败返回空串。 */
    protected static String localTime(String iso, ZoneId zone) {
        Instant instant = parseInstant(iso);
        return instant == null ? "" : formatLocal(instant, zone);
    }

    /** 取字符串字段，缺失或 null 返回空串。 */
    protected static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    /** 取嵌套对象的字符串字段，如 {@code username} ∈ {@code author}。 */
    protected static String nestedText(JsonNode node, String objectField, String field) {
        return node == null ? "" : text(node.path(objectField), field);
    }

    /** 字符串数组字段 → 列表（忽略空值）。 */
    protected static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (item != null && !item.isNull() && notBlank(item.asText(""))) {
                    values.add(item.asText(""));
                }
            }
        }
        return values;
    }

    /** 统计 unified diff 的增删行数（排除 +++ / --- 文件头）。返回 {@code [additions, deletions]}。 */
    protected static int[] diffStat(String diff) {
        int additions = 0;
        int deletions = 0;
        if (diff == null || diff.isEmpty()) {
            return new int[]{additions, deletions};
        }
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        return new int[]{additions, deletions};
    }

    /** 路径过滤：未给关键字返回 true；否则新旧路径任一包含关键字即可（忽略大小写）。 */
    protected static boolean matchesPathFilter(String newPath, String oldPath, String filter) {
        if (!notBlank(filter)) {
            return true;
        }
        String keyword = filter.trim().toLowerCase(Locale.ROOT);
        String newer = newPath == null ? "" : newPath.toLowerCase(Locale.ROOT);
        String older = oldPath == null ? "" : oldPath.toLowerCase(Locale.ROOT);
        return newer.contains(keyword) || older.contains(keyword);
    }

    /** GitLab 文件改动节点（commit diff / compare / MR changes 同构）→ 精简输出结构。 */
    protected static ObjectNode toDiffFile(JsonNode change, int[] stat, boolean includeDiff, int maxDiffChars) {
        ObjectNode file = JsonUtil.newObject();
        String newPath = text(change, "new_path");
        String oldPath = text(change, "old_path");
        file.put("newPath", newPath);
        if (notBlank(oldPath) && !oldPath.equals(newPath)) {
            file.put("oldPath", oldPath);
        }
        if (change.path("new_file").asBoolean(false)) {
            file.put("newFile", true);
        }
        if (change.path("deleted_file").asBoolean(false)) {
            file.put("deletedFile", true);
        }
        if (change.path("renamed_file").asBoolean(false)) {
            file.put("renamedFile", true);
        }
        file.put("additions", stat[0]);
        file.put("deletions", stat[1]);
        if (includeDiff) {
            String diff = text(change, "diff");
            file.put("diff", diff.isEmpty() ? "" : truncate(diff, maxDiffChars));
        }
        return file;
    }
    protected static ObjectNode toCountNode(Map<String, Integer> counts) {
        ObjectNode node = JsonUtil.newObject();
        counts.forEach(node::put);
        return node;
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }
}