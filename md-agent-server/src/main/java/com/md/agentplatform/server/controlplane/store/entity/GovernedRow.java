package com.md.agentplatform.server.controlplane.store.entity;

/**
 * 「受治理行」—— 四张资源主表（md_agent / md_prompt / md_mcp_server / md_skill_meta）
 * <b>共有的那 8 个治理字段</b>的抽象。
 *
 * <h2>一、为什么值得抽这一层</h2>
 * 四张表的业务内容完全不同（agent 定义 / 提示词正文 / MCP 端点 / 技能元数据），
 * 但「草稿-发布-版本」这套生命周期字段是逐字相同的。不抽这一层的话，
 * 「新建 = version 1 + DRAFT + 时间戳」「保存 = version+1 + DRAFT + 保留创建人」
 * 这三段逻辑要在 {@code MyBatisControlPlaneStore} 里各写四遍，
 * 而它们必须永远一致 —— 一旦某张表漏改，症状是"这个页面的版本号不涨了"，
 * 极难联想到是复制粘贴漏了一行。
 *
 * <h2>二、它是"行模型"的一部分，不是领域模型</h2>
 * 这四个字段在库里就是列，实现方是四张表的实体类；领域对象（{@code AgentDefinition}）不认识它。
 * 所以本接口住在 {@code store.entity} 包，与实体放在一起。
 *
 * <h2>三、实现方由 Lombok 自动满足</h2>
 * 四个实体都是 {@code @Getter @Setter}，getter/setter 签名与本接口逐字对应，
 * 因此实现一个实体只需在类声明上加 {@code implements GovernedRow}，不必手写方法。
 * <b>改实体字段类型时注意</b>：这里声明的是 {@code Integer / Long} 包装类型，
 * 换成原始类型就编译不过 —— 这是有意的，见 {@code AgentEntity} 关于三态的说明。
 */
public interface GovernedRow {

    /** 草稿态：内容可继续修改，未上线。 */
    String STATUS_DRAFT = "DRAFT";

    /** 已发布态：运行期装配读的就是这个版本的内容。 */
    String STATUS_PUBLISHED = "PUBLISHED";

    String getStatus();

    void setStatus(String status);

    /** 内容版本号，单调递增；每次保存 / 发布 / 回滚都可能推进它。 */
    Integer getVersion();

    void setVersion(Integer version);

    /** 已发布版本号；null = 从未发布。 */
    Integer getPublishedVersion();

    void setPublishedVersion(Integer publishedVersion);

    /** 最近一次发布时间；null = 从未发布。 */
    Long getPublishedAtMs();

    void setPublishedAtMs(Long publishedAtMs);

    Long getCreatedAtMs();

    void setCreatedAtMs(Long createdAtMs);

    Long getUpdatedAtMs();

    void setUpdatedAtMs(Long updatedAtMs);

    String getCreatedBy();

    void setCreatedBy(String createdBy);

    String getUpdatedBy();

    void setUpdatedBy(String updatedBy);

    /**
     * 新建：version=1、DRAFT、发布时间清空，创建与修改信息都是本次。
     *
     * <p>为什么由 Store 覆盖而不是听页面的：这些是存储层的职责。
     * 若允许请求体带 version/status，页面就能凭空造出"version=99 且已发布"的脏状态，
     * 而它对不上任何一次真实发布。
     */
    default void initGovernance(String operator, long now) {
        setStatus(STATUS_DRAFT);
        setVersion(1);
        setPublishedVersion(null);
        setPublishedAtMs(null);
        setCreatedAtMs(now);
        setUpdatedAtMs(now);
        setCreatedBy(operator);
        setUpdatedBy(operator);
    }

    /**
     * 保存草稿 / 回滚：内容被整体替换，但两组信息<b>必须沿用旧行</b>。
     * <ul>
     *   <li>创建信息（created_at / created_by）—— 它描述"这条资源什么时候被建出来的"，
     *       跟内容改了几版无关；</li>
     *   <li>发布信息（published_version / published_at_ms）—— 它描述"线上现在跑的是哪一版"。
     *       保存草稿不动线上，所以发布信息不该跟着草稿走。
     *       （回滚同理：回滚是"准备一个旧内容的新版本"，不是直接上线，见设计文档 §4.4。）</li>
     * </ul>
     * 版本号 +1：哪怕这次提交的内容跟上一版一模一样，也照加 ——
     * 版本号表达的是"提交次数"，不是"内容差异"，否则页面无法解释"我明明保存了，版本号没动"。
     */
    default void advanceDraft(GovernedRow existing, String operator, long now) {
        setStatus(STATUS_DRAFT);
        setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        setPublishedVersion(existing.getPublishedVersion());
        setPublishedAtMs(existing.getPublishedAtMs());
        setCreatedAtMs(existing.getCreatedAtMs());
        setCreatedBy(existing.getCreatedBy());
        setUpdatedAtMs(now);
        setUpdatedBy(operator);
    }

    /**
     * 发布：只动状态与发布信息，<b>不改内容、也不推进 version</b>。
     *
     * <p>version 在这里保持不动是有意的：发布不是一次内容变更，
     * 它只是给"当前这一版"盖一个"上线"的戳。若发布也 +1，
     * 就会出现"快照里的 v5 内容和主表的 v6 内容完全相同"这种噪音，
     * 而 published_version 的存在本来就是为了区分这两件事。
     */
    default void markPublished(long now, String operator) {
        if (getVersion() == null) {
            setVersion(1);
        }
        setStatus(STATUS_PUBLISHED);
        setPublishedVersion(getVersion());
        setPublishedAtMs(now);
        setUpdatedAtMs(now);
        setUpdatedBy(operator);
    }
}
