# 2026-09-24 工作流的运行入口：清单页补按钮 + 自动弹窗判早了

## 一句话结论

「工作流没有运行的地方」这一条反馈连着来了两次：
第一次只把入口补在**设计器页头**，而人是先在**清单页**找的，所以还是找不到 —— 这次把「运行」
放到清单页行操作的第一位；并且从清单页点「运行」会直接落到设计器的「运行记录」tab 并自动弹开
运行对话框。

补这条路时踩到一个真 bug：**自动弹窗那次判断跑得太早**，`dirty` 读到的还是「草稿还没灌进来」
的瞬间，于是一个刚打开、什么都没改的工作流被判成「有未保存改动」，按钮灰着、自动弹窗也变成
一句「暂时不能运行」。闸门换成 `savedSnapshot` 之后两个 watch 的先后就无关紧要了。

## 问题现场

| 轮次 | 反馈原话 | 当时的现状 |
| --- | --- | --- |
| 第 1 轮 | 「创建好了 workflow，但没有运行的地方」 | 设计器页头只有 保存草稿 / 校验 / 发布 / 删除 |
| 第 2 轮 | 「workflow 现在不还是没有运行的按钮吗，在哪运行啊」 | 清单页行操作只有 设计 / 删除 |

第 1 轮的修法（在页头加按钮）**方向没错但不解决问题**：`/workflows` 是进工作流模块的第一屏，
用户在那里找不到任何「运行」字样，就不会再往下点。

## 改动

### 1. 清单页行操作补「运行」

`src/pages/WorkflowsPage.vue` 的操作列现在是 **运行 / 设计 / 删除**（运行在最前，`type="primary"`）。

- 按钮外面包一层 `<span>` 再套 `el-tooltip`：button 一旦 `disabled` 就不再派发 `mouseenter`，
  不包的话鼠标移上去什么都不弹，用户拿不到"为什么不能跑"。
- 列宽重新配平（工作流 200→180、说明 160→130、最近改动 110→100、操作 140→200），
  合计与改动前一致，保住 `2026-09-23_workflow-design.md` 里记的「1280 视口下七列全放得下」。

### 2. 清单页只带路，不重复实现运行 UI

`openRun(row)` 只做一件事：`router.push('/workflows/<key>?tab=runs&run=1')`。

为什么不在这里也放一个输入框和「开始跑」：运行的 UI 只有设计器那一处。两份实现的必然结果是
漂移，而「清单页跑出来的结果和设计器里跑出来的不一样」是最难查的一类问题。

### 3. 所有可见文案「试跑」→「运行」

页头按钮、tab 内按钮（「试跑一次」→「运行一次」）、对话框标题、placeholder、
`triggerRef` 默认值（「页面试跑」→「页面运行」）。「试跑」是内部黑话，用户反馈里用的词是「运行」。

### 4. 能不能跑，由一份口径统一判定

`src/features/controlplane/workflowStage.ts` 新增 `workflowRunBlockedReason()`，与既有的
`workflowStageOf()` 同处一个文件 —— 徽标和按钮必须同源，否则会出现"清单页说能跑、点进去说不能"
这种没法解释的不一致。两个阻断条件：

| 条件 | 文案 |
| --- | --- |
| 从来没发布过（`publishedVersion === null`） | 还没有发布过：运行的是线上那一版，先点一次「发布」 |
| 有未保存的改动（只有设计器传 `dirty`） | 有未保存的改动：先「保存草稿 → 发布」，运行的是线上那一版 |

这两条都**在按钮上拦住**，不让用户点下去等后端回 409 —— 后端那条 409 是兜底，不是交互。

### 5. 自动弹窗判早了（本轮唯一的功能性 bug）

从清单页带 `?run=1` 进来，设计器要在详情落地后才决定弹不弹对话框：

```ts
// 修好后
watch(
  () => [isLoading.value, detail.value, savedSnapshot.value] as const,
  () => {
    if (!pendingRun.value) return;
    if (isLoading.value || !detail.value || savedSnapshot.value === '') return;
    ...
  },
  { immediate: true },
);
```

`workflowRunBlockedReason` 要读 `dirty`，而 `dirty = snapshot() !== savedSnapshot`，`savedSnapshot`
由上面那条 `watch(detail, ...)` 灌草稿时写入。**两条 watch 都是被 `detail` 的变化触发的，谁先跑
不由我们决定** —— 实测这里先跑，打点确认那时 `savedSnapshot` 还是空串：

```text
DBG-WATCH {"isLoading":false,"hasDetail":true,"publishedVersion":2,"dirty":true,
           "reason":"有未保存的改动：先「保存草稿 → 发布」...","savedSnapshot":""}
DBG-DRAFT true          ← 灌草稿这一步在它之后才发生
```

于是 `dirty` 恒为 true，判断结论是假的。把 `savedSnapshot` 当闸门（空 = 草稿还没灌进来），
先后顺序就不再影响结果；它同时进 sources，是因为闸门自己会变一次，得靠那次变化把 watch 再叫醒。

### 6.`watch(activeTab)` 补 `immediate`

`runsEnabled` 由 `watch(activeTab)` 置位，而带 `?tab=runs` 进来时没有"切换"这个动作，
不加 `immediate` 就永远不置位 —— 运行记录那一页一直空着，而库里其实有记录。

## 工作流怎么用（五步）

1. `/workflows` → 「+ 新建工作流」，填 `workflow-key`（业务主键，页面上不展示）+ 展示名；
2. 进设计器加节点（`+ Agent` / `+ 条件` / `+ 汇合`）、连边、配节点（key / 说明 / 目标 agent / 边上的表达式）；
3. 「保存草稿」（只落库，半成品也能存）→「校验」（只看问题、不落库，用的是**正在编辑的这份**）；
4. 「发布」：后端先校验一遍，通过后写一份快照并让新版本生效；
5. 「运行」：填输入 JSON（条件表达式按 `input.<字段>` 取值）→ 开始跑 → 画布上叠加各节点状态，
   点节点能进那一次调用的执行链路。

## 内部逻辑（`WorkflowEngine.execute`）

- **取的是已发布版本的冻结快照**，不是草稿。运行期间别人改草稿又重新发布，本次运行仍按启动时那版跑完。
- **按拓扑序逐节点同步推进**，一次运行只有一个线程在决策（第一批刻意不做并行）。
- **边有自己的状态机**：`PENDING / TAKEN / SKIPPED`，只在内存里。节点「就绪」= 全部入边已决断；
  「真的执行」= 就绪且至少一条入边 `TAKEN`（入口节点无入边视为满足）；否则整个节点标 `SKIPPED`
  并把出边全部 `SKIPPED`，逐层传播。
- **分支只由 condition 节点制造**：按声明顺序求第一条为真的出边，其余标 `SKIPPED`。
  顺序就是画布上连边的顺序（所以「上移 / 下移」是语义而不是排版偏好）；一条都不成立时以
  `NO_BRANCH_MATCHED` 明确失败，而不是静默停住。其余类型把所有出边都放行。
- **表达式作用域**：`input.<k>` / `run.input.<k>` / `run.variables.<k>`（第一批恒空）/
  `node.<key>.text|state|type`；取不到一律 null 不报错。语言是自研受限子集（`== != > >= < <= && || !`
  + 括号 + 路径，逻辑短路，无函数调用无算术），替换点收敛在 `WorkflowConditionEvaluator` 的两个静态方法。
- **失败策略只有 fail_fast**：任何节点失败 → 整次运行立刻 `FAILED`，**没轮到的节点保持 `PENDING`**
  （不是 `SKIPPED`，两者语义和颜色都不同）。
- **节点输入怎么拼**：本步说明（label）+ 本次运行输入 JSON + 上游节点的结果文本（单节点截断 4000 字）。
- **整次运行共用一个会话键** `wf-<runId>`，用户默认 `console`；状态是**按 agent 分区**存的，
  所以不同 agent 的节点互不影响，同一 agent 出现在两个节点上时第二个节点会看到第一个的上下文。
- **agent 节点**：先写节点行的 `agentRunId`（`TraceRunIdGenerator.next()`）再调 `AgentManager`，
  页面因此能从节点点进执行链路；agent 在发布后被删/停用时，**手工落一条节点失败行**
  （`FAILURE_AGENT_MISSING`），而不是只让整次运行失败。
- **前置检查在控制器**（`WorkflowController` 的 `POST /{key}/runs`）：工作流不存在 → 404；
  没有已发布版本 → 409。两者刻意不交给引擎抛异常 —— 引擎的 `ControlPlaneValidationException`
  统一翻译成 400，那是给"字段填错了"用的。
- **同步阻塞**：控制器把调用切到 `boundedElastic`，接口超时由调用方承担。第一批不做异步，
  因为异步需要一个能恢复的执行器，否则进程重启即悬空。

## 验证

| 项 | 结果 |
| --- | --- |
| `npx vue-tsc --noEmit` | 0 错 |
| `npx vitest run` | 11 文件 / **151** 用例全过（新增清单页运行入口 1 条） |
| 新增用例覆盖 | 点行内「运行」→ 落到 `#pane-runs` → 对话框标题是「运行一次」→ `?run` 已被清掉 |
| 浏览器实测 | 点清单页「运行」：URL `/workflows/test_workflow?tab=runs`，激活 tab「运行记录」，对话框「运行一次」可见 |

`run` 参数用过就 `router.replace` 掉：留着的话刷新或按后退会再弹一次，而那时用户并没有想再跑一遍。

## 遗留

- 触发方式只有手动（`triggerType` 只写 `manual`）：定时、事件、channel 触发都还没有。
- 五类节点（team / approval / timer / signal / subrun）未实现，枚举里显式认识这些名字并报「属于后续批次」。
- 边状态不落库（画布颜色按节点状态反推，入边多义时不上色），所以"哪条分支被跳过"从库里查不到。
- `POST /{key}/publish` 必须带 JSON body，否则 415。