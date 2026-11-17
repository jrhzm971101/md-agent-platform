package com.md.agentplatform.server.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.md.agentplatform.server.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作日 / 节假日工具类。
 *
 * <p>节假日数据来自 timor.tech 年度接口 {@code https://timor.tech/api/holiday/year/{year}}：
 * <ul>
 *   <li>{@code holiday = true} —— 法定放假日（含周末连休），非工作日；</li>
 *   <li>{@code holiday = false} —— 调休补班日（通常是周末），是工作日。</li>
 * </ul>
 *
 * <p>接口未覆盖该日期、或调用失败时，回退为「周一至周五即工作日」的兜底判定，
 * 避免一次网络抖动就让上层流程拿不到任何工作日结果。
 *
 * <p>结果按年缓存在进程内（接口自身返回 {@code Cache-Control: max-age=43200}）。
 * 加载失败不写缓存，下次调用会重试。
 */
public final class DateUtils {

    private static final Logger logger = LoggerFactory.getLogger(DateUtils.class);

    /** timor.tech 年度节假日接口。 */
    public static final String HOLIDAY_API_URL = "https://timor.tech/api/holiday/year/%d";

    /**
     * 该站点走 Cloudflare，会按 User-Agent 拦截：
     * curl / okhttp 的默认 UA 直接返回 403，浏览器 UA 正常返回 200，故显式设置。
     */
    private static final String USER_AGENT = "Mozilla/5.0";

    private static final int TIMEOUT_MS = 5000;

    private static final Map<Integer, Map<LocalDate, HolidayInfo>> CACHE = new ConcurrentHashMap<>();

    /** 年度节假日加载器；默认走 timor.tech，测试可替换以避免依赖外网。 */
    private static volatile HolidayLoader holidayLoader = DateUtils::fetchFromTimor;

    private DateUtils() {
    }

    // ============================ 对外方法 ============================

    /** 获取今天日期。 */
    public static LocalDate getToday() {
        return LocalDate.now();
    }

    /** 判断今天是否为工作日。 */
    public static boolean isTodayWorkday() {
        return isWorkday(LocalDate.now());
    }

    /**
     * 判断指定日期是否为工作日。
     *
     * <p>调休补班的周末算工作日；法定节假日与普通周末不算。
     */
    public static boolean isWorkday(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date 不能为空");
        }
        HolidayInfo info = holidayInfoOf(date);
        if (info != null) {
            return !info.holiday();
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }

    /** 获取本月全部工作日（升序）。 */
    public static List<LocalDate> getWorkdaysOfMonth() {
        return getWorkdaysOfMonth(YearMonth.now());
    }

    /** 获取指定月份的全部工作日（升序）。 */
    public static List<LocalDate> getWorkdaysOfMonth(YearMonth month) {
        if (month == null) {
            throw new IllegalArgumentException("month 不能为空");
        }
        List<LocalDate> workdays = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            if (isWorkday(date)) {
                workdays.add(date);
            }
        }
        return Collections.unmodifiableList(workdays);
    }

    /** 获取指定月份（如 {@code 2026-10}、{@code 2026年10月}、{@code 10月}）的全部工作日（升序）。 */
    public static List<LocalDate> getWorkdaysOfMonth(String month) {
        return getWorkdaysOfMonth(parseYearMonth(month));
    }

    /** 获取指定年、月的全部工作日（升序）。 */
    public static List<LocalDate> getWorkdaysOfMonth(int year, int month) {
        return getWorkdaysOfMonth(YearMonth.of(year, month));
    }

    /**
     * 解析月份文本为 {@link YearMonth}，兼容常见写法：
     * <ul>
     *   <li>{@code 2026-10}、{@code 2026/10}、{@code 2026.10}、{@code 202610}、{@code 2026年10月}</li>
     *   <li>{@code 2026-10-15}（带日则取所在月份）</li>
     *   <li>{@code 10月}、{@code 10}（省略年份时取当前年）</li>
     * </ul>
     *
     * @throws IllegalArgumentException 无法解析或月份越界时抛出
     */
    public static YearMonth parseYearMonth(String month) {
        if (month == null || month.isBlank()) {
            throw new IllegalArgumentException("month 不能为空");
        }
        String normalized = month.trim()
                .replace("年", "-")
                .replace("月", "")
                .replace("/", "-")
                .replace(".", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+|-+$", "");
        String[] parts = normalized.split("-");
        try {
            if (parts.length >= 2) {
                return YearMonth.of(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            }
            String single = parts[0].trim();
            if (single.length() == 6) {
                return YearMonth.of(Integer.parseInt(single.substring(0, 4)), Integer.parseInt(single.substring(4)));
            }
            if (single.length() <= 2) {
                return YearMonth.of(LocalDate.now().getYear(), Integer.parseInt(single));
            }
        } catch (NumberFormatException | DateTimeException e) {
            throw new IllegalArgumentException("无法解析月份: " + month, e);
        }
        throw new IllegalArgumentException("无法解析月份: " + month + "（示例：2026-10、2026年10月、10）");
    }

    /** 获取本周（周一至周日）的全部工作日（升序）。 */
    public static List<LocalDate> getWorkdaysOfWeek() {
        return getWorkdaysOfWeek(LocalDate.now());
    }

    /** 获取指定日期所在周（周一至周日）的全部工作日（升序）。 */
    public static List<LocalDate> getWorkdaysOfWeek(LocalDate anyDayOfWeek) {
        if (anyDayOfWeek == null) {
            throw new IllegalArgumentException("anyDayOfWeek 不能为空");
        }
        LocalDate monday = anyDayOfWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<LocalDate> workdays = new ArrayList<>(5);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = monday.plusDays(offset);
            if (isWorkday(date)) {
                workdays.add(date);
            }
        }
        return Collections.unmodifiableList(workdays);
    }

    /**
     * 获取今天是本月的第几个工作日。
     *
     * @return 今天是工作日时返回序号（从 1 开始）；今天不是工作日时返回 0
     */
    public static int getTodayWorkdayOrdinal() {
        return getWorkdayOrdinal(LocalDate.now());
    }

    /**
     * 获取指定日期是所在月份的第几个工作日。
     *
     * @return 该日期是工作日时返回序号（从 1 开始）；不是工作日时返回 0
     */
    public static int getWorkdayOrdinal(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date 不能为空");
        }
        if (!isWorkday(date)) {
            return 0;
        }
        YearMonth month = YearMonth.from(date);
        int ordinal = 0;
        for (int day = 1; day <= date.getDayOfMonth(); day++) {
            if (isWorkday(month.atDay(day))) {
                ordinal++;
            }
        }
        return ordinal;
    }

    /** 获取指定日期的节假日名称（如「国庆节」「国庆节后补班」）；无记录返回 {@code null}。 */
    public static String getHolidayName(LocalDate date) {
        HolidayInfo info = holidayInfoOf(date);
        return info == null ? null : info.name();
    }

    /** 获取指定日期的节假日详情；无记录返回 {@code null}。 */
    public static HolidayInfo getHolidayInfo(LocalDate date) {
        return holidayInfoOf(date);
    }

    // ============================ 缓存与加载 ============================

    /** 替换节假日加载器（供测试或自定义数据源使用）；传 {@code null} 恢复默认实现并清空缓存。 */
    public static void setHolidayLoader(HolidayLoader loader) {
        holidayLoader = loader == null ? DateUtils::fetchFromTimor : loader;
        CACHE.clear();
    }

    /** 清空年度缓存。 */
    public static void clearCache() {
        CACHE.clear();
    }

    private static HolidayInfo holidayInfoOf(LocalDate date) {
        return holidaysOf(date.getYear()).get(date);
    }

    private static Map<LocalDate, HolidayInfo> holidaysOf(int year) {
        Map<LocalDate, HolidayInfo> cached = CACHE.get(year);
        if (cached != null) {
            return cached;
        }
        Map<LocalDate, HolidayInfo> loaded;
        try {
            loaded = holidayLoader.load(year);
        } catch (Exception e) {
            logger.warn("[DateUtils] 加载 {} 年节假日异常，回退周末判定: {}", year, e.toString());
            return Collections.emptyMap();
        }
        if (loaded == null || loaded.isEmpty()) {
            // 失败或空结果不写缓存，等网络恢复后下次调用能重新拉取
            return Collections.emptyMap();
        }
        CACHE.put(year, loaded);
        return loaded;
    }

    /** 默认实现：调用 timor.tech 年度接口；失败返回空 Map，由上层回退周末判定。 */
    static Map<LocalDate, HolidayInfo> fetchFromTimor(int year) {
        String url = String.format(HOLIDAY_API_URL, year);
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(TIMEOUT_MS);
            factory.setReadTimeout(TIMEOUT_MS);
            RestTemplate restTemplate = new RestTemplate(factory);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parseYear(response.getBody(), year);
        } catch (Exception e) {
            logger.warn("[DateUtils] 获取 {} 年节假日失败({}), 回退周末判定", year, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 解析 timor.tech 年度接口返回体。 */
    static Map<LocalDate, HolidayInfo> parseYear(String json, int year) {
        Map<LocalDate, HolidayInfo> holidays = new HashMap<>();
        if (json == null || json.isBlank()) {
            return holidays;
        }
        JsonNode root = JsonUtil.parse(json);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            logger.warn("[DateUtils] {} 年节假日接口返回非 0 code: {}", year, code);
            return holidays;
        }
        JsonNode holidayNode = root.path("holiday");
        if (!holidayNode.isObject()) {
            return holidays;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = holidayNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode node = entry.getValue();
            if (!node.path("holiday").isBoolean()) {
                continue;
            }
            LocalDate date = parseDate(node.path("date").asText(null), entry.getKey(), year);
            if (date == null) {
                continue;
            }
            holidays.put(date, new HolidayInfo(
                    node.path("date").asText(entry.getKey()),
                    node.path("holiday").asBoolean(),
                    node.path("name").asText(""),
                    node.path("wage").asInt(0),
                    node.path("target").asText(null),
                    node.hasNonNull("after") ? node.path("after").asBoolean() : null));
        }
        return holidays;
    }

    private static LocalDate parseDate(String date, String fallbackMonthDay, int year) {
        try {
            if (date != null && !date.isBlank()) {
                return LocalDate.parse(date);
            }
            int month = Integer.parseInt(fallbackMonthDay.substring(0, 2));
            int dayOfMonth = Integer.parseInt(fallbackMonthDay.substring(3, 5));
            return YearMonth.of(year, month).atDay(dayOfMonth);
        } catch (DateTimeParseException | NumberFormatException | StringIndexOutOfBoundsException e) {
            logger.warn("[DateUtils] 无法解析节假日日期: date={}, key={}", date, fallbackMonthDay);
            return null;
        }
    }

    // ============================ 数据结构 ============================

    /** 节假日条目，字段与 timor.tech 返回体一一对应。 */
    public record HolidayInfo(String date, boolean holiday, String name, int wage, String target, Boolean after) {

        /** 是否为放假日（{@code true} 放假、{@code false} 为调休补班）。 */
        public boolean isRestDay() {
            return holiday;
        }
    }

    /** 年度节假日加载器。 */
    @FunctionalInterface
    public interface HolidayLoader {
        Map<LocalDate, HolidayInfo> load(int year);
    }


    public static void main(String[] args) {
        System.out.println(getWorkdaysOfMonth());
    }
}
