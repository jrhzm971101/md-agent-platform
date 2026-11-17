package com.md.agentplatform.server.workflow;

import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分支表达式求值器的单测。
 *
 * <p>这个类是"页面上的分支为什么没走"这类问题的唯一归因点，所以每条规则都钉一个用例。
 * 测试<b>不连库、不起 Spring</b>：它是纯函数，入参是表达式与作用域。
 *
 * <p>用例分四组，对应类注释里的四节：语法（含错误信息是否可照做）、取值与 null 传播、
 * 比较语义、真值规则。
 */
class WorkflowConditionEvaluatorTest {

    /** 造一个与引擎里形状一致的作用域：input / run / node 三个根。 */
    private static Map<String, Object> scope(Map<String, Object> input, Map<String, Object> nodes) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", input);
        scope.put("run", Map.of("input", input, "variables", Map.of()));
        scope.put("node", nodes);
        return scope;
    }

    private static boolean eval(String expression, Map<String, Object> scope) {
        return WorkflowConditionEvaluator.evaluate(expression, scope);
    }

    // ==================== 语法 ====================

    @Test
    void 逻辑优先级与括号() {
        // && 比 || 紧：false && false 先算，整体 true
        assertTrue(eval("true || false && false", Map.of()));
        // 括号能改优先级
        assertFalse(eval("(true || false) && false", Map.of()));
        assertTrue(eval("(true || false) && true", Map.of()));
    }

    @Test
    void 非运算() {
        assertTrue(eval("!false", Map.of()));
        assertFalse(eval("!true", Map.of()));
        // 双重否定回到原值
        assertTrue(eval("!!true", Map.of()));
        // ! 作用于"取值结果"而不是语法：取不到是 null，null 为假，取反为真
        assertTrue(eval("!input.missing", scope(Map.of(), Map.of())));
    }

    @Test
    void 短路求值() {
        // 右侧会抛错（大小比较两边非数字），但短路后根本不该被求值。
        // 这条很重要：它让用户能写出"先判有没有、再比大小"的表达式。
        assertTrue(eval("true || \"abc\" > 1", Map.of()));
        assertFalse(eval("false && \"abc\" > 1", Map.of()));
    }

    @Test
    void 语法错误都带可照做的提示() {
        // 单个 = 是最常见的手误，提示必须直接说"用 =="
        ControlPlaneValidationException e1 = assertThrows(ControlPlaneValidationException.class,
                () -> eval("input.x = 1", Map.of()));
        assertTrue(e1.getMessage().contains("=="), "提示里应指明要用 ==：" + e1.getMessage());

        assertThrows(ControlPlaneValidationException.class, () -> eval("(input.x == 1", Map.of()),
                "括号没闭合要报错");
        assertThrows(ControlPlaneValidationException.class, () -> eval("input.x == 1 extra", Map.of()),
                "表达式后面多出内容要报错");
        assertThrows(ControlPlaneValidationException.class, () -> eval("input. == 1", Map.of()),
                "点号后面没跟字段名要报错");
        assertThrows(ControlPlaneValidationException.class, () -> eval("== 1", Map.of()),
                "以运算符开头要报错");
    }

    @Test
    void 空表达式按语法错误处理而不是默认放行() {
        // 空表达式一定是漏填。若默认 true，用户会以为"分支生效了"，而真相是"没写条件"。
        assertThrows(ControlPlaneValidationException.class, () -> eval("", Map.of()));
        assertThrows(ControlPlaneValidationException.class, () -> eval("   ", Map.of()));
        assertThrows(ControlPlaneValidationException.class, () -> eval(null, Map.of()));
    }

    @Test
    void validate只查语法不求值() {
        assertDoesNotThrow(() -> WorkflowConditionEvaluator.validate("input.age > 18 && node.up.text != \"\""));
        // 大小比较在求值时会炸，但语法是合法的 —— 校验必须放行（那时还不知道值长什么样）
        assertDoesNotThrow(() -> WorkflowConditionEvaluator.validate("\"abc\" > 1"));
        assertThrows(ControlPlaneValidationException.class,
                () -> WorkflowConditionEvaluator.validate("input.age >"));
    }

    // ==================== 取值与 null 传播 ====================

    @Test
    void 取不到的值是null而不是报错() {
        Map<String, Object> scope = scope(Map.of("age", 20), Map.of());
        assertFalse(eval("input.missing", scope));
        assertFalse(eval("input.missing == \"a\"", scope));
        assertTrue(eval("input.missing == null", scope));
        assertFalse(eval("input.missing != null", scope));
        // 不存在的根名字同理：不是错误，只是永远 null
        assertTrue(eval("nothing.at.all == null", scope));
    }

    @Test
    void 中间层不是对象时后续取值收敛成null() {
        // input.age 是数字，再 .x 就取不到了 —— 不该抛错
        Map<String, Object> scope = scope(Map.of("age", 20), Map.of());
        assertTrue(eval("input.age.x == null", scope));
    }

    @Test
    void run_variables第一批恒为空() {
        assertTrue(eval("run.variables.anything == null", scope(Map.of(), Map.of())));
    }

    @Test
    void 节点输出的文本可以两种写法取到() {
        Map<String, Object> nodes = Map.of(
                "step1", Map.of("text", "已通过", "state", "SUCCEEDED", "type", "AGENT"));
        Map<String, Object> scope = scope(Map.of(), nodes);

        assertTrue(eval("node.step1.text == \"已通过\"", scope));
        assertTrue(eval("node.step1.state == \"SUCCEEDED\"", scope));
        assertTrue(eval("node.step1.type == \"AGENT\"", scope));
        // 有产出的节点按真值规则为真
        assertTrue(eval("node.step1.text", scope));
    }

    @Test
    void 被跳过的节点用空串表达没走这条分支() {
        // 引擎在 skipNode 里把被跳过的节点写成 text=""、state=SKIPPED（而不是干脆不放进去），
        // 就是为了让"这条分支没走"能用一句普通的判断表达出来。
        Map<String, Object> nodes = Map.of("skipped", Map.of("text", "", "state", "SKIPPED", "type", "AGENT"));
        Map<String, Object> scope = scope(Map.of(), nodes);

        assertFalse(eval("node.skipped.text", scope), "空串为假");
        assertTrue(eval("node.skipped.text == \"\"", scope));
        assertTrue(eval("node.skipped.state == \"SKIPPED\"", scope));
    }

    @Test
    void 节点本身是字符串时_text写法依然可读() {
        // step() 的特例：路径最后一段是 text 且上一层是字符串，直接返回它本身。
        // 这让 node.x.text 在"节点输出就是一段文本"这个最常见场景下永远有值。
        Map<String, Object> scope = scope(Map.of(), Map.of("step1", "纯文本结果"));
        assertTrue(eval("node.step1.text == \"纯文本结果\"", scope));
    }

    // ==================== 比较语义 ====================

    @Test
    void 数字与带引号的数字相等() {
        // 页面提交的 JSON 里数字可能带引号，两者必须等价，否则用户会写出永远为假的分支
        assertTrue(eval("input.age == 18", scope(Map.of("age", "18"), Map.of())));
        assertTrue(eval("input.age == 18", scope(Map.of("age", 18), Map.of())));
        assertTrue(eval("input.age == \"18\"", scope(Map.of("age", 18), Map.of())));
    }

    @Test
    void 大小比较按数值不按字典序() {
        // 字典序下 "10" < "9"，这里是数字比较，所以 10 > 9 为真
        assertTrue(eval("input.n > 9", scope(Map.of("n", "10"), Map.of())));
        assertTrue(eval("input.n >= 10", scope(Map.of("n", 10), Map.of())));
        assertFalse(eval("input.n < 10", scope(Map.of("n", 10), Map.of())));
        assertTrue(eval("input.n <= 10", scope(Map.of("n", 10.0), Map.of())));
    }

    @Test
    void 大小比较两边不能归约成数字时抛错() {
        // 刻意不默默为 false：那会让"分支永远不走"变成一个没有报错的谜题
        Map<String, Object> scope = scope(Map.of("name", "abc"), Map.of());
        ControlPlaneValidationException e = assertThrows(ControlPlaneValidationException.class,
                () -> eval("input.name > 1", scope));
        assertTrue(e.getMessage().contains("数字"), "提示里应说明两边要是数字：" + e.getMessage());
        assertThrows(ControlPlaneValidationException.class, () -> eval("input.missing > 1", scope));
    }

    @Test
    void 相等比较永远可用_不同类型只是不相等() {
        Map<String, Object> scope = scope(Map.of("name", "abc", "flag", true), Map.of());
        assertTrue(eval("input.name == \"abc\"", scope));
        assertTrue(eval("input.name != \"abd\"", scope));
        assertFalse(eval("input.name == 1", scope));
        assertTrue(eval("input.flag == true", scope));
        // null 与任何非 null 都不相等
        assertFalse(eval("input.missing == \"abc\"", scope));
    }

    @Test
    void 字符串字面量两种引号都认() {
        Map<String, Object> scope = scope(Map.of("name", "abc"), Map.of());
        assertTrue(eval("input.name == 'abc'", scope));
        assertTrue(eval("input.name == \"abc\"", scope));
    }

    // ==================== 真值规则 ====================

    @Test
    void 真值规则() {
        // 11 个键，超过 Map.of 的重载上限（10 对），所以走 ofEntries。
        // 这不是为了凑数：真值规则一共就这几类值（数字 / 字符串 / 集合 / 表 / null），
        // 一次摆全比拆成五个用例更容易看出"哪些为假"是穷尽了。
        Map<String, Object> scope = scope(Map.ofEntries(
                Map.entry("zero", 0),
                Map.entry("one", 1),
                Map.entry("empty", ""),
                Map.entry("blank", "   "),
                Map.entry("falseText", "false"),
                Map.entry("falseTextUpper", "FALSE"),
                Map.entry("text", "ok"),
                Map.entry("emptyList", List.of()),
                Map.entry("list", List.of(1)),
                Map.entry("emptyMap", Map.of()),
                Map.entry("map", Map.of("a", 1))
        ), Map.of());

        assertFalse(eval("input.zero", scope), "0 是假");
        assertTrue(eval("input.one", scope), "非 0 数字是真");
        assertFalse(eval("input.empty", scope), "空串是假");
        assertFalse(eval("input.blank", scope), "纯空白是假");
        assertFalse(eval("input.falseText", scope), "\"false\" 是假（字面量反序列化后的样子）");
        assertFalse(eval("input.falseTextUpper", scope), "\"FALSE\" 同样按假处理，忽略大小写");
        assertTrue(eval("input.text", scope));
        assertFalse(eval("input.emptyList", scope), "空集合是假");
        assertTrue(eval("input.list", scope));
        assertFalse(eval("input.emptyMap", scope), "空表是假");
        assertTrue(eval("input.map", scope));
        assertFalse(eval("input.missing", scope), "null 是假");
    }

    @Test
    void scope为null时不抛空指针() {
        // 引擎在"第一个节点就是 condition"时可能还没有任何节点作用域，
        // 这条路必须是可求值的（全是 null），而不是 NPE。
        assertFalse(WorkflowConditionEvaluator.evaluate("input.a == 1", null));
        assertTrue(WorkflowConditionEvaluator.evaluate("input.a == null", null));
    }
}