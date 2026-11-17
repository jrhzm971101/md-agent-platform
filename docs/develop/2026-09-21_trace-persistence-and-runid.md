# 运行链路观测：Step 2（落库）与 runId 改造

> 记录时间：2026-09-21
> 涉及仓库：后端 `D:\jinran\code\Md-Agent-Platform`（前端 `D:\jinran\code\agent-contro-plane` 本次无需改动）
> 覆盖范围：事件模型修正（D1 缺陷）→ runId 改造 → Step 2 落库（DDL / MyBatis-Plus / 异步归档 / 读路径）
> 验证状态：后端 `Tests run: 217, Failures: 0, Errors: 0` + `BUILD SUCCESS`；真库集成测试 `MyBatisTraceStoreIT` 6/6 通过
> 组织方式：目标 → 决策 → 改动清单 → 数据模型 → 设计取舍 → 验证 → 已知缺口 → 后续

---

## 0. 结论速览

1. **修掉一个会让页面永久卡死的缺陷（D1）**：旧实现每次查询都重新编号 `seq`，并行工具交错完成时已推送的序号会整体平移，而前端只放行「从游标 +1 起的连续前缀」，于是链路渲染到某一刻就再也不动了。
2. **seq 改为「产生即冻结」**：阶段开始写一条 `STARTED`、结束写一条 `COMPLETED`，两条各自在产生那一刻拿到序号，此后只做回放、不再重编号。这是前端做断点续传的前提，也是 Step 3（SSE）能落地的前提。
3. **runId 改造完成**：从 `UUID.substring(0, 8)` 换成 `{定长 8 位 base36 时间戳}-{6 位 base36 随机}`（形如 `t3k9f2a1-x7b2q9`），字典序 = 时间序，落库后可直接按主键排序取最近 N 次。
4. **落库打通**：两张表 + MyBatis-Plus 存储层 + 有界队列异步归档；内存当热数据/读缓存，MySQL 当权威副本，重启后链路仍然可查。
5. **发现并修复一个环境缺陷**：`application.yml` 里配的库 `md-agent-platform` 在服务器上**并不存在**（见 6.1）。已建库并执行建表脚本，落库才可能真正生效。

---

## 1. 目标与验收

### 1.1 要解决什么

Step 1 把调用链路做成了页面可读的调用树，但数据只在进程内存里（`trace-max-runs` 默认 200 条），**后端一重启，历史链路全丢**；而「某天某次调用到底发生了什么」恰恰是排障时最需要的。Step 2 把观测数据落进 MySQL。

### 1.2 验收标准

| 项 | 标准 |
| --- | --- |
| 持久化 | 重启后端后，`/api/traces` 仍能列出历史 run，`/api/traces/{runId}/events` 仍能还原完整调用树 |
| 不阻塞 | 归档写入**不允许**让 agent 线程等数据库；数据库变慢时也不能把一次真实调用卡住 |
| 容量 | `/api/traces` 能查到的条数不再受 `trace-max-runs` 限制 |
| 不倒退 | 关闭归档（`trace-archive-enabled=false`）时行为与 Step 1 完全一致 |

---

## 2. 决策记录

| 编号 | 决策 | 取舍 |
| --- | --- | --- |
| D1 | 阶段开始/结束**各写一条事件**（STARTED / COMPLETED），各自独立 seq、纯 INSERT、无 UPDATE | 行数翻倍、读时需配对；换来 seq 稳定、SSE 可用、崩溃只留「半截阶段」而不留僵尸 RUNNING 行 |
| D2 | 异步写：**有界队列 + 单消费者线程 + 批量刷盘**；入队用 `offer()` 不用 `put()`，满则**丢尾部 + 告警** | 极端情况下丢观测数据；换来「观测系统故障不会传导成业务故障」 |
| D3 | 内存 = 热数据/读缓存，DB = 权威副本，**内存优先、不足查库补齐** | 读路径多一次回库；换来列表页常态零查库、重启后又能看到全部历史 |
| D4 | 数据层用 **MyBatis-Plus**，不用 JdbcTemplate | 引入框架依赖；换来声明式的行模型映射与批量写 |
| D5 | **不用 Redis / MQ 中转** | 生产者消费者同 JVM，中间加一跳只增加失败模式，并不解决「进程被 kill 时队列里的数据照样丢」 |

### 2.1 D1 的根因（这一段值得单独读）

旧模型是「一个阶段一条记录」（`AgentTraceStep`：开始时刻 + 耗时 + 进出参）。它在纯内存场景够用，但一落到「增量推送 + 断点续传」就不成立了，因为**阶段是可变的**（进行中没有耗时与出参），而 `seq` 一旦发出就不能再变 —— 客户端拿它当游标：

```
并行工具，tool1 还在跑、tool2 已完成：
  1 run.started   2 tool1.started   3 tool2.started   4 tool2.completed
tool1 随后也完成，同一批数据重新翻译：
  1 run.started   2 tool1.started   3 tool1.completed   ← 3 号换了内容，而客户端已按旧内容渲染
  4 tool2.started 5 tool2.completed
```

前端 `mergeContiguousEvents` 只放行「从 cursor+1 起的连续前缀」，序号平移后它停在原地等，后面的事件全部积压 —— 表现就是**链路渲染到一半就不动了**。

现在的铁律是：**seq 在事件产生时分配并冻结，翻译器只做透传**。配套还有一条容易忽略的推论：**不输出的东西不许占号**，否则会留下永远补不上的空洞。因此 ——

- `MODEL_CALL`（单次 LLM 请求）**不产生事件**：页面上它被折叠进所属的 `REASONING`（同一次推理请求），模型名与 token 用量由 `REASONING` 的收口事件携带；
- `SYSTEM_PROMPT` **不产生事件**：它是 run 级属性（整轮只生效一个），直接挂在 run 上，落库后是 run 表的一列。

---

## 3. runId 改造

| 项 | 旧 | 新 |
| --- | --- | --- |
| 形态 | `UUID.randomUUID()` 取前 8 位（十六进制） | `t3k9f2a1-x7b2q9`（8 位 base36 时间戳 + 6 位 base36 随机） |
| 排序 | 随机，无法排序 | **字典序 = 时间序** |
| 用途 | 只能当标识 | 标识 + 可当「最近的 run」排序键（先按 `started_at_ms`，同时刻按 runId） |

实现要点（`TraceRunIdGenerator`）：

- 时间前缀固定 8 位：`base36(epochMillis)` 到 2059 年才会溢出到 9 位，定长是「字典序 = 时间序」的前提；
- `AtomicLong` CAS 保证时间前缀**单调非降**，防止 NTP 回拨造出「新 run 比旧 run 还小」的号；
- 随机后缀用 `ThreadLocalRandom`，避免并发下的竞争与伪随机退化；
- 单测覆盖：格式、单调性、20 万次唯一性、8 线程并发 8 万次唯一性。

---

## 4. 改动清单

### 4.1 事件模型（D1 修正）

| 文件 | 改动 |
| --- | --- |
| `server/trace/AgentTraceEvent.java` | **新增**。替代 `AgentTraceStep`；`seq`/`stageSeq`/`eventType`/`phase`/`status` + 工厂方法 `started(...)` / `completed(...)`；`PHASE_*` 词表收敛到 record 自己身上（写入方 / 成树方 / 测试三处共用一份，避免漂移导致「某个阶段整段消失」） |
| `server/trace/AgentTraceStep.java` | **删除** |
| `server/trace/AgentTraceRun.java` | `steps` → `events`（CopyOnWriteArrayList）；新增 `systemPrompt` 字段与 `restoreState` / `restoreEvents`（供存储回放） |
| `server/trace/AgentTraceRegistry.java` | 写入接口改为 `start` / `beginStage` / `endStage` / `systemPrompt` / `finish`；**seq 在这里分配并冻结**；`beginStage` 返回「阶段句柄」（即为该阶段 STARTED 事件的 seq） |
| `server/trace/AgentRunEventTranslator.java` | **重写**：删掉 seq 分配、`foldModelCalls`、`normalizeParents`；新增 `pairStages()`（按 stageSeq 配对，未收口的 `endMs = Long.MAX_VALUE`，好让「进行中的容器」能收编内部正在跑的节点）；path/parentPath 推导逻辑原样保留 |
| `server/agent/middleware/AgentRunTraceMiddleware.java` | 阶段开始/结束各写一条事件；`ModelCallFold`（按 agent 名分桶的折叠区，取走即清零）把模型名与 token 带到 `REASONING` 的收口事件上；`SYSTEM_PROMPT` 走 run 级属性 |

### 4.2 落库（Step 2）

| 文件 | 说明 |
| --- | --- |
| `md-agent-bootstrap/src/main/resources/db/trace-schema.sql` | **新增**。两张表的 DDL（幂等 `CREATE TABLE IF NOT EXISTS`） |
| `server/trace/store/entity/AgentRunEntity.java` | `md_agent_run` 行模型；`@TableName(autoResultMap = true)` + JSON 列 `@TableField(typeHandler = JacksonTypeHandler.class)` |
| `server/trace/store/entity/AgentRunEventEntity.java` | `md_agent_run_event` 行模型，同上 |
| `server/trace/store/mapper/AgentRunMapper.java`、`AgentRunEventMapper.java` | `BaseMapper` + 保留期清理用的两个 `@Delete` |
| `server/trace/store/TraceRowWriter.java` | **行级**写入契约（吃实体），异步写入器依赖它 |
| `server/trace/store/TraceStore.java` | 归档存储契约（继承行级写入 + 领域读写），并写明「读同步、写异步」与「列表路径不带事件」两条约定 |
| `server/trace/store/MyBatisTraceStore.java` | MySQL 实现：先查再决定 insert/update、`MybatisBatch` 批量写事件、按 seq 升序读回并重建领域对象 |
| `server/trace/store/AsyncTraceStore.java` | 有界队列 + 单消费者 + 批量刷盘 + 保序 + 丢尾告警 + `@PreDestroy` 刷盘 |
| `server/trace/store/TraceRetentionJob.java` | 每天 03:10 清理过期 run（含重入保护、不抛异常） |
| `server/config/TraceStoreConfig.java` | 装配（`@ConditionalOnProperty` 控制开关）+ `@EnableScheduling` |
| `server/config/AgentPlatformProperties.java` | `trace-max-runs` 语义改为「内存热数据上限」；新增归档开关 / 队列容量 / 批量上限 / 保留天数 |
| `md-agent-bootstrap/src/main/resources/application.yml` | 写入上述 5 个配置项（此前 `trace-max-runs` 从未写进任何 yml） |

### 4.3 测试

| 文件 | 说明 |
| --- | --- |
| `TraceRunIdGeneratorTest`（新增） | 格式 / 单调 / 唯一 / 并发 |
| `AgentRunEventTranslatorTest`（重写 16 例） | 含「输出与输入一一对应」、「未收口阶段呈现为进行中节点」、「事件按发生顺序输出」 |
| `AgentRunTraceMiddlewareTest`（改造 12 例） | 断言「推理收口事件带模型名与 token 合计」（验证折叠生效），并断言 `MODEL_CALL` **不产生事件** |
| `AsyncTraceStoreTest`（新增 4 例） | 保序与 clear 屏障、队列满丢弃不阻塞、关停刷盘、关停后不再收 |
| `TraceEntityMappingTest`（新增 5 例） | 行模型 ↔ 领域对象逐字段往返、seq 计数器还原、JSON 列往返 |
| `MyBatisTraceStoreIT`（新增 6 例，**真库**，默认不随 `mvn test` 跑） | JSON 列能否读回（守 `autoResultMap`）、`MybatisBatch` 能否工作、唯一键、列表顺序、保留期清理、清空（空 Wrapper 的 `DELETE`）。清空用例会先确认库里没有真实链路数据，有则跳过 —— 测试不允许误删用户数据 |

---

## 5. 数据模型

### 5.1 两张表

```
md_agent_run                    -- 一次 run 一行
  run_id (PK) agent_name agent_kind session_id user_id status
  started_at_ms ended_at_ms input_tokens output_tokens cached_tokens
  error system_prompt input(JSON)
  索引: (agent_name, started_at_ms) / (started_at_ms) / (session_id)

md_agent_run_event              -- 一条阶段事件一行（只增不改）
  id (PK, auto) run_id seq stage_seq event_type phase title status
  stage_start_ms cost_ms input(JSON) output(JSON) error created_at_ms
  唯一键: (run_id, seq)         -- 重复投递写不进脏数据
  索引: (run_id, stage_seq)
```

### 5.2 三个刻意的选择

1. **时间存 `BIGINT`（epoch millis）而不是 `DATETIME`**：领域模型里就是 millis，直存直取，不做任何时区换算。`DATETIME` 一旦 JDBC URL 上的 `serverTimezone` 被改动，历史数据会整体漂移。代价是 SQL 客户端里不便肉眼阅读，用 `FROM_UNIXTIME(started_at_ms/1000)` 包一层即可。
2. **事件表自增主键 + `(run_id, seq)` 业务唯一键**：seq 是「本 run 内的序号」，把它当物理主键会让「以后要支持事件修正」变成改主键的活。
3. **读的两条路径不对称**：`findRun` 带事件（建树要），`findRecent` 不带事件（列表页只要 run 级字段）。20 条 run 各带几百条事件地把数据拉回来，是白白放大几十倍载荷。

### 5.3 写入时序

```
agent 线程                                归档线程（单线程，队列有界）
  start()            → saveRun(run)   入队
                     → STARTED(root)  入队
  beginStage(...)    → STARTED(...)   入队
  endStage(...)      → COMPLETED(...) 入队
  systemPrompt(...)  → saveRun(run)   入队
  finish(...)        → COMPLETED(root) 入队
                     → saveRun(run)   入队  ─→ poll / drainTo → 按序 flush
                                                事件攒批 → MybatisBatch 一次提交
```

**顺序是怎么保住的**：FIFO + 单消费者只保证「入队顺序 = 出队顺序」，而攒批会打乱它 —— 如果先把一批里的 run 快照全写了、再写事件，run 的收口快照就会跑到它自己的事件前面。所以 `flush()` 是**按顺序扫描、遇到非事件任务就先把已攒的事件写掉**；批量化只发生在「连续的事件」之间。

**为什么 `clear` 也要走队列**：直接打数据库的话，队列里排着的旧事件会在清空之后才落库，用户看到的是「点了清空、刷新又冒出来几条」。

---

## 6. 验证与复现

### 6.1 环境缺陷：配置里的库根本不存在（本次发现并修复）

`application.yml` 声明的是 `jdbc:mysql://106.53.145.158:30306/md-agent-platform`，但服务器上**没有这个库**：

```
ikb_risk_control  information_schema  mysql  nacos_config  performance_schema
saas_prod_risk_core  state_store  sys  xxl_job
```

之所以一直没暴露：`ConfigurableAgentBuilder#resolveStateStore` 只在 agent 定义里填了 `stateStore` 时才用数据源，而当前几个 agent 的状态都落在文件系统（`~/.agentscope/state`），那条路径从未被走到；`SqlQueryTool`（JdbcTemplate）同理。**但归档是每次调用都要写的**，落在一个不存在的库上，表现会是「页面上一片空白，日志里每批一次 ERROR」—— 正是最该避免的静默失败。

处置：建库 `md-agent-platform`（`utf8mb4`），并执行 `db/trace-schema.sql`。若将来想让它自建，可在 JDBC URL 上加 `createDatabaseIfNotExist=true`（本次刻意没加：隐式建库在生产环境是个惊吓）。

### 6.2 全量测试

```powershell
cd D:\jinran\code\Md-Agent-Platform
$env:JAVA_HOME = "C:\Users\User\.jdks\ms-21.0.11"
& "D:\jinran\maven\apache-maven-3.9.10-bin\apache-maven-3.9.10\bin\mvn.cmd" -o test
# 期望：md-agent-server 210 + md-agent-bootstrap 7 = 217 全绿
```

### 6.3 真库集成测试（需要连得到那台 MySQL）

```powershell
& "D:\jinran\maven\apache-maven-3.9.10-bin\apache-maven-3.9.10\bin\mvn.cmd" -o test `
    "-Dtest=MyBatisTraceStoreIT" "-Dsurefire.failIfNoSpecifiedTests=false"
# 期望：Tests run: 6, Failures: 0, Errors: 0
```

它默认不随 `mvn test` 跑（类名以 `*IT` 结尾不在 surefire 的默认包含里），所以别人机器上没库也能构建通过。测试数据统一用 `itest-` 前缀的 runId，跑完自动清理，不会污染真实链路数据。

### 6.4 端到端自测（改完后端必须重启）

```powershell
# 1) 先造一次 run：给接入的企微 / 飞书 bot 发一条消息（或等定时任务）
# 2) 看列表
Invoke-WebRequest "http://localhost:9090/api/traces?limit=10" -UseBasicParsing | Select-Object -ExpandProperty Content
# 3) 看事件流（runId 形如 t3k9f2a1-x7b2q9）
Invoke-WebRequest "http://localhost:9090/api/traces/<runId>/events" -UseBasicParsing | Select-Object -ExpandProperty Content
# 4) 【本次验收的关键一步】重启后端，再执行 2) 与 3) —— 数据必须原样还在
```

---

## 7. 已知缺口

| 项 | 说明 |
| --- | --- |
| **没有 HTTP 触发 agent 执行的接口** | `POST /api/agents/{name}/invoke` 仍未实现。它是「造一次 run」成本最低的方式，没有它就只能靠发企微/飞书消息，联调与验收效率都很低（本次的验收靠真库集成测试兜住了持久化那一段）。**2026-09-21 用户明确：本阶段暂缓，不做** |
| SSE 未实现 | `GET /api/traces/{runId}/events/stream` 属于 Step 3。当前前端靠 `after` 游标轮询，能看到链路在长但有延迟 |
| 子 agent 无独立事件 | 子 agent 与父 agent 共用 run，产物里没有 `subagent.dispatched/completed`（Step 5） |
| 并发工具的父子关系不精确 | 同一批并行工具的开始时刻取的是同一个值（所以它们互为兄弟、不会互认父子），但与「批次」这一层的归属仍不精确（Step 5） |
| `agentKind` 修复未在真实运行中验证 | 装配期登记快照那处改动（`AgentRunTraceMiddleware` + `ConfigurableAgentBuilder`）只过了单测，还没在真跑一次企微问答后确认过 |
| 保留期清理是 Spring `@Scheduled` | 项目里已有的调度是 xxl-job（需要 Admin 控制台配任务），观测数据的清理选用了「部署即生效」的 `@Scheduled`。若要统一，删 `@EnableScheduling`、把 `TraceRetentionJob` 的方法改成 `@XxlJob` 即可，逻辑不用动 |
| 零鉴权 | 无 `spring-security`、无 CORS 配置，`DELETE /api/traces` 完全裸奔；`application.yml` 里硬编码了数据库口令等敏感信息，公开前必须脱敏 |

---

## 8. 后续路线

1. **Step 3：SSE**（`Flux<ServerSentEvent>`，`Last-Event-ID` 续传；通知器只负责唤醒、不承载数据）。
2. **Step 6：`POST /api/agents/{name}/invoke`**（成本最低、收益最大，建议提前）。
3. **Step 4：前端其余页面接真数据**（概览 / 工具 / 技能 / MCP / 会话 / 渠道 …；逐页给独立开关，避免一刀切造成半真半假）。
4. **Step 5：子 agent 与并发工具的归属重定义**。

---

## 9. 关键文件索引

| 关注点 | 从哪里看起 |
| --- | --- |
| 为什么事件而不是阶段 | `md-agent-server/.../trace/AgentTraceEvent.java` 的类注释 |
| seq 在哪里分配、为什么 | `md-agent-server/.../trace/AgentTraceRegistry.java` |
| 调用树是怎么长出来的 | `md-agent-server/.../trace/AgentRunEventTranslator.java` |
| 表结构 | `md-agent-bootstrap/src/main/resources/db/trace-schema.sql` |
| 行模型与 JSON 列的两个必要条件 | `md-agent-server/.../trace/store/entity/AgentRunEntity.java` 的类注释 |
| 队列的取舍（有界 / 丢尾 / 保序 / 刷盘） | `md-agent-server/.../trace/store/AsyncTraceStore.java` 的类注释 |
| `MybatisBatch` 的命名空间坑 | `md-agent-server/.../trace/store/MyBatisTraceStore.java` 的 `insertEvents` |
| 配置项 | `md-agent-bootstrap/src/main/resources/application.yml` 的 `agent-platform` 段 |
