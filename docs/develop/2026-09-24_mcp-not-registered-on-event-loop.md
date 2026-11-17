# 页面发布出来的 agent 为什么少了全部 MCP 工具

> 2026-09-24 · 影响面：所有「页面发布 / 重载 / 回滚」触发的**热装配** · 故障性质：**静默**（不报错、agent 能正常对话）

## 一、现象

`test_workwx-notify-agent` 在企微里能正常回复，但从不调用任何 MCP 工具。
IDEA 在 `AgentManager.stream()` 打断点看它的 `ReActAgent`：

- `toolkit.toolRegistry.tools` 只有 **2** 个（`workwx-send-msg` / `workwx-webhook-send`，都是 Spring `@Tool` bean）
- `mcpClientManager` 对象在，但它注册的 MCP 工具**一个都没有**

关键对照：

| 装配时机 | 线程 | 结果 |
| --- | --- | --- |
| 进程启动（`AgentManager.init`） | `main` | 工具齐全 |
| 页面点「发布」 | `reactor-http-nio-N`（Netty 事件循环） | **全部 MCP 工具丢失** |

同一份 `md_agent` + `md_mcp_server` 数据，只是装配时机不同，结果就不同 —— 所以这不是配置问题。

## 二、根因

一句话：

> 热装配跑在 **Netty 事件循环线程**上；装配过程内部会调用框架（我们改不了）的 `Mono#block()`
> 做 MCP 握手；**Reactor 禁止在事件循环线程上 block**，于是每个 MCP 都在建链阶段抛
> `IllegalStateException`；而这个异常又被 `DbMcpClientFactory.resolveAll` 逐条吞成一行 ERROR 日志后
> **继续装配** —— agent 照样建得出来，只是**少了全部 MCP 工具**。

### 2.1 调用链

页面 `POST /api/agents/{name}/publish`：

```
AgentController.publish            ← 同步 handler，跑在 reactor-http-nio-N 上
 └─ ControlPlanePublishService.publish
     └─ AgentManager.reloadIfPresent
         └─ ConfigurableAgentBuilder.build                 ← 唯一装配入口
             ├─ resolveMcpBindings
             │   └─ DbMcpClientFactory.resolveAll           ← 逐个 MCP
             │       └─ McpClientWrapper.initialize().block()
             │            ✗ IllegalStateException: block()/blockFirst()/blockLast() are blocking,
             │              which is not supported in thread reactor-http-nio-1
             └─ catch (Exception e) → log.error("[MCP] {} 挂载失败，已跳过") → 继续装配
```

### 2.2 为什么是「静默」的

失败发生在**装配期**，而「工具少了」只在**模型行为**上体现：

- 接口不报错（agent 建成功了）
- 页面看不出来（`md_agent_ref.mcp_names` 明明写着 3 个 MCP）
- 数据库不记录（没有「本次实例实际挂载了几个工具」这一列）

三个可观测面全都不落痕迹，所以它能藏两天。

## 三、复现证据（本次实测，探针已删）

按「先证客户端、再证装配、最后证线程」三步做，避免把「端点不通」误判成「线程不对」：

| # | 探针 | 结论 |
| --- | --- | --- |
| A | 直连 WeCom MCP：`initialize` + `listTools` | 200，**6** 个工具（`chat_groups_list` / `message_send` / ...）→ 端点和 apikey 都没问题 |
| B | 用库里真实配置跑项目自己的装配代码（普通线程） | 3 个 MCP 全部挂载，**12** 个工具进 registry；`Toolkit.copy()` 不丢 MCP → 排除「定义没读到」「copy 丢工具」 |
| C | 复刻事件循环线程：`Thread implements reactor.core.scheduler.NonBlocking` | 普通线程 `resolveAll` 成功 **3/3**；事件循环线程成功 **1/3**（只有先被缓存的那个侥幸成功），其余每个都是一行 `IllegalStateException: ... not supported in thread probe-http-nio-1` |

C 的 `1/3` 与截图里「只有 2 个非 MCP 工具」完全对应。

顺带排除的其它怀疑：`md_agent_ref.mcp_names` 存了、4 个 MCP 都是 `PUBLISHED`+`enabled=1`、
`agent-platform.agents.source=db`、`enable_tools`/`disable_tools` 均为 null（不过滤）。

## 四、改动（4 个文件）

| 文件 | 改动 | 为什么 |
| --- | --- | --- |
| `md-agent-server/.../agent/ConfigurableAgentBuilder.java` | `build()` 包一层新增的 `onAssemblyThread(...)`；`resolveMcpBindings` 在「没挂全」时补一条 WARN | **正确性兜底**：装配入口只有一个，在这里做线程交接，对所有触发方（发布任何资源、重载、回滚、导入、启动）一次生效，将来新增入口也不会漏 |
| `md-agent-bootstrap/.../controller/agent/AgentController.java` | 全部端点改成 `Mono` + `blocking(...)`（`Mono.fromCallable(...).subscribeOn(boundedElastic())`） | **响应性**：它原本就是同步 handler，装配被挪走后会在事件循环上等结果（MCP 握手默认超时 120s），必须一起离开事件循环。约定同 `ModelController` §4 |
| `md-agent-bootstrap/.../controller/test/TestController.java` | `/test/wecom-mcp` 改成 `Mono` + `subscribeOn(boundedElastic)`；顺手让它以 `text/html` 返回（它本来就拼 HTML） | 同一个坑：该端点在同步 handler 里直接 `.block()`，**必然 500** —— 而它正是用来验证「MCP 通不通」的那个诊断端点 |
| `md-agent-server/src/test/.../agent/AssemblyThreadHandoffTest.java` | 新增 5 个用例 | 防回归，见下 |

### 4.1 为什么 `onAssemblyThread` 要在入口做，而不是逐个控制器 `subscribeOn`

两者都要，但守的不是同一件事：

- **`ConfigurableAgentBuilder.onAssemblyThread`（兜底正确性）**：保证「无论谁、从哪条线程调 `build`，
  装配一定在允许阻塞的线程上跑」。控制器漏改、将来新加一个导入入口、某个定时任务里热装配 —— 都不会再丢工具。
  它只在 `Schedulers.isInNonBlockingThread()` 为真时才搬运线程，启动期（`main`）与弹性线程上是零开销直通。
- **控制器 `blocking(...)`（响应性）**：保证「调用方自己不等在事件循环上」。少了这层，装配虽然正确，
  但事件循环会被占住（MCP 握手最坏 120s），表现为「点了发布，整个控制台卡住」。

### 4.2 新增的 WARN

`resolveMcpBindings` 里补了一句：

```
[Agent] xxx 的 MCP 未全部挂载: 成功 1/3，缺失原因见上方的 [MCP] 行
```

`resolveAll` 仍然保持「单个失败只告警跳过」的语义（一个写错的名字不该拖垮整个 agent，也不该拖垮平台启动），
所以「少挂了」这件事必须在**汇总**处再说一次 —— 否则又回到「静默」。

### 4.3 防回归测试（`AssemblyThreadHandoffTest`）

不连任何真实 MCP（避免测试依赖外网）。Reactor 判定「这条线程能不能 block」看的就是
`reactor.core.scheduler.NonBlocking` 这个标记接口，而 Netty 的 `reactor-http-nio-N` 正是实现它来声明禁止阻塞 ——
用同一条规则复刻，等价且稳定。

| 用例 | 守的行为 |
| --- | --- |
| `handsAssemblyOffWhenCallerIsEventLoopThread` | 事件循环线程上调用时确实换了线程，且新线程不是 `NonBlocking` |
| `runsAssemblyInlineWhenCallerMayBlock` | 本来就能阻塞的线程（启动期 / 弹性线程）**不**多搬一次线程 |
| `propagatesRuntimeExceptionUnchanged` | 装配异常原样抛出 —— `AgentManager` 依赖异常类型决定旧实例是否保留，包一层就全失效 |
| `propagatesErrorUnchanged` | `Error` 也原样抛 |
| `concurrentAssembliesDoNotCrossTalk` | 「全量重载」那种并发装配不串线（守住「别把结果提成共享字段」） |

## 五、验证

| 项 | 结果 |
| --- | --- |
| `mvn test`（`Md-Agent-Platform`） | `md-agent-server` **347 passed** + `md-agent-bootstrap` **23 passed**，BUILD SUCCESS |
| 新增用例 | 5 个全绿 |
| 副本项目 `md-admin-platform/agent-platform` | 4 个文件已同步，SHA256 一致，关键用例同样通过 |

## 六、遗留 + 下一步

### 6.1 仍然缺线程交接的控制器（正确性已由 builder 兜住，只影响响应性）

| 文件 | 风险点 |
| --- | --- |
| `controller/controlplane/ResourceController.java` | 发布 prompt / skill / mcp 会热装配依赖它的 agent —— 与本次事故同一条路径 |
| `controller/channel/ChannelController.java` | `publish` / `reload` / `reload-all` 会重建渠道与 agent |
| `controller/channel/GatewayController.java` | 同上 |
| `controller/chat/AgentChatController.java` | SSE 入口，需单独确认它内部是否已经异步 |
| `controller/test/TestController.java` `/test` | 直接调 Nacos `AiService`（阻塞 HTTP），且 Step 7 要删掉它 |

改法一致：加一个私有 `blocking(...)`（照 `ModelController` 末尾那个写），端点返回 `Mono<...>`。
建议单独一次提交，便于 review。

### 6.2 让「少挂工具」再也不静默

这是本次事故真正的教训：**正确性**已经修了，但**可观测性**还没有。建议在 agent 详情页加一列
「本实例实际挂载的 MCP / 工具数」，由 `AgentManager` 暴露。这样「少挂了 2 个 MCP」在页面上一眼可见，
而不是等模型不调工具才发现。

### 6.3 运行时实例的现状

数据库里 `test_workwx-notify-agent` 当前的运行时实例**仍是坏的**（它是 17:10 由页面发布装出来的）。
处置二选一：

- **重启后端**：`init()` 在 `main` 线程装配，自然恢复；
- 或页面上点一次**「重载」**。

（改动的代码要重启后端才生效。）