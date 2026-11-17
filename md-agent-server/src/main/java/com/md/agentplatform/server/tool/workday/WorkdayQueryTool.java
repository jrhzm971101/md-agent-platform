package com.md.agentplatform.server.tool.workday;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.tools.DateUtils;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 工作日查询工具（本地工具，非 MCP）。
 *
 * <p>把 {@link DateUtils} 的节假日能力暴露给 agent。工作日判定必须来自这里，
 * 不能由模型按「周末即休息」推断：调休补班日（周末上班）与法定假日（工作日放假）
 * 都会让星期推断出错，而模型无法自行发现这类错误。
 */
@Component
public class WorkdayQueryTool {

    private static final Logger logger = LoggerFactory.getLogger(WorkdayQueryTool.class);

    private static final String SCOPE_TODAY = "today";
    private static final String SCOPE_WEEK = "week";
    private static final String SCOPE_MONTH = "month";

    @Tool(
            name = "workday-query",
            description = """
                    查询工作日。基于法定节假日与调休补班数据判定，不要用「周末即休息」自行推断。

                    参数说明：
                    - scope: 查询范围，today（今天）/ week（某一周）/ month（某一月），默认 month
                    - month: 月份，scope=month 时使用；支持 2026-09、2026/09、202610、2026年9月、9月 等写法，省略则取本月
                    - date:  日期 yyyy-MM-dd，scope=week 时用于定位所在周，省略则取本周

                    返回：JSON。始终含 today、isTodayWorkday（今天是否工作日）、
                    todayWorkdayOrdinal（今天是本月第几个工作日，非工作日为 0）；
                    scope=week / month 时另含 workdays（工作日日期升序列表）与 count。
                    """
    )
    public String query(
            @ToolParam(name = "scope", description = "查询范围：today / week / month，默认 month") String scope,
            @ToolParam(name = "month", description = "月份，如 2026-09、2026年9月、9月；scope=month 时使用，省略取本月") String month,
            @ToolParam(name = "date", description = "日期 yyyy-MM-dd，scope=week 时用于定位所在周，省略取本周") String date
    ) {
        long startedAt = System.currentTimeMillis();
        String resolvedScope = (scope == null || scope.isBlank()) ? SCOPE_MONTH : scope.trim().toLowerCase();
        logger.info("workday-query called, scope:{}, month:{}, date:{}", resolvedScope, month, date);

        try {
            LocalDate today = DateUtils.getToday();
            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("scope", resolvedScope);
            result.put("today", today.toString());
            result.put("isTodayWorkday", DateUtils.isTodayWorkday());
            result.put("todayWorkdayOrdinal", DateUtils.getTodayWorkdayOrdinal());
            String todayHoliday = DateUtils.getHolidayName(today);
            if (todayHoliday != null) {
                result.put("todayHolidayName", todayHoliday);
            }

            if (SCOPE_MONTH.equals(resolvedScope)) {
                YearMonth yearMonth = (month == null || month.isBlank())
                        ? YearMonth.from(today)
                        : DateUtils.parseYearMonth(month);
                result.put("month", yearMonth.toString());
                putWorkdays(result, DateUtils.getWorkdaysOfMonth(yearMonth));
            } else if (SCOPE_WEEK.equals(resolvedScope)) {
                LocalDate anchor = (date == null || date.isBlank()) ? today : parseDate(date);
                result.put("weekOf", anchor.toString());
                putWorkdays(result, DateUtils.getWorkdaysOfWeek(anchor));
            } else if (!SCOPE_TODAY.equals(resolvedScope)) {
                return error("不支持的 scope: " + scope + "（可选 today / week / month）");
            }

            result.put("elapsedMs", System.currentTimeMillis() - startedAt);
            return result.toString();
        } catch (IllegalArgumentException e) {
            logger.warn("workday-query 参数错误: {}", e.getMessage());
            return error(e.getMessage());
        }
    }

    private static void putWorkdays(ObjectNode result, List<LocalDate> workdays) {
        ArrayNode array = result.putArray("workdays");
        for (LocalDate workday : workdays) {
            array.add(workday.toString());
        }
        result.put("count", workdays.size());
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("无法解析日期: " + date + "（示例：2026-09-18）", e);
        }
    }

    private static String error(String message) {
        ObjectNode node = JsonUtil.newObject();
        node.put("success", false);
        node.put("error", (message == null || message.isBlank()) ? "未知错误" : message);
        return node.toString();
    }
}
