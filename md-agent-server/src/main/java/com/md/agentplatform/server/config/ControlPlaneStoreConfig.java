package com.md.agentplatform.server.config;

import com.md.agentplatform.server.controlplane.store.ControlPlaneStore;
import com.md.agentplatform.server.controlplane.store.MyBatisControlPlaneStore;
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
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * 控制面元数据存储（{@code md_agent} / {@code md_prompt} / {@code md_mcp_server} / {@code md_skill_meta}）
 * 的装配。
 *
 * <h2>一、为什么手工 {@code @Bean} 而不在这些类上标 {@code @Component}</h2>
 * <ol>
 *   <li><b>skill 仓库必须由官方 Builder 造</b>：它不是自己能 new 出来的普通组件
 *       （{@code MysqlSkillRepository.builder(dataSource)…build()}），
 *       而"用哪个库、要不要自动建表、是否可写"这三个决定必须写在一个地方被人看见 ——
 *       散在某个类里做 static 初始化，出问题时没人知道它连的是哪个 schema；</li>
 *   <li><b>依赖集合要一眼可见</b>：Store 依赖 12 个 Mapper + 1 个 skill 仓库，
 *       全部摆在方法签名上，比一堆 {@code @Autowired} 字段更能说明它到底碰了哪些表；</li>
 *   <li><b>启动顺序</b>：两者必须在 {@code AgentManager} 初始化前就绪
 *       （装配期要按名字解析 prompt / skill / mcp 引用），显式的 Bean 方法把这条依赖写进了容器图。</li>
 * </ol>
 *
 * <h2>二、这里为什么没有开关（与 {@code TraceStoreConfig} 不同）</h2>
 * 链路归档关掉是有意义的（纯内存也能跑）。控制面存储关不掉：
 * 它是元数据的<b>唯一</b>来源，没有它一个 agent 都装配不出来。
 * 与其给一个"关掉就起不来"的开关，不如让它在启动阶段就大声失败。
 *
 * <h2>三、skill 内容为什么会写到另一个 schema</h2>
 * 官方 {@link AgentSkillRepository} 对库名做标识符白名单校验（只允许字母数字下划线），
 * 而本项目业务库名 {@code md-agent-platform} 带连字符，会被它直接拒绝（设计文档 §4.3.1）。
 * 所以 skill 内容落在独立库（默认 {@code agentscope}），库名由
 * {@code agent-platform.control-plane-skill-database} 配置。
 */
@Configuration
public class ControlPlaneStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneStoreConfig.class);

    /**
     * 库名白名单。这个值最终来自配置，而建库语句会把库名拼进 SQL 的标识符位置（反引号包裹），
     * 反引号本身能被人为闭合 —— 一条 {@code a`; DROP ...} 的配置就是一条注入路径。
     * 校验放在装配期，让非法值在启动时立刻暴露，而不是等某个 agent 首次跑工作区 IO 才炸。
     */
    private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * 官方 skill 内容仓库（内容表在<b>另一个 schema</b>）。
     *
     * <p>{@code createIfNotExist(true)}：首次构造时它自己 CREATE DATABASE + CREATE TABLE，
     * 所以新环境不需要额外执行 DDL；已存在的库/表则原样使用。
     *
     * <p>{@code writeable(true)}：控制面要能新建/覆盖 skill。官方实现在只读时
     * {@code save()} 会直接返回 false 并打一条 warn，那种"保存没反应"的问题很难查，
     * 这里明确写成可写。
     *
     * <p>容器关停时会调用它的 {@code close()}（{@code AutoCloseable} 由 Spring 自动推断销毁方法）。
     */
    @Bean
    public AgentSkillRepository controlPlaneSkillRepository(DataSource dataSource,
                                                            AgentPlatformProperties properties) {
        String database = properties.getControlPlaneSkillDatabase();
        AgentSkillRepository repository = MysqlSkillRepository.builder(dataSource)
                .databaseName(database)
                .createIfNotExist(true)
                .writeable(true)
                .build();
        // 启动日志必须带上库名：这是排查"skill 保存了但查不到"的第一现场
        // （最常见的原因是库名被配成业务库，要么被官方校验拒绝，要么写到了别处）。
        // location 由框架拼成 "库名/内容表名"，正好够定位；不要只打一句"仓库已就绪"。
        AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
        log.info("控制面 skill 内容仓库已就绪：type={} location={} 可写={}", info.getType(),
                info.getLocation(), info.isWritable());
        return repository;
    }

    /**
     * 工作区 KV 库的连接池。
     *
     * <h3>为什么单独建库</h3>
     * 与 state 同样的理由：工作区 KV 是「行多但单体小」（随用户数 × 会话数 × 文件数增长），
     * state 是「行少但单体极大」。两者的备份频率、容量评估、保留期毫不相干，
     * 放进同一个 schema 只会让任何一次备份都被对方拖住。
     *
     * <h3>为什么这里要 CREATE DATABASE</h3>
     * {@link JdbcStore} 的 {@code initializeSchema} 只建表不建库，库不存在时它的
     * {@code CREATE TABLE} 会直接抛错、整个 Bean 构造失败 —— 然后是一连串看不懂的
     * 「启动就挂」。这里先建库，与官方 {@code MysqlSkillRepository.createIfNotExist(true)}、
     * {@code MysqlAgentStateStore} 的取舍保持一致：新环境零手工步骤。
     *
     * <h3>为什么连接串从主库 URL 派生</h3>
     * 只换库名，host / port / 字符集 / 时区参数原样继承。这样改数据库地址时不必同步改两处，
     * 避免出现「主库改了、KV 库还指着旧机」这种只在切 REMOTE 时才暴露的隐蔽故障。
     *
     * <h3>这个 Bean 是本项目第二个 DataSource，所以主库必须由我们自己声明</h3>
     * Spring Boot 的 {@code DataSourceAutoConfiguration} 带
     * {@code @ConditionalOnMissingBean({DataSource.class, XADataSource.class})}：
     * 容器里<b>只要出现任意一个</b> DataSource Bean，它就整块退出。
     * 也就是说，本方法一旦存在，主库连接池就得由
     * {@link DataSourceConfig} 显式补上（并标 {@code @Primary}），否则：
     *
     * <ol>
     *   <li>下面那个 {@code DataSource} 参数会按类型解析到本方法自己的返回值 ——
     *       Spring 报循环依赖，应用启动失败；</li>
     *   <li>更糟的是"假如它能启动"：容器里唯一的 DataSource 变成这个 KV 池，
     *       Mapper / skill 仓库 / 状态存储会全部连到 {@code md_agent_workspace} 上。</li>
     * </ol>
     *
     * <p>由此推出一条硬约束：<b>本 Bean 永远不能标 {@code @Primary}</b>。
     * 它只是工作区文件的存储后端，不是"默认数据库"。
     */
    @Bean("workspaceKvDataSource")
    public DataSource workspaceKvDataSource(DataSource dataSource,
                                            AgentPlatformProperties properties,
                                            @Value("${spring.datasource.url}") String mainUrl,
                                            @Value("${spring.datasource.username}") String username,
                                            @Value("${spring.datasource.password}") String password) {
        String database = properties.getWorkspaceKvDatabase();
        if (database == null || !DATABASE_NAME_PATTERN.matcher(database).matches()) {
            throw new IllegalStateException(
                    "agent-platform.workspace-kv-database 非法（只允许字母、数字、下划线、连字符，1-64 位）: " + database);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("准备工作区 KV 库失败: " + database, e);
        }
        String kvUrl = deriveDatabaseUrl(mainUrl, database);
        log.info("工作区 KV 库连接串已派生：{}", kvUrl);
        return DataSourceBuilder.create()
                .url(kvUrl)
                .username(username)
                .password(password)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }

    /**
     * 把主库连接串里的<b>库名</b>换成 {@code database}，host / port / 查询参数原样保留。
     *
     * <h3>为什么不用正则（2026-09-23 修）</h3>
     * 这里原先是一条 {@code Pattern.compile("/([^/?]+)(\\?|$)")} 配
     * {@code replaceFirst("/" + database + "$1")} ——
     * 而 {@code $1} 正是"库名"那一组，于是替换把库名又拼了一遍，实际拿到的是
     * {@code ...md_agent_workspacemd-agent-platformuseUnicode=true&...}。
     * 这种错误<b>不抛异常</b>：它只会连到一个不存在的库上，然后在别处以
     * "Identifier name is too long" 这种看不懂的报错收场。
     *
     * <p>换成显式的下标切分之后，"切成三段、只换中间那段"这个意图是直接读得出来的，
     * 而且可以脱离 Spring 单测（见 {@code ControlPlaneStoreConfigTest}）——
     * 纯字符串逻辑值得留一条测试钉住，它出错的代价是"库连错了"。
     *
     * @throws IllegalStateException 连接串为空，或里面根本没写库名
     */
    static String deriveDatabaseUrl(String mainUrl, String database) {
        if (mainUrl == null || mainUrl.isBlank()) {
            throw new IllegalStateException("spring.datasource.url 为空，无法派生工作区 KV 库连接串");
        }
        // 查询参数先单独摘出来：它内部完全可能含 '/'（比如某个参数值是路径），
        // 让它参与 lastIndexOf('/') 就会把库名定位到参数里去。
        int queryAt = mainUrl.indexOf('?');
        String base = queryAt < 0 ? mainUrl : mainUrl.substring(0, queryAt);
        String query = queryAt < 0 ? "" : mainUrl.substring(queryAt);
        // 库名是最后一个 '/' 之后的那一段；这个 '/' 必须排在 "//host:port" 之后，
        // 否则说明 url 里压根没写库名（jdbc:mysql://host:3306）。
        int slashAt = base.lastIndexOf('/');
        int authorityAt = base.indexOf("//");
        if (slashAt < 0 || authorityAt < 0 || slashAt <= authorityAt + 1) {
            throw new IllegalStateException(
                    "spring.datasource.url 里没有库名，无法派生工作区 KV 库连接串: " + mainUrl);
        }
        return base.substring(0, slashAt + 1) + database + query;
    }

    /**
     * 工作区文件 KV（表名 {@code agentscope_store}，前缀由官方方言固定）。
     *
     * <p>只有 {@code fs_backend=REMOTE} 的 agent 会读写它；LOCAL 的 agent 完全不碰这张表 ——
     * 所以「建了库但没数据」是正常状态，不代表配置没生效。
     *
     * <p>注意表名前缀是方言里写死的 {@code agentscope_}，不要把这个库与其他 agentscope 组件的
     * 库混用，否则会撞表名。缓存加速用的 {@code WorkspaceIndex} 仍落在本地磁盘
     * （{@code <workspace>/.index/workspace.db}），那是刻意的：它是可重建的派生数据。
     */
    @Bean("workspaceKvStore")
    public BaseStore workspaceKvStore(
            @Qualifier("workspaceKvDataSource") DataSource workspaceKvDataSource) {
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(workspaceKvDataSource).build();
        JdbcStore store = JdbcStore.builder(workspaceKvDataSource)
                .dialect(dialect)
                .build();
        log.info("工作区文件 KV 已就绪：table={}", dialect.storeTableName());
        return store;
    }

    /** 控制面元数据存储：13 个 Mapper + 官方 skill 仓库。写路径的事务边界在实现类里（标在方法上）。 */
    @Bean("controlPlaneStore")
    public ControlPlaneStore controlPlaneStore(AgentMapper agentMapper, AgentRefMapper refMapper,
                                               AgentRuntimeMapper runtimeMapper,
                                               AgentMemoryMapper memoryMapper,
                                               AgentWorkspaceMapper workspaceMapper,
                                               AgentSubAgentMapper subAgentMapper,
                                               PromptMapper promptMapper, McpServerMapper mcpMapper,
                                               ModelMapper modelMapper,
                                               SkillMetaMapper skillMetaMapper,
                                               ResourceVersionMapper versionMapper,
                                               ChannelMapper channelMapper,
                                               ChannelBindingMapper channelBindingMapper,
                                               WorkflowMapper workflowMapper,
                                               WorkflowNodeRefMapper workflowNodeRefMapper,
                                               WorkflowValidator workflowValidator,
                                               AgentSkillRepository controlPlaneSkillRepository) {
        return new MyBatisControlPlaneStore(agentMapper, refMapper, runtimeMapper, memoryMapper,
                workspaceMapper, subAgentMapper, promptMapper, mcpMapper, modelMapper, skillMetaMapper,
                versionMapper,
                controlPlaneSkillRepository, channelMapper, channelBindingMapper,
                workflowMapper, workflowNodeRefMapper, workflowValidator);
    }
}
