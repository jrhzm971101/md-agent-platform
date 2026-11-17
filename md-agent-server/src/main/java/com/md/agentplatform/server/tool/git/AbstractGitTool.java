package com.md.agentplatform.server.tool.git;

import com.md.agentplatform.server.config.GitlabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * Git 工具公共基类。
 *
 * <p>本类只保留与具体存储方式无关的通用能力：日志、配置、路径与字符串处理。
 * 所有仓库访问都通过 GitLab REST API 完成，不再依赖 JGit。
 */
public abstract class AbstractGitTool {

    private static final DateTimeFormatter PARAM_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter PARAM_MINUTE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter PARAM_SECOND_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    protected GitlabProperties gitlabProperties;

    /**
     * 解析 repoDir 对应的本地目录。相对路径只取最后一级目录名并拼到 gitlab.base-dir 下；
     * 绝对路径直接使用。API 工作区的真正解析由 GitlabWorkspaceManager 完成。
     */
    protected Path resolveRepoDir(String repoDir) {
        if (!notBlank(repoDir)) {
            throw new IllegalArgumentException("repoDir 不能为空");
        }
        Path path = Path.of(repoDir.trim());
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        Path leaf = path.getFileName();
        if (leaf == null) {
            throw new IllegalArgumentException("repoDir 缺少目录名: " + repoDir);
        }
        String base = gitlabProperties == null ? "workspace/code" : gitlabProperties.getBaseDir();
        return Path.of(notBlank(base) ? base : "workspace/code", leaf.toString())
                .toAbsolutePath().normalize();
    }

    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 仓库内路径统一为正斜杠，并去掉开头的 /。 */
    protected static String normalizePath(String path) {
        if (!notBlank(path)) {
            return "";
        }
        String value = path.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    protected static String shortHash(String sha) {
        return sha == null ? "" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }

    protected static int parseInt(String value, int defaultValue, int min, int max) {
        if (!notBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是合法整数: " + value);
        }
    }

    protected static boolean parseBool(String value, boolean defaultValue) {
        if (!notBlank(value)) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException("不是合法布尔值: " + value);
    }

    protected static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... [已截断，原长度 " + text.length() + " 字符]";
    }

    /**
     * 解析工具参数中的时间。仅日期时按本地时区整天处理；
     * endOfDay=true 取当天 23:59:59.999，否则取 00:00:00。
     */
    protected static Date parseDate(String text, boolean endOfDay) {
        if (!notBlank(text)) {
            return null;
        }
        String value = text.trim();
        ZoneId zone = ZoneId.systemDefault();
        try {
            LocalDate date = LocalDate.parse(value, PARAM_DATE_FORMAT);
            LocalDateTime time = endOfDay ? date.atTime(23, 59, 59, 999_000_000) : date.atStartOfDay();
            return Date.from(time.atZone(zone).toInstant());
        } catch (DateTimeParseException ignored) {
            // 继续尝试更精确的格式
        }
        try {
            return Date.from(LocalDateTime.parse(value, PARAM_MINUTE_FORMAT)
                    .atZone(zone).toInstant());
        } catch (DateTimeParseException ignored) {
            // 继续尝试秒级格式
        }
        try {
            return Date.from(LocalDateTime.parse(value, PARAM_SECOND_FORMAT)
                    .atZone(zone).toInstant());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "时间格式错误: " + text + "，应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm[:ss]");
        }
    }
}
