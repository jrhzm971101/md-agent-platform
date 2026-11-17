package com.md.agentplatform.server.controlplane.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.server.controlplane.store.entity.AgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentMemoryEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRefEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentRuntimeEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentSubAgentEntity;
import com.md.agentplatform.server.controlplane.store.entity.AgentWorkspaceEntity;
import com.md.agentplatform.server.controlplane.store.entity.ChannelBindingEntity;
import com.md.agentplatform.server.controlplane.store.entity.ChannelEntity;
import com.md.agentplatform.server.controlplane.store.entity.GovernedRow;
import com.md.agentplatform.server.controlplane.store.entity.McpServerEntity;
import com.md.agentplatform.server.controlplane.store.entity.ModelEntity;
import com.md.agentplatform.server.controlplane.store.entity.PromptEntity;
import com.md.agentplatform.server.controlplane.store.entity.ResourceVersionEntity;
import com.md.agentplatform.server.controlplane.store.entity.SkillMetaEntity;
import com.md.agentplatform.server.controlplane.store.entity.WorkflowEntity;
import com.md.agentplatform.server.controlplane.store.entity.WorkflowNodeRefEntity;
import com.md.agentplatform.server.controlplane.store.mapper.AgentMapper;
import com.md.agentplatform.server.controlplane.store.mapper.AgentMemoryMapper;
import com.md.agentplatform.server.controlplane.store.mapper.AgentRefMapper;
import com.md.agentplatform.server.controlplane.store.mapper.AgentRuntimeMapper;
import com.md.agentplatform.server.controlplane.store.mapper.AgentSubAgentMapper;
import com.md.agentplatform.server.controlplane.store.mapper.AgentWorkspaceMapper;
import com.md.agentplatform.server.controlplane.store.mapper.ChannelBindingMapper;
import com.md.agentplatform.server.controlplane.store.mapper.ChannelMapper;
import com.md.agentplatform.server.controlplane.store.mapper.McpServerMapper;
import com.md.agentplatform.server.controlplane.store.mapper.ModelMapper;
import com.md.agentplatform.server.controlplane.store.mapper.PromptMapper;
import com.md.agentplatform.server.controlplane.store.mapper.ResourceVersionMapper;
import com.md.agentplatform.server.controlplane.store.mapper.SkillMetaMapper;
import com.md.agentplatform.server.controlplane.store.mapper.WorkflowMapper;
import com.md.agentplatform.server.controlplane.store.mapper.WorkflowNodeRefMapper;
import com.md.agentplatform.server.workflow.WorkflowNodeType;
import com.md.agentplatform.server.workflow.WorkflowSpec;
import com.md.agentplatform.server.workflow.WorkflowValidator;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link ControlPlaneStore} 的 MySQL 实现（MyBatis-Plus）。
 *
 * <h2>一、这个类只做三件事</h2>
 * 行模型的读写、聚合的整体替换、以及"引用完整性"这一条事务内校验。
 * <b>不做</b>领域对象转换（那是 {@code AgentDefinitionResolver} 的事）、
 * <b>不做</b> HTTP 状态码翻译（那是控制器的事）、
 * <b>不做</b>热装配（那是 {@code AgentManager} 的事）。
 * 边界这么划的原因：存储层要能脱离 Spring 容器、脱离 Nacos、脱离模型 API 单独跑起来测试
 * （见 {@code MyBatisControlPlaneStoreIT}），一旦掺进任何一个是它的下游，这个测试就搭不起来了。
 *
 * <h2>二、"整体替换"为什么是先删后插，而不是 updateById</h2>
 * MyBatis-Plus 的 {@code updateById} 默认策略是 <b>只更新非 null 字段</b>
 * （{@code FieldStrategy.NOT_NULL}）。于是页面上「把描述删空 → 保存」会静默失效：
 * 页面上描述没了，库里还留着旧值，下次打开又冒出来 —— 而且没有任何报错。
 * 三种解法里：
 * <ul>
 *   <li>给每个可空列标 {@code updateStrategy = FieldStrategy.ALWAYS} ——
 *       要覆盖 6 张表几十个列，且"哪个列允许清空"变成散落在实体注解里的隐式约定；</li>
 *   <li>拼 {@code LambdaUpdateWrapper} 逐列 {@code .set()} —— 每张表十几行，列名有两个维护点；</li>
 *   <li><b>事务内先删后插</b> —— 一行代码，语义是"提交什么就存什么"。</li>
 * </ul>
 * 选第三种。它唯一的代价是自增主键会涨（子 agent 表），以及删/插之间有一瞬间行不存在 ——
 * 但后者在事务里对外不可见（其他连接在提交前读到的仍是旧行），因此没有风险。
 * <b>注意</b>：正因为先删后插，传入的实体必须是"完整内容"，
 * 调用方不能只想改一列就丢一个残缺对象进来（控制面是整表单提交，天然满足）。
 *
 * <p>例外：{@code publish} 走的仍是 {@code updateById}。因为它"先读行、改两个状态字段、再写回"，
 * 手里拿的就是完整旧行，没有清空字段的意图 —— 只改状态不该重写全表。
 *
 * <h2>三、事务怎么进来的</h2>
 * 本类由 {@code ControlPlaneStoreConfig} 手工 {@code new} 成 Bean，
 * {@code @Transactional} 依然生效（Spring 对容器内的 Bean 生成代理，与是否手工 new 无关）。
 * 但有一条必须遵守：<b>事务方法不能被同类内部调用</b> —— 那样走的是 this，绕过代理，注解形同虚设。
 * 所以这里的私有辅助方法（{@code replaceAgentRows} 等）刻意不标 {@code @Transactional}，
 * 它们由已经开启事务的公开方法调用，共享同一个事务。
 *
 * <h2>四、与官方 skill 仓库共存</h2>
 * skill 内容在另一个 schema，由官方 {@link AgentSkillRepository} 自己开连接、自己管事务
 * （见设计文档 §10.2 的已知缺口）。本类对它只做两件事：转发读写、维持"先内容后 meta"的顺序。
 * 两者<b>不共享事务</b>是既定事实，不是遗漏。
 */
public class MyBatisControlPlaneStore implements ControlPlaneStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisControlPlaneStore.class);

    /** 官方 skill 内容表的 source 标记：由后端补固定值，页面只填名称与描述（决策 D14）。 */
    private static final String SKILL_SOURCE = "control-plane";

    /** 合法的 agent 类型。这里写字面量而不是引用 {@code AgentDefinition.Type}，是为了不让存储层依赖装配层的类。 */
    private static final Set<String> AGENT_TYPES = Set.of("REACT", "HARNESS");

    /** 合法的 MCP 传输方式，对齐官方 {@code McpServerConfig}。stdio 可存但运行期拒绝，详见 DDL 注释。 */
    private static final Set<String> MCP_TRANSPORTS = Set.of("streamable", "sse", "stdio");

    /**
     * 合法的单聊会话范围，取值与官方 {@code DmScope} 枚举逐字相同。
     *
     * <p>这里写字面量而不是引用官方枚举，与 {@link #AGENT_TYPES} 同一个理由：
     * 存储层不该依赖装配层的类。代价是官方将来新增一个 scope 时这里要跟着加 ——
     * 但那种情况下更危险的是"库里存了一个官方不认识的 scope"，
     * 路由期会直接抛异常，宁可在这里先拦住。
     */
    private static final Set<String> DM_SCOPES =
            Set.of("MAIN", "PER_PEER", "PER_CHANNEL_PEER", "PER_ACCOUNT_CHANNEL_PEER");

    private final AgentMapper agentMapper;
    private final AgentRefMapper refMapper;
    private final AgentRuntimeMapper runtimeMapper;
    private final AgentMemoryMapper memoryMapper;
    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentSubAgentMapper subAgentMapper;
    private final PromptMapper promptMapper;
    private final McpServerMapper mcpMapper;
    /** 模型表（md_model）：CRUD + 引用反查（见 {@link ModelMapper}）。 */
    private final ModelMapper modelMapper;
    private final SkillMetaMapper skillMetaMapper;
    private final ResourceVersionMapper versionMapper;
    private final AgentSkillRepository skillRepository;
    private final ChannelMapper channelMapper;
    private final ChannelBindingMapper channelBindingMapper;

    /** 工作流定义主表（第六类受治理资源）。 */
    private final WorkflowMapper workflowMapper;

    /**
     * 工作流节点引用投影 —— 只在发布时写。
     *
     * <p>反查「删掉 agent X 会毁掉哪些已发布的流程」靠它，理由见 WorkflowNodeRefMapper 的类注释。
     */
    private final WorkflowNodeRefMapper workflowNodeRefMapper;

    /**
     * 工作流定义校验器。
     *
     * <p>它被注入到这里、而不是只被控制器调用：发布是「把一棵图放上线」的唯一入口，
     * 校验必须<b>贴着发布</b>做。放在控制器上的话，别的调用方（测试、将来的导入器）
     * 绕过接口就能把未校验的图发布出去，而页面上看不出任何区别。
     */
    private final WorkflowValidator workflowValidator;

    /**
     * 专用于快照 JSON 的序列化器，<b>刻意不复用容器里的 ObjectMapper</b>。
     *
     * <p>快照是落进 LONGTEXT 列、要能跨版本读回来的持久化格式。
     * 容器的 ObjectMapper 会被别人改（SNAKE_CASE、NON_NULL、自定义模块……），
     * 那种改动对接口响应是无害的偏好，对已落库的快照却是"语料格式变了"，
     * 后果是老快照回滚时反序列化出 null 字段 —— 一个改 Jackson 配置的人根本想不到的连带影响。
     * 自己的实例把这份格式钉死在本类里。
     */
    private final ObjectMapper snapshotMapper = new ObjectMapper();

    public MyBatisControlPlaneStore(AgentMapper agentMapper, AgentRefMapper refMapper,
                                    AgentRuntimeMapper runtimeMapper, AgentMemoryMapper memoryMapper,
                                    AgentWorkspaceMapper workspaceMapper, AgentSubAgentMapper subAgentMapper,
                                    PromptMapper promptMapper, McpServerMapper mcpMapper,
                                    ModelMapper modelMapper,
                                    SkillMetaMapper skillMetaMapper, ResourceVersionMapper versionMapper,
                                    AgentSkillRepository skillRepository,
                                    ChannelMapper channelMapper, ChannelBindingMapper channelBindingMapper,
                                    WorkflowMapper workflowMapper,
                                    WorkflowNodeRefMapper workflowNodeRefMapper,
                                    WorkflowValidator workflowValidator) {
        this.agentMapper = agentMapper;
        this.refMapper = refMapper;
        this.runtimeMapper = runtimeMapper;
        this.memoryMapper = memoryMapper;
        this.workspaceMapper = workspaceMapper;
        this.subAgentMapper = subAgentMapper;
        this.promptMapper = promptMapper;
        this.mcpMapper = mcpMapper;
        this.modelMapper = modelMapper;
        this.skillMetaMapper = skillMetaMapper;
        this.versionMapper = versionMapper;
        this.skillRepository = skillRepository;
        this.channelMapper = channelMapper;
        this.channelBindingMapper = channelBindingMapper;
        this.workflowMapper = workflowMapper;
        this.workflowNodeRefMapper = workflowNodeRefMapper;
        this.workflowValidator = workflowValidator;
    }

    // ==================================================================================
    // 一、agent 定义（装配期读）
    // ==================================================================================

    @Override
    public List<AgentEntity> listAgents() {
        return agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .orderByDesc(AgentEntity::getUpdatedAtMs)
                // 补一个名字升序：同一毫秒更新的两个 agent 否则顺序不稳，
                // 列表刷新时会看到两条记录来回换位。
                .orderByAsc(AgentEntity::getAgentName));
    }

    @Override
    public AgentEntity findAgent(String agentName) {
        return agentName == null || agentName.isBlank() ? null : agentMapper.selectById(agentName);
    }

    @Override
    public AgentDefinitionBundle loadAgentBundle(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return null;
        }
        AgentEntity agent = agentMapper.selectById(agentName);
        if (agent == null) {
            return null;
        }
        AgentDefinitionBundle bundle = new AgentDefinitionBundle();
        bundle.setAgent(agent);
        bundle.setRef(refMapper.selectById(agentName));
        bundle.setRuntime(runtimeMapper.selectById(agentName));
        bundle.setMemory(memoryMapper.selectById(agentName));
        bundle.setWorkspace(workspaceMapper.selectById(agentName));
        bundle.setSubAgents(loadSubAgents(agentName));
        return bundle;
    }

    @Override
    public List<AgentDefinitionBundle> loadAllAgentBundles() {
        // 6 条全表查询 + 内存分组。逐个 agent 查 6 次在 14 个 agent 时就是 84 条 SQL，
        // 而且每加一个 agent 线性变差 —— 控制面表的数据量是"人手维护的元数据"，
        // 全表读回来的成本可以忽略，这个取舍写在这里以免后人"优化"回去。
        List<AgentEntity> agents = agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .orderByAsc(AgentEntity::getAgentName));
        Map<String, AgentRefEntity> refs = index(refMapper.selectList(null), AgentRefEntity::getAgentName);
        Map<String, AgentRuntimeEntity> runtimes =
                index(runtimeMapper.selectList(null), AgentRuntimeEntity::getAgentName);
        Map<String, AgentMemoryEntity> memories =
                index(memoryMapper.selectList(null), AgentMemoryEntity::getAgentName);
        Map<String, AgentWorkspaceEntity> workspaces =
                index(workspaceMapper.selectList(null), AgentWorkspaceEntity::getAgentName);
        // 子 agent 是有序的：先按 agent 分组、组内按 sort_order 排，
        // 分组用 LinkedHashMap + 有序游标，保证组内顺序与 SQL 的排序一致。
        Map<String, List<AgentSubAgentEntity>> subAgents = groupSubAgents(subAgentMapper.selectList(
                Wrappers.<AgentSubAgentEntity>lambdaQuery()
                        .orderByAsc(AgentSubAgentEntity::getAgentName)
                        .orderByAsc(AgentSubAgentEntity::getSortOrder)));

        List<AgentDefinitionBundle> bundles = new ArrayList<>(agents.size());
        for (AgentEntity agent : agents) {
            String name = agent.getAgentName();
            AgentDefinitionBundle bundle = new AgentDefinitionBundle();
            bundle.setAgent(agent);
            bundle.setRef(refs.get(name));
            bundle.setRuntime(runtimes.get(name));
            bundle.setMemory(memories.get(name));
            bundle.setWorkspace(workspaces.get(name));
            bundle.setSubAgents(subAgents.getOrDefault(name, List.of()));
            bundles.add(bundle);
        }
        log.debug("控制面加载 agent 定义束: {} 个", bundles.size());
        return bundles;
    }

    // ==================================================================================
    // 私有：读取辅助
    // ==================================================================================

    /** 某个 agent 的子 agent 声明，按 sort_order 升序 —— 顺序即页面表格顺序，也影响装配时的呈现顺序。 */
    private List<AgentSubAgentEntity> loadSubAgents(String agentName) {
        return subAgentMapper.selectList(Wrappers.<AgentSubAgentEntity>lambdaQuery()
                .eq(AgentSubAgentEntity::getAgentName, agentName)
                .orderByAsc(AgentSubAgentEntity::getSortOrder));
    }

    /**
     * 把一批行按某个键建索引。
     *
     * <p>用 {@code Function} 传取键逻辑，而不是给这几张表抽一个公共接口：
     * 它们唯一的共同点就是"外键是 agent_name"，为这个共同点造一个接口，
     * 会让人以为它们还有别的共性。四张表各传一个方法引用，意图反而更直白。
     *
     * <p>键重复时后写覆盖先写：这些是"每个 agent 一行"的表，重复意味着主键约束被绕过，
     * 属于不可能发生的情况；真发生了也只是装配到错的那行，不会静默丢数据。
     */
    private static <T> Map<String, T> index(List<T> rows, Function<T, String> keyFn) {
        Map<String, T> indexed = new HashMap<>(Math.max(16, rows.size() * 2));
        for (T row : rows) {
            indexed.put(keyFn.apply(row), row);
        }
        return indexed;
    }

    /** 子 agent 行按父 agent 名分组；组内保持 SQL 给出的 sort_order 顺序（见调用方注释）。 */
    private static Map<String, List<AgentSubAgentEntity>> groupSubAgents(List<AgentSubAgentEntity> rows) {
        Map<String, List<AgentSubAgentEntity>> grouped = new LinkedHashMap<>();
        for (AgentSubAgentEntity row : rows) {
            grouped.computeIfAbsent(row.getAgentName(), key -> new ArrayList<>(4)).add(row);
        }
        return grouped;
    }


    // ==================================================================================
    // 二、agent 写
    // ==================================================================================

    @Override
    @Transactional
    public boolean createAgent(AgentDefinitionBundle bundle, String operator) {
        validateAgentContent(bundle);
        AgentEntity incoming = bundle.getAgent();
        if (agentMapper.selectById(incoming.getAgentName()) != null) {
            // 重名属于重复提交 / 并发下的正常竞争，用返回值表达，不抛异常（约定见接口注释第三节）。
            return false;
        }
        long now = System.currentTimeMillis();
        // 生命周期字段由存储层覆盖：请求体里带的 version / status 一律不作数，理由见 GovernedRow#initGovernance。
        incoming.initGovernance(operator, now);
        if (incoming.getEnabled() == null) {
            // enabled 是 NOT NULL 列，而"新建出来的 agent 一般是拿来用的"，缺省给 true。
            incoming.setEnabled(Boolean.TRUE);
        }
        replaceAgentRows(bundle);
        log.info("控制面新建 agent: {} type={} operator={}", incoming.getAgentName(),
                incoming.getAgentType(), operator);
        return true;
    }

    @Override
    @Transactional
    public boolean saveAgent(AgentDefinitionBundle bundle, String operator) {
        AgentEntity incoming = bundle == null ? null : bundle.getAgent();
        if (incoming == null || isBlank(incoming.getAgentName())) {
            throw new ControlPlaneValidationException("agent-name 不能为空");
        }
        AgentEntity existing = agentMapper.selectById(incoming.getAgentName());
        if (existing == null) {
            return false;
        }
        // ── 类型不可变更（2026-09-22）──
        // 为什么必须在这里挡：advanceDraft 只继承治理字段（created_* / published_*），不继承
        // agentType —— 请求体里带什么就落什么。而"换类型"实际是"整批换掉这个 agent 的行结构"：
        // HARNESS → REACT 会连同 md_agent_sub_agent 的全部行一起丢掉（replaceAgentRows 先删后插），
        // 而别的 agent 可能正引它当子 agent；REACT → HARNESS 则会留下一份全空的 workspace 行。
        // 损失发生在保存那一刻，却要到很久之后（某条编排链走到某一步"没反应"）才暴露，所以直接拒。
        String existingType = normalizeType(existing.getAgentType());
        String incomingType = normalizeType(incoming.getAgentType());
        if (incomingType != null && existingType != null && !existingType.equals(incomingType)) {
            throw new ControlPlaneValidationException("agent 类型不可变更（" + existingType + " → "
                    + incomingType + "）。需要 " + incomingType + " 请新建一个，再把 "
                    + incoming.getAgentName() + " 挂成它的子 agent。");
        }
        // 类型一律以库里的为准：漏传、写成小写都归一到旧值。放在校验之前，
        // 是为了让"校验放行了"与"字段真的没被改写"是同一件事，不留缝隙。
        incoming.setAgentType(existingType);
        validateAgentContent(bundle);
        long now = System.currentTimeMillis();
        incoming.advanceDraft(existing, operator, now);
        if (incoming.getEnabled() == null) {
            incoming.setEnabled(existing.getEnabled());
        }
        replaceAgentRows(bundle);
        log.info("控制面保存 agent 草稿: {} → v{} status={} operator={}", incoming.getAgentName(),
                incoming.getVersion(), incoming.getStatus(), operator);
        return true;
    }

    @Override
    @Transactional
    public void deleteAgent(String agentName) {
        if (isBlank(agentName)) {
            return;
        }
        // 没有外键，删除顺序不影响正确性；先删子表只是为了让"父行已删、子行还在"的中间态窗口最短。
        subAgentMapper.deleteByAgentName(agentName);
        workspaceMapper.deleteById(agentName);
        memoryMapper.deleteById(agentName);
        runtimeMapper.deleteById(agentName);
        refMapper.deleteById(agentName);
        agentMapper.deleteById(agentName);
        // 版本快照一并删除，理由见 deleteVersionsOf。
        int snapshots = deleteVersionsOf(ResourceType.AGENT, agentName);
        log.info("控制面删除 agent: {}（6 张表 + {} 份版本快照）", agentName, snapshots);
    }

    // ==================================================================================
    // 私有：agent 写入辅助
    // ==================================================================================

    /**
     * 用 bundle 的内容整体替换该 agent 的全部行（调用方保证已在事务内）。
     *
     * <p>create / save / rollback 三条路径共用这一段，是刻意的：
     * 它们的差别只在"内容从哪来"（新建入参 / 编辑入参 / 旧快照），
     * 而"这些内容怎么落成 6 张表的行"必须完全一致，否则三条路径会各自长出细微差异。
     */
    private void replaceAgentRows(AgentDefinitionBundle bundle) {
        String agentName = bundle.agentName();
        // 主表也走"先删后插"：既让保存草稿能真正清空字段（见类注释第二节），
        // 也让三条路径不需要各自区分 insert 与 update。
        replaceRow(agentName, bundle.getAgent(), agentMapper);
        replaceRow(agentName, bundle.getRef(), refMapper);
        replaceRow(agentName, bundle.getRuntime(), runtimeMapper);
        replaceRow(agentName, bundle.getMemory(), memoryMapper);
        replaceRow(agentName, bundle.getWorkspace(), workspaceMapper);
        replaceSubAgents(agentName, bundle.getSubAgents());
    }

    /**
     * 四张"每个 agent 一行"的关联表共用的替换逻辑。
     *
     * <p>入参为 null 时<b>删掉这一行</b>，而不是写一行全 null：NULL 与"没有这行"在页面上是两回事
     * （一个 agent 没有 harness 配置，正确表达就是 md_agent_workspace 里没有它），
     * 写一行全 null 会让"配置被清空"和"配置本来就没有"混在一起，
     * 将来想按"是否存在 workspace 行"判断类型时就会踩坑。
     */
    private <T> void replaceRow(String agentName, T row, BaseMapper<T> mapper) {
        mapper.deleteById(agentName);
        if (row != null) {
            mapper.insert(row);
        }
    }

    /**
     * 替换某个 agent 的子 agent 声明。
     *
     * <p>按 {@code sort_order} 落库，缺省按数组下标补齐 —— 页面表格的行序就是装配时子 agent 的呈现顺序，
     * 不能交给数据库的物理顺序决定。
     */
    private void replaceSubAgents(String agentName, List<AgentSubAgentEntity> subAgents) {
        subAgentMapper.deleteByAgentName(agentName);
        if (subAgents == null || subAgents.isEmpty()) {
            return;
        }
        int order = 0;
        for (AgentSubAgentEntity sub : subAgents) {
            // id 必须清空：回滚时这些行来自快照，带着上一世的自增 id，
            // 直接插入会撞主键唯一约束（uk_agent_sub 之外还有主键）。
            sub.setId(null);
            sub.setAgentName(agentName);
            if (sub.getSortOrder() == null) {
                sub.setSortOrder(order);
            }
            subAgentMapper.insert(sub);
            order++;
        }
    }

    /**
     * 删除某个资源的全部版本快照，返回删除行数。
     *
     * <p>为什么"删资源"要连快照一起删：快照的用途是<b>回滚</b>，而资源没了就无从回滚，
     * 留着只会产生一个诡异现象 —— 用同一个名字新建资源后，版本历史里冒出上一世的 v1~v5，
     * 点回滚还能把旧内容"复活"。
     *
     * <p>代价是审计历史随之消失。若将来需要版本审计，正确做法是把它做成不可删的审计表
     * （或者把删除改成软删），而不是在回滚语义里夹带审计语义。
     */
    private int deleteVersionsOf(ResourceType type, String name) {
        return versionMapper.delete(Wrappers.<ResourceVersionEntity>lambdaQuery()
                .eq(ResourceVersionEntity::getResourceType, type.name())
                .eq(ResourceVersionEntity::getResourceName, name));
    }

    // ==================================================================================
    // 私有：校验
    // ==================================================================================

    /**
     * agent 内容校验 —— 一次收集全部问题再抛（理由见 {@link ControlPlaneValidationException}）。
     *
     * <p>校验放在存储层且和写入同一个事务，而不是放在控制器：
     * 控制器先校验、存储层后写入，中间有一段窗口，被引用的 prompt 可能刚好被删掉，
     * 于是就落下一个指向不存在 prompt 的 agent。放进事务里，"校验通过"与"写入完成"之间没有缝。
     */
    private void validateAgentContent(AgentDefinitionBundle bundle) {
        AgentEntity agent = bundle == null ? null : bundle.getAgent();
        if (agent == null) {
            throw new ControlPlaneValidationException("agent 定义缺失（bundle.agent 为 null）");
        }
        List<String> problems = new ArrayList<>();
        String agentName = agent.getAgentName();
        if (isBlank(agentName)) {
            problems.add("agent-name 不能为空");
        }
        // 展示名是页面卡片标题的唯一来源，留空等于给出一张没有标题的卡片。
        // 这里不回落成 agent-name：回落看着更省事，代价是页面长期停在一堆机器名上，
        // 那这条列就白加了。宁可让创建时多填一个字段。
        if (isBlank(agent.getDisplayName())) {
            problems.add("显示名不能为空（页面卡片标题用它，不填会显示成空白）");
        } else {
            // 顺手 trim：展示名会被写进标题与快照，前后空白在页面上看不出来，
            // 却会让"看起来一样"的两个名字在快照里不相等。
            agent.setDisplayName(agent.getDisplayName().trim());
        }
        if (isBlank(agent.getAgentType())) {
            problems.add("agent-type 不能为空（REACT | HARNESS）");
        } else if (!AGENT_TYPES.contains(agent.getAgentType().trim().toUpperCase(Locale.ROOT))) {
            problems.add("agent-type 只能是 REACT 或 HARNESS，收到: " + agent.getAgentType());
        } else {
            // 归一大写再落库：库里只认 REACT / HARNESS 两种取值，页面传来小写不该变成第三、第四种。
            agent.setAgentType(agent.getAgentType().trim().toUpperCase(Locale.ROOT));
        }

        AgentRefEntity ref = bundle.getRef();
        if (ref != null) {
            String promptKey = ref.getPromptKey();
            if (!isBlank(promptKey) && promptMapper.selectById(promptKey) == null) {
                problems.add("prompt 不存在: " + promptKey);
            }
            for (String skill : nonNull(ref.getSkillNames())) {
                if (skillMetaMapper.selectById(skill) == null) {
                    problems.add("skill 不存在: " + skill);
                }
            }
            for (String mcp : nonNull(ref.getMcpNames())) {
                McpServerEntity server = mcpMapper.selectById(mcp);
                if (server == null) {
                    problems.add("MCP 不存在: " + mcp);
                } else if (!Boolean.TRUE.equals(server.getEnabled())) {
                    // 停用的 MCP 不该被新引用：装配期会跳过它并告警，
                    // 用户以为挂上了、实际没挂，这种"静默失效"必须在保存时就拦住。
                    problems.add("MCP 已停用: " + mcp);
                }
            }
        }

        Set<String> declared = new HashSet<>();
        for (AgentSubAgentEntity sub : nonNull(bundle.getSubAgents())) {
            String subName = sub.getSubName();
            if (isBlank(subName)) {
                problems.add("子 agent 缺少 sub-name");
                continue;
            }
            if (subName.equals(agentName)) {
                problems.add("子 agent 不允许自引用: " + agentName);
            }
            if (!declared.add(subName)) {
                problems.add("子 agent 重复声明: " + subName);
                continue;
            }
            if (Boolean.TRUE.equals(sub.getRemote())) {
                // 远程子 agent 不在本平台注册表里，只要求给得出端点。
                if (isBlank(sub.getUrl())) {
                    problems.add("远程子 agent 缺少 url: " + subName);
                }
                continue;
            }
            AgentEntity subAgent = agentMapper.selectById(subName);
            if (subAgent == null) {
                problems.add("子 agent 不存在: " + subName);
            } else if (!"REACT".equals(normalizeType(subAgent.getAgentType()))) {
                // 子 agent 的定位是"被派发的执行体"，只有能力单元（REACT）适合这个角色。
                // 放开会引来"编排体套编排体"，而现有校验只挡自引用（subName == agentName），
                // 挡不住 A→B→A 这种环 —— 环 + 嵌套编排是最难排查的一类"跑着跑着就没反应"。
                problems.add("子 agent 必须是 REACT 类型: " + subName
                        + "（当前是 " + subAgent.getAgentType() + "）");
            }
        }

        if (!problems.isEmpty()) {
            throw new ControlPlaneValidationException(problems);
        }
    }

    /** 类型比对用的归一：抹平大小写与首尾空白，避免 "react" 被当成"换了一种类型"。 */
    private static String normalizeType(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** null 安全的集合遍历：调用方不必在每个循环前写一遍 List.of() 兜底。 */
    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * 回滚时决定这一行的展示名：快照里的 &gt; 当前行里的 &gt; 标识列兜底。
     *
     * <p>为什么要专门兜这一下：快照记录的是<b>内容</b>，而展示名是纯展示口径。
     * display_name 是 2026-09-22 才加的列，此前写下的快照里根本没有这个字段 ——
     * 直接 {@code insert} 会把它写成 NULL（库列是 NOT NULL，直接报错），
     * 就算列允许为空，也等于"回滚内容"顺手把用户后来改好的名字抹掉了，
     * 而这两件事在页面上完全看不出关联。三选一兜底保证结果永远非空。
     */
    private static String pickDisplayName(String fromSnapshot, String fromCurrent, String identifier) {
        if (!isBlank(fromSnapshot)) {
            return fromSnapshot.trim();
        }
        if (!isBlank(fromCurrent)) {
            return fromCurrent.trim();
        }
        return identifier;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ==================================================================================
    // 三、Prompt
    // ==================================================================================

    @Override
    public List<PromptEntity> listPrompts() {
        return promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                .orderByDesc(PromptEntity::getUpdatedAtMs)
                .orderByAsc(PromptEntity::getPromptKey));
    }

    @Override
    public PromptEntity findPrompt(String promptKey) {
        return isBlank(promptKey) ? null : promptMapper.selectById(promptKey);
    }

    @Override
    @Transactional
    public boolean createPrompt(PromptEntity prompt, String operator) {
        validatePrompt(prompt);
        return insertGovernedRow(prompt, prompt.getPromptKey(), promptMapper, "prompt", operator);
    }

    @Override
    @Transactional
    public boolean savePrompt(PromptEntity prompt, String operator) {
        validatePrompt(prompt);
        return replaceGovernedRow(prompt, prompt.getPromptKey(), promptMapper, "prompt", operator);
    }

    @Override
    @Transactional
    public void deletePrompt(String promptKey) {
        if (isBlank(promptKey)) {
            return;
        }
        promptMapper.deleteById(promptKey);
        int snapshots = deleteVersionsOf(ResourceType.PROMPT, promptKey);
        log.info("控制面删除 prompt: {}（含 {} 份版本快照）", promptKey, snapshots);
    }

    // ==================================================================================
    // 四、MCP 服务端
    // ==================================================================================

    @Override
    public List<McpServerEntity> listMcpServers() {
        return mcpMapper.selectList(Wrappers.<McpServerEntity>lambdaQuery()
                .orderByDesc(McpServerEntity::getUpdatedAtMs)
                .orderByAsc(McpServerEntity::getServerName));
    }

    @Override
    public McpServerEntity findMcpServer(String serverName) {
        return isBlank(serverName) ? null : mcpMapper.selectById(serverName);
    }

    @Override
    @Transactional
    public boolean createMcpServer(McpServerEntity server, String operator) {
        validateMcp(server);
        return insertGovernedRow(server, server.getServerName(), mcpMapper, "MCP", operator);
    }

    @Override
    @Transactional
    public boolean saveMcpServer(McpServerEntity server, String operator) {
        validateMcp(server);
        return replaceGovernedRow(server, server.getServerName(), mcpMapper, "MCP", operator);
    }

    @Override
    @Transactional
    public void deleteMcpServer(String serverName) {
        if (isBlank(serverName)) {
            return;
        }
        mcpMapper.deleteById(serverName);
        int snapshots = deleteVersionsOf(ResourceType.MCP, serverName);
        log.info("控制面删除 MCP: {}（含 {} 份版本快照）", serverName, snapshots);
    }

    // ==================================================================================
    // 五、Skill（内容在官方表，治理在 md_skill_meta）
    // ==================================================================================

    @Override
    public List<SkillMetaEntity> listSkillMetas() {
        return skillMetaMapper.selectList(Wrappers.<SkillMetaEntity>lambdaQuery()
                .orderByDesc(SkillMetaEntity::getUpdatedAtMs)
                .orderByAsc(SkillMetaEntity::getSkillName));
    }

    @Override
    public SkillMetaEntity findSkillMeta(String skillName) {
        return isBlank(skillName) ? null : skillMetaMapper.selectById(skillName);
    }

    @Override
    public AgentSkill findSkillContent(String skillName) {
        // 官方 getSkill 对不存在的名字抛 IllegalArgumentException，这里先判存在，
        // 把"没有"统一成 null —— 调用方（页面详情、发布前的漂移检查）不该靠捕获异常来判断存在性。
        if (isBlank(skillName) || !skillRepository.skillExists(skillName)) {
            return null;
        }
        return skillRepository.getSkill(skillName);
    }

    @Override
    public List<String> listSkillContentNames() {
        return skillRepository.getAllSkillNames();
    }

    @Override
    @Transactional
    public boolean createSkill(SkillMetaEntity meta, String skillContent, Map<String, String> resources,
                               String operator) {
        validateSkill(meta, skillContent);
        String skillName = meta.getSkillName();
        if (skillMetaMapper.selectById(skillName) != null) {
            return false;
        }
        // 顺序固定：先内容、后 meta（理由见接口注释第五节）。
        writeSkillContent(skillName, meta.getDescription(), skillContent, resources);
        meta.initGovernance(operator, System.currentTimeMillis());
        skillMetaMapper.insert(meta);
        log.info("控制面新建 skill: {} 附属文件 {} 个 operator={}", skillName, sizeOf(resources), operator);
        return true;
    }

    @Override
    @Transactional
    public boolean saveSkill(SkillMetaEntity meta, String skillContent, Map<String, String> resources,
                             String operator) {
        validateSkill(meta, skillContent);
        String skillName = meta.getSkillName();
        SkillMetaEntity existing = skillMetaMapper.selectById(skillName);
        if (existing == null) {
            return false;
        }
        writeSkillContent(skillName, meta.getDescription(), skillContent, resources);
        meta.advanceDraft(existing, operator, System.currentTimeMillis());
        if (meta.getEnabled() == null) {
            meta.setEnabled(existing.getEnabled());
        }
        // meta 与 prompt / mcp 一样走先删后插：description 允许被清空吗？不允许（NOT NULL），
        // 但 display_name / biz_tags 允许，所以这一步仍是必要的。
        skillMetaMapper.deleteById(skillName);
        skillMetaMapper.insert(meta);
        log.info("控制面保存 skill 草稿: {} → v{} status={} operator={}", skillName, meta.getVersion(),
                meta.getStatus(), operator);
        return true;
    }

    @Override
    @Transactional
    public void deleteSkill(String skillName) {
        if (isBlank(skillName)) {
            return;
        }
        // 先删内容再删 meta：反过来的话，若内容删除失败，会留下一个"控制面看不见、框架还挂着"的 skill，
        // 而按本顺序最坏情况是"meta 还在、内容没了"，页面上表现为读取报错，是能被发现的。
        if (skillRepository.skillExists(skillName) && !skillRepository.delete(skillName)) {
            log.warn("官方 skill 仓库删除返回 false（内容可能未删干净）: {}", skillName);
        }
        skillMetaMapper.deleteById(skillName);
        int snapshots = deleteVersionsOf(ResourceType.SKILL, skillName);
        log.info("控制面删除 skill: {}（官方内容 + meta + {} 份版本快照）", skillName, snapshots);
    }

    // ==================================================================================
    // 私有：资源写入辅助（prompt / mcp / skill-meta 共用）
    // ==================================================================================

    /**
     * 新建四类资源主表的通用逻辑（agent 除外 —— 它的内容散在 6 张表，见 {@link #replaceAgentRows}）。
     *
     * <p>泛型上界是 {@link GovernedRow}，因此这里的"初始化治理字段"与四张表是同一份代码，
     * 不存在"某张表忘了把 version 置 1"的可能。
     */
    private <T extends GovernedRow> boolean insertGovernedRow(T row, String key, BaseMapper<T> mapper,
                                                              String label, String operator) {
        if (mapper.selectById(key) != null) {
            return false;
        }
        row.initGovernance(operator, System.currentTimeMillis());
        mapper.insert(row);
        log.info("控制面新建 {}: {} operator={}", label, key, operator);
        return true;
    }

    /** 保存四类资源主表的草稿（整体替换 + version 推进）。 */
    private <T extends GovernedRow> boolean replaceGovernedRow(T row, String key, BaseMapper<T> mapper,
                                                               String label, String operator) {
        T existing = mapper.selectById(key);
        if (existing == null) {
            return false;
        }
        row.advanceDraft(existing, operator, System.currentTimeMillis());
        // 先删后插而不是 updateById：见类注释第二节（清空字段必须能落库）。
        mapper.deleteById(key);
        mapper.insert(row);
        log.info("控制面保存 {} 草稿: {} → v{} status={} operator={}", label, key, row.getVersion(),
                row.getStatus(), operator);
        return true;
    }

    /**
     * 写官方 skill 内容表。
     *
     * <p>{@code overwrite=true} 是必须的：官方 {@code save()} 在 overwrite=false 时会先检查同名是否已存在，
     * 存在就抛 {@code IllegalStateException}。那是给"只允许新增"的场景设计的，
     * 而控制面的"保存草稿"本来就是覆盖语义。
     *
     * <p>这次写入<b>不在本类的事务里</b>（官方实现自己 getConnection + setAutoCommit(false) + commit），
     * 是设计文档 §10.2 记录的已知缺口，不是遗漏。
     */
    private void writeSkillContent(String skillName, String description, String skillContent,
                                   Map<String, String> resources) {
        AgentSkill skill = AgentSkill.builder()
                .name(skillName)
                .description(description)
                .skillContent(skillContent)
                .resources(resources == null ? Map.of() : resources)
                .source(SKILL_SOURCE)
                .build();
        if (!skillRepository.save(List.of(skill), true)) {
            // save 返回 false 只可能是"仓库只读"或"列表为空"，两者都属于配置/代码问题而非用户输入问题。
            throw new IllegalStateException("官方 skill 仓库写入失败（仓库只读或内容为空）: " + skillName);
        }
    }

    private void validatePrompt(PromptEntity prompt) {
        if (prompt == null || isBlank(prompt.getPromptKey())) {
            throw new ControlPlaneValidationException("prompt-key 不能为空");
        }
        List<String> problems = new ArrayList<>();
        if (isBlank(prompt.getDisplayName())) {
            problems.add("显示名不能为空（页面卡片标题用它，不填会显示成空白）");
        } else {
            prompt.setDisplayName(prompt.getDisplayName().trim());
        }
        if (!problems.isEmpty()) {
            throw new ControlPlaneValidationException(problems);
        }
        if (isBlank(prompt.getBody())) {
            // 不拦（草稿允许边写边存），但要留痕：引用它的 agent 会拿到一个空的系统提示词，
            // 而"agent 突然变得不听话"这类问题很难联想到是 prompt 正文被清空了。
            log.warn("prompt {} 的正文为空 —— 引用它的 agent 将拿到空的系统提示词", prompt.getPromptKey());
        }
    }

    private void validateMcp(McpServerEntity server) {
        if (server == null || isBlank(server.getServerName())) {
            throw new ControlPlaneValidationException("server-name 不能为空");
        }
        List<String> problems = new ArrayList<>();
        if (isBlank(server.getDisplayName())) {
            problems.add("显示名不能为空（页面卡片标题用它，不填会显示成空白）");
        } else {
            server.setDisplayName(server.getDisplayName().trim());
        }
        String transport = server.getTransport();
        if (isBlank(transport)) {
            // 没写 transport 时至少要给得出一个端点：既没有 url 也没有 command 的 MCP 是没法连的。
            if (isBlank(server.getUrl()) && isBlank(server.getCommand())) {
                problems.add("MCP 至少要给 url（远端）或 command（stdio）之一");
            }
        } else {
            String normalized = transport.trim().toLowerCase(Locale.ROOT);
            if (!MCP_TRANSPORTS.contains(normalized)) {
                problems.add("transport 只能是 streamable / sse / stdio，收到: " + transport);
            } else {
                server.setTransport(normalized);
                if (!"stdio".equals(normalized) && isBlank(server.getUrl())) {
                    problems.add("transport=" + normalized + " 时必须给 url");
                }
            }
        }
        if (server.getEnabled() == null) {
            server.setEnabled(Boolean.TRUE);
        }
        if (!problems.isEmpty()) {
            throw new ControlPlaneValidationException(problems);
        }
    }

    private void validateSkill(SkillMetaEntity meta, String skillContent) {
        if (meta == null || isBlank(meta.getSkillName())) {
            throw new ControlPlaneValidationException("skill-name 不能为空");
        }
        List<String> problems = new ArrayList<>();
        // 这条列从建表起就存在，但一直是选填（页面回落显示 skill-name）。
        // 现在统一成必填：四类资源加渠道的展示口径必须一致，否则"哪一页可以留空"
        // 会变成一个要翻代码才能回答的问题。
        if (isBlank(meta.getDisplayName())) {
            problems.add("显示名不能为空（页面卡片标题用它，不填会显示成空白）");
        } else {
            meta.setDisplayName(meta.getDisplayName().trim());
        }
        // 这两条 NOT NULL 是官方表定的（见设计文档 §4.3.1）。不在这里拦，
        // 用户看到的就是一句 "Column description cannot be null"，没人能从这句话知道该填什么。
        if (isBlank(meta.getDescription())) {
            problems.add("skill 描述不能为空（官方内容表该列 NOT NULL，框架要用它拼技能提示词）");
        }
        if (isBlank(skillContent)) {
            problems.add("skill 正文不能为空");
        }
        if (meta.getEnabled() == null) {
            meta.setEnabled(Boolean.TRUE);
        }
        if (!problems.isEmpty()) {
            throw new ControlPlaneValidationException(problems);
        }
    }

    private static int sizeOf(Map<String, String> map) {
        return map == null ? 0 : map.size();
    }

    // ==================================================================================
    // 六、渠道（md_channel + md_channel_binding）
    // ==================================================================================

    @Override
    public List<ChannelEntity> listChannels() {
        return channelMapper.selectList(Wrappers.<ChannelEntity>lambdaQuery()
                .orderByDesc(ChannelEntity::getUpdatedAtMs)
                .orderByAsc(ChannelEntity::getChannelId));
    }

    @Override
    public ChannelEntity findChannel(String channelId) {
        return isBlank(channelId) ? null : channelMapper.selectById(channelId);
    }

    @Override
    public ChannelDefinitionBundle loadChannelBundle(String channelId) {
        ChannelEntity channel = findChannel(channelId);
        if (channel == null) {
            return null;
        }
        return new ChannelDefinitionBundle(channel, loadBindings(channelId));
    }

    @Override
    public List<ChannelDefinitionBundle> loadAllChannelBundles() {
        List<ChannelEntity> channels = listChannels();
        if (channels.isEmpty()) {
            return List.of();
        }
        // 一次把绑定全捞回来再按 channel_id 分组：渠道数量是个位数、绑定也是，
        // 这样是 2 次查询，而不是"每个渠道查一次绑定"的 1+N 次。
        Map<String, List<ChannelBindingEntity>> byChannel = new HashMap<>();
        for (ChannelBindingEntity binding : channelBindingMapper.selectList(
                Wrappers.<ChannelBindingEntity>lambdaQuery()
                        .orderByAsc(ChannelBindingEntity::getChannelId)
                        .orderByAsc(ChannelBindingEntity::getSortOrder)
                        .orderByAsc(ChannelBindingEntity::getId))) {
            byChannel.computeIfAbsent(binding.getChannelId(), key -> new ArrayList<>()).add(binding);
        }
        List<ChannelDefinitionBundle> bundles = new ArrayList<>(channels.size());
        for (ChannelEntity channel : channels) {
            bundles.add(new ChannelDefinitionBundle(channel,
                    byChannel.getOrDefault(channel.getChannelId(), List.of())));
        }
        return bundles;
    }

    @Override
    @Transactional
    public boolean createChannel(ChannelDefinitionBundle bundle, String operator) {
        validateChannel(bundle);
        ChannelEntity channel = bundle.channel();
        // 先插主表：insertGovernedRow 内部做存在性检查，已存在就直接返回 false，
        // 于是绑定不会先于"渠道已存在"这个结论落地（顺序反了会留下一堆孤儿绑定行）。
        boolean created = insertGovernedRow(channel, channel.getChannelId(), channelMapper, "channel", operator);
        if (created) {
            replaceChannelBindings(channel.getChannelId(), bundle.bindings());
        }
        return created;
    }

    @Override
    @Transactional
    public boolean saveChannel(ChannelDefinitionBundle bundle, String operator) {
        validateChannel(bundle);
        ChannelEntity channel = bundle.channel();
        boolean saved = replaceGovernedRow(channel, channel.getChannelId(), channelMapper, "channel", operator);
        if (saved) {
            replaceChannelBindings(channel.getChannelId(), bundle.bindings());
        }
        return saved;
    }

    @Override
    @Transactional
    public void deleteChannel(String channelId) {
        if (isBlank(channelId)) {
            return;
        }
        channelMapper.deleteById(channelId);
        // 绑定随渠道一起删：绑定是渠道的组成部分，"没有渠道的绑定"不是一种有意义的形态。
        channelBindingMapper.delete(Wrappers.<ChannelBindingEntity>lambdaQuery()
                .eq(ChannelBindingEntity::getChannelId, channelId));
        int snapshots = deleteVersionsOf(ResourceType.CHANNEL, channelId);
        log.info("控制面删除 channel: {}（含其绑定与 {} 份版本快照）", channelId, snapshots);
    }

    @Override
    public List<String> findReferencingChannels(String agentName) {
        return isBlank(agentName) ? List.of() : channelBindingMapper.findChannelIdsByAgentId(agentName);
    }

    // ------------------------------- 私有：渠道读写辅助 -------------------------------

    /** 读某个渠道的绑定行，按 sort_order 升序；同序时按 id，保证顺序稳定（免得页面顺序每次刷新都在跳）。 */
    private List<ChannelBindingEntity> loadBindings(String channelId) {
        return channelBindingMapper.selectList(Wrappers.<ChannelBindingEntity>lambdaQuery()
                .eq(ChannelBindingEntity::getChannelId, channelId)
                .orderByAsc(ChannelBindingEntity::getSortOrder)
                .orderByAsc(ChannelBindingEntity::getId));
    }

    /**
     * 替换某个渠道的全部绑定（先删后插）。
     *
     * <p>与 {@code replaceSubAgents} 同一套做法与同一个理由：绑定数量是个位数，
     * 逐行 diff 换不来收益，却会引入"某个字段忘了比对"的静默丢数据。
     */
    private void replaceChannelBindings(String channelId, List<ChannelBindingEntity> bindings) {
        channelBindingMapper.delete(Wrappers.<ChannelBindingEntity>lambdaQuery()
                .eq(ChannelBindingEntity::getChannelId, channelId));
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        int order = 0;
        for (ChannelBindingEntity binding : bindings) {
            // id 必须清空：回滚时这些行来自快照，带着上一世的自增 id，直接插入会撞主键。
            binding.setId(null);
            binding.setChannelId(channelId);
            if (binding.getSortOrder() == null) {
                binding.setSortOrder(order);
            }
            channelBindingMapper.insert(binding);
            order++;
        }
    }

    /**
     * 渠道校验 —— 一次把问题列全（与 {@link #validateMcp} 同风格）。
     *
     * <p>拦三类"必然出问题"的输入：
     * <ol>
     *   <li><b>名称/类型为空</b>：没有类型就选不出适配器，运行时只能跳过这个渠道，
     *       而现象是"渠道列表里明明有它、消息却进不来"，排查成本很高；</li>
     *   <li><b>绑定是死规则</b>：官方 {@code ChannelRouter} 的七层匹配都要求对应字段非空，
     *       一条 peer / parent-peer / guild / team / account / channel 全空的绑定永远不会命中，
     *       但在页面上它和一条能用的规则长得一模一样；</li>
     *   <li><b>绑定指向不存在的 agent</b>：消息会路由到一个注册表里没有的名字，
     *       报错点落在 {@code AgentManager.stream}，离真正的原因（agent 名拼错了）很远。</li>
     * </ol>
     *
     * <p><b>凭据是否齐全不在这里校验</b>（比如企微没填 botId）—— 那是各渠道类型的知识，
     * 属于装配层的 {@code ChannelValidator}。存储层认识 type 的具体含义，
     * 就意味着"加一个平台要改存储层"，那是这次重构要消灭的东西。
     */
    private void validateChannel(ChannelDefinitionBundle bundle) {
        if (bundle == null || bundle.channel() == null) {
            throw new ControlPlaneValidationException("渠道内容不能为空");
        }
        ChannelEntity channel = bundle.channel();
        List<String> problems = new ArrayList<>();
        if (isBlank(channel.getChannelId())) {
            problems.add("channel-id 不能为空");
        }
        // 展示名从选填改成必填：页面表格里 channel-id 是次要信息（mono 小字），
        // 主标题是展示名。留空会让整张表的首列空掉，那比显示一个机器名更糟。
        if (isBlank(channel.getDisplayName())) {
            problems.add("显示名不能为空（页面列表首列用它，不填会显示成空白）");
        } else {
            channel.setDisplayName(channel.getDisplayName().trim());
        }
        if (isBlank(channel.getChannelType())) {
            problems.add("channel-type 不能为空（wecom / feishu ...，决定用哪个适配器装配）");
        }
        // enabled 在库里是 NOT NULL，而三态的最后一种取值（null）对渠道没有语义，
        // 所以这里补默认值，而不是留着 null 去撞数据库约束（那会冒出英文的 SQL 报错）。
        if (channel.getEnabled() == null) {
            channel.setEnabled(Boolean.TRUE);
        }
        if (!isBlank(channel.getDmScope())
                && !DM_SCOPES.contains(channel.getDmScope().trim().toUpperCase(Locale.ROOT))) {
            problems.add("dm-scope 只能是 " + DM_SCOPES + " 之一，收到: " + channel.getDmScope());
        }
        if (!isBlank(channel.getDefaultAgentId())
                && agentMapper.selectById(channel.getDefaultAgentId()) == null) {
            problems.add("默认 agent 不存在: " + channel.getDefaultAgentId());
        }
        int index = 0;
        for (ChannelBindingEntity binding : bundle.bindings()) {
            index++;
            String where = "第 " + index + " 条绑定";
            if (binding == null) {
                problems.add(where + "是空的");
                continue;
            }
            if (isBlank(binding.getAgentId())) {
                problems.add(where + " 没写 agent");
            } else if (agentMapper.selectById(binding.getAgentId()) == null) {
                problems.add(where + " 指向的 agent 不存在: " + binding.getAgentId());
            }
            if (isBlank(binding.getPeer()) && isBlank(binding.getParentPeer()) && isBlank(binding.getGuild())
                    && isBlank(binding.getTeam()) && isBlank(binding.getAccount()) && isBlank(binding.getChannel())) {
                problems.add(where + " 没有任何匹配条件"
                        + "（peer / parent-peer / guild / team / account / channel 至少填一个），这条规则永远不会命中");
            }
            if (!isBlank(binding.getSessionScope())
                    && !DM_SCOPES.contains(binding.getSessionScope().trim().toUpperCase(Locale.ROOT))) {
                problems.add(where + " 的 session-scope 只能是 " + DM_SCOPES + " 之一，收到: "
                        + binding.getSessionScope());
            }
        }
        if (!problems.isEmpty()) {
            throw new ControlPlaneValidationException(problems);
        }
    }

    // ==================================================================================
    // 七、模型（md_model）
    // ==================================================================================

    @Override
    public List<ModelEntity> listModels() {
        // 展示名 + model-key 双排序：同名的两个模型（或展示名都为空的老数据）顺序才稳定
        return modelMapper.selectList(Wrappers.<ModelEntity>lambdaQuery()
                .orderByAsc(ModelEntity::getDisplayName)
                .orderByAsc(ModelEntity::getModelKey));
    }

    @Override
    public ModelEntity findModel(String modelKey) {
        return isBlank(modelKey) ? null : modelMapper.selectById(modelKey);
    }

    @Override
    @Transactional
    public boolean createModel(ModelEntity model, String operator) {
        String key = model.getModelKey();
        // 存在性检查放在"清旧默认值"之前：反过来的话，一次重名的创建失败会顺手把别人的默认标记清掉，
        // 而这次调用返回的是 false（正常的 409 竞争），事务不会回滚 —— 默认模型就此凭空消失。
        if (modelMapper.selectById(key) != null) {
            return false;
        }
        if (Boolean.TRUE.equals(model.getIsDefault())) {
            clearDefaultModel(key);
        }
        return insertGovernedRow(model, key, modelMapper, "模型", operator);
    }

    @Override
    @Transactional
    public boolean saveModel(ModelEntity model, String operator) {
        String key = model.getModelKey();
        if (modelMapper.selectById(key) == null) {
            return false;
        }
        if (Boolean.TRUE.equals(model.getIsDefault())) {
            clearDefaultModel(key);
        }
        return replaceGovernedRow(model, key, modelMapper, "模型", operator);
    }

    @Override
    @Transactional
    public void deleteModel(String modelKey) {
        if (isBlank(modelKey)) {
            return;
        }
        modelMapper.deleteById(modelKey);
        int snapshots = deleteVersionsOf(ResourceType.MODEL, modelKey);
        log.info("控制面删除模型: {}（含 {} 份版本快照）", modelKey, snapshots);
    }

    /**
     * 把"默认模型"收敛到唯一一行：清掉除 {@code keepKey} 之外的全部默认标记。
     *
     * <p>为什么由应用层而不是数据库保证唯一：MySQL 没有"部分唯一索引"，
     * 而"用 NULL 表示非默认"会与快照还原冲突（NULL 与 0 在三态语义下不等价，
     * 见建表脚本第 11 节的注释）。既然只能在应用层做，就把它放在事务内 ——
     * 两个默认模型同时存在时，装配期取到哪个取决于查询计划，那是无法复现的故障。
     */
    private void clearDefaultModel(String keepKey) {
        List<ModelEntity> defaults = modelMapper.selectList(Wrappers.<ModelEntity>lambdaQuery()
                .eq(ModelEntity::getIsDefault, true));
        for (ModelEntity row : defaults) {
            if (row.getModelKey().equals(keepKey)) {
                continue;
            }
            row.setIsDefault(false);
            modelMapper.updateById(row);
            log.info("控制面取消旧的默认模型标记: {}", row.getModelKey());
        }
    }

    // ==================================================================================
    // 八、发布 / 回滚 / 版本 / 引用（受治理资源共用）
    // ==================================================================================

    @Override
    @Transactional
    public void publish(ResourceType type, String name, String operator, String changeNote) {
        long now = System.currentTimeMillis();
        switch (type) {
            case PROMPT -> {
                PromptEntity row = requireRow(promptMapper.selectById(name), type, name);
                row.markPublished(now, operator);
                promptMapper.updateById(row);
                writeSnapshot(type, name, row.getVersion(), row, changeNote, operator, now);
            }
            case MCP -> {
                McpServerEntity row = requireRow(mcpMapper.selectById(name), type, name);
                row.markPublished(now, operator);
                mcpMapper.updateById(row);
                writeSnapshot(type, name, row.getVersion(), row, changeNote, operator, now);
            }
            case SKILL -> {
                SkillMetaEntity meta = requireRow(skillMetaMapper.selectById(name), type, name);
                if (!skillRepository.skillExists(name)) {
                    // meta 有、内容没了 —— 设计文档 §10.7 说的漂移。放行的话，引用它的 agent
                    // 会在热装配时缺技能，报错点离真正的原因很远，所以在这里拦住。
                    throw new ControlPlaneValidationException(
                            "skill 内容缺失（官方内容表里没有 " + name + "），无法发布");
                }
                AgentSkill content = skillRepository.getSkill(name);
                meta.markPublished(now, operator);
                skillMetaMapper.updateById(meta);
                writeSnapshot(type, name, meta.getVersion(),
                        new SkillSnapshot(meta, content.getDescription(), content.getSkillContent(),
                                content.getResources()),
                        changeNote, operator, now);
            }
            case AGENT -> {
                AgentDefinitionBundle bundle = requireRow(loadAgentBundle(name), type, name);
                bundle.getAgent().markPublished(now, operator);
                agentMapper.updateById(bundle.getAgent());
                writeSnapshot(type, name, bundle.getAgent().getVersion(), bundle, changeNote, operator, now);
            }
            case CHANNEL -> {
                ChannelDefinitionBundle bundle = requireRow(loadChannelBundle(name), type, name);
                bundle.channel().markPublished(now, operator);
                channelMapper.updateById(bundle.channel());
                // 快照存 ChannelSnapshot 而不是 bundle：前者是"钉死的持久化格式"，
                // 后者是随页面需要会变的接口形状，理由见 ChannelSnapshot 的类注释。
                writeSnapshot(type, name, bundle.channel().getVersion(),
                        ChannelSnapshot.of(bundle), changeNote, operator, now);
            }
            case MODEL -> {
                ModelEntity row = requireRow(modelMapper.selectById(name), type, name);
                row.markPublished(now, operator);
                modelMapper.updateById(row);
                // 快照里含<b>明文 apiKey</b>：回滚必须能恢复旧密钥，所以这里不能打码。
                // 与主表存明文是同一处已知债（见 ModelEntity 的类注释），不是这一处的疏漏。
                writeSnapshot(type, name, row.getVersion(), row, changeNote, operator, now);
            }
            case WORKFLOW -> {
                WorkflowEntity row = requireRow(workflowMapper.selectById(name), type, name);
                // 发布是唯一的强制校验点：引擎按发布版本执行，放一棵有环、或引用了不存在 agent
                // 的图上线，代价是"运行时才炸"，而那时前面的节点已经产生副作用了。
                // 注意校验在 markPublished <b>之前</b>：先改状态再校验的话，校验失败时
                // 主表已经写着已发布，而这个版本永远不会有一份快照。
                workflowValidator.validate(row.getSpec(), this::agentExists);
                row.markPublished(now, operator);
                workflowMapper.updateById(row);
                writeSnapshot(type, name, row.getVersion(), row, changeNote, operator, now);
                projectNodeRefs(row);
            }
        }
    }

    @Override
    @Transactional
    public void rollback(ResourceType type, String name, int version, String operator) {
        ResourceVersionEntity snapshot = findVersionRow(type, name, version);
        if (snapshot == null) {
            throw new ControlPlaneValidationException(type + " " + name + " 不存在版本 v" + version);
        }
        long now = System.currentTimeMillis();
        switch (type) {
            case PROMPT -> {
                PromptEntity old = readSnapshot(snapshot, PromptEntity.class);
                PromptEntity current = requireRow(promptMapper.selectById(name), type, name);
                // 主键以路径参数为准，不信快照里的键：快照是"当时的内容"，
                // 而"回滚到哪个资源"这件事是调用方刚刚决定的。
                old.setPromptKey(name);
                old.setDisplayName(pickDisplayName(old.getDisplayName(), current.getDisplayName(), name));
                old.advanceDraft(current, operator, now);
                promptMapper.deleteById(name);
                promptMapper.insert(old);
            }
            case MCP -> {
                McpServerEntity old = readSnapshot(snapshot, McpServerEntity.class);
                McpServerEntity current = requireRow(mcpMapper.selectById(name), type, name);
                old.setServerName(name);
                old.setDisplayName(pickDisplayName(old.getDisplayName(), current.getDisplayName(), name));
                old.advanceDraft(current, operator, now);
                mcpMapper.deleteById(name);
                mcpMapper.insert(old);
            }
            case SKILL -> {
                SkillSnapshot old = readSnapshot(snapshot, SkillSnapshot.class);
                SkillMetaEntity current = requireRow(skillMetaMapper.selectById(name), type, name);
                SkillMetaEntity meta = requireSnapshot(old.meta(), name, version);
                meta.setSkillName(name);
                meta.setDisplayName(pickDisplayName(meta.getDisplayName(), current.getDisplayName(), name));
                // 内容先回写：它不在本事务里（官方仓库自管事务），失败时 meta 还没被动过，
                // 因此最坏情况是"内容回了旧版、meta 还是新版"，页面上看得见，可再操作一次。
                writeSkillContent(name, meta.getDescription(), old.skillContent(), old.resources());
                meta.advanceDraft(current, operator, now);
                skillMetaMapper.deleteById(name);
                skillMetaMapper.insert(meta);
            }
            case AGENT -> {
                AgentDefinitionBundle old = readSnapshot(snapshot, AgentDefinitionBundle.class);
                AgentEntity current = requireRow(agentMapper.selectById(name), type, name);
                AgentEntity oldAgent = requireSnapshot(old == null ? null : old.getAgent(), name, version);
                oldAgent.setAgentName(name);
                oldAgent.setDisplayName(pickDisplayName(oldAgent.getDisplayName(), current.getDisplayName(), name));
                oldAgent.advanceDraft(current, operator, now);
                replaceAgentRows(old);
            }
            case CHANNEL -> {
                ChannelSnapshot old = readSnapshot(snapshot, ChannelSnapshot.class);
                ChannelEntity current = requireRow(channelMapper.selectById(name), type, name);
                ChannelDefinitionBundle bundle = requireSnapshot(old == null ? null : old.toBundle(), name, version);
                // 与 AGENT 分支同一条约定：主键以路径参数为准，不信快照里的键。
                bundle.channel().setChannelId(name);
                bundle.channel().setDisplayName(
                        pickDisplayName(bundle.channel().getDisplayName(), current.getDisplayName(), name));
                bundle.channel().advanceDraft(current, operator, now);
                channelMapper.deleteById(name);
                channelMapper.insert(bundle.channel());
                // 绑定也一起回滚：只还原渠道行、留下当前这批绑定，是回滚里最容易被忽略的错误 ——
                // 页面上看起来"回滚成功了"，实际生效的路由规则还是新的那套。
                replaceChannelBindings(name, bundle.bindings());
            }
            case MODEL -> {
                ModelEntity old = readSnapshot(snapshot, ModelEntity.class);
                ModelEntity current = requireRow(modelMapper.selectById(name), type, name);
                ModelEntity oldRow = requireSnapshot(old, name, version);
                // 主键以路径参数为准，不信快照里的键（同 AGENT / CHANNEL 分支的约定）
                oldRow.setModelKey(name);
                oldRow.setDisplayName(pickDisplayName(oldRow.getDisplayName(), current.getDisplayName(), name));
                // is_default 是<b>运维开关</b>而不是内容：回滚"端点改错了"这件事，
                // 不该顺手把"平台兜底模型是哪个"也改掉 —— 那会影响所有未声明 model_ref 的 agent，
                // 而用户以为自己只是退回了一个端点。
                oldRow.setIsDefault(current.getIsDefault());
                oldRow.advanceDraft(current, operator, now);
                modelMapper.deleteById(name);
                modelMapper.insert(oldRow);
            }
            case WORKFLOW -> {
                WorkflowEntity old = readSnapshot(snapshot, WorkflowEntity.class);
                WorkflowEntity current = requireRow(workflowMapper.selectById(name), type, name);
                WorkflowEntity oldRow = requireSnapshot(old, name, version);
                // 主键以路径参数为准，不信快照里的键（同其它分支的约定）
                oldRow.setWorkflowKey(name);
                oldRow.setDisplayName(pickDisplayName(oldRow.getDisplayName(), current.getDisplayName(), name));
                oldRow.advanceDraft(current, operator, now);
                workflowMapper.deleteById(name);
                workflowMapper.insert(oldRow);
                // 刻意<b>不动</b>引用投影：回滚只把旧内容写回草稿，线上仍是原来那一版
                //（advanceDraft 已保证 published_version 不变），所以已发布版本的引用没有变化。
                // 新版本的引用会在下一次"发布"时由 projectNodeRefs 投影出来。
            }
        }
        log.info("控制面回滚 {}: {} 到 v{}，内容成为新版本且状态置 DRAFT（需再发布一次才生效）operator={}",
                type, name, version, operator);
    }

    @Override
    public List<ResourceVersionInfo> listVersions(ResourceType type, String name) {
        // 刻意走 listVersionHeaders（只查 4 个投影列）：版本列表页不显示正文，
        // 而 agent 的快照是 6 张表的整行 JSON，一次列表拖回几十上百 KB 纯属浪费。
        List<ResourceVersionEntity> rows = versionMapper.listVersionHeaders(type.name(), name);
        List<ResourceVersionInfo> versions = new ArrayList<>(rows.size());
        for (ResourceVersionEntity row : rows) {
            versions.add(new ResourceVersionInfo(
                    row.getVersion() == null ? 0 : row.getVersion(),
                    row.getChangeNote(),
                    row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs(),
                    row.getCreatedBy()));
        }
        return versions;
    }

    @Override
    public List<String> findReferencingAgents(ResourceType type, String name) {
        if (isBlank(name)) {
            return List.of();
        }
        // 四类资源的"引用落点"各不相同，这正是这里必须 switch 而不能统一处理的原因：
        // prompt 是单值外键列，skill / mcp 是 JSON 数组列，agent 则是子 agent 表里的被引用方。
        return switch (type) {
            case PROMPT -> refMapper.findAgentNamesByPromptKey(name);
            case SKILL -> refMapper.findAgentNamesBySkillName(name);
            case MCP -> refMapper.findAgentNamesByMcpName(name);
            case AGENT -> subAgentMapper.findAgentNamesBySubName(name);
            // 渠道不反向引用 agent（引用方向是"渠道绑 agent"），所以这里的答案是"没有"。
            // 反方向的删除保护由 findReferencingChannels 承担，别把两者搞混：
            // 本方法的返回值会被渲染成"被这些 agent 引用"，塞渠道名进去会误导用户。
            case CHANNEL -> List.of();
            // 模型是"被 agent 引用"的资源，反查落在四张表上（见 ModelMapper 的 UNION）。
            case MODEL -> modelMapper.findAgentNamesByModelKey(name);
            // 工作流的引用方向是"工作流的节点引用 agent"，agent 不认识工作流，
            // 所以"哪些 agent 引用了这个工作流"的答案是"没有"。
            // 反方向的删除保护（删 agent 时看它被哪些已发布的工作流引用）
            // 由 findReferencingWorkflows 承担，别把两者搞混 —— 本方法的返回值会被
            // 渲染成"被这些 agent 引用"，塞工作流名进去会误导用户。
            case WORKFLOW -> List.of();
        };
    }

    // ==================================================================================
    // 九、工作流定义（md_workflow）—— 第六类受治理资源
    // ==================================================================================

    /**
     * 全部工作流，最近改过的在前。
     *
     * <p>按 updated_at_ms 倒序而不是按名字：与 prompt / mcp 那几张表一致 ——
     * 这是一个"我最近动过什么"的治理视图；而模型那类是"挑一个用"的清单，才按名字排。
     */
    @Override
    public List<WorkflowEntity> listWorkflows() {
        return workflowMapper.selectList(Wrappers.<WorkflowEntity>lambdaQuery()
                .orderByDesc(WorkflowEntity::getUpdatedAtMs));
    }

    @Override
    public WorkflowEntity findWorkflow(String workflowKey) {
        return isBlank(workflowKey) ? null : workflowMapper.selectById(workflowKey.trim());
    }

    @Override
    public WorkflowEntity loadPublishedWorkflow(String workflowKey) {
        if (isBlank(workflowKey)) {
            return null;
        }
        WorkflowEntity row = workflowMapper.selectById(workflowKey.trim());
        // never published 有两种成因：新建后没发过、或快照被手工清了。
        // 两种都归到"没有可执行的版本"，由调用方给出同一句人话提示。
        if (row == null || row.getPublishedVersion() == null) {
            return null;
        }
        ResourceVersionEntity snapshot =
                findVersionRow(ResourceType.WORKFLOW, row.getWorkflowKey(), row.getPublishedVersion());
        if (snapshot == null) {
            log.warn("工作流 {} 的已发布版本 v{} 没有快照行，按未发布处理",
                    row.getWorkflowKey(), row.getPublishedVersion());
            return null;
        }
        WorkflowEntity published = readSnapshot(snapshot, WorkflowEntity.class);
        if (published == null) {
            return null;
        }
        // 主键与发布号以主表为准，不信快照里存的：快照描述的是"当时的内容"，
        // 而"现在线上是哪一版"这件事只有主表知道（同 rollback 分支的约定）。
        published.setWorkflowKey(row.getWorkflowKey());
        published.setPublishedVersion(row.getPublishedVersion());
        published.setPublishedAtMs(row.getPublishedAtMs());
        published.setStatus(row.getStatus());
        published.setVersion(row.getVersion());
        return published;
    }

    /**
     * 新建工作流草稿。
     *
     * <p>这里<b>不做完整校验</b>（不查环、不查引用是否存在），只做"能不能存进去"这一层。
     * 理由见 WorkflowValidator 的类注释第一节：页面上的图是一步步搭出来的，
     * 中途必然半成品；保存就拦截等于逼人先在心里把图画完。校验发生在发布与执行。
     */
    @Override
    @Transactional
    public boolean createWorkflow(WorkflowEntity workflow, String operator) {
        if (workflow == null || isBlank(workflow.getWorkflowKey())) {
            throw new ControlPlaneValidationException("工作流缺少 workflow_key");
        }
        String key = workflow.getWorkflowKey().trim();
        workflow.setWorkflowKey(key);
        if (workflowMapper.selectById(key) != null) {
            return false;
        }
        if (isBlank(workflow.getDisplayName())) {
            throw new ControlPlaneValidationException("工作流 " + key + " 缺少展示名 displayName");
        }
        if (workflow.getSpec() == null) {
            // 主表那一列是 NOT NULL，空图收敛成一个空 spec 而不是 null
            workflow.setSpec(new WorkflowSpec());
        }
        workflow.initGovernance(operator, System.currentTimeMillis());
        workflowMapper.insert(workflow);
        return true;
    }

    /** 保存草稿：整体替换 + version+1 + 状态回 DRAFT；库里没有这个 key 时返回 false（控制器回 404）。 */
    @Override
    @Transactional
    public boolean saveWorkflow(WorkflowEntity workflow, String operator) {
        if (workflow == null || isBlank(workflow.getWorkflowKey())) {
            throw new ControlPlaneValidationException("工作流缺少 workflow_key");
        }
        String key = workflow.getWorkflowKey().trim();
        workflow.setWorkflowKey(key);
        WorkflowEntity current = workflowMapper.selectById(key);
        if (current == null) {
            return false;
        }
        if (isBlank(workflow.getDisplayName())) {
            // 展示名不因为"页面没传"被清空：它会被列表页与运行详情页当作标题显示，
            // 空标题在这些地方看起来像加载失败。要改就显式给一个值。
            workflow.setDisplayName(current.getDisplayName());
        }
        if (workflow.getSpec() == null) {
            workflow.setSpec(new WorkflowSpec());
        }
        // 先删后插而不是 updateById：与 agent 那套"整体替换"同一个理由 ——
        // MyBatis-Plus 默认只更新非 null 字段，清空 description 这类操作会被静默忽略。
        workflow.advanceDraft(current, operator, System.currentTimeMillis());
        workflowMapper.deleteById(key);
        workflowMapper.insert(workflow);
        return true;
    }

    /**
     * 删除工作流：定义 + 引用投影 + 版本快照。
     *
     * <p>引用投影必须一起删：留着的话，"删除 agent 之前的检查"会报出一个已经不存在的工作流名，
     * 而用户去页面上根本找不到它。
     */
    @Override
    @Transactional
    public void deleteWorkflow(String workflowKey) {
        if (isBlank(workflowKey)) {
            return;
        }
        String key = workflowKey.trim();
        workflowMapper.deleteById(key);
        workflowNodeRefMapper.deleteByWorkflow(key);
        deleteVersionsOf(ResourceType.WORKFLOW, key);
    }

    @Override
    public List<String> findReferencingWorkflows(String agentName) {
        if (isBlank(agentName)) {
            return List.of();
        }
        return workflowNodeRefMapper.findWorkflowKeysByAgent(agentName.trim());
    }

    @Override
    public List<String> checkWorkflowSpec(WorkflowSpec spec) {
        return workflowValidator.check(spec, this::agentExists);
    }

    /**
     * agent 是否存在。
     *
     * <p>校验器要的是一个"断言"，这里用主表的一条等值查询兑现 ——
     * 刻意不注入 AgentManager（运行时注册表）：装配源尚未切到 db 时，
     * 注册表里可能一个实例都没有，用它做校验会把所有工作流都判成"引用了不存在的 agent"。
     * 治理数据之间的一致性应该由治理数据自己回答。
     */
    private boolean agentExists(String agentName) {
        return !isBlank(agentName) && agentMapper.selectById(agentName.trim()) != null;
    }

    /**
     * 把本次发布版本的节点引用投影成行。
     *
     * <p>先按 revision 清、再插，让"重复发布同一版"保持幂等（与 writeSnapshot 是同一个套路）。
     * 只投影 agent 节点：condition / join 不引用任何受治理资源，
     * 将来 condition 引用知识库时在这里加一个分支，而不是给 ref_type 加一堆空行。
     */
    private void projectNodeRefs(WorkflowEntity workflow) {
        int revision = workflow.getVersion() == null ? 1 : workflow.getVersion();
        workflowNodeRefMapper.deleteByWorkflowRevision(workflow.getWorkflowKey(), revision);
        for (WorkflowSpec.WorkflowNode node : workflow.getSpec().nodesOrEmpty()) {
            if (node == null || isBlank(node.getAgentId())) {
                continue;
            }
            WorkflowNodeType nodeType;
            try {
                nodeType = WorkflowNodeType.fromSpec(node.getType());
            } catch (ControlPlaneValidationException e) {
                // 发布前已校验过，走到这里说明校验被绕过了；跳过而不是中断整次发布，
                // 因为此刻状态与快照都已经写好了，为一条引用投影把事务回滚代价更大
                continue;
            }
            if (nodeType != WorkflowNodeType.AGENT) {
                continue;
            }
            WorkflowNodeRefEntity ref = new WorkflowNodeRefEntity();
            ref.setWorkflowKey(workflow.getWorkflowKey());
            ref.setRevision(revision);
            ref.setNodeKey(node.keyOrEmpty());
            ref.setNodeType(nodeType.name());
            ref.setRefType(WorkflowNodeRefEntity.REF_AGENT);
            ref.setRefId(node.getAgentId().trim());
            workflowNodeRefMapper.insert(ref);
        }
    }

    // ==================================================================================
    // 私有：快照读写
    // ==================================================================================

    /**
     * 落一份版本快照，并记录"这是一次发布"。
     *
     * <p>先删同版本旧行再插入，是为了让发布<b>幂等</b>：用户连点两次发布时 version 没变，
     * 第二次会撞 {@code uk_type_name_ver}，抛一个主键冲突给前端；
     * 而这两次的内容本来就完全相同，覆盖掉不丢任何信息。
     */
    private void writeSnapshot(ResourceType type, String name, Integer version, Object payload,
                               String changeNote, String operator, long now) {
        int snapshotVersion = version == null ? 1 : version;
        versionMapper.delete(Wrappers.<ResourceVersionEntity>lambdaQuery()
                .eq(ResourceVersionEntity::getResourceType, type.name())
                .eq(ResourceVersionEntity::getResourceName, name)
                .eq(ResourceVersionEntity::getVersion, snapshotVersion));

        ResourceVersionEntity snapshot = new ResourceVersionEntity();
        snapshot.setResourceType(type.name());
        snapshot.setResourceName(name);
        snapshot.setVersion(snapshotVersion);
        snapshot.setSnapshotJson(toJson(payload));
        snapshot.setChangeNote(changeNote);
        snapshot.setCreatedAtMs(now);
        snapshot.setCreatedBy(operator);
        versionMapper.insert(snapshot);
        log.info("控制面发布 {}: {} v{} operator={} 变更说明={}", type, name, snapshotVersion, operator,
                changeNote);
    }

    /** 取指定版本快照的整行（含正文），回滚才需要。 */
    private ResourceVersionEntity findVersionRow(ResourceType type, String name, int version) {
        return versionMapper.selectOne(Wrappers.<ResourceVersionEntity>lambdaQuery()
                .eq(ResourceVersionEntity::getResourceType, type.name())
                .eq(ResourceVersionEntity::getResourceName, name)
                .eq(ResourceVersionEntity::getVersion, version));
    }

    private String toJson(Object payload) {
        try {
            return snapshotMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照序列化失败: " + payload.getClass().getSimpleName(), e);
        }
    }

    private <T> T readSnapshot(ResourceVersionEntity snapshot, Class<T> payloadType) {
        try {
            return snapshotMapper.readValue(snapshot.getSnapshotJson(), payloadType);
        } catch (JsonProcessingException e) {
            // 快照读不出来是运维级故障（库被手工改过 / 反序列化格式演进不兼容），
            // 不是用户输入问题，所以走 IllegalStateException 而不是校验异常。
            throw new IllegalStateException("快照反序列化失败: " + snapshot.getResourceType() + " "
                    + snapshot.getResourceName() + " v" + snapshot.getVersion(), e);
        }
    }

    private static <T> T requireRow(T row, ResourceType type, String name) {
        if (row == null) {
            throw new ControlPlaneValidationException(type + " " + name + " 不存在");
        }
        return row;
    }

    private static <T> T requireSnapshot(T payload, String name, int version) {
        if (payload == null) {
            throw new ControlPlaneValidationException("版本快照内容损坏，无法回滚: " + name + " v" + version);
        }
        return payload;
    }
}
