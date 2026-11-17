# 工作流（Workflow）：DAG 编排的第一批基础能力

> 日期：2026-09-23
> 范围：`md-agent-server` / `md-agent-bootstrap` / `agent-contro-plane`
> 落表脚本：`db/workflow-schema.sql`（4 张新表）+ `db/workflow-schema-upgrade-2026-09-23.sql`（`md_agent_run` 补 2 列）
> 决策来源：用户确认的两类工作流 + 三层落表方案；本批只做 `agent` / `condition` / `join` 三类节点

---

## 0. 结论速览

| 问题 | 结论 |
| --- | --- |
| 工作流是什么 | **第六类受治理资源**：草稿可改、发布写快照、可回滚、版本单调递增 —— 与 prompt / skill / mcp / channel / model 同一套生命周期 |
| 第一批实现到哪 | 固定节点 DAG：`agent` 调一个已装配的 agent、`condition` 按表达式选一条出边、`join` 等入边到齐 |
| 定义存在哪 | `md_workflow.spec_json` —— **整张图一段 JSON**，不是节点表 + 边表 |
| 引用怎么反查 | `md_workflow_node_ref` —— 发布时投影出来的「这个节点引用了谁」，供删除保护 |
| 运行记录存在哪 | `md_workflow_run`（一次编排一行）+ `md_workflow_run_node`（每个节点一行） |
| 每个节点的 agent 调用在哪 | 还是 `md_agent_run`，靠新增的 `workflow_run_id` / `workflow_node_key` 两列挂回来 |
| 前端在哪 | `/workflows`（清单）+ `/workflows/:workflowKey`（设计器：画布 / 节点配置 / 连线 / 校验 / 运行记录 / 版本） |
| 本批没做 | `team` / `approval` / `timer` / `signal` / `subrun` 五类节点、定时触发、并行执行 |

---

## 1. 工作流的两类形态

这是本模块最先要分清的一件事，否则「工作流」会把两种毫不相干的实现混成一个词。

### 1.1 类型 1：固定节点 DAG（本批实现）

节点与边由人编排好，执行顺序由**拓扑**决定，不需要模型参与调度。
引擎要解决的只有四件事：拓扑顺序、分支选择、汇合、失败语义。

适用：步骤确定、顺序确定、只在中途做「如果 / 否则」的流程
（月度工时核对、报表生成与投递、按环境跑一组发布检查）。

### 1.2 类型 2：harness 自主调度（本批不动，但今天就能跑）

**它不需要工作流引擎。** 一个 HARNESS agent + 子 agent 清单 + 工作区里的
`AGENTS.md` / `skills/`，本身就是「让模型自己决定下一步调谁」的编排。

这一点很关键：如果给类型 2 也套一层 DAG，得到的是一张只有一个节点
（`harness`）的图 —— 那层图不表达任何东西，却要额外维护 spec、版本、节点行。
所以本模块**只**为类型 1 建表、建接口、建页面。

两者的分工可以一句话概括：

| | 类型 1（本批） | 类型 2（既有） |
| --- | --- | --- |
| 谁决定下一步 | 图的拓扑 + condition 表达式 | 模型自己 |
| 可预测性 | 完全可预测、可复现 | 不可预测，但能处理开放任务 |
| 观测点 | 节点级（谁成功、走了哪条分支、耗时） | 靠执行链路的调用树 |
| 落表 | `md_workflow_run` + `md_workflow_run_node` | 无（就是一次普通的 agent run） |

---

## 2. 数据模型：三层落表

### 2.1 三层各自回答什么

| 层 | 表 | 回答问题 | 与谁同类 |
| --- | --- | --- | --- |
| 定义层 | `md_workflow` | 这张图长什么样 | `md_prompt` / `md_mcp_server` |
| 定义层（投影） | `md_workflow_node_ref` | 这张图引用了谁 | 无（发布时生成的索引） |
| 运行层 | `md_workflow_run` | 这一次编排跑成什么样 | `md_agent_run` |
| 运行层 | `md_workflow_run_node` | 这一次里每个节点跑成什么样 | `md_agent_run_event` |
| 执行层 | `md_agent_run.workflow_run_id` / `workflow_node_key` | 某个节点呼出来的那条链路是哪条 | 同表既有列 `source` |

### 2.2 关键取舍

**① 为什么 `spec_json` 是一段 JSON，而不是「节点表 + 边表」**

三类节点的配置字段完全异构（agent 要 `agentId`、condition 要表达式、join 要模式），
建列的结果必然是「一张表塞二十个大部分为 NULL 的列」或「每类节点一张表」。
更要紧的是读写粒度：保存提交的是**全量 spec**，校验、发布、执行读的也都是**整张图**，
不存在「只改一个节点」的请求。拆表换来的只是把一次 INSERT 变成一次先删后插的循环。

拆表唯一真正的好处是「能反查引用」，这个能力由 `md_workflow_node_ref` 补上 —— 
而且反查只需要**发布过**的引用，草稿里的引用本来就不需要保护。

**② 为什么版本快照复用 `md_resource_version`，不新开一张 revision 表**

「草稿 / 发布 / 快照 / 回滚」这套流程已被五类资源验证过，工作流是第六类。
另起一张表意味着版本列表、回滚、快照序列化三处都要再实现一遍，
而它们必须与另外五类**永远一致** —— 重复实现的唯一效果是多一个会漏改的地方。
`resource_type` 因此新增取值 `WORKFLOW`。

**③ `md_workflow_node_ref` 是投影，且只在发布时写**

- 发布：删掉该 `revision` 的旧行 + 写入本次 spec 投影出的引用；
- 回滚：**不重写**。回滚只把内容写回草稿，线上仍是原来那一版，
  此时若把 `node_ref` 也改掉，反查出来的就是「一份还没生效的引用」，
  删除保护会拦住一次本来安全的删除。
理由写在 `MyBatisControlPlaneStore.projectNodeRefs` / `rollback` 的注释里。

**④ 时间一律 `BIGINT`（epoch millis）**

与 `trace-schema.sql` / `control-plane-schema.sql` 一致，避免 `serverTimezone`
改动导致历史数据整体漂移。

**⑤ 不做外键、不做级联删除**

运行数据是可丢弃的观测类数据，连「删除顺序」都不需要维护；
级联只会把「丢弃」变成一次有风险的连锁操作。`workflow_node_ref` 也不加外键指向
`md_workflow`：它是索引，不是约束的载体。

---

## 3. 节点类型

| 类型 | 做什么 | 必填配置 | 本批 |
| --- | --- | --- | --- |
| `agent` | 调一个已装配的 agent，取它的最终文本作为输出 | `agentId` | ✅ |
| `condition` | 按表达式从出边里选**一条**，不调用模型、不产生执行链路 | `condition` 表达式 | ✅ |
| `join` | 等入边到齐，把走通的那几支结果收在一起 | `join.mode`（缺省 `all`） | ✅ |
| `team` | 多 agent 协作（一个节点内跑一支团队） | — | ❌ 后续批次 |
| `approval` | 人工审批（挂起、等外部动作、恢复） | — | ❌ 后续批次 |
| `timer` | 定时 / 延迟 | — | ❌ 后续批次 |
| `signal` | 等待外部信号（回调 / webhook） | — | ❌ 后续批次 |
| `subrun` | 调另一条工作流 | — | ❌ 后续批次 |

### 3.1 为什么另五个类型不做

它们的共同点是要**挂起 + 跨进程恢复**：一次执行的中间态要能从数据库里恢复回来，
而当前引擎是「一次调用里同步跑完」（见 §4.1）。在没有持久化执行器之前做它们，
只能做成「假挂起」—— 那比不做更糟：用户会以为流程停在那里等审批，
实际进程一重启就没了。

这五个名字**保留在 `WorkflowNodeType` 里**、页面的下拉如实展示它们，
但校验器会明确拒绝。保留的理由是让报错能区分两种情况：
「这个名字存在、但现在不给你用」与「你把名字拼错了」——后者的排查成本高得多。

### 3.2 `condition` 的语义：if / else-if，兜底边必须排在最后

out-edges 按 **spec 里的声明顺序**依次求值，**取第一条成立的**，其余一律 `SKIPPED`。

这里踩过一个真实的坑：早期实现里「已选中一条」之后没有短路，于是
「条件成立的真分支」与「最后那条不带条件的兜底边」**同时**被标成 `TAKEN`，
兜底分支被无条件执行了一遍 —— 而页面上看起来只走了一条路。
修法就是那个不能省的 `taken == null &&`（`WorkflowEngine.runConditionNode`）。

由此推出一条**语义要求**（不是排版偏好）：兜底边（不写条件那条）必须放最后，
排前面会把后面的分支全挡掉。所以设计器里的「上移 / 下移」是功能性按钮，
`md_workflow_run_node.branch_taken` 也把「选了哪条」明确记下来。

### 3.3 条件表达式为什么是自研受限子集

Maven 仓库里没有 CEL 的可用实现，而引一个表达式引擎进来会顺带引入
「表达式能做什么」这一整套需要向用户解释的边界。当前子集只保留扫表时真正会用的：

| 能力 | 支持 |
| --- | --- |
| 比较 | `== != > >= < <=` |
| 逻辑组合 | `&& \|\| !` 与括号 |
| 取值 | `input.x` / `run.input.x` / `run.variables.x` / `node.<key>.text`（以及 `state` / `type`） |
| 取不到的值 | 一律按 `null` 处理，不抛错 |
| 函数调用 / 算术 | ❌ 不支持 |

这段边界**直接印在页面上**（`CONDITION_HINT`）：照着 CEL 文档写一定会踩空，
而踩空的表现只有一句「语法错误」，不解释就没人能自己改对。

---

## 4. 执行模型

### 4.1 为什么是同步阻塞

`POST /api/workflows/{key}/runs` 同步跑完才返回。这不是偷懒，而是当前架构下的正确选择：

- 引擎的主循环本来就是同步的（拓扑序 for 循环），agent 调用用 `blockLast()` 阻塞；
- 没有持久化执行器，异步化只会得到「接口返回了 runId，但进程一重启这次执行就悬空」；
- 页面的「试跑」要的就是「这一次跑成什么样」，同步返回最直接。

代价写在接口语义里：**超时风险由调用方承担**，长流程要调大客户端超时。

### 4.2 拓扑序 + 冻结 revision

执行读的是**已发布版本**（`loadPublishedWorkflow` — 主表 `published_version` → 
`md_resource_version` 的快照），不是草稿。由此得到官方设计里最值钱的一条性质：

> **后续编辑不改变已有运行的拓扑。** 执行期间有人改了草稿并发布，这次执行仍按它启动时的版本跑完。

否则页面上会出现「图变了但节点状态对不上」——那种现象没法向用户解释。
`md_workflow_run.revision` 记的就是这次锁定的版本号。

没有已发布版本时点运行 → **409**（不是 400）：这是「状态不对」，不是「参数写错」，
页面上对应「先去设计器点一次发布」这句提示。

### 4.3 会话键 `wf-<runId>`：整次运行共用一个会话

一次工作流执行里的多个 agent 节点共享同一个 `sessionId`。
这么做是为了让「这次编排整体说过什么」是一份连续的上下文，而不是每个节点各自开一份。
`md_workflow_run.session_id` 单独留了一列，也正是为了这个键能被查到、能被关联。

### 4.4 节点状态机

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 还没轮到 |
| `RUNNING` | 正在跑 |
| `SUCCEEDED` | 跑完了 |
| `FAILED` | 这一步失败，整次运行随之中止（`policy = fail_fast`） |
| `SKIPPED` | 上游分支没走到它，**永远不会执行** |

`PENDING` 与 `SKIPPED` 的区别就是「还没轮到就断了」与「明确不走它」——
画布上这两个颜色不同，否则「为什么这一步没跑」没法回答。

### 4.5 执行链路怎么和节点对上

节点行的 `agent_run_id` 在**调用前**就生成好并写进 `md_workflow_run_node`，
再经 `RuntimeContext` 的四个属性（`runId` / `source` / `workflowRunId` / `workflowNodeKey`）
传给中间件，由中间件写进 `md_agent_run`。于是：

- 页面上从节点直接跳到 `/traces/<agentRunId>` 看那次调用的完整链路；
- 「只看某次工作流运行的所有链路」= 按 `workflow_run_id` 过滤（走 `idx_workflow_run` 最左前缀）；
- `workflow_run_id` / `workflow_node_key` 都可空，`NULL` 的语义是
  「这条链路不属于任何工作流运行」——**不是**「属于但没记」。
  这与 `source` 列的语义相反（那里 NULL = 入口方没标注），别混为一谈。

---

## 5. 落表与校验

### 5.1 校验器报什么（收集式，一次报全）

| 分组 | 规则 |
| --- | --- |
| 节点 | key 必填、格式 `[A-Za-z_][A-Za-z0-9_]*`、不重复；类型必须认得；按类型必填（agent 要目标存在、condition 要表达式可解析、join 模式必须 `all`） |
| 边 | 两端必须是已定义节点；同一对端点不重复；**条件只允许出现在 condition 节点的出边**上 |
| condition | 至少一条出边 —— 没有出边的 condition 只能停在那里，运行期必然报 `NO_BRANCH_MATCHED` |
| 图结构 | 无环 → 入口唯一 → 全部可达（三步有先后，后两步建立在「图是 DAG」之上） |

两条口径：

- **agent 存在性在发布前就查**。等到执行时才发现，前面的节点已经产生副作用了。
- key 格式**连形状都要管**：`node.<key>.text` 是唯一的寻址方式，
  放行 `triage-1` 会得到「节点建得了、但永远引用不到它」这种半残状态。

> 关于可达性：**入口唯一 + 无环已经蕴含全部可达**，所以校验器第三步是兜底，
> 构造不出独立的失败样例。这一点写成了 `WorkflowValidatorTest.连通的图不报可达性问题` 的注释，
> 免得后人以为「这条分支没测到」。

### 5.2 发布 = 校验 → 写快照 → 投影引用 → 生效

发布时后端先校验（不通过回 **400 + 逐条 violations**），通过后：
写 `md_resource_version` 快照、更新 `published_version` / `published_at_ms`、
重写 `md_workflow_node_ref`，然后返回 `PublishResult{ reloadedAgents, note }`。

工作流的 `reloadedAgents` 是**空的**，`note` 说明原因：
工作流不需要重建 agent 实例，发布后立即对新执行生效。
（这点与 prompt / skill 发布不同 —— 那些要重建引用它们的 agent 实例。）

### 5.3 删除保护

`DELETE /api/workflows/{key}` 之前要检查 `md_workflow_node_ref`：
被别的流程引用时回 **409 + referencingWorkflows**，页面据此列出「先解绑谁」。
同理，删除 agent 时也会检查 `findReferencingWorkflows`，
拦住「删掉一个还被工作流引用的 agent」。

---

## 6. 接口清单

工作流：`WorkflowController`（`/api/workflows`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/workflows` | 清单（不含 spec，只带 nodeCount / edgeCount） |
| POST | `/api/workflows` | 新建（`workflowKey` + `displayName` + 可选 spec）；重名 409 |
| GET | `/api/workflows/{key}` | 详情（含 `spec`） |
| PUT | `/api/workflows/{key}` | 保存草稿（全量 spec） |
| DELETE | `/api/workflows/{key}` | 删除；有引用则 409 |
| POST | `/api/workflows/{key}/validate` | 只校验、不落库；body 里的 spec 为空则校验库里草稿 |
| POST | `/api/workflows/{key}/publish` | 校验 → 写快照 → 生效；不通过 400 + violations |
| POST | `/api/workflows/{key}/rollback` | 按版本号写回**草稿**（线上不动） |
| GET | `/api/workflows/{key}/versions` | 版本快照列表（不含 snapshot_json） |
| POST | `/api/workflows/{key}/runs` | 执行一次（同步阻塞）；404 工作流不存在 / 409 没有已发布版本 |
| GET | `/api/workflows/{key}/runs?limit=` | 该工作流的运行记录 |

运行详情：`WorkflowRunController`（`/api/workflow-runs`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/workflow-runs/{runId}` | run + 全部节点合成一个响应体 |

> **为什么运行详情不在 `/api/workflows/{key}/runs/{runId}`**：
> 那条路径与「工作流的运行列表」`/api/workflows/{key}/runs` 只差一段，
> 以后再加 `/api/workflows/runs/{runId}` 这类写法就会构成歧义模式。
> 单独起一个前缀之后，`/api/workflows/**` 下的路径空间永远是「工作流定义与它的附属列表」。
---

## 7. 前端：设计器（`/workflows/:workflowKey`）

### 7.1 两页分工

| 页面 | 注意力单位 | 回答什么 |
| --- | --- | --- |
| `/workflows` 清单页 | 有几条、各自什么状态、最近改没改 | 找一条流程 |
| `/workflows/:key` 设计器 | 这张图哪里不通 | 改一处流程 |

拆开与 AgentsPage / AgentDetailPage 同一套理由：混在一页里，画布要么被挤得很小，
要么把清单顶到看不见的地方。而「边连线边看清单」并不是一个真实动作。

### 7.2 画布为什么是手写的 SVG

官方前端也是手写的（`WorkflowGraph.tsx`，约 130 行）：按入边算列深度 + 三次贝塞尔 + 箭头 marker。
不引 react-flow / x6 的理由很实际 —— 本项目的图只有三类节点、顺序执行、几十个节点上限，
而这类库要额外带来拖拽画布、缩放、坐标持久化、命中测试一整套状态，
那套状态还得与**真源**（spec 里的 nodes / edges）保持同步，是净负担。

列深用**最长路径**而不是最短：多条路径汇聚时，按最短排会让下游节点被上游的边穿过。

### 7.3 谁是真源：位置是派生值

真源永远是 `spec = { nodes, edges }`。画布上所有坐标都是**现算的派生值**，不落库、不回写。

所以页面上**没有「拖节点」这个动作** —— 位置由拓扑决定，拖了也存不下来
（要存就得处理「两个节点叠在一起」这类对流程毫无意义的问题）。
要改的是**连接关系**与**节点配置**，这两件事在右侧面板里做。

### 7.4 保存 / 校验 / 发布 三件事各自的边界

| 动作 | 落库 | 用哪份内容 | 说明 |
| --- | --- | --- | --- |
| 保存草稿 | ✅ | 本地正在编辑的 | 可以半成品（后端保存时不校验） |
| 校验 | ❌ | 本地正在编辑的 | 只报问题；不通过是「一次正常的询问结果」，走 warning 不是 error |
| 发布 | ✅ | 后端先校验**库里那份** | 所以页面会先保存再发布，否则发的是旧内容 |

最后一条是「我明明改了、发布完却没变」的经典来源，所以 `publishMutation` 里
先 `saveMutation.mutateAsync()` 再 publish。发布失败时后端回的 violations
会**灌进同一块校验卡** —— 文件头那句「发布失败与校验不通过看到的是同一批问题」
要落到界面上，否则用户只看到一条通知、刷新就没了。

### 7.5 运行叠加：「在图上看这次执行」

从运行详情点「在图上看这次执行」把某次执行的节点状态叠到画布上：
走通的边变绿加粗、被跳过的边变灰虚线、未执行的节点保持中性色；
标题条右侧一句话汇总（`成功 2 · 跳过 1`）。这是排查「分支为什么没走」最直接的一屏。

**边状态只能「按语义还原」**，这一点值得记下来：真源里没有边状态 ——
`TAKEN` / `SKIPPED` 是引擎执行时的内存量，不落库（只有节点行有状态）。
所以按能确定的规则上色：下游被跳过 ⇒ 这条边被跳过；下游跑过或上游成功 ⇒ 走过。
会有歧义的情形是「一个节点有多条入边而它被执行了」，此时无法只从节点状态反推
究竟哪几条入边走过 —— 所以只做到「不歧义才上色」；
精确的边状态要看运行详情里 condition 节点的 `branchTaken`（它明确写了选了哪条）。

### 7.6 三态徽标口径收在一处

`status` 只区分 `DRAFT` / `PUBLISHED`，但用户真正关心的是第三件事：

| 阶段 | 判定 | 含义 |
| --- | --- | --- |
| `PUBLISHED` | `status = PUBLISHED` | 线上就是这一版 |
| `CHANGED` | 草稿且 `publishedVersion != null` | 改过还没发，**能运行** |
| `NEVER` | 草稿且 `publishedVersion = null` | 从没发布过，点了运行会被后端回 409 |

判定与文案收在 `src/features/controlplane/workflowStage.ts`，
清单页与设计器共用 —— 两份映射必然漂移，表现是「列表说已发布、详情说待发布」，
用户没法判断该信谁。

### 7.7 前端文件清单

| 文件 | 内容 |
| --- | --- |
| `src/api/controlplane/types.ts` | 工作流类型区（`WorkflowSpec` / `WorkflowNodeType` / 运行行 / 节点行 / 校验结果） |
| `src/api/controlplane/index.ts` | 第十节：12 个接口函数 |
| `src/features/controlplane/workflowStage.ts` | 三态口径（新增） |
| `src/lib/queryKeys.ts` | `workflows` / `workflow(key)` / `workflowRuns(key)` / `workflowVersions(key)` |
| `src/pages/WorkflowsPage.vue` | 清单页（chips 三态 + 关键字 + 新建抽屉 + 删除确认） |
| `src/pages/WorkflowDesignerPage.vue` | 设计器（画布 SVG + 节点 / 连线 / 校验 / 运行记录 / 版本 + 试跑对话框 + 运行详情抽屉） |
| `src/app/router.ts` | `/workflows/:workflowKey`（`meta.wide`） |
| `src/app/nav.ts` | 设计器顶栏标题沿用「工作流」 |
| `src/pages/routes.smoke.test.ts` | 路由夹具 + 2 条设计器行为断言 |

另有一处**契约纠正**：`WorkflowRow` 原本 `extends Governed`，
但后端这个投影**不回** `createdAtMs` / `createdBy` / `updatedBy` —— 
声明成 `Governed` 会让页面以为「拿得到创建人」而实际拿到 `undefined`。
已改成独立接口并写明原因（这类谎报只有点进页面才暴露）。

---

## 8. 与既有各域的关联

| 域 | 关联方式 | 谁写 | 谁读 |
| --- | --- | --- | --- |
| agent | `agent` 节点存 `agentId`（`md_agent.agent_name`）；发布前查存在性 | 设计器 | `WorkflowValidator` / `WorkflowEngine` |
| agent（删除保护） | `md_workflow_node_ref` 反查引用 | 发布时投影 | `AgentController.delete` |
| 执行链路 | `md_agent_run.workflow_run_id` + `workflow_node_key` | 中间件（照抄 RuntimeContext） | 链路列表按工作流筛选 / 节点跳链路 |
| 会话与状态 | 整次运行共用一个 `sessionId = wf-<runId>` | 引擎 | 状态存储页按会话查上下文 |
| prompt / skill / mcp / middleware | **不直接关联**：工作流只调 agent，agent 自己带这些能力 | — | — |
| channel / gateway | **不直接关联**：渠道把消息交给某个 agent，而该 agent 可能是工作流的一个节点 | — | — |
| 版本快照 | `md_resource_version.resource_type = WORKFLOW` | 发布 / 回滚 | 版本列表、回滚 |
| 模型 / 环境 | 工作流不感知：模型由 agent 决定，环境由 agent 执行时注入 | — | — |

一句话：**工作流是「agent 之上的第二层编排」，它只认 agent 这个名字，
对 agent 内部用什么模型、挂哪些工具、走哪条渠道一概不管。**
这也是它不需要动其它域一行代码就能接上的原因。
---

## 9. 联调实测（2026-09-23，后端 9090 + 前端 dev 5273 真跑）

### 9.1 先决条件：两份 DDL 必须手工执行

工作流四张表**不做自动建表**（与其它 schema 脚本一致）。没执行时的表现是
`GET /api/workflows` 直接 500（找不到表），而不是一条能看懂的报错 ——
所以这一步漏了会先去怀疑代码。

```bash
mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < workflow-schema.sql
mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < workflow-schema-upgrade-2026-09-23.sql
```

第二个脚本**刻意不幂等**：重复执行会因「列已存在」报错。
静默忽略重复执行需要写存储过程查 `information_schema`，
而这类一次性升级脚本的错误应当显式暴露，而不是看起来「成功」却没生效。

### 9.2 命令级验证（全链路）

夹具：一条**不含 agent 节点**的三段图（`condition` 分流 → 两个 `join`），
可反复重跑、不产生任何模型调用与副作用。

| 步骤 | 结果 |
| --- | --- |
| `POST /api/workflows` | 200，`status=DRAFT`、`version=1`、`publishedVersion=null` |
| `POST .../validate`（正例） | 200 `{ok:true, problems:[]}` |
| `POST .../validate`（反例） | 200 `{ok:false}`，一次报出全部问题：`节点 nope 是 agent 节点但没有选目标 agent`、`边 gate → missing 的终点 missing 不是已定义的节点` |
| `POST .../publish` | 200，`reloadedAgents=[]` + note（工作流发布无需重建 agent） |
| `POST .../runs` `{"month":"2026-09"}` | 200 `SUCCEEDED`；`gate.branchTaken=done`、`done` 成功、**`audit` SKIPPED** |
| `POST .../runs` `{}` | 200 `SUCCEEDED`；走兜底边 → `gate.branchTaken=audit`、**`done` SKIPPED** |
| `GET /api/workflow-runs/{runId}` | 200，run + 3 个节点行；`sessionId = wf-<runId>` |
| `GET .../runs` / `.../versions` | 2 条运行 / 1 条版本（带 changeNote） |
| `PUT /api/workflows/{key}` | 200 `version=2`、`status=DRAFT`（保存推进版本，不碰线上） |
| `POST .../publish` → `rollback v1` → `publish` | 回滚后 `version=3 / publishedVersion=2 / status=DRAFT`，note 明确「线上仍是原版本，需再点一次发布」；再发布后 `publishedVersion=3`，版本列表 v1 / v2 / v3 齐全 |
| 反例：引用了不存在的 agent | `publish` → **400**，响应体是前端直接消费的形状：`{"error":"校验未通过（1 条）","violations":["节点 a 引用的 agent 不存在：no_such_agent"]}`。**发布前**就拦住，不会跑到执行期；此时 `runs` → 409「还没有已发布的版本」 |

> 上面第一次 `rollback` + 再发布这一串不是「为了测而测」：
> 它验证的是「回滚只写草稿、线上不动」这条口径真的落在接口行为上，
> 而不是只写在注释里。

### 9.3 页面级验证（真浏览器逐屏看过）

| 屏 | 看到什么 |
| --- | --- |
| `/workflows` 清单 | 三态 chips（全部 1 / 已发布 1 / 有改动待发布 0 / 未发布过 0）+ 关键字 + 表格（节点/边 3/2、版本 v3 线上 v3、状态、最近改动、设计/删除） |
| 新建抽屉 | `workflow-key` 格式校验、展示名必填、创建并进入设计器 |
| 设计器 · 设计 | 画布按拓扑分两列铺开；选中节点描边变紫；右侧节点清单带类型徽标；条件节点的「出边（按声明顺序求值）」把 `done`（带条件）与 `audit`（兜底徽标）按顺序列出 |
| 设计器 · 运行记录 | 单行紧凑表格，结果列 `成功 2 · 跳过 1`；行点击开详情 |
| 设计器 · 版本 | 版本历史（v3 带「线上」标） + 发布卡（变更说明 + 发布当前草稿） |
| 试跑对话框 | 页面内真实跑一次：返回通知「执行完成 耗时 763ms」，自动切到运行记录并新增一行 `页面联调试跑 / 成功 / v3 / 763ms` |
| 运行详情抽屉 | 节点表：`audit 成功 69ms {"joinedFrom":["gate"]}`、`done 跳过`、`gate 成功 71ms → audit`；下面是运行的输入与输出 JSON |
| 在图上看这次执行 | 画布叠加：`gate` 绿、`done` 灰（跳过）、`audit` 绿；`gate→audit` 绿色加粗、`gate→done` 灰色虚线；标题条右侧 `成功 2 · 跳过 1` |

联调中改掉的三处**真实体验问题**（都只有真看页面才发现）：

| 问题 | 表现 | 处理 |
| --- | --- | --- |
| 结果列塞 JSON | 整段 `{nodes:{…}}` 把每行撑成三四行高，表格没法扫 | 改成只数节点状态（`成功 2 · 跳过 1`）+ `show-overflow-tooltip` 截断；完整输出留在运行详情 |
| 连线行文本被截 | 一刀切 `nowrap` 后连 `gate → done` 都变成 `gate →…` | 分两类：`orow__fixed`（端点，永不缩）与 `orow__soft`（条件，可截断 + `title` 看全） |
| 清单页操作列看不见 | 列宽合计超过 1280 视口的容器宽度，「设计 / 删除」被挤到横向滚动区外 | 收紧各列宽度，让常用视口下七列全放得下 |

### 9.4 前端门禁

| 检查 | 结果 |
| --- | --- |
| `vue-tsc --noEmit` | exit 0 |
| `vitest run` | **132 passed**（基线 129，新增路由用例 1 + 设计器行为断言 2） |
| `vite build` | 成功，`WorkflowDesignerPage` 独立 chunk ≈ 33 kB |

两条新增的行为断言值得留下，因为它们钉的是 vue-tsc 看不见的东西：

- **「节点与连线按 spec 铺到画布上」**：`.nbox` 3 个、`.wire` 3 条。
  布局算法写错（入度没减、有环时漏节点、按最短路径分层）时节点数会变，
  而类型检查与纯函数单测都测不到；
- **「连线列表保持库里的顺序」**：断言三条边的顺序与 spec 一致。
  一旦页面在这里排序或去重，`condition` 的兜底边就可能跑到有条件的那条前面，
  表现是分支被静默跳过，而画布上完全看不出来。

---

## 10. 技术债与已知边界

| 项 | 说明 |
| --- | --- |
| 五类节点未实现 | `team` / `approval` / `timer` / `signal` / `subrun`：要持久化执行器（挂起 + 跨进程恢复）才做 |
| 执行是同步阻塞 | 接口超时由调用方承担；异步需要一个能恢复的执行器，否则进程重启即悬空 |
| 边状态不落库 | `TAKEN` / `SKIPPED` 只存在于执行期的内存；画布上的边颜色是「按节点状态反推」，入边多义时不上色（见 §7.5） |
| 触发方式只有手动 | `triggerType` 目前只写 `manual`；定时触发要等 `timer` 节点与调度器 |
| `POST .../publish` 必须带 JSON body | 不传 body 时 Spring 回 **415**（`@RequestBody` 需要 `Content-Type`）。前端始终发 `{changeNote}`，所以走不到；命令行调用需要 `-H 'Content-Type: application/json' -d '{}'` |
| 无 agent 可用时只能验证无模型路径 | 当前开发库 `md_agent` 为空，所以联调用的是纯 condition / join 的图；agent 节点的链路归属（`workflow_run_id`）要等有 agent 后在页面上再确认一次 |
| 工作流清单是投影 | 列表接口刻意不回 `createdAt/by`；`WorkflowRow` 的类型已按此纠正（见 §7.7） |

---

## 11. 一句话总结

工作流是**第六类受治理资源**，复用「草稿 / 发布 / 快照 / 回滚」这套已被验证五次的流程，
把「整张图」存成一段 JSON、把「引用了谁」投影成一张反查表、
把「跑成什么样」拆成 run / run_node 两层、
把「每个节点实际调了什么」挂回既有的 `md_agent_run`；
执行读**冻结的已发布版本**、节点按**拓扑序**跑、分支只在 `condition` 处产生、
整次运行共享一个会话键 —— 于是页面上能同时看到「图长什么样」与「这一次走到了哪，
哪条分支被跳过」，而为此新增的只有四张表和两列。