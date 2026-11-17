package com.md.agentplatform.server.tool.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.md.agentplatform.server.tools.DateUtils;
import com.md.agentplatform.server.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作日工具测试 —— 用固定节假日快照，不依赖外网。
 *
 * <p>重点是两类只有真实节假日数据才能答对的日期：
 * 2026-09-20（周日补班，应算工作日）与 2026-10-01~10-07（国庆假期，自然工作日放假）。
 */
class WorkdayQueryToolTest {

    private final WorkdayQueryTool tool = new WorkdayQueryTool();

    @BeforeEach
    void useFixedHolidaySnapshot() {
        DateUtils.setHolidayLoader(year -> year == 2026 ? snapshot2026() : Map.of());
    }

    @AfterEach
    void restoreDefaultLoader() {
        DateUtils.setHolidayLoader(null);
    }

    // ==================== month ====================

    @Test
    void monthScopeReturnsWorkdayListAndTodayInfo() {
        JsonNode result = query("month", "2026-10", null);

        assertTrue(result.path("success").asBoolean());
        assertEquals("month", result.path("scope").asText());
        assertEquals("2026-10", result.path("month").asText());
        assertEquals(18, result.path("count").asInt());
        assertEquals(18, result.path("workdays").size());
        assertEquals("2026-10-08", result.path("workdays").get(0).asText(), "假期结束后的第一个工作日");
        assertTrue(texts(result.path("workdays")).contains("2026-10-10"), "补班周六应计入");

        assertEquals(DateUtils.isTodayWorkday(), result.path("isTodayWorkday").asBoolean());
        assertEquals(DateUtils.getTodayWorkdayOrdinal(), result.path("todayWorkdayOrdinal").asInt());
        assertTrue(result.path("elapsedMs").isInt());
    }

    @Test
    void monthScopeAcceptsChineseCompactAndSlashFormats() {
        assertEquals(18, query("month", "2026年10月", null).path("count").asInt());
        assertEquals(18, query("month", "202610", null).path("count").asInt());
        assertEquals(18, query("month", "2026/10", null).path("count").asInt());
    }

    @Test
    void scopeDefaultsToMonthAndMonthDefaultsToCurrent() {
        JsonNode result = query(null, null, null);

        assertEquals("month", result.path("scope").asText());
        assertEquals(YearMonth.now().toString(), result.path("month").asText());
        assertEquals(DateUtils.getWorkdaysOfMonth(YearMonth.now()).size(), result.path("count").asInt());
    }

    // ==================== week ====================

    @Test
    void weekScopeUsesAnchorDateAndSpansMondayToSunday() {
        JsonNode result = query("week", null, "2026-10-01");

        assertEquals("week", result.path("scope").asText());
        assertEquals("2026-10-01", result.path("weekOf").asText());
        assertEquals(List.of("2026-09-28", "2026-09-29", "2026-09-30"), texts(result.path("workdays")));
    }

    @Test
    void weekScopePicksUpCompensatorySunday() {
        // 2026-09-20 是周日但要上班；任何「周末休息」的推断都会漏掉它
        assertTrue(texts(query("week", null, "2026-09-20").path("workdays")).contains("2026-09-20"));
    }

    // ==================== today ====================

    @Test
    void todayScopeAnswersOnlyTheTodayQuestion() {
        JsonNode result = query("today", null, null);

        assertEquals("today", result.path("scope").asText());
        assertTrue(result.has("isTodayWorkday"));
        assertTrue(result.has("todayWorkdayOrdinal"));
        assertFalse(result.has("workdays"), "today 范围不该带日期列表");
        assertFalse(result.has("count"));
    }

    // ==================== 参数错误：返回 error JSON，不抛异常 ====================

    @Test
    void unknownScopeReturnsErrorJson() {
        JsonNode result = query("year", null, null);

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("scope"));
    }

    @Test
    void invalidMonthReturnsErrorJson() {
        assertFalse(query("month", "2026-13", null).path("success").asBoolean());
        assertFalse(query("month", "abc", null).path("success").asBoolean());
    }

    @Test
    void invalidDateReturnsErrorJson() {
        JsonNode result = query("week", null, "18/09/2026");

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("日期"));
    }

    private JsonNode query(String scope, String month, String date) {
        return JsonUtil.parse(tool.query(scope, month, date));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    /** 直接构造节假日快照；接口解析逻辑另有 DateUtilsTest 覆盖，这里只关心工具行为。 */
    private static Map<LocalDate, DateUtils.HolidayInfo> snapshot2026() {
        Map<LocalDate, DateUtils.HolidayInfo> holidays = new HashMap<>();
        holidays.put(LocalDate.of(2026, 9, 20), entry(false, "中秋节前补班", "2026-09-20"));
        for (int day = 1; day <= 7; day++) {
            LocalDate date = LocalDate.of(2026, 10, day);
            holidays.put(date, entry(true, "国庆节", date.toString()));
        }
        holidays.put(LocalDate.of(2026, 10, 10), entry(false, "国庆节后补班", "2026-10-10"));
        return holidays;
    }

    private static DateUtils.HolidayInfo entry(boolean holiday, String name, String date) {
        return new DateUtils.HolidayInfo(date, holiday, name, holiday ? 2 : 1, null, null);
    }
}
