package com.md.agentplatform.server.model;

import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;

import java.util.List;

/**
 * 模型页的接口形状 —— 与前端 {@code src/api/controlplane/types.ts} 里的 ModelRow / ModelDetail 一一对应。
 *
 * <h2>为什么列表行是一个"摊平"的 record，而不是直接回实体</h2>
 * 列表页要显示三样实体里没有的东西：
 * <ul>
 *   <li><b>打码后的密钥</b>：实体里是明文，绝不允许原样回给列表接口（见 {@link #apiKeyMask}）；</li>
 *   <li><b>引用它的 agent 数</b>：来自反查，不在实体上；</li>
 *   <li><b>近 7 日 token / 执行次数</b>：来自 run 表的聚合。</li>
 * </ul>
 * 把这三样塞回实体等于让实体带上一批 {@code @TableField(exist = false)} 的展示字段 ——
 * 存储细节与展示口径就混在一处了（这条在项目里已被明确否定，见 {@code AgentEntity} 的类注释）。
 *
 * <h2>密钥为什么整串打码，不做"前 6 后 4"</h2>
 * 与渠道凭据同一套约定（{@code ChannelController} 的 MASK）：部分暴露只对"人肉比对两个 key 是否相同"
 * 有帮助，而那件事本来就不该在列表页做；代价却是把密钥的一截写进了浏览历史、日志与截屏。
 * 要看真值请走详情接口 —— 那里的用途是回填表单，明文是必须的。
 */
public final class ModelViews {

    private ModelViews() {
    }

    /** 列表行：实体全字段（密钥打码）+ 引用与用量。 */
    public record ModelRow(
            String modelKey,
            String displayName,
            String purpose,
            String provider,
            String baseUrl,
            /** 打码后的密钥（{@code ******}）；密钥为空时是空串。 */
            String apiKeyMask,
            String modelName,
            String endpointPath,
            String formatter,
            String reasoningEffort,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Integer contextWindowSize,
            String description,
            List<String> bizTags,
            boolean isDefault,
            boolean enabled,
            String status,
            Integer version,
            Integer publishedVersion,
            Long publishedAtMs,
            Long createdAtMs,
            Long updatedAtMs,
            String createdBy,
            String updatedBy,
            /** 引用它的 agent 数（含主模型 / 兜底 / 子 agent / 记忆压缩四处引用）。 */
            int agentCount,
            /**
             * 靠它兜底的 agent 数（{@code md_agent_runtime.model_ref} 为空的那批）；
             * 非默认模型恒为 0。
             *
             * <p>为什么要单独一格：这批 agent 「用」这个模型，但<b>不引用</b>它 ——
             * 它们不会出现在 {@link #agentCount} 里。只给 agentCount，页面上就会出现
             * "0 个 agent 在用 · 近 7 日 66k token" 这种自相矛盾的画面。
             */
            int fallbackForAgents,
            /** 近 7 日 token 合计；无数据为 0。 */
            long tokens7d,
            /** 近 7 日执行次数；无数据为 0。 */
            long runs7d) {
    }

    /**
     * 详情：<b>明文密钥</b> + 引用清单 + 用量。
     *
     * <p>为什么这里可以嵌一个实体进去：详情接口的载荷<b>就是</b>编辑表单要回填的东西，
     * 而表单提交时后端收的也是同一个实体（PUT /api/models/{key}）。两者形状一致，
     * 页面就不需要一份"从展示结构拼回提交结构"的映射代码 —— 那正是最容易漏字段的地方。
     */
    public record ModelDetail(
            ModelEntity model,
            int agentCount,
            long tokens7d,
            long runs7d,
            /** 引用它的 agent 名（已去重排序），页面直接列出来。 */
            List<String> usedByAgents) {
    }

    /** 默认值表里的一行。{@code note} 是人话补充，页面显示为灰色小字。 */
    public record DefaultsItem(String label, String value, String note) {
    }

    /**
     * 「调用参数默认值」—— 页面上这一块必须能说清"没配的那部分去哪了"。
     *
     * <p>它的内容全部来自 {@code application.yml} 的 {@code spring.ai.model.*}
     *（{@link com.md.agentplatform.server.config.ModelConfigProperties}）：那是框架默认值之外
     * 最后一级兜底，也是 DB 里没有默认模型时所有 agent 实际在用的连接。
     * 把它显示出来，页面上"模型表是空的但 agent 跑得好好的"就不再是个谜。
     */
    public record ModelDefaults(
            /** 当前生效的兜底模型键；{@code null} = 没有任何兜底（装配期会直接失败）。 */
            String fallbackKey,
            /** 兜底来源：{@code md_model} 还是 {@code application.yml}。 */
            String fallbackSource,
            List<DefaultsItem> items) {
    }

    /**
     * 连通性探测结果。
     *
     * @param ok     HTTP 2xx 才算通
     * @param detail 成功时是"HTTP 200 · 12 个模型 · 340ms"，失败时是异常原因（原样透传，不做美化 ——
     *               401 / DNS 解析失败 / 连接超时这三种原因对应三种完全不同的修法）
     */
    public record ConnectionTest(boolean ok, String detail) {
    }
}
