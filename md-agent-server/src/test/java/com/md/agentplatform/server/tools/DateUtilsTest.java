package com.md.agentplatform.server.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作日工具测试 —— 全程使用固定的接口快照，不依赖外网。
 *
 * <p>2026-10 是关键用例月：10-01~10-07 为国庆假期（10-01 周四、10-02 周五是工作日被放假），
 * 10-10 周六为节后补班（调休上班）。该月 22 个自然工作日（周一至周五），
 * 扣除 5 个被放假的自然工作日、加上 1 个补班周六，实际工作日 18 天。
 */
class DateUtilsTest {

    /** 摘取自 https://timor.tech/api/holiday/year/2026 的真实返回片段。 */
    private static final String YEAR_2026_JSON = """
            {"code":0,"holiday":{
              "01-01":{"holiday":true,"name":"元旦","wage":3,"date":"2026-01-01","rest":77},
              "01-04":{"holiday":false,"name":"元旦后补班","wage":1,"after":true,"target":"元旦","date":"2026-01-04","rest":1},
              "09-20":{"holiday":false,"name":"中秋节前补班","after":false,"wage":1,"target":"中秋节","date":"2026-09-20","rest":2},
              "10-01":{"holiday":true,"name":"国庆节","wage":3,"date":"2026-10-01","rest":3},
              "10-02":{"holiday":true,"name":"国庆节","wage":3,"date":"2026-10-02","rest":1},
              "10-03":{"holiday":true,"name":"国庆节","wage":3,"date":"2026-10-03","rest":1},
              "10-04":{"holiday":true,"name":"国庆节","wage":2,"date":"2026-10-04","rest":1},
              "10-05":{"holiday":true,"name":"国庆节","wage":2,"date":"2026-10-05","rest":1},
              "10-06":{"holiday":true,"name":"国庆节","wage":2,"date":"2026-10-06","rest":1},
              "10-07":{"holiday":true,"name":"国庆节","wage":2,"date":"2026-10-07","rest":1},
              "10-10":{"holiday":false,"after":true,"wage":1,"name":"国庆节后补班","target":"国庆节","date":"2026-10-10"}
            }}""";

    @BeforeEach
    void useFixedHolidaySnapshot() {
        DateUtils.setHolidayLoader(year -> year == 2026
                ? DateUtils.parseYear(YEAR_2026_JSON, year)
                : Map.of());
    }

    @AfterEach
    void restoreDefaultLoader() {
        DateUtils.setHolidayLoader(null);
    }

    // ==================== 解析 ====================

    @Test
    void parseYearReadsAllFields() {
        Map<LocalDate, DateUtils.HolidayInfo> parsed = DateUtils.parseYear(YEAR_2026_JSON, 2026);
        assertEquals(11, parsed.size());

        DateUtils.HolidayInfo nationalDay = parsed.get(LocalDate.of(2026, 10, 1));
        assertNotNull(nationalDay);
        assertTrue(nationalDay.holiday());
        assertTrue(nationalDay.isRestDay());
        assertEquals("国庆节", nationalDay.name());
        assertEquals(3, nationalDay.wage());
        assertEquals("2026-10-01", nationalDay.date());
        assertNull(nationalDay.target());
        assertNull(nationalDay.after());

        DateUtils.HolidayInfo makeup = parsed.get(LocalDate.of(2026, 10, 10));
        assertNotNull(makeup);
        assertFalse(makeup.holiday());
        assertFalse(makeup.isRestDay());
        assertEquals("国庆节后补班", makeup.name());
        assertEquals("国庆节", makeup.target());
        assertEquals(Boolean.TRUE, makeup.after());
    }

    @Test
    void parseYearReturnsEmptyOnNonZeroCode() {
        assertTrue(DateUtils.parseYear("{\"code\":-1}", 2026).isEmpty());
    }

    @Test
    void parseYearReturnsEmptyOnBlankOrMissingHolidayNode() {
        assertTrue(DateUtils.parseYear(null, 2026).isEmpty());
        assertTrue(DateUtils.parseYear("  ", 2026).isEmpty());
        assertTrue(DateUtils.parseYear("{\"code\":0}", 2026).isEmpty());
    }

    // ==================== 判断今天 / 指定日是否为工作日 ====================

    @Test
    void statutoryHolidayIsNotWorkday() {
        assertFalse(DateUtils.isWorkday(LocalDate.of(2026, 10, 1)), "10-01 周四，国庆节放假");
        assertFalse(DateUtils.isWorkday(LocalDate.of(2026, 10, 2)), "10-02 周五，国庆节放假");
        assertFalse(DateUtils.isWorkday(LocalDate.of(2026, 10, 5)), "10-05 周一，国庆节放假");
    }

    @Test
    void compensatoryWeekendIsWorkday() {
        assertTrue(DateUtils.isWorkday(LocalDate.of(2026, 10, 10)), "10-10 周六，节后补班");
        assertTrue(DateUtils.isWorkday(LocalDate.of(2026, 1, 4)), "01-04 周日，元旦后补班");
        assertTrue(DateUtils.isWorkday(LocalDate.of(2026, 9, 20)), "09-20 周日，中秋节前补班");
    }

    @Test
    void ordinaryWeekendIsNotWorkday() {
        assertFalse(DateUtils.isWorkday(LocalDate.of(2026, 10, 11)), "10-11 周日，无节假日记录");
        assertFalse(DateUtils.isWorkday(LocalDate.of(2026, 10, 17)), "10-17 周六，无节假日记录");
    }

    @Test
    void ordinaryWeekdayIsWorkday() {
        assertTrue(DateUtils.isWorkday(LocalDate.of(2026, 10, 8)), "10-08 周四，无节假日记录");
        assertTrue(DateUtils.isWorkday(LocalDate.of(2026, 10, 20)), "10-20 周二，无节假日记录");
    }

    @Test
    void isWorkdayRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DateUtils.isWorkday(null));
    }

    @Test
    void todayHelpersMatchCurrentDate() {
        assertEquals(LocalDate.now(), DateUtils.getToday());
        assertEquals(DateUtils.isWorkday(LocalDate.now()), DateUtils.isTodayWorkday());
    }

    // ==================== 本月工作日 ====================

    @Test
    void workdaysOfMonthExcludeHolidaysAndIncludeCompensatoryDay() {
        List<LocalDate> workdays = DateUtils.getWorkdaysOfMonth(YearMonth.of(2026, 10));

        assertEquals(18, workdays.size(), "2026-10 共 18 个工作日");
        assertTrue(workdays.contains(LocalDate.of(2026, 10, 8)));
        assertTrue(workdays.contains(LocalDate.of(2026, 10, 10)), "10-10 补班应计入");
        for (int day = 1; day <= 7; day++) {
            assertFalse(workdays.contains(LocalDate.of(2026, 10, day)), "10-0" + day + " 属于国庆假期");
        }
        assertEquals(workdays.stream().sorted().toList(), workdays, "结果应升序");
        assertThrows(UnsupportedOperationException.class, workdays::clear, "返回不可变集合");
    }

    @Test
    void workdaysOfMonthWithoutArgsUsesCurrentMonth() {
        assertEquals(DateUtils.getWorkdaysOfMonth(YearMonth.now()), DateUtils.getWorkdaysOfMonth());
    }

    @Test
    void workdaysOfMonthRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DateUtils.getWorkdaysOfMonth((YearMonth) null));
        assertThrows(IllegalArgumentException.class, () -> DateUtils.getWorkdaysOfMonth((String) null));
    }

    @Test
    void workdaysOfMonthAcceptsTextAndIntInput() {
        List<LocalDate> october = DateUtils.getWorkdaysOfMonth(YearMonth.of(2026, 10));

        assertEquals(18, october.size());
        assertEquals(october, DateUtils.getWorkdaysOfMonth("2026-10"));
        assertEquals(october, DateUtils.getWorkdaysOfMonth("2026年10月"));
        assertEquals(october, DateUtils.getWorkdaysOfMonth("202610"));
        assertEquals(october, DateUtils.getWorkdaysOfMonth(2026, 10));
    }

    @Test
    void parseYearMonthAcceptsCommonFormats() {
        YearMonth october = YearMonth.of(2026, 10);

        assertEquals(october, DateUtils.parseYearMonth("2026-10"));
        assertEquals(october, DateUtils.parseYearMonth("2026/10"));
        assertEquals(october, DateUtils.parseYearMonth("2026.10"));
        assertEquals(october, DateUtils.parseYearMonth("202610"));
        assertEquals(october, DateUtils.parseYearMonth("2026年10月"));
        assertEquals(october, DateUtils.parseYearMonth(" 2026 - 10 "));
        assertEquals(october, DateUtils.parseYearMonth("2026-10-15"), "带日则取所在月份");
        assertEquals(YearMonth.of(2026, 9), DateUtils.parseYearMonth("2026-9"), "月份不补零也支持");
    }

    @Test
    void parseYearMonthWithoutYearFallsBackToCurrentYear() {
        int currentYear = LocalDate.now().getYear();

        assertEquals(YearMonth.of(currentYear, 10), DateUtils.parseYearMonth("10月"));
        assertEquals(YearMonth.of(currentYear, 10), DateUtils.parseYearMonth("10"));
        assertEquals(YearMonth.of(currentYear, 3), DateUtils.parseYearMonth("03"));
    }

    @Test
    void parseYearMonthRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> DateUtils.parseYearMonth(null));
        assertThrows(IllegalArgumentException.class, () -> DateUtils.parseYearMonth("   "));
        assertThrows(IllegalArgumentException.class, () -> DateUtils.parseYearMonth("2026"), "只有年份不算月份");
        assertThrows(IllegalArgumentException.class, () -> DateUtils.parseYearMonth("abc"));
        assertThrows(IllegalArgumentException.class, () -> DateUtils.parseYearMonth("2026-13"), "月份越界");
        assertThrows(IllegalArgumentException.class, () -> DateUtils.parseYearMonth("13"));
    }

    // ==================== 本周工作日 ====================

    @Test
    void workdaysOfWeekSpanMondayToSunday() {
        // 2026-10-01 周四 -> 所在周 09-28(一) ~ 10-04(日)，其中 10-01/10-02 放假
        assertEquals(
                List.of(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30)),
                DateUtils.getWorkdaysOfWeek(LocalDate.of(2026, 10, 1)));
    }

    @Test
    void workdaysOfWeekIncludesCompensatorySaturday() {
        // 2026-10-05 周一 -> 所在周 10-05 ~ 10-11，其中 10-10 周六补班
        assertEquals(
                List.of(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)),
                DateUtils.getWorkdaysOfWeek(LocalDate.of(2026, 10, 5)));
    }

    @Test
    void workdaysOfWeekWithoutArgsUsesCurrentDate() {
        assertEquals(DateUtils.getWorkdaysOfWeek(LocalDate.now()), DateUtils.getWorkdaysOfWeek());
    }

    @Test
    void workdaysOfWeekRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DateUtils.getWorkdaysOfWeek(null));
    }

    // ==================== 工作日序号 ====================

    @Test
    void workdayOrdinalCountsFromMonthStart() {
        assertEquals(1, DateUtils.getWorkdayOrdinal(LocalDate.of(2026, 10, 8)), "10-08 是本月第 1 个工作日");
        assertEquals(2, DateUtils.getWorkdayOrdinal(LocalDate.of(2026, 10, 9)));
        assertEquals(3, DateUtils.getWorkdayOrdinal(LocalDate.of(2026, 10, 10)), "补班日也应计入序号");
        assertEquals(4, DateUtils.getWorkdayOrdinal(LocalDate.of(2026, 10, 12)));
    }

    @Test
    void workdayOrdinalIsZeroOnRestDay() {
        assertEquals(0, DateUtils.getWorkdayOrdinal(LocalDate.of(2026, 10, 1)), "假期返回 0");
        assertEquals(0, DateUtils.getWorkdayOrdinal(LocalDate.of(2026, 10, 11)), "周日返回 0");
    }

    @Test
    void todayWorkdayOrdinalMatchesGenericOrdinal() {
        assertEquals(DateUtils.getWorkdayOrdinal(LocalDate.now()), DateUtils.getTodayWorkdayOrdinal());
    }

    @Test
    void workdayOrdinalRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DateUtils.getWorkdayOrdinal(null));
    }

    // ==================== 节假日名称 ====================

    @Test
    void holidayNameLookup() {
        assertEquals("国庆节", DateUtils.getHolidayName(LocalDate.of(2026, 10, 1)));
        assertEquals("国庆节后补班", DateUtils.getHolidayName(LocalDate.of(2026, 10, 10)));
        assertNull(DateUtils.getHolidayName(LocalDate.of(2026, 10, 8)), "普通工作日无名称");
        assertNull(DateUtils.getHolidayName(LocalDate.of(2027, 1, 1)), "未覆盖年份无记录");
    }

    @Test
    void holidayInfoLookupExposesRestFlag() {
        DateUtils.HolidayInfo info = DateUtils.getHolidayInfo(LocalDate.of(2026, 1, 4));
        assertNotNull(info);
        assertFalse(info.isRestDay());
        assertEquals(Boolean.TRUE, info.after());
        assertEquals("元旦", info.target());
        assertNull(DateUtils.getHolidayInfo(LocalDate.of(2026, 10, 8)));
    }

    // ==================== 兜底与缓存 ====================

    @Test
    void loaderFailureFallsBackToWeekdayRule() {
        DateUtils.setHolidayLoader(year -> {
            throw new IllegalStateException("network down");
        });

        assertTrue(DateUtils.isWorkday(LocalDate.of(2026, 10, 1)), "拿不到数据时 10-01 按周四算工作日");
        assertFalse(DateUtils.isWorkday(LocalDate.of(2026, 10, 11)), "周日仍不算工作日");
        assertFalse(DateUtils.isWorkday(LocalDate.of(2026, 10, 10)), "兜底口径下周六不算工作日");
        assertNull(DateUtils.getHolidayName(LocalDate.of(2026, 10, 1)));
    }

    @Test
    void emptyResultFallsBackAndIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        DateUtils.setHolidayLoader(year -> {
            calls.incrementAndGet();
            return Map.of();
        });

        assertTrue(DateUtils.isWorkday(LocalDate.of(2026, 10, 1)));
        assertTrue(DateUtils.isWorkday(LocalDate.of(2026, 10, 1)));
        assertEquals(2, calls.get(), "空结果不应写入缓存，下次调用需重试");
    }

    @Test
    void successfulResultIsCachedPerYear() {
        AtomicInteger calls = new AtomicInteger();
        DateUtils.setHolidayLoader(year -> {
            calls.incrementAndGet();
            return DateUtils.parseYear(YEAR_2026_JSON, year);
        });

        DateUtils.isWorkday(LocalDate.of(2026, 10, 1));
        DateUtils.isWorkday(LocalDate.of(2026, 10, 2));
        assertEquals(1, calls.get(), "同一自然年只加载一次");

        DateUtils.isWorkday(LocalDate.of(2027, 1, 1));
        assertEquals(2, calls.get(), "跨年需重新加载");
    }

    @Test
    void clearCacheForcesReload() {
        AtomicInteger calls = new AtomicInteger();
        DateUtils.setHolidayLoader(year -> {
            calls.incrementAndGet();
            return DateUtils.parseYear(YEAR_2026_JSON, year);
        });

        DateUtils.isWorkday(LocalDate.of(2026, 10, 1));
        DateUtils.clearCache();
        DateUtils.isWorkday(LocalDate.of(2026, 10, 1));
        assertEquals(2, calls.get());
    }
}
