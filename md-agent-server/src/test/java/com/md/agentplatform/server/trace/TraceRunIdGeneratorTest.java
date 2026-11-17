package com.md.agentplatform.server.trace;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * runId 生成器的契约测试。
 *
 * <p>为什么要专门测它：runId 是观测数据的唯一入口键，它的两个性质会被下游直接消费——
 * <ol>
 *   <li><b>字典序等于时间序</b>：读路径用 {@code ORDER BY run_id DESC} 取"最近的 run"，</li>
 *   <li><b>不重复</b>：库表 run_id 唯一键的前提。</li>
 * </ol>
 * 这两条都属于"看起来显然、实际全靠实现保证"的性质。破掉之后不会抛异常、不会编译报错，
 * 只会让页面上的顺序悄悄变乱或丢数据——正是最该用测试钉死的那类不变量。
 */
class TraceRunIdGeneratorTest {

    /**
     * 期望格式：8 位 base36 时间前缀 + "-" + 6 位 base36 随机后缀。
     *
     * <p>这里写死 8 位是刻意的：定长是"字典序等于时间序"的前提（见生成器类的注释）。
     * 这条断言同时起到了"提醒 2059 年的人来做迁移"的作用——它一旦变红，说明前缀长度变了，
     * 下游所有依赖排序的地方都要重新评估。
     */
    private static final String EXPECTED_FORMAT = "[0-9a-z]{8}-[0-9a-z]{6}";

    @Test
    void matchesExpectedFormat() {
        String runId = TraceRunIdGenerator.next();

        assertThat(runId).matches(EXPECTED_FORMAT);
        // 前缀解 base36 后必须能还原成一个合理的毫秒时间戳（不是随便凑的字符串）
        long millis = Long.parseLong(runId.substring(0, runId.indexOf('-')), 36);
        assertThat(millis).isBetween(1_700_000_000_000L, 2_600_000_000_000L);
    }

    @Test
    void timePrefixIsMonotonicSoLexicalOrderEqualsTimeOrder() {
        List<String> ids = IntStream.range(0, 10_000)
                .mapToObj(i -> TraceRunIdGenerator.next())
                .toList();

        // 字典序非降 —— 这是"按 runId 排序即按时间排序"的直接证据
        assertThat(ids).isSorted();
    }

    @Test
    void generatesUniqueIds() {
        int count = 200_000;
        Set<String> ids = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            ids.add(TraceRunIdGenerator.next());
        }

        assertThat(ids).hasSize(count);
    }

    @Test
    void generatesUniqueIdsUnderConcurrency() {
        Set<String> ids = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 8).parallel().forEach(worker -> {
            for (int i = 0; i < 10_000; i++) {
                ids.add(TraceRunIdGenerator.next());
            }
        });

        // 单调前缀靠 CAS 保证，随机后缀靠 ThreadLocalRandom 保证线程独立：
        // 这里要验的正是"两类保证叠加之后仍然不重号"。
        assertThat(ids).hasSize(80_000);
    }

    // ==================== 调用方自带的 runId 能不能用 ====================

    /**
     * 自带的 id 与生成器自己产的 id 形状不同（没有时间前缀），所以不能拿
     * EXPECTED_FORMAT 当判据 —— 这里测的是"字符集与长度是否落在安全范围内"。
     */
    @Test
    void acceptsCallerSuppliedIdsThatAreSafeInUrlLogAndDatabase() {
        assertThat(TraceRunIdGenerator.isWellFormed("t3k9f2a1-x7b2q9")).isTrue();
        assertThat(TraceRunIdGenerator.isWellFormed("manual-20260921-checkout")).isTrue();
        assertThat(TraceRunIdGenerator.isWellFormed("A_b.c-1")).isTrue();
    }

    /**
     * 这三种值要在三个落点上都安全：URL 路径、日志行、数据库主键。
     * 放开它们等于允许把 URL 拆断（问号 / 井号）、把一条日志栅成两行（换行 / 空格）。
     */
    @Test
    void rejectsCharactersThatWouldBreakUrlOrLogLines() {
        assertThat(TraceRunIdGenerator.isWellFormed("a b")).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("a\nb")).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("a?b")).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("a#b")).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("a/b")).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("a:b")).isFalse();
    }

    @Test
    void rejectsBlankAndOverlongIds() {
        assertThat(TraceRunIdGenerator.isWellFormed(null)).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("")).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("a".repeat(TraceRunIdGenerator.MAX_LENGTH))).isTrue();
        assertThat(TraceRunIdGenerator.isWellFormed(
                "a".repeat(TraceRunIdGenerator.MAX_LENGTH + 1))).isFalse();
    }

    /**
     * 点号是允许字符，但"全是点"要单独拦：{@code /api/traces/..} 会被路径规范化掉、
     * 根本路由不到详情接口，作为文件名又指向上级目录。
     */
    @Test
    void rejectsDotsOnlyIds() {
        assertThat(TraceRunIdGenerator.isWellFormed(".")).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("..")).isFalse();
        assertThat(TraceRunIdGenerator.isWellFormed("....")).isFalse();
    }
}
