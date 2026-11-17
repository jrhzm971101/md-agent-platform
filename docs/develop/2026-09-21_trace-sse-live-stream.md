# 运行链路观测：Step 3（SSE 实时推送）

> 记录时间：2026-09-21
> 涉及仓库：后端 `D:\jinran\code\Md-Agent-Platform`；前端 `D:\jinran\code\agent-contro-plane`（本次只改了三处过时文案）
> 覆盖范围：唤醒通道 → 数据面（Flux）→ SSE 端点 → 契约测试 → 前端文案
> 验证状态：后端全量 `Tests run: 242, Failures: 0, Errors: 0` + `BUILD SUCCESS`（含真库集成测试 6/6）
> 组织方式：目标 → 与官方实现的对应 → 数据怎么流到页面 → 改动清单 → 设计取舍 → 踩坑记录 → 前端契约 → 验证 → 联调步骤 → 已知缺口

---

## 0. 结论速览

1. **SSE 端点已落地**：`GET /api/traces/{runId}/events/stream`（`text/event-stream`），支持 `Last-Event-ID` 断线续传。页面上"链路正在长"不再依赖手动刷新。
2. **数据面的结构照搬官方**：`Flux.merge(订阅即读, 低频兜底轮询, 通知唤醒) → concatMap(按游标读) → concatMapIterable(推进游标)`，与官方 `SessionEventLog#subscribe(sessionId, afterSeq)` 一一对应。
3. **通知只唤醒、不承载数据**：事件进内存后广播一个 runId 信号，订阅者按自己的游标重新读一次。于是通知**可以丢、可以重**，正确性与这条通道的可靠性无关 —— 丢了有兜底轮询补。
4. **两个刻意保留的接线**：`onBackpressureLatest + concatMap`（唤醒可合并、读必须串行）、`subscribeOn(Schedulers.boundedElastic())`（回库读是阻塞的，不能待在 Netty 事件循环线程上）。
5. **一个只有集成测试才抓得到的坑**：`Mono.fromCallable` 的 Callable 返回 `null` 时给出的是**空 Mono**（不是"发一个 null"），渲染结果是 `200 + 空响应体`，而不是想要的 404。前端把 404 当作"别再重连"的信号，所以这条必须对。
6. **前端不改也能联调**：本轮前端只动了 `TracesPage.vue` 里三处已经过时的提示文案。

---

## 1. 目标与验收

### 1.1 要解决什么

Step 1/2 让"每次调用的链路"变得可查（内存热数据 + MySQL 归档），但页面看到的永远是一个**定格**的快照：想看到新出现的节点，得手动刷新或等列表轮询。"这次调用现在跑到哪了"是实时观测的核心诉求，而它需要一条**推送**通道。

### 1.2 验收标准

| 项 | 标准 |
| --- | --- |
| 实时性 | 事件写入内存后，订阅者的延迟应在毫秒级（不是"下一个轮询周期"） |
| 不丢 | 断线重连后不丢事件：从客户端游标的下一个号继续推，不重不漏 |
| 不重 | 重复帧无害（前端按 id 去重），但正常情况下不应重复推送已有数据 |
| 不阻塞业务 | 推送通道的任何异常（订阅者慢、通知丢）都不能拖慢 agent 线程 |
| 不倒退 | 关掉归档（`trace-archive-enabled=false`）时 SSE 仍然可用（数据面只依赖内存） |
| 前端兼容 | 前端 `features/traces` 一行不改即可联调 |

---

## 2. 与官方实现的对应关系

官方的参照物是 `agentscope-service` 里会话事件的两段代码：`service-common` 的 `SessionEventNotifier`（唤醒）与 `SessionEventLog#subscribe`（读取）。本项目把这两段拆成了三个类。

| 官方 | 本项目 | 差异与原因 |
| --- | --- | --- |
| `SessionEventNotifier`（进程内 Sinks + PostgreSQL LISTEN/NOTIFY） | `TraceStreamNotifier`（只保留进程内 Sinks） | 官方是多副本部署，写入在 A 副本、观看的人在 B 副本，必须跨实例广播；本项目单实例且事件只在进程内产生，加一层数据库通知只多出连接与重连逻辑，换不来正确性 |
| `SessionEventLog#subscribe`（查询 MySQL） | `TraceEventStream#stream`（读 Registry：内存优先、未命中回库） | 本项目的事件**先存在于内存**、归档是异步旁路，所以读取必须"内存优先"，否则页面会看到滞后于真实进度的数据 |
| `DataSessionApiController#streamEvents`（`Flux<ServerSentEvent<String>>` + `toSse`） | `AgentTraceController#streamEvents` / `frames` / `toSse` | 一致：`id` 放 seq、`event` 放类型、`data` 放 JSON。额外加了心跳帧（官方没有），见 5.4 |
| `SessionEventLog` 里的 `MAX_SEQ_RETRIES` 冲突重试 | 无 | 本项目 seq 由内存里的 `AtomicInteger` 分配，不存在并发写冲突；分页也明确不引入 MybatisPlusInterceptor（见 Step 2 决策） |

一句话：**骨架照抄官方，把"多副本 + 数据库为准"的那一层复杂度按本项目的部署形态砍掉了。**

---

## 3. 数据是怎么流到页面的

### 3.1 一次事件写入之后发生了什么

```
agent 线程                                        SSE 订阅者（页面）
   │
   │ registry.beginStage(...)
   │   ├─ ① run.addEvent(事件)        ← 必须最先：被叫醒后要能立刻读到它
   │   ├─ ② archiveEvent → 有界队列 → MySQL（异步，与页面无关）
   │   └─ ③ notifier.publish(runId) ────────┐ 唤醒信号（不带数据）
   │                                        ▼
   │                        TraceEventStream：被唤醒 → 读一次
   │                          ├─ registry.get(runId)     内存优先 / 未命中回 MySQL
   │                          ├─ translate(run) → List<AgentRunEvent>（纯函数）
   │                          └─ 只取 seq > cursor 的一段，并推进 cursor
   │                                        │
   │                                        ▼
   │                        SSE 帧：id:<seq>  event:<type>  data:<JSON>
   │
   └─ 兜底：每 trace-stream-recovery-interval-ms 无脑读一次（防唤醒丢失 / run 不在内存里）
```

顺序不能换：**先内存、再归档、最后唤醒**。反过来的话，被叫醒的一方读到的还是"这条事件之前"的状态，而它只会等下一条事件才醒 —— 页面表现就是"某个节点晚一拍才出现"。

### 3.2 三条唤醒来源

1. **订阅时立即读一次** —— 把"页面上已有的历史"与"从现在起的增量"之间的缝补上。少了它，新开的页面要等到下一条事件才有数据；
2. **通知唤醒** —— 主通道，事件一写入就推；
3. **低频兜底轮询**（默认 5 秒）—— 通知丢了的保险，也是"run 已被内存淘汰、只能回 MySQL 读"时唯一的发现途径。

---

## 4. 改动清单

### 4.1 新增（后端）

| 文件 | 作用 |
| --- | --- |
| `md-agent-server/.../server/trace/stream/TraceStreamNotifier.java` | 进程内唤醒通道：`publish(runId)` / `wakeups(runId)` |
| `md-agent-server/.../server/trace/stream/TraceEventStream.java` | 数据面：`Flux<AgentRunEvent> stream(runId, afterSeq)` |
| `md-agent-server/src/test/.../trace/stream/TraceEventStreamTest.java` | 5 例：订阅即读 / 游标 / 通知链路 / 兜底轮询 / run 不存在 |
| `md-agent-server/src/test/.../trace/stream/TraceStreamNotifierTest.java` | 3 例：只唤醒对应 run / 没人订阅时不抛不阻塞 / 退订后不抛 |

### 4.2 改动（后端）

| 文件 | 改动 |
| --- | --- |
| `AgentTraceRegistry` | 新增 `streamNotifier` 字段（`required=false`）；把四处 `run.addEvent + archiveEvent` 收口成 `record(run, event)`，在其中"内存 → 归档 → 唤醒" |
| `AgentRunEventTranslator` | 新增 `public static int indexAfterSeq(events, seq)`（二分），REST 与 SSE 共用一份游标切片实现 |
| `AgentPlatformProperties` | 新增 `trace-stream-recovery-interval-ms`（默认 5000）、`trace-stream-heartbeat-ms`（默认 15000） |
| `application.yml` | 上述两个配置项与注释 |
| `AgentTraceController` | 新增 SSE 端点 + `frames()` + `toSse()`；原来私有的 `firstIndexAfter` 换成翻译器的公共方法 |
| `AgentTraceControllerTest` | 新增 4 例：data 与 REST 同 JSON / 数据帧与心跳帧 / 从 `Last-Event-ID` 续传 / 不存在的 run 是 404 |

### 4.3 改动（前端）

| 文件 | 改动 |
| --- | --- |
| `src/pages/TracesPage.vue` | 三处文案：`SSE（Step 3）`→`SSE`；"实时推送未接通"→"实时推送中断"；图例说明改为"历史走 /api/traces，增量走 …/events/stream" |

---

## 5. 设计取舍

### 5.1 为什么通知里不带事件数据

如果用"通知携带事件"来推送，就要求这条通道**既不丢也不重**：丢一条，前端 `mergeContiguousEvents` 只放行从游标起的连续前缀，缺号就永久卡住，后面的事件全部积压不渲染；重一条，就要在服务端去重。

改成"通知只负责叫人起床，读的人按游标自己读"之后：

- 丢了 → 兜底轮询会补；
- 重了 → 游标没动，一条都不发（读是幂等的）；
- 于是这条通道不需要任何可靠性保证，`tryEmitNext` 失败直接忽略，也不会因为"没人看页面"而刷日志。

### 5.2 为什么是 `onBackpressureLatest` + `concatMap`

- `onBackpressureLatest`：唤醒只需要"最新有没有人写"，积压 100 个唤醒与积压 1 个等价，多攒只是浪费；
- `concatMap`（而不是 `flatMap`，并发 1）：**读必须串行**。两次读并发会各自读到同一批事件、各自推进同一个 `AtomicLong` 游标，发出重复帧。

### 5.3 为什么读要切到 `boundedElastic`

项目跑在 Netty 上，控制器方法默认在**事件循环线程**里执行。`registry.get(runId)` 在内存未命中时是一次阻塞的 MySQL 查询 —— 留在事件循环里，就等于"一个观测页面的慢查询拖慢同一条线程上所有连接"。所有阻塞读都包在 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 里。

> 附带说明：既有的 REST 端点（`/api/traces`、`/api/traces/{runId}/events`）是**直接在控制器方法里同步查库**的，同样存在这个问题。它们属于 Step 1/2 的实现，本轮没有一起改（改动面会外溢到其它控制器），但结论是明确的：**这些端点迟早也要切到弹性线程池**。

### 5.4 为什么会话流里加了心跳

官方没有心跳。本项目加它的原因只有一个：**代理会掐掉"长时间没有字节流动"的长连接**（nginx `proxy_read_timeout` 默认 60 秒）。心跳是 SSE 的注释帧（`: keep-alive`），不含数据，前端只解析 `data:` 行所以天然忽略它。

### 5.5 为什么 run 结束了也不关流

收口信息本身也是事件（`run.completed` / `error.raised`），前端按事件内容判断"不用再等了"。如果服务端在 run 结束时关流，前端的退避重连机制（1s→2s→…→30s）会立刻对着一个已经结束的 run 重新连上来，日志里全是噪声。

---

## 6. 踩坑记录

### 6.1 `Mono.fromCallable` 返回 null 是"空 Mono"，不是"发一个 null"

第一版端点写的是：

```java
return Mono.fromCallable(() -> traceRegistry.get(runId))   // run 不存在时返回 null
        .map(run -> run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(frames(...)));
```

期望"不存在的 run → 404"。实际结果是 **200 OK + 空响应体**：`Callable` 返回 `null` 时 `Mono` 直接是**空的**，`map` 根本不会被调用，而空 Mono 到 WebFlux 就是"什么都不写"。前端只会抱着一条永远没有数据的连接不放，还看不出哪里错了。

修法是显式把"没有"翻译成 404：

```java
.map(run -> ResponseEntity.ok(frames(runId, afterSeq)))
.defaultIfEmpty(ResponseEntity.notFound().<Flux<ServerSentEvent<String>>>build());
```

这条是**测试逼出来的**：接口在浏览器里表现正常（有数据就是 200），只有"不存在的 run"这一条路径会静默变成 200。

### 6.2 WebTestClient 手上的解码器是 SSE reader，不是原始字节

用 `returnResult(String.class)` 拿到的是**事件的 JSON 内容**（reader 已经把帧拆开、只把 `data` 交出来），不是带 `data:` 前缀的原始字节 —— 前端走的是原生 `fetch` + 自己切帧，拿到的是原始字节，两边不一样。反过来利用这一点，可以顺手证明"服务端写出去的帧是合法 SSE"：reader 能拆出来，就说明帧格式没问题。

### 6.3 子 agent 的 systemPrompt 覆盖了父 run 的（真库缺陷，与 SSE 同批修掉）

现象：`md_agent_run` 里 `agent_name=deployment_agent` 的行，`system_prompt` 存的是子 agent（Kubernetes 组件检查 / Nacos 配置中心管理）的提示词。三条真实数据：

| run_id | agent_name | system_prompt 头部 |
| --- | --- | --- |
| muas2uy3-dcsf45 | deployment_agent | 你是 Nacos 配置中心管理 agent |
| muarzv2g-p3x92p | deployment_agent | 你是 Kubernetes 组件检查 agent |
| muaryq5c-25fsmn | workwx_batch_agent | 你是批量任务编排助手（正确） |

根因：`onSystemPrompt` 对**树里的每个 agent** 都会被调用，而子 agent 与父 agent 共用同一个 `RuntimeContext` → 同一个 `RunState` → 同一个 `traceRun`，代码又是无条件写 —— **谁最后说话谁说了算**。

修法的第一版是错的（值得记下来）：加一个 `owner` 字段（"这个 RunState 是不是我建的"）来判，但子 agent 共用父的 RunState，`owner` 对子 agent 同样成立，等于没判 —— 被新加的测试当场抓出来。正确判据是**名字**：`agent.getName()` 与建 run 时记下的名字相同，才是根 agent。

```java
// onSystemPrompt：只有根 agent 能写 run 级属性
if (state != null && state.isRootAgent(agent)) {
    traceRegistry.systemPrompt(state.traceRun, digest(sysPrompt, TEXT_LIMIT));
}
```

子 agent 的提示词**不留存**（2026-09-22 更新）：原先靠 `AGENT-TRACE` 过程日志留个影子，而日志是运行期排障用的、不是观测面，已随整套过程日志一并删除。这一列的语义是「这个 run 用的系统提示词」，整轮只有一个值 —— 子 agent 的那份不落库、不落日志，是刻意的。

副作用：**库里那两条已经写坏的行无法恢复**（原值被就地覆盖，事件表里没有备份），只能删掉重跑，或者接受它。

> 后续可选：如果确实需要在页面上看子 agent 的提示词，方案是 run 表加一列 `agent_prompts`（JSON：agentName → prompt），或者等 Step 5 把它挂到 `subagent.dispatched` 事件上。当前只存根 agent 的。

---

## 7. 前端契约（后端必须一直满足）

| 项 | 约定 |
| --- | --- |
| 路径 | `GET /api/traces/{runId}/events/stream` |
| 请求头 | `Accept: text/event-stream`；重连时带 `Last-Event-ID: <已拿到的最大 seq>`（也接受查询参数 `after`，两者取较大值） |
| 帧格式 | **只读 `data:` 行**，内容是**与 REST 完全相同的 `AgentRunEvent` JSON**；`event:` / `id:` / 注释行被忽略 |
| 游标 | 从 `max(after, Last-Event-ID) + 1` 起连续推送；重复帧无害（前端按 `id` 去重） |
| 状态码 | 不存在的 runId 必须是 **404**（前端据此停止重连）；正常情况 200 |
| 生命周期 | 流不会因为 run 结束而关闭，靠心跳维持 |

---

## 8. 验证

### 8.1 自动化

```powershell
cd D:\jinran\code\Md-Agent-Platform
$env:JAVA_HOME = "C:\Users\User\.jdks\ms-21.0.11"
& "D:\jinran\maven\apache-maven-3.9.10-bin\apache-maven-3.9.10\bin\mvn.cmd" -o test
```

结果：`Tests run: 242, Failures: 0, Errors: 0`，`BUILD SUCCESS`（server 231 + bootstrap 11；含真库 `MyBatisTraceStoreIT` 6/6）。

测试为什么这样切分（重要）：

- `TraceEventStreamTest#newEventsArriveThroughTheNotifier`：把兜底轮询调到 **60 秒**，此时还能立刻收到新事件 → 只可能是通知链路在工作；
- `TraceEventStreamTest#eventsStillArriveWhenWakeupsAreLost`：**完全不接唤醒器**（模拟通知丢失），新事件靠兜底轮询回来；
- 两条必须分开。合在一起的话，"通知坏了、轮询兜住了"会表现为**通过**，而线上最怕的就是这种"看起来在实时推送、其实是 5 秒轮询"的静默降级。

### 8.2 手工验收

```powershell
# 0) 取一个 runId（从旧到新排序时取最后一条）
curl.exe -s "http://localhost:9090/api/traces?limit=1"

# 1) 从 0 开始订阅：应看到已发生的所有帧，之后每来一条事件就推一帧（Ctrl+C 结束）
curl.exe -N "http://localhost:9090/api/traces/<runId>/events/stream"

# 2) 断点续传：只应看到 seq > 3 的帧
curl.exe -N -H "Last-Event-ID: 3" "http://localhost:9090/api/traces/<runId>/events/stream"

# 3) 心跳：静置 15 秒应看到一行 ": keep-alive"

# 4) 不存在的 run 必须 404（这条最容易被写错成 200）
curl.exe -s -o NUL -w "%{http_code}`n" "http://localhost:9090/api/traces/no-such-run/events/stream"
```

页面侧：

1. 打开"调用链路"页 → 选一次正在跑的 run（企业微信/飞书给 bot 发条消息触发）；
2. 链路节点应当**自己长出来**，不需要手动刷新；
3. 浏览器网络面板里 `events/stream` 应为 `200` 且持续处于 pending（不再有 404）；
4. 断网 10 秒再恢复：不应丢事件、也不应出现重复节点。

---

## 9. 已知缺口与后续

1. **path 漂移（前端显示层）**：`translate` 是纯函数，节点的 `path` 会随新阶段的到达而变化；而前端按 `id` 去重时**保留先到的那一份**，所以已经渲染出来的节点仍用旧 path。并行工具交错开始时（新节点的开始时刻早于已渲染的兄弟）理论上会让两个节点拿到同一个 path。影响限于显示层、刷新即恢复；彻底解法是给前端一个"整份替换"的读法（`?snapshot=1`）或在 run 结束时做一次全量对齐 —— 归到 Step 4。
2. **REST 端点仍阻塞事件循环线程**（见 5.3），建议在 Step 4 一并切到弹性线程池。
3. **run 列表仍是轮询**：SSE 推的是"某一个 run 的事件"，不是"有哪些 run"。列表要实时化需要第二条通道（官方也是分开的）。
4. **子 agent 事件**（`subagent.dispatched` / `subagent.completed`）仍未产出 —— Step 5。
5. **其余页面接真数据** —— Step 4。

---

## 10. 补丁（2026-09-21）：链路详情页不再「从第 61 轮开始展示」

### 10.1 现象

打开一个跑了 423 秒、共 **528 条事件** 的 run（`workwx_batch_agent`），调用链树最上面一行是
「第 61 轮推理」，往下是 62 / 63 / 64 轮；第 1~59 轮整段不见了。树上还挂着「第 60 轮」，
但它挂在第 61 轮**下面** —— 那是因为它本来就是 `1.14.1.1`，是路径上的子节点，与先后顺序无关。

### 10.2 根因：三段拼出来的

| # | 环节 | 发生了什么 |
| --- | --- | --- |
| 1 | 前端首屏 | `fetchRunEvents(id, { limit: 100 })` **不带游标** → 后端按既定语义返回**最新** 100 条 = `seq 429..528` |
| 2 | 前端投影 | `adapters.ts` 对「只有完成事件、没有起点事件」的节点直接跳过（原注释写的就是「历史没取全」）→ 节点 `1.14`（第 59 轮）不存在 |
| 3 | 前端投影 | `1.14.1`（第 61 轮）的父节点不在，`else roots.push(node)` 把它**顶成了根**，`sortRecursive` 再按 path 数字段排序 → 根列表变成 1.14.1 / 1.15 / … 一串平级节点 |

一句话：**树的根在链路的开头**，而取数方向是「从结尾往回取 100 条」。链路越长截断越狠，
被截掉的恰好是最值得看的开头；旧实现既没有截断提示，也没有「继续加载」的入口。

### 10.3 修复（前端 `src/features/traces/useRunEvents.ts`）

1. **首屏取最早一页**：`fetchRunEvents(id, { limit: 100, after: 0 })`。
   `after: 0` 必须显式写 —— 不带游标时后端给的是最新一页，不能让「从头开始」这件事依赖默认值。
   首屏这一页直接并集落状态、不过连续性校验：它自带 run 的开头，上游没有缺口可言。
2. **首屏之后向后补齐**：新增 `pullForward()`，从当前游标按 `after` 一页页拉到尾部，
   每页 `CATCH_UP_PAGE = 500`，一次补齐最多 `CATCH_UP_MAX_ROUNDS = 20` 轮（上限 10000 条）。
   它同时接管了原来的「直播缺口回补」职责（原 `repairGap` 的缓冲 + 前向补齐语义原样保留），
   三处调用同一份实现 —— 不会出现「手动拉的路径漏做连续性校验」这种只在长链路上暴露的分叉。
3. **补齐撞上限时把决定权交回给人**：置 `hasMore = true`，页面显示
   「链路尚未加载完，当前只渲染了前 N 条事件」+「继续加载」按钮（按钮走的就是同一个 `pullForward`）。
   顶栏状态也改成「已加载 N 条事件 · 增量推送中」，截断不再是无感的。

### 10.4 为什么这个方向是对的

- `events` 现在**始终是 `seq 1..N` 的连续前缀**：树根一定在，且与 `mergeContiguousEvents`
  要求的「只放行连续前缀」天然吻合 —— 旧方向（从尾部开窗）游标一跳就留缺口。
- 首屏 100 条给人看（先画出来）、补齐 500 条一批给机器拉（往返次数少），职责不同，所以是两个常量。
- 轮数上限不能省：事件数没有天花板（跑半小时的 harness run 轻易上万条），
  不设限的话打开一个病态 run 会把浏览器和后端一起拖住；撞上限不是错误。

### 10.5 顺带删掉的死代码

`loadEarlier` / `hasEarlier` / `loadingEarlier` 这一组在原实现里写得是完整的，但**全项目没有任何调用方**
（页面从来没做过「向上翻页」的入口）。改成「从最早一页开始 + 向后补齐」之后它们彻底没有意义 ——
按「不保留没人用的状态」整组删掉，换成 `loadMore` / `hasMore` / `loadingMore`。

### 10.6 验证

新增 `src/features/traces/useRunEvents.test.ts`（3 条用例）。假 api 的分页语义**照抄**
`AgentTraceController#events`，这样「发了什么游标」与「拿回来是哪一段」之间没有空隙，可以直接断言请求序列：

- 首屏必须是 `{ limit: 100, after: 0 }`，补齐必须是 `{ limit: 500, after: 100 }`，拿回来的是 `seq 1..250`；
- 12000 条事件：补齐停在 10100 条并置 `hasMore`，`loadMore()` 后拉满 12000 且 `hasMore` 落回 false；
- 直播断号（152 先到、151 后到）先缓冲再一起放出，续传游标跟着「已拿到的最大 seq」走。

前端：`vue-tsc --noEmit` 通过、`vitest run` **53 passed**（新增 3 条）、`vite build` 通过。
