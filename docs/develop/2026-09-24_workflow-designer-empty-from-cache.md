# 2026-09-24 工作流详情一片空白：详情已经在缓存里时 watch 不触发

## 一句话结论

工作流设计器用 `watch(detail, ...)` 把服务器那份详情灌进草稿，而 **`watch` 只在"变化"时才触发**。
第二次进入同一个工作流时，详情往往**已经在 vue-query 缓存里**（`staleTime` 15s，从清单页点回来就是这种情况），
挂载时 `detail` 就已经有值 —— 回调永远不跑，页面于是空着。
修法只有一处：给这条 watch 加 `{ immediate: true }`。

## 问题现场（实测，改动前）

从工作流清单点「设计」进入 `test_workflow`，页面上是空的：

| 页面上看到 | 库里实际是 |
| --- | --- |
| 标题回落成 `test_workflow` | `display_name = 测试工作流` |
| 画布「还是一张空图」 | 4 个节点 |
| 「已有连线 · 0」 | 3 条边 |
| 页头挂着一个「有未保存改动」 | 没有任何改动 |
| 节点配置 / 连线面板全空 | —— |

后端与数据都是好的（排除法做过了）：

```bash
curl -s http://localhost:9090/agent-platform/api/workflows/test_workflow | jq '.spec | {nodes: (.nodes|length), edges: (.edges|length)}'
# { "nodes": 4, "edges": 3 }
```

`md_workflow_run` 里这个工作流确实**没有运行记录**，所以「运行记录 0」是对的，不是同一个 bug。

## 根因

`WorkflowDesignerPage.vue` 里草稿与服务器数据是**两份**（草稿可改、详情只读），靠一条 watch 单向同步：

```ts
// 改动前
watch(detail, (row) => {
  if (!row) return;
  draft.displayName = row.displayName ?? '';
  draft.nodes = (row.spec?.nodes ?? []).map((node) => ({ ...node }));
  ...
});   // ← 没有 immediate
```

`detail` 来自 `useQuery`，key 是 `queryKeys.workflow(route.params.key)`，`staleTime: 15_000`。
于是有两种完全不同的时点：

| 时点 | 挂载时 `detail` | watch 回调 | 页面 |
| --- | --- | --- | --- |
| 冷启动（整页刷新 / 首次访问） | `undefined`，之后变成对象 | 触发一次 | **正常** |
| 热缓存折返（15s 内从清单点回来） | **已经是对象** | 从不触发 | **全空** |

「冷启动对、折返错」是这次排查最花时间的地方：同一个 URL，刷新一下就好了，
看起来像偶发，其实是**有没有缓存**这一个变量在决定。

## 为什么这个坑项目里早就写过

`ModelsPage.vue` 与 `ChannelsPage.vue` 的注释逐字说过同一件事：

> 注意这里不靠 watch(data) 去填草稿：详情可能早已在缓存里（第二次打开同一个模型），
> 那时 data 不会变化，watch 永远不触发，抽屉就空着。所以由 openEdit 主动 await refetch 后填。

那两处是**抽屉**，用「打开时显式 refetch」绕开了；工作流设计器是**路由页**，
没有"打开"这个动作可以挂钩，所以中招了。

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `src/pages/WorkflowDesignerPage.vue` | 给 `watch(detail, ...)` 加 `{ immediate: true }`；整段从「节点编辑」之前挪到 `selectedNodeKey` 声明**之后**（原因见下）；原位置留一段指针注释 |
| `src/pages/SkillsPage.vue` | 同类耦合的**防御性**修复：同一条 watch 补 `{ immediate: true }`（今天还不炸，理由见「关键决策 4」） |
| `src/pages/routes.smoke.test.ts` | `mountRoute` 增加 `seed` 参数（挂载前往查询缓存塞数据）；新增用例「详情已在缓存里时（第二次进入），草稿照样被灌进去」 |

## 关键决策

**1. 为什么是 `immediate: true`，而不是照抄抽屉那套"打开时显式 refetch"**
抽屉有"打开"这个动作可以挂钩，路由页没有 —— 挂载本身就等于"打开"。
而且缓存里那份正是**希望立刻显示**的内容，多打一次请求只是为了把 watch 唤醒，没有意义。
`immediate: true` 表达的就是"挂载即同步一次"，语义比"为了触发 watch 而重取"更准。

**2. 为什么这段代码必须放在 `selectedNodeKey` 声明之后**
回调里要写 `selectedNodeKey.value`，而 `immediate` 的回调是在 **setup 当场**执行的。
放在那个 `ref` 声明之前就是典型的 TDZ：

```
ReferenceError: Cannot access 'selectedNodeKey' before initialization
→ 页面掉进错误边界：「这个页面渲染失败了 … Cannot read properties of undefined (reading 'indexOf')」
```

（`indexOf` 那句来自被压缩的 vue 运行时，不是源码里的 `indexOf`，第一次看到会被带偏。）
反过来把 `immediate` 去掉就能绕开报错 —— 但那就退回本文档要修的这个 bug 了。
所以位置不是排版偏好，是**必需**；`WorkflowDesignerPage.vue` 里留了这段说明，
免得后人"顺手把这段挪回原位"。

**3. 为什么不直接把 `staleTime` 调成 0**
那会打掉缓存的意义（每次进页面都重取），而且**治不了病**：只要详情在挂载时有值，
"靠 watch 发现变化"这条路就是不成立的。这类 bug 的正确修法是让同步逻辑**不依赖"变化"**。

**4. 为什么顺手改了 SkillsPage（今天它其实不炸）**
`SkillsPage.vue` 也是「详情 → 草稿」+ 无 `immediate`，今天靠一条巧合保着：
`watch(drawerOpen)` 在关闭时会把 `selectedName` 置空 → 查询键跟着变 → 再打开时
`detail` 必然从 `undefined` 变成有值 → watch 又跑起来了。
**巧合不是设计**：哪天关闭时不再清 `selectedName`（比如为了保留上次浏览位置），这里立刻退化成空抽屉。
补 `immediate` 之后不再依赖这条耦合，也和 `GatewayPage` / `EnvironmentsPage` /
`AgentDetailPage` / `WorkspaceDetailPage` 四处**已有的**写法对齐。

## 测试与验证

新增用例刻意复刻"详情已在缓存里"这个时点，两个细节都是必需的：

- **预置缓存**：`client.setQueryData(queryKeys.workflow('wf-test'), WORKFLOW_DETAIL)`
  —— 让 `detail` 在挂载时就有值；
- **让后续请求永不返回**：`vi.stubGlobal('fetch', () => new Promise(() => {}))`
  —— 测试环境的 `staleTime` 是 0，预置的那份立刻算过期；不挡住重取的话 data 会换成一个**新对象**、
  watch 又被触发，bug 反而躲过去了。线上之所以中招，正是因为 15s 内那份是"新鲜"的、根本不会重取。

| 层 | 断言 | 结果 |
| --- | --- | --- |
| 类型检查 | `npx vue-tsc --noEmit` | 0 错 |
| 前端全量 | `npx vitest run` | 10 文件 / 144 例通过（含新增 1 例） |
| 反向验证 | 把 `immediate: true` 改回 `false` 再跑该用例 | 按预期**失败**（`expected 'wf-test' to be '冒烟测试工作流'`） |
| 浏览器（冷启动） | 整页刷新 `/workflows/test_workflow` | `h1=测试工作流`，4 节点 / 3 连线 |
| 浏览器（热缓存折返） | SPA 内 清单 →「设计」进入 | `h1=测试工作流`、4 节点 / 3 连线、无「还是一张空图」、无假的「未保存改动」 |
| 浏览器（Skills 抽屉） | 打开 → Escape 关闭 → 再打开同一个 | 正文 736 字符、版本历史都在 |

## 遗留

**1. 抽屉在窄视口下鼠标关不掉。** 抽屉是 `size="820px"` + `:with-header="false"`（没有关闭按钮），
窗口宽度小于约 820px 时它就铺满整个视口、遮罩点不到，只能用键盘 Escape。
本平台按「桌面端、不做移动端改造」定位，1280 以上没问题，所以本次没动；
真要修，建议给抽屉补一个「关闭」动作（按钮或顶部 X），而不是改 `size`。

**2. 同类模式已经全库对齐。** 目前「详情 → 草稿」的同步点：

| 页面 | 方式 |
| --- | --- |
| `ModelsPage` / `ChannelsPage` | 打开时 `await refetch()` 后填（有注释说明） |
| `McpPage` / `PromptsPage` | 直接用列表行填，没有详情请求 |
| `GatewayPage` / `EnvironmentsPage` / `AgentDetailPage` / `WorkspaceDetailPage` | `watch(..., { immediate: true })` |
| `WorkflowDesignerPage` / `SkillsPage` | 本次补齐 |

新增页面如果也走「详情 → 可编辑草稿」，**默认要 `immediate: true`**，
否则会以"偶发空白"的形式回来（冷启动看不出来）。