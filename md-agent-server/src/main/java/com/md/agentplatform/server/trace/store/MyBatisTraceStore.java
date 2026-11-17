package com.md.agentplatform.server.trace.store;

import com.baomidou.mybatisplus.core.batch.MybatisBatch;
import com.baomidou.mybatisplus.core.batch.BatchMethod;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.md.agentplatform.server.trace.AgentTraceEvent;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.store.entity.AgentRunEntity;
import com.md.agentplatform.server.trace.store.entity.AgentRunEventEntity;
import com.md.agentplatform.server.trace.store.mapper.AgentRunEventMapper;
import com.md.agentplatform.server.trace.store.mapper.AgentRunMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link TraceStore} 的 MySQL 实现（MyBatis-Plus）。
 *
 * <h2>一、这个类只做三件事</h2>
 * 行模型转换、拼查询条件、批量提交。业务语义（seq 怎么分配、阶段怎么配对、
 * 什么时候该写）一律不在这里 —— 那些在 {@code AgentTraceRegistry} 与翻译器里，
 * 否则改存储实现就得连带改业务规则。
 *
 * <h2>二、saveRun 为什么是「先查再决定」</h2>
 * run 行会被写三次（开始 / 设置系统提示词 / 收口），第一次要 INSERT，后两次要 UPDATE，
 * 而调用方（Registry）不该关心"这是第几次"。三种做法里：
 * <ul>
 *   <li>{@code updateById} 返回 0 就 INSERT —— 依赖 JDBC 连接参数
 *       （{@code useAffectedRows} 为 true 时"值没变的更新"也返回 0），
 *       一旦有人改了 URL，就会退化成"每写一次都撞主键"；</li>
 *   <li>数据库方言 {@code INSERT ... ON DUPLICATE KEY UPDATE} —— 要么写 XML、
 *       要么把列名再抄一遍，列名从此有两个维护点；</li>
 *   <li><b>先 selectById 判存在</b> —— 多一次往返（每次 run 共 3 次），换来确定性：
 *       不依赖任何连接参数，读起来也不需要知道方言。<b>选这个</b>，
 *       因为这张表的写入量是"每次 agent 调用 3 次"，而 agent 调用本身要花几秒到几分钟。
 *   </li>
 * </ul>
 *
 * <h2>三、批量插入为什么用 MybatisBatch</h2>
 * {@code MybatisBatch} 是框架自己的批量工具：它开一个 {@code ExecutorType.BATCH} 的
 * SqlSession，逐条 {@code update} 后统一 {@code flushStatements}，走的是 JDBC 的
 * addBatch / executeBatch。相比手写 {@code <foreach>} 拼多值 INSERT：
 * <ul>
 *   <li>不用手写 SQL，列名只在实体上维护一次；</li>
 *   <li>单条超长（比如某个工具返回的巨大 JSON）只会让那条失败，不会让整条语句全废；</li>
 *   <li>{@code <foreach>} 拼出来的语句长度随批量线性增长，一批 500 条很容易撞
 *       {@code max_allowed_packet}。</li>
 * </ul>
 */
public class MyBatisTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisTraceStore.class);

    /** 列表查询的硬上限：接口参数已经被控制器夹过一次，这里再兜一次底，防止将来别处直接调用。 */
    private static final int MAX_RECENT = 500;

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;
    /** 批量插入要用它开 BATCH 模式的 SqlSession —— 这也是本类必须拿到 SqlSessionFactory 的原因。 */
    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisTraceStore(AgentRunMapper runMapper, AgentRunEventMapper eventMapper,
                             SqlSessionFactory sqlSessionFactory) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    // ==================================================================================
    // 写
    // ==================================================================================

    @Override
    public void saveRun(AgentTraceRun run) {
        if (run == null) {
            return;
        }
        persistRun(AgentRunEntity.of(run));
    }

    /**
     * 落一行 run 快照（存在则整行覆盖）。
     *
     * <p>单独暴露一个吃行模型的重载，是为了让异步归档线程直接把"出队时已经转换好"的快照写下去，
     * 不必再回到领域对象上取值 —— 领域对象在那一刻可能已经被 agent 线程改过了。
     */
    public void persistRun(AgentRunEntity entity) {
        if (entity == null || entity.getRunId() == null) {
            return;
        }
        if (runMapper.selectById(entity.getRunId()) == null) {
            runMapper.insert(entity);
        } else {
            runMapper.updateById(entity);
        }
    }

    @Override
    public void appendEvent(AgentTraceRun run, AgentTraceEvent event) {
        if (run == null || event == null) {
            return;
        }
        insertEvents(List.of(AgentRunEventEntity.of(run.getRunId(), event)));
    }

    /**
     * 批量插入事件行（一次一个 JDBC 批）。
     *
     * <p>调用方负责保证"同一批里没有重复的 (runId, seq)"：表上有唯一键，重复会让整批回滚
     * （MyBatis 的 BATCH 模式在 commit 时才真正执行，一条失败整批报错），
     * 而我们的写入侧不会产生重复（seq 由内存里的 AtomicInteger 分配，一个 run 一个计数器）。
     */
    public void insertEvents(List<AgentRunEventEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        MybatisBatch<AgentRunEventEntity> batch = new MybatisBatch<>(sqlSessionFactory, entities);
        // namespace 传 Mapper 接口，而不是官方示例里的实体类：
        // 语句 id 必须是「Mapper 接口全限定名 + .insert」，也就是 BaseMapper 注入 insert 时用的那个命名空间。
        // 传实体类会拼出「实体全限定名.insert」，那条语句根本不存在，运行期报
        // "Mapped Statements collection does not contain value for ...entity.AgentRunEventEntity.insert"。
        // 这个坑是 MyBatisTraceStoreIT（真库集成测试）抓出来的 —— 单测与编译期都发现不了。
        MybatisBatch.Method<AgentRunEventEntity> method = new MybatisBatch.Method<>(AgentRunEventMapper.class);
        BatchMethod<AgentRunEventEntity> insertStatement = method.get("insert");
        batch.execute(insertStatement);
    }

    @Override
    public void clearAll() {
        // 先删事件再删 run：反过来的话，run 行没了就再也定位不到孤儿事件。
        // delete 传空 Wrapper 生成的是"无 WHERE 的 DELETE"，是 MyBatis-Plus 的标准清表写法。
        int events = eventMapper.delete(new LambdaQueryWrapper<>());
        int runs = runMapper.delete(new LambdaQueryWrapper<>());
        log.info("trace 归档已清空: runs={} events={}", runs, events);
    }

    @Override
    public int deleteOlderThan(long cutoffMs) {
        if (cutoffMs <= 0) {
            return 0;
        }
        eventMapper.deleteByRunStartedBefore(cutoffMs);
        return runMapper.deleteStartedBefore(cutoffMs);
    }

    // ==================================================================================
    // 读
    // ==================================================================================

    @Override
    public AgentTraceRun findRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        AgentRunEntity runEntity = runMapper.selectById(runId);
        if (runEntity == null) {
            return null;
        }
        return toDomain(runEntity, loadEvents(runId));
    }

    @Override
    public List<AgentTraceRun> findRecent(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_RECENT));
        LambdaQueryWrapper<AgentRunEntity> query = Wrappers.<AgentRunEntity>lambdaQuery()
                .orderByDesc(AgentRunEntity::getStartedAtMs)
                // 开始时刻可能相同（同一毫秒内并发起多次），补一个 runId 降序，
                // 保证顺序稳定 —— 否则分页/刷新会出现"同一条 run 时而第二时而第三"。
                .orderByDesc(AgentRunEntity::getRunId)
                // last() 是字符串拼接，有注入风险；这里拼的是夹好范围的 int，安全。
                // 之所以不用分页插件：为一个列表页引入 MybatisPlusInterceptor 得不偿失（见 Step 2 说明）。
                .last("LIMIT " + size);
        List<AgentRunEntity> rows = runMapper.selectList(query);
        List<AgentTraceRun> runs = new ArrayList<>(rows.size());
        for (AgentRunEntity row : rows) {
            // 刻意不查事件：列表页只显示 run 级字段，详见 TraceStore 的类注释。
            runs.add(toDomain(row, List.of()));
        }
        return runs;
    }

    /** 某个 run 的全部事件，按 seq 升序 —— 顺序即前端事件流的顺序。 */
    private List<AgentRunEventEntity> loadEvents(String runId) {
        return eventMapper.selectList(Wrappers.<AgentRunEventEntity>lambdaQuery()
                .eq(AgentRunEventEntity::getRunId, runId)
                .orderByAsc(AgentRunEventEntity::getSeq));
    }

    // ==================================================================================
    // 行模型 → 领域对象
    // ==================================================================================

    /**
     * 组装领域对象。
     *
     * <p>刻意保留"事件可以为空"这个形态（而不是在这里抛错）：列表查询就是这么用的。
     * 调用方要拿它建树时必须自己确认事件已加载 —— 这个约定写在 {@link TraceStore} 的类注释里。
     */
    static AgentTraceRun toDomain(AgentRunEntity runEntity, List<AgentRunEventEntity> eventEntities) {
        AgentTraceRun run = new AgentTraceRun(runEntity.getRunId(), runEntity.getAgentName(),
                runEntity.getAgentKind(), runEntity.getSessionId(), runEntity.getUserId(),
                runEntity.getInput(), runEntity.getSource(),
                // 编排归属：两列都可能为 NULL（不是工作流发起的），构造器会把空白收敛成 null
                runEntity.getWorkflowRunId(), runEntity.getWorkflowNodeKey(),
                // 派发归属：只有异步子 agent 在父 run 收口之后才开跑时才有值，同样可能为 NULL
                runEntity.getParentRunId(), runEntity.getTaskId());
        run.restoreState(runEntity.getStartedAtMs(),
                // 行模型用 NULL 表示"进行中"，领域模型用 0 —— 在边界上翻译回来（与落库时的翻译对称）。
                runEntity.getEndedAtMs() == null ? 0L : runEntity.getEndedAtMs(),
                runEntity.getStatus(), runEntity.getInputTokens(), runEntity.getOutputTokens(),
                runEntity.getCachedTokens(), runEntity.getError(), runEntity.getSystemPrompt());
        List<AgentTraceEvent> events = new ArrayList<>(eventEntities.size());
        for (AgentRunEventEntity eventEntity : eventEntities) {
            events.add(toEvent(eventEntity));
        }
        run.restoreEvents(events);
        return run;
    }

    /** 事件行 → 事件（字段一一对应，没有派生计算）。 */
    static AgentTraceEvent toEvent(AgentRunEventEntity entity) {
        return new AgentTraceEvent(entity.getSeq(), entity.getStageSeq(), entity.getEventType(),
                entity.getPhase(), entity.getTitle(), entity.getStatus(), entity.getStageStartMs(),
                entity.getCostMs(), entity.getInput(), entity.getOutput(), entity.getError());
    }
}
