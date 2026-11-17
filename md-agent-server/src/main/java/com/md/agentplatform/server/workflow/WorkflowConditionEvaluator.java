package com.md.agentplatform.server.workflow;

import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分支表达式求值器 —— {@code condition} 节点与边上的 {@code condition} 都用它。
 *
 * <h2>一、为什么不是 CEL</h2>
 * 官方用的是 CEL（受限表达式，明确"不能当任意脚本执行器"）。本项目先不引它，原因只有一个：
 * <b>引入一门表达式语言的运行时，代价不在解析，而在运维</b> —— 多一个依赖要跟版本、
 * 多一套错误信息要翻译成页面能读的中文、多一份"哪些函数能用"的清单要维护。
 * 而第一批真正需要的判断只占 CEL 能力的一小角（比较 + 逻辑组合 + 取值）。
 *
 * <p>所以这里实现一个<b>明确受限的子集</b>，并把"换成 CEL"的成本压到一处：
 * 引擎与校验器只调用本类的两个静态方法（{@link #evaluate} / {@link #validate}），
 * 将来接 CEL 时替换的是本文件的实现，调用方一行都不用动。
 * 取舍要看清：<b>本类支持的语法是硬约束，页面上的提示必须与它完全一致</b>，
 * 否则用户照着 CEL 文档写出一个本类不认的表达式，只会得到一句"语法错误"。
 *
 * <h2>二、支持的语法（这就是全部，没有隐藏能力）</h2>
 * <pre>
 * 表达式   := 或
 * 或       := 与 ( "||" 与 )*
 * 与       := 比较 ( "&amp;&amp;" 比较 )*
 * 比较     := 一元 ( ("==" | "!=" | "&gt;=" | "&lt;=" | "&gt;" | "&lt;") 一元 )?
 * 一元     := "!" 一元 | 原子
 * 原子     := 字面量 | 路径 | "(" 表达式 ")"
 * 字面量   := 数字 | 字符串("..." 或 '...') | true | false | null
 * 路径     := 标识符 ( "." 标识符 )*
 * </pre>
 * 逻辑运算<b>短路</b>，与 Java 一致。没有函数调用、没有算数、没有下标 —— 需要它们时说明
 * 这段判断已经复杂到该拆成两个节点了。
 *
 * <h2>三、求值作用域：可用的四个根名字</h2>
 * | 路径 | 含义 |
 * | --- | --- |
 * | {@code input.<k>} | 本次运行的输入（页面提交的 JSON），也写作 {@code run.input.<k>} |
 * | {@code run.input.<k>} | 同上，完整写法 |
 * | {@code run.variables.<k>} | 运行期变量（第一批恒为空表，为后续批次留位） |
 * | {@code node.<key>.text} | 某个前序 agent 节点输出的最终文本 |
 * | {@code node.<key>.state} | 该节点状态（SUCCEEDED / FAILED / SKIPPED …） |
 * | {@code node.<key>.type} | 该节点类型 |
 *
 * <p><b>取不到的值一律是 null，不报错</b>。这一条是刻意的：分支表达式最常见的写法就是
 * 问"某个节点有没有产出"，而它上游可能是被跳过的分支。若把"取不到"当错误，
 * 用户就必须为每个分支写两层判断。null 的真值见 {@link #truthy}，
 * 与 null 的比较见 {@link #compare}（只有 {@code !=} 会为真）。
 *
 * <h2>四、比较语义里最容易踩的两处</h2>
 * <ol>
 *   <li><b>数字按数值比，不按字符串比</b>：{@code input.age > 18} 里 {@code 18} 与
 *       {@code "18"} 是同一个东西（页面提交的 JSON 里数字可能带引号）。两边都是数字时
 *       走 double 比较，否则退化成字符串比较 —— 这样 {@code "10" > "9"} 不会因为
 *       字典序给出错误答案。</li>
 *   <li><b>大小与相等都要类型可归约</b>：{@code "abc" > 1} 会<b>抛错</b>而不是默默为 false。
 *       默默为 false 会让"分支永远不走"变成一个没有报错的谜题；抛错会在节点上留下
 *       一条失败记录，页面上直接能看到是哪条边、比的是什么。</li>
 * </ol>
 */
public final class WorkflowConditionEvaluator {

    /** 允许出现在表达式里的根名字；写别的名字不是错误，只是永远取到 null。 */
    public static final String ROOT_INPUT = "input";
    public static final String ROOT_RUN = "run";
    public static final String ROOT_NODE = "node";

    private WorkflowConditionEvaluator() {
    }

    /**
     * 求值。
     *
     * @param expression 表达式原文；空串按"语法错误"处理，而不是默认 true ——
     *                   条件节点上的空表达式一定是漏填，静默放行会让人以为分支生效了
     * @param scope      求值作用域，结构见类注释第三节
     * @return 表达式的真值；调用方通常是"表达式为真才走这条边"
     * @throws ControlPlaneValidationException 语法错误，或比较的两边无法归约成同一类
     */
    public static boolean evaluate(String expression, Map<String, Object> scope) {
        Expr ast = parse(expression);
        Object value = ast.eval(scope == null ? Map.of() : scope);
        return truthy(value);
    }

    /**
     * 只做语法检查，不求值。
     *
     * <p>走的是与 {@link #evaluate} 完全相同的解析器，所以"校验通过"与"能求值"是同一件事，
     * 不存在"校验说没问题、跑起来才发现语法错"的缝隙。发布前必须调它 ——
     * 表达式写错在工作流定义里是不会立刻暴露的，只有走到那个节点才失败。
     */
    public static void validate(String expression) {
        parse(expression);
    }

    // ==================================================================================
    // 解析：表达式原文 → AST
    // ==================================================================================

    /** 解析入口；所有失败都收敛成一条带位置的说明。 */
    private static Expr parse(String expression) {
        String source = expression == null ? "" : expression.trim();
        if (source.isEmpty()) {
            throw new ControlPlaneValidationException("分支表达式为空");
        }
        List<Token> tokens = tokenize(source);
        Parser parser = new Parser(tokens, source);
        Expr ast = parser.parseOr();
        parser.expectEnd();
        return ast;
    }

    /** 记号。{@code pos} 只用于报错时指出位置，不参与求值。 */
    private record Token(Type type, String text, int pos) {
        enum Type { IDENT, NUMBER, STRING, TRUE, FALSE, NULL, EQ, NE, GT, GE, LT, LE, AND, OR, NOT, LPAREN, RPAREN, DOT, END }
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (Character.isLetter(c) || c == '_') {
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
                String word = source.substring(start, i);
                tokens.add(new Token(switch (word.toLowerCase(Locale.ROOT)) {
                    case "true" -> Token.Type.TRUE;
                    case "false" -> Token.Type.FALSE;
                    case "null" -> Token.Type.NULL;
                    default -> Token.Type.IDENT;
                }, word, start));
                continue;
            }
            if (Character.isDigit(c)) {
                i = scanNumber(source, i);
                tokens.add(new Token(Token.Type.NUMBER, source.substring(start, i), start));
                continue;
            }
            if (c == '"' || c == '\'') {
                i = scanString(source, i, c);
                // 记号的 text 去掉首尾引号：求值阶段只关心内容
                tokens.add(new Token(Token.Type.STRING, source.substring(start + 1, i - 1), start));
                continue;
            }
            switch (c) {
                case '(' -> { tokens.add(new Token(Token.Type.LPAREN, "(", i++)); }
                case ')' -> { tokens.add(new Token(Token.Type.RPAREN, ")", i++)); }
                case '.' -> { tokens.add(new Token(Token.Type.DOT, ".", i++)); }
                case '!' -> {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '=') {
                        tokens.add(new Token(Token.Type.NE, "!=", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(Token.Type.NOT, "!", i++));
                    }
                }
                case '=' -> {
                    // 单个 = 是最常见的手误，单独给一句能直接照做的提示
                    if (i + 1 < source.length() && source.charAt(i + 1) == '=') {
                        tokens.add(new Token(Token.Type.EQ, "==", i));
                        i += 2;
                    } else {
                        throw syntaxError(source, i, "赋值号 = 不是比较运算符，请用 ==");
                    }
                }
                case '>' -> {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '=') {
                        tokens.add(new Token(Token.Type.GE, ">=", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(Token.Type.GT, ">", i++));
                    }
                }
                case '<' -> {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '=') {
                        tokens.add(new Token(Token.Type.LE, "<=", i));
                        i += 2;
                    } else {
                        tokens.add(new Token(Token.Type.LT, "<", i++));
                    }
                }
                case '&' -> {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '&') {
                        tokens.add(new Token(Token.Type.AND, "&&", i));
                        i += 2;
                    } else {
                        throw syntaxError(source, i, "逻辑与要写两个 &（&&）");
                    }
                }
                case '|' -> {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '|') {
                        tokens.add(new Token(Token.Type.OR, "||", i));
                        i += 2;
                    } else {
                        throw syntaxError(source, i, "逻辑或要写两个 |（||）");
                    }
                }
                default -> throw syntaxError(source, i, "不支持的字符 " + c);
            }
        }
        tokens.add(new Token(Token.Type.END, "", source.length()));
        return tokens;
    }

    private static int scanNumber(String source, int from) {
        int i = from;
        boolean dotSeen = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isDigit(c)) {
                i++;
            } else if (c == '.' && !dotSeen) {
                dotSeen = true;
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** 扫描字符串字面量，返回结束引号之后的下标。只支持反斜杠转义引号与反斜杠本身。 */
    private static int scanString(String source, int from, char quote) {
        int i = from + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        throw syntaxError(source, from, "字符串没有闭合的引号");
    }

    /** 报错信息固定带上位置与原文：页面直接展示，人能一眼看到是哪里。 */
    private static ControlPlaneValidationException syntaxError(String source, int pos, String reason) {
        return new ControlPlaneValidationException(
                "分支表达式语法错误（第 " + (pos + 1) + " 个字符处）：" + reason + " ｜ 表达式：" + source);
    }

    // ==================================================================================
    // AST
    // ==================================================================================

    /** 一个可求值的表达式节点。用函数式接口而不是一堆子类：求值逻辑都只有一两行。 */
    private interface Expr {
        Object eval(Map<String, Object> scope);
    }

    /** 递归下降解析器。字段 {@code at} 是当前记号下标，一路右移。 */
    private static final class Parser {

        private final List<Token> tokens;
        /** 表达式原文，只用于报错文案 —— 校验/执行失败时页面要能回显用户写的那一行。 */
        private final String source;
        private int at;

        Parser(List<Token> tokens, String source) {
            this.tokens = tokens;
            this.source = source;
        }

        Expr parseOr() {
            Expr left = parseAnd();
            while (peek(Token.Type.OR)) {
                next();
                Expr right = parseAnd();
                Expr lhs = left;
                left = scope -> truthy(lhs.eval(scope)) || truthy(right.eval(scope));
            }
            return left;
        }

        Expr parseAnd() {
            Expr left = parseCompare();
            while (peek(Token.Type.AND)) {
                next();
                Expr right = parseCompare();
                Expr lhs = left;
                left = scope -> truthy(lhs.eval(scope)) && truthy(right.eval(scope));
            }
            return left;
        }

        Expr parseCompare() {
            Expr left = parseUnary();
            Token.Type type = current().type();
            if (type != Token.Type.EQ && type != Token.Type.NE && type != Token.Type.GT
                    && type != Token.Type.GE && type != Token.Type.LT && type != Token.Type.LE) {
                return left;
            }
            next();
            Expr right = parseUnary();
            Expr lhs = left;
            return scope -> compare(type, lhs.eval(scope), right.eval(scope));
        }

        Expr parseUnary() {
            if (peek(Token.Type.NOT)) {
                next();
                Expr inner = parseUnary();
                return scope -> !truthy(inner.eval(scope));
            }
            return parsePrimary();
        }

        Expr parsePrimary() {
            Token token = current();
            switch (token.type()) {
                case LPAREN -> {
                    next();
                    Expr inner = parseOr();
                    if (!peek(Token.Type.RPAREN)) {
                        throw syntaxError(source, token.pos(), "括号没有闭合");
                    }
                    next();
                    return inner;
                }
                case STRING -> {
                    next();
                    return scope -> token.text();
                }
                case NUMBER -> {
                    next();
                    double value = parseNumber(token);
                    return scope -> value;
                }
                case TRUE -> {
                    next();
                    return scope -> Boolean.TRUE;
                }
                case FALSE -> {
                    next();
                    return scope -> Boolean.FALSE;
                }
                case NULL -> {
                    next();
                    return scope -> null;
                }
                case IDENT -> {
                    List<String> path = new ArrayList<>();
                    path.add(token.text());
                    next();
                    while (peek(Token.Type.DOT)) {
                        next();
                        if (!peek(Token.Type.IDENT)) {
                            throw syntaxError(source, current().pos(), "点号后面要跟一个字段名");
                        }
                        path.add(current().text());
                        next();
                    }
                    return scope -> resolve(scope, path);
                }
                default -> throw syntaxError(source, token.pos(), "这里需要一个值（字段、字面量或括号）");
            }
        }

        void expectEnd() {
            if (!peek(Token.Type.END)) {
                throw syntaxError(source, current().pos(), "表达式在 " + current().text() + " 之后多出了内容");
            }
        }

        private Token current() {
            return tokens.get(at);
        }

        private boolean peek(Token.Type type) {
            return current().type() == type;
        }

        private void next() {
            if (at < tokens.size() - 1) {
                at++;
            }
        }

        /** 数字记号 → double；解析失败时说明是数字格式问题（例如 {code 1.2.3}）。 */
        private double parseNumber(Token token) {
            try {
                return Double.parseDouble(token.text());
            } catch (NumberFormatException e) {
                throw syntaxError(source, token.pos(), "数字格式不对：" + token.text());
            }
        }
    }

    // ==================================================================================
    // 求值辅助
    // ==================================================================================

    /**
     * 按路径逐层取值；任何一层取不到都返回 null（不报错，理由见类注释第三节）。
     *
     * <p>路径里最后一段若是 {@code text}，且上一层是字符串，直接返回字符串本身 ——
     * 这样 {@code node.x.text} 在"某个 agent 节点"这个最常见场景下总是有值可比较。
     */
    private static Object resolve(Map<String, Object> scope, List<String> path) {
        Object current = scope;
        for (String segment : path) {
            if (current == null) {
                // 中途断了：后面几段再走也只会一直返回 null，直接收口
                return null;
            }
            current = step(current, segment);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Object step(Object current, String segment) {
        if (current instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).get(segment);
        }
        // 节点输出的便捷写法：agent 节点的产物是纯文本，页面/用户自然想写 node.x.text
        if ("text".equals(segment)) {
            return current;
        }
        return null;
    }

    /** 真值规则：null / false / 0 / 空串 / 空集合 / "false" 为假，其余为真。 */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (value instanceof CharSequence s) {
            String text = s.toString().trim();
            return !text.isEmpty() && !"false".equalsIgnoreCase(text);
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof Iterable<?> it) {
            return it.iterator().hasNext();
        }
        return true;
    }

    /**
     * 比较两个值。
     *
     * <p>相等比较永远可用（不同类型只是不相等）；大小比较要求两边都能归约成数字，
     * 否则抛错 —— 理由见类注释第四节。
     */
    private static boolean compare(Token.Type op, Object left, Object right) {
        if (op == Token.Type.EQ || op == Token.Type.NE) {
            boolean equal = valuesEqual(left, right);
            return op == Token.Type.EQ ? equal : !equal;
        }
        Double a = asNumber(left);
        Double b = asNumber(right);
        if (a == null || b == null) {
            throw new ControlPlaneValidationException(
                    "分支表达式的大小比较要求两边都是数字，实际是 " + describe(left) + " 与 " + describe(right));
        }
        int cmp = Double.compare(a, b);
        return switch (op) {
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            default -> throw new IllegalStateException("不可能到达的比较运算符: " + op);
        };
    }

    /**
     * 相等判定：两边都是数字时按数值比（{@code 18} 与 {@code "18"} 相等），
     * 否则按 {@code Objects.equals} 比。null 与任何非 null 都不相等。
     */
    private static boolean valuesEqual(Object left, Object right) {
        Double a = asNumber(left);
        Double b = asNumber(right);
        if (a != null && b != null) {
            return a.doubleValue() == b.doubleValue();
        }
        if (left == null || right == null) {
            return left == right;
        }
        return left.equals(right);
    }

    /**
     * 尝试把值看成数字。
     *
     * <p>数字字面量、{@link Number}、能完整解析成数字的字符串三者都算 ——
     * 最后一条是为了让页面提交的 JSON 里 {@code "18"} 这种带引号的数字也能比较。
     * 解析不成功返回 null，由调用方决定是"按字符串比"还是"报错"。
     */
    private static Double asNumber(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof CharSequence s) {
            String text = s.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 报错时把实际值写成能读的样子。字符串加引号，好与数字区分。 */
    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence s) {
            return "\"" + s + "\"";
        }
        return String.valueOf(value);
    }
}