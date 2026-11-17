# 会话记录：把「人」这一层还回来（run 按 (user, session) 聚合）

> 整理时间：2026-09-23。面向架构评审与后来者：每一块回答三个问题 —— **解决什么问题、关键取舍是什么、代码落在哪里**。
> 验证基线：后端 `mvn -o test` **300 通过**（server 277 + bootstrap 23）；前端 `vue-tsc` 0 错、`vitest` **116 例**、`vite build` 通过。
> 真实联调：`/api/sessions`、`/api/sessions/detail`、`source=console` 的端到端写入、`/storage` 深链，均在运行中的后端 + 前端 dev server 上验过。

---

## 0. 一句话

**会话不是一个实体，是 `md_agent_run` 按 `(user_id, session_id)` 的聚合。**
所以这一轮只加了 **一列 + 一个索引 + 两个只读接口 + 一个页面**，没有新表、没有新写入方。

这样定，是因为 run 表里已经有两列把这件事说清楚了：一次调用 = 一行 run（= 一棵调用树的根），
同一串 run 的 `(user_id, session_id)` 相同。GROUP BY 这两列就是会话。
反过来，为会话单开一张表，就多了一个必须与 run 保持同步的地方 ——
而同步不上的表现是「链路里明明有一次执行，会话列表里却没有」，
那比没有这一页更糟：一个安静的假象比一个明显的缺口更难查。

---

## 1. 问题：调用链路看得见，但「人」看不见

改造前的现状是三个具体现象：

| # | 现象 | 根因 |
| --- | --- | --- |
| 1 | 想知道「这个人一共聊过几轮」只能去 run 列表里翻 | 链路页是按 run（一次执行）平铺的，没有「同一个会话的多次执行」这个视角 |
| 2 | 想知道「这条会话的记忆还在不在」要切到状态存储页再搜一次 | 状态存储页按表组织，不按人组织；而两个库之间没有可点的跳转 |
| 3 | 「这条会话是谁在服务」只能看到单个 agent | `agent_kind` / `agent_name` 在 run 上是单值，而**同一会话会被多个 agent 服务** |

第 3 点是最容易被忽略、也最容易做错的一条，见 §4.1。

---

## 2. 数据模型：只加一列、一个索引

### 2.1 `md_agent_run` 新增 `source`

```sql
ALTER TABLE `md_agent_run`
    ADD COLUMN `source` VARCHAR(32) DEFAULT NULL
        COMMENT '触发来源：console | wecom | feishu | schedule；NULL=入口方没写'
        AFTER `user_id`,
    ADD KEY `idx_user_session` (`user_id`, `session_id`);
```

取值是闭集，常量在 `AgentTraceRun`：

| 常量 | 值 | 谁写 |
| --- | --- | --- |
| `SOURCE_CONSOLE` | `console` | `ChatStreamService`（页面对话，`/api/agents/{id}/chat`） |
| `SOURCE_WECOM` | `wecom` | `WeComThinkRenderer`（企微侧渲染） |
| `SOURCE_FEISHU` | `feishu` | `FeishuCardRenderer`（飞书侧渲染） |
| `SOURCE_SCHEDULE` | `schedule` | `DailyReportSendWxJob`（定时任务） |

### 2.2 为什么 `source` 必须由**入口方**写下来

这是本轮最值得记的一个取舍。来源看起来可以从 run 已有的列**反推**出来
（比如 `sessionId` 的形状：`single-<userId>-<date>` 像企微、`console-<hash>` 像页面）。
不做，有两个理由：

1. **反推规则会随入口演进失效**。`sessionId` 是各入口自己算的约定，不是本项目的契约；
   改一次生成写法，所有历史数据的反推结果会**静默变化** —— 昨天读出来是企微，今天读出来是控制台，
   而没有任何一条报错。
2. **链路观测数据"看起来有值但是错的"比空着更糟**。空值（NULL）读者知道是"没记"，
   做排查时会去找别的线索；一个错的来源会被当成事实，把排查带向错误的方向。

所以做法是：**入口方在发起调用前，把来源写进 `RuntimeContext`，采集层原样读到 run 上。**
实现上是与 `runId` 完全对称的一对方法（`AgentTraceRegistry.SOURCE_ATTR` / `attachSource` / `peekSource`）：

```
入口方（4 处）  ctx.put(SOURCE_ATTR, SOURCE_XXX)
                      │
                      ▼
采集层 AgentRunTraceMiddleware.onAgent()
   registry.start(..., AgentTraceRegistry.peekSource(ctx))   ← 原样读，不推断
                      │
                      ▼
              md_agent_run.source
```

**为什么不做成"没写就按 console"的兜底**：兜底会把"入口方忘了写"这个缺陷
永远藏起来 —— 加了新入口、漏了写来源，页面照样显示 console，没人会发现。

### 2.3 `idx_user_session` 为什么是 `(user_id, session_id)` 而不是只 `session_id`

`sessionId` 由入口方算，**不保证全局唯一**：群聊用 chatId、单聊用 userId、页面对话用随机短 id。
只按 `session_id` 建索引，两条不同用户的同名会话就会共用一个索引桶、共用一份缓存、
在聚合时还可能被分到一起。`(user_id, session_id)` 才是这一页真正的分组键。

> 注意：这里 `session_id` 上原本已有一个单列索引 `idx_session`（另一个用途：按会话查 run 列表）。
> 新索引是给「按用户 + 会话聚合」用的，两个都留。

### 2.4 存量数据为什么保持 NULL（不回填）

升级前产生的 run，`source` 一律 NULL，**不猜不回填**：
能用的线索只有 `sessionId` 的形状，而回填结果与事实不符的代价见 §2.2。
页面上的表现是「未标注」——一个明确的、可解释的状态。

---

## 3. 接口

### 3.1 `GET /api/sessions` —— 会话列表

| 参数 | 类型 | 口径 |
| --- | --- | --- |
| `agentId` | string | **"被这个 agent 服务过"**（不是"等于"）。见 §4.1 |
| `source` | string | 精确匹配来源；空 = 不限 |
| `from` / `to` | long（epoch millis） | 比的是**最近一次活动**，不是"把窗口内那几次挑出来"（见下） |
| `keyword` | string | 同时匹配 `userId` 与 `sessionId` 的子串 |
| `hasState` | boolean（**三态**） | `true` 只看还有记忆的 / `false` 只看没记忆的 / 不传 = 不限 |
| `limit` / `offset` | int | 默认 20，上限 200 |

响应：

```json
{ "total": 15, "allTotal": 15,
  "items": [{ "userId": "wo7U1", "sessionId": "single-abc",
    "runs": 6, "failed": 1, "tokens": 40280,
    "agents": ["deployment_agent", "workwx_batch_agent"],
    "sources": ["wecom"], "firstMs": 1790064819160, "lastMs": 1790066317176,
    "hasState": true, "stateTables": ["test_agent"] }] }
```

| 字段 | 为什么长这样 |
| --- | --- |
| `agents` / `sources` 是**数组** | 一条会话会被多个 agent 服务、可能有多个来源；`GROUP_CONCAT(DISTINCT ... ORDER BY ...)` 出去，服务层切成列表。`ORDER BY` 是必要的 —— 不加则拼接顺序由执行计划决定，"两次打开显示的顺序不一样"会让人以为数据变了 |
| 时间是 **epoch millis** | 与 run 表同口径；前端要拿它做相对时间与时间窗比较，给 ISO 字符串反而要再解析一次 |
| `total` 与 `allTotal` 分开 | 页面要显示「筛选出 X / 共 Y 个会话」。只给一个数，用户分不清是"筛完了"还是"还没翻页" |
| 没有写接口 | 改一条会话等于改它底下的一批 run，而 run 是历史事实、只增不改 |

**时间窗比的是「最近一次活动」，不是"窗口内有跑过"**：
后者会让同一条会话在两次筛选里显示出不同的执行次数（因为聚合值也跟着被裁了），那更容易误解。

### 3.2 `GET /api/sessions/detail` —— 一次会话的执行时间线 + 记忆清单

| 参数 | 说明 |
| --- | --- |
| `userId` | **可以不传**（库里有 `user_id` 为 NULL 的会话），查询走 MySQL 的 NULL 安全等值 `<=>` |
| `sessionId` | 必填；缺了直接 400，而不是"查全部" |

之所以把"时间线"与"记忆清单"放在**同一个响应**里：页面上它们是同一个抽屉的两块内容，
分两个接口只会让"打开抽屉"变成两次往返 + 两次 loading 状态。
时间线上限 200 次（会话只增，跑了一年的定时任务会话可能有几千次执行）。

### 3.3 为什么 `userId` / `sessionId` 是查询参数而不是路径段

与状态存储页同一个理由：`sessionId` 由各入口自己算，形态不受本项目控制（可能含冒号、斜杠、中文），
而 `userId` **允许为空** —— 路径段表达不了"这一段是空的"，查询参数可以。

### 3.4 为什么每个方法都 `subscribeOn(boundedElastic)`

本项目是 **WebFlux**（请求跑在 Netty 事件循环线程上），而底下全是阻塞的 JDBC。
在事件循环线程上做阻塞查询会把整条事件循环卡住 —— 表现是"查一次会话，所有接口一起变慢"。
`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 是给阻塞调用准备的包装，
顺带让服务层抛的异常走 `onError` 而不是直接冒到事件循环上。

---

## 4. 两个必须记住的口径（最容易错的地方）

### 4.1 agent 是**筛选维度**，不是归属维度

实测：企微里同一条会话先交给 `workwx_batch_agent` 又交给 `deployment_agent`，**`sessionId` 没变**
（因为 sessionId 由入口方算、里面不含 agent）。

于是：

- `agents` 必须是**列表**，页面上把每个都显示出来；
- `agentId` 筛选的语义是「凡是被这个 agent 服务过的会话」，用 `contains` 而不是 `equals`；
- 子串不算命中：`deployment` 筛不到 `deployment_agent`（`agentId` 是精确匹配的标识）。

如果哪天有人把它改成"取第一个"或"取最后一个"，页面会开始撒谎 ——
而它**看起来完全正常**：一列一个名字，谁会怀疑少了几个。所以这条必须有单测钉死
（`AgentSessionAdminServiceTest#同一会话被多个agent服务时agents是完整列表且各自都能筛到它`）。

### 4.2 两个库的 join 键

```
md_agent_run.session_id  = single-wo7U1ZEQAApziTCwT6jow9UKLs0xrqlw-2026-09-22
md_agent_run.user_id     = wo7U1ZEQAApziTCwT6jow9UKLs0xrqlw
md_agent_state.<table>.session_id = wo7U1ZEQAApziTCwT6jow9UKLs0xrqlw:single-wo7U1ZEQ…-2026-09-22
```

**必须用 `(user_id, session_id)` 两列对齐，不能直接比字符串。**
拼法（`userId + ":" + sessionId`）只有一处实现：`AgentStateAdminService.slotId(userId, sessionId)`
（与框架 `MysqlAgentStateStore` 同一个拼法）。它被两个包共用，就是为了让"两个库之间唯一能对齐的键"
不可能被写歪 —— 拼错了不会报错，只会让所有会话都显示"没有记忆"。

### 4.3 状态库读不到时，页面必须还能用

「记忆」只是会话行上的一列标记，而列表的其余内容全在业务库里。
状态库连不上时服务层吞掉异常、只记日志、把标记降级成"无记忆" ——
与链路观测的既定原则一致（观测是旁路能力，写不进去不该把业务搞挂）。
反过来做的话，一个辅助标记就能让整页 500。

---

## 5. 前端页面（`agent-contro-plane`）

### 5.1 列表（10 列）

`用户 · 会话 · 记忆 · 服务过的 agent · 来源 · 执行 · 成功率 · Token · 最近活动 · 操作`

| 列 | 要点 |
| --- | --- |
| 用户 | `user_id` 为 NULL 时显示**「（未分区）」**，不留白 —— 留白会被读成"页面坏了"，而它是库里的正常状态 |
| 记忆 | 徽标「有 / 无」，来自跨库那一步 |
| 服务过的 agent | **多个都列**（`tag-chip` 逐个渲染），展示名走 `agentDisplayName`（回落规则只有一处） |
| 来源 | 翻成中文（`wecom` → 企业微信）；一次都没标注过时显示「未标注」 |
| 成功率 | `(runs - failed) / runs`；`runs` 为 0 时给 0 而不是 NaN（NaN 会渲染成 "NaN%"） |
| 最近活动 | 相对时间 + 绝对时间（`2 小时前 · 2026-09-22 23:28`） |

### 5.2 筛选：五个维度**全部在服务端**

这轮把「有没有记忆」做成了**服务端参数**（`hasState`），而不是让页面在当页数据上本地筛。
理由是分页：在当页那 20 行上筛，用户看到「共 57 个会话、筛出 3 条」时
无法判断那 3 条是全部符合的，还是只是第一页里符合的。分页与服务端筛选是同一个决定的两半。

代价与取舍：

| 取舍 | 说明 |
| --- | --- |
| chips **不带条数** | 另外两页的 chips 带条数是因为数据全在前端（一次拉回、本地筛）。这里的三个数只有后端知道，从当页算出来的条数会与「共 N 个会话」互相矛盾 —— 索性只给状态、不给数字 |
| `hasState` 用 **三态**（`boolean \| null`） | 这一维刚好会取到 `false`，而 `false` 在 `buildUrl` 里是"有值"不会被丢掉。用可空字符串表达"不限"，第二个和第三个取值就会撞在一起 |
| 「全部」用 `null`、不是空串 | 同上 |
| 时间窗转 epoch millis 再传 | 后端不再解析日期字符串，也就不会出现"这个时间按谁的时区算"的争议 |

页面把**同一个查询对象**同时喂给 `queryKey` 与 `queryFn`（`queryKeys.sessions(query)`）——
拆成两份的写法迟早会出现"键变了但请求没变"，而那种 bug 的表现是"点了筛选，结果没动"，没人会先怀疑缓存。

### 5.3 详情抽屉

头部：用户 + 会话 + 记忆徽标 + 「查看记忆」+ 关闭。
正文两块：

1. **执行时间线**：每次执行一行（开始时刻 / 运行 ID / Agent / 来源 / 状态 / 耗时 / Token）。
   点运行 ID 跳到 `/traces/{runId}` 看那棵树是怎么走的。失败原因显示在状态徽标下方
   （只给一个「失败」徽标等于什么都没说），过长时 CSS 截断 + `title` 悬浮看全文。
2. **记忆（状态存储）**：这个会话落在哪几张状态表里、各多少行、最后写入时间。

### 5.4 跳到状态存储页时**带上参数**

「查看记忆」跳 `/storage?table=<表名>&keyword=<sessionId>`：

- 带 `table`：状态存储页默认选中「第一张有数据的表」，与本会话的记忆未必是同一张；
- 带 `keyword`：那一页的会话列表也是**服务端分页**的，一条会话可能落在第二页。

配套地，状态存储页新增了深链支持（`StoragePage.vue` 的 `pinnedTable` / `pinnedKeyword`）：

- **只在初始化时读一次**，不跟着 URL 持续同步 —— 用户在本页改了自己的选择之后，
  让地址栏再把它拽回去是最典型的"页面自己动"；
- 深链的关键字要当作**初值**写在 `useDebounce` **之前**：`refDebounced` 是用源 ref 的当前值
  初始化输出的，写在后面的话输出会先是空串、300ms 后才补上关键字，那期间页面会先发一次
  不带关键字的请求。

---

## 6. 实现清单

### 6.1 后端（`Md-Agent-Platform`）

| 文件 | 改动 |
| --- | --- |
| `db/trace-schema.sql` | `md_agent_run` 加 `source` 列 + `idx_user_session`；文件头补"为什么必须由入口方写下来" |
| `db/trace-schema-upgrade-2026-09-23-source.sql` | **新增**增量升级脚本（注释形态的 ALTER + 存量保持 NULL 的理由） |
| `server/trace/AgentTraceRun.java` | `SOURCE_*` 常量、`source` 字段、`normalizeSource()`；构造函数 6 参 → 7 参 |
| `server/trace/AgentTraceRegistry.java` | `SOURCE_ATTR` / `attachSource` / `peekSource`（与 `RUN_ID_ATTR` 完全对称）；`start(...)` 新增 7 参重载，保留旧重载 |
| `server/trace/store/entity/AgentRunEntity.java`、`MyBatisTraceStore.java` | `source` 字段贯通 |
| `server/trace/TraceRunInfo.java` | 删掉硬编码的 `SOURCE_UNKNOWN`，改读真实值 |
| `server/trace/AgentRunStatus.java` | **新增**：后端状态 → 前端闭集/状态色的唯一出口（原来私有在 `TraceRunSummary` 里，会话详情也要用） |
| `server/agent/middleware/AgentRunTraceMiddleware.java` | `onAgent` 里 `start(...)` 多传 `peekSource(ctx)` |
| `server/chat/ChatStreamService.java` | `SOURCE_CONSOLE` |
| `server/messaging/render/WeComThinkRenderer.java` | `SOURCE_WECOM` |
| `server/messaging/render/FeishuCardRenderer.java` | `SOURCE_FEISHU` |
| `server/schedule/DailyReportSendWxJob.java` | `SOURCE_SCHEDULE` |
| `server/session/`（**新增包，5 个类**） | `SessionAggregateRow` / `SessionRunRow`（映射模型）、`AgentSessionMapper`（两条只读 SQL）、`AgentSessionViews`（输出 record）、`AgentSessionAdminService`（筛选 + 翻译 + 跨库对齐） |
| `controller/session/SessionController.java` | **新增**：`GET /api/sessions`、`GET /api/sessions/detail` |
| `server/state/AgentState*` | 加 `slotTables()` / `sessionStates(userId, sessionId)` / **`public static slotId(userId, sessionId)`**（跨包共用唯一的拼法） |

### 6.2 前端（`agent-contro-plane`）

| 文件 | 改动 |
| --- | --- |
| `src/api/sessions.ts` | **整体重写**：类型 + `fetchSessions` / `fetchSessionDetail`，无 mock 分叉，文件头写明两个口径 |
| `src/pages/SessionsPage.vue` | **新增**（662 行，含大段"为什么"注释） |
| `src/lib/queryKeys.ts` | `sessions(filter)` 带全部 7 维；`sessionDetail(userId, sessionId)` 带 userId |
| `src/pages/StoragePage.vue` | 深链支持（`table` + `keyword`） |
| `src/api/types.ts` | 删掉 `SessionSummary` / `SessionSnapshot`（旧的假数据形状） |
| `src/api/mock/dataset.ts` | 删掉 `sessions` / `sessionSnapshots` 及 missingEndpoints 里的 `GET /api/sessions` |
| `src/features/traces/model.ts` | `TraceRunInfo.source` 改成 `string \| null` |
| `src/pages/routes.smoke.test.ts` | 加两个夹具（`SessionPage` / `SessionDetail`）+ 4 条断言 |

---

## 7. 验证

### 7.1 自动化

| 层 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `mvn -o test` | **300 通过**（server 277 + bootstrap 23） |
| 后端聚焦 | `-Dtest="AgentSessionAdminServiceTest,SessionControllerTest"` | 8 + 3 通过 |
| 前端类型 | `vue-tsc --noEmit` | 0 错 |
| 前端测试 | `vitest run` | **116 例**（其中 routes.smoke 41 例） |
| 前端构建 | `vite build` | 通过（SessionsPage chunk 14.0 kB / gzip 5.6 kB） |

新增测试覆盖的**都是"错了不会报错"的那一类**：

- 同一会话多 agent → `agents` 是完整列表，且每个 agent 都能筛到它（`contains` vs `equals` 的边界）；
- 记忆标记用 `slotId` 对齐状态库（拼错不会报错，只会全部显示"没有记忆"）；
- 状态库读失败降级为"无记忆"而不是整页失败；
- `hasState` 三态不与"不限"混淆（Java 里只差一个自动拆箱）；
- 分页边界（`limit=0` / 负数 / 超界 offset）；
- HTTP 契约：`hasState` 走 `Boolean`（写成 String 的话 `"false"` 是非空字符串，HTTP 上看起来完全正常）；
- 前端：`hasState=true|false` 真的出现在请求 URL 上（**筛选在服务端**这件事唯一能被机器验到的证据）；
- 前端：深链的 `table` / `keyword` 真的被用上（夹具特意让"深链点名的表"与"默认会选中的表"不同）。

### 7.2 真实联调（2026-09-23 实测）

| 动作 | 结果 |
| --- | --- |
| `GET /api/sessions?limit=3` | 200，`total=15 / allTotal=15` |
| `GET /api/sessions?hasState=true` | 200，`total=1`（对照组 `hasState=false` → `total=14`） |
| `GET /api/sessions?keyword=wo7U1` | 200，`total=2` |
| `GET /api/sessions/detail?userId=codex&sessionId=state-test-1` | 200，1 次执行 + 1 张状态表 |
| 15 条会话的 detail 逐条计时 | 全部 197~272 ms，响应体 257~1335 B（**无慢查询**，此前 5.19 MB 大会话 15 s 的情况在本轮样本里已不存在——那条会话的状态已被清空） |
| `POST /api/agents/test_agent/chat` 后再查 `?source=console` | 200，`sources: ["console"]` —— **`source` 链路端到端通**（模型侧 402 欠费，run 仍以 `failed` 落库） |
| 页面渲染 | `/sessions` 在 running 的 dev server 上渲染出 15 条真实数据：agent 显示名（`测试智能体`）、记忆徽标、成功率、Token、相对+绝对时间、分页页脚均正确 |

---

## 8. 已知边界与技术债

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | **存量 run 的 `source` 全是 NULL** | 升级前的数据不猜不回填（§2.4）。页面显示「未标注」；想看「按来源筛」的效果需要新产生几条 run |
| 2 | 子 agent 事件 `subagent.dispatched/completed` 后端仍不产出 | 与本轮无关，见架构总览 §6 |
| 3 | run 未记 `agent_version` / `prompt_version` | 见架构总览 §4.5，仍是唯一的断链处 |
| 4 | 会话列表每次都要全量扫一遍 run 表 | 筛选维度里有"有没有记忆"（物理上在另一个库），SQL 表达不出来，所以统一在内存里筛。代价是每次扫全表（走 `idx_user_session`），会话数与 run 数都在"每次调用几行"的量级，可接受；真的长大了要先想到"把记忆标记物化到 run 表"这条退路 |
| 5 | `GROUP_CONCAT` 有 `group_concat_max_len`（默认 1024 字节）截断风险 | 一个会话最多被几个 agent 服务，实际远达不到；真撞上只会让这一列少几个名字，不影响其它字段与筛选 |
| 6 | 前端仍有 6 页 mock | 概览 / 模型 / 端点 / 环境 / 工作流（会话页本轮已转真） |

---

## 9. 后续可做

| 优先级 | 事项 | 价值 |
| --- | --- | --- |
| 高 | 概览页改用真实聚合 | 现在它还是 mock，而 /api/sessions 已经能给出"会话数 / 执行数 / 成功率"这类口径 |
| 高 | run 落 `agent_version` / `prompt_version` | 补上 §4.5 的断链：能回答"这次执行用的是哪一版提示词" |
| 中 | 会话列表加"按来源分布"的 chips 条数 | 需要后端在聚合结果上再统计一次，属于同一个内存遍历 |
| 中 | 状态存储页与会话页互相跳转的反向链 | 现在只有"会话 → 记忆"，缺"记忆 → 这条会话跑过什么" |
| 低 | 会话的可读别名 / 备注 | 需要新表，且要解决"谁有权改"；在没有真实需求前不做 |
