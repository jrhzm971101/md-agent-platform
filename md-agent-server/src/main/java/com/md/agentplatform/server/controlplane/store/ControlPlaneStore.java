package com.md.agentplatform.server.controlplane.store;

import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;
import com.md.agentplatform.server.controlplane.store.entity.McpServerEntity;
import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import com.md.agentplatform.server.controlplane.store.entity.SkillMetaEntity;
import com.md.agentplatform.server.controlplane.store.entity.WorkflowEntity;
import com.md.agentplatform.server.workflow.WorkflowSpec;
import io.agentscope.core.skill.AgentSkill;

import java.util.List;
import java.util.Map;

/**
 * 控制面元数据的<b>唯一存储入口</b> —— Agent / Prompt / Skill / MCP 四类资源全部经由它落库。
 *
 * <h2>一、它取代了什么</h2>
 * 改造前这套元数据有三个真相：Nacos AgentSpec、{@code workspace/agents/*.md}、
 * {@code workspace/agent-definitions/*.json}。改造后 DB 是唯一真相，
 * 本接口就是那个真相的读写门面 —— 页面、导入器、装配期解析全部只认它。
 *
 * <h2>二、事务边界在这里，且只在这里</h2>
 * 每个 <b>写</b> 方法都标了 {@code @Transactional}（本项目首个事务点，改造前全项目 0 处）。
 * 之所以必须成对落库而不是逐个方法各自提交：
 * <ul>
 *   <li><b>agent</b> 一个逻辑对象散在 6 张表里，保存是"整体替换"；
 *       中途失败若已提交，会留下"主表说自己是 v5，关联表还是 v4 的内容"这种无法自证的脏数据；</li>
 *   <li><b>发布</b>要同时改主表状态 + 落快照，只有一半成功的话，
 *       快照表里会多出一条"从未发布过的版本"，回滚时按它还原会得到一个不存在过的状态；</li>
 *   <li><b>skill</b> 要同时写官方内容表（另一 schema）和 {@code md_skill_meta}。</li>
 * </ul>
 * 实现里"整体替换"用的是事务内<b>先删后插</b>，而不是 {@code updateById}：
 * MyBatis-Plus 默认只更新非 null 字段，于是「把描述清空」这类操作会被静默忽略 ——
 * 页面显示已清空、库里其实还留着旧值。先删后插让"提交什么就存什么"成立，
 * 而事务保证其他连接读到的仍是旧行，不会出现"查不到"的窗口。
 *
 * <h2>三、返回值约定：false 是"业务上不成立"，异常是"数据不合法"</h2>
 * <ul>
 *   <li>{@code create*} 返回 false = 同名资源已存在（控制器回 409）；</li>
 *   <li>{@code save*} 返回 false = 目标资源不存在（控制器回 404）；</li>
 *   <li>引用完整性 / 版本不存在等问题抛 {@link ControlPlaneValidationException}，
 *       <b>在事务内</b>抛出，由调用方翻译成 HTTP 状态码。</li>
 * </ul>
 * 区分这两种失败是有意的：前者是并发下的正常竞争，用返回值表达；后者是用户填错了内容，
 * 需要把原因原样带回页面。
 *
 * <h2>四、读方法只返回"行"，不返回装配好的定义</h2>
 * {@code md_agent} 六张表 → {@code AgentDefinition} 的转换只发生在
 * {@code AgentDefinitionResolver} 一处（{@code AgentEntity} 的类注释已说明理由）。
 * 本接口因此只提供 {@link #loadAgentBundle} 这种"行束"读法。
 *
 * <h2>五、与官方框架的边界</h2>
 * skill 内容不落在本库：官方 {@code AgentSkillRepository} 是它的唯一权威读写路径
 * （跨 schema，见设计文档 §4.3.1）。本接口的 skill 方法内部转发给它，
 * 并额外维护 {@code md_skill_meta} 这份治理元数据。
 * <b>注意</b>：官方 {@code save()} 自开连接与事务，不参与本接口的事务，
 * 这是已知缺口（设计文档 §10.2）—— 顺序上先写官方表再写 meta，让最坏情况的残留偏向"多一个孤儿内容"，
 * 而不是"meta 说有、内容却读不出来"。
 */
public interface ControlPlaneStore {

    // ==================================================================================
    // 一、agent 定义（装配期读）
    // ==================================================================================

    /**
     * 全部 agent 的主表行，最近修改的在前。
     *
     * <p>只查主表：列表页显示的类型 / 状态 / 版本 / 启用都在主表上，
     * 不为了列表去 join 5 张关联表（14 个 agent 就是 70 次多余查询）。
     */
    List<AgentEntity> listAgents();

    /** 一个 agent 的主表行；不存在返回 null。 */
    AgentEntity findAgent(String agentName);

    /**
     * 一个 agent 的完整内容行束（主表 + 5 张关联表），供装配期拼 {@code AgentDefinition}。
     *
     * @return 不存在时返回 null；存在但某些关联表没行时，对应字段为 null（语义见
     *         {@link AgentDefinitionBundle}）
     */
    AgentDefinitionBundle loadAgentBundle(String agentName);

    /**
     * 全部 agent 的完整内容束 —— {@code AgentManager.init()} 的加载源。
     *
     * <p>实现上是「6 条全表查询 + 内存分组」，不是「逐个 agent 查 6 次」：
     * 后者在 14 个 agent 时是 84 条 SQL，且每加一个 agent 线性变差。
     * 控制面表的总量是"人手维护的元数据"，全表读回来的成本可以忽略。
     */
    List<AgentDefinitionBundle> loadAllAgentBundles();

    // ==================================================================================
    // 二、agent 写
    // ==================================================================================

    /**
     * 新建 agent：写主表（version=1、status=DRAFT）+ 关联表。
     *
     * <p>生命周期字段（version / status / created_at / created_by / published_*）由本方法覆盖，
     * 请求里带什么都不作数 —— 这些是存储层的职责，不该由页面决定。
     *
     * @return false = 同名 agent 已存在（未做任何写入）
     */
    boolean createAgent(AgentDefinitionBundle bundle, String operator);

    /**
     * 保存草稿：整体替换该 agent 的全部内容行，version 在原值上 +1，status 置 DRAFT。
     *
     * <p>版本号只在这里和发布/回滚时变化，草稿可以随便改，但<b>不落版本快照</b>
     * —— 否则反复编辑会把快照表灌爆（设计文档 §4.4）。
     *
     * @return false = 该 agent 不存在（未做任何写入）
     */
    boolean saveAgent(AgentDefinitionBundle bundle, String operator);

    /**
     * 物理删除 agent 的 6 张表数据（无级联、无软删，与 trace 表风格一致）。
     *
     * <p><b>不做引用保护</b>：调用方必须先查 {@link #findReferencingAgents}。
     * 这里不再阻止一次 —— 一个方法只承担一个职责，保护策略留给控制层，
     * 也方便将来给"强制删除"留口子（目前没有这个入口）。
     */
    void deleteAgent(String agentName);

    // ==================================================================================
    // 三、Prompt
    // ==================================================================================

    /** 全部 prompt，最近修改的在前（不含正文的场景由页面自己忽略 body 字段）。 */
    List<PromptEntity> listPrompts();

    /** 单个 prompt；不存在返回 null。 */
    PromptEntity findPrompt(String promptKey);

    /** 新建 prompt（status=DRAFT、version=1）。@return false = 该 prompt-key 已存在 */
    boolean createPrompt(PromptEntity prompt, String operator);

    /** 保存 prompt 草稿（整体替换，version +1，status=DRAFT）。@return false = 不存在 */
    boolean savePrompt(PromptEntity prompt, String operator);

    /**
     * 删除 prompt。
     *
     * <p>控制器应先用 {@link #findReferencingAgents} 拦截"被引用"的情况（409 + 使用方列表）；
     * 本方法不再重复判断 —— 引用保护是控制层的策略，存储层只负责删。
     */
    void deletePrompt(String promptKey);

    // ==================================================================================
    // 四、MCP 服务端
    // ==================================================================================

    /** 全部 MCP 服务端，最近修改的在前。 */
    List<McpServerEntity> listMcpServers();

    /** 单个 MCP 服务端；不存在返回 null。 */
    McpServerEntity findMcpServer(String serverName);

    /** 新建 MCP（status=DRAFT、version=1、enabled 缺省为 true）。@return false = 已存在 */
    boolean createMcpServer(McpServerEntity server, String operator);

    /** 保存 MCP 草稿（整体替换，version +1，status=DRAFT）。@return false = 不存在 */
    boolean saveMcpServer(McpServerEntity server, String operator);

    /** 删除 MCP 服务端；引用保护同 {@link #deletePrompt}。 */
    void deleteMcpServer(String serverName);

    // ==================================================================================
    // 五、Skill（内容在官方表，治理在 md_skill_meta）
    // ==================================================================================

    /** 全部 skill 的治理元数据，最近修改的在前；<b>不含正文</b>（正文要回查官方表，列表页不需要）。 */
    List<SkillMetaEntity> listSkillMetas();

    /** 单个 skill 的治理元数据；不存在返回 null。 */
    SkillMetaEntity findSkillMeta(String skillName);

    /**
     * 官方表里的 skill 内容（描述 + 正文 + 附属文件）。
     *
     * <p>官方实现的 {@code getSkill} 对不存在的名字<b>会抛异常</b>而不是返回 null，
     * 实现里先用 {@code skillExists} 判一次存在，把"没有"统一表达成 null（接口层不该让调用方
     * 靠捕获 IllegalArgumentException 来判断资源是否存在）。代价是多一次存在性查询 —— 值得。
     *
     * @return 官方表里没有这个名字时返回 null；返回 null 也可能是"meta 有、内容没了"的漂移
     *         （设计文档 §10.7），调用方需结合 meta 自行判断
     */
    AgentSkill findSkillContent(String skillName);

    /**
     * 官方表里的全部 skill 名 —— 与 {@link #listSkillMetas()} 做差集，用于发现漂移（设计文档 §10.7）。
     */
    List<String> listSkillContentNames();

    /**
     * 新建 skill：先写官方内容表，再写治理元数据。
     *
     * <p>{@code skillName} / {@code description} 的权威位置是官方表（框架要读它拼技能提示词），
     * meta 里的值是与它同步的缓存；这里强制两处同源，不给"meta 与内容不一致"留入口。
     *
     * @param meta         治理元数据（display_name / biz_tags / enabled 等），
     *                     skill_name 与 description 会被本次调用的参数覆盖
     * @param skillContent SKILL.md 正文，官方表 NOT NULL，不可为空
     * @param resources    附属文件（路径 → 内容），可为 null
     * @return false = 该 skill 已存在
     */
    boolean createSkill(SkillMetaEntity meta, String skillContent, Map<String, String> resources, String operator);

    /** 保存 skill 草稿（整体替换官方内容 + meta，version +1，status=DRAFT）。@return false = 不存在 */
    boolean saveSkill(SkillMetaEntity meta, String skillContent, Map<String, String> resources, String operator);

    /** 删除 skill（官方内容 + 治理元数据）；引用保护同 {@link #deletePrompt}。 */
    void deleteSkill(String skillName);

    // ==================================================================================
    // 六、渠道（md_channel + md_channel_binding）
    // ==================================================================================

    /**
     * 全部渠道的主表行，最近修改的在前。
     *
     * <p>与 {@link #listAgents()} 同理：列表页显示的类型 / 状态 / 版本 / 启用都在主表上，
     * 不为了列表去 join 绑定表。绑定条数在页面里按需单查（见 {@code ChannelController}）。
     */
    List<ChannelEntity> listChannels();

    /** 一个渠道的主表行；不存在返回 null。 */
    ChannelEntity findChannel(String channelId);

    /**
     * 一个渠道的完整内容（主表行 + 绑定行，按 sort_order 升序），供装配期与详情页使用。
     *
     * @return 不存在时返回 null
     */
    ChannelDefinitionBundle loadChannelBundle(String channelId);

    /**
     * 全部渠道的完整内容 —— {@code ChannelRuntime} 的装配源。
     *
     * <p>实现上是「2 条全表查询 + 内存分组」，而不是「逐个渠道查 2 次」：
     * 后者在 10 个渠道时是 20 次往返，而渠道数量在可预见的将来都是个位数。
     */
    List<ChannelDefinitionBundle> loadAllChannelBundles();

    /** 新建渠道（channel_id 已存在时返回 false，控制器回 409）。 */
    boolean createChannel(ChannelDefinitionBundle bundle, String operator);

    /**
     * 保存渠道草稿：主表整体替换 + 绑定整体替换，version +1，status=DRAFT。
     *
     * <p>绑定用「先按 channel_id 全删、再插当前列表」而不是逐行 diff ——
     * 与 agent 保存子 agent 的策略一致：绑定数量是个位数，diff 的复杂度换不来收益，
     * 而且 diff 漏掉一个字段就会静默丢数据。
     *
     * @return false = 渠道不存在（控制器回 404）
     */
    boolean saveChannel(ChannelDefinitionBundle bundle, String operator);

    /**
     * 删除渠道及其全部绑定。
     *
     * <p>绑定<b>随渠道一起删</b>，不在这里做引用保护：绑定是渠道的组成部分
     * （没有渠道不存在"孤立的绑定"这种有意义的形态），拦着不让删只会逼用户先手工清空绑定。
     * 反方向的保护是另一回事 —— 删 agent 时要看它有没有被渠道绑着，见
     * {@link #findReferencingChannels(String)}。
     */
    void deleteChannel(String channelId);

    /**
     * 反查：这个 agent 被哪些渠道绑着（删除 agent 前的保护检查）。
     *
     * @return 渠道 id 列表，去重；没被绑定时为空列表（不是 null）
     */
    List<String> findReferencingChannels(String agentName);

    // ==================================================================================
    // 七、模型（md_model）
    // ==================================================================================

    /**
     * 全部模型，按展示名升序。
     *
     * <p>为什么不像其它资源那样按 updated-at 倒序：模型在页面上是"一份可挑选的清单"
     *（建 agent 时要在这里选一个），按名字排列才找得到；而 prompt / mcp 那几张表是
     *"最近改过什么"的工作视图，按时间倒序才有用。
     */
    List<ModelEntity> listModels();

    /** 一个模型的行；不存在返回 null。 */
    ModelEntity findModel(String modelKey);

    /**
     * 新建模型草稿（model_key 已存在时返回 false，控制器回 409）。
     *
     * <p>若 {@code isDefault=true}，本方法会在<b>同一个事务内</b>把旧的默认模型清掉 ——
     * 否则会出现两个默认模型，而装配期取的是"任意一个"，行为取决于查询计划。
     */
    boolean createModel(ModelEntity model, String operator);

    /**
     * 保存模型草稿：整体替换 + version +1 + 状态回 DRAFT；{@code isDefault} 同样保证全表唯一。
     *
     * @return false = 模型不存在（控制器回 404）
     */
    boolean saveModel(ModelEntity model, String operator);

    /**
     * 删除模型及其版本快照。
     *
     * <p>不做引用保护 —— 引用检查（{@code findReferencingAgents}）由调用方在删除<b>之前</b>做，
     * 这样 404 / 409 的区分留在接口层（与 {@code ResourceController.deleteChecked} 同一个顺序约定）。
     */
    void deleteModel(String modelKey);

    // ==================================================================================
    // 八、发布 / 回滚 / 版本 / 引用（受治理资源共用）
    // ==================================================================================

    /**
     * 发布：读当前内容 → 落一份快照 → 主表置 PUBLISHED 并记住 published_version。
     *
     * <p>顺序上"先落快照再改状态"：反过来的话，若快照写入失败，主表已经显示已发布，
     * 而这个版本没有快照，将来回滚不到它。
     *
     * <p><b>本方法不触发热装配</b>：热装配需要 {@code AgentManager}，
     * 而存储层不该依赖运行时注册表（否则存储层就没法单独测）。由控制层在发布成功后调用重建。
     */
    void publish(ResourceType type, String name, String operator, String changeNote);

    /**
     * 回滚到指定历史版本：读快照 → 整体写回内容 → version = 当前值 + 1 → status 置 DRAFT。
     *
     * <p>回滚<b>新增</b>版本号而不是覆盖历史，保证版本序列单向递增、可审计；
     * 且回滚后仍需显式发布一次才生效 —— 回滚是"准备一个旧内容的新版本"，不是直接上线。
     *
     * <p>不回滚 {@code published_version / published_at_ms}：它们描述"线上现在跑的是哪一版"，
     * 回滚只动草稿，线上仍是原来那一版。
     *
     * @throws ControlPlaneValidationException 资源或该版本号不存在
     */
    void rollback(ResourceType type, String name, int version, String operator);

    /** 版本历史，新版本在前；不含快照正文（理由见 {@link ResourceVersionInfo}）。 */
    List<ResourceVersionInfo> listVersions(ResourceType type, String name);

    /**
     * 反向查引用：哪些 agent 引用了这个资源（删除前的保护检查）。
     *
     * <p>四类资源的"引用"各有落点：
     * <ul>
     *   <li>{@code PROMPT} → {@code md_agent_ref.prompt_key}</li>
     *   <li>{@code SKILL} / {@code MCP} → {@code md_agent_ref.skill_names} / {@code mcp_names}（JSON 数组）</li>
     *   <li>{@code AGENT} → {@code md_agent_sub_agent.sub_name}（子 agent 引用）</li>
     *   <li>{@code CHANNEL} → 没有：引用是单向的（渠道绑 agent，agent 不认识渠道），
     *       所以这里恒返回空列表；反方向的保护见 {@link #findReferencingChannels}</li>
     * </ul>
     * 列表只覆盖 agent 一类的引用方；渠道不引用 prompt，定时任务等未来可能引入新的引用方，
     * 扩展位留在实现里（设计文档 §10.6）。
     *
     * @return 引用方的 agent 名列表，无人引用时为空列表（不是 null）
     */
    List<String> findReferencingAgents(ResourceType type, String name);

    // ==================================================================================
    // 九、工作流定义（md_workflow）—— 第六类受治理资源
    // ==================================================================================
    //
    // 为什么它跟着这套接口走，而不是另起一个 WorkflowStore：
    // 它需要的四件事（草稿 / 发布 / 版本快照 / 回滚）与另外五类逐字相同，
    // 而 {@link #publish} / {@link #rollback} / {@link #listVersions} 本来就是按
    // {@link ResourceType} 分派的。把工作流接进来只需要在 switch 里加一个分支，
    // 另起一套则意味着版本列表、快照序列化、回滚语义三处都要各写一遍 ——
    // 而它们必须与另外五类永远一致，重复实现的唯一效果是多一个会漏改的地方。
    //
    // 与其它五类不同的只有两点，都在下面各自的方法注释里说明：
    //   1）保存时不做完整校验（半成品的图也要能存），校验推迟到发布；
    //   2）发布时要额外把节点引用投影成行，供删除保护反查。

    /** 全部工作流主表行，最近修改的在前。 */
    List<WorkflowEntity> listWorkflows();

    /** 一个工作流主表行；不存在返回 null。 */
    WorkflowEntity findWorkflow(String workflowKey);

    /**
     * 取一个工作流<b>已发布</b>的那一版内容 —— 执行引擎唯一的定义来源。
     *
     * <p>为什么不直接读主表：主表里的 {@code spec} 是<b>草稿</b>。用户在设计器里改到一半、
     * 甚至改出一棵有环的图，点「保存」就落到主表了（保存不校验，见 {@link #createWorkflow}）。
     * 而运行中的每一次执行都必须锁在一个<b>经过校验、且不会再变</b>的版本上 ——
     * 所以引擎走快照，不走主表。
     *
     * <p>实现上就是 {@link #findVersionRow} + {@link #readSnapshot} 两跳，
     * 但刻意包成一个方法而不是让引擎自己拼：引擎不该知道"快照存在哪张表、DTO 是哪个类"，
     * 那是控制面内部的事。
     *
     * @return 已发布版本的实体；从未发布过、或快照丢了，返回 null（调用方负责报"没有可执行的版本"）
     */
    WorkflowEntity loadPublishedWorkflow(String workflowKey);

    /**
     * 新建工作流草稿（workflow_key 已存在时返回 false，控制器回 409）。
     *
     * <p><b>只做"能存进去"这一层检查</b>，不查环、不查引用是否存在 ——
     * 页面上的图是一步步搭出来的，保存就拦截等于逼人先把图画完。发布时才强制校验。
     *
     * @throws ControlPlaneValidationException 缺少 workflow_key 或展示名
     */
    boolean createWorkflow(WorkflowEntity workflow, String operator);

    /**
     * 保存草稿：整体替换 + version+1 + 状态回 DRAFT。
     *
     * @return false = 工作流不存在（控制器回 404）
     */
    boolean saveWorkflow(WorkflowEntity workflow, String operator);

    /** 删除工作流：定义 + 引用投影 + 版本快照（引用投影必须一起删，理由见实现类）。 */
    void deleteWorkflow(String workflowKey);

    /**
     * 反查：这个 agent 被哪些<b>已发布</b>的工作流引用（删除 agent 前的保护检查）。
     *
     * <p>与 {@link #findReferencingChannels(String)} 是同一类方法、同一类用途，
     * 方向也一样（都是"我要删的这条资源被别人用着"）。之所以不并进
     * {@link #findReferencingAgents}：那个方法回答的是"哪些 agent 引用了我"，
     * 返回值的消费者渲染的是"被这些 agent 引用"，把工作流名塞进去会误导用户。
     *
     * @return 工作流 key 列表，去重并按 key 排序；没被引用时为空列表（不是 null）
     */
    List<String> findReferencingWorkflows(String agentName);

    /**
     * 只校验、不落库 —— 页面上的「校验」按钮用它，让人在发布前先看到全部问题。
     *
     * @return 全部问题，顺序即检查顺序；空列表表示这份定义可以发布
     */
    List<String> checkWorkflowSpec(WorkflowSpec spec);
}
