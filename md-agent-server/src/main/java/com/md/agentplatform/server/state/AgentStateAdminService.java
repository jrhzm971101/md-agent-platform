package com.md.agentplatform.server.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.AgentPlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 状态存储的管理面：看有哪些表、某个 agent 有哪些会话、某个会话记了什么、删掉一个会话。
 *
 * <h2>一、这里只做两件事：翻译 + 守卫</h2>
 * SQL 全在 {@link AgentStateMapper}，本类负责：
 * <ol>
 *   <li><b>守卫</b>：任何动态表名先用"白名单正则 + information_schema 存在性"两道闸门，
 *       再交给 Mapper 拼 SQL；</li>
 *   <li><b>翻译</b>：把库里的 {@code <userId>:<sessionId>} 拼串拆成人能读的两列，
 *       把 1.65 MB 的 {@code state_data} 压成"消息条数 / 压缩摘要 / 任务 / 计划"摘要。</li>
 * </ol>
 *
 * <h2>二、改完为什么不用重启就生效</h2>
 * 框架在<b>每次 call 的开头</b>都会从 store 重新加载该会话的状态
 * （{@code ReActAgent.activateSlotForContext} 的注释写明 "state is always reloaded from the
 * store at the beginning of each call"）。所以这里直接删库，下一次对话就会以全新上下文开始，
 * agent 实例内的缓存会在那次 call 时被覆盖。前提是该 agent 配了 state store
 * —— 没配的走文件存储，本页看不到也管不着。
 *
 * <h2>三、为什么不做编辑</h2>
 * {@code state_data} 是框架内部对象的序列化结果，字段随版本演进
 * （已实测的就有 {@code context / tasks_context / plan_mode_context / tool_context} 等）。
 * 页面写回一份"看起来对"的 JSON，风险是让框架反序列化出一个半损坏的状态，
 * 而失败现象会在下一次对话才出现。删除是幂等的、可解释的；编辑不是。
 */
@Service
public class AgentStateAdminService {

    private static final Logger log = LoggerFactory.getLogger(AgentStateAdminService.class);

    /**
     * 表名白名单。与 {@code ConfigurableAgentBuilder#STATE_TABLE_PATTERN} 同一套规则：
     * 装配期拦一次（防止启动就带着坏配置），这里再拦一次（防止接口成为新的注入面）。
     */
    private static final Pattern SAFE_TABLE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 框架只用这一个 state_key 存单值状态（存量数据实测 100% 是它）。 */
    private static final String AGENT_STATE_KEY = "agent_state";
    /** 单值状态的 item_index。 */
    private static final int AGENT_STATE_INDEX = 0;

    /** 原始 JSON 的返回上限。超过就拒绝，避免把几 MB 的字符串灌进浏览器。 */
    private static final long MAX_RAW_BYTES = 4L * 1024 * 1024;

    /** 对话页回放最多返回多少条消息（从最新往前取），以及单条消息单个字段的截断长度。 */
    public static final int MAX_MESSAGES = 500;
    private static final int MESSAGE_FIELD_LIMIT = 20_000;

    /** 详情页预览：最多几条消息、每条截多长。 */
    private static final int PREVIEW_LIMIT = 20;
    private static final int PREVIEW_TEXT_LIMIT = 400;

    /**
     * 详情里各文本字段的长度上限。
     *
     * <p><b>为什么必须有这些上限</b>：详情接口的整个存在意义就是"不要把 MB 级的
     * {@code state_data} 传给浏览器"，只返回人能读的摘要。但摘要里的单个字段本身也可能极大 ——
     * 实测构造一条 5 MB 的 {@code summary} 时，详情响应体直接变成 5.4 MB，
     * 上限形同虚设（这条是端到端测出来的，不是假想风险）。所以逐字段再设一道：
     * {@code summary} 是压缩摘要（可能很长），任务标题与工具组名是列表里的短串，
     * 各自给一个与用途匹配的上限。
     */
    private static final int SUMMARY_TEXT_LIMIT = 2_000;
    private static final int TASK_TEXT_LIMIT = 200;
    private static final int PATH_TEXT_LIMIT = 512;
    private static final int SHORT_TEXT_LIMIT = 64;

    /** 详情里返回的任务条数上限。超出只计数（{@code tasksTotal} 仍是真实值）。 */
    private static final int TASKS_LIMIT = 200;
    /** 详情里返回的工具组个数上限。 */
    private static final int TOOL_GROUPS_LIMIT = 50;

    /** 会话列表的默认/最大分页大小。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    private final AgentStateMapper mapper;
    private final AgentPlatformProperties properties;
    private final ObjectMapper objectMapper;

    public AgentStateAdminService(AgentStateMapper mapper,
                                  AgentPlatformProperties properties,
                                  ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 状态库名（页面上要显示"这些表在哪"，所以把它暴露出去）。 */
    public String database() {
        return properties.getStateStoreDatabase();
    }

    // ==================================================================================
    // 表清单
    // ==================================================================================

    /**
     * 全部状态表 = 库里真实存在的 + 声明了但还没建的。
     *
     * <p>后者的行数/体积都是 0，且 {@code exists=false} —— 页面据此显示"尚未创建"，
     * 而不是显示一张 0 行的空表（那看起来像"数据被清了"）。
     */
    public List<AgentStateViews.TableView> listTables() {
        String db = database();
        List<AgentStateViews.TableView> views = new ArrayList<>();

        for (StateTableRow row : mapper.listExistingTables(db)) {
            StateSlotRow stats = mapper.tableStats(db, row.getTableName());
            long bytes = stats == null || stats.getBytes() == null ? 0L : stats.getBytes();
            views.add(new AgentStateViews.TableView(
                    row.getTableName(),
                    row.getAgentName(),
                    true,
                    mapper.countSessions(db, row.getTableName()),
                    stats == null || stats.getRows() == null ? 0L : stats.getRows(),
                    bytes,
                    iso(stats == null ? null : stats.getUpdatedAt())));
        }

        for (StateTableRow row : mapper.listDeclaredMissingTables(db)) {
            views.add(new AgentStateViews.TableView(
                    row.getTableName(), row.getAgentName(), false, 0L, 0L, 0L, null));
        }

        views.sort(Comparator.comparing(AgentStateViews.TableView::tableName));
        return views;
    }

    // ==================================================================================
    // 会话列表
    // ==================================================================================

    /**
     * 某个状态表下的会话列表（关键字过滤 + 分页都在内存里做，理由见 Mapper 的注释）。
     *
     * <p>关键字同时匹配 userId 与 sessionId 的子串 —— 页面上只有一个输入框，
     * 让用户去选"按哪个字段搜"是没有价值的选择题。
     */
    public AgentStateViews.SessionPage listSessions(String table, String keyword, int limit, int offset) {
        String db = requireTable(table);
        List<StateSlotRow> all = mapper.listSessions(db, table);
        all.forEach(this::decorate);

        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<StateSlotRow> matched = new ArrayList<>();
        for (StateSlotRow row : all) {
            if (needle.isEmpty() || contains(row.getUserId(), needle) || contains(row.getSessionId(), needle)) {
                matched.add(row);
            }
        }

        int size = Math.max(1, Math.min(limit <= 0 ? DEFAULT_PAGE_SIZE : limit, MAX_PAGE_SIZE));
        int from = Math.max(0, Math.min(offset, matched.size()));
        int to = Math.min(from + size, matched.size());
        return new AgentStateViews.SessionPage(
                matched.size(), all.size(), new ArrayList<>(matched.subList(from, to)));
    }

    // ==================================================================================
    // 会话视角的查询（供「会话记录」页使用）
    // ==================================================================================

    /**
     * slotId → 它出现在哪些状态表里。
     *
     * <p><b>为什么一次性取全量，而不是逐行按需查</b>：会话页的一页是 20 行、状态表有 N 张，
     * 逐行查就是 20×N 次往返；而一张状态表的会话数是"几十"量级（实测最多 35），
     * 全量取回来的代价远小于反复往返。这也是"每一行都能显示有没有记忆"这一列的必要前提：
     * 口径必须一致，不能有的行查了库、有的行是猜的。
     *
     * <p>一个 slotId 出现在多张表里是正常的：同一条会话被两个 agent 服务过，
     * 而状态表是"一个 agent 一张"，两边各记各的。
     */
    public Map<String, List<String>> slotTables() {
        String db = database();
        Map<String, List<String>> result = new HashMap<>();
        for (StateTableRow row : mapper.listExistingTables(db)) {
            String table = row.getTableName();
            /*
             * 表名虽然来自 information_schema，仍然过一遍白名单。
             * 动态 SQL 的闸门只此一处（见类注释），将来若有人把别的来源接进来，
             * 这一行是唯一还能拦住它的地方 —— 少了它，拼进 SQL 的就成了调用方给的串。
             */
            if (table == null || !SAFE_TABLE.matcher(table).matches()) {
                continue;
            }
            for (String slotId : mapper.listSlotIds(db, table)) {
                if (slotId != null && !slotId.isBlank()) {
                    result.computeIfAbsent(slotId, key -> new ArrayList<>()).add(table);
                }
            }
        }
        return result;
    }

    /**
     * 一条会话在哪些状态表里留下了记忆，各自多少行、多大、最后写入是什么时候。
     *
     * <p>逐表查（表数 = 配了状态存储的 agent 数，本项目个位数），每次打开详情一次，不做缓存：
     * 缓存要处理"删完这条会话之后失效"，而这一页的访问频率是人工点击级别。
     *
     * <p>只读元信息，不读 {@code state_data}：这是"这条会话在这里有没有、多大"，
     * 要看内容去状态存储页。
     */
    public List<AgentStateViews.SessionStateRow> sessionStates(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        if (slotId == null) {
            return List.of();
        }
        String db = database();
        List<AgentStateViews.SessionStateRow> result = new ArrayList<>();
        for (StateTableRow row : mapper.listExistingTables(db)) {
            String table = row.getTableName();
            if (table == null || !SAFE_TABLE.matcher(table).matches()) {
                continue;
            }
            List<StateSlotRow> slots = mapper.selectSessionSlots(db, table, slotId);
            if (slots.isEmpty()) {
                continue;
            }
            long bytes = 0;
            String latest = null;
            for (StateSlotRow slot : slots) {
                bytes += slot.getBytes() == null ? 0L : slot.getBytes();
                // updated_at 是 DATETIME，同格式字符串的字典序就是时间序，不必解析成 Instant。
                String at = slot.getUpdatedAt();
                if (at != null && (latest == null || at.compareTo(latest) > 0)) {
                    latest = at;
                }
            }
            result.add(new AgentStateViews.SessionStateRow(
                    table, row.getAgentName(), slots.size(), bytes, iso(latest)));
        }
        return result;
    }

    /**
     * userId + sessionId → 状态表 {@code session_id} 列的值。
     *
     * <p>与框架 {@code MysqlAgentStateStore.slotId} 同一个拼法，也是两个库之间唯一能对齐的键：
     * run 表把这两段分开存在两列里，状态表把它们拼成一列。
     *
     * <p>之所以是 {@code public static} 而不是私有工具：会话页（{@code server.session} 包）
     * 也要用它，而"这个键怎么拼"只能有一个答案 —— 各拼一份的结果是某天改了拼法，
     * 一边改了另一边没改，页面上表现为"会话在、记忆查不到"。
     *
     * <p>任一段为空就返回 null：状态表里没有"未分区"这种写法，
     * 硬拼出 {@code :sessionId} 只会查到不存在的东西。
     */
    public static String slotId(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return userId + ":" + sessionId;
    }

    // ==================================================================================
    // 会话详情
    // ==================================================================================

    /** 一个会话的槽位清单 + 解析后的 agent_state 摘要。 */
    public AgentStateViews.SessionDetail detail(String table, String slotId) {
        String db = requireTable(table);
        requireSlot(slotId);

        List<StateSlotRow> slots = mapper.selectSessionSlots(db, table, slotId);
        if (slots.isEmpty()) {
            throw new StateStoreException(StateStoreException.Kind.NOT_FOUND,
                    "会话不存在: " + slotId + "（表 " + table + "）");
        }
        slots.forEach(this::decorate);

        long totalBytes = 0L;
        for (StateSlotRow slot : slots) {
            totalBytes += slot.getBytes() == null ? 0L : slot.getBytes();
        }

        // 只解析 agent_state/0 这一个槽位：它是"模型能看到什么"的全部内容，
        // 其余槽位（列表型状态）在页面上只列元信息。
        StateSlotRow target = null;
        for (StateSlotRow slot : slots) {
            if (AGENT_STATE_KEY.equals(slot.getStateKey())
                    && slot.getItemIndex() != null && slot.getItemIndex() == AGENT_STATE_INDEX) {
                target = slot;
                break;
            }
        }

        AgentStateViews.Snapshot snapshot = null;
        String parseError = null;
        if (target != null) {
            String json = mapper.selectStateData(db, table, slotId, AGENT_STATE_KEY, AGENT_STATE_INDEX);
            if (json == null || json.isBlank()) {
                parseError = "agent_state 槽位为空";
            } else {
                try {
                    snapshot = parse(json, target);
                } catch (Exception e) {
                    // 解析失败不改状态码：会话本身是存在的，页面仍要能删它。
                    // 原因带回给页面，否则用户只会看到一个空白的摘要区。
                    parseError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    log.warn("[State] 解析 agent_state 失败 table={} slot={}: {}", table, slotId, e.getMessage());
                }
            }
        } else {
            parseError = "该会话没有 agent_state 槽位";
        }

        String[] parts = split(slotId);
        return new AgentStateViews.SessionDetail(slotId, parts[0], parts[1], totalBytes, slots, snapshot, parseError);
    }

    /**
     * 原始 {@code state_data} JSON 文本。
     *
     * <p>有独立的体积上限：详情摘要是给人读的，原文是给排障用的（比如要确认某条工具结果
     * 到底塞了什么）。超过 4 MB 直接拒绝而不是截断 —— 截断后的 JSON 不是一个合法文档，
     * 前端解析会报一个更难懂的错误。
     */
    public String raw(String table, String slotId) {
        String db = requireTable(table);
        requireSlot(slotId);

        Long bytes = null;
        List<StateSlotRow> slots = mapper.selectSessionSlots(db, table, slotId);
        for (StateSlotRow slot : slots) {
            if (AGENT_STATE_KEY.equals(slot.getStateKey())
                    && slot.getItemIndex() != null && slot.getItemIndex() == AGENT_STATE_INDEX) {
                bytes = slot.getBytes();
                break;
            }
        }
        if (bytes == null) {
            throw new StateStoreException(StateStoreException.Kind.NOT_FOUND,
                    "会话没有 agent_state 槽位: " + slotId);
        }
        if (bytes > MAX_RAW_BYTES) {
            throw new StateStoreException(StateStoreException.Kind.TOO_LARGE,
                    "agent_state 体积 " + (bytes / 1024) + " KB，超过 " + (MAX_RAW_BYTES / 1024 / 1024)
                            + " MB 上限，不予整包返回");
        }
        String json = mapper.selectStateData(db, table, slotId, AGENT_STATE_KEY, AGENT_STATE_INDEX);
        if (json == null) {
            throw new StateStoreException(StateStoreException.Kind.NOT_FOUND, "agent_state 槽位不存在: " + slotId);
        }
        return json;
    }

    // ==================================================================================
    // 会话回放
    // ==================================================================================

    /**
     * 一个会话的完整消息清单。
     *
     * <h2>一、为什么要有这个接口（而不是复用 detail 的 preview）</h2>
     * {@code detail} 里的 preview 是「详情摘要」的一部分：只取最后 {@code PREVIEW_LIMIT} 条
     * （20 条）、每条压到 {@code PREVIEW_TEXT_LIMIT} 字（400 字）并拼成一行。
     * 那份摘要回答的是"这个会话现在处在什么状态"；而对话页要把历史<b>照着原样画出来</b>
     * —— 截断到 400 字的回答画在气泡里就是"模型只答了半句"。
     * 两者用途不同，所以是两个接口，而不是把 detail 的上限调大
     * （调大之后状态存储页会跟着变重，那是它不需要的）。
     *
     * <h2>二、为什么从最新往前取</h2>
     * 一条跑了一年的定时任务会话可能有几千条消息，全给没有意义。取最近的 {@code limit} 条，
     * 与页面上「最近会话」这个词的口径一致。
     *
     * <h2>三、为什么解析失败是 400 而不是 500</h2>
     * 状态是框架内部对象的序列化结果，形态由框架版本决定。解析不了说明"库里这段文本
     * 不是我们认识的形态"（版本错位、被手工改过），把它当成参数不合法（INVALID）
     * 比当成服务端故障更准确 —— 页面拿到的文案也会是"解析失败"而不是"服务器错误"。
     */
    public List<AgentStateViews.Message> messages(String table, String slotId, int limit) {
        String db = requireTable(table);
        requireSlot(slotId);

        String json = mapper.selectStateData(db, table, slotId, AGENT_STATE_KEY, AGENT_STATE_INDEX);
        if (json == null || json.isBlank()) {
            throw new StateStoreException(StateStoreException.Kind.NOT_FOUND,
                    "会话没有 agent_state 槽位（可能它还没跑过、或这个 agent 没开状态存储）: " + slotId);
        }

        int cap = limit <= 0 ? MAX_MESSAGES : Math.min(limit, MAX_MESSAGES);
        try {
            JsonNode context = objectMapper.readTree(json).path("context");
            if (!context.isArray()) {
                // context 缺失或不是数组：当成"这个会话还没有消息"，不是错误。
                return List.of();
            }
            List<AgentStateViews.Message> messages = new ArrayList<>();
            int from = Math.max(0, context.size() - cap);
            for (int i = from; i < context.size(); i++) {
                messages.add(message(context.get(i)));
            }
            return messages;
        } catch (StateStoreException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[State] 解析会话消息失败 table={} slot={}: {}", table, slotId, e.getMessage());
            throw new StateStoreException(StateStoreException.Kind.INVALID,
                    "解析会话消息失败: " + e.getMessage());
        }
    }

    /**
     * 一条消息 → 对话页能直接渲染的三段。
     *
     * <p>{@code content} 既可能是数组（多块）也可能是对象（单块），两条路都要走 ——
     * 与 {@link #preview(JsonNode)} 同一套判断，理由一致。
     */
    private AgentStateViews.Message message(JsonNode node) {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<String> tools = new ArrayList<>();
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                collectBlock(block, text, thinking, tools);
            }
        } else if (content.isObject()) {
            collectBlock(content, text, thinking, tools);
        }
        return new AgentStateViews.Message(
                node.path("role").asText(""),
                clip(text.toString(), MESSAGE_FIELD_LIMIT),
                clip(thinking.toString(), MESSAGE_FIELD_LIMIT),
                List.copyOf(tools),
                node.path("timestamp").asText(""));
    }

    /**
     * 一个内容块 → 正文 / 思考 / 工具名。
     *
     * <p>与 {@link #collect(JsonNode, List)} 的区别只在落点：那边把四类块都压成一行文本，
     * 这边把正文与思考分开、把工具结果丢掉。工具结果不渲染是刻意的 ——
     * 它是"过程"而不是"对话"，几千字的表格查询结果铺满整页会让人找不到对话本身。
     */
    private static void collectBlock(JsonNode block, StringBuilder text, StringBuilder thinking,
                                     List<String> tools) {
        switch (block.path("type").asText("")) {
            case "text" -> text.append(block.path("text").asText(""));
            case "thinking" -> thinking.append(block.path("thinking").asText(""));
            case "tool_use" -> tools.add(block.path("name").asText(""));
            default -> {
                // tool_result / 未知块：不进气泡。留一个显式分支而不是省略 default，
                // 是为了把"这是有意丢掉的"写下来 —— 下一版要渲染它时这里有落点。
            }
        }
    }

    // ==================================================================================
    // 删除
    // ==================================================================================

    /**
     * 删除一个会话的状态（<b>只删状态</b>，不碰调用链路观测数据）。
     *
     * <p>返回删掉的行数：一个会话通常只有 1 行，但列表型状态会有多行。
     * 返回 0 说明这个会话本来就不存在 —— 那不是错误，是"重复点了删除"，
     * 所以按 NOT_FOUND 处理而不是静默成功：页面要能区分"删掉了"与"早就没了"。
     */
    public int deleteSession(String table, String slotId) {
        String db = requireTable(table);
        requireSlot(slotId);
        int deleted = mapper.deleteSession(db, table, slotId);
        log.info("[State] 删除会话状态 db={} table={} slot={} deletedRows={}", db, table, slotId, deleted);
        if (deleted == 0) {
            throw new StateStoreException(StateStoreException.Kind.NOT_FOUND,
                    "会话不存在，无需删除: " + slotId);
        }
        return deleted;
    }

    // ==================================================================================
    // 守卫
    // ==================================================================================

    /**
     * 两道闸门，返回库名给调用方拼后续 SQL。
     *
     * <p>顺序有意义：先正则（拒绝奇怪字符，避免把攻击串送去查库），
     * 再存在性（拒绝拼错的名字，让 404 与 400 分得清）。
     */
    private String requireTable(String table) {
        if (table == null || !SAFE_TABLE.matcher(table).matches()) {
            throw new StateStoreException(StateStoreException.Kind.INVALID,
                    "非法的状态表名（只允许字母、数字、下划线、连字符，1-64 位）: " + table);
        }
        String db = database();
        if (mapper.countTable(db, table) == 0) {
            throw new StateStoreException(StateStoreException.Kind.NOT_FOUND,
                    "状态表不存在: " + db + "." + table);
        }
        return db;
    }

    private void requireSlot(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            throw new StateStoreException(StateStoreException.Kind.INVALID, "slotId 不能为空");
        }
    }

    // ==================================================================================
    // 解析与格式化
    // ==================================================================================

    /**
     * {@code <userId>:<sessionId>} → 两段。
     *
     * <p>按<b>第一个</b>冒号切：框架拼的时候 userId 在前，而 sessionId 理论上可能含冒号
     * （框架只禁止斜杠）。本项目自己生成的 sessionId 一律过消毒（冒号会被换成连字符），
     * 所以实际不会遇到；真遇到了也只是显示得难看，不影响删除 —— 删除用的是原串。
     */
    /**
     * 出参前统一加工：拆出 userId/sessionId，并把时间归一成 ISO。
     *
     * <p><b>为什么必须在服务层做，而不是交给前端</b>：JDBC 取 DATETIME 出来是
     * {@code 2026-09-22 15:29:20} 这种空格分隔的形式，而带空格的日期字符串虽然能在
     * V8 / Firefox 里被 {@code new Date()} 解析，却不在 ECMAScript 规范里
     * （规范只接受简化 ISO 格式），部分浏览器会得到 Invalid Date。
     * 归一化放在唯一的出口上，所有列表/详情都一致；分散到前端格式化函数里，
     * 迟早会有某个新页面忘了过那道函数。
     */
    private void decorate(StateSlotRow row) {
        String[] parts = split(row.getSlotId());
        row.setUserId(parts[0]);
        row.setSessionId(parts[1]);
        row.setCreatedAt(iso(row.getCreatedAt()));
        row.setUpdatedAt(iso(row.getUpdatedAt()));
    }

    private static String[] split(String slotId) {
        if (slotId == null) {
            return new String[]{"(空)", "(空)"};
        }
        int colon = slotId.indexOf(':');
        if (colon < 0) {
            // 没有分区前缀：老数据或手工写入的行。原样显示，不要编一个 userId 出来。
            return new String[]{"(未分区)", slotId};
        }
        return new String[]{slotId.substring(0, colon), slotId.substring(colon + 1)};
    }

    /**
     * {@code 2026-09-21 14:01:35} → {@code 2026-09-21T14:01:35}。
     *
     * <p>JDBC 取 DATETIME 出来是空格分隔的形式，而带空格的日期字符串在部分浏览器里
     * 会被解析成 Invalid Date。统一成 ISO 后前端可以直接 {@code new Date(...)}。
     */
    private static String iso(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() >= 19 && v.charAt(10) == ' '
                ? v.substring(0, 10) + "T" + v.substring(11)
                : v;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * 把 {@code agent_state} 的 JSON 压成页面能读的摘要。
     *
     * <p>字段名是<b>下划线</b>风格（框架的 wire 格式，实测：{@code session_id / cur_iter /
     * tasks_context} 等）。这里全部用 {@code path()} + 默认值访问，缺字段不抛异常 ——
     * 框架版本升级增删字段时，本方法应当退化成"少显示一块"，而不是让整个详情页 500。
     */
    private AgentStateViews.Snapshot parse(String json, StateSlotRow target) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        JsonNode context = root.path("context");
        int messages = context.isArray() ? context.size() : 0;

        List<AgentStateViews.MessagePreview> preview = new ArrayList<>();
        if (context.isArray()) {
            int from = Math.max(0, context.size() - PREVIEW_LIMIT);
            for (int i = from; i < context.size(); i++) {
                preview.add(preview(context.get(i)));
            }
        }

        JsonNode tasksNode = root.path("tasks_context").path("tasks");
        List<AgentStateViews.TaskView> tasks = new ArrayList<>();
        int tasksTotal = 0;
        if (tasksNode.isArray()) {
            tasksTotal = tasksNode.size();
            for (JsonNode task : tasksNode) {
                if (tasks.size() >= TASKS_LIMIT) {
                    break;
                }
                tasks.add(new AgentStateViews.TaskView(
                        clip(text(task, "id"), SHORT_TEXT_LIMIT),
                        clip(text(task, "subject"), TASK_TEXT_LIMIT),
                        clip(text(task, "state"), SHORT_TEXT_LIMIT),
                        clip(text(task, "owner"), SHORT_TEXT_LIMIT)));
            }
        }

        List<String> groups = new ArrayList<>();
        JsonNode groupsNode = root.path("tool_context").path("activated_groups");
        if (groupsNode.isArray()) {
            for (JsonNode node : groupsNode) {
                if (groups.size() >= TOOL_GROUPS_LIMIT) {
                    break;
                }
                groups.add(clip(node.asText(""), SHORT_TEXT_LIMIT));
            }
        }

        JsonNode plan = root.path("plan_mode_context");
        JsonNode planFileNode = plan.path("current_plan_file");
        String planFile = planFileNode.isMissingNode() || planFileNode.isNull()
                ? null
                : clip(planFileNode.asText(null), PATH_TEXT_LIMIT);

        return new AgentStateViews.Snapshot(
                target.getBytes() == null ? 0L : target.getBytes(),
                target.getVersion() == null ? 0L : target.getVersion(),
                target.getCreatedAt(),
                target.getUpdatedAt(),
                clip(root.path("summary").asText(""), SUMMARY_TEXT_LIMIT),
                messages,
                root.path("cur_iter").asInt(0),
                root.path("shutdown_interrupted").asBoolean(false),
                tasksTotal,
                tasks,
                plan.path("plan_active").asBoolean(false),
                planFile,
                groups,
                clip(root.path("permission_context").path("mode").asText(""), SHORT_TEXT_LIMIT),
                preview);
    }

    /** 一条消息 → 预览。content 既可能是对象（单块）也可能是数组（多块），两条路都要走。 */
    private AgentStateViews.MessagePreview preview(JsonNode message) {
        String role = message.path("role").asText("");
        String timestamp = message.path("timestamp").asText("");
        JsonNode content = message.path("content");

        List<String> parts = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode block : content) {
                kinds.add(block.path("type").asText(""));
                collect(block, parts);
            }
        } else if (content.isObject()) {
            kinds.add(content.path("type").asText(""));
            collect(content, parts);
        }

        String joined = clip(String.join(" | ", parts).trim(), PREVIEW_TEXT_LIMIT);
        return new AgentStateViews.MessagePreview(role, kindOf(kinds), joined, timestamp);
    }

    /**
     * 一个内容块 → 一行可读文本。
     *
     * <p>四种块的正文位置各不相同（text 在 {@code text}、thinking 在 {@code thinking}、
     * 工具调用在 {@code name}、工具结果在 {@code output[].text}），所以必须分开取；
     * 加前缀是为了让人一眼分出"这是模型在想"还是"这是工具返回"——
     * 排查问题时这两者的可信度完全不同。
     */
    private void collect(JsonNode block, List<String> parts) {
        String type = block.path("type").asText("");
        switch (type) {
            case "text" -> parts.add(block.path("text").asText(""));
            case "thinking" -> {
                String thinking = block.path("thinking").asText("");
                if (!thinking.isBlank()) {
                    parts.add("[思考] " + thinking);
                }
            }
            case "tool_use" -> parts.add("[调用] " + block.path("name").asText(""));
            case "tool_result" -> {
                JsonNode output = block.path("output");
                String first = "";
                if (output.isArray() && !output.isEmpty()) {
                    first = output.get(0).path("text").asText("");
                }
                parts.add("[返回] " + block.path("name").asText("")
                        + (first.isBlank() ? "" : " → " + first));
            }
            default -> {
                // 未知块类型：不去猜它的结构，但也别把这条消息整体丢掉 —— 名字本身就是线索。
                String name = block.path("name").asText("");
                parts.add(name.isBlank() ? ("[" + (type.isBlank() ? "未知块" : type) + "]") : ("[" + type + "] " + name));
            }
        }
    }

    /** 多个块类型合成一个 kind：只有一个就照抄，多个且不同则为 mixed。 */
    private static String kindOf(List<String> kinds) {
        if (kinds.isEmpty()) {
            return "unknown";
        }
        String first = kinds.get(0).isBlank() ? "unknown" : kinds.get(0);
        for (String kind : kinds) {
            if (!kind.equals(first)) {
                return "mixed";
            }
        }
        return first;
    }

    /** 取字符串字段，缺失或 null 一律回 null（而不是空串），让页面能区分"没有"和"是空的"。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    /**
     * 按字符数截断，超长则补省略号。
     *
     * <p>按<b>字符</b>而不是字节：这些字段都是给人读的，截在半个汉字上比多传几 KB 更糟。
     * 上限是"防止响应体失控"的兜底，不是精确的分页边界，所以字符/字节的差别不重要。
     */
    private static String clip(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "…";
    }
}
