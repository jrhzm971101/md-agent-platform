package com.md.agentplatform.server.tool.mysql;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 轻量 SQL 文本分析：只做工具层的安全闸门，不替代 MySQL 自己的语法解析。
 *
 * <p>核心原则：先屏蔽字符串字面量、反引号标识符和注释，再做关键字判断。
 * 否则 {@code SELECT 'update'} 会被误判成写操作，或者注释里的分号会被误判成多语句。
 */
final class SqlText {

    private static final List<String> WRITE_KEYWORDS = List.of(
            "insert", "update", "delete", "replace", "merge",
            "create", "alter", "drop", "truncate", "rename",
            "call", "grant", "revoke");

    private static final List<String> FORBIDDEN_FIRST_KEYWORDS = List.of(
            "shutdown", "kill", "grant", "revoke", "flush", "purge",
            "install", "uninstall", "reset");

    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "load data", "load_file", "into outfile", "into dumpfile",
            "set global", "set persist",
            "create user", "alter user", "drop user",
            "create role", "drop role",
            "install plugin", "uninstall plugin",
            "reset master", "reset replica", "purge binary logs",
            "change master", "change replication",
            "start replica", "stop replica", "start slave", "stop slave");

    private SqlText() {
    }

    /** 是否是工具层可接受的只读语句。WITH 只有在整条语句不含写关键字时才算只读。 */
    static boolean isReadOnly(String sql) {
        String normalized = normalized(sql);
        String keyword = firstKeyword(normalized);
        if (List.of("select", "show", "describe", "desc", "explain").contains(keyword)) {
            return true;
        }
        if ("with".equals(keyword)) {
            return !containsWriteKeyword(normalized);
        }
        return false;
    }

    /** 粗粒度 SQL 类型，仅用于回报给模型，不作为安全判定的唯一依据。 */
    static String classify(String sql) {
        String normalized = normalized(sql);
        String keyword = firstKeyword(normalized);
        return switch (keyword) {
            case "select", "show", "describe", "desc", "explain" -> "QUERY";
            case "with" -> isReadOnly(sql) ? "QUERY" : "DML";
            case "insert", "update", "delete", "replace", "merge" -> "DML";
            case "create", "alter", "drop", "truncate", "rename" -> "DDL";
            case "grant", "revoke" -> "DCL";
            case "call" -> "CALL";
            case "start", "begin", "commit", "rollback", "savepoint", "release" -> "TCL";
            default -> "OTHER";
        };
    }

    /**
     * 是否包含多条语句。尾部一个或多个分号不算多语句；分号后面还有有效内容才算。
     * 字符串、反引号标识符和注释里的分号不会参与判断。
     */
    static boolean hasMultipleStatements(String sql) {
        String masked = mask(sql);
        int lastMeaningful = masked.length() - 1;
        while (lastMeaningful >= 0
                && (Character.isWhitespace(masked.charAt(lastMeaningful))
                || masked.charAt(lastMeaningful) == ';')) {
            lastMeaningful--;
        }
        int firstSemicolon = masked.indexOf(';');
        return firstSemicolon >= 0 && firstSemicolon < lastMeaningful;
    }

    /** 服务器管理类语句：即使开了 allowWrite 也不允许通过业务 Agent 执行。 */
    static boolean containsForbiddenAdmin(String sql) {
        String normalized = normalized(sql);
        if (FORBIDDEN_FIRST_KEYWORDS.contains(firstKeyword(normalized))) {
            return true;
        }
        for (String phrase : FORBIDDEN_PHRASES) {
            if (containsPhrase(normalized, phrase)) {
                return true;
            }
        }
        return false;
    }

    static String quoteIdentifier(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("identifier 不能为空");
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String normalized(String sql) {
        return mask(sql).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean containsWriteKeyword(String normalized) {
        for (String keyword : WRITE_KEYWORDS) {
            if (containsWord(normalized, keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String firstKeyword(String normalized) {
        int index = 0;
        while (index < normalized.length() && normalized.charAt(index) == '(') {
            index++;
        }
        while (index < normalized.length() && Character.isWhitespace(normalized.charAt(index))) {
            index++;
        }
        int end = index;
        while (end < normalized.length() && Character.isLetter(normalized.charAt(end))) {
            end++;
        }
        return normalized.substring(index, end);
    }

    private static boolean containsWord(String normalized, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(normalized).find();
    }

    private static boolean containsPhrase(String normalized, String phrase) {
        String[] words = phrase.split("\\s+");
        StringBuilder regex = new StringBuilder("\\b");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(words[i]));
        }
        regex.append("\\b");
        return Pattern.compile(regex.toString()).matcher(normalized).find();
    }

    /**
     * 把字符串 / 反引号 / 注释替换成空格；保留普通 SQL 文本用于关键字与分号判断。
     */
    private static String mask(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length());
        int state = 0; // 0 normal, 1 single quote, 2 double quote, 3 backtick, 4 line comment, 5 block comment
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (state == 0) {
                if (c == '\'') {
                    state = 1;
                    out.append(' ');
                } else if (c == '"') {
                    state = 2;
                    out.append(' ');
                } else if (c == '`') {
                    state = 3;
                    out.append(' ');
                } else if (c == '-' && next == '-') {
                    state = 4;
                    out.append("  ");
                    i++;
                } else if (c == '#') {
                    state = 4;
                    out.append(' ');
                } else if (c == '/' && next == '*') {
                    state = 5;
                    out.append("  ");
                    i++;
                } else {
                    out.append(c);
                }
            } else if (state == 1) {
                out.append(' ');
                if (c == '\\' && next != '\0') {
                    out.append(' ');
                    i++;
                } else if (c == '\'') {
                    if (next == '\'') {
                        out.append(' ');
                        i++;
                    } else {
                        state = 0;
                    }
                }
            } else if (state == 2) {
                out.append(' ');
                if (c == '\\' && next != '\0') {
                    out.append(' ');
                    i++;
                } else if (c == '"') {
                    if (next == '"') {
                        out.append(' ');
                        i++;
                    } else {
                        state = 0;
                    }
                }
            } else if (state == 3) {
                out.append(' ');
                if (c == '`') {
                    if (next == '`') {
                        out.append(' ');
                        i++;
                    } else {
                        state = 0;
                    }
                }
            } else if (state == 4) {
                if (c == '\n' || c == '\r') {
                    state = 0;
                    out.append(c);
                } else {
                    out.append(' ');
                }
            } else {
                out.append(' ');
                if (c == '*' && next == '/') {
                    out.append(' ');
                    i++;
                    state = 0;
                }
            }
        }
        return out.toString();
    }
}
