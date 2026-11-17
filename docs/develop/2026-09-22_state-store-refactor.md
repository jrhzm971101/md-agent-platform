# 会话状态存储改造：从「散落在业务库 / 本地文件」到「独立状态库 + 页面可管」

> 整理时间：2026-09-22（收尾于 09-23 凌晨）。面向后续维护者：回答 **为什么改、改了什么、怎么验证、还剩什么**。
> 涉及两个仓库：后端 `Md-Agent-Platform`（md-agent-server / md-agent-bootstrap），前端 `agent-contro-plane`。

---

## 0. 一句话结论

会话状态（agent 的「记忆」）从**业务库里的 11 张散表 + 本地文件目录**，迁到**同一个 MySQL 实例上的独立库 `md_agent_state`**：一个 agent 一张表、表名冻结在 `md_agent_runtime.state_table`，并由前端「状态存储」页直接查看与清空。旧的 state 数据、旧库、旧文件目录**全部作废**（按决策放弃，非迁移）。

---

## 1. 为什么改：三代落点盘点

状态存储的位置在项目里换过三代，每一代的问题都不一样。搞清楚这段历史，才能理解这次为什么是「分库 + 冻结表名」而不是别的方案。

| 代 | 落点 | 当时为什么这么选 | 暴露的问题 |
| --- | --- | --- | --- |
| 一 | 本地文件 `C:\Users\User\.agentscope\state`（框架默认文件 store） | 零配置、单机跑得通 | 多实例各写各的；无法查询；没有保留策略；容器化后直接丢失 |
| 二 | 业务库 `md-agent-platform` 里的 11 张状态表（框架自建） | 换成 MySQL store 解决了「多实例不一致」 | **状态表混在业务库里**：备份/清库/权限/容量都被状态数据绑架；表名 = agent 名，agent 一改名就产生孤儿表；页面看不到也管不了 |
| 三（本次） | **独立库 `md_agent_state`** + 冻结表名列 | 存储与业务分开；表名与 agent 名解耦；控制面页面对状态有可视性与删除权 | 见 §7「未做与遗留」 |

补充一个容易混的点：项目里存在**两个 state 相关的库名**。

- `state_store`：早期遗留库，本次 **DROP**。
- `md_agent_state`：本次**新建**，现役库。

---

## 2. 决策（已拍板，不再讨论）

| # | 决策 | 理由 |
| --- | --- | --- |
| 1 | 状态库名 = `md_agent_state` | 与业务库同实例 → SQL 里写全限定名即可跨库，**不需要第二个 DataSource**，也就没有跨库事务问题 |
| 2 | 之前的 state **全部舍弃**，并清掉业务库里的 state 表 | 旧的都是测试/试用数据，迁移成本 > 价值；先备份再删（§3） |
| 3 | 表名**冻结**：新增列 `md_agent_runtime.state_table` | agent 改名不再产生孤儿表；「表名是数据、不是常量」这一点在 SQL 层也成立（见 §4.4） |
| 4 | 页面删会话**只删 state**，不碰 run / trace | 状态是「agent 下一步要用的东西」，链路是「当时到底发生了什么」；上下文清空后恰恰更需要链路留着 |

关于第 3 条中「表名」与「agent 名」的解析规则（**装配期与页面查询必须完全一致**，否则页面会把正在使用的表标成孤儿）：

```
实际表名 = COALESCE(NULLIF(md_agent_runtime.state_table, ''), md_agent_runtime.agent_name)
```

---

## 3. 数据迁移与清理（不可逆动作，均已执行）

### 3.1 先备份

备份目录 `logs/state-archive-20260922/`，共 **24 个文件 / 77.2 MB**：

- `_schema-before-cleanup.sql`：删除前的完整 DDL
- 23 个 `.jsonl`：`<来源库>__<表名>.jsonl`，每行一条 `state_data` 原文

| 来源 | 表数 | 行数 | 备注 |
| --- | --- | --- | --- |
| `md-agent-platform`（业务库） | 11 | 31 | 含 `workwx_doc_agent` 14.2 MB |
| `state_store`（遗留库） | 12 | 84 | 含 `workwx_doc_agent` **55.8 MB**（单表） |

### 3.2 再清理

- 删除业务库 `md-agent-platform` 里 11 张状态表 → 该库现在 **15 张表、全部 `md_*` 前缀**，不再混有状态数据
- `DROP DATABASE state_store`
- `CREATE DATABASE md_agent_state`（空库，表由框架首次使用时自建）
- `md_agent_runtime` 新增列：

```sql
state_table VARCHAR(128) NULL COMMENT '状态表名（冻结，改名不改它）；state_store 非空时生效，空则回落 agent_name'
```

- 现役 agent 指向新库：`test_agent` / `test_harness` → `state_store='mysql'`、`state_table=<同名>`
- 文件态归档：`C:\Users\User\.agentscope\state` → **`state_legacy_20260922`**（8 个 agent / 19 文件 / 1.6 MB）

> ⚠️ 文件目录被移走后，**老的本地文件上下文已不可达**（决策 2 已授权舍弃）。若某次排查确实需要，从 `state_legacy_20260922` 里按目录名还原即可。

### 3.3 建表由框架负责，但 DDL 已对齐

框架在首次用到状态时会 `CREATE DATABASE IF NOT EXISTS` + `CREATE TABLE`。我们**手工建过一份同样的表做过对照**，确认框架 DDL 与手工 DDL 镜像一致，所以事后手工建表不会与框架冲突。

---

## 4. 后端改动清单

### 4.1 配置与模型

| 文件 | 改动 |
| --- | --- |
| `md-agent-bootstrap/src/main/resources/application.yml` | 新增 `agent-platform.state-store-database: md_agent_state` |
| `md-agent-server/.../config/AgentPlatformProperties.java` | 新增字段 `stateStoreDatabase = "md_agent_state"` |
| `md-agent-server/.../controlplane/store/entity/AgentRuntimeEntity.java` | 新增 `stateTable` |
| `md-agent-server/.../agent/AgentDefinition.java` | 新增 `stateTable` |
| `md-agent-server/.../agent/AgentDefinitionResolver.java` | `def.setStateTable(rt.getStateTable())` |
| `md-agent-bootstrap/src/main/resources/db/control-plane-schema.sql` | 新增 `state_table` 列 + 更新 `state_store` 注释 |

### 4.2 装配期：`ConfigurableAgentBuilder.resolveStateStore`

所有 agent 的唯一装配入口。这里同时做了三件事：

1. **库名**取配置（`md_agent_state`），不再写死业务库；
2. **表名**取 `state_table`，为空回落 agent 名；
3. **白名单**校验 `STATE_TABLE_PATTERN = [A-Za-z0-9_-]{1,64}` —— 装配期拦一次（防止带着坏配置启动），接口层再拦一次（§4.4）。

### 4.3 新增的管理面代码（server）

| 文件 | 职责 |
| --- | --- |
| `state/StateTableRow.java` / `StateSlotRow.java` | 映射模型（跟着表结构走） |
| `state/AgentStateViews.java` | 输出模型（跟着页面要什么走）；一律 record |
| `state/AgentStateMapper.java` | 只读查询 + 按会话删除；动态表名用 `${}` |
| `state/AgentStateAdminService.java` | 守卫（白名单 + information_schema 存在性）+ 翻译（拼串拆列、大 JSON 压摘要） |
| `state/StateStoreException.java` | `Kind = INVALID / NOT_FOUND / TOO_LARGE` |

**为什么映射模型与输出模型分两份**：合成一个类会演化出「为了页面好看而给实体加字段」的反向依赖，加着加着实体就不再是表的样子。

### 4.4 接口层（bootstrap）

`controller/state/StateStoreController.java`（`/api/state/**`）+ `StateStoreExceptionHandler.java`（400 / 404 / 413）。三个值得记住的决定：

1. **`table` / `slotId` 用查询参数而不是路径段** —— `slotId` 的真实形态是 `<userId>:<sessionId>`，含冒号；放进路径段就要先赌一次「`encodeURIComponent` 与 Spring 路径匹配在冒号上的行为」。
2. **每个方法都 `subscribeOn(Schedulers.boundedElastic())`** —— 项目是 WebFlux（请求跑在 Netty 事件循环上），而下面是阻塞 JDBC。不在事件循环上做阻塞查询，否则「查一次状态，所有接口一起变慢」。
3. **只有删除，没有写接口** —— 见 §4.5。

**接口清单**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/state/database` | 状态库名 |
| GET | `/api/state/tables` | 表清单（含孤儿表与「已声明未建表」） |
| GET | `/api/state/sessions?table=&keyword=&limit=&offset=` | 会话列表（分页） |
| GET | `/api/state/sessions/detail?table=&slotId=` | 会话详情（解析后的摘要，**不含原文**） |
| GET | `/api/state/sessions/raw?table=&slotId=` | 原文 JSON（**>4 MB 返回 413**，不截断） |
| DELETE | `/api/state/sessions?table=&slotId=` | 删除该会话的全部状态行 |

**`${}` 注入的两道闸门**（缺一不可）：

1. 表名白名单正则；
2. 表名必须在 `information_schema.tables` 里真实存在。

两条都过才拼进 SQL；库名来自配置，不是用户输入。

### 4.5 为什么不做编辑

`state_data` 是框架内部对象（`AgentState`）的序列化结果，字段随框架版本演进（实测已含 `context` / `tasks_context` / `plan_mode_context` / `tool_context` 四套子结构）。页面写回一份「看起来对」的 JSON，风险是让框架反序列化出一个**半损坏**的状态，而且失败要等到**下一次对话**才暴露 —— 那时谁也联系不到「那天在页面上改过一次」。

**删除是幂等的、可解释的；编辑不是。**

### 4.6 改完为什么不用重启

框架在**每次 call 的开头**都会从 store 重新加载该会话的状态（`ReActAgent.activateSlotForContext` 注释原文：*state is always reloaded from the store at the beginning of each call*）。所以页面删完，下一次对话就是全新上下文。

这条必须写在页面上 —— 否则没人敢点删除（怕删完要重启，或者怕删了不生效）。

---

## 5. 两个实测出来的缺陷（都不是假想风险）

### 5.1 `rows` 是 MySQL 保留字

统计 SQL 里 `COUNT(*) AS rows` 直接语法报错（`ROWS` 是窗口函数关键字）。改法：`AS \`rows\``。**两处都要改**，漏一处就 500。

### 5.2 详情响应体可被单个大字段撑爆

详情接口的整个存在意义是「不要把 MB 级的 `state_data` 传给浏览器」。但摘要里的**单个字段本身也可能极大**：实测构造一条 5 MB 的 `summary` 时，详情响应体变成 **5.4 MB**，上限形同虚设。

改法：字段级裁剪（字符数），各自与用途匹配：

| 字段 | 上限 |
| --- | --- |
| `summary` | 2000 |
| 任务标题 / 工具组名 | 200 / 50 条 |
| `planFile` 路径 | 512 |
| 短标识（权限模式等） | 64 |
| 消息预览 | 最多 20 条 × 400 字 |

修复后同一会话详情响应体：**5,414,256 B → 7,691 B**（实测）。

---

## 6. 前端：「状态存储」页（`/storage`）

这一页 2026-09-22 前是**硬编码的部署形态盘点文案**（「状态在本地文件，MySQL 只给工具用」）——那是当时的事实，一旦状态搬进 `md_agent_state` 就变成一句理直气壮的假话。本次重写为真实状态管理器。

### 6.1 页面结构

```
状态存储
├─ 指标条：状态库 / 状态表 / 会话 / 占用空间
├─ 左：会话列表（关键字搜索 + 分页 + 每行删除）
│    └─ 列：用户 / 会话 / 状态行 / 大小 / 版本 / 最近写入 / 操作
└─ 右：状态表清单（点击切换左栏）
     └─ 每项：表名 + 会话数 + 体积 + 最后写入
         · exists=false → 徽标「尚未创建」
         · agentName=null → 徽标「孤儿表」
         · agentName ≠ tableName → 额外显示 agent 名（正是「表名冻结」要解决的场景）

详情抽屉：槽位清单 / 上下文摘要 / 任务清单 / 消息预览 / 原始 state_data（按需加载）
```

### 6.2 三个刻意的取舍

| 取舍 | 理由 |
| --- | --- |
| **详情不直接给原文** | 实测单个会话最大 5.19 MB。点一次详情就传一份完整上下文，页面会卡、流量白花。原文是单独的、按钮触发的动作 |
| **原文超限直接 413，不截断** | 截断后的 JSON 不是合法文档，前端再解析只会报一个更难懂的错误 |
| **关键字走服务端、输入防抖 300ms** | 每敲一个字母发一次请求，后到的旧响应会盖掉新结果，列表在眼前自己跳 |

### 6.3 复用与新增

- 复用：`PageHeader` / `FilterBar` / `FilterField` / `TableCard` / `PaneCard` / `MetricCard` / `EmptyState` / `AppBadge` / `AppNote` / `KVList` / `JsonBlock`
- 新增：`src/api/state.ts`（**无 mock 分叉**：这一页的全部价值就是「库里现在长什么样」，用假数据展示等于把价值抽掉）、`formatBytes()`、`queryKeys.stateDatabase/stateTables/stateSessions/stateSessionDetail/stateRaw`
- 路由 `/storage` 开 `meta.wide`（左栏 + 一张 7 列的表，1400px 下主栏会被压到折行）

### 6.4 顺手修掉的一个通用缺陷：时间解析

后端确实会给出**两种**时间形态：MySQL `DATETIME` 直接取出来是 `2026-09-22 15:30:05`（空格），Jackson 序列化 `LocalDateTime` 是 `2026-09-22T15:30:05`。带空格的那种 Chrome / Node 能解析，**Safari 会得到 `Invalid Date`**，页面表现是时间列整列显示「—」，完全看不出是解析问题。

`lib/format.ts` 因此把 `Date.parse(x)` 统一换成 `parseTime(x)`（先把「日期 空格 时间」归一成 ISO）。**这是一个前端侧的兜底，不是重复劳动**：后端的 `iso()` 归一化只在重启后生效，而前端不该依赖「后端一定重启过」。

### 6.5 联调中发现的真实 Bug：抽屉宽度导致按钮点不到

`el-drawer` 写死 `size="900px"`。窗口一旦窄于 900px（笔记本分屏、IDE 内嵌浏览器都常见），抽屉左侧会整体跑到屏幕外 —— **那一侧的内容连同按钮点都点不到**，表现是「按钮点了没反应」，实际是它根本不在视口里。

改法：`size="min(900px, 92vw)"`（Element Plus 把 `size` 直接当 `width` 样式用，所以能写 `min()`）。

> 这个 Bug 在 jsdom 里**永远测不出来**（jsdom 不做布局）。它是靠真实浏览器联调发现的 —— 也是本次坚持在真浏览器里跑一遍的原因。

---

## 7. 真实联调验证记录（后端 9090 + 前端 5273，均为真实数据）

| # | 用例 | 结果 |
| --- | --- | --- |
| 1 | 页面首屏 | 状态库 `md_agent_state`；状态表 2 张；会话 4 个；占用 6.49 MB |
| 2 | 状态表清单 | `test_agent`（4 会话 · 6.49 MB · 最后写入 8 小时前）、`test_harness`（0 会话） |
| 3 | 会话列表 | 4 行，列头/大小/版本/相对时间均正确 |
| 4 | 关键字搜索 `conv-h` | 「筛选出 1 / 共 4 个会话」，命中 `conv-huge` |
| 5 | 分页 | `共 4 条 · 20 条/页 · 第 1 页` |
| 6 | 详情（10 KB 会话） | 用户 `codex` / 5 条消息 / 权限 `bypass` / 工具组 `skill-build-in-tools`；预览带 `[思考]`、`[返回]` 前缀 |
| 7 | 读取原文（10 KB） | 8091 字符 JSON 正常渲染 |
| 8 | 详情（5.19 MB 会话） | 200，响应体 **7,691 B**（裁剪生效），摘要以「…」截断 |
| 9 | 读取原文（5.19 MB） | **413**，页面显示后端原话：`agent_state 体积 5316 KB，超过 4 MB 上限，不予整包返回` |
| 10 | 非法表名 `bad\`;DROP` | 400「非法的状态表名」（注入闸门生效） |
| 11 | 不存在的表 | 404「状态表不存在」 |
| 12 | 删除会话 | 200 `{deleted:2}`；重复删 404 |

### 已知性能观察

详情接口要**读出并解析整段 `state_data`**，所以延时随上下文线性增长（实测）：

| 会话大小 | 详情响应体 | 耗时 |
| --- | --- | --- |
| 1.29 MB | 10,551 B | 3.5 s |
| 5.19 MB | 7,691 B | 15.1 s |

响应体已经被裁到 KB 级，慢在「读大字段 + 解析」。真实 agent 的上下文通常 < 1 MB，暂不处理；若后续出现常态化的 MB 级上下文，可选：只解析 `context` 尾部、或在写入时顺带落一份摘要。

---

## 8. 未做 / 遗留风险

| 项 | 说明 |
| --- | --- |
| **`md_agent_state` 里的测试数据** | `test_agent` 表下 4 个会话均为联调造的样本（`codex:state-test-1` 是框架真实写入，其余 3 个是从归档灌入的）。交付前可删；保留的价值是「页面上有东西可点」 |
| **详情里的槽位行不返回 `userId`/`sessionId`** | 外层已填、内层为 null。**前端只读外层字段**；要补齐只需在 `detail()` 里对 slots 多走一次 `decorate`（已有该逻辑） |
| **状态库不在控制面里做备份/保留策略** | 目前只有手工备份（§3.1）。没有 TTL、没有归档 |
| **页面只能删，不能导出** | 想留存只能走 `/raw`（且受 4 MB 限制） |
| **`md_agent_state` 与业务库同实例** | 换实例时 `state-store-database` 要跟着改，且**跨库查询会失效**（需要第二个 DataSource）。这是当初选同实例换来的简单性，代价写在这里 |
| **多副本部署下的并发写** | 状态进 MySQL 解决了「各写各的」问题，但同一会话的**并发写**仍由框架自身保证；本项目没有加分布式锁 |

---

## 9. 运维速查

| 我想… | 怎么做 |
| --- | --- |
| 知道状态在哪个库 | 页面顶部「状态库」，或 `GET /api/state/database` |
| 让某个 agent 换一张表 | 改 `md_agent_runtime.state_table`（改完**下次对话生效**，不需要重启） |
| 清空某个会话的记忆 | 页面「删除」，或 `DELETE /api/state/sessions?table=&slotId=` |
| 看 agent 到底能看见什么 | 页面「详情」→ 上下文摘要 + 消息预览 |
| 排查某条工具结果塞了什么 | 页面「详情」→「读取原文」（≤ 4 MB） |
| 找回被舍弃的旧状态 | `logs/state-archive-20260922/*.jsonl`（业务库/遗留库）、`C:\Users\User\.agentscope\state_legacy_20260922`（文件态） |

---

## 10. 验证基线（本次收尾时）

| 项 | 结果 |
| --- | --- |
| 后端 `mvn -o test -Dtest=AgentStateAdminServiceTest` | **14 / 14 通过** |
| 前端 `vue-tsc --noEmit` | 0 错 |
| 前端 `vitest run` | **111 例通过**（含新增 2 例：状态存储页取数与「原文按需加载」） |
| 前端 `vite build` | 通过（StoragePage chunk 16.27 kB / gzip 6.84 kB） |
| 真实浏览器联调 | 见 §7 |
