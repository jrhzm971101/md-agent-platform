# 2026-09-24 错误响应体：资源不存在一律带可读原因

## 一句话结论

后端所有「资源不存在 / 请求非法」的响应，**状态码不变，但 body 里必须有一句用户看得懂的中文原因**
（`{"error": "..."}`）。空 body 的 `404` / `400` 已经全部清掉，前端只负责把这句话显示出来。

## 问题现场（实测，改动前）

同一次排查里两个 404 的表现完全不同，这是本次要抹平的差异：

| 请求 | 状态码 | content-type | body |
| --- | --- | --- | --- |
| `GET /agent-platform/api/nope`（路由不存在） | 404 | `application/json` | `{"timestamp":...,"error":"Not Found",...}` |
| `GET /agent-platform/api/traces/no-such-run` | 404 | 空 | **空** |
| `GET /agent-platform/api/agents/no-such-agent` | 404 | 空 | **空** |
| `POST /agent-platform/api/agents/no-such-agent/chat` | 404 | 空 | **空** |

路由不存在那一行是 Spring Boot 自带的错误处理给的，**一直是有 body 的**；真正空的是业务分支里
`ResponseEntity.notFound().build()` 这种写法。它传到页面只剩一句「请求失败 (404)」：

- 用户分不清是**名字打错了**、**资源被删了**，还是**后端根本没这个端点**；
- 本平台的资源全是人工在控制台里建的，名字打错一个字符是常态 —— 空 body 等于让用户自己猜。

## 改动清单（两个项目同步）

| 文件 | 端点 | 之前 | 现在 |
| --- | --- | --- | --- |
| `chat/AgentChatController` | `POST /api/agents/{id}/chat` | `400` 空 body / `404` 空 body | `消息内容不能为空` / `agent 不存在或未装配: X（到 Agent 列表确认它已发布，或点一次「重载」）` |
| `agent/AgentController` | `GET /api/agents/{name}` | `404` 空 body | `agent 不存在: X` |
| `controlplane/ResourceController` | `GET /api/resources/prompts/{key}` | `404` 空 body | `prompt 不存在: X`（复用已有的 `notFound(kind, name)`） |
| `controlplane/ResourceController` | `GET /api/resources/mcp/{name}` | `404` 空 body | `mcp 不存在: X`（同上） |
| `trace/AgentTraceController` | `GET /api/traces/{runId}` | `404` 空 body | `run 不存在: X` |
| `trace/AgentTraceController` | `GET /api/traces/{runId}/events` | `404` 空 body | `run 不存在: X` |
| `trace/AgentTraceController` | `GET /api/traces/{runId}/events/stream`（SSE） | `404` 空 body | `run 不存在: X`（JSON，不是 SSE 帧） |
| `workflow/WorkflowController` | `GET /api/workflows/{key}` | `404` 空 body | `workflow 不存在: X`（复用 `notFound(key)`） |
| `model/ModelController` | `GET /api/models/{modelKey}` | `404` 空 body | `模型不存在: X`（复用 `notFound(modelKey)`） |

前端同步：`src/features/chat/useChat.ts` 的 `describe()` 删掉了「按状态码另写一套文案」
（原先 `404` → `agent 不存在或未装配：X`、`400` → `请求被拒绝：消息内容为空`），改为直接显示
`ApiError.message` —— 也就是后端 `error` 字段（`lib/apiClient.ts` 的 `readApiFailure` 读的就是它）。

## 关键决策

**1. 为什么必须带 body，而不是保持"干净的 404"**
`readApiFailure` 的取值顺序是 `error` → `message` → `请求失败 (状态码)`。换句话说
**body 是前端唯一的信息来源**，状态码只在没有 body 时用来兜底。想让用户看到原因，就只能写在 body 里。

**2. 为什么把这些端点的返回类型放宽成 `ResponseEntity<?>`**
错误体是 `Map`、成功体是领域对象，两者没有公共泛型；写死成 `ResponseEntity<TraceRunInfo>` 这类
根本放不进 `Map`（编译期就会失败）。放宽成通配符与这些控制器里**已有的** `notFound(...)` / `conflict(...)`
私有方法完全一致 —— 沿用它，而不是为新代码另立一种风格。

**3. 为什么 SSE 端点的错误体要显式写 `content-type: application/json`**
两个 SSE 端点的 `produces` 是 `text/event-stream`。返回 `Map` 时若不指定 content-type，
写响应体时会去挑 SSE 那套帧编码器。显式声明之后，错误响应是纯 JSON，状态码语义（真 404、
前端据此停止重连）一个字都没动。这一点由测试钉死，见下面 `unknownRunStreamIs404`。

**4. 为什么不让所有控制器共用一个 `notFound` 工具类**
`ResourceController` / `WorkflowController` / `ModelController` 各自已经有一个私有的
`notFound(...)`，文案里的名词不同（`prompt` / `mcp` / `workflow` / `模型`）。抽成公共类意味着
把「哪个资源该怎么称呼」这件事挪到一个离调用点很远的地方，而这几处一共只省下三行。
（同一个理由写在 `WorkflowController.blocking` 的注释里，是既有约定。）

**5. 为什么前端不再按状态码猜文案**
原先 404 那句「agent 不存在或未装配」曾经把排查方向带偏 —— 真因是**新建 agent 没热装配**
（已单独修掉），但页面上看起来像请求发错了地方。文案只有一个来源（后端），改一处就够了。

## 测试与验证

| 层 | 断言 | 位置 |
| --- | --- | --- |
| HTTP 契约（后端） | 空消息 `400` 的 body 是 `消息内容不能为空` | `AgentChatControllerTest.blankMessageIsBadRequest` |
| HTTP 契约（后端） | 未知 agent 的 `404` body 里带这个 agentId | `AgentChatControllerTest.unknownAgentIsNotFound` |
| HTTP 契约（后端） | 未知 run 在 `/events`、`/events/stream` 上都是真 404 + `run 不存在: X` | `AgentTraceControllerTest.unknownRunReturns404OnEveryReadEndpoint`、`unknownRunStreamIs404` |
| 前端状态机 | 404 的 `turn.error` 等于后端给的那句话（含括号里的下一步） | `useChat.test.ts` →「useChat 错误文案」 |
| 前端状态机 | 后端没给文案时（网络层失败）落回 `Error.message` | 同上 |

命令（两个项目各跑一次）：

```bash
mvn -o -pl md-agent-bootstrap -am test -Dtest=AgentChatControllerTest,AgentTraceControllerTest
cd ../agent-contro-plane && npm run typecheck && npm test
```

结果：后端 18 例通过（7 + 11）；前端 `typecheck` 0 错、143 例通过。

## 遗留

**未匹配的路由**（写错路径、少了平台前缀）仍然走 Spring Boot 自带的错误处理，body 是
`{"timestamp":...,"error":"Not Found","path":...}` —— 有 body，但是英文。
要变成中文得改写 `ErrorAttributes` / 注册 `WebExceptionHandler`，属于**全局**行为，
会影响所有错误（含静态资源），本次没有动。真要改，建议只覆盖「No matching handler」这一类，
并同样用测试钉住 status 语义不变。