# Agent 生产就绪检查清单

> 位置：`docs/agent-production-readiness.md`
> 用途：把「工时统计分析 agent」（`workwx_batch_agent` + `workwx_doc_agent` + `workwx_notify_agent`）从「能演示」推到「能无人值守上生产」的逐项检查表。
> 口径：只写缺口与修法，不做成绩汇报。每条都带**证据**（类 / 行号 / 框架 API 或实测命令），标 `待核实` 的是我没验证到、需要你确认的。
> 框架版本：`agentscope 2.0.2-subagent-bugfix`（`pom.xml:27`）
> 相关代码：`AgentManager` / `ConfigurableAgentBuilder` / `AgentController` / `AgentDefinitionStore` / `MessageRouter` / `WorkWxWebhookSendTool` / `DateUtils`

## 使用说明

- 每条按同一结构写：**现状 → 缺口 → 怎么修 → 验收**。验收项是可以直接打勾的。
- 勾选规则：`[ ]` 未开始、`[~]` 进行中、`[x]` 已完成、`[-]` 确认不适用（不适用要写明理由）。
- 建议顺序：先做第一部分（业务阻塞项），因为它决定「这件事该不该做」；技术项可以并行。
- 级别含义：**阻塞** = 不解决不该给真人发消息；**高危** = 能上但会出事；**工程债** = 能忍但会反噬。

## 总览

| 编号 | 分类 | 标题 | 级别 |
| --- | --- | --- | --- |
| B-1 | 业务 | agent 在生产可能加载不出来 | 阻塞 |
| B-2 | 业务 | 没有任何触发方式 | 阻塞 |
| B-3 | 业务 | 幂等下发没有任何落地 | 阻塞 |
| B-4 | 业务 | 判定口径仍是「待确认」 | 阻塞 |
| B-5 | 业务 | 身份对齐用的是姓名 | 阻塞 |
| B-6 | 业务 | 通知通道单点且无代码级节流 | 高危 |
| B-7 | 业务 | 工作日判定依赖第三方免费接口 | 高危 |
| B-8 | 业务 | 核对结果没有落库 | 高危 |
| B-9 | 业务 | 成本 / 耗时 / 调用量未测量 | 高危 |
| T-1 | 上下文 | 压缩策略有机制但未配置 | 高危 |
| T-2 | 上下文 | 最该配的 doc agent 是 REACT，配不了 | 高危 |
| T-3 | 上下文 | 工具结果驱逐未接 | 高危 |
| T-4 | 上下文 | 没有 token 预算与窗口检查 | 高危 |
| T-5 | 上下文 | 中间件只能挂一个 | 工程债 |
| T-6 | 记忆 | 长期记忆没用框架抽象 | 高危 |
| T-7 | 记忆 | 记忆保留期未配置 | 工程债 |
| T-8 | 治理 | 权限层被硬编码为 BYPASS | 高危 |
| T-9 | 治理 | 未用 askRules 做发送审批闸门 | 高危 |
| T-10 | 运行 | scheduler-xxl-job 已引依赖但未使用 | 阻塞 |
| T-11 | 运行 | 无分布式状态，workspace 落本地磁盘 | 高危 |
| T-12 | 运行 | 异步工具超时 / 任务仓储全默认 | 工程债 |
| T-13 | 运行 | 中断与优雅停机未接，换实例硬 close | 工程债 |
| T-14 | 运行 | 模型 / 工具执行配置全默认 | 工程债 |
| T-15 | 可观测 | hooks 完全没接 | 高危 |
| T-16 | 可观测 | 无 token 与成本计量 | 高危 |
| T-17 | 可观测 | 仅 actuator，无指标 | 工程债 |
| T-18 | 通道 | 企微 WS 自写，未评估官方扩展 | 工程债 |
| T-19 | 通道 | studio 调试控制台未引 | 工程债 |
| T-20 | 通道 | 框架 RAG / Knowledge 入口未接 | 建议 |
| C-1 | 配置 | 未知配置键静默失效 | 工程债 |
| C-2 | 配置 | 定义优先级与其他事实源冲突 | 高危 |
| C-3 | 配置 | skill 本地优先会盖掉页面配置 | 高危 |

---

## 第一部分 业务与治理

### B-1 agent 在生产可能加载不出来 `阻塞`

**现状**：`AgentManager.init()` 只从控制面表 `md_agent` 加载定义，不再支持本地 MD/JSON 兜底。agent 的线上版本由页面的「发布」动作决定，草稿不会进生产。

**缺口**：没在页面「发布」的 agent，生产环境里根本不存在——不是"跑得不对"，是"没有"。

**怎么修**：在页面把该 agent 发布成「已发布」版本；本地测试也连测试库，从控制面表加载。

**验收**：
- [ ] 控制面页面能看到 `workwx_batch_agent` 处于「已发布」
- [ ] 生产配置启动后，`GET /api/agents` 返回里包含它

### B-2 没有任何触发方式 `阻塞`

**现状**：全仓库 `rg "@Scheduled|EnableScheduling|TaskScheduler"` 零命中。`server/tool/xxljob/` 那 6 个类只是**通过 REST 管理 XXL-JOB 控制台**的工具，不是"被调度"。

**缺口**：现在只能靠人在企微里发一句话触发。对一个"每月自动提醒填报"的机器人，这不叫上线。

**怎么修**：见 T-10（框架的 `agentscope-extensions-scheduler-xxl-job` 依赖已经在 `md-agent-server/pom.xml:89`，接上即可）；或外部调度直接调 REST / 发一条消息触发。

**验收**：
- [ ] 有一个不需要人工介入的定时触发路径
- [ ] 触发失败/未触发有告警

### B-3 幂等下发没有任何落地 `阻塞`

**现状**：`batch-audit-and-notify/SKILL.md:51` 与 `workwx_batch_agent.md:55` 都要求"下发前先查已有的下发记录"，但全仓库搜不到任何"下发记录"的存储或工具。工作区里只有 `audit-results/*.md` 这类人读产物，没有机器可查的下发台账。

**缺口**：这条硬约束目前**无法执行**，模型只能自己编一个。重复提醒同一批人是这类 bot 最典型的生产事故。

**怎么修**：落一张真实的记录表（或可查可写的状态文件）+ 一个配套工具（查 / 写），把幂等从 prompt 口号变成代码。

**验收**：
- [ ] 存在一个可查询的"下发记录"数据源
- [ ] 同一对象同一批次重复执行会被跳过
- [ ] 有重跑演练：人为重跑一次，验证没有重复消息

### B-4 判定口径仍是「待确认」 `阻塞`

**现状**：`workspace/harness/workwx_batch_agent/AGENTS.md` 的「本次运行参数」表里，基准来源 / 基准关键字段 / 记录来源 / 记录对齐字段 / 判定口径 / 日期口径 / 结果落表位置 / 下发正文模板 / 分片大小 **全部是 `待确认`**。

**缺口**：这不是配置没填，是**业务规则没定**。请假、调休、出差、半天、哺乳假算不算异常？填了但不是工时算不算？
**给一个请了假的人发「你没填工时」是组织事故，不是 bug。**

**怎么修**：与人事逐条签字确认，写进 `AGENTS.md` 参数表，把 `待确认` 全部消掉。

**验收**：
- [ ] 参数表里不再有 `待确认`
- [ ] 异常判定有书面口径，且人事确认过
- [ ] 请假/调休/出差等边界场景各有一条明确的期望结果

### B-5 身份对齐用的是姓名 `阻塞`

**现状**：`workspace/harness/workwx_batch_agent/*/MEMORY.md` 与 `memory/*.md` 里的实际比对与通知都按姓名 / 英文名走（如 `Claire Wang`、`Cynthia Hu`、`金冉`）。前期排查已提示过"建议用工号/员工ID，勿用姓名"。

**缺口**：重名、改名、中英文名不一致 → **静默错配**，而且消息会发给错的人。

**怎么修**：花名册与工时表都用稳定 ID（工号 / 员工 ID）做对齐键，姓名只用于展示。

**验收**：
- [ ] 对齐键是稳定 ID，不是姓名
- [ ] 有重复姓名 / 改名场景的验证用例

### B-6 通知通道单点且无代码级节流 `高危`

**现状**：
- 邮件 MCP 未授权（851008）。
- `workwx-send-msg` 只能发给和机器人聊过的人，100 人不可能都聊过（`workwx-send-msg/SKILL.md` 已写明该通道能力限制）。
- 唯一能 @ 到人的是群 webhook，限 20 条/分钟（`AGENTS.md`「已确认的通道硬约束」）。
- **`WorkWxWebhookSendTool` 没有任何节流 / 退避 / 重试**：`rg "sleep|Semaphore|throttle|retry"` 只命中一条把 `45009` 翻译成中文的字符串（`WorkWxWebhookSendTool.java:293`）。

**缺口**：100 人至少 5 分钟，节流全靠模型自律；触发限流后没有队列表述，也没有失败重投。

**怎么修**：在工具层加令牌桶（20/分钟）+ 退避 + 失败重试队列；并发上限与节流参数写进 agent 定义而不是 prompt。
另外确认：目标群是否真的包含全部收件人。

**验收**：
- [ ] 工具层有硬节流，模型无法绕过
- [ ] 限流时自动退避，不丢消息
- [ ] 目标群成员覆盖全部收件人（待核实项有结论）

### B-7 工作日判定依赖第三方免费接口 `高危`

**现状**：`DateUtils`（`md-agent-server/src/main/java/com/md/agentplatform/server/tools/DateUtils.java`）从 `https://timor.tech/api/holiday/year/{year}` 取节假日。该站走 Cloudflare 且**按 User-Agent 拦截**（实测 `curl/8.7.1`、`okhttp/4.12.0` 返回 403，`Mozilla/5.0` 返回 200），所以代码里显式设了 UA（`DateUtils.java:54`）。

**缺口**：接口挂掉或被封时，实现会**静默降级为「只认周末」**——国庆、补班全部判错，且不报警。对算工时这种精度要求，这是不可接受的。

**怎么修**：三层防护——① 结果落盘持久化，不只存内存；② 降级到周末兜底时**打告警**（现在只有一行 warn）；③ 更稳的做法是把当年节假日表固化进配置（一年内不会变）。

**验收**：
- [ ] 接口不可用时，有明确的告警而不是静默降级
- [ ] 节假日数据有持久化缓存，重启不丢
- [ ] 有 `2026-09-20`（周日补班）、`2026-10-01~07`（假期）这类用例守住

### B-8 核对结果没有落库 `高危`

**现状**：`AGENTS.md` 里「结果落表位置」是 `待确认`；实际结果写在 `workspace/harness/workwx_batch_agent/<userId>/audit-results/*.md`（本地文件）。

**缺口**：本地磁盘、无副本、无法追溯和审计。HR 拿这个做绩效的话必须落表/落库。

**怎么修**：把汇总结果写进正式的表格 / 库，并保留可追溯的批次号。

**验收**：
- [ ] 结果写入正式存储，而不是 agent 工作区文件
- [ ] 每批次结果可按批次号追溯

### B-9 成本 / 耗时 / 调用量未测量 `高危`

**现状**：`max-iters: 50`，100 人分片，工时表 `forbid_sql_query: true`（不能用 SQL 聚合，只能一页页拉记录）。没有任何测量数据。

**缺口**：一次全量跑要多少次 MCP 调用、多少 token、多少钱、多久——全是未知数。分片大小也还没定。

**怎么修**：见 T-16（token/成本计量）先补齐度量，再压测一轮记录基线。

**验收**：
- [ ] 有一份全量跑的基线：调用数 / token / 耗时 / 成本
- [ ] 分片大小有数据支撑，不是拍脑袋

---

## 第二部分 框架技术：上下文与记忆

### T-1 压缩策略有机制但未配置 `高危`

**现状**：`HarnessAgent.Builder` 提供 `compaction(CompactionConfig)`（还有 `disableCompaction()`），但 `ConfigurableAgentBuilder` 全文**一次都没调用**，所以跑的是框架默认值。

**缺口**：你不知道它何时触发、保留多少，也没有任何观测。叠加已实测的问题：压缩失败只打一行 warn 就继续，而 `CompactionMiddleware` 的 `onErrorResume` 救不回**已被就地改写**的 state（见 `CompactionMiddleware` 与 `InboxMiddleware` 的调用链）。

**怎么修**：在 `AgentDefinition` 加 compaction 相关 typed 字段，在 `applyTuning(HarnessAgent.Builder)`（`ConfigurableAgentBuilder.java:189`）里装配。可用旋钮（`javap` 实测）：

| Builder 方法 | 含义 |
| --- | --- |
| `triggerMessages(int)` / `triggerTokens(int)` | 触发压缩的阈值（消息数 / token 数） |
| `reserved(int)` | 预留量 |
| `keepMessages(int)` / `keepTokens(int)` / `keepTokensMin/Max(int)` / `keepTokensRatio(double)` | 压缩后保留多少 |
| `summaryPrompt(String)` | 摘要提示词（可替换默认） |
| `flushBeforeCompact(boolean)` / `offloadBeforeCompact(boolean)` | 压缩前是否刷记忆 / 卸载 |
| `truncateArgs(TruncateArgsConfig)` | 参数截断（含 `maxArgLength`） |
| `prune(PruneConfig)` | 剪枝（`protectTokens` / `minimumTokens` / `maxOutputChars` / `excludedTools`） |
| `model(String)` | 摘要用的模型（默认可能用主模型，成本敏感时单独指定小模型） |

默认兜底常量：`CompactionConfig.FALLBACK_TRIGGER_TOKENS = 160000`。

**验收**：
- [ ] `AgentDefinition` 有 compaction 字段，DB / MD / REST 三种源都能配
- [ ] 至少给 harness 配了 `triggerTokens` 与 `keepTokens`
- [ ] 压缩触发与失败都有日志/告警（配合 T-15）

### T-2 最该配的 doc agent 是 REACT，配不了 `高危`

**现状**：`javap` 实测 `compaction` / `toolResultEviction` **只存在于 `HarnessAgent.Builder`**，`ReActAgent.Builder` 没有这两个方法。而 `workwx_doc_agent.md` 的 `type: REACT`，它又是**唯一读智能表格记录的那个**。

**缺口**：最容易撑爆上下文的链（`forbid_sql_query: true`，只能一页页拉 `smartsheet_records_list`，100 人分片 × 每片多次调用），恰好是**唯一连驱逐旋钮都没有**的 agent 类型。
`待核实`：REACT 路径是否另有框架内置的截断行为。

**怎么修**：二选一——① 把 `workwx_doc_agent` 改成 HARNESS（能配 compaction/eviction，但要评估 harness 默认工具集与工作区语义的影响）；② 保持 REACT，改为在**代码侧**限流/截断工具返回（比如包一层工具或在 MCP 侧分页）。

**验收**：
- [ ] 明确选择 ① 或 ② 并写明理由
- [ ] doc agent 读大表时，单轮上下文有上界
- [ ] 用真实规模的数据验证过（例如一整月的工时记录）

### T-3 工具结果驱逐未接 `高危`

**现状**：框架提供 `toolResultEviction(ToolResultEvictionConfig)` 与 `ToolResultEvictionMiddleware`，未使用。

**缺口**：工具返回体是上下文爆炸的主要来源，这是官方解药，没用等于放弃一道防线。

**怎么修**：可用旋钮（`javap` 实测）：`maxResultChars(int)`、`previewChars(int)`、`evictionPath(String)`、`excludedToolNames(Set)`。
默认值：`DEFAULT_MAX_RESULT_CHARS = 80000`、`DEFAULT_PREVIEW_CHARS = 2000`、`DEFAULT_EVICTION_PATH = "large_tool_results"`。
注意 `excludedToolNames`：`workday-query` 这类小返回可以排除，避免被无谓驱逐。

**验收**：
- [ ] 给需要的 agent 配了 eviction 阈值
- [ ] 超高返回被落盘 + 只留预览
- [ ] 需要完整结果时，agent 能按路径读回（否则驱逐会丢信息）

### T-4 没有 token 预算与窗口检查 `高危`

**现状**：core 里有 `io.agentscope.core.model.ModelContextWindows`，但代码里没有任何主动检查。`maxContextTokens` 只在 HARNESS 链映射（`ConfigurableAgentBuilder.java:232`），且 **`AgentDefinition` 里默认 `null`、MD 里也没写**，等于没设；REACT 链连这个旋钮都没有。

**缺口**：没有任何"快超窗口了"的判断，全靠压缩被动兜底。

**怎么修**：给 harness 显式设 `maxContextTokens`；把模型窗口与实际用量的比值作为告警指标（配合 T-16）。

**验收**：
- [ ] harness 的 `maxContextTokens` 有明确取值
- [ ] 用量接近窗口时有告警

### T-5 中间件只能挂一个 `工程债`

**现状**：`ConfigurableAgentBuilder.java:163`（REACT）与 `:206`（HARNESS）都是 `builder.middleware(getBean(...))`——**单个**。框架有 `middlewares(List<? extends MiddlewareBase>)`，未使用。`middleware` 字段是单个 bean 名字符串。

**缺口**：无法声明式组合多个中间件（压缩 + 驱逐 + 追踪）。想加第二个中间件就得改 Java 代码。

**怎么修**：`AgentDefinition.middleware` 改为 `List<String>`（或新增 `middlewares` 字段），装配改用 `middlewares(List)`。注意向后兼容已有定义里的单值写法。

**验收**：
- [ ] 一个 agent 能同时声明多个中间件
- [ ] 旧的单值写法仍可用

### T-6 长期记忆没用框架抽象 `高危`

**现状**：框架有一整套长期记忆抽象：`io.agentscope.core.memory.LongTermMemory` / `LongTermMemoryMode` / `LongTermMemoryTools` / `StaticLongTermMemoryHook`，以及 `extensions-mem0`。ReActAgent.Builder 也提供 `longTermMemory(...)` / `longTermMemoryMode(...)` / `longTermMemoryAsyncRecord(...)`，**均未使用**。
实际走的是 HarnessAgent 的文件式 `MEMORY.md`，由模型自己写。

**缺口**：**没有 schema、没有校验、没有淘汰策略**。这正是之前一次临时探测就能污染会话状态的技术原因。生产上一条错误记忆会静默带偏后续所有运行。

**怎么修**：评估改用框架的 `LongTermMemory`；若继续用文件式记忆，至少加——写入前的结构校验、人工可读的 diff 审计、以及明确的清理流程。

**验收**：
- [ ] 记忆写入有校验或人工可审
- [ ] 记忆内容的变化可追溯（不只看当前值）
- [ ] 有明确的清理 / 重建流程

### T-7 记忆保留期未配置 `工程债`

**现状**：`MemoryConfig` 提供 `dailyFileRetentionDays()` / `sessionRetentionDays()` / `consolidationMaxTokens()` / `consolidationMinGap()`，未使用。默认值（`javap -constants` 实测）：`DEFAULT_DAILY_FILE_RETENTION_DAYS = 90`、`DEFAULT_SESSION_RETENTION_DAYS = 180`、`DEFAULT_CONSOLIDATION_MAX_TOKENS = 4000`。

**缺口**：记忆文件与 session 文件会长期累积，磁盘与会话读取成本持续上升。

**怎么修**：在 `AgentDefinition` 加 `memory(MemoryConfig)` 的字段；至少确认默认保留期是否符合合规要求。

**验收**：
- [ ] 保留期是显式配置的，不是默认值兜着
- [ ] 有一轮"过期清理"的验证

---

## 第三部分 框架技术：治理与权限

### T-8 权限层被硬编码为 BYPASS `高危`

**现状**：`ConfigurableAgentBuilder.java:186`：
```java
builder.permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build());
```
所有 agent 无条件绕过整套 `io.agentscope.core.permission`。

**缺口**：没有"危险操作需人工确认"的机制。`PermissionMode` 实际有 5 档（实测）：`DEFAULT` / `ACCEPT_EDITS` / `EXPLORE` / `BYPASS` / `DONT_ASK`，你现在固定在最宽松的一档。

**怎么修**：把 mode 变成 `AgentDefinition` 的可配置字段（带默认值），不要写死在代码里。

**验收**：
- [ ] mode 可按 agent 配置
- [ ] 至少"只读"类 agent 不用 BYPASS

### T-9 未用 askRules 做发送审批闸门 `高危`

**现状**：`PermissionContextState.Builder` 提供 `addAllowRule(toolName, rule)` / `addDenyRule(...)` / `addAskRule(...)`（按工具名配置），`javap` 实测存在。当前 `permissionContext` 只设了 mode，**规则一条都没用**。

**缺口**：这是把 prompt 里的软约束（"发送前确认内容与目标"）升级成**硬闸门**的唯一框架级途径，被整个放弃了。

**怎么修**：给发送类工具（群 webhook / 消息发送）配 `addAskRule`，让"要不要发"变成需要确认的一步；或与 B-3/T-? 的 dry-run 结合。

**验收**：
- [ ] 发送类工具落在 ask 规则里
- [ ] 首次上线阶段，发送动作确实需要确认

---

## 第四部分 框架技术：运行与调度

### T-10 scheduler-xxl-job 已引依赖但未使用 `阻塞`

**现状**：`md-agent-server/pom.xml:89-91` 已声明 `agentscope-extensions-scheduler-xxl-job`。但 `rg "agentscope.extensions.scheduler"` 在 `md-agent-server/src` 与 `md-agent-bootstrap/src` **零命中**。

**缺口**：这是"定时触发 agent"的现成能力，只差接上。

**怎么修**：按扩展的接入方式（executor handler）把 agent 触发注册进去；或先接一个更简单的进程内调度验证链路，再换 XXL-JOB。

**验收**：
- [ ] 定时触发跑通（见 B-2 验收）
- [ ] 触发记录可查（哪个 agent、哪次、结果）

### T-11 无分布式状态，workspace 落本地磁盘 `高危`

**现状**：`HarnessAgent.Builder` 提供 `distributedStore(DistributedStore)`，未使用。当前 `state-store` 落 MySQL，但 workspace（会话、任务、记忆、`audit-results/`）全在本地目录：`workspace/harness/workwx_batch_agent/<userId>/...`（实测存在 `MEMORY.md`、`agents/.../sessions/*.jsonl`、`tasks/*.json`、`audit-results/*.md`、`memory/*.md`）。`AgentDefinitionStore` 也写本机磁盘（`{workspace-root}/agent-definitions/{name}.json`）。

**缺口**：多副本部署时会分叉：A 副本建的 agent 只有 A 认；各副本各写各的 workspace。

**怎么修**：若确定单副本，把这条标 `[-]` 并写明约束；否则接 `distributedStore` 并把 workspace 外置到共享存储。

**验收**：
- [ ] 明确单副本 / 多副本，并把结论写进部署文档
- [ ] 若多副本：两个副本看到同一份状态

### T-12 异步工具超时 / 任务仓储全默认 `工程债`

**现状**：`HarnessAgent.Builder` 有 `asyncToolTimeout(Duration)` / `taskRepository(TaskRepository)` / `messageBus(MessageBus)` / `asyncToolRegistry(...)`，均未使用。`tasks/*.json` 靠本地文件。`enablePendingToolRecovery: true` 说明这条异步链是活的（对应 MCP 的 `*_submit` / `*_query` 成对调用）。

**缺口**：异步工具没有显式超时，卡住的工具会一直占着；任务状态在本地文件。

**怎么修**：给异步工具设超时；评估 `taskRepository` 落库。

**验收**：
- [ ] 异步工具有超时上界
- [ ] 超时后有明确的失败语义（而不是挂着）

### T-13 中断与优雅停机未接，换实例硬 close `工程债`

**现状**：core 有 `io.agentscope.core.interruption` 与 `io.agentscope.core.shutdown`，未接。`AgentManager.create`（`AgentManager.java:81`）在 `agents.put()` 之后**立即** `close()` 旧实例；`stream`（`:125`）每请求取一次 map。

**缺口**：① 跑到一半的批次无法取消；② 热更新定义时，持有旧实例引用的进行中会话会被硬打断。

**怎么修**：接入 interruption 做"安全取消"；换实例改为引用归零再关（或明确接受"保存配置会打断进行中的对话"并写进产品语义）。

**验收**：
- [ ] 能取消一个正在跑的批次
- [ ] 热更新时的会话中断行为有明确定义（要么不中断，要么明确告知）

### T-14 模型 / 工具执行配置全默认 `工程债`

**现状**：`modelExecutionConfig(ExecutionConfig)` / `toolExecutionConfig(ExecutionConfig)` / `generateOptions(GenerateOptions)` 未使用。

**缺口**：模型与工具层的并发、超时、重试全默认。批量并发派发时这一层没有任何显式控制。

**怎么修**：给 harness 与并发的子 agent 显式配置工具执行并发与超时。

**验收**：
- [ ] 并发上限是配置项，不是默认值
- [ ] 有并发压测记录（配合 B-9）

---

## 第五部分 框架技术：可观测与成本

### T-15 hooks 完全没接 `高危`

**现状**：`ReActAgent.Builder` / `HarnessAgent.Builder` 都有 `hook(Hook)` / `hooks(List<Hook>)`，`applyTuning` 里一个都没调。框架自带整套事件（实测）：`PreReasoningEvent` / `PostReasoningEvent` / `ReasoningChunkEvent` / `PreActingEvent` / `PostActingEvent` / `ActingChunkEvent` / `PreCallEvent` / `PostCallEvent` / **`PreSummaryEvent` / `SummaryEvent` / `SummaryChunkEvent` / `PostSummaryEvent`** / `ErrorEvent` / `ReasoningEvent` / `ActingEvent`，另有现成的 `core/hook/recorder/JsonlTraceExporter`。
`Hook` 接口：`onEvent(T) -> Mono<T>`、`tools()`、`priority()`。

**缺口**：没有压缩打点、没有错误采集、没有 trace 导出。排查全靠日志拼。

**怎么修**：挂一个 `Hook` 实现，至少覆盖 `ErrorEvent` 与 `*Summary*` 事件；评估直接用 `JsonlTraceExporter`。

**验收**：
- [ ] 压缩发生时有事件可观测
- [ ] 错误事件被采集，而不是只在日志里

### T-16 无 token 与成本计量 `高危`

**现状**：`GenerateOptions` / `ExecutionConfig` 未接，流里的 usage 事件没人消费。

**缺口**：100 人跑一轮花多少钱、用了多少 token，答不出来。这是上生产的硬指标。

**怎么修**：用 T-15 的 hook 在 `PostCallEvent` 上累计 token；按 agent / 批次维度落指标。

**验收**：
- [ ] 每次运行有 token 与成本记录
- [ ] 可按 agent 与批次查询

### T-17 仅 actuator，无指标 `工程债`

**现状**：`md-agent-server/pom.xml:49` 引了 `spring-boot-starter-actuator`，但没有 micrometer / prometheus registry。

**缺口**：跑没跑、发了多少条、谁失败、耗时多少——全无指标。

**怎么修**：接 micrometer + prometheus（或现有监控体系），暴露关键业务指标：批次成功率、单批耗时、消息发送数、限流次数、工具失败率。

**验收**：
- [ ] 关键指标可在监控面板看到
- [ ] 有告警规则（未跑 / 失败率超阈 / 限流）

---

## 第六部分 框架技术：通道与扩展

### T-18 企微 WS 自写，未评估官方扩展 `工程债`

**现状**：框架有 `agentscope-extensions-channel-wecom` / `channel-feishu` / `channel-dingtalk`。项目是自写的 `WorkWxWsClient` + `WeComBotInstance`（OkHttp WS、心跳、退避重连）。

**缺口**：自写的重连 / 心跳 / 背压是持续维护成本。**但不确定官方扩展是否覆盖企微智能机器人的 aibot 协议**——若覆盖不了，自写就是对的。

**怎么修**：花半天评估官方 `channel-wecom` 的能力边界；覆盖不了就把结论写进文档，避免以后重复讨论。

**验收**：
- [ ] 有明确结论（用官方 or 自写），并写明理由

### T-19 studio 调试控制台未引 `工程债`

**现状**：仓库里有 `agentscope-extensions-studio`，未引入。

**缺口**：缺一个可视化的会话/事件调试入口，现在排查全靠日志。

**怎么修**：评估引入 studio 用于测试环境调试（注意别把调试入口暴露到生产）。

**验收**：
- [ ] 有结论：引入 / 不引入，及理由

### T-20 框架 RAG / Knowledge 入口未接 `建议`

**现状**：`ReActAgent.Builder` 有 `knowledge(...)` / `knowledges(...)` / `ragMode(...)` / `retrieveConfig(...)`，仓库另有 `extensions-rag-simple`。项目现在走的是 `RagIntelligentRetrievalTool`（HTTP/MCP 方式）。

**缺口**：不算缺口，只是**两条路线并存**。若未来要做知识库类 agent，需要决定用哪条。

**怎么修**：记录选型结论即可。

**验收**：
- [ ] 选型结论写进文档

---

## 第七部分 配置层与约定

### C-1 未知配置键静默失效 `工程债`

**现状**：`AgentDefinition.ignoreUnknown` 用 `@JsonAnySetter` **只 warn**（`AgentDefinition.java` 末尾）。这是有意的设计——坏配置不会让启动失败。

**缺口**：反过来说，**配置写错了会静默失效**。例如想配 `compaction` 但字段名拼错，会安静地走默认值。

**怎么修**：保留"启动不失败"的语义，但把这个 warn 暴露成**可查询的校验结果**（管理页面 / 启动自检报告）。做管理页面时必须做进去。

**验收**：
- [ ] 未知键能在一个地方被看到（不是翻日志）
- [ ] 管理页面保存定义时给出提示

### C-2 定义优先级与其他事实源冲突 `高危`

**现状**：`AgentManager.init`（`:60`）在 local 模式下的优先级是 **YAML → JSON 持久化（`agent-definitions/*.json`）→ MD 文档（最高）**。

**缺口**：**页面上保存的定义会被同名 `.md` 覆盖**。做管理页面之前必须先把"唯一事实源"定死，否则会出现"改了没反应 / 谁覆盖谁看运气"这类最难查的 bug。
补充：`AgentController` 的 POST/PUT 是**先 `definitionStore.save(def)` 再 `agentManager.create(def)`**；而 `validateTools`（`ConfigurableAgentBuilder.java:268`）会抛异常。结果：坏定义已经落盘，页面收到 500——**下次重启 `init()` 无 catch 地 create → `@PostConstruct` 抛 → 应用起不来**。

**怎么修**：① 定死事实源（**已落地：控制面 DB 为唯一权威**，本地文件降级为开发期兜底）；② `AgentController` 改为**先 build 成功再落盘**；③ 启动期保持 fail-fast（那是给手写 MD 用的），但要避免页面的坏定义污染启动路径。

**验收**：
- [ ] 事实源唯一且有文档说明
- [ ] 保存一个引用不存在工具的坏定义，不会导致下次启动失败
- [ ] 页面保存后立即生效，且不会被文件悄悄覆盖

### C-3 skill 本地优先会盖掉页面配置 `高危`

**现状**：`ConfigurableAgentBuilder.resolveSkillRepository` 当前是**本地目录优先、按名从 DB 解析兜底**（`workspace/single/skills/<agent>/...` 存在时优先用本地）。

**缺口**：与 C-2 同源。在页面上改 skill，会被工作区里的本地文件盖掉。

**怎么修**：与 C-2 一起把优先级收敛到单一事实源；本地目录只在开发期作为覆盖手段，且要有显式开关。

**验收**：
- [ ] skill 改了能看到效果，不需要删本地文件
- [ ] 本地覆盖有显式开关，不会在生产生效

---

## 附录 A：框架构建器旋钮对照

`javap` 实测 `ReActAgent.Builder` / `HarnessAgent.Builder` 的公开 setter，标出当前是否已接（`ConfigurableAgentBuilder.applyTuning`）。

| 旋钮 | REACT | HARNESS | 项目是否已接 |
| --- | --- | --- | --- |
| `name` / `description` / `sysPrompt` / `model` / `toolkit` | ✓ | ✓ | 已接 |
| `maxIters` / `maxRetries` / `checkRunning` / `stopOnReject` | ✓ | ✓ | 已接 |
| `defaultSessionId` / `enableMetaTool` / `enablePendingToolRecovery` / `enableTaskList` | ✓ | ✓ | 已接 |
| `stateStore` | ✓ | ✓ | 已接（MySQL） |
| `fallbackModel` | ✓ | ✓ | 已接 |
| `middleware`（单个） | ✓ | ✓ | 已接 |
| `middlewares`（列表） | ✓ | ✓ | **未接**（T-5） |
| `hook` / `hooks` | ✓ | ✓ | **未接**（T-15） |
| `permissionContext` | ✓ | ✓ | 已接但**硬编码 BYPASS**（T-8） |
| `generateOptions` / `modelExecutionConfig` / `toolExecutionConfig` | ✓ | ✓ | **未接**（T-14） |
| `longTermMemory` / `longTermMemoryMode` / `longTermMemoryAsyncRecord` | ✓ | — | **未接**（T-6） |
| `knowledge` / `knowledges` / `ragMode` / `retrieveConfig` | ✓ | — | **未接**（T-20） |
| `skillRepository` / `skillRepositories` / `skillFilter` / `dynamicSkillsEnabled` / `skillCodeExecutionEnabled` / `skillWorkDir` | ✓ | 部分 | 部分已接 |
| `compaction` / `disableCompaction` | **无** | ✓ | **未接**（T-1、T-2） |
| `toolResultEviction` / `disableToolResultEviction` | **无** | ✓ | **未接**（T-3） |
| `memory(MemoryConfig)` | **无** | ✓ | **未接**（T-7） |
| `maxContextTokens` | **无** | ✓ | 已接但**没设值**（T-4） |
| `distributedStore` | — | ✓ | **未接**（T-11） |
| `asyncToolTimeout` / `taskRepository` / `messageBus` / `asyncToolRegistry` | — | ✓ | **未接**（T-12） |
| `subagentFactory` / `subagent` / `subagents` | — | ✓ | 已接（按名惰性路由） |
| `workspace` / `environment` / `environmentMemory` / `projectGlobalSkillsDir` / `agentId` | — | ✓ | 已接 |
| `enablePlanMode` / `planFileDirectory` / `allowShellInPlanMode` | — | ✓ | 已接 |
| `enableAgentTracingLog` / `useLegacyXmlWorkspaceContext` | — | ✓ | 已接 |
| `disableFilesystemTools` / `disableShellTool` / `disableSubagents` / `disableMemoryTools` 等一批 disable* | — | ✓ | **未接**（需要收紧能力时可用） |

## 附录 B：上下文相关默认值（`javap -constants` 实测）

| 常量 | 值 |
| --- | --- |
| `CompactionConfig.FALLBACK_TRIGGER_TOKENS` | `160000` |
| `ToolResultEvictionConfig.DEFAULT_MAX_RESULT_CHARS` | `80000` |
| `ToolResultEvictionConfig.DEFAULT_PREVIEW_CHARS` | `2000` |
| `ToolResultEvictionConfig.DEFAULT_EVICTION_PATH` | `large_tool_results` |
| `MemoryConfig.DEFAULT_CONSOLIDATION_MAX_TOKENS` | `4000` |
| `MemoryConfig.DEFAULT_DAILY_FILE_RETENTION_DAYS` | `90` |
| `MemoryConfig.DEFAULT_SESSION_RETENTION_DAYS` | `180` |

## 附录 C：复现验证的命令

```powershell
# 1. 确认没有进程内调度
rg -n "@Scheduled|EnableScheduling|TaskScheduler" md-agent-server/src md-agent-bootstrap/src

# 2. 确认 scheduler 扩展引了但没用
rg -n "agentscope" md-agent-server/pom.xml
rg -n "agentscope\.extensions\.scheduler" md-agent-server/src md-agent-bootstrap/src

# 3. 确认权限被硬编码 BYPASS
rg -n "permissionContext|PermissionMode" md-agent-server/src/main/java/com/md/agentplatform/server/agent/ConfigurableAgentBuilder.java

# 4. 确认 webhook 工具没有节流
rg -n "sleep|Semaphore|throttle|retry" md-agent-server/src/main/java/com/md/agentplatform/server/tool/workwx/WorkWxWebhookSendTool.java

# 5. 挖框架旋钮（按需替换 jar 路径）
$javap = "C:\Users\User\.jdks\ms-21.0.11\bin\javap.exe"
$cp = "D:\jinran\maven\repo\io\agentscope\agentscope-harness\2.0.2-subagent-bugfix\agentscope-harness-2.0.2-subagent-bugfix.jar"
& $javap -cp $cp 'io.agentscope.harness.agent.HarnessAgent$Builder'
& $javap -p -constants -cp $cp "io.agentscope.harness.agent.memory.compaction.CompactionConfig"

# 6. 跑测试
mvn -o -pl md-agent-server -am test
```

## 附录 D：待核实清单

以下是我没有验证到、需要你确认的，确认后请把结论补回对应条目：

- [ ] 控制面里 `workwx_batch_agent` 的实际发布状态（B-1）
- [ ] REACT 路径是否另有框架内置的工具结果截断（T-2）
- [ ] 官方 `channel-wecom` 扩展是否支持智能机器人 aibot 协议（T-18）
- [ ] 通知目标群是否包含全部收件人（B-6）
- [ ] DeepSeek `reasoning_content` 400 的修复在重启后是否真的生效（历史问题，影响流式稳定性）
- [ ] 部署形态是单副本还是多副本（T-11）
