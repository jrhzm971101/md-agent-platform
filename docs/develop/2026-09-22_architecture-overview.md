# 平台架构总览：已完成能力的「设计思路 → 实现方案」对照

> 整理时间：2026-09-22。面向架构评审与后来者：每一块回答三个问题 —— **解决什么问题、关键取舍是什么、代码落在哪里**。
> 当前验证基线（2026-09-23 更新）：后端 `mvn -o test` **300 通过**（server 277 + bootstrap 23）+ 真库集成测试 `MyBatisControlPlaneStoreIT` **11 通过**（`*IT` 不在 surefire 默认范围，需显式指定）；前端 `vue-tsc` 0 错、`vitest` **118 例**、`vite build` 通过。

---

## 0. 一句话全景

平台是**一个控制面 + 四条运行时链路**：

```
                       ┌─────────────────── 控制面（21 个页面，agent-contro-plane）──────────────────┐
                       │  Agents/Prompts/Skills/MCP/Middleware/Tools/Channels/Gateway  → 真实 CRUD   │
                       └───────────────────────────────┬────────────────────────────────────────────┘
                                                       │ 保存 / 发布 / 回滚（X-Operator 审计）
                                                       ▼
                                 ┌──── 16 张 MySQL 表（服务端唯一真相，Nacos 已退场）────┐
                                 │ 元数据 11 张 + 渠道 3 张 + 链路 2 张                    │
                                 └───────────────┬──────────────────┬────────────────────┘
                                                 │ 发布触发热装配      │ 读写
                                                 ▼                    ▼
   ┌── 渠道入口 ──┐                     ┌──────────────────┐   ┌─────────────────────┐
   │ 企微/飞书/…  │──路由(ChannelRouter)─▶│  AgentManager    │◀──│ 页面对话 SSE         │
   └──────────────┘      ▼              │  运行时注册表     │   │ /api/agents/{id}/chat│
                  MessageRouter ───────▶│ (agent 实例)     │   └─────────────────────┘
                                        └────────┬─────────┘
                                                 │ agent.call()
                                                 ▼
                              AgentRunTraceMiddleware ──▶ AgentTraceRegistry（内存热数据）
                                                 │                    │
                                                 │                    ├─ 异步批量 ─▶ md_agent_run / _event
                                                 │                    └─ SSE 唤醒 ─▶ 执行链路页（事件流成树）
                                                 │
                                                 └─ md_agent_run 按 (user_id, session_id) 聚合 ─▶ 会话记录页
                                                    （人 / 轮次 / 被哪些 agent 服务过 / 记忆还在不在）
```

四条运行时链路：**① 渠道消息（企微/飞书）② 页面对话（SSE）③ 运行链路观测 ④ 元数据发布→热装配**。

---

## 1. 模块与职责

| 模块 | 定位 | 关键内容 | 有意不放在这里 |
| --- | --- | --- | --- |
| `md-agent-model` | 领域模型 | AgentDefinition 等纯模型 | 任何 Spring / DB 依赖 |
| `md-agent-server` | 平台内核 | 装配层、控制面 Store/校验/发布、渠道运行时、消息路由、渲染器、链路观测、工具 | HTTP 入口 |
| `md-agent-bootstrap` | 应用外壳 | 全部 Controller（agent/channel/gateway/chat/trace/resource/test）、yml、SQL 脚本、启动装配 | 业务逻辑 |
| `agent-contro-plane` | 控制台前端 | Vue3 + Vite + TS + Element Plus；21 个页面，features: chat / controlplane / conversation / traces | 任何直连 DB 的行为 |

> Controller 全部收口在 bootstrap 是**刻意**的：上一轮做过一次「controller 归位」改造，避免入口散落在 server 里。

---

## 2. 数据模型全景（16 张表 + 1 个无表聚合视图）

### 2.1 控制面元数据（`control-plane-schema.sql`，11 张）

| 表 | 承载什么 | 为什么这样切 | 关键约定 |
| --- | --- | --- | --- |
| `md_agent` | 身份（**agent_name = 标识，display_name = 展示名**）+ 生命周期（status/version/published_version/enabled） | 只放「我是谁、处于什么状态」，不放任何配置 | 主键 = agent_name = 运行时注册键，**页面标题一律取 display_name** |
| `md_agent_ref` | 引用：prompt_key / tools / middleware / skill_names / mcp_names / knowledges | 引用随页面勾选频繁变动，与运行时旋钮混表会让「改动定位」变难；且「谁引用了 X」的反查只扫这一张 | 全部是**下拉多选**，禁止自由输入 |
| `md_agent_runtime` | 通用运行时旋钮 + 模型 | ReActAgent 与 HarnessAgent 的**共有部分** | NULL = 不下发该 setter（用框架默认） |
| `md_agent_memory` | 上下文压缩 + 记忆 + 工具结果驱逐 | 对应框架 `CompactionConfig` / `MemoryConfig` / `ToolResultEvictionConfig` 三个对象 | 同上（三态） |
| `md_agent_workspace` | Harness 工作区 + 能力开关 | **纯 HARNESS 表**（REACT 不建 workspace 行） | 三项技能策略已迁到 runtime 表 |
| `md_agent_sub_agent` | 子 agent 声明（一对多） | 子 agent 只允许 REACT | 装配走 `subagentFactory(name, agents::get)` 惰性路由 |
| `md_prompt` | prompt 正文（prompt_key → body）+ display_name | 正文与引用分离，一处改多处生效 | 被 agent 引用时覆盖 sysPrompt；**主键不可当名字** |
| `md_mcp_server` | MCP 服务端（端点/headers/启用开关）+ display_name | 连接与「工具白/黑名单」成对下沉到 MCP 级 | stdio 一律拒绝；mcp_names 的引用值是 server_name |
| `md_skill_meta` | skill 的**治理**元数据（含 display_name） | 内容不在这里：正文由官方 `MysqlSkillRepository` 管在另一个 schema（`agentscope`） | description 是缓存，以官方表为准 |
| `md_model` | 模型连接（端点/密钥/模型名）+ 生成参数 + **兜底开关 `is_default`** + display_name | 端点与密钥是需要回滚的线上凭据；`model_ref` 早就在库里，以前填的是 Bean 名、无法校验 | `formatter` / `endpoint_path` / 生成参数全部三态；「全表最多一个默认模型」由 Store 在事务内保证（MySQL 无部分唯一索引） |
| `md_resource_version` | 六类资源的版本快照（AGENT/PROMPT/SKILL/MCP/CHANNEL/MODEL） | 回滚 = 用快照生成**新版本**，版本序列单向递增 | 唯一键 (type, name, version) |

**横向表级约定四条**（写在脚本头部，改脚本前必读）：
1. JSON 列必须配 `@TableName(autoResultMap = true)` + `JacksonTypeHandler`（漏了的表现是「写得进、读出来是 null」）；
2. 一批 `TINYINT(1)` 是**三态**（NULL / 0 / 1），不是布尔 —— 页面必须能区分「未设置」与「关闭」；
3. **不做外键、不做级联删除**：删除保护要给出「prompt foo 正被 agent A、B 引用」这种可读报错，外键只能抛 SQL 异常。
4. **标识列与展示名分列**（2026-09-22 起）：`agent_name` / `prompt_key` / `server_name` / `skill_name` / `channel_id` 是运行时注册键、路由参数与引用值，改一次等于换一个资源，**因此它们不能承担「给人看的名字」** —— 每个域都另有一列 `display_name`（NOT NULL、必填、不参与装配、不参与唯一键、不可被引用）。页面标题与列表首列只取 `display_name`，标识降为次要的 mono 索引。

### 2.2 渠道（`channel-schema.sql`，3 张）

| 表 | 承载什么 | 关键设计 |
| --- | --- | --- |
| `md_channel` | 渠道实例（channel_id / channel_type / default_agent_id / dm_scope / reply_style / properties / 生命周期） | 与 agent 同构的「草稿 + 发布 + 版本」治理字段 |
| `md_channel_binding` | 路由规则（peer / parent_peer / guild / roles / team / account / channel + sort_order） | 字段**逐一对应官方 ChannelBinding**，让路由能直接交给官方 `ChannelRouter` 求值 |
| `md_gateway` | 网关配置（**单行表**，id 固定 1） | 只有一个决策项（主 agent），多行会制造「哪一行生效」的问题；必须由脚本预置 |

### 2.3 运行链路（`trace-schema.sql`，2 张）

| 表 | 承载什么 | 关键设计 |
| --- | --- | --- |
| `md_agent_run` | run 级汇总：run_id / agent_name / **agent_kind（建 run 那刻冻结）** / session / user / **source（触发来源，入口方写）** / status / tokens / error / system_prompt / input 摘要 | run_id 形如 `t3k9f2a1-x7b2q9`，**字典序 = 时间序**，列表页直接按它倒序 |
| `md_agent_run_event` | 阶段事件（只增不改）：seq / stage_seq / event_type / phase / status / stage_start_ms / cost_ms / input / output / error | 唯一键 `(run_id, seq)`；seq 是前端的断点续传游标 |

### 2.4 会话状态（**独立库** `md_agent_state`，按 agent 分表）

状态**不在业务库里**：2026-09-22 起会话状态搬到同实例的独立库 `md_agent_state`，一个 agent 一张表，表名由 `md_agent_runtime.state_table` 冻结给出。

| 关注点 | 结论 |
| --- | --- |
| 库从哪来 | 配置 `agent-platform.state-store-database`；表由框架首次使用时自建 |
| 表名怎么定 | `COALESCE(NULLIF(state_table, ''), agent_name)` —— **装配期与页面查询必须用同一表达式**，否则页面会把在用的表标成孤儿 |
| 一行是什么 | `session_id = <userId>:<sessionId>`、`state_key`（恒为 `agent_state`）、`item_index`（恒为 0）、`state_data`（框架对象的序列化 JSON） |
| 跨库查询 | 同实例 → SQL 里写全限定名 `库.表` 即可，**不需要第二个 DataSource** |
| 页面能做什么 | 看表清单 / 看会话 / 看上下文摘要 / **删会话状态**（只删状态，不碰 run 与 event） |
| 为什么不能编辑 | `state_data` 是框架内部对象的序列化结果，写回半损坏的 JSON 会在下一次对话才炸 |

详见 `docs/develop/2026-09-22_state-store-refactor.md`。

### 2.5 会话：**聚合出来的第三层（没有表）**

「会话」不是一张表，就是上表里 `md_agent_run` 按 `(user_id, session_id)` 的 GROUP BY。
之所以敢这么定，是因为 run 表已经有两列把这件事说清楚了：一次调用 = 一行 run，
同一串 run 的这两列相同 —— 于是分组的结果就是「一个人在一段对话里的所有执行」。
单开一张会话表，就多了一个必须与 run 保持同步的地方，
而同步不上的表现是「链路里明明有一次执行，会话列表里却没有」：
一个安静的假象比一个明显的缺口更难查。

| 关注点 | 结论 |
| --- | --- |
| 表 / 写入方 | **都没有**。这一层是只读视图，没有新表、也没有新写入方 |
| 分组键 | `(user_id, session_id)` 两列 —— `sessionId` 由入口方算、不保证全局唯一，只按它会撞车 |
| 跨库对齐 | 与状态库对齐的键是 `slotId = userId + ":" + sessionId`；唯一实现是 `AgentStateAdminService.slotId(...)` |
| 本轮新增 | `md_agent_run.source`（触发来源，**由入口方写下来**，不从 sessionId 形状反推）+ 索引 `idx_user_session (user_id, session_id)` |
| 页面能力 | 会话列表（筛选五维全在服务端）/ 一条会话的执行时间线 / 跳到链路详情 / 跳到状态存储页清记忆 |
| 为什么没有编辑 | 改一条会话等于改它底下的一批 run，而 run 是历史事实、只增不改 |

详见 `docs/develop/2026-09-23_session-management.md`。

---

## 3. 分块设计：问题 → 取舍 → 实现

### 3.1 控制面元数据（把 Nacos + 本地 md 换成 MySQL 单源）

| 维度 | 内容 |
| --- | --- |
| 解决什么问题 | 元数据散在三处（Nacos AI registry / 本地 md / 代码常量），**改一处不生效、不知道哪份是真的**；且没法给出「草稿 / 发布 / 回滚」的治理 |
| 关键取舍 | ① **单源 MySQL**（放弃迁移期 Nacos 兜底 —— 双源会让「改了不生效」永远存在）；② **按域拆 6 张 agent 表** 而不是大宽表（每域独立演进、页面表单天然分区）；③ 小配置用 **JSON 列**（没有按子字段查询的需求）；④ skill 内容用**官方仓库**，只自建治理表（零自研） |
| 实现 | `ControlPlaneStore` 契约 + `MyBatisControlPlaneStore` 唯一实现；每个写方法标 `@Transactional`（**本项目首个事务点**）；`ControlPlaneValidator` 收集式校验（一次报全）；`ControlPlanePublishService` 管发布/回滚/快照；行模型 `GovernedRow` 用默认方法实现「新建/保存/发布」的字段推进 |
| 落点 | `server/controlplane/{store,store/entity,store/mapper}`、`ControlPlaneValidator`、`ControlPlanePublishService`、`controller/{agent,controlplane}` |

### 3.2 装配层（DB 行 → 可运行的 Agent 实例）

| 维度 | 内容 |
| --- | --- |
| 解决什么问题 | 元数据换了源，运行时建造链（Builder 链）必须跟着换源，且不能把「库里的 null」变成「框架的 false」 |
| 关键取舍 | ① `agent-platform.agents.source` 分派 db/local/nacos 三条源（**当前 = db**）；② **技能白名单装饰器是必须的**：不装饰，框架的 `DynamicSkillMiddleware` 会把**全库技能**列进每个 agent 的提示词（静默的行为放大）；③ MCP 连接缓存必须可失效（`invalidate(name)`），否则「端点改了、也发布了、还是连旧会话」；④ 三态开关**没配就不调 setter** |
| 实现 | `AgentManager`（注册表 + `init` / `unregister` / `reload` / `reloadAll`）、`ConfigurableAgentBuilder`（REACT / HARNESS 两条 builder 链 + 装配期 `registerAgentKind`）、`AgentDefinitionResolver`（6 表行束 → AgentDefinition 的**唯一**转换点）、`DbMcpClientFactory`、`NamedSkillRepository`、`McpServerBinding`(name, client, enableTools, disableTools) |
| 落点 | `server/agent/` |

### 3.3 渠道与网关（IM 消息怎么走到 agent）

| 维度 | 内容 |
| --- | --- |
| 解决什么问题 | 多家 IM（企微 / 飞书 / 钉钉 / GitLab）各自有凭据、回调、会话键、回复渲染差异；且「谁的消息交给谁」需要可配置、可审计 |
| 关键取舍 | ① **不自己写路由分层**：绑定规则字段与官方 `ChannelBinding` 一一对应，直接交给官方 `ChannelRouter` 求值，避免与官方漂移；② 网关做成**单行配置**；③ 类型差异用**能力声明**表达（`ChannelCapability`：是否支持 dmScope / 回复风格），页面按能力渲染控件，不适用的不渲染控件只留原因；④ 凭据以 `properties` JSON 存（**暂未字段级加密**，已知缺口） |
| 实现 | `ChannelTypeRegistry` + 6 个工厂（wecom/feishu/dingtalk/gitlab + wecom-bot/feishu-bot）；`ChannelConfigAssembler`（行 → 官方 ChannelConfigEntry）；`AgentGatewayRuntime`（用 `md_gateway.main_agent_id` 构造官方 router）；`ChannelRuntime` / `ManagedGateway` / `ChannelRouteTable` / `RouteDecision`；`ChannelValidator` 做发布前阻断 |
| 落点 | `server/channel/**`、`server/messaging/**`、`controller/{channel,gateway}` |

### 3.4 消息处理与回复渲染（渠道侧的下半段）

| 维度 | 内容 |
| --- | --- |
| 解决什么问题 | 官方渠道只把消息交出来，**调用哪个 agent、怎么把流式输出变成 IM 能看的形态**是平台自己的事 |
| 关键取舍 | ① `MessageRouter` 只从路由结论里取 `agentId` / `matchedBy`，自己不做匹配（不重复官方逻辑）；② 渲染风格按平台推断 + 可覆盖（`ReplyStyle` / `reply_style`）；③ 「思考过程」单独渲染（`WeComThinkRenderer` 把 reasoning/thinking 帧转成可读进度），因为长链路任务里用户等的是过程而不是最终一句 |
| 实现 | `MessageRouter`、`BotInstanceManager` / `BotInstance`、`ReplyRendererRegistry` + `WeComThinkRenderer` / `FeishuCardRenderer`、`WorkWxWsClient`（同步阻塞 + 重试，校验 errcode） |

### 3.5 页面对话（POST /api/agents/{agentId}/chat，SSE）

| 维度 | 内容 |
| --- | --- |
| 解决什么问题 | 联调与自用需要一个**不依赖 IM** 的入口，且要能造 run、能拿到 runId 去观测 |
| 关键取舍 | ① 初版只传 agentId，其余走默认（先把链路打通）；② 每个 agent 独立会话（切 agent 切会话）；③ 前端用 markdown 渲染；④ 同样的 runId 语义与渠道一致（页面对话也要能「跳去执行链路」） |
| 实现 | `AgentChatController`（`produces=TEXT_EVENT_STREAM_VALUE`）、`ChatStreamEvent`（帧结构集中在一处工厂方法，前端不必跟着改类型）、前端 `features/chat/{api,useChat,model,markdown}` |

### 3.6 运行链路观测（五层，本项目最核心的一块）

| 层 | 做什么 | 关键设计 |
| --- | --- | --- |
| ① 采集 `AgentRunTraceMiddleware` | 在 5 个 stage（onAgent / onReasoning / onModelCall / onActing / onSystemPrompt）把入参与出参写进 Registry | **洋葱型 vs 管道型**：调用前已知的进 input，调用后产生的只能从事件流攒（token 用量、工具结果）；工具结果不在 `ToolResultEndEvent` 里，必须按 toolCallId 攒 delta；异常没有事件类型，只能 `doOnError` 接住**不吞** |
| ② 聚合 `AgentTraceRegistry` + `AgentTraceRun/Event` | 内存里当聚合根：分配 seq、成对写 STARTED / COMPLETED、发布事件 | **事件而不是阶段**：seq 一旦发出就冻结（前端拿它做断点续传游标）；不输出的东西**不许占号**（MODEL_CALL 折叠进 REASONING、SYSTEM_PROMPT 挂 run 级属性） |
| ③ 落库 `TraceStore` / `AsyncTraceStore` / `MyBatisTraceStore` | 内存保留为热数据（最近 200 条），MySQL 是权威副本 | **读同步、写异步**：写走有界队列 + 单线程批量刷盘，满则丢观测数据并告警，**绝不阻塞 agent 线程**；`TraceRetentionJob` 每天清理超期数据 |
| ④ 推送 `TraceStreamNotifier` | 写入即唤醒订阅者 | **通知器只负责唤醒、不承载数据**：唤醒后仍从持久化游标重读，避免把通知当数据；另有兜底轮询与心跳 |
| ⑤ 成树 `AgentRunEventTranslator` → 前端 `adapters.ts` | 事件对 → 调用树（靠 `stage_start_ms` 时间区间包含推算父子） | 一棵树两种消费：REST 快照（首屏 + 补齐）与 SSE 增量（游标续传） |

### 3.7 前端控制台

| 维度 | 内容 |
| --- | --- |
| 解决什么问题 | 元数据、渠道、网关、链路、对话都要能在页面上治理，不再靠改配置文件 |
| 关键取舍 | ① **不做 mock 分叉**（DB 已是唯一真相，留 mock 只会让页面「看着挺好但与库无关」）；② 三态必须渲染成三选（复选框会把 NULL 存成 false，那一刻框架默认值就永久失效）；③ 「被谁引用」在前端本地算（列表已带回引用名清单，避免按卡片逐条回查）；④ agent 详情是**分域表单 + 7 个页签**，与后端 6 张表的切分一一对应 |
| 实现 | `src/api/controlplane/{index,types}`（真实接口层 + 契约测试锁 method/path/载荷）、`components/ui/{TriStateSelect,JsonField,KeyValueEditor}`、`components/controlplane/VersionsPanel`、`features/controlplane/feedback`（400 逐条列问题 / 409 列引用方） |

### 3.8 会话聚合（把「人」这一层还回来）

| 维度 | 内容 |
| --- | --- |
| 解决什么问题 | 链路页回答「这一次是怎么走的」，状态页回答「agent 现在记得什么」，两者之间缺一层：「这是谁、聊过几轮、被哪几个 agent 服务过、还记得多少」 |
| 关键取舍 | ① **不建表**（见 §2.5）；② **agent 是筛选维度不是归属维度**：`agents` 是数组、`agentId` 用 `contains` 筛而不是 `equals`；③ **筛选全在服务端**（含"有没有记忆"）：列表是分页的，在当页数据上本地筛会让「共 N 个会话 / 筛出 M 条」自相矛盾；④ 状态库读不到时降级成"无记忆"而不是整页 500；⑤ 时间统一给 epoch millis，前端不再解析日期字符串 |
| 实现 | `server/session/{AgentSessionMapper,AgentSessionAdminService,AgentSessionViews,SessionAggregateRow,SessionRunRow}`（聚合 SQL + 内存筛选 + 跨库对齐 + 状态翻译）、`controller/session/SessionController`（`subscribeOn(boundedElastic)` 包装阻塞 JDBC）、前端 `api/sessions.ts` + `pages/SessionsPage.vue`（五个筛选维度 + 详情抽屉 + 深链），以及 `pages/StoragePage.vue` 的 `table`/`keyword` 深链支持 |

---

## 4. 关联设计（跨块怎么咬合）

### 4.1 channel ↔ agent：三条路径 + 一条优先级链

```
消息进来
  │
  ├─1. md_channel_binding 命中？
  │     按官方层级：peer > parent_peer > guild+roles > guild > team > account > channel
  │     同层内按 sort_order 升序取第一条 → agent_id
  ├─2. 未命中 → md_channel.default_agent_id
  └─3. 仍未命中 → md_gateway.main_agent_id（单行表）
              └─ 兜底：官方 ChannelRouter 内置常量 "main"
```

| 关系 | 怎么表达的 | 注意 |
| --- | --- | --- |
| 渠道 → agent | `md_channel_binding.agent_id`（多对一，可配多条规则指向不同 agent） | 绑定可以覆盖 `session_scope`（会话键范围），NULL = 用渠道级 `dm_scope` |
| 渠道 → agent（兜底） | `md_channel.default_agent_id` | 与网关主 agent 的优先级是「渠道兜底 > 网关主 agent」 |
| 网关 → agent | `md_gateway.main_agent_id` | NULL 时官方常量 `main` 会生效，运行时不存在该 agent → 这类配置错误已有专门的 warn 诊断 |
| 生效时机 | 保存 ≠ 生效：必须点「发布并生效」或「重载」 | 否则路由表里还是旧 ChannelConfig —— 这是排查「Agent 不存在」时最容易踩的一条 |

### 4.2 agent ↔ 元数据（prompt / skill / mcp / middleware / tool）

```
md_agent_ref (agent_name 主键)
   ├─ prompt_key   ──▶ md_prompt.prompt_key      （正文覆盖 sysPrompt）
   ├─ skill_names  ──▶ md_skill_meta.skill_name  （正文在官方 agentscope 库）
   ├─ mcp_names    ──▶ md_mcp_server.server_name （顺序即装配顺序）
   ├─ tools        ──▶ Spring 工具 bean 名       （只读目录，代码强相关）
   ├─ middleware   ──▶ 中间件 bean 名            （只读目录，代码强相关）
   └─ knowledges   ──▶ RAG Knowledge bean 名
```

| 关联 | 方向 | 保护机制 |
| --- | --- | --- |
| agent → prompt/skill/mcp | 单向引用（agent 持有名字清单） | 删除资源前反查 `md_agent_ref`：`AgentRefMapper` 用 `JSON_CONTAINS` 三个反查，命中则 409 + 列出引用方 |
| tool / middleware | **页面只能选、不能建** | 它们是 Spring bean，和代码强相关；后端提供只读目录接口（`/api/agents/tools/available`、`/resources.../middleware-catalog`） |
| mcp → 工具白/黑名单 | 名单**下沉到 MCP 级**，与连接成对传递（`McpServerBinding`） | 拆开传迟早出现「连接对了、名单串到另一个 MCP 上」；导入期若两个 agent 对同一 MCP 的白名单不同 → 记 ERROR 人工裁决，不静默取一个 |
| skill 内容 ↔ 治理表 | 内容在官方表，`md_skill_meta` 只放官方没有的治理字段 | `description` 是缓存；已规划「读取时用官方 name 集合做差集校验」防漂移（接口未暴露，见 §6） |

### 4.3 agent ↔ 运行时/工作区/子 agent：类型决定行结构

| agent_type | 有 md_agent_runtime | 有 md_agent_memory | 有 md_agent_workspace | 有 md_agent_sub_agent |
| --- | --- | --- | --- | --- |
| REACT | ✔（含三项技能策略） | ✔ | ✘ | ✔（子 agent 只允许 REACT） |
| HARNESS | ✔ | ✔ | ✔（工作区 + 能力开关） | ✘（它是编排体，不再当别人的子 agent） |

- `agent_type` **创建后不可变更**（后端硬校验 400）：改类型不是改一个字段，而是换整套行结构（REACT 没有 workspace 行与子 agent），草稿会丢掉一半配置，所以直接拒绝。
- 三项技能策略（`dynamic_skills_enabled` / `skill_code_execution_enabled` / `skill_work_dir`）从 workspace 表迁到 runtime 表：它们只定义在 `ReActAgent.Builder` 上，留在 workspace 表等于「REACT 的字段挂在 HARNESS 的表上」。
- 子 agent 装配走 `subagentFactory(name, agents::get)`：**只搬名字**，运行时新建立即可用。

### 4.4 发布 → 快照 → 热装配（版本闭环）

```
页面保存  → version+1（草稿态，运行时不变）
页面发布  → 校验（ControlPlaneValidator，收集式一次报全）
          → 写 md_resource_version 快照（六类资源共用一张表）
          → published_version = version / status = PUBLISHED
          → ControlPlanePublishService 触发**进程内热装配**
             （MCP/skill 的发布顺序固定：invalidate → 重建引用它的 agent）
页面回滚  → 用历史快照生成**新版本**（版本序列单向递增，可审计）
```

| 关注点 | 现状 |
| --- | --- |
| 快照粒度 | 只在**发布**时落（草稿不落，否则反复编辑灌爆快照表） |
| 生效范围 | 进程内热装配；**多实例下其他实例不会自动感知**（已知缺口，需要 Redis pub/sub 或 MQ） |
| 「谁引用了谁」 | 由 Store 在**事务内**校验（不用外键，见 §2.1） |

### 4.5 run ↔ 定义版本：**当前唯一断链的地方**

- run 表存了 `system_prompt` 正文快照与 `agent_kind`，但**没有记录 `agent_version` / `prompt_version`**。
- 后果：定义改过之后，无法回答「这条历史 run 当时用的是哪一版定义」——只能看到「当时的提示词正文是什么」。
- 这一条是控制面版本能力与观测能力之间**唯一还没接上的缝**，已列为下一个迭代。

---

## 5. 贯穿全项目的 11 条设计原则

| # | 原则 | 体现 |
| --- | --- | --- |
| 1 | **单一真相** | 元数据只有 MySQL 一份；skill 正文只有官方表一份；链路 MySQL 是权威、内存是缓存 |
| 2 | **声明式优先，不自造轮子** | 渠道绑定字段对齐官方 ChannelBinding；skill 用官方仓库；路由交给官方 ChannelRouter |
| 3 | **三态而非布尔** | 一批 `TINYINT(1)` NULL/0/1；页面用三选控件；装配层判空后再拆箱 |
| 4 | **不做隐式兜底，错误要显眼** | 不做外键/级联删除；对账不通过 = 零写入；白名单冲突记 ERROR 人工裁决；网关主 agent 缺失打 warn |
| 5 | **不可变的历史** | agent_kind 建 run 时冻结；快照只增不改；回滚生成新版本；事件 seq 产生即冻结 |
| 6 | **不阻塞 agent 线程** | 链路落库走有界队列丢尾 + 告警；SSE 通知用非阻塞信号；工具结果只记类型名不塞大对象 |
| 7 | **分层职责单一** | 中间件只观察不改写；通知器只唤醒不承载数据；MessageRouter 只取路由结论不做匹配 |
| 8 | **数据要能给人看** | 收尾信息（信号 / token / 事件计数）落 run 的 output 而不是只打日志；页面是唯一观测面 |
| 9 | **标识 ≠ 名字** | 六类元数据各有 `display_name` 与标识列并存（见 §2.1 约定 4）；ID 只作为业务数据出现（run.agent_id、渠道绑定的 agent_id、`md_agent_ref` 的引用值），页面上展示的是名字，标识降为次要索引 |
| 10 | **存储与业务分离，且表名是数据** | 会话状态单独一个库 `md_agent_state`（同实例跨库查询，不引入第二个 DataSource）；表名冻结在 `md_agent_runtime.state_table`，agent 改名不再产生孤儿表。**删状态不删链路** —— 上下文没了之后恰恰更需要链路留着（见 §2.4） |
| 11 | **能聚合出来的实体就不建表；三态就用三态的类型** | 会话是 run 按 `(user_id, session_id)` 的 GROUP BY，没有新表也没有新写入方（见 §2.5）；`hasState` 用 `boolean \| null` 而不是可空字符串 —— 这一维刚好会取到 `false`，而 `false` 是"有值"，用空串表达"不限"会让两个取值撞在一起（见 §3.8） |

---

## 6. 完成度一览

| 块 | 状态 | 说明 |
| --- | --- | --- |
| 控制面 7 表（agent 6 + model 1）+ Store + 校验 + 发布/回滚 | ✅ | `ControlPlaneGovernanceTest`、`MyBatisControlPlaneStoreIT`（真库，11 通过） |
| 装配层换源 DB | ✅ | `agents.source=db` |
| 控制面接口（CRUD/发布/回滚/只读目录） | ✅ | `AgentController` / `ResourceController` |
| 前端治理页（5 卡片页 + 编辑抽屉 + 引用下拉） | ✅ | 契约测试锁 method/path/载荷 |
| 渠道 + 网关控制面 | ✅ | 类型能力声明、绑定优先级、发布生效 |
| 运行链路：中间件 → Registry → 落库 → SSE → 成树 | ✅ | 300 后端测试（+11 真库 IT）/ 118 前端测试 |
| 会话聚合（会话记录页） | ✅ | 不加表：run 按 `(user_id, session_id)` 聚合；`GET /api/sessions[/detail]`；已真实联调（见 `docs/develop/2026-09-23_session-management.md`） |
| 会话状态分库 + 页面可管 | ✅ | `md_agent_state`；`GET/DELETE /api/state/**`；前端「状态存储」页已真实联调（含 413 超限分支） |
| 页面对话 SSE | ✅ | 每 agent 独立会话、markdown 渲染 |
| 消息路由 + 双平台渲染 | ✅ | 企微 / 飞书 |
| Nacos 依赖清理 | ⏸ | 你决定暂缓（保留以保证可回退） |
| 一次性导入器（Nacos/md → DB） | 🗑 | 2026-09-22 **已删除**：决定不迁移历史数据，改为页面手工建立 |
| 子 agent 事件 `subagent.dispatched/completed` | ❌ | 常量已定义、前端已能渲染，**后端不产出** |
| `POST /api/agents/{name}/invoke` | ❌ | 已被 `/api/agents/{id}/chat`（SSE）覆盖，可不做 |
| run ↔ 定义版本关联 | ❌ | 见 §4.5 |
| 触发来源 `source` | ✅ | 4 个入口写入（console / wecom / feishu / schedule）；存量 run 为 NULL，页面显示「未标注」 |
| skill 漂移校验接口 | ❌ | `listSkillContentNames()` 已在 Store，缺 controller |
| 鉴权 / CORS / TestController 改 JSON / 配置脱敏 | ❌ | 上线前必须补 |
| 模型控制面（`md_model` + 装配换源 + 连通性探测） | ✅ | 见 `docs/develop/2026-09-23_control-plane-model-and-overview.md` |
| 总览聚合（`GET /api/overview`） | ✅ | 单端点同时刻快照；同上一份文档 |
| 前端其余页面接真数据 | ⚠️ | 端点/环境/工作流 3 页仍是 mock（概览/模型 2026-09-23、会话 2026-09-23 已转真） |

---

## 7. 架构师阅读顺序（按这个顺序看，最省时间）

| # | 目标 | 先看 |
| --- | --- | --- |
| 1 | 数据模型全貌 | `md-agent-bootstrap/src/main/resources/db/control-plane-schema.sql` 头部注释（45 行内讲清 11 张表 + 3 条约定） |
| 2 | 「改一处怎么生效」 | `docs/develop/2026-09-21_control-plane-metadata.md` §7 装配时序 |
| 3 | 链路观测为什么这么设计 | `server/trace/AgentTraceEvent.java` 类注释（为什么是事件而不是阶段）+ `AgentTraceRegistry` 的 `record()` |
| 4 | 采集层的边界 | `AgentRunTraceMiddleware.java` 类注释（洋葱型/管道型、三个必须知道的坑） |
| 5 | 渠道路由为什么不自研 | `md-agent-bootstrap/src/main/resources/db/channel-schema.sql` §2 注释（优先级链 + 字段来源） |
| 6 | 前端怎么消费 | `agent-contro-plane/src/features/traces/{adapters.ts,useRunEvents.ts}` 与 `src/api/controlplane/contract.test.ts` |
| 7 | 会话状态在哪、怎么管 | `docs/develop/2026-09-22_state-store-refactor.md`（三代落点 → 分库决策 → 页面能力），以及 `md-agent-server/.../state/AgentStateAdminService.java` 的类注释（为什么只读 + 只删） |
| 8 | 会话（人）这一层怎么来的 | `docs/develop/2026-09-23_session-management.md`（为什么是聚合而不是建表 → 五个筛选维度 → 两个库的 join 键），以及 `md-agent-server/.../session/AgentSessionMapper.java` 的类注释（为什么聚合不做 SQL 分页） |
| 9 | 模型为什么进 DB、总览为什么要单端点 | `docs/develop/2026-09-23_control-plane-model-and-overview.md`（四级解析 → 内容戳缓存 → 三个口径），以及 `md-agent-server/.../model/DbModelFactory.java` 的类注释（为什么缓存不靠事件、停用为什么直接抛） |
