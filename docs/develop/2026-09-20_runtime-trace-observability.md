# Agent 运行链路观测：Step 1 实现与前端联调改动记录

> 记录时间：2026-09-20
> 涉及仓库：后端 `D:\jinran\code\Md-Agent-Platform`，前端 `D:\jinran\code\agent-contro-plane`
> 覆盖范围：Step 0（升级 WebFlux）→ Step 1（事件化链路观测）→ 前端接线与联调修复
> 验证状态：后端 `Tests run: 203, Failures: 0, Errors: 0` + `BUILD SUCCESS`；前端 `14/14` 通过，`vue-tsc --noEmit` 与 `vite build` 均通过
> 组织方式：目标 → 改动清单 → 设计取舍 → 已知缺口 → 后续路线，便于逐段 review

---

## 0. 结论速览

1. **观测页面的数据链路已打通**：后端 `/api/traces` 三个端点全部可用，前端「执行链路」页能渲染真实调用树，不再依赖手写 mock。
2. 后端新增 `trace` 包 5 个契约类，**形态转换只发生后端一处**（`AgentRunEventTranslator`），控制器只做分页切片、不做翻译。
3. 联调时发现并修掉一个**接线错配**：run 列表走 mock、事件与详情走真后端，页面拿 mock 的 `runId`（`r_8f3c21a9` 这种）去问真后端，导致满屏 404，看起来像「后端接口没实现」。
4. 后端发现并修掉一个**真 bug**：`agentKind` 恒为 `react`。`HarnessAgent.Builder#middleware(mw)` 把业务中间件挂到内层 `ReActAgent` 上，回调参数里永远不是外壳，`agent instanceof HarnessAgent` 恒为 false。
5. 遗留一个**设计内的 404**：`GET /api/traces/{runId}/events/stream`（SSE）属于 Step 3。页面现在只用提示条说明「实时推送未接通」，不再把已经拿到的历史链路整页抹掉。

---

## 1. 目标与设计基线

### 1.1 要解决的问题

接入企微 / 飞书的 agent 已经在实际使用，但调用过程不可观测：只能看到最终输出和落库的 `state`，中间经历了哪几轮推理、调了哪些工具、花了多少 token、哪一步慢，全在黑盒里。

Step 1 的目标是把**每次调用链路**做成页面可读的调用树。

### 1.2 数据流

```
Agent 执行
  └─ AgentRunTraceMiddleware 在每个阶段「结束时」写一条 AgentTraceStep
       └─ AgentTraceRegistry（进程内有界环形缓冲，默认保留最近 200 次 run）
            ├─ GET /api/traces                 → 列表（run 级摘要）
            ├─ GET /api/traces/{runId}         → 顶部概览元信息
            └─ GET /api/traces/{runId}/events  → 事件流（游标分页）
                 └─ AgentRunEventTranslator 把「阶段记录」翻译成「开始 / 结束」两条事件
                      └─ 前端 projectTrace() 按 path 建树 → 调用树
```

两条通道分工（对齐官方 `agentscope-service`）：

| 通道 | 职责 | 状态 |
| --- | --- | --- |
| REST（历史） | 权威读，支持 `after` / `before` 游标分页，用于首屏与补齐缺口 | 已完成 |
| SSE（增量） | 只负责增量推送与「唤醒」，断线用 `Last-Event-ID` 续传 | Step 3 |

### 1.3 三条不可动摇的约束

1. **事件是唯一事实来源，树只是可重算的投影**。前端不缓存树、不反向写回事件。
2. **历史 REST 权威，SSE 不做数据源**。推送到的帧同样要经游标合并才落状态，不能因为「是推送来的」就当成已消费。
3. **`seq` 必须从 1 开始且连续**。前端 `mergeContiguousEvents` 只放行「从 cursor+1 起的连续前缀」，中间缺一个号就停在那里等，后面的事件全部积压不渲染。

---

## 2. 后端改动

### 2.1 Step 0：升级到 WebFlux

| 文件 | 改动 |
| --- | --- |
| `md-agent-server/pom.xml:51` | 引入 `spring-boot-starter-webflux`，移除 servlet 栈 |
| `md-agent-server/.../config/WebFluxStackTest.java` | 新增，2 个护栏测试 |

**为什么专门写一个测试守住这件事**：`spring-boot-starter-web` 与 `spring-boot-starter-webflux` 同时在 classpath 上时，Spring Boot 会判定为 SERVLET 并**静默关掉** WebFlux 自动配置 —— 不报错、不留日志，编译通过、启动正常、端口能通，但任何返回 `Flux` 的接口都会退化，SSE 永远推不出数据。所以直接问 Spring Boot 自己 `getWebApplicationType()`，并断言 classpath 上没有 `DispatcherServlet` / `Tomcat`。

### 2.2 新增：`server/trace` 包的 5 个契约类

| 文件 | 职责 | review 关注点 |
| --- | --- | --- |
| `AgentRunEvent.java` | 前端直接消费的一条事件；`TYPES` / `KINDS` / `STATUSES` 是**闭集** | 字段名与前端 `TraceEvent` 逐字对应，改名等于打散前端建树逻辑；`durationMs` / `tokens` 用包装类型，因为 null（无此指标）与 0（指标就是 0）在页面是两种显示 |
| `AgentRunEventTranslator.java` | 纯函数 `translate(run)`：阶段记录 → 事件流 | 树的父子关系、路径编号、token 归属全在这里，是 review 的重心 |
| `TraceRunSummary.java` | 列表一行 | 状态词表在这里翻译一次（后端 `DONE/ERROR/RUNNING` → 前端 `success/failed/running`），页面不写 if/else 猜 |
| `TraceRunInfo.java` | 单次 run 元信息 | `cacheHitRate` 分母是输入 token；`source` 当前固定 `unknown`，**刻意不按 sessionId 前缀猜**（猜错比留空更误导） |
| `TraceEventPage.java` | 事件分页结果 | 与前端 `TraceEventPage` 一致 |

### 2.3 改动：4 个既有文件

| 文件 | 改动 |
| --- | --- |
| `AgentTraceStep.java:41` | 把 `PHASE_*` 词表提为 public 常量，写入方 / 翻译方 / 测试共用一份 |
| `AgentTraceRun.java:24,35` | 新增 `AGENT_KIND_REACT` / `AGENT_KIND_HARNESS` 与 `agentKind` 字段 |
| `AgentTraceRegistry.java` | `start(...)` 增加 `agentKind` 参数；类注释明确「落库时只需换掉本类实现」 |
| `AgentTraceController.java` | 从「返回原始 step」重写为 4 个端点 + 游标分页 + 二分查找 |

**端点清单**

| 方法 | 路径 | 返回 |
| --- | --- | --- |
| GET | `/api/traces?limit=50` | `List<TraceRunSummary>`（上限 500） |
| GET | `/api/traces/{runId}` | `TraceRunInfo`；run 被环形缓冲淘汰后返回真 404 |
| GET | `/api/traces/{runId}/events?limit=&after=&before=` | `TraceEventPage`（上限 1000） |
| DELETE | `/api/traces` | 清空内存轨迹，不动业务数据 |

游标语义与前端 `useRunEvents` 的三次调用严格对应：不带游标取最新一页（首屏）、`after=N` 取 `seq > N` 的最前一批（断线补齐）、`before=N` 取 `seq < N` 的最后一批（向上翻历史）。

### 2.4 本轮修复：`agentKind` 恒为 `react`

**现象**：`workwx_batch_agent` 的定义是 `HARNESS`（`/api/agents` 可见），但 `/api/traces/{runId}` 返回的 `agentKind` 是 `react`，页面徽标跟着显示错。

**根因**（框架侧已核实）：

```java
// HarnessAgent.java:1198
private final ReActAgent.Builder inner = ReActAgent.builder();

// HarnessAgent.java:1602 —— 业务中间件被转发给内层，外壳不在回调链上
public Builder middleware(MiddlewareBase middleware) {
    if (middleware != null) {
        middlewares.add(middleware);
        inner.middleware(middleware);
    }
    return this;
}
```

`HarnessAgent implements Agent`，内部 `private final ReActAgent delegate`，但它**不是** `ReActAgent` 的子类。中间件挂在 delegate 上，所以 `MiddlewareBase` 回调收到的 `agent` 永远是内层 `ReActAgent`，`instanceof HarnessAgent` 恒为 false。

**修法**：装配期登记「名字 → 类型」快照，中间件按名字查表，`instanceof` 降级为兜底。

| 文件 | 位置 | 内容 |
| --- | --- | --- |
| `AgentRunTraceMiddleware.java` | :153 | 新增 `agentKinds` 装配期快照 |
| 同上 | :161 | `registerAgentKind(name, kind)` |
| 同上 | :767 | `agentKind(agent)` 改为查表，`instanceof` 仅作兜底 |
| `ConfigurableAgentBuilder.java` | :84 | `build()` **成功后**登记，失败不覆盖仍在服役的旧实例 |
| `AgentTraceRegistry.java` | `start` 的 javadoc | 同步订正旧描述（原文写的是「由调用方当场观测 agent 对象类型」，已不成立） |

**为什么用「装配期快照」而不是「运行期回查 `AgentDefinition`」**：定义是可变数据（页面随时能改），回查会让「昨天那条 harness 的 run」在改完定义后被重新解释成 react，历史观测数据失去可信度。快照只影响此后新建的 agent，已经写进 `AgentTraceRun` 的类型是历史事实。

### 2.5 三个核心设计决策（review 重点）

**决策一：`seq` 是写入顺序，不是时间顺序。**
前端只放行连续前缀，且已发出的 `seq` 不能回头改。若按 `startedAt` 排序，「后到却开始最早」的 reasoning 会顶掉整条流。

**决策二：`path` 是读时投影，不是写入时分配。**
`onReasoning` 在外层，它包住 `onModelCall` / `onActing`，写入顺序与真实时间线相反，所以父子关系只能靠「时间区间包含」在翻译时推导（`AgentRunEventTranslator` 的 `contains` / `isInnermost` / `assignPaths`）。**为什么不在写入时分配**：中间件实例会被框架复制给子 agent 长期共享，共享栈必然串台；纯函数没有共享状态。代价：路径会变（`1.2` → `1.1.1`），已有测试钉住行为。

**决策三：`MODEL_CALL` 折叠进 `REASONING`。**
`source` 取模型名、`tokens` 取该轮总用量，对齐前端契约。同时：`SYSTEM_PROMPT` 不进树（管道型阶段，没有「前后」）；失败一律走 `error.raised`；**根节点不带 tokens**（前端会对全树求和，根节点再带一次就翻倍）。

### 2.6 测试

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `AgentRunEventTranslatorTest.java` | 15 | 新增；翻译的纯函数行为（路径、折叠、异常、token 归属） |
| `AgentTraceControllerTest.java` | 7 | 重写；用 `WebTestClient` 打 HTTP 契约与游标分页 |
| `AgentRunTraceMiddlewareTest.java` | 12 | 9 → 12；新增 3 条钉住 agent 类型判定（登记为 harness 时优先、未登记回落 react、类型在 run 开始时冻结） |

后端合计 `203/203`（Step 1 之前是 181）。

---

## 3. 前端改动（`agent-contro-plane`）

### 3.1 联调接线修复

| 文件 | 改动 |
| --- | --- |
| `vite.config.ts:21` | `/api` 代理目标 `18080 → 9090`（18080 是从官方 `agentscope-service` 照抄的错误默认值，指向没人监听的端口时只会看到 500/ECONNREFUSED，极易被误判成「接口没实现」） |
| `.env.local`（新增） | `VITE_TRACES_LIVE=true` |
| `features/traces/api.ts` | 新增本页独立开关 `TRACES_LIVE` / `TRACES_SOURCE_MOCK`；`fetchRecentRuns` 从 `api/overview.ts` **迁入**；SSE 遇到 404/405 不再退避重连（否则会以 30s 为单位一直敲后端，日志全是噪声） |
| `api/overview.ts` | 删除 `fetchRecentRuns` |
| `pages/TracesPage.vue` | 改 import 到本页 api；`refetchInterval` 打开实时刷新时 3s 轮询；空列表显示提示文案；**有数据时实时通道报错不再整页替换**（`v-if="loading && events.length === 0"` / `v-else-if="events.length === 0 && error"` + 独立提示条）；图例标注数据来源是 mock 还是真后端 |

**为什么必须把 `fetchRecentRuns` 挪进本页**：它原本受**全局** `VITE_USE_MOCK` 控制。全局开关关掉时其余十几个页面会一起变成满屏 404；不关掉则列表是 mock、事件是真后端 —— 一个页面用到的数据被两个开关各管一半，就会出现本次联调那种「拿着 mock 的 runId 问真后端」的 404。原则：**一个页面用到的数据必须由同一个开关决定**。

### 3.2 真实响应契约测试（新增）

| 文件 | 说明 |
| --- | --- |
| `features/traces/backendContract.test.ts` | 4 条断言，把**真实的后端响应**喂给投影层 |
| `features/traces/__fixtures__/run-5302626f-events.json` | fixture，由 `Invoke-WebRequest http://localhost:9090/api/traces/5302626f/events?limit=100` 的结果**逐字节原样保存**，未做任何再序列化 |

**为什么必须有**：这次满屏 404，靠肉眼根本分不清「接口没通」还是「数据通了但渲染不出来」。mock 事件源是照着契约手写的，写得再像也不能证明后端真的这么返回 —— 字段名差一个字母、payload 少一层，页面就是一棵空树而且不报错。有了这份 fixture，后端一改事件形状测试先红，不用等联调时在页面上猜。

断言内容：事件字段与 `TraceEvent` 契约一致且 seq 单调；投影出根节点 + 一轮推理且 `run.completed` 不重复建节点；节点详情能吃到 `payload.input/output/metrics`；`token` 不因根节点而翻倍。

前端合计 `14/14`（原有 10 + 新增 4）。

### 3.3 实测数据（供对照）

```
GET /api/traces
[{"runId":"5302626f","agentId":"workwx_batch_agent","sessionId":"single-wo7U1…-2026-09-20",
  "status":"success","tone":"ok","durationMs":4801,"tokens":16897,"startedAt":"2026-09-20T14:20:32.027Z"}]

GET /api/traces/5302626f/events → 4 条
 seq  type                  path  status  durationMs  source              tokens
   1  run.started           1     run     -           -                   -
   2  reasoning.started     1.1   run     -           deepseek-v4-flash   -
   3  reasoning.completed   1.1   ok      4098        deepseek-v4-flash   16897
   4  run.completed         1     ok      4801        -                   -（刻意不带，避免前端求和翻倍）
```

---

## 4. 本次改动的完整文件清单

**后端 `D:\jinran\code\Md-Agent-Platform`**

| 文件 | 新增/改动 |
| --- | --- |
| `md-agent-server/pom.xml` | 改（WebFlux） |
| `.../config/WebFluxStackTest.java` | 新增 |
| `.../trace/AgentRunEvent.java` | 新增 |
| `.../trace/AgentRunEventTranslator.java` | 新增 |
| `.../trace/TraceRunSummary.java` | 新增 |
| `.../trace/TraceRunInfo.java` | 新增 |
| `.../trace/TraceEventPage.java` | 新增 |
| `md-agent-bootstrap/.../controller/trace/AgentTraceController.java` | 重写 |
| `.../trace/AgentTraceStep.java` | 改 |
| `.../trace/AgentTraceRun.java` | 改 |
| `.../trace/AgentTraceRegistry.java` | 改 |
| `.../agent/middleware/AgentRunTraceMiddleware.java` | 改（agentKind 修复） |
| `.../agent/ConfigurableAgentBuilder.java` | 改（装配期登记） |
| `.../trace/AgentRunEventTranslatorTest.java` | 新增 |
| `md-agent-bootstrap/.../controller/trace/AgentTraceControllerTest.java` | 重写 |
| `.../agent/middleware/AgentRunTraceMiddlewareTest.java` | 改（+3） |

**前端 `D:\jinran\code\agent-contro-plane`**

| 文件 | 新增/改动 |
| --- | --- |
| `vite.config.ts` | 改（代理端口） |
| `.env.local` | 新增 |
| `src/features/traces/api.ts` | 改 |
| `src/api/overview.ts` | 改（删 `fetchRecentRuns`） |
| `src/pages/TracesPage.vue` | 改 |
| `src/features/traces/backendContract.test.ts` | 新增 |
| `src/features/traces/__fixtures__/run-5302626f-events.json` | 新增 |

---

## 5. 已知缺口

### 5.1 设计内的缺口

| 项 | 说明 |
| --- | --- |
| SSE 端点 404 | `GET /api/traces/{runId}/events/stream` 属于 Step 3。现在前端靠 `after` 游标补一次也能看到链路在长，只是有延迟；网络面板里那一个 404 就是它，不是故障 |
| 「进行中」的步骤在页面上不存在 | 阶段统一在**结束时**写入，所以正在跑的那一步还没落库。Step 2 改为「开始 / 结束各写一条」后消除 |
| 子 agent 阶段 | `subagent.dispatched/completed` 两个 type 已定义但后端还不产出，留给 Step 5 |
| `source`（触发来源）固定 `unknown` | 后端目前拿不到「这次是企微来的还是定时任务来的」，接入渠道时再补 |

### 5.2 本次引入、需要补的

| 项 | 影响 |
| --- | --- |
| `ConfigurableAgentBuilder.build()` 里的 `registerAgentKind` 调用**没有单测钉住** | builder 依赖 Spring 上下文，单测成本高；中间件侧的 3 条测试只覆盖「查表」不覆盖「写表」，删掉那行不会变红 |

### 5.3 后端既有遗留（与本次改动无关，但会影响联调与上线）

| 项 | 说明 |
| --- | --- |
| 零鉴权 | 无 `spring-security`、无拦截器、无 CORS 配置；`DELETE /api/agents/{name}` 完全裸奔。前端已经在发 `Bearer token`，后端不校验 |
| **没有 HTTP 触发 agent 执行的接口** | 联调最大的痛点：造不出 run，只能靠企微 / 飞书给 bot 发消息或等定时任务 |
| ~~`AGENT-TRACE` 日志没有 logback 配置~~ | 2026-09-22 起这套过程日志已整体删除（页面上的执行链路是唯一观测面），不再需要 logback 配置 |
| `TestController.java` 6 个写接口返回「200 + `<pre>` 文案」 | 不是 REST 语义，前端无法复用 |
| 无 eval、无 Dockerfile | — |
| `application.yml` 硬编码 `106.53.145.158` 等敏感信息 | 公开前必须脱敏 |

### 5.4 小瑕疵（不影响功能，记录备查）

- `TraceEventPage.hasEarlier` 在 `after` 查询下语义偏怪：`?after=2` 会返回 `hasEarlier=true`（含义是「存在更早的事件，但不在本页」）。前端只在首屏用这个字段，不影响行为；等 Step 2 换成精确游标分页时一并理顺。
- `agent-platform.trace-max-runs` 没有写进任何 yml，走 `AgentPlatformProperties` 的默认值 200。

---

## 6. 后续路线

### Step 2：落库 —— ✅ 已完成（2026-09-21）

实际做法与本节当初的设想有两处不同，**以下面为准**（详见 `2026-09-21_trace-persistence-and-runid.md`）：

- 「seq 冲突重试」**整条砍掉**：seq 由内存里的 `AtomicInteger` 在事件产生时分配，同一个 run 只有一个计数器，不存在冲突。当初设想的重试是「每次查询重新编号」时代的产物，那个做法已被证伪（见新文档 2.1）。
- 「内存实现换成 MySQL 实现」也没有照做：**内存保留为热数据/读缓存**，MySQL 作为权威副本，读路径内存优先、不足回库补齐。这样列表页常态零查库，重启后又能看到全部历史。
- 其余照原计划：唯一键 `(run_id, seq)`、开始/结束各写一条、中间件一行未改（它只依赖 Registry 的写入方法）。

### Step 3：SSE

- WebFlux 下返回 `Flux<ServerSentEvent>`。
- `afterSeq = max(query after, Last-Event-ID)`；帧结构照官方：`id(seq)` / `event(type)` / `data(json)`。
- 通知器用官方极简版：**只负责唤醒，不承载数据**，唤醒后仍然从持久化游标重新读，避免把通知当成数据。
- **验收**：前端不再发 `/events` 轮询；网络面板里那个 404 消失；浏览器断网重连后不丢事件、不重复渲染。

### Step 4：前端其余页面接入真数据

还有十几个页面（概览 / 工具 / 技能 / MCP / 会话 / 渠道 …）走 mock。逐页按本次「执行链路」的做法改：给页面一个独立开关，避免全局开关一刀切造成半真半假。

### Step 5：子 agent 与并发工具

- 产出 `subagent.dispatched` / `subagent.completed`。
- 并发工具的 `startedAt` 目前取的是「这一批开始」的时刻，父子关系可能不准，需要重新定义。

### Step 6：可联调性与加固（建议优先级最高）

- **`POST /api/agents/{name}/invoke`**：成本最低、收益最大。没有它就只能靠发消息造 run，联调效率极低。
- 鉴权 / CORS；`TestController` 改为返回 JSON。（原列的「logback 配 `AGENT-TRACE`」已随 2026-09-22 删除过程日志而作废）

---

## 7. 复现与验证

**后端**（`JAVA_HOME` 需显式指定 JDK 21）

```powershell
cd D:\jinran\code\Md-Agent-Platform
$env:JAVA_HOME = "C:\Users\User\.jdks\ms-21.0.11"
& "D:\jinran\maven\apache-maven-3.9.10-bin\apache-maven-3.9.10\bin\mvn.cmd" -o test
```

**前端**

```powershell
cd D:\jinran\code\agent-contro-plane
$node = "C:\Users\User\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
& $node "node_modules\vitest\vitest.mjs" run
& $node "node_modules\vue-tsc\bin\vue-tsc.js" --noEmit
& $node "node_modules\vite\bin\vite.js" build
```

**闭环自测（改完后端 agent 类型判定之后必须重启后端）**

```powershell
Invoke-WebRequest "http://localhost:9090/api/traces" -UseBasicParsing | Select-Object -ExpandProperty Content
# 期望：agentKind 为 harness（workwx_batch_agent / diagnosis_agent / deployment_agent 三个定义都是 HARNESS）
Invoke-WebRequest "http://localhost:9090/api/traces/<runId>" -UseBasicParsing | Select-Object -ExpandProperty Content
```

前端 `.env.local` 里的 `VITE_TRACES_LIVE` 是**新增环境变量**，改完必须重启 dev server；浏览器侧确认「最近执行」列表里的 runId 是 8 位十六进制（真后端）而不是 `r_` 开头（mock）。

---

## 8. 关键文件索引

| 关注点 | 从哪里看起 |
| --- | --- |
| 契约定义 | `md-agent-server/.../trace/AgentRunEvent.java` 的 javadoc |
| 树是怎么长出来的 | `md-agent-server/.../trace/AgentRunEventTranslator.java` 的 `translate` / `contains` / `assignPaths` |
| agent 类型判定 | `AgentRunTraceMiddleware.java:153,161,767` + `ConfigurableAgentBuilder.java:84` |
| 前端建树 | `agent-contro-plane/src/features/traces/adapters.ts` 的 `projectTrace` |
| 前端游标与续传 | `agent-contro-plane/src/features/traces/useRunEvents.ts` |
| 前端取数开关 | `agent-contro-plane/src/features/traces/api.ts` 的 `TRACES_LIVE` / `TRACES_SOURCE_MOCK` |
