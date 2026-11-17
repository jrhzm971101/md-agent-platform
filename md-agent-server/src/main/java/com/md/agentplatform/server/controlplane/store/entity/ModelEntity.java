package com.md.agentplatform.server.controlplane.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * {@code md_model} 的行模型 —— 一个模型一条连接 + 一套生成参数。
 *
 * <h2>一、为什么它实现 {@link GovernedRow}</h2>
 * 模型现在是<b>第六类受治理资源</b>：和 prompt / skill / mcp 一样有草稿、发布、版本快照与回滚。
 * 理由不是"整齐"，而是端点与 apiKey 这类东西改错了需要能一键退回上一版 ——
 * 一个改坏的端点会让所有引用它的 agent 一起失效，靠人记住旧值回填是不现实的。
 *
 * <h2>二、它<b>不</b>实现的那部分：内容与装配的边界</h2>
 * 这一行只描述"怎么连模型"，不描述"用哪个模型跑哪个 agent" ——
 * 后者的落点是 {@code md_agent_runtime.model_ref}（以及记忆域的另外两列）。
 * 于是"换一个 agent 的模型"和"改一个模型的端点"是两件互不干扰的事，
 * 各自有各自的版本与影响面（前者影响一个 agent，后者影响所有引用它的 agent）。
 *
 * <h2>三、api_key 明文在行模型里，打码在接口层</h2>
 * 存储层不做遮掩：遮掩后的值无法回填表单，页面一保存就会把真密钥覆盖成 {@code ******}
 * （这个坑在渠道凭据上已经踩过一次，见 ChannelController 的类注释）。
 * 所以打码只发生在<b>列表</b>接口，详情接口给明文。
 *
 * <h2>四、字段命名与前端一一对应</h2>
 * 列名 snake_case ↔ 字段 camelCase 由 MyBatis-Plus 的默认映射规则完成（map-underscore-to-camel-case）。
 * {@code is_default / isDefault} 这一对比较特殊：{@code isDefault} 的 getter 会被 Lombok 生成为
 * {@code getIsDefault()}（布尔包装类型 + is 前缀字段名），正好与列名对上，不需要额外的注解。
 */
@Getter
@Setter
@TableName(value = "md_model", autoResultMap = true)
public class ModelEntity implements GovernedRow {

    /**
     * 引用键 —— 页面上唯一允许被 {@code model_ref} 填的值。
     *
     * <p>为什么与 display-name 分开：model_key 会被写进 {@code md_agent_runtime.model_ref}，
     * 是装配期的解析键，改一次等于所有引用它的 agent 集体失效；而展示名只是给人看的。
     * 这两件事挂在一列上，就会出现"为了改个显示名字，得批量改 agent 配置"的荒唐需求
     *（同因不同果于 {@code AgentEntity.displayName} 的注释）。
     */
    @TableId(value = "model_key", type = IdType.INPUT)
    private String modelKey;

    /** 展示名（页面标题用它）。只做展示：不参与装配、不参与唯一键、不可被引用。 */
    private String displayName;

    /** 用途分组（对话 / 记忆归并 / 向量化…）。仅页面筛选，装配层读不到它。 */
    private String purpose;

    /** 厂商标识（deepseek / ark / openai…）。仅展示与筛选：路由实际只看 base_url。 */
    private String provider;

    /** OpenAI 兼容端点根，例如 https://api.deepseek.com。 */
    private String baseUrl;

    /** 密钥。列表接口打码、详情接口明文，见类注释第三节。 */
    private String apiKey;

    /** 厂商侧模型名，例如 deepseek-v4-flash。 */
    private String modelName;

    /** 补全路径；空 = 用框架默认（/v1/chat/completions）。 */
    private String endpointPath;

    /**
     * 回填格式化器：{@code default} | {@code deepseek-reasoning}。
     *
     * <p>为什么做成一个可选值而不是让装配层"看 base_url 猜厂商"：DeepSeek 的思考模式要求
     * 把 reasoning_content 原样回传，不回传就 400 —— 这是厂商特化行为，必须显式声明。
     * 用猜的会在接入下一家兼容厂商时静默猜错，而错误的形态只有 run 到一半才暴露。
     */
    private String formatter;

    /** low | medium | high；空 = 不下发（用厂商默认）。 */
    private String reasoningEffort;

    /** NULL = 不下发。用包装类型而不是 double：0 与"没配"在模型参数上语义完全不同。 */
    private Double temperature;

    private Double topP;

    private Integer maxTokens;

    /** 对应框架 {@code OpenAIChatModel.Builder.contextWindowSize}；NULL = 不下发。 */
    private Integer contextWindowSize;

    private String description;

    @TableField(value = "biz_tags", typeHandler = JacksonTypeHandler.class)
    private List<String> bizTags;

    /**
     * 是否全局兜底模型：agent 没声明 {@code model_ref} 时用它。
     *
     * <p>全表最多一个 {@code true}，由 Store 在事务内保证（写入新默认值前先清掉旧的），
     * 不是数据库约束 —— 理由见建表脚本第 11 节的注释。
     */
    private Boolean isDefault;

    /** 停用后不参与装配。 */
    private Boolean enabled;

    private String status;

    private Integer version;

    private Integer publishedVersion;

    private Long publishedAtMs;

    private Long createdAtMs;

    private Long updatedAtMs;

    private String createdBy;

    private String updatedBy;
}
