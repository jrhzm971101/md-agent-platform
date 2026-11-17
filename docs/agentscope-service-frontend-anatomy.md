# AgentScope Service 前端剖析（含整体架构）

> 剖析对象：`D:\jinran\code\agentscope-java-main\agentscope-java-main\agentscope-service`
> 来源：公开仓库 `agentscope-ai/agentscope-java` 的 `main` 分支（此前我判断"源码看不到"是错的，该模块已并入公开仓库）
> 引入时间：commit `be41da40`（2026-09-09，"Introduce new designed AgentScope Service implementation" #3080），文档补充 `846d3c32`（2026-09-14）
> 代码量：Go 505 文件 · Java 209 文件 · TS/TSX 227 文件 · SQL 迁移 214 个

---

## 0. 结论速览

1. **前端不是 Vue，是 React 18 + Vite 6 + TS 5.7 + Tailwind v4 + Radix UI + TanStack Query**。没有 Redux / Zustand / MobX。
2. 整套前端最核心、也最值得抄的只有一件事：**会话事件管线**——durable event log → 游标 → 连续性合并 → 投影成 UI 模型。相关代码不到 300 行，但它回答了"Agent 黑盒怎么打开"这个问题。
3. 实时通道有两条，分工明确：会话级走 **SSE（能续传、不能丢事件）**，应用级走 **WebSocket（只做缓存失效提示，REST 权威）**。
4. 它是**四平面 + PostgreSQL 三 schema** 的重型平台。前端离开这套后端会全部空转，不能整体搬。
5. 对你的项目真正可迁移的，是 `lib/` 和 `features/*/lib` 里的**纯逻辑**（游标、投影、事件字典），这部分恰好是语言无关、最有价值的部分。

---

## 1. 仓库版图

```
agentscope-service/
├─ aistio/              控制面（Go，aistiod，505 个 .go）
│  ├─ internal/httpapi/       HTTP 路由（104 文件）
│  ├─ internal/product/       产品资源（66）
│  ├─ internal/postgres/      存储 + migrations（43，//go:embed migrations/*.sql）
│  ├─ internal/orchestration/ 编排
│  ├─ internal/collaboration/ 协作（issue/team/审批）
│  ├─ internal/runtimehost/   托管宿主（Codex/Claude Code 等）
│  ├─ internal/sessionops/    会话运维（压缩上下文等）
│  ├─ internal/asdp/          应用 SDK 接入协议
│  ├─ internal/discovery/     注册与发现
│  ├─ cmd/ · sdk/dsh/ · install/ · helm/ · api/
├─ service-gateway/     公共入口、鉴权、路由（Java Spring Boot）
├─ service-dataplane/   Harness 运行时、事件日志、SSE、Turn Lease、HITL、工作队列（Java）
├─ service-scheduler/   Channel、Cron、出站任务、自托管 Hands Worker（Java）
├─ service-common/      共享（鉴权、目录、持久化、协调）
├─ frontend/            React SPA（227 ts/tsx）
├─ deploy/ docker/ helm/ release/ scripts/ docs/
└─ docker-compose.yml
```

**职责边界（README 原文表格）**

| 平面 | 负责 | 不负责 |
| --- | --- | --- |
| Gateway | 公共入口、鉴权、API 路由 | 业务状态、Agent 执行 |
| Control Plane（`aistiod`，Go） | 产品资源、控制台、Agent 状态、Sessions、Teams、运行时命令 | Harness 推理、会话流传输 |
| Dataplane（Java） | Managed Harness Runtime、事件日志、SSE、Turn Lease、HITL、工作队列 | 直接读产品目录表 |
| Scheduler（Java） | Channel、Cron、出站任务、自托管 Worker | 推理循环 |

三种 Agent 形态统一在同一套 `ExecutionAttempt` 契约下：`managed`（托管 Harness）、`external-application`（用户自建，走 ASDP 注册）、`hosted-runtime`（Codex/Claude Code 这类进程，由 Runtime Host 守护）。

---

## 2. 前端技术选型

| 层 | 选型 | 备注 |
| --- | --- | --- |
| 框架 | React 18.3 | 全量 `React.lazy` + `Suspense` 做路由级分包 |
| 构建 | Vite 6 | `build.outDir: '../aistio/ui'` —— 产物直接喂给 Go 控制面 |
| 语言 | TypeScript 5.7 | `build` 脚本先 `tsc --noEmit` 再 vite build |
| 样式 | **Tailwind CSS v4** | 用 `@tailwindcss/vite`，**没有 tailwind.config.js**，主题写在 `index.css` 的 CSS 变量里 |
| 组件 | **Radix UI primitives** | dialog / dropdown-menu / tabs / tooltip / scroll-area / separator / label / slot |
| 自研原子组件 | 只有 5 个 | `components/ui/` = badge / button / card / dialog / input |
| 样式工具 | clsx + tailwind-merge（`cn`）、class-variance-authority（cva） | 变体用 cva，不写 switch |
| 图标 | lucide-react | |
| 服务端状态 | **@tanstack/react-query v5** | 唯一的状态容器 |
| 路由 | react-router-dom v6 | |
| 富文本 | react-markdown + prism-react-renderer | |
| 单测 | vitest | 31 个 colocated `*.test.ts` + 2 个 `*.test.tsx` |
| E2E | Playwright | 14 个场景，且按领域拆了 5 套 config（channel / permissions / resources / workspace / 默认） |

---

## 3. 目录架构：按"业务域"垂直切，不按"技术层"横切

```
frontend/src/
├─ api/          一个域一个文件：agents.ts channels.ts collaboration.ts
│                orchestration.ts managedSessions.ts permissions.ts ...
│                http.ts（authHeaders / readApiError）
├─ lib/          纯逻辑与纯工具：apiClient auth capabilities namespaceScope format utils
│                ★ 大量 *.test.ts 落在这里 —— "可测的纯函数"被刻意抽出来
├─ app/          应用壳：AppShell ScopeContext PrivateRoute CommandPalette
│                NamespaceSwitcher useCollaborationEvents approvalAttention
├─ components/   跨域共享组件（含 ui/ 原子组件、JsonViewer、EmptyState、
│                CapabilityGate、ConversationSurface 相关的通用件）
├─ features/     业务域，每个域自带 api.ts / components / lib / 测试
│   ├─ conversation/  对话投影（10 文件，全部围绕事件→UI）
│   ├─ operate/       运维控制台（13 文件：实时会话、上下文检查、压缩、token 趋势）
│   ├─ build/         资源定义与部署（19）
│   ├─ teams/         团队编排（12）
│   ├─ orchestration/ 编排定义（10）
│   ├─ settings/ issues/ approvals/ inbox/ work/ automations/ tasks/ chat/
├─ pages/        只做路由挂载的页面壳（21 个 *Page.tsx）
├─ index.css · main.tsx
```

**可抄的结构要点**：`pages/` 是薄壳（挂路由）→ `features/<域>/` 装业务 → `lib/` 装纯函数 → `components/` 装跨域复用件。测试跟着纯逻辑走。

---

## 4. 路由与信息架构

三层，按"谁在用什么"切，而不是按技术模块切：

- **`/work/*` 人的工作面**：overview · chat · issues · inbox（审批中心）· automations · activity · executions · sessions
- **`/agent-center/*` 编排面**：agents（下设 definition/behavior、workspace、skills、tools、subagents、versions + connections/channels）· teams · workflows · entrypoints（Channels）· workspaces · environments · memory · vaults
- **`/settings/*` 治理面**：namespaces · users · access-log · integrations · profile

配套机制：

- **权限门是路由级的**：`<WorkspaceAccess area="work">`、`<OperatorAccess>`、`PrivateRoute`、页面内 `CapabilityGate` / `DisabledAction`
- **Legacy 重定向层**：`/control/*`、`/managed/*`、`/operations/*` 全部 `RedirectWithSearch` 到新路径（且保留 query 参数）。说明他们重构过 IA，用重定向保住老书签 —— 这是很实用的一招
- **导航是数据驱动的**：`navigation: NavGroup[]` 每项带 `admin / operator / agentCenter / configure` 标记，`visibleNavigation` 按角色过滤后渲染，不写 if-else 分支组件

---

## 5. 状态管理策略：三层，没有全局 store

| 状态类型 | 方案 |
| --- | --- |
| 服务端数据 | TanStack Query（queryKey 规范 + `invalidateQueries`） |
| 全局上下文（租户 / 命名空间 / 身份） | React Context（`ScopeContext`） |
| 流式事件 | `useRef` 存游标 + `useState` 存数组，**刻意不走 Query** |

---

## 6. 核心：会话事件管线

### 6.1 两个数据源

```
历史：GET /api/v1/sessions/{id}/events?limit=&before=&after=&eventType=
实时：GET /api/v1/sessions/{id}/events/stream?after=N     （SSE）
```

### 6.2 SSE 客户端不是 EventSource

`features/operate/api.ts` 的 `streamSessionEvents` 用 **fetch + ReadableStream 手写**，原因很硬：

- `EventSource` 不能带 `Authorization` header，他们的鉴权是 Bearer token
- 手写才能自己控制续传：重连时同时带 `Last-Event-ID` header 与 `?after=` query（双保险）
- 手写 `\n\n` 分帧、拼 `data:` 行、`JSON.parse`；**坏帧直接丢且不推进游标**
- 指数退避 1s → 30s（封顶），**收到任一正常事件就把退避重置回 1s**
- `AbortController` 实现 close

### 6.3 游标连续性：`mergeContiguousEvents`（`features/conversation/eventCursor.ts`）

整个文件 24 行，配了 2KB 测试。逻辑：

1. 收到的 seq 先进 `pending` Map；
2. 只释放**从 `cursor+1` 开始连续**的那一段前缀，`cursor` 随之推进；
3. 若 12 先到、11 后到：先缓存 12 → 触发补历史 → 补齐 11 后再把 11、12 一起放出去。

文件里的注释点明了动机：

> The pending map intentionally survives reconnects. If sequence 12 arrives before sequence 11, callers retain 12, repair history from 10, and then release 11 and 12 together. This prevents the UI resume cursor from permanently jumping over a transaction that was not visible to the first database read.

即：**防止续传游标永久跳过一条"第一次读库时还不可见"的事务**。

### 6.4 补洞：`repairGap`

- 只要 `pending` 里有积压（= 存在缺口），就发起前向读 `?after=cursor&limit=500`，循环分页直到追平
- 同一时刻只允许一个 repair 在跑（`repairingRef` 去重）；失败或仍有积压则 1 秒后重试
- 设计取向："**前向读是权威**"——它覆盖了"历史加载与实时流挂载期间提交的一切"

### 6.5 generation 计数防竞态

`generationRef`：切换 session 就自增；所有异步回调返回时先比对 generation，不匹配就丢弃结果。解决 React 里经典的"旧请求覆盖新状态"。这个模式他们在多处复用。

### 6.6 投影层：事件 ≠ UI

`features/conversation/` 是独立一层，做"原生事件 → 可读投影"：

```ts
ConversationProjection {
  schemaVersion: 'agentscope.conversation.v1',
  messages: ConversationMessage[],   // role + 有序 ContentBlock[]
  events:   ConversationEvent[],     // 带 category 的可读事件流
}
```

`model.ts` 的注释是这套设计的纲领：

> Runtime-native events remain the durable source of truth. Messages are the readable projection of that log and must not be used to reconstruct native provider state.

几个关键设计：

- **ContentBlock 是有序数组**：`text | tool | data | thinking | model`，一条助手回复 = 若干有序块，而不是"一条消息一个字符串"
- **工具调用靠 `callId` 与结果配对**，块上带 `eventSeq` / `resultSeq` 可溯源
- **三种来源归一化到同一投影**：managed 运行时事件、外部 SDK 事件、控制面写入的事件
- **原始 payload 永久保留**（`raw` / `payload`），渲染层永不解释它，只给 inspector 看

### 6.7 事件字典：`eventPresentation.ts`

一个把后端事件名翻译成"人话 + 来源 + 关联实体 + 诊断"的纯函数。摘两例：

| event type | 标题 | 解释 |
| --- | --- | --- |
| `session.status_idle` | Session idle | 运行时空闲、等待输入。**这不代表 issue 已被接受** |
| `agent.thinking` | Thinking recorded | 模型/运行时暴露的推理内容，**与最终回复是两回事** |
| `session.status_requires_action` | Waiting for action | 运行时需要外部动作才能继续 |

它还为每条事件生成**可点击的关联实体**：Session / Task / Execution attempt / Turn / Tool call / Dispatch generation / Endpoint invocation，外加诊断信息（原始运行时事件 ID、运行时序号、去重键）。

其中 `dispatchGeneration` 的用途是"拒绝来自更早 attempt 的事件"——多副本/重试场景下防止旧事件串台。

---

## 7. 两条实时通道，分工明确（很值得抄）

| 通道 | 技术 | 语义 |
| --- | --- | --- |
| 会话级 | SSE `/api/v1/sessions/{id}/events/stream` | **事件流本身**，前端自维护游标，不允许丢 |
| 应用级 | WebSocket `/api/v1/events?tenant=&namespace=` | **只是缓存失效提示** |

`app/useCollaborationEvents.ts` 的注释是原话：

> Realtime delivery is a cache-invalidation hint. REST remains authoritative, and the existing query intervals provide recovery after a disconnect.

实现细节：

- JWT 通过 **WebSocket subprotocol** 传递：`['aistio.v1', 'aistio.jwt.<token>']`
- 收到消息**只调 `invalidateQueries`**，从不直接改缓存
- 断线指数退避重连到 30s；真正的兜底是 Query 的轮询间隔

这个分工避免了"实时推送把前端状态带歪"这类最难查的 bug。

---

## 8. 多租户与权限

- `ScopeContext` 提供 `tenant` / `namespace`，由 `scopedApiPath` **双写**到 header（`X-AgentScope-Tenant` / `X-AgentScope-Namespace`）与 URL query
- `scopedPath()` 给路由链接统一加作用域前缀，导航组件不需要各自拼 namespace
- `namespaceCan()` 做能力判断，`NamespaceSwitcher` 做切换，`resourceURL()` 生成"访问与依赖"入口
- 401 统一在 apiClient 拦截：清 token → 跳登录

---

## 9. 可观测性页面：`/work/sessions/:id`（最值得抄的一页）

`OperateSessionDetailPage` 组合了 6 个 Query + 1 个事件流：session · context · events · tasks · subagentTasks · commands · turns。

组件与职责：

| 组件 | 作用 |
| --- | --- |
| `StatusStrip` | 状态条；`phaseTone()` / `phaseHint()` 把 phase 映射成色调与提示文案 |
| `ContextPanel` | **上下文检查器**（详见下） |
| `TokenTrend` | token 用量趋势 |
| `CompressButton` | 在线压缩会话上下文（主动干预） |
| `ConversationSurface` | 渲染对话投影（消息块 + 事件行 + 工具块） |

`ContextPanel` 的呈现方式是把"黑盒"打开的关键：

- **System prompt**，并标注来源是 `effective`（最后一次模型调用实际用的）还是 `base`（builder 基础，本回合还没采样）
- **Plan**：是否 active、当前 plan 文件、plan 摘要
- **Compaction**：压缩摘要（若已压缩）
- **Tools (session-effective)**：本会话实际生效的工具清单
- **Effective messages**：**下一次模型调用将要发送的完整消息窗口**，逐条可展开
- 顶部有汇总徽标：是否已压缩 / 是否 plan mode / 模型名 / 消息数 / 工具数 / `窗口 totalTokens / maxTokens`
- 底部 "Show raw" 可以看原始 JSON

> 精髓：不是在"看日志"，而是把**下一次模型调用要发的上下文完整摊开**给人看。

---

## 10. 工程约定

- 每个文件带 Apache 2.0 版权头
- 单测与源码**同目录**（31 个）；纯逻辑放 `lib/` 便于测试
- E2E 按领域拆 config（permissions / resources / workspace / channel）
- `scripts/docs/capture.mjs` 自动截图生成文档配图
- 原子组件只 5 个，其余全用 Tailwind 类内联 —— **不引重型组件库**
- 语义色统一走 Badge 的 `tone` 变体（default / info / warning / …）
- 可访问性做到了：skip-to-content 锚点、`aria-label`、focus ring、`main tabIndex={-1}`

---

## 11. 构建与部署

- `vite build` → 输出到 `../aistio/ui`，由 Go 控制面（`aistiod`，:8081）**连 SPA 一起提供**
- 开发态：vite proxy `/api` → `http://localhost:18080`（gateway）
- `docker-compose.yml`：
  - `db` postgres:17（三个 schema：`cp` / `rt` / `dp`）
  - `control` aistiod :8081（`--storage-dsn ...?search_path=rt`）
  - `data` :8082 · `scheduler` :8083 · `gateway` :18080
- 生产走 Helm，四平面独立扩缩；数据库迁移 214 个 SQL，由 Go 侧 `//go:embed migrations/*.sql` 执行

---

## 12. 该抄什么 / 不该抄什么

### 值得直接借鉴（按价值排序）

1. **事件管线的协议设计**：durable log + 单调 seq + `after`/`before` 分页 + `Last-Event-ID` 续传 + 连续性合并 + 补洞重试 + generation 防竞态。这六个词就是"可观测"的全部骨架
2. **投影层思想**：原生事件是唯一真相，UI 模型是它的投影，投影带 `schemaVersion`，原始 payload 永久保留
3. **事件字典单一出处**：每个事件类型在这里配标题/解释/来源/关联实体/链接，别散落在各个组件里
4. **实时只做 invalidate**：REST 权威 + 轮询兜底，别让推送直接改状态
5. **目录结构**：`features/<域>` 垂直切 + `lib/` 纯逻辑 + `pages/` 薄壳
6. **数据驱动导航 + legacy 重定向层**
7. **ContextPanel 的呈现方式**：把"下一次模型调用要发什么"摊开
8. **Tailwind v4 CSS-first token + 极薄原子组件层**

### 不要抄 / 需要警惕

1. **它需要整套后端**：Go 控制面 + 4 个 Java 平面 + PostgreSQL 三 schema。单搬前端会全部空转
2. **事件模型的隐含前提是"事件已持久化且有序号"**。你现在（Md-Agent-Platform）的事件是 middleware 在内存里聚合的，没有落库、没有单调 seq —— 想上这套，**前置条件是先把事件落库并分配 seq**。这是你最大的架构缺口
3. **企业级多租户那套**（tenant / namespace / vault / RBAC / access-log）内网自用可以先不碰
4. **技术栈不能整体移植**：React + Radix + TanStack Query ≠ Vue + Element Plus。但 `lib/`、`features/*/lib`、事件字典这些**纯逻辑是语言无关的**，可以一比一翻译 —— 恰好是最值钱的部分

---

## 13. 对你项目的直接映射

| 你的现状 | 它的做法 | 你要补的 |
| --- | --- | --- |
| trace 存内存（`AgentTraceRegistry`，重启即丢） | durable event log + 单调 seq 落库 | 建 run/step/tool_call 表，写入时分配 seq |
| 前端只能轮询 `/api/traces` | SSE 断点续传 + 游标补洞 | 加 `/events/stream?after=` 与 `Last-Event-ID` |
| 靠 middleware 拼阶段树 | 投影层统一归一化多种来源 | 抽一层"事件 → UI 模型"，别让组件直接读原始事件 |
| 无事件语义说明 | `eventPresentation` 事件字典 | 给每个阶段配标题/解释/关联实体 |
| 会话状态是本地文件 | 事件日志是权威，状态可重建 | 先解决"事件可重放"，再谈状态存储 |

---

## 附：关键文件清单（可直接对照阅读）

```
frontend/src/features/conversation/eventCursor.ts          游标连续性合并（24 行，核心）
frontend/src/features/conversation/eventCursor.test.ts     它的测试
frontend/src/features/conversation/model.ts                投影数据模型（设计纲领在注释里）
frontend/src/features/conversation/adapters.ts             事件 → 消息/事件 投影（14.7KB）
frontend/src/features/conversation/eventPresentation.ts    事件字典（人话 + 关联实体）
frontend/src/features/operate/lib/useSessionEvents.ts      历史分页 + SSE + 补洞 + generation
frontend/src/features/operate/api.ts                       streamSessionEvents（手写 SSE，:388）
frontend/src/features/operate/components/ContextPanel.tsx  上下文检查器
frontend/src/app/AppShell.tsx                              布局 + 数据驱动导航
frontend/src/app/useCollaborationEvents.ts                 应用级 WebSocket（只做 invalidate）
frontend/src/lib/apiClient.ts                              fetch 封装 + 401 拦截 + 作用域双写
frontend/src/main.tsx                                      路由表 + legacy 重定向
```