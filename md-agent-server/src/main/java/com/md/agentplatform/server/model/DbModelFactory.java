package com.md.agentplatform.server.model;

import com.md.agentplatform.server.config.DeepSeekReasoningBackfillFormatter;
import com.md.agentplatform.server.config.ModelConfigProperties;
import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;
import com.md.agentplatform.server.controlplane.store.mapper.ModelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@code md_model} 的一行变成框架要的 {@link Model} 实例 —— 装配期唯一的模型解析入口。
 *
 * <h2>一、它取代了什么</h2>
 * 改造前 {@code ConfigurableAgentBuilder.resolveModel} 只有一句 {@code getBean(ref, Model.class)}：
 * 模型是 {@code application.yml} 里那一个全局 Bean，页面上既看不到、也改不了，
 * {@code model_ref} 拼错只能在装配期炸出一句 "Bean 不存在"。
 * 现在解析顺序变成：<b>DB 优先、Bean 兜底</b>：
 * <ol>
 *   <li>{@code ref} 为空 → 库里的默认模型（{@code is_default=1} 且启用）；库里没有 → 兜底 Bean；</li>
 *   <li>{@code ref} 命中 {@code md_model.model_key} → 按行构建；</li>
 *   <li>{@code ref} 不是库里的键，但容器里有同名 Bean → 用它（<b>兼容存量</b>：
 *       改造前建的 agent 里 {@code model_ref} 写的就是 {@code openAIChatModel} 这种 Bean 名，
 *       让它们继续可装配，而不是逼所有人一次性改库）；</li>
 *   <li>都没有 → 抛异常并说明"既不在库里、也不是 Bean 名"。</li>
 * </ol>
 *
 * <h2>二、为什么必须有缓存，以及缓存凭什么不会读到脏数据</h2>
 * 每个 {@link OpenAIChatModel} 自带一份 HTTP 连接池与重试配置。装配 14 个 agent 就建 14 份、
 * 而它们指向的往往还是同一个端点，纯属浪费（改造前是所有 agent 共享同一个单例 Bean）。
 * 所以这里按 {@code model_key} 缓存实例。
 *
 * <p>缓存失效<b>不靠事件通知，靠内容戳</b>：每次解析都读一次行（主键查询，代价可忽略），
 * 拿 {@code updated_at_ms} 与缓存里记的戳比对，不一致就重建。
 * 于是"发布新版本""改完保存"这类动作天然会换掉实例，不需要谁记得调用 invalidate ——
 * 那种"记得调用"的约定迟早会漏一处，而漏掉的表现是"页面上改了、线上还是旧连接"。
 *（{@link #invalidate} 仍然保留给删除场景：行都没了就不该留着实例。）
 *
 * <h2>三、停用的模型为什么直接抛而不是退回默认</h2>
 * 静默回退到另一个模型，会让"我停用了一个贵模型"变成"账单上多了一笔没人看得懂的用量"。
 * 装配期大声失败，页面上就会看到"agent 装配失败：模型已停用 x"，指向明确。
 */
@Component
public class DbModelFactory {

    private static final Logger log = LoggerFactory.getLogger(DbModelFactory.class);

    /** yml 兜底模型的 Bean 名（见 {@code ModelBeanConfig}）。 */
    public static final String FALLBACK_BEAN = "openAIChatModel";

    /** {@code formatter} 列的取值之一：DeepSeek 思考模式回填。 */
    public static final String FORMATTER_DEEPSEEK_REASONING = "deepseek-reasoning";

    private final ModelMapper modelMapper;
    private final ApplicationContext applicationContext;
    private final ModelConfigProperties fallbackConfig;

    /** model_key → 已构建实例 + 构建时的内容戳（updated_at_ms）。 */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public DbModelFactory(ModelMapper modelMapper, ApplicationContext applicationContext,
                          ModelConfigProperties fallbackConfig) {
        this.modelMapper = modelMapper;
        this.applicationContext = applicationContext;
        this.fallbackConfig = fallbackConfig;
    }

    /**
     * 解析一个模型引用。
     *
     * @param modelRef {@code md_model.model_key} / 旧 Bean 名 / 空（空 = 用平台默认）
     * @return 可交给 builder 的模型实例
     * @throws IllegalStateException 引用既不在库里也不是 Bean 名，或引用的模型已被停用
     */
    public Model resolve(String modelRef) {
        String key = (modelRef == null || modelRef.isBlank()) ? defaultModelKey() : modelRef.trim();
        if (key == null || key.isBlank()) {
            // 库里没有任何默认模型 → 落回 yml 那个全局 Bean，行为与改造前完全一致
            return fallbackBean();
        }
        ModelEntity row = modelMapper.selectById(key);
        if (row == null) {
            if (applicationContext.containsBean(key)) {
                log.debug("[Model] {} 不是 md_model 里的键，按 Spring Bean 解析（存量兼容）", key);
                return applicationContext.getBean(key, Model.class);
            }
            throw new IllegalStateException("模型不存在: " + key
                    + "（既不在 md_model 表里，也不是 Spring Bean 名）");
        }
        if (Boolean.FALSE.equals(row.getEnabled())) {
            throw new IllegalStateException("模型已停用，不能用于装配: " + key);
        }
        return cached(row);
    }

    /** 库里的默认模型键；没有则返回 null（调用方据此落回 yml 兜底）。 */
    public String defaultModelKey() {
        ModelEntity row = modelMapper.selectOne(
                Wrappers.<ModelEntity>lambdaQuery()
                        .eq(ModelEntity::getIsDefault, true)
                        .eq(ModelEntity::getEnabled, true)
                        .last("LIMIT 1"));
        return row == null ? null : row.getModelKey();
    }

    /** 丢弃某个模型的缓存实例（删除时调用；改内容靠内容戳自动失效）。 */
    public void invalidate(String modelKey) {
        if (modelKey != null) {
            cache.remove(modelKey);
        }
    }

    /** yml 兜底 Bean 是否可用（页面上要据此提示"没有兜底模型时会装配失败"）。 */
    public boolean fallbackAvailable() {
        return applicationContext.containsBean(FALLBACK_BEAN);
    }

    // ==================== 内部 ====================

    private Model fallbackBean() {
        if (!applicationContext.containsBean(FALLBACK_BEAN)) {
            throw new IllegalStateException("没有任何可用的兜底模型：" + FALLBACK_BEAN + " Bean 也不存在");
        }
        return applicationContext.getBean(FALLBACK_BEAN, Model.class);
    }

    private Model cached(ModelEntity row) {
        long stamp = row.getUpdatedAtMs() == null ? 0L : row.getUpdatedAtMs();
        Cached hit = cache.get(row.getModelKey());
        if (hit != null && hit.stamp() == stamp) {
            return hit.model();
        }
        Model model = construct(row);
        cache.put(row.getModelKey(), new Cached(stamp, model));
        log.info("[Model] 已构建模型实例 key={} baseUrl={} modelName={} formatter={}",
                row.getModelKey(), row.getBaseUrl(), row.getModelName(), row.getFormatter());
        return model;
    }

    /** 行 → 框架实例。所有可空字段都是"没配就不下发"，与库里的三态语义一致。 */
    private Model construct(ModelEntity row) {
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (notBlank(row.getReasoningEffort())) {
            options.reasoningEffort(row.getReasoningEffort());
        }
        if (row.getTemperature() != null) {
            options.temperature(row.getTemperature());
        }
        if (row.getTopP() != null) {
            options.topP(row.getTopP());
        }
        if (row.getMaxTokens() != null) {
            options.maxTokens(row.getMaxTokens());
        }

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(row.getApiKey())
                .baseUrl(row.getBaseUrl())
                .modelName(row.getModelName())
                .generateOptions(options.build());
        if (notBlank(row.getEndpointPath())) {
            builder.endpointPath(row.getEndpointPath());
        }
        if (row.getContextWindowSize() != null && row.getContextWindowSize() > 0) {
            builder.contextWindowSize(row.getContextWindowSize());
        }
        if (FORMATTER_DEEPSEEK_REASONING.equalsIgnoreCase(row.getFormatter())) {
            builder.formatter(new DeepSeekReasoningBackfillFormatter());
        }
        return builder.build();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 缓存条目：实例 + 构建时的 {@code updated_at_ms}。 */
    private record Cached(long stamp, Model model) {
    }
}
