# 页面对话：从输入框到 agent 输出的流式链路

> 记录时间：2026-09-21
> 涉及仓库：后端 `D:\jinran\code\Md-Agent-Platform`；前端 `D:\jinran\code\agent-contro-plane`
> 覆盖范围：`POST /api/agents/{agentId}/chat`（SSE）→ 帧翻译层 → 对话页
> 验证状态：后端全量 `Tests run: 260, Failures: 0, Errors: 0` + `BUILD SUCCESS`；前端 `vue-tsc` 通过、`vitest` 50/50 通过、`vite build` 通过；并已用无头浏览器打真后端跑通两条真实对话（Markdown / 高亮 / 表格 / 思考区均按预期渲染）
> 组织方式：结论 → 契约 → 分层 → 取舍 → 改动清单 → 验证 → 联调 → 已知缺口

---

## 0. 结论速览

1. **一条新端点**：`POST /api/agents/{agentId}/chat`，`text/event-stream`，请求体 `{ message, sessionId?, userId? }`。只传 `agentId` + 消息即可跑通，会话键与用户标识都可省。
2. **帧词表只有 6 个值**：`run / delta / thinking / tool / done / error`。前 5 个是正常路径，最后一个只在失败时出现，且后面**一定**还跟一帧 `done`。
3. **不做 mock**：对话页始终打真后端。给它造一份假回答只能测出前端拼字符串对不对，测不出后端、模型、工具任何一环。
4. **翻译层单独一层**（`ChatStreamService`），控制器只做协议适配。这与链路 SSE 的切法一致：server 模块不出现任何 Web 注解。
5. **观测是白送的**：所有 agent 装配时都挂了运行链路 middleware，所以页面上的每次对话都会在执行链路页留下一条完整 run，本功能不需要为此写一行代码。
6. **与链路 SSE 最关键的一处相反**：对话流**必须收口**（`done` 帧后关连接），而链路 SSE **永不关闭**（run 结束也不关）。理由见 4.5。
7. **回答按 Markdown 渲染**（含代码高亮），用户消息保持纯文本；`thinking` 帧做成正文上方一个默认展开、正文到达即自动收起的折叠块。两条的取舍见 4.8 / 4.9 / 4.10。

---

## 1. 接口契约

```
POST /api/agents/{agentId}/chat
Content-Type: application/json
Accept: text/event-stream

{
  "message": "把 deployment 环境检查一遍",
  "sessionId": "console-a1b2c3d4",   // 可省：省了后端现生成一个并回执
  "userId": "console"                // 可省：省了后端用默认用户 console，实际生效值同样回执
}
```

响应：SSE。`data:` 行是整帧 JSON，`event:` 行冗余写类型（只为 curl 直读，前端以 JSON 里的 `type` 为准）。

| type | data 字段 | 前端动作 |
| --- | --- | --- |
| `run` | `agentId` / `sessionId` / `userId` / `runId` / `startedAtMs` | 记住会话键、生效的用户标识，以及这次执行的链路 id |
| `delta` | `text` | 追加到当前回答 |
| `thinking` | `text` | 单独存（本版不展示） |
| `tool` | `callId` / `name` / `phase`(start\|end) | 显示「正在调用 xxx…」 |
| `done` | `status`(DONE\|ERROR) / `elapsedMs` | 收尾，停止 loading |
| `error` | `message` | 显示失败摘要（后面仍有一帧 done） |

约定：

- **不写 `id:` 行**。本接口不支持断点续传，写一个不处理的游标只会让后来的人以为可以续传。
- **心跳走注释行**（`:keep-alive`），前端天然忽略。间隔 `agent-platform.chat-heartbeat-ms`（默认 15000，实现方最小抬到 1000）。
- **两个错误码在流开始之前定下来**：消息为空 → 400，agent 不存在 → 404。SSE 一旦开始写响应体就改不了状态码，那时再发现参数错，前端只会收到一条 200 的空流。
- **`userId` 与 `sessionId` 同级处理**：两者最终都会落到「键 / 路径」上（见 4.2 / 4.3），都要过同一套消毒与限长，`run` 帧里回执的都是消毒后的**实际生效值**，不是请求原值。
- **`runId` 由后端受理时定下**，不由前端传、也不由中间件现场随机生成（见 4.4）。它就是 `GET /api/traces/{runId}` 里的那个 id，前端拿到后直接拼链接即可，不需要再查一次"最近一条 run"。

---

## 2. 分层

```
bootstrap  AgentChatController      协议适配：状态码、SSE 帧、心跳、takeUntil 收口
   ↓
server     ChatStreamService       AgentEvent 流 → ChatStreamEvent 帧流（唯一翻译点）
           ChatStreamEvent         帧模型（词表常量 + 各帧工厂方法）
```

为什么不让控制器直接翻译：控制器是 HTTP 边界，`bootstrap` 模块里只该有协议代码；「哪些事件值得转成帧」是领域知识，改它不该动 Web 层。官方 service 也是这个切法（web 层只翻译，dataplane 层产事件）。

**与企微 / 飞书那条链路的区别**：那两条是「平台主动推消息」，renderer 自己管分片、节流、卡片更新；本类不管这些，它只是**只读观察者 + 翻译器**，不产生副作用、不改 agent 行为。

---

## 3. 前端

```
features/chat/model.ts      ChatFrame / ChatTurn 类型 + 词表校验
features/chat/api.ts        splitSseFrames / decodeChatFrame / streamChat / fetchChatAgents
features/chat/markdown.ts   回答正文的 Markdown → HTML（页面里唯一的 v-html 入口）
features/chat/highlight.css 代码块的语法配色（只做颜色，底色描边归 ChatPage）
features/chat/useChat.ts    状态机：turns / sending / sessionId / userId / send / stop / reset
pages/ChatPage.vue          布局：工具行 + 消息区 + 输入区
```

- **用 fetch + ReadableStream，不用 EventSource**：EventSource 只能发 GET，消息正文塞进 query 会撞长度上限。代价是自己切帧 —— `splitSseFrames` 是纯函数，单测覆盖了「逐字符喂入不丢帧」这条不变式（网络分片不保证落在帧边界上，丢掉尾部残帧的表现是「回答中间少一段」且几乎无法复现）。
- **`meta.flush`**：路由新增的标记，让 `AppShell` 的内容区去掉内边距并把滚动权交给页面。只有「整屏自成一体」的页面需要它，目前只有对话页。
- **Enter 发送要判 `event.isComposing`**：中文输入法组词期间的回车是「确认候选词」，不判这一条，打拼音时按回车会把半截词发出去。
- **agent 列表不走 `api/agents.ts`**：那个跟随全局 mock 开关（形状是 `AgentSummary`），本页要的是后端原样返回的 `{ name, type }`。两边数据源不同会出现「下拉里有、调用时说 404」，所以单独一个 `queryKeys.chatAgents`。
- **回答按 Markdown 渲染，用户消息不渲染**：主流 AI App 都是「agent 说的排版、用户说的原样」，这里对齐这个观感。渲染只走 `renderMarkdown()` 一个出口（`markdown-it` + `html:false`），用 `v-html` 挂载 —— 所以它是页面里唯一的 XSS 面，取舍见 4.8 / 4.9。
- **思考过程排在正文上面、默认展开**：时序上思考本来就在前；更实际的原因是正文首字到达时思考区会自动收起，若它在下面，正文会把整块往下顶，「顶下去」和「收起来」撞在同一帧上，看起来像页面跳了一下。取舍见 4.10。

---

## 4. 设计取舍

### 4.1 为什么不复用 `AgentRunEvent`

那是**观测**语义：一次 run 的阶段边界，只增不改、带 seq 游标、可以事后回放、要落库归档。对话帧是**传输**语义：一次请求的即时输出，只在订阅期间有效，断了就没了。两者的消费方、生命周期、字段集合完全不重叠，硬合并的结果是两边都长出一堆自己不需要的字段。

### 4.2 为什么帧要回执 sessionId

会话键对前端是**输入**：请求没带时后端现生成一个，前端必须拿到才能让第 2 轮落进同一个会话（记忆按 `(userId, sessionId)` 分区）。不回传的话，多轮对话会变成每次都是新会话。

会话键还**必须过 `sanitize`**：harness 把它直接当成任务文件名，框架的校验只拦斜杠，冒号能过校验但在 Windows 下会让 `Path.of` 抛异常。这条坑在企微链路上已经踩过一次（见 `HarnessSessionIdTest`）。

### 4.3 为什么请求里要带 userId，以及它为什么必须消毒

userId 不是「给会话贴个标签」，它是记忆分区的一半：框架的状态存储接口签名就是
`save(userId, sessionId, key, value)`，换 userId 等于换一个人（另起一份上下文）。

**它落在哪，取决于用哪个状态存储** —— 这点容易想当然，值得先确认再下结论：

| 状态存储 | `(userId, sessionId)` 的去向 |
| --- | --- |
| 本项目（`MysqlAgentStateStore`） | 拼成 `<userId>:<sessionId>` 存进 per-agent 状态表的 `session_id` 列。库里现有数据长这样：`wo7U1ZEQAApziTCwT6jow9UKLs0xrqlw:sub-72aea1e9-a588-...` |
| 框架默认（`JsonFileAgentStateStore`） | 变成两级目录：`<root>/<agentId>/<userId>/<sessionId>/`；userId 为空时是 `__anon__` |

（另外顺带记一条：会话事件 / 任务的目录用的是 `{tenant}/{agentId}/{sessionId}`，`tenant` 是**独立的一维**、默认 `default`，它不等于 userId —— 所以在 `workspace/default/<agent>/<session>/events/` 里找 userId 是找不到的。）

所以消毒有两条独立的理由：

1. **它是路径片段**。文件状态存储下 userId 直接是目录名，不消毒的话一个带 `/` 或 `\` 的值就能把对话产物写到工作区外面去。
2. **它和会话键拼在同一个键里**。MySQL 状态存储用 `:` 拼接，userId 自身带 `:` 就会把「谁」和「哪个会话」的边界搅乱 —— 消毒同时把这类歧义堵掉。

点号序列单独挡一道：`.` / `..` 能通过字符集校验（点号是允许字符），但作为目录名会让路径解析跳到上一层 —— 这是唯一一条「洗完仍然危险」的值，所以直接回落到默认用户，不做静默改写（改写后 `..` 会变成别的东西，磁盘上更难对上号）。

前端的取值约定：控制台不传「匿名」，而是显式传 `CONSOLE_USER_ID`（见 `features/chat/model.ts`）。理由是这样「页面发起的对话」与企微 / 飞书来的天然分开、不共用同一份记忆；接入登录后把那个常量换成登录用户 id 即可，流式解析那一层不用动。

### 4.4 runId 是谁生成的，为什么不能等跑完再回查

页面上要能点着「这条回答」跳到「那次执行」，就需要一个在**调用开始之前就存在**的 id。
这不是顺手就能拿到的：中间件里的 runId 是订阅事件流那一刻现生成的（`AgentTraceRegistry#start`），
翻译层根本看不到它。等跑完再回查「最近一条 run」也不行 —— 同一个会话里连着问两句、
或同一时刻别人也在跑，都会认错人。

所以方向反过来：**调用方先定 id，中间件照用**。

```
调用方：ChatStreamService / WeComThinkRenderer / FeishuCardRenderer / DailyReportSendWxJob
  │   runId = TraceRunIdGenerator.next()
  │   RuntimeContext.put(AgentTraceRegistry.RUN_ID_ATTR, runId)
  ▼
AgentRunTraceMiddleware#onAgent
  │   traceRegistry.start(..., preferredRunId = peekRunId(ctx))
  ▼
AgentTraceRegistry#resolveRunId
        形状合法 && 未被占用  →  就用它
        否则                  →  现生成一个，并 warn
```

几个刻意的选择：

- **不给 `AgentManager.stream(...)` 加参数**，而是走 `RuntimeContext` 的属性袋：runId 只有「观测」这一件事需要，
  业务链路的每一步（agent → 子 agent → 工具）都不该知道它存在。
- **建议值只是建议**：形状不合法（含空格 / 问号 / 换行 / 全点号 …… 会破坏 URL、日志或库表主键）
  或已被占用时，Registry 换成现生成的 id 并打 warn。静默接受会把两次 run 并成一条，
  那比没有观测更误导人。
- **生成要放在 `Flux.defer` 内**：同一个 Flux 被订阅两次必须是两个 run，否则页面上两条回答指向同一条链路
  （有单测 `eachConversationGetsItsOwnRunId` 盯着）。
- **企微 / 飞书 / 日报也生成**：这三条链路没有页面可以回执 id，它们把 id 写进自己的日志
  （`[WeCom] 开始 run=…`）—— 那是「刚收到的那条回复跑的是哪棵树」唯一的线索。

### 4.5 异常为什么在这里就地转成帧，而链路 SSE 要往外抛

- **链路 SSE**：订阅式观测。异常抛出去 → 前端退避重连 → 补齐缺口。重连是安全的（只读）。
- **对话**：一问一答。重连等于把 agent **再跑一遍** —— 有副作用、要花 token。所以异常在服务层转成 `error` 帧（紧跟一帧 `done`），连接正常结束。

同一件事的两种做法，判据是「重连是否有害」。

### 4.6 `takeUntil` 是控制器里最容易写错的一行

```java
return Flux.merge(data, heartbeats)
        .takeUntil(frame -> TYPE_DONE.equals(frame.event()) || TYPE_ERROR.equals(frame.event()));
```

`Flux.interval` 永远不结束，直接 merge 出去连接就永远不会关 —— 页面表现为「答完了但还在转圈」。链路 SSE 是故意不关的，所以那个控制器里没有这一行，照抄过来就会错。

### 4.7 文本增量的兜底

不是所有模型 / 配置都会产生 `TextBlockDelta`（有的只在最后一次性给出全文）。只认 delta 的话，这类情况页面会一直空白到 `done`，看起来像「agent 没回话」。所以 `AgentResultEvent` 到达时，如果这一轮**没有**流过任何增量，就把整段结果补发一次 delta。已流过增量时不能再发，否则页面上会出现两份回答。

### 4.8 为什么用 `markdown-it`，以及 `html: false` 是硬约束

agent 的回答天然是 Markdown（带代码块、表格、有序列表），照纯文本渲染等于把 `**` 和 `|` 原样怼给用户看。这个功能只有二十来行，但它是页面里唯一一处 `v-html`，所以几条边界都值得钉住：

- **不自己写解析器**：手写正则方案在「未闭合的 ``` 围栏」「行内反引号里还夹着星号」这些地方必错，而错了不抛异常，只会把回答渲染成一团乱码 —— 在页面上看起来像「模型答得乱七八糟」，排查方向会被直接带偏。
- **`html: false` 不是可选配置**：正文来自模型与它调用的工具，属于不可信输入。开着它等于把 XSS 开在自己的控制台上（`<img src=x onerror=...>` 就够了）。关掉之后原始 HTML 一律转义成字面量，`markdown.test.ts` 把这条钉成了契约。
- **不额外挂 sanitizer**：`markdown-it` 自带的 `validateLink` 已经挡掉 `javascript:` / `vbscript:` / `file:` 这类协议（`data:` 只放行图片）。少一个依赖，也少一处「换库后对不上」的地方。
- **外链补 `rel="noopener noreferrer"`**：渲染出来的基本都是外部地址，不补的话目标页能通过 `window.opener` 反向操作本页。
- **流式下边收边渲染**：半截文本（停在 ``` 之后、停在 `**` 中间）对 `markdown-it` 不抛异常，会退化成普通文本。若等文本齐了再渲染，用户在整段生成期间看到的都将是原文而非排版结果 —— 打字机效果就没了。

### 4.9 代码高亮：为什么按语言白名单引，为什么不用官方主题

agent 答技术问题十有八九带代码块，无高亮时整块是同一个颜色，`public` 和字符串分不出来。

- **引 `highlight.js/lib/core` 逐个注册，而不是整个包**：完整包带 190 多种语法，解压后接近 1 MB；本平台这几个 agent（k8s 巡检、报表、git、工时统计）实际吐出来的就是 java / xml / yaml / json / bash / sql / diff / properties / dockerfile / ini / python / js / ts / markdown 这些。白名单写成一个 `LANGUAGES` map，要加语言就往里加一行。实测 15 种语法让对话页那个分包从 110 kB 涨到 172 kB（gzip 46 → 66 kB）—— 它是懒加载的，只有进对话页才下载。
- **语言名要消毒**：围栏后面那串字（```java 里的 `java`）是模型输出的一部分，会被拼进 `<code class="language-xxx">`。白名单正则 `[a-z0-9+#._-]{1,32}` 把「靠语言名把属性引号闭合掉、再塞事件属性」那条路堵死；`escapeAttr` 只是第二道，不指望它。
- **不写语言名 / 语言不在名单 / 高亮抛异常**，三种情况都返回空串让 `markdown-it` 自己走默认路径（转义 + 包一层 `<pre><code>`）。高亮是锦上添花，不该有能力把正文搞坏。
- **不用官方主题包**（`node_modules/highlight.js/styles/*.css`）：那些主题自带底色、描边、圆角，引进来等于在页面里塞第二套皮肤。本项目的规矩是颜色只有 `tokens.css` 一个来源，所以 `highlight.css` 只做「hljs 语义类 → 既有令牌」的映射，底色与描边留给 `ChatPage.vue` 的 `.bub__md :deep(pre)` —— 两边都写会互相覆盖，而 scoped 选择器权重更高，结果是「改了主题没反应」。

### 4.10 思考过程为什么默认展开、又为什么自动收起

`thinking` 帧原先只是躺在 `ChatTurn.thinking` 里不展示 —— 于是模型想得久的时候，页面上就是一个空气泡，看起来像卡死。现在把它做成正文上方一个可折叠块。

- **默认展开**：思考刚来时它是页面上唯一有动静的东西，折叠着等于什么都没显示。
- **正文首字到达时自动收起**（`useChat.ts` 的 `autoCollapseThinking`）：这个动作本身就是个界面信号 ——「想完了，开始答了」。`done` / `error` 也各收一次，所以「想完直接结束」「想一半报错」这两条小概率路径上它也不会敞着。
- **记一个 `thinkingTouched`**：用户手动点过之后，自动规则一律让位。他刚展开又被页面自动收回去，那种「点了没用」的感觉比自动收起本身更糟。
- **不用 Markdown 渲染思考正文**：思考是「心路」不是「交付物」，过渲染器只会让它比正文更抢眼；而且每多一处 `v-html` 就多一个 XSS 面。配 `pre-wrap` 保留换行就够（模型常按行推理）。
- **`max-height: 220px` + 自己滚**：长思考动辄几千字，全展开会把正文顶出屏幕。思考是要能撇一眼的，不是要占满视野的。
- **`thinking` 帧不再写活动提示**：原先它会在没有正文时挂一句「思考中…」。内容已经在页面上滚了，再说一遍是重复，还会占掉「正在调用 xxx」那行。真空白的兜底改由页面按状态给（`ChatPage.vue` 的 `idleHint`：既没正文也没思考才提示「思考中…」）。

---

## 5. 改动清单

**后端（`md-agent-server`）**

| 文件 | 说明 |
| --- | --- |
| `server/chat/ChatStreamEvent.java` | 新增。帧模型 + 词表常量 + 6 个工厂方法；`run` 帧回执 `runId` |
| `server/chat/ChatStreamService.java` | 新增。事件 → 帧的翻译、会话键 / 用户键解析、runId 定名、错误就地收口 |
| `src/test/.../server/chat/ChatStreamServiceTest.java` | 新增，16 例（用户键 5 例 + run 回执 2 例） |
| `server/trace/TraceRunIdGenerator.java` | 加 `isWellFormed` + `MAX_LENGTH`（调用方自带 id 的形状校验） |
| `server/trace/AgentTraceRegistry.java` | 加 `RUN_ID_ATTR` / `attachRunId` / `peekRunId`，`start` 支持 `preferredRunId` |
| `server/agent/middleware/AgentRunTraceMiddleware.java` | 建 run 时优先采用上下文里建议的 runId |
| `src/test/.../trace/TraceRunIdGeneratorTest.java` | 加 4 例（字符集 / 限长 / 全点号） |
| `src/test/.../middleware/AgentRunTraceMiddlewareTest.java` | 加 3 例（采纳 / 重号回落 / 非法回落） |
| `server/messaging/render/WeComThinkRenderer.java` | 定 runId 并放进上下文，日志带 `run=` |
| `server/messaging/render/FeishuCardRenderer.java` | 同上（卡片上不显示，只进日志） |
| `server/schedule/DailyReportSendWxJob.java` | 同上，三处日志都带 `run=` |

**后端（`md-agent-bootstrap`）**

| 文件 | 说明 |
| --- | --- |
| `controller/chat/AgentChatController.java` | 新增。SSE 端点 + `frames()` + `toSse()`；请求体含可选 `userId` |
| `src/test/.../controller/chat/AgentChatControllerTest.java` | 新增，7 例 |
| `resources/application.yml` | 加 `agent-platform.chat-heartbeat-ms` |

**前端（`agent-contro-plane`）**

| 文件 | 说明 |
| --- | --- |
| `src/features/chat/model.ts` | 新增。类型 + 词表校验 + `CONSOLE_USER_ID` + `ChatTurn.runId` |
| `src/features/chat/api.ts` | 新增。切帧 / 解码 / `streamChat`（带 `userId`） / `fetchChatAgents` |
| `src/features/chat/markdown.ts` | 新增。`markdown-it` 封装（`html:false` / `linkify` / `breaks`）+ 外链 `rel=noopener` + 代码高亮（`highlight.js` core + 15 种语法白名单） |
| `src/features/chat/highlight.css` | 新增。hljs 语义类 → 设计令牌的配色映射；不设底色描边（归 `ChatPage.vue`） |
| `src/features/chat/useChat.ts` | 新增。对话状态机（会话键 / 用户标识按 agent 存，`runId` 按轮次存；思考区 `thinkingOpen` / `thinkingTouched`） |
| `src/features/chat/sseFrames.test.ts` | 新增，11 例 |
| `src/features/chat/markdown.test.ts` | 新增，17 例（HTML 转义 / 协议拦截 / 外链属性 / 半截文本容错 / 高亮与语言名消毒） |
| `src/features/chat/useChat.test.ts` | 新增，8 例（run 帧回填 / 思考自动展开与收起 / 用户手动展开优先 / 停止不算失败） |
| `src/pages/ChatPage.vue` | 新增。对话页（顶栏显示 `userId` 与会话键；思考区折叠块；每条回答下方给链路入口） |
| `src/app/router.ts` | 加 `/chat` 路由 + `meta.flush` |
| `src/app/nav.ts` | 加「使用 / 对话」导航组 |
| `src/app/AppShell.vue` | 支持 `meta.flush` 布局 + 对话图标 |
| `src/lib/queryKeys.ts` | 加 `chatAgents` |

---

## 6. 验证

- 后端：`mvn -o test` → **260 passed / 0 failed**（server 242 + bootstrap 18；对话这一功能的用例合计 16 + 7 = 23 例），`BUILD SUCCESS`。
- 前端：`npm run typecheck` 通过；`npm test` → 50 passed（`sseFrames` 11 + `markdown` 17 + `useChat` 8 + 其余 14）；`npm run build` 通过。注意 `ChatPage` 那个 chunk 因 markdown-it + highlight.js 涨到约 172 kB（gzip 66 kB；页面级懒加载，只有进对话页才下载）。
- 页面渲染：开发服务器（5273）打开 `/chat`，侧栏出现「使用 / 对话」，agent 下拉已由真接口 `/api/agents` 填满（默认选中第一个），空态与输入区正常。
- **端到端真机跑通**（无头 Chrome 打 5273 → 代理到 9090 的真后端，两条对话各一次）：
  - `run` 帧回执 `runId`（形如 `mub0hii7-psy1xp`），页面底部的「执行链路 …」直接可点；
  - 代码块落到 `<pre class="hljs"><code class="hljs language-java">`，五种语义色与令牌逐一核对一致：关键字 `#3d3dc4`（`--pri-ink`）、字符串 `#0e8a50`（`--grn`）、数字 `#1552b8`（`--blu`）、注释 `#787891`（`--t3`）、类名 `#a15c07`（`--amb`）；
  - 代码块底色 `#eeeef7`（`--panel-sunken`）、表头底色 `#f6f6fc`（`--panel-soft`）—— 即 `ChatPage.vue` 的 scoped 规则赢过主题文件，与 4.9 的设计一致；
  - 思考区：流中为 `think think--open`（正文可见、左侧 2px 竖线、`max-height:220px`），`done` 后变 `think`（收起、三角转回）；手动点击折叠头后重新展开并保持展开；
  - assistant 气泡的 DOM 顺序为 `think → bub__md → bub__trace`，与 4.10 里「思考排在上面」的说法一致。

---

## 7. 联调步骤

1. **重启后端**（`md-agent-bootstrap`）。启动日志里应能看到新端点注册，且 `GET /api/agents` 仍返回 14 个 agent。
2. 自检端点（不花 token，验路由是否生效）：
   ```powershell
   # 期望 400（消息为空）—— 若返回 404，说明跑的还是旧包
   curl.exe -i -X POST http://localhost:9090/api/agents/k8s_agent/chat `
     -H "Content-Type: application/json" -d "{\"message\":\"  \"}"
   ```
3. 真跑一次（会花 token、可能有副作用，选只读的 agent 更稳）：
   ```powershell
   curl.exe -N -X POST http://localhost:9090/api/agents/k8s_agent/chat `
     -H "Content-Type: application/json" -H "Accept: text/event-stream" `
     -d "{\"message\":\"介绍一下你自己\"}"
   ```
   （要验 `userId` 就在 `-d` 里加 `\"userId\":\"u1\"`；带非法字符或全点号的值只会在后端日志里留下告警，不会报错。）
   期望顺序：`run` → （`thinking` / `tool` …）→ `delta` … → `done`，且**连接自己断开**。
4. 打开 `http://localhost:5273/chat` 发一句话，验收：
   - 文字逐段出现，跑完「发送」按钮恢复可点；
   - 顶栏出现用户标识（默认 `console`）与会话键；第二句话仍带同一个会话键（说明记忆接上了）；
   - 状态表（如 `k8s_agent`）里新增的 `session_id` 形如 `console:<顶栏那个键>`，与企微 / 飞书来的（`wo7U1Z...:<会话>`）分开；
   - 此时切到「执行链路」页，最新一条 run 的 agent 就是刚才那个 agent、`session_id` 就是顶栏那个键。

---

## 8. 已知缺口

| 缺口 | 说明 |
| --- | --- |
| 工具调用只显示一行状态 | 工具入参 / 结果没有展示位；要看细节去执行链路页 |
| 思考区不区分「思考」与「推理摘要」 | 现在只按 `thinking` 帧原样拼；有的模型会连着输出多段，页面上是一条连续的灰字，没有段间标题 |
| 高亮语言是白名单 | 名单外的语言（go / nginx / c++…）退化成纯文本。加语言改 `markdown.ts` 的 `LANGUAGES` 一行，代价是那个分包再大一点 |
| 对话历史不持久 | 刷新页面即丢。`ChatTurn` 没有落库，"历史会话"目前由后端的状态文件（harness 会话 jsonl）承担 |
| 不限制并发 / 无鉴权 | 单实例自用阶段够用；多用户前必须补 |
