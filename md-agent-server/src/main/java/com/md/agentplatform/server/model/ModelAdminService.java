package com.md.agentplatform.server.model;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.config.ModelConfigProperties;
import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;
import com.md.agentplatform.server.controlplane.store.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型管理面 —— 列表 / 详情 / 默认值 / 连通性探测。
 *
 * <h2>一、这一层做三件"SQL 里做不到或不该做"的事</h2>
 * <ol>
 *   <li><b>打码</b>：列表接口只回 {@code ******}，明文只出现在详情（见 {@link ModelViews} 的类注释）；</li>
 *   <li><b>用量归属</b>：{@code md_agent_run} 只记 agent，不记模型。用量要按模型显示，
 *       就得把"agent 声明的 model_ref"接上去 —— 而 {@code model_ref} 为空的 agent
 *       还要归到<b>当前默认模型</b>名下（否则这批用量在页面上凭空消失）；</li>
 *   <li><b>探测</b>：连通性测试要真发一次 HTTP，不能靠"配置看起来对"就报绿色。</li>
 * </ol>
 *
 * <h2>二、为什么校验放在这一层而不是控制器</h2>
 * 控制器的职责是"把 HTTP 翻译成领域调用"，校验规则属于领域。而且校验是<b>收集式</b>的
 *（{@link ControlPlaneValidationException} 一次带全部问题），写在控制器里会让每个 handler
 * 各带一段拼装逻辑。写库本身（含 is_default 互斥）仍然在 Store 里，事务边界不变。
 */
@Service
public class ModelAdminService {

    private static final Logger log = LoggerFactory.getLogger(ModelAdminService.class);

    /** 与渠道凭据同一套打码取值（{@code ChannelController.MASK}）。 */
    private static final String MASK = "******";

    /** 用量统计的窗口：近 7 日，与页面上那一列的表头一致。 */
    private static final int USAGE_WINDOW_DAYS = 7;

    /** 探测超时。取值理由：正常 {@code /models} 在几百毫秒内返回，8 秒足够区分"慢"与"不通"。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);

    private final ModelMapper modelMapper;
    private final ControlPlaneStore store;
    private final DbModelFactory modelFactory;
    private final ModelConfigProperties fallbackConfig;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ModelAdminService(ModelMapper modelMapper, ControlPlaneStore store,
                             DbModelFactory modelFactory, ModelConfigProperties fallbackConfig) {
        this.modelMapper = modelMapper;
        this.store = store;
        this.modelFactory = modelFactory;
        this.fallbackConfig = fallbackConfig;
    }

    // ==================================================================================
    // 读
    // ==================================================================================

    /** 列表：密钥打码 + 引用数 + 近 7 日用量；按展示名排序（页面上是给人读的顺序）。 */
    public List<ModelViews.ModelRow> list() {
        List<ModelEntity> rows = modelMapper.selectList(Wrappers.<ModelEntity>lambdaQuery()
                .orderByAsc(ModelEntity::getDisplayName));
        Map<String, Integer> counts = agentCounts();
        Map<String, ModelUsageRow> usage = usage7d();
        // 「有几个 agent 在靠兜底」——这一格不加，默认模型的卡片上会出现
        // "0 个 agent 在用 · 近 7 日 66k token" 这种自相矛盾的画面：
        // 那 66k 恰恰来自没声明 model_ref 的 agent，而它们不出现在引用反查里。
        // 只在默认模型上算：别的模型行没有"兜底"这个语义。
        String currentDefaultKey = modelFactory.defaultModelKey();
        int fallbackForAgents = currentDefaultKey == null
                ? 0 : modelMapper.findAgentsWithoutModelRef().size();

        List<ModelViews.ModelRow> result = new ArrayList<>(rows.size());
        for (ModelEntity row : rows) {
            ModelUsageRow stat = usage.get(row.getModelKey());
            boolean isDefaultRow = Boolean.TRUE.equals(row.getIsDefault());
            result.add(new ModelViews.ModelRow(
                    row.getModelKey(),
                    row.getDisplayName(),
                    row.getPurpose(),
                    row.getProvider(),
                    row.getBaseUrl(),
                    mask(row.getApiKey()),
                    row.getModelName(),
                    row.getEndpointPath(),
                    row.getFormatter(),
                    row.getReasoningEffort(),
                    row.getTemperature(),
                    row.getTopP(),
                    row.getMaxTokens(),
                    row.getContextWindowSize(),
                    row.getDescription(),
                    row.getBizTags(),
                    isDefaultRow,
                    !Boolean.FALSE.equals(row.getEnabled()),
                    row.getStatus(),
                    row.getVersion(),
                    row.getPublishedVersion(),
                    row.getPublishedAtMs(),
                    row.getCreatedAtMs(),
                    row.getUpdatedAtMs(),
                    row.getCreatedBy(),
                    row.getUpdatedBy(),
                    counts.getOrDefault(row.getModelKey(), 0),
                    isDefaultRow ? fallbackForAgents : 0,
                    stat == null ? 0L : stat.getTokens(),
                    stat == null ? 0L : stat.getRuns()));
        }
        return result;
    }

    /** 详情：明文密钥（用于回填表单）+ 引用清单 + 用量。不存在返回 null（控制器回 404）。 */
    public ModelViews.ModelDetail detail(String modelKey) {
        ModelEntity row = modelMapper.selectById(modelKey);
        if (row == null) {
            return null;
        }
        ModelUsageRow stat = usage7d().get(modelKey);
        List<String> owners = new ArrayList<>(modelMapper.findAgentNamesByModelKey(modelKey));
        owners.sort(Comparator.naturalOrder());
        return new ModelViews.ModelDetail(row, owners.size(),
                stat == null ? 0L : stat.getTokens(), stat == null ? 0L : stat.getRuns(), owners);
    }

    /**
     * 「调用参数默认值」—— 页面上必须能解释"没配的那部分去哪了"。
     *
     * <p>内容随"有没有默认模型"而变：有默认模型就显示它的端点与参数；
     * 没有就显示 yml 兜底 Bean 的配置，并明确标出来源是 application.yml ——
     * 那正是"模型表是空的、agent 却跑得好好的"这个现象的答案。
     */
    public ModelViews.ModelDefaults defaults() {
        String defaultKey = modelFactory.defaultModelKey();
        ModelEntity row = defaultKey == null ? null : modelMapper.selectById(defaultKey);
        List<ModelViews.DefaultsItem> items = new ArrayList<>();

        String baseUrl = row != null ? row.getBaseUrl() : fallbackConfig.getBaseUrl();
        String modelName = row != null ? row.getModelName() : fallbackConfig.getModelName();
        String effort = row != null ? row.getReasoningEffort() : fallbackConfig.getReasoningEffort();
        String endpointPath = row != null ? row.getEndpointPath() : null;
        // yml 兜底那一路的 formatter 不是 null：ModelBeanConfig 里写死了 DeepSeekReasoningBackfillFormatter。
        // 这里曾经写的是 null，页面上于是显示成 "default" —— 而它恰恰是排查 400 的关键线索
        //（DeepSeek 思考模式不回填 reasoning_content 就 400，看到 "default" 的人不会往这儿想）。
        String formatter = row != null ? row.getFormatter() : DbModelFactory.FORMATTER_DEEPSEEK_REASONING;

        items.add(new ModelViews.DefaultsItem("兜底来源",
                row != null ? "md_model · " + defaultKey : "openAIChatModel（application.yml）",
                "agent 没声明 model-ref 时用它；改这里会影响所有未声明模型的 agent。"));
        items.add(new ModelViews.DefaultsItem("端点", blankToDash(baseUrl),
                row != null ? null : "来自 application.yml 的 spring.ai.model.base-url。"));
        items.add(new ModelViews.DefaultsItem("模型名", blankToDash(modelName), null));
        items.add(new ModelViews.DefaultsItem("reasoning-effort", blankToDash(effort),
                blank(effort) ? "未配置 = 不下发，由厂商默认值接管。" : null));
        items.add(new ModelViews.DefaultsItem("端点路径",
                blank(endpointPath) ? "/v1/chat/completions（框架默认）" : endpointPath, null));
        items.add(new ModelViews.DefaultsItem("formatter",
                blank(formatter) ? "default" : formatter,
                row != null
                        ? "deepseek-reasoning = 思考模式回填 reasoning_content（该字段不回传时厂商会返回 400）。"
                        : "来自 application.yml 的 openAIChatModel Bean（ModelBeanConfig 里写死，不是可选项）。"));
        items.add(new ModelViews.DefaultsItem("温度 / top-p / max-tokens", "未设置 = 不下发",
                "留空表示交给厂商默认值；不要填 0，0 是另一个语义（真的要 0）。"));
        items.add(new ModelViews.DefaultsItem("向量化模型", blankToDash(fallbackConfig.getEmbeddingModel()),
                "来自 application.yml，尚未纳入模型表（RAG 检索在用）。"));

        return new ModelViews.ModelDefaults(defaultKey, row != null ? "md_model" : "application.yml", items);
    }

    // ==================================================================================
    // 写（校验在这一层，落库在 Store；事务边界不越层）
    // ==================================================================================

    /** 新建草稿。{@code false} = 同名 model_key 已存在（控制器回 409）。 */
    public boolean create(ModelEntity model, String operator) {
        validate(model);
        return store.createModel(model, operator);
    }

    /** 保存草稿（version +1，状态回 DRAFT）。{@code false} = 不存在（控制器回 404）。 */
    public boolean save(String modelKey, ModelEntity model, String operator) {
        validate(model);
        model.setModelKey(modelKey);
        return store.saveModel(model, operator);
    }

    /** 删除前的引用清单；控制器据此决定 409 的 body。 */
    public List<String> referencingAgents(String modelKey) {
        return modelMapper.findAgentNamesByModelKey(modelKey);
    }

    /**
     * 连通性探测：真发一次 {@code GET {baseUrl}/models}。
     *
     * <p>为什么探这个端点而不是真跑一次对话：它是 OpenAI 兼容协议的公共列表接口，
     * 不消耗 token、不产生费用、也不会因为提示词被厂商的安全策略拦掉，
     * 但它同样会走「DNS 解析 → TLS 握手 → 鉴权 → 业务响应」这四步 ——
     * 这四步正好覆盖了配错端点、密钥失效、网络不通这三类实际故障。
     * 真跑一次对话反而会引入"模型在思考"这种假失败。
     *
     * <p>失败原因<b>原样透传</b>（不美化）：401、UnknownHost、ConnectException 三种原因
     * 对应三种完全不同的修法，把它们统一成一句"测试失败"等于把信息删掉。
     */
    public ModelViews.ConnectionTest testConnection(String modelKey) {
        ModelEntity row = modelMapper.selectById(modelKey);
        if (row == null) {
            throw new IllegalArgumentException("模型不存在: " + modelKey);
        }
        String url = stripTrailingSlash(row.getBaseUrl()) + "/models";
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            return new ModelViews.ConnectionTest(false, "端点不是合法 URL: " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(PROBE_TIMEOUT)
                .header("Authorization", "Bearer " + row.getApiKey())
                .header("Accept", "application/json")
                .GET()
                .build();
        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            if (response.statusCode() / 100 != 2) {
                return new ModelViews.ConnectionTest(false, "HTTP " + response.statusCode()
                        + " · " + costMs + "ms · " + brief(response.body()));
            }
            return new ModelViews.ConnectionTest(true,
                    "HTTP " + response.statusCode() + " · " + modelCount(response.body()) + " · " + costMs + "ms");
        } catch (Exception e) {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.warn("[Model] 连通性探测失败 key={} url={} — {}", modelKey, url, e.toString());
            return new ModelViews.ConnectionTest(false,
                    e.getClass().getSimpleName() + "（" + costMs + "ms）: " + e.getMessage());
        }
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    /**
     * 内容校验 —— 收集全部问题一次返回。
     *
     * <p>只校验"装配期一定会用到"的字段：model-key / 展示名 / 端点 / 密钥 / 模型名。
     * 生成参数一概不校验是否为空（空 = 不下发是合法状态），只在填了明显越界的值时提醒。
     */
    private void validate(ModelEntity model) {
        if (model == null) {
            throw new ControlPlaneValidationException("请求体为空");
        }
        List<String> violations = new ArrayList<>();
        if (blank(model.getModelKey())) {
            violations.add("model-key 不能为空（agent 的 model-ref 填的就是它）");
        }
        if (blank(model.getDisplayName())) {
            violations.add("展示名不能为空（列表与下拉里显示的就是它）");
        }
        if (blank(model.getBaseUrl())) {
            violations.add("端点（base-url）不能为空，例如 https://api.deepseek.com");
        } else if (!model.getBaseUrl().trim().startsWith("http")) {
            violations.add("端点必须以 http:// 或 https:// 开头，当前值: " + model.getBaseUrl());
        }
        if (blank(model.getApiKey())) {
            violations.add("密钥（api-key）不能为空");
        }
        if (blank(model.getModelName())) {
            violations.add("模型名（model-name）不能为空，例如 deepseek-v4-flash");
        }
        if (model.getTemperature() != null && (model.getTemperature() < 0 || model.getTemperature() > 2)) {
            violations.add("温度常见取值范围是 0 ~ 2，当前值: " + model.getTemperature());
        }
        if (model.getTopP() != null && (model.getTopP() < 0 || model.getTopP() > 1)) {
            violations.add("top-p 取值范围是 0 ~ 1，当前值: " + model.getTopP());
        }
        if (!violations.isEmpty()) {
            throw new ControlPlaneValidationException(violations);
        }
    }

    /** model_key → 引用方数量。 */
    private Map<String, Integer> agentCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (ModelAgentCountRow row : modelMapper.agentCounts()) {
            counts.put(row.getModelKey(), row.getAgentCount());
        }
        return counts;
    }

    /**
     * model_key → 近 7 日用量；并把 {@code model_ref} 为空的那批归到当前默认模型名下。
     *
     * <p>归属这一步是必须的：没声明 model_ref 的 agent 用的就是兜底模型，
     * 它们的用量如果因为"模型键是 NULL 从而对不上任何一行"而消失，
     * 页面上就会出现"各模型用量加起来比 run 表总量少一截"这种对不上账的现象。
     * 反过来，平台<b>没有</b>默认模型时（所有未声明的 agent 都走 yml 兜底 Bean），
     * 这批用量确实不属于任何一行，那就如实不显示 —— 不编一个不存在的模型名。
     */
    private Map<String, ModelUsageRow> usage7d() {
        long since = System.currentTimeMillis() - Duration.ofDays(USAGE_WINDOW_DAYS).toMillis();
        Map<String, ModelUsageRow> usage = new HashMap<>();
        ModelUsageRow unattributed = null;
        for (ModelUsageRow row : modelMapper.usageSince(since)) {
            if (blank(row.getModelKey())) {
                unattributed = row;
            } else {
                usage.put(row.getModelKey(), row);
            }
        }
        String defaultKey = modelFactory.defaultModelKey();
        if (unattributed != null && !blank(defaultKey)) {
            ModelUsageRow existing = usage.get(defaultKey);
            if (existing == null) {
                ModelUsageRow merged = new ModelUsageRow();
                merged.setModelKey(defaultKey);
                merged.setRuns(unattributed.getRuns());
                merged.setTokens(unattributed.getTokens());
                usage.put(defaultKey, merged);
            } else {
                existing.setRuns(existing.getRuns() + unattributed.getRuns());
                existing.setTokens(existing.getTokens() + unattributed.getTokens());
            }
        }
        return usage;
    }

    /** {@code ******}；密钥为空时回空串（而不是打码出一串星号，那会让人以为配了东西）。 */
    private static String mask(String apiKey) {
        return blank(apiKey) ? "" : MASK;
    }

    /** 响应体里的模型数量；结构不符时回"未识别模型列表"。 */
    private String modelCount(String body) {
        try {
            JsonNode data = json.readTree(body).path("data");
            if (data.isArray()) {
                return data.size() + " 个模型";
            }
        } catch (Exception ignored) {
            // 不是 JSON、或没有 data 数组：不影响"通不通"的结论，如实说不识别即可
        }
        return "未识别模型列表";
    }

    /** 错误响应体截断到 200 字：厂商回的错误常常带着整段说明，全贴进通知里没法读。 */
    private static String brief(String body) {
        if (body == null) {
            return "(空响应体)";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToDash(String value) {
        return blank(value) ? "未设置" : value;
    }
}