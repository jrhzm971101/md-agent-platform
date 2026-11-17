package com.md.agentplatform.server.controlplane.store;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
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
import com.md.agentplatform.server.controlplane.store.entity.SkillMetaEntity;
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
import com.md.agentplatform.server.workflow.WorkflowValidator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制面存储的<b>真库</b>集成测试（需要连得到 application.yml 里那台 MySQL）。
 *
 * <h2>一、为什么类名以 IT 结尾</h2>
 * surefire 默认只跑 {@code *Test}，{@code *IT} 不在其中 —— 于是它不会出现在 {@code mvn test} 里
 * （别人的机器上没库也能构建通过）。需要时手工执行：
 * <pre>
 * mvn -o test -Dtest=MyBatisControlPlaneStoreIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <h2>二、它守的是"普通单测守不住"的那几条</h2>
 * <ol>
 *   <li><b>JSON 列能不能读回来</b>：实体上少了 {@code autoResultMap = true} 时写入正常、读取恒为 null，
 *       纯映射单测测不出来（那一项只在 MyBatis 组装 ResultMap 时才生效）。本测试在
 *       ref / runtime / memory / workspace / sub_agent 五张表上都放了 JSON 列，
 *       就是为了让"漏一个注解"必然在这里暴露；</li>
 *   <li><b>JSON_CONTAINS 反查引用能不能用</b>：删除保护的三个查询依赖 MySQL 的 JSON 函数，
 *       以及 JSON_QUOTE 对特殊字符的转义 —— 只有真库能验；</li>
 *   <li><b>先删后插是否真的清空了字段</b>：MyBatis-Plus 默认不更新 null 字段，
 *       本测试专门用"把描述改成 null 再保存"来证明整体替换生效了；</li>
 *   <li><b>跨 schema 的 skill 内容</b>：官方内容表在另一个库，读写路径与事务边界都与本库不同；</li>
 *   <li><b>列宽 / NOT NULL / 唯一键</b>：DDL 与实体对不上时只有真库会报。</li>
 * </ol>
 *
 * <h2>三、这个 IT <b>测不到</b>事务回滚</h2>
 * 测试不启动 Spring 容器，{@code @Transactional} 注解在这里是<b>不生效</b>的（没有事务代理）。
 * 换句话说：本测试验证的是"每条 SQL 与映射对不对"，而"六张表要么全写、要么全不写"
 * 这件事只能靠 Spring 容器起来后验。这是这类无容器 IT 的固有边界，写在这里免得后人误会。
 *
 * <p>测试数据统一用 {@link #PREFIX} 前缀，{@link #cleanUp} 负责删干净（含官方 skill 内容表），
 * 不会在开发库里留下垃圾，也不会影响真实链路数据。
 */
class MyBatisControlPlaneStoreIT {

    // 通过环境变量提供真实库连接：MYSQL_URL / MYSQL_USERNAME / MYSQL_PASSWORD。
    private static final String URL = System.getenv().getOrDefault("MYSQL_URL",
            "jdbc:mysql://127.0.0.1:3306/md-agent-platform"
                    + "?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai");
    private static final String USERNAME = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv("MYSQL_PASSWORD");

    /** 测试数据前缀：清理时只删自己的，绝不误伤开发库里的真实数据。 */
    private static final String PREFIX = "itest-cp-";

    private static DataSource dataSource;
    private static SqlSession sqlSession;
    private static MyBatisControlPlaneStore store;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(PASSWORD != null && !PASSWORD.isBlank(),
                "skip: set MYSQL_PASSWORD (and optionally MYSQL_URL/MYSQL_USERNAME) to run this integration test");
        // 必须用连接池，而且这不是"顺手优化"：官方 skill 仓库每次调用都自己 getConnection()，
        // 无池时"每次一个新连接"在这个公网库上实测要 5~7 秒一个往返，整个 IT 会跑到 200 秒以上
        // （对比：Hikari 下同一批调用合计不到 1 秒）。生产用的就是 Hikari
        // （spring-boot-starter-jdbc 的默认池），测试与生产同构才既快又可信。
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(URL);
        hikari.setUsername(USERNAME);
        hikari.setPassword(PASSWORD);
        hikari.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(hikari);
        applySchema();

        SqlSessionFactory factory = sqlSessionFactory(dataSource);
        sqlSession = factory.openSession(true);

        // 与 ControlPlaneStoreConfig 一致的构造方式（createIfNotExist + writeable），
        // 这样测试覆盖的是生产用的那套配置，而不是"测试专用的一套"。
        AgentSkillRepository skillRepository = MysqlSkillRepository.builder(dataSource)
                .createIfNotExist(true)
                .writeable(true)
                .build();

        store = new MyBatisControlPlaneStore(
                sqlSession.getMapper(AgentMapper.class),
                sqlSession.getMapper(AgentRefMapper.class),
                sqlSession.getMapper(AgentRuntimeMapper.class),
                sqlSession.getMapper(AgentMemoryMapper.class),
                sqlSession.getMapper(AgentWorkspaceMapper.class),
                sqlSession.getMapper(AgentSubAgentMapper.class),
                sqlSession.getMapper(PromptMapper.class),
                sqlSession.getMapper(McpServerMapper.class),
                sqlSession.getMapper(ModelMapper.class),
                sqlSession.getMapper(SkillMetaMapper.class),
                sqlSession.getMapper(ResourceVersionMapper.class),
                skillRepository,
                sqlSession.getMapper(ChannelMapper.class),
                sqlSession.getMapper(ChannelBindingMapper.class),
                // 工作流与另外五类走同一个 Store（理由见 ControlPlaneStore 第九节），
                // 所以这里也必须一起装配，否则接口里那几个方法在 IT 里是空的。
                sqlSession.getMapper(WorkflowMapper.class),
                sqlSession.getMapper(WorkflowNodeRefMapper.class),
                new WorkflowValidator());

        cleanUp();
    }

    @AfterAll
    static void tearDown() throws Exception {
        cleanUp();
        sqlSession.close();
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    // ==================================================================================
    // agent
    // ==================================================================================

    /** 六张表的整束往返：重点是五张表上的 JSON 列与三态 null 能不能原样回来。 */
    @Test
    void agentBundleWithJsonColumnsSurvivesARoundTrip() {
        String child = PREFIX + "child";
        String parent = PREFIX + "parent";
        // 子 agent 必须是 REACT（见 validateAgentContent）：HARNESS 是编排体，只能编排别人、不能被派发。
        assertTrue(store.createAgent(newAgentBundle(child, "REACT"), "itest"), "子 agent 先建出来，供父 agent 引用");

        AgentDefinitionBundle bundle = newAgentBundle(parent);
        // 两个子 agent：本地那个必须真实存在（见校验规则），远程那个只需要给得出端点。
        // 顺序即 sort_order，断言按这个顺序验回来。
        bundle.getSubAgents().clear();
        bundle.getSubAgents().add(localSubAgent(child, 0));
        bundle.getSubAgents().add(remoteSubAgent("itest-remote-child", 1));

        assertTrue(store.createAgent(bundle, "itest"));
        assertFalse(store.createAgent(newAgentBundle(parent), "itest"), "重名新建必须返回 false");

        AgentDefinitionBundle loaded = store.loadAgentBundle(parent);
        assertNotNull(loaded);
        assertEquals("HARNESS", loaded.getAgent().getAgentType());
        // display_name 必须原样回来：它是页面卡片的标题，落库丢了不会报错、只会让页面变空白
        assertEquals("集成测试编排 Agent", loaded.getAgent().getDisplayName());
        assertEquals(List.of("itest", "观测"), loaded.getAgent().getBizTags(), "biz_tags 是 JSON 列");
        assertEquals(GovernedRow.STATUS_DRAFT, loaded.getAgent().getStatus());
        assertEquals(1, loaded.getAgent().getVersion().intValue());
        assertNull(loaded.getAgent().getPublishedVersion(), "新建后还没发布过");

        AgentRefEntity ref = loaded.getRef();
        assertNotNull(ref);
        assertEquals("openAIChatModel", loaded.getRuntime().getModelRef());
        assertEquals(12, loaded.getRuntime().getMaxIters().intValue());
        assertTrue(loaded.getRuntime().getCheckRunning());
        assertNull(loaded.getRuntime().getEnableTaskList(), "三态开关的 null 必须原样回来，不能变成 false");
        assertEquals("0.3", loaded.getRuntime().getGenerateOptions().get("temperature"),
                "generate_options 是 JSON 列");

        AgentMemoryEntity memory = loaded.getMemory();
        assertFalse(memory.getCompactionDisabled());
        assertEquals(40, memory.getTriggerMessages().intValue());
        assertEquals(0, memory.getKeepTokensRatio().compareTo(new BigDecimal("0.2")));
        assertEquals("itest/compact/", memory.getPrune().get("path"), "prune 是 JSON 列");
        assertEquals(List.of("git_show_commit"), memory.getEvictExcludedTools(), "JSON 数组列");

        AgentWorkspaceEntity workspace = loaded.getWorkspace();
        assertEquals("itest/ws", workspace.getWorkspacePath());
        assertEquals(List.of("*.md", "AGENTS.md"), workspace.getFilesystem().get("readOnly"),
                "filesystem 是嵌套 JSON 列");
        assertNull(workspace.getDisableToolsConfig(), "三态开关的 null 必须原样回来");

        List<AgentSubAgentEntity> subAgents = loaded.getSubAgents();
        assertEquals(2, subAgents.size(), "子 agent 数量");
        assertEquals(child, subAgents.get(0).getSubName(), "顺序即 sort_order：本地子 agent 在前");
        assertEquals(Map.of("X-Tenant", "itest"), subAgents.get(0).getHeaders(), "headers 是 JSON 列");
        assertTrue(subAgents.get(1).getRemote(), "第二个是远程子 agent");
        assertEquals("https://itest.example.com/a2a", subAgents.get(1).getUrl());
        assertEquals("always", subAgents.get(1).getRemoteContextAttributes().get("ask"));

        List<AgentDefinitionBundle> all = store.loadAllAgentBundles();
        assertTrue(all.stream().anyMatch(item -> parent.equals(item.agentName())), "批量加载要包含它");
    }

    /** 保存草稿推进版本号、能清空字段；发布落快照；回滚把旧内容变成新版本。 */
    @Test
    void draftSaveAdvancesVersionAndRollbackRestoresOldContent() {
        String name = PREFIX + "rollback";
        AgentDefinitionBundle bundle = newAgentBundle(name);
        assertTrue(store.createAgent(bundle, "itest"));

        // 1) 保存草稿：v2
        AgentDefinitionBundle edit = newAgentBundle(name);
        edit.getAgent().setDescription("第二版描述");
        assertTrue(store.saveAgent(edit, "itest"));
        assertEquals(2, store.findAgent(name).getVersion().intValue());
        assertEquals(GovernedRow.STATUS_DRAFT, store.findAgent(name).getStatus());

        // 2) 发布：状态改变但不推进 version，快照落在 v2 上
        store.publish(ResourceType.AGENT, name, "itest", "首次发布");
        AgentEntity published = store.findAgent(name);
        assertEquals(GovernedRow.STATUS_PUBLISHED, published.getStatus());
        assertEquals(2, published.getPublishedVersion().intValue());
        assertEquals(2, published.getVersion().intValue(), "发布不是内容变更，不该推进 version");
        assertEquals(1, store.listVersions(ResourceType.AGENT, name).size());

        // 3) 再改一版：v3，并且把描述清空 —— 这一步专门验"先删后插"能不能真的清字段
        AgentDefinitionBundle clearDescription = newAgentBundle(name);
        clearDescription.getAgent().setDescription(null);
        assertTrue(store.saveAgent(clearDescription, "itest"));
        assertEquals(3, store.findAgent(name).getVersion().intValue());
        assertNull(store.findAgent(name).getDescription(),
                "描述被清空后库里必须也是 null —— updateById 的 NOT_NULL 策略会把它静默留下");

        // 4) 回滚到 v2：旧内容 → 新版本 v4，且线上仍是 v2
        store.rollback(ResourceType.AGENT, name, 2, "itest");
        AgentEntity rolled = store.findAgent(name);
        assertEquals("第二版描述", rolled.getDescription(), "回滚后内容应回到 v2 那份");
        assertEquals(4, rolled.getVersion().intValue(), "回滚生成新版本号，不覆盖历史");
        assertEquals(GovernedRow.STATUS_DRAFT, rolled.getStatus(), "回滚后是草稿，要再发布一次才生效");
        assertEquals(2, rolled.getPublishedVersion().intValue(), "线上仍是 v2");
        assertEquals(1, store.listVersions(ResourceType.AGENT, name).size(),
                "回滚本身不落快照（回滚不是发布），表里只有发布过的那一份");

        // 5) 子 agent 声明也随整体替换回来（v2 时它引用的是同一个子 agent）
        assertEquals(1, store.loadAgentBundle(name).getSubAgents().size());
    }

    /** 删除保护用的反向查询：四类资源的引用落点各不相同，逐个验。 */
    @Test
    void referencingAgentsAreFoundForEveryResourceKind() {
        String promptKey = PREFIX + "ref-prompt";
        String skillName = PREFIX + "ref-skill";
        String skillName2 = PREFIX + "ref-skill-2";
        String mcpName = PREFIX + "ref-mcp";
        String childName = PREFIX + "ref-child";
        String parentName = PREFIX + "ref-parent";

        assertTrue(store.createPrompt(newPrompt(promptKey), "itest"));
        assertTrue(store.createSkill(newSkillMeta(skillName2), "# itest2", Map.of(), "itest"));
        assertTrue(store.createSkill(newSkillMeta(skillName), "# itest\n正文", Map.of("a.md", "附件"), "itest"));
        assertTrue(store.createMcpServer(newMcp(mcpName), "itest"));
        assertTrue(store.createAgent(newAgentBundle(childName, "REACT"), "itest"), "子 agent 必须是 REACT 才能被引用");

        AgentDefinitionBundle parent = newAgentBundle(parentName);
        parent.getRef().setPromptKey(promptKey);
        parent.getRef().setSkillNames(List.of(skillName, skillName2));
        parent.getRef().setMcpNames(List.of(mcpName));
        // 本地子 agent：引用反查要在 md_agent_sub_agent.sub_name 上命中
        parent.getSubAgents().clear();
        parent.getSubAgents().add(localSubAgent(childName, 0));
        assertTrue(store.createAgent(parent, "itest"));

        assertEquals(List.of(parentName), store.findReferencingAgents(ResourceType.PROMPT, promptKey),
                "prompt 的引用落点是 md_agent_ref.prompt_key 这个单值列");
        assertEquals(List.of(parentName), store.findReferencingAgents(ResourceType.SKILL, skillName),
                "skill 的引用落点是 JSON 数组列，靠 JSON_CONTAINS 反查");
        assertEquals(List.of(parentName), store.findReferencingAgents(ResourceType.MCP, mcpName),
                "mcp 同上");
        assertEquals(List.of(parentName), store.findReferencingAgents(ResourceType.AGENT, childName),
                "agent 的引用落点是子 agent 表的 sub_name");

        assertEquals(List.of(), store.findReferencingAgents(ResourceType.PROMPT, PREFIX + "nobody"),
                "没人引用时返回空列表而不是 null");
    }

    /** 删除 agent 要连版本快照一起删掉，否则同名重建会看到上一世的版本历史。 */
    @Test
    void deletingAnAgentAlsoRemovesItsSnapshots() {
        String name = PREFIX + "delete-me";
        assertTrue(store.createAgent(newAgentBundle(name), "itest"));
        store.publish(ResourceType.AGENT, name, "itest", "发布一次，留下一份快照");
        assertEquals(1, store.listVersions(ResourceType.AGENT, name).size());

        store.deleteAgent(name);

        assertNull(store.findAgent(name), "主表行应被删除");
        assertNull(store.loadAgentBundle(name));
        assertEquals(0, store.listVersions(ResourceType.AGENT, name).size(), "版本快照应一并删除");
    }

    /** 引用完整性：悬空引用必须在事务内被拦住，并且一次把问题说全。 */
    @Test
    void validationRejectsDanglingReferences() {
        String name = PREFIX + "dangling";

        AgentDefinitionBundle missingPrompt = newAgentBundle(name);
        missingPrompt.getRef().setPromptKey(PREFIX + "no-such-prompt");
        ControlPlaneValidationException promptError = assertThrows(ControlPlaneValidationException.class,
                () -> store.createAgent(missingPrompt, "itest"));
        assertTrue(promptError.getMessage().contains("prompt 不存在"), promptError.getMessage());

        AgentDefinitionBundle missingSkill = newAgentBundle(name);
        missingSkill.getRef().setSkillNames(List.of(PREFIX + "no-such-skill"));
        assertThrows(ControlPlaneValidationException.class, () -> store.createAgent(missingSkill, "itest"));

        AgentDefinitionBundle missingMcp = newAgentBundle(name);
        missingMcp.getRef().setMcpNames(List.of(PREFIX + "no-such-mcp"));
        assertThrows(ControlPlaneValidationException.class, () -> store.createAgent(missingMcp, "itest"));

        AgentDefinitionBundle selfReference = newAgentBundle(name);
        selfReference.getSubAgents().get(0).setSubName(name);
        ControlPlaneValidationException selfError = assertThrows(ControlPlaneValidationException.class,
                () -> store.createAgent(selfReference, "itest"));
        assertTrue(selfError.getMessage().contains("自引用"), selfError.getMessage());

        // 一次多个问题：必须一次报全，页面上一轮就能改完
        AgentDefinitionBundle multi = newAgentBundle(name);
        multi.getRef().setPromptKey(PREFIX + "no-such-prompt");
        multi.getSubAgents().get(0).setSubName(PREFIX + "no-such-agent");
        // 默认子 agent 是远程的（远程不要求在本地存在），关掉它才验得到"子 agent 不存在"
        multi.getSubAgents().get(0).setRemote(Boolean.FALSE);
        multi.getSubAgents().get(0).setUrl(null);
        ControlPlaneValidationException multiError = assertThrows(ControlPlaneValidationException.class,
                () -> store.createAgent(multi, "itest"));
        assertEquals(2, multiError.getViolations().size(), "两个问题应各报一条: " + multiError.getViolations());

        assertNull(store.findAgent(name), "校验失败不能留下半条数据");
    }

    // ==================================================================================
    // 测试数据构造
    // ==================================================================================

    /** 一个字段尽量填满的 HARNESS agent，用来逼出"某个字段没落库 / 读不回来"的问题。 */
    private static AgentDefinitionBundle newAgentBundle(String name) {
        return newAgentBundle(name, "HARNESS");
    }

    /**
     * 指定 agent-type 的束。
     *
     * <p>存在的理由：{@code validateAgentContent} 要求「子 agent 必须是 REACT」，而上面那个默认造 HARNESS。
     * 需要拿这个 agent 当别人子 agent 的用例（子 agent 表往返、引用反查）必须显式建 REACT，
     * 否则会被校验直接拒掉 —— 那条规则本身是对的，不能让夹具去绕过它。
     */
    private static AgentDefinitionBundle newAgentBundle(String name, String agentType) {
        AgentEntity agent = new AgentEntity();
        agent.setAgentName(name);
        // display_name 是 NOT NULL 且必填（validateAgentContent 会拦空值），夹具必须给上
        agent.setDisplayName("集成测试编排 Agent");
        agent.setAgentType(agentType);
        agent.setDescription("集成测试 agent");
        agent.setBizTags(List.of("itest", "观测"));
        agent.setCategory("itest");
        agent.setEnabled(Boolean.TRUE);

        AgentRefEntity ref = new AgentRefEntity();
        ref.setAgentName(name);
        ref.setTools(List.of("git_show_commit"));
        ref.setMiddleware(List.of("AgentRunTraceMiddleware"));
        ref.setSkillNames(List.of());
        ref.setMcpNames(List.of());
        ref.setKnowledges(List.of(PREFIX + "knowledge"));

        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentName(name);
        runtime.setModelRef("openAIChatModel");
        runtime.setFallbackModelRef(null);
        runtime.setReasoningEffort("low");
        runtime.setMaxIters(12);
        runtime.setMaxRetries(2);
        runtime.setCheckRunning(Boolean.TRUE);
        // 三态：null 表示"未设置，用框架默认"，必须原样回来（不能用基本类型）
        runtime.setEnableTaskList(null);
        runtime.setGenerateOptions(Map.of("temperature", "0.3", "maxTokens", 2048));

        AgentMemoryEntity memory = new AgentMemoryEntity();
        memory.setAgentName(name);
        memory.setCompactionDisabled(false);
        memory.setTriggerMessages(40);
        memory.setKeepTokensRatio(new BigDecimal("0.2"));
        memory.setSummaryPrompt("摘要提示词");
        memory.setPrune(Map.of("path", "itest/compact/", "keepLast", 5));
        memory.setEvictExcludedTools(List.of("git_show_commit"));

        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setAgentName(name);
        workspace.setHarnessAgentId(name + "-id");
        workspace.setWorkspacePath("itest/ws");
        workspace.setEnvironment("itest");
        workspace.setFilesystem(Map.of("readOnly", List.of("*.md", "AGENTS.md")));
        workspace.setSkillsEnabled(Boolean.TRUE);
        workspace.setDisableToolsConfig(null);

        // 默认带一个「远程」子 agent：远程不要求在本平台注册表里存在，
        // 所以这个构造器放进任何用例都不会被引用校验拦下。
        AgentSubAgentEntity sub = remoteSubAgent(PREFIX + "sub-placeholder", 0);

        AgentDefinitionBundle bundle = new AgentDefinitionBundle();
        bundle.setAgent(agent);
        bundle.setRef(ref);
        bundle.setRuntime(runtime);
        bundle.setMemory(memory);
        bundle.setWorkspace(workspace);
        bundle.setSubAgents(new java.util.ArrayList<>(List.of(sub)));
        return bundle;
    }

    /** 本地子 agent：必须在 md_agent 里真实存在，否则引用校验会拒绝（这是设计上的硬约束）。 */
    private static AgentSubAgentEntity localSubAgent(String name, int sortOrder) {
        AgentSubAgentEntity sub = new AgentSubAgentEntity();
        sub.setSubName(name);
        sub.setSubDescription("本地子 agent");
        sub.setSortOrder(sortOrder);
        sub.setHidden(Boolean.FALSE);
        sub.setHeaders(Map.of("X-Tenant", "itest"));
        sub.setTools(List.of("git_show_commit"));
        return sub;
    }

    /** 远程子 agent：不在本平台注册表里，只要给得出端点，引用校验就放行。 */
    private static AgentSubAgentEntity remoteSubAgent(String name, int sortOrder) {
        AgentSubAgentEntity sub = new AgentSubAgentEntity();
        sub.setSubName(name);
        sub.setSubDescription("远程子 agent");
        sub.setSortOrder(sortOrder);
        sub.setRemote(Boolean.TRUE);
        sub.setUrl("https://itest.example.com/a2a");
        sub.setRemoteStreaming(Boolean.TRUE);
        sub.setRemoteContextAttributes(Map.of("ask", "always"));
        return sub;
    }

    private static PromptEntity newPrompt(String promptKey) {
        PromptEntity prompt = new PromptEntity();
        prompt.setPromptKey(promptKey);
        prompt.setDisplayName("集成测试提示词");
        prompt.setDescription("集成测试提示词");
        prompt.setBizTags(List.of("itest"));
        prompt.setBody("你是集成测试用的助手。");
        return prompt;
    }

    private static McpServerEntity newMcp(String serverName) {
        McpServerEntity server = new McpServerEntity();
        server.setServerName(serverName);
        server.setDisplayName("集成测试 MCP");
        server.setDescription("集成测试 MCP");
        server.setBizTags(List.of("itest"));
        server.setTransport("streamable");
        server.setUrl("https://itest.example.com/mcp");
        server.setHeaders(Map.of("Authorization", "Bearer itest"));
        server.setQueryParams(Map.of("tenant", "itest"));
        server.setEnableTools(List.of("doc_read"));
        server.setDisableTools(List.of("doc_delete"));
        server.setTimeoutMs(30_000L);
        return server;
    }

    private static SkillMetaEntity newSkillMeta(String skillName) {
        SkillMetaEntity meta = new SkillMetaEntity();
        meta.setSkillName(skillName);
        meta.setDisplayName("集成测试技能");
        meta.setDescription("集成测试用的技能描述");
        meta.setBizTags(List.of("itest"));
        return meta;
    }

    private static ModelEntity newModel(String modelKey, String baseUrl, boolean isDefault) {
        ModelEntity model = new ModelEntity();
        model.setModelKey(modelKey);
        model.setDisplayName("集成测试模型");
        model.setPurpose("对话");
        model.setProvider("deepseek");
        model.setBaseUrl(baseUrl);
        model.setApiKey("sk-itest-not-a-real-key");
        model.setModelName("itest-model");
        model.setEndpointPath("/v1/chat/completions");
        model.setFormatter("deepseek-reasoning");
        model.setReasoningEffort("low");
        // 三态：null 表示「不下发」。故意留空两个，用来证明包装类型不会被写成 0。
        model.setTemperature(null);
        model.setTopP(null);
        model.setMaxTokens(2048);
        model.setContextWindowSize(65536);
        model.setDescription("集成测试模型");
        model.setBizTags(List.of("itest", "对话"));
        model.setIsDefault(isDefault);
        model.setEnabled(Boolean.TRUE);
        return model;
    }

    // ==================================================================================
    // prompt / mcp / skill
    // ==================================================================================

    @Test
    void promptPublishVersionsAndRollback() {
        String key = PREFIX + "prompt";
        assertTrue(store.createPrompt(newPrompt(key), "itest"));
        assertFalse(store.createPrompt(newPrompt(key), "itest"), "同名 prompt 新建必须返回 false");

        store.publish(ResourceType.PROMPT, key, "itest", "v1 上线");
        assertEquals(GovernedRow.STATUS_PUBLISHED, store.findPrompt(key).getStatus());
        assertEquals(1, store.listVersions(ResourceType.PROMPT, key).size());

        // 连点两次发布：version 没变，第二次会撞唯一键 uk_type_name_ver —— 这里验证它是幂等的
        store.publish(ResourceType.PROMPT, key, "itest", "又点了一次发布");
        assertEquals(1, store.listVersions(ResourceType.PROMPT, key).size(), "同版本重复发布不该新增快照");

        PromptEntity second = newPrompt(key);
        second.setBody("第二版正文");
        assertTrue(store.savePrompt(second, "itest"));
        store.publish(ResourceType.PROMPT, key, "itest", "v2 上线");
        assertEquals(2, store.listVersions(ResourceType.PROMPT, key).size());

        store.rollback(ResourceType.PROMPT, key, 1, "itest");
        PromptEntity rolled = store.findPrompt(key);
        assertEquals("你是集成测试用的助手。", rolled.getBody(), "回滚要拿回 v1 的正文");
        assertEquals(3, rolled.getVersion().intValue(), "回滚生成新版本号，不覆盖历史");
        assertEquals(GovernedRow.STATUS_DRAFT, rolled.getStatus());
        assertEquals(2, rolled.getPublishedVersion().intValue(), "线上仍是 v2，回滚只准备草稿");

        store.deletePrompt(key);
        assertNull(store.findPrompt(key));
        assertEquals(0, store.listVersions(ResourceType.PROMPT, key).size(), "删资源要连快照一起删");
    }

    @Test
    void mcpServerValidationAndRoundTrip() {
        String name = PREFIX + "mcp";
        assertTrue(store.createMcpServer(newMcp(name), "itest"));
        assertFalse(store.createMcpServer(newMcp(name), "itest"));

        McpServerEntity loaded = store.findMcpServer(name);
        assertEquals("streamable", loaded.getTransport());
        assertEquals(Map.of("Authorization", "Bearer itest"), loaded.getHeaders(), "headers 是 JSON 列");
        assertEquals(Map.of("tenant", "itest"), loaded.getQueryParams(), "query_params 是 JSON 列");
        assertEquals(List.of("doc_read"), loaded.getEnableTools());
        assertEquals(30_000L, loaded.getTimeoutMs().longValue());
        assertTrue(loaded.getEnabled(), "enabled 没给时缺省为 true");

        // transport=streamable 却没有 url：必须拦住，否则装配期会去连一个空地址
        McpServerEntity noUrl = newMcp(PREFIX + "mcp-no-url");
        noUrl.setUrl(null);
        assertThrows(ControlPlaneValidationException.class, () -> store.createMcpServer(noUrl, "itest"));

        McpServerEntity badTransport = newMcp(PREFIX + "mcp-bad");
        badTransport.setTransport("websocket");
        assertThrows(ControlPlaneValidationException.class, () -> store.createMcpServer(badTransport, "itest"));

        // 停用的 MCP 不能被新引用：装配期会跳过它并告警，那种"以为挂上了其实没挂"必须提前拦
        McpServerEntity disabled = newMcp(PREFIX + "mcp-disabled");
        disabled.setEnabled(Boolean.FALSE);
        assertTrue(store.createMcpServer(disabled, "itest"));
        AgentDefinitionBundle withDisabled = newAgentBundle(PREFIX + "mcp-user");
        withDisabled.getRef().setMcpNames(List.of(PREFIX + "mcp-disabled"));
        ControlPlaneValidationException error = assertThrows(ControlPlaneValidationException.class,
                () -> store.createAgent(withDisabled, "itest"));
        assertTrue(error.getMessage().contains("已停用"), error.getMessage());

        // 保存草稿能真的清空可空字段（先删后插，而不是 updateById）
        McpServerEntity cleared = newMcp(name);
        cleared.setDescription(null);
        assertTrue(store.saveMcpServer(cleared, "itest"));
        assertNull(store.findMcpServer(name).getDescription());
        assertEquals(2, store.findMcpServer(name).getVersion().intValue());
    }

    @Test
    void skillContentLivesInTheOfficialSchema() {
        String name = PREFIX + "skill";
        assertTrue(store.createSkill(newSkillMeta(name), "# 第一版正文", Map.of("scripts/run.sh", "echo hi"),
                "itest"));
        assertFalse(store.createSkill(newSkillMeta(name), "# 又来一次", Map.of(), "itest"));

        AgentSkill content = store.findSkillContent(name);
        assertNotNull(content, "内容应落在官方 agentscope 库");
        assertEquals("集成测试用的技能描述", content.getDescription());
        assertEquals("# 第一版正文", content.getSkillContent());
        assertEquals("echo hi", content.getResource("scripts/run.sh"), "附属文件要一起往返");
        assertTrue(store.listSkillContentNames().contains(name));

        // 保存草稿：内容与治理元数据必须同步推进
        SkillMetaEntity edited = newSkillMeta(name);
        edited.setDescription("改过的描述");
        assertTrue(store.saveSkill(edited, "# 第二版正文", Map.of(), "itest"));
        assertEquals("# 第二版正文", store.findSkillContent(name).getSkillContent());
        assertEquals("改过的描述", store.findSkillContent(name).getDescription());
        assertTrue(store.findSkillContent(name).getResourcePaths().isEmpty(), "旧的附属文件应被覆盖掉");
        assertEquals(2, store.findSkillMeta(name).getVersion().intValue());

        store.publish(ResourceType.SKILL, name, "itest", "技能上线");
        assertEquals(1, store.listVersions(ResourceType.SKILL, name).size());
        // 发出去的是 v2，所以快照落在 v2 上；再改一版，才能证明回滚真的把内容换回了旧的那份
        SkillMetaEntity third = newSkillMeta(name);
        third.setDescription("第三版描述");
        assertTrue(store.saveSkill(third, "# 第三版正文", Map.of(), "itest"));
        assertEquals(3, store.findSkillMeta(name).getVersion().intValue());

        store.rollback(ResourceType.SKILL, name, 2, "itest");
        assertEquals("# 第二版正文", store.findSkillContent(name).getSkillContent(),
                "skill 回滚要连官方库里的内容一起回");
        assertEquals("改过的描述", store.findSkillContent(name).getDescription());
        assertEquals(4, store.findSkillMeta(name).getVersion().intValue());
        assertEquals(GovernedRow.STATUS_DRAFT, store.findSkillMeta(name).getStatus());

        store.deleteSkill(name);
        assertNull(store.findSkillMeta(name));
        assertNull(store.findSkillContent(name), "内容也要从官方库删掉");
    }

    /** 描述与正文是官方表的 NOT NULL 列：缺了要在这里就说人话，而不是抛一句 SQL 异常给用户。 */
    @Test
    void skillValidationSpeaksBeforeMysqlDoes() {
        SkillMetaEntity noDescription = newSkillMeta(PREFIX + "skill-nodesc");
        noDescription.setDescription(null);
        ControlPlaneValidationException error = assertThrows(ControlPlaneValidationException.class,
                () -> store.createSkill(noDescription, "# 正文", Map.of(), "itest"));
        assertTrue(error.getMessage().contains("描述"), error.getMessage());

        SkillMetaEntity noBody = newSkillMeta(PREFIX + "skill-nobody");
        assertThrows(ControlPlaneValidationException.class,
                () -> store.createSkill(noBody, "   ", Map.of(), "itest"));
    }

    // ==================================================================================
    // 模型（md_model）
    // ==================================================================================

    /**
     * 模型行的往返 + 发布 / 回滚 + 默认标记的互斥与「回滚不动默认标记」。
     *
     * <p>这里守的是几条只有真库能验的事：
     * <ol>
     *   <li>{@code biz_tags} 是 JSON 列（漏了 {@code autoResultMap = true} 时写入正常、读出来恒为 null）；</li>
     *   <li>生成参数的三态：{@code null} 表示「不下发」，必须原样回来。用基本类型接会变成 0，
     *       而 0 与「没配」在采样参数上是两个语义（temperature=0 是一个真实存在的策略）；</li>
     *   <li>{@code is_default} 的全表互斥：新默认值上岗时旧的那个必须自动摘掉。
     *       这条由应用层在事务内保证，数据库没有「部分唯一索引」能兜底；</li>
     *   <li>回滚<b>取的是当前默认标记、不是快照里的那份</b>：默认标记是运维开关，不是模型内容
     *       的一部分，回滚一个端点不该顺手改掉「谁是兜底」。</li>
     * </ol>
     */
    @Test
    void modelRowRoundTripPublishRollbackAndDefaultFlag() throws Exception {
        String first = PREFIX + "model-a";
        String second = PREFIX + "model-b";

        // 这个用例要验的正是 is_default 的互斥，而互斥的副作用是「把开发库里原本的默认模型清掉」。
        // 所以先把原本的默认模型记下来，结束时恢复 —— 否则跑一次测试就会让开发库失去兜底模型，
        // 所有没声明 model_ref 的 agent 会在下次装配时悄悄换掉连接。
        List<String> preexistingDefaults = captureDefaultModels();
        try {
            // ---------- 新建与往返 ----------
            assertTrue(store.createModel(newModel(first, "https://itest.example.com/endpoint-a", false), "itest"));
            assertFalse(store.createModel(newModel(first, "https://itest.example.com/endpoint-a", false), "itest"),
                    "重名新建必须返回 false");

            ModelEntity loaded = store.findModel(first);
            assertNotNull(loaded);
            assertEquals("集成测试模型", loaded.getDisplayName());
            assertEquals(List.of("itest", "对话"), loaded.getBizTags(), "biz_tags 是 JSON 列");
            assertEquals(GovernedRow.STATUS_DRAFT, loaded.getStatus());
            assertEquals(1, loaded.getVersion().intValue());
            assertNull(loaded.getPublishedVersion(), "新建后还没发布过");
            assertNull(loaded.getTemperature(), "没配的温度必须原样回来，不能变成 0");
            assertNull(loaded.getTopP());
            assertEquals(2048, loaded.getMaxTokens().intValue());
            assertEquals("deepseek-reasoning", loaded.getFormatter(),
                    "formatter 是装配层要用的字段，丢了会让 DeepSeek 思考模式直接 400");
            assertFalse(loaded.getIsDefault());

            // ---------- 发布：快照落在 v1，而这一版的 is_default 是 false ----------
            store.publish(ResourceType.MODEL, first, "itest", "模型首版上线");
            assertEquals(GovernedRow.STATUS_PUBLISHED, store.findModel(first).getStatus());
            assertEquals(1, store.findModel(first).getPublishedVersion().intValue());
            assertEquals(1, store.listVersions(ResourceType.MODEL, first).size());

            // ---------- 默认标记互斥：second 先当默认，再让 first 把它顶掉 ----------
            assertTrue(store.createModel(newModel(second, "https://itest.example.com/endpoint-c", true), "itest"));
            assertTrue(store.findModel(second).getIsDefault());
            // v2 草稿同时动两个字段：端点换一个、温度从 null（v1 快照里的值）改成 0.7。
            // 两个都动，回滚时才能同时验出「端点回到快照」+「null 也真的写回去，不是沿用草稿值」。
            ModelEntity defaultDraft = newModel(first, "https://itest.example.com/endpoint-b", true);
            defaultDraft.setTemperature(0.7);
            assertTrue(store.saveModel(defaultDraft, "itest"), "把 first 设为默认");
            assertTrue(store.findModel(first).getIsDefault());
            assertFalse(store.findModel(second).getIsDefault(), "旧默认标记必须被清掉");
            assertEquals(1, store.listModels().stream()
                            .filter(row -> Boolean.TRUE.equals(row.getIsDefault())).count(),
                    "全表最多只能有一个默认模型");

            // 改完还没发布：线上仍是 v1，草稿已经是 v2（端点和温度都动了）
            ModelEntity draft = store.findModel(first);
            assertEquals(GovernedRow.STATUS_DRAFT, draft.getStatus());
            assertEquals(2, draft.getVersion().intValue());
            assertEquals("https://itest.example.com/endpoint-b", draft.getBaseUrl());
            assertEquals(0, draft.getTemperature().compareTo(0.7));

            // ---------- 回滚：内容回到 v1，但默认标记必须留在「当前」这一份上 ----------
            store.rollback(ResourceType.MODEL, first, 1, "itest");
            ModelEntity rolled = store.findModel(first);
            assertEquals("https://itest.example.com/endpoint-a", rolled.getBaseUrl(),
                    "回滚要把端点换回快照里那份");
            assertNull(rolled.getTemperature(), "快照里的 null 要真的写回去（不是沿用草稿的 0.7）");
            assertTrue(rolled.getIsDefault(), "默认标记是运维开关：回滚内容不该顺手把它关掉");
            assertEquals(GovernedRow.STATUS_DRAFT, rolled.getStatus(), "回滚只写草稿，不热装配");
            assertEquals(3, rolled.getVersion().intValue(), "回滚也是一次内容变更，版本要往前走");

            // ---------- 删除：行与快照一起走 ----------
            store.deleteModel(first);
            assertNull(store.findModel(first));
            assertTrue(store.listVersions(ResourceType.MODEL, first).isEmpty(), "快照要一起删掉");
        } finally {
            restoreDefaultModels(preexistingDefaults);
        }
    }

    // ==================================================================================
    // 渠道
    // ==================================================================================

    /**
     * 渠道整束往返 + 发布 / 回滚 / 反查。
     *
     * <p>这里守的是三件只有真库能验的事：
     * <ol>
     *   <li>{@code properties} 与 {@code roles} 两个 JSON 列能不能读回来
     *       （漏了 {@code autoResultMap = true} 时写入正常、读出来恒为 null）；</li>
     *   <li>绑定是否按 {@code sort_order} 保序返回 —— 顺序决定同一层里哪条规则先命中，
     *       不能交给数据库的物理顺序；</li>
     *   <li><b>回滚是否把绑定一起还原</b>。这是最容易漏的一条：只回滚渠道行、留下新的绑定，
     *       页面上看起来"回滚成功了"，实际生效的路由规则还是新那套。</li>
     * </ol>
     */
    @Test
    void channelBundleRoundTripPublishAndRollbackRestoresBindings() {
        String agent = PREFIX + "chan-agent";
        assertTrue(store.createAgent(newAgentBundle(agent), "itest"), "绑定校验要求被绑定的 agent 先存在");

        String channelId = PREFIX + "wecom-main";
        // 故意乱序传入（先 sort_order=1、再 0），这样"读回来是排好序的"才是真的断言
        List<ChannelBindingEntity> bindings = new ArrayList<>();
        bindings.add(newBinding(agent, "group:c_ops", 1));
        ChannelBindingEntity direct = newBinding(agent, "direct:u_42", 0);
        direct.setRoles(List.of("ops", "oncall"));
        direct.setSessionScope("PER_PEER");
        bindings.add(direct);
        ChannelDefinitionBundle bundle = new ChannelDefinitionBundle(newChannel(channelId, agent), bindings);

        assertTrue(store.createChannel(bundle, "itest"));
        assertFalse(store.createChannel(newChannelBundle(channelId, agent), "itest"), "重名新建必须返回 false");

        ChannelDefinitionBundle loaded = store.loadChannelBundle(channelId);
        assertNotNull(loaded);
        assertEquals("wecom", loaded.channel().getChannelType());
        assertEquals(Map.of("botId", "bot-1", "secret", "s3cret", "retry", 3),
                loaded.channel().getProperties(), "properties 是 JSON 列，数字也不能退化成字符串");
        assertEquals("PER_PEER", loaded.channel().getDmScope());
        assertEquals(GovernedRow.STATUS_DRAFT, loaded.channel().getStatus());
        assertEquals(1, loaded.channel().getVersion().intValue());
        assertNull(loaded.channel().getPublishedVersion(), "新建后还没发布过");

        assertEquals(2, loaded.bindings().size());
        assertEquals("direct:u_42", loaded.bindings().get(0).getPeer(), "顺序即 sort_order，小的在前");
        assertEquals("group:c_ops", loaded.bindings().get(1).getPeer());
        assertEquals(List.of("ops", "oncall"), loaded.bindings().get(0).getRoles(), "roles 是 JSON 列");
        assertEquals("PER_PEER", loaded.bindings().get(0).getSessionScope());

        // 反向引用：删除 agent 前的保护依据
        assertEquals(List.of(channelId), store.findReferencingChannels(agent));
        // 渠道不反向引用 agent，这条必须是空而不是把渠道名塞进去
        assertTrue(store.findReferencingAgents(ResourceType.CHANNEL, channelId).isEmpty());

        // 发布：落一份含绑定集合的快照
        store.publish(ResourceType.CHANNEL, channelId, "itest", "渠道上线");
        assertEquals(GovernedRow.STATUS_PUBLISHED, store.findChannel(channelId).getStatus());
        assertEquals(1, store.findChannel(channelId).getPublishedVersion().intValue());
        assertEquals(1, store.listVersions(ResourceType.CHANNEL, channelId).size());

        // 改一版：凭据换掉、绑定收敛成一条
        ChannelDefinitionBundle edited = newChannelBundle(channelId, agent);
        edited.channel().setProperties(Map.of("botId", "bot-2", "secret", "n3w"));
        assertTrue(store.saveChannel(edited, "itest"));
        assertEquals(2, store.findChannel(channelId).getVersion().intValue());
        assertEquals(1, store.loadChannelBundle(channelId).bindings().size());

        // 回滚到 v1：渠道行与绑定都要回到发布时那一份
        store.rollback(ResourceType.CHANNEL, channelId, 1, "itest");
        ChannelDefinitionBundle rolled = store.loadChannelBundle(channelId);
        assertEquals(Map.of("botId", "bot-1", "secret", "s3cret", "retry", 3),
                rolled.channel().getProperties(), "凭据要回到旧那份");
        assertEquals(2, rolled.bindings().size(), "绑定必须一起回滚，否则线上路由规则还是新那套");
        assertEquals("direct:u_42", rolled.bindings().get(0).getPeer());
        assertEquals(3, rolled.channel().getVersion().intValue(), "回滚是新增一个版本，不覆盖历史");
        assertEquals(GovernedRow.STATUS_DRAFT, rolled.channel().getStatus());
        assertEquals(1, rolled.channel().getPublishedVersion().intValue(), "线上仍是发出去的那一版");

        // 校验：默认 agent 不存在时必须拦在写库之前，且要说人话
        ChannelDefinitionBundle dangling = newChannelBundle(PREFIX + "chan-dangling", agent);
        dangling.channel().setDefaultAgentId(PREFIX + "no-such-agent");
        ControlPlaneValidationException error = assertThrows(ControlPlaneValidationException.class,
                () -> store.createChannel(dangling, "itest"));
        assertTrue(error.getMessage().contains("不存在"), error.getMessage());

        // 校验：一条没有任何匹配条件的绑定是死规则，也要拦
        ChannelDefinitionBundle deadRule = new ChannelDefinitionBundle(newChannel(PREFIX + "chan-dead", agent),
                new ArrayList<>(List.of(newBinding(agent, null, 0))));
        ControlPlaneValidationException deadError = assertThrows(ControlPlaneValidationException.class,
                () -> store.createChannel(deadRule, "itest"));
        assertTrue(deadError.getMessage().contains("永远不会命中"), deadError.getMessage());

        // 删除：渠道连同绑定与快照一起清掉
        store.deleteChannel(channelId);
        assertNull(store.findChannel(channelId));
        assertTrue(store.findReferencingChannels(agent).isEmpty(), "绑定随渠道一起删");
        assertEquals(0, store.listVersions(ResourceType.CHANNEL, channelId).size());
    }

    /** 一个最小可用的企微渠道行；凭据里带一个数字，用来证明 JSON 列没有把类型退化成字符串。 */
    private static ChannelEntity newChannel(String channelId, String agentName) {
        ChannelEntity channel = new ChannelEntity();
        channel.setChannelId(channelId);
        channel.setChannelType("wecom");
        channel.setDisplayName("集成测试渠道");
        channel.setDescription("只在 IT 里出现，用 PREFIX 清理");
        channel.setOwnerId("itest");
        channel.setDefaultAgentId(agentName);
        channel.setDmScope("PER_PEER");
        channel.setReplyStyle("WECOM_THINK");
        channel.setProperties(Map.of("botId", "bot-1", "secret", "s3cret", "retry", 3));
        channel.setEnabled(Boolean.TRUE);
        return channel;
    }

    /** 一条绑定；peer 传 null 用来构造"没有任何匹配条件"的死规则。 */
    private static ChannelBindingEntity newBinding(String agentName, String peer, int sortOrder) {
        ChannelBindingEntity binding = new ChannelBindingEntity();
        binding.setAgentId(agentName);
        binding.setPeer(peer);
        binding.setSortOrder(sortOrder);
        return binding;
    }

    /** 渠道 + 单条绑定；返回的绑定列表是可变集合，调用方可以继续追加。 */
    private static ChannelDefinitionBundle newChannelBundle(String channelId, String agentName) {
        return new ChannelDefinitionBundle(newChannel(channelId, agentName),
                new ArrayList<>(List.of(newBinding(agentName, "group:c_ops", 0))));
    }

    // ==================================================================================
    // 辅助
    // ==================================================================================

    /**
     * 记下库里原本的默认模型（model_key 列表）。
     *
     * <p>模型的既有用例只碰 {@code itest-cp-} 前缀的行，唯独 {@code is_default} 不一样：
     * 它的互斥语义是「新默认值上岗 → 全表其它默认标记被清掉」，也就是说这个用例必然动到
     * <b>开发库里那行真实数据</b>。不先留一份快照，跑一次测试就会让开发库失去兜底模型。
     */
    private static List<String> captureDefaultModels() throws Exception {
        List<String> keys = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT model_key FROM md_model WHERE is_default = 1")) {
            while (rows.next()) {
                keys.add(rows.getString(1));
            }
        }
        return keys;
    }

    /** 把默认标记还原成 {@link #captureDefaultModels()} 那一刻的样子。 */
    private static void restoreDefaultModels(List<String> keys) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE md_model SET is_default = 0");
            for (String key : keys) {
                statement.execute("UPDATE md_model SET is_default = 1 WHERE model_key = '"
                        + key.replace("'", "''") + "'");
            }
        }
    }

    /**
     * 建表：直接执行 md-agent-bootstrap 里那两份 DDL，保证"测试用的表"与"生产用的表"是同一份定义。
     *
     * <p>两份是渠道/网关这轮改造后的结果：{@code control-plane-schema.sql} 管元数据，
     * {@code channel-schema.sql} 管渠道与网关。分开是因为它们由不同时期的上线动作执行，
     * 合成一份会逼着"只想加一张渠道表"的人去重跑整份元数据脚本。
     *
     * <p><b>先逐行去注释、再按分号切分</b>，顺序不能反：本脚本的注释里出现了
     * {@code CREATE TABLE} 这样的字样，也有说明性的中文长句，
     * 先切分的话注释会被切断，残留的半句话会被当成 SQL 执行（trace 的 IT 用的是相反顺序，
     * 那份脚本恰好没有这个问题）。
     */
    private static void applySchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String script : List.of("control-plane-schema.sql", "channel-schema.sql", "workflow-schema.sql")) {
                Path schema = Path.of("..", "md-agent-bootstrap", "src", "main", "resources", "db",
                        script).toAbsolutePath().normalize();
                assertTrue(Files.exists(schema), "找不到建表脚本，期望路径: " + schema);
                StringBuilder withoutComments = new StringBuilder();
                for (String line : Files.readAllLines(schema, StandardCharsets.UTF_8)) {
                    if (!line.trim().startsWith("--")) {
                        withoutComments.append(line).append('\n');
                    }
                }
                for (String raw : withoutComments.toString().split(";")) {
                    if (!raw.isBlank()) {
                        statement.execute(raw.trim());
                    }
                }
            }
        }
    }

    /**
     * 手工搭一个 MyBatis-Plus 的 SqlSessionFactory。
     *
     * <p>不启动 Spring 容器：本测试要验的是存储层本身，启动整个应用会连带拉起
     * Nacos / Redis / xxl-job，既慢又会引入无关的失败源。
     */
    private static SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentMapper.class);
        configuration.addMapper(AgentRefMapper.class);
        configuration.addMapper(AgentRuntimeMapper.class);
        configuration.addMapper(AgentMemoryMapper.class);
        configuration.addMapper(AgentWorkspaceMapper.class);
        configuration.addMapper(AgentSubAgentMapper.class);
        configuration.addMapper(PromptMapper.class);
        configuration.addMapper(McpServerMapper.class);
        configuration.addMapper(ModelMapper.class);
        configuration.addMapper(SkillMetaMapper.class);
        configuration.addMapper(ResourceVersionMapper.class);
        configuration.addMapper(ChannelMapper.class);
        configuration.addMapper(ChannelBindingMapper.class);
        configuration.addMapper(WorkflowMapper.class);
        configuration.addMapper(WorkflowNodeRefMapper.class);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    /** 清理：只删 {@link #PREFIX} 开头的行，绝不碰开发库里的真实数据。 */
    private static void cleanUp() throws Exception {
        // 官方 skill 内容在另一个 schema，只能用官方仓库的 API 删（它还负责删附属文件）。
        for (String skillName : store.listSkillContentNames()) {
            if (skillName.startsWith(PREFIX)) {
                store.deleteSkill(skillName);
            }
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 子 agent 表要按两列删：sub_name 也可能是本前缀（父 agent 在测试外的情况不存在，
            // 但远程子 agent 的 sub_name 同样带前缀，漏删会留下孤儿行）。
            statement.execute("DELETE FROM md_agent_sub_agent WHERE agent_name LIKE '" + PREFIX
                    + "%' OR sub_name LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM md_agent_workspace WHERE agent_name LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM md_agent_memory WHERE agent_name LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM md_agent_runtime WHERE agent_name LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM md_agent_ref WHERE agent_name LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM md_agent WHERE agent_name LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM md_prompt WHERE prompt_key LIKE '" + PREFIX + "%'");
        statement.execute("DELETE FROM md_mcp_server WHERE server_name LIKE '" + PREFIX + "%'");
        statement.execute("DELETE FROM md_model WHERE model_key LIKE '" + PREFIX + "%'");
        statement.execute("DELETE FROM md_skill_meta WHERE skill_name LIKE '" + PREFIX + "%'");
            // 绑定先删：它没有外键约束，但先删子行读起来更符合直觉，也不会在中间态留下悬空绑定。
            statement.execute("DELETE FROM md_channel_binding WHERE channel_id LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM md_channel WHERE channel_id LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM md_resource_version WHERE resource_name LIKE '" + PREFIX + "%'");
        }
    }
}
