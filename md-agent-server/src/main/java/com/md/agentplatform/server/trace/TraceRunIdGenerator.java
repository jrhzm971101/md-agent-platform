package com.md.agentplatform.server.trace;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调用链路 runId 生成器。
 *
 * <h2>为什么不用 UUID</h2>
 * 旧实现是 {@code UUID.randomUUID().toString().substring(0, 8)}，有两个毛病：
 * <ol>
 *   <li><b>不可排序</b>：随机十六进制串的字典序与时间毫无关系。落库后想取"最近 N 次 run"，
 *       就不得不额外依赖 {@code started_at} 列加索引，而 runId 作为主键白白浪费掉自带的排序能力。</li>
 *   <li><b>不可读</b>：日志里出现 {@code 5302626f} 看不出这是什么时候跑的，排查时得回库查时间列。</li>
 * </ol>
 *
 * <h2>本实现的格式</h2>
 * <pre>
 *   {base36(epochMillis)}-{6 位 base36 随机}
 *   例：t3k9f2a1-x7b2q9
 * </pre>
 *
 * <p><b>时间前缀定长 8 位</b>：36^8 ≈ 2.82e12，覆盖到 2059 年。定长这一点不是巧合而是前提——
 * 变长会让字典序不再等于数值序（"1abc" &lt; "zzzz" 但数值上更大），整个"按 runId 排序"
 * 的设计就塌了。所以 2059 年之后要么扩位并做一次性数据迁移，要么换方案，不能直接放开长度。
 *
 * <p>由此得到最关键的性质：<b>字典序等于时间序</b>，{@code ORDER BY run_id DESC} 就是
 * "最近的 run 在前"，读路径不需要额外的排序列。
 *
 * <h2>随机后缀的作用与边界</h2>
 * 6 位 base36 有 36^6 ≈ 21.7 亿种取值。同一毫秒内并发产生两个 run 的碰撞概率约为 1/21.7 亿，
 * 工程上可忽略；但"可忽略"不等于"不可能"，所以库表仍然对 run_id 建唯一键兜底。
 * 真撞了表现为插入失败——观测数据丢一条不致命，也不会污染已有记录。
 *
 * <h2>为什么不用自增序号</h2>
 * 序号在单进程内能保证唯一，但多实例部署必撞；而且它退化了"从 id 就能读出时间"这个性质。
 * 现在是单实例，然而没必要把这条路堵死。
 */
public final class TraceRunIdGenerator {

    /**
     * base36 字母表：0-9 与 a-z。
     *
     * <p>刻意全部小写：MySQL 默认 collation 大小写不敏感，若混用大小写，
     * {@code run_id} 的唯一键比较与 Java 侧的字符串比较会给出不同结论，徒增歧义。
     */
    private static final char[] BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    /** 随机后缀长度。取 6 是"碰撞概率足够低"与"id 别太长"之间的折中。 */
    private static final int RANDOM_LENGTH = 6;

    /**
     * 上一次使用的时间戳，用来保证时间前缀单调非降。
     *
     * <p>为什么需要它：{@code System.currentTimeMillis()} 受 NTP 校时影响<em>可以回拨</em>。
     * 一旦回拨，新 run 的 id 会排到旧 run 前面，"最近 N 次"的语义就错了，而且错得很隐蔽。
     * 这里做一道兜底，让前缀永不倒退——宁可时间戳略微偏大，也不要顺序错乱。
     */
    private static final AtomicLong LAST_MILLIS = new AtomicLong();

    /** 工具类，禁止实例化。 */
    private TraceRunIdGenerator() {
    }

    /**
     * 调用方自带的 runId 允许的最大长度。
     *
     * <p>取 64：本生成器给出的是 15 位，留出余量给"带业务前缀的人工 id"
     * （例如 {@code manual-20260921-checkout}），同时不至于让它在 URL 与日志里占太宽。
     */
    public static final int MAX_LENGTH = 64;

    /**
     * 判断调用方自带的 runId 能不能用。
     *
     * <p><b>为什么连形状也要管</b>：这个值会同时出现在三个地方 —— URL 路径
     * （{@code /api/traces/{runId}}）、日志行、数据库主键。放开字符集就等于允许
     * 问号、井号、空格、换行混进来：前两个会把 URL 拆断，后两个能把一条日志栅成两行。
     * 所以这里只收下"在三个地方都安全"的字符：字母、数字、连字符、下划线、点。
     *
     * <p><b>为什么额外挡掉"全是点"</b>：点号完全符合上面的字符集，但 {@code .} 与
     * {@code ..} 作为 URL 路径段会被规范化掉（{@code /api/traces/..} 根本路由不到本接口），
     * 作为文件名又指向上级目录。这是唯一一条"过了字符集仍然危险"的值，单独拦下来。
     *
     * <p>返回 false 时调用方不要静默改写：本方法只回答能不能用，用不了就交给
     * {@link #next()} 现生成，并在日志里说明放弃了这个值 —— 否则"我传的 id 怎么没了"
     * 会变成一桩无头案。
     */
    public static boolean isWellFormed(String runId) {
        if (runId == null || runId.isEmpty() || runId.length() > MAX_LENGTH) {
            return false;
        }
        boolean allDots = true;
        for (int i = 0; i < runId.length(); i++) {
            char c = runId.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!safe) {
                return false;
            }
            if (c != '.') {
                allDots = false;
            }
        }
        return !allDots;
    }

    /**
     * 生成一个新的 runId。
     *
     * <p>无状态且线程安全：随机源用 {@link ThreadLocalRandom}（避免共享 {@code Random} 的竞争），
     * 时间前缀用 CAS 保证单调。
     */
    public static String next() {
        long millis = monotonicMillis();
        StringBuilder id = new StringBuilder(16);
        id.append(Long.toString(millis, 36));
        id.append('-');
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            id.append(BASE36[random.nextInt(BASE36.length)]);
        }
        return id.toString();
    }

    /**
     * 取一个"单调非降"的毫秒时间戳。
     *
     * <p>读当前时间，若未超过上次返回值，就在上次基础上 +1。CAS 循环保证并发下也不会退回去。
     *
     * <p>代价说明：同一毫秒内连续调用会拿到 now、now+1、now+2……，即前缀可能略微超前于真实时间。
     * run 的产生频率远低于此，这个偏移可以忽略；换来的是"顺序永不出错"这个更值钱的性质。
     */
    private static long monotonicMillis() {
        while (true) {
            long last = LAST_MILLIS.get();
            long candidate = Math.max(System.currentTimeMillis(), last + 1);
            if (LAST_MILLIS.compareAndSet(last, candidate)) {
                return candidate;
            }
        }
    }
}
