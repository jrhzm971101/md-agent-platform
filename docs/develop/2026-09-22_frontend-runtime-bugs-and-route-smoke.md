# 前端运行时缺陷复盘 + 全路由冒烟测试（2026-09-22）

> 起因：打开的 Agent 详情页显示「这个页面渲染失败了 ——
> `Failed to execute 'structuredClone' on 'Window': #<Object> could not be cloned.`」。
> 顺着这一次「全量测试」一共挖出 **3 个只在运行时才暴露的缺陷**，
> 并补上一层页面级冒烟测试把它们钉住。

---

## 一、三个缺陷

### 1.1 AgentDetailPage 必然白屏：对响应式代理做 structuredClone

**现象**：`/agents/<name>` 打开即被错误边界接住，整页替换成报错文案。

**根因**：`AgentDetailPage.vue` 的编辑副本是这么造的：

```ts
draft.value = value ? structuredClone(value) : null;
```

而 `value` 来自 `useQuery` 的 `data`。vue-query 把**每一次查询结果**装进了 `reactive()`：

```js
// node_modules/@tanstack/vue-query/build/modern/useBaseQuery.js:29
const state = defaultedOptions.value.shallow
  ? shallowReactive(observer.getCurrentResult())
  : reactive(observer.getCurrentResult());
```

读出来的每个对象都是 Proxy，而结构化克隆算法遇到 Proxy 直接抛 `DataCloneError`
（Chrome / Node 的文案都是 `#<Object> could not be cloned.`）。

**这不是回归，是写在纸上就永远不可能通过的一行** —— 它只在「组件真的跑一次 setup」
时才会被执行，而当时的验证方式是 `vue-tsc` + 纯函数单测 + 构建，三者都不会执行它。

**修法**：新增 `src/lib/clone.ts` 的 `editableCopy()`，先 `toRaw()` 脱掉最外层代理再克隆。

`toRaw` 只脱一层就够 —— Vue 的深层代理是「读属性时才创建」的，而这份数据是 JSON.parse
出来的、从没被写过，内部属性本来就是原始对象。

**顺带修掉的第二处**：保存成功后的 `draft.value = structuredClone(saved)` 有同样的问题
（mutation 的结果同样装在 reactive 里），只是被第一处挡住没机会执行。

### 1.2 ChatPage 的 agent 下拉里两项都是 undefined

**根因**：`features/chat/api.ts` 把 `GET /api/agents` 的响应**直接断言**成
`{name, type}[]`，但控制面重构后这个接口返回的是 `AgentRow`（`agentName` / `agentType`
加引用清单）。两个后果：

- 展开下拉时 `item.type.toLowerCase()` 当场抛错 → 页面白屏；
- 「自动选中第一个 agent」静默失效（`list[0].name` 是 undefined，路由参数被当空值丢掉），
  表现为停在 `/chat` 不进 `/chat/<agentId>`。

之所以一直没被发现：`el-option` 的内容在**下拉第一次展开时**才渲染，
所以只要不点那个框，页面看起来一切正常。

**修法**：在该层做一次映射（`AgentRow → ChatAgent`），并加注释说明「为什么必须映射、
为什么不能直接声明成后端的响应形状」。

### 1.3 一个不认识的事件类型能让整条链路页白屏

**现象**：冒烟测试里 `/traces/<runId>` 报 `Cannot read properties of undefined (reading 'standalone')`。

**根因**：`adapters.ts` 里写的是 `presentEvent(event.type).standalone`，而
`presentEvent` 就是 `eventPresentation[type]` 的直接查表 —— 查不到就是 undefined。

类型系统在这里给了一个**虚假的安全感**：`eventPresentation` 是
`Record<TraceEventType, EventPresentation>`，看起来"查得到一定有条目"；
但 `type` 是 JSON 里的任意字符串，反序列化时被直接断言成联合类型，没有任何校验兜住。
后端加一个 `reasoning.retried` 这种新事件，前端这一刻的代码就会炸 ——
**恰好在最需要看链路的时候打不开链路页**。

**修法**：`presentEvent` 对未知类型给兜底（label 用原始 type、`standalone: true`），
让这条事件在树里真的可见，而不是被悄悄吞掉。

---

## 二、新增的一层测试：全路由冒烟

### 2.1 为什么必须有这一层

上面三个缺陷有一个共同点：**只有「组件真的跑一次 setup、真的渲染一次模板」才会暴露**。
既有的三层验证都挡不住：

| 手段 | 为什么挡不住 |
|---|---|
| `vue-tsc` | `apiJson<T>` 里的 T 只是一次断言，T 写错了它照样通过；`presentEvent` 的返回类型也是"看起来非空" |
| 纯函数单测 | 八个测试文件全是纯函数，根本不碰组件树 |
| `vite build` | 只证明能打包，不证明能运行 |

### 2.2 做法（`src/pages/routes.smoke.test.ts`）

- 把 `router.ts` 里**全部 20 条路由**各挂一次（带参数的路由用真实存在的 id），
  断言：没有掉进 `.crash` 错误边界、顶栏标题正确。
- 夹具**照抄运行中后端的响应形状**（2026-09-22 实测），并且都用 `controlplane/types.ts`
  的接口标注 —— 字段名写错、少写、多写都会被 `vue-tsc` 拦下。
  夹具一旦与契约脱钩，这个测试就从「守门」退化成「骗自己」。
- `fetch` 整个换掉，所以**不依赖后端在跑**（CI 上没有 9090 也能过）。
- 把 Vue 的 `Failed to resolve component` 警告提成断言：DOM 上看不出「组件没导入」
  （那一块只是渲染成空），不这么做的话这类缺陷永远不会变红。
- `vi.setConfig({ testTimeout })` 只放宽本文件：jsdom 里挂一个几百行的表单实测要 3~5 秒，
  而纯函数测试那边超时就是真的出问题了，不该一起放宽。

### 2.3 顺带抓到的东西

`AgentDetailPage.vue` 的模板里用了 `<AppCard>`，但**从来没有 import 过它**。
后果是「读取失败」这一支本该显示的提示卡直接渲染成空白 ——
因为 Vue 只打一条 warn，DOM 上什么都看不出来。

---

## 三、验证

| 项 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx vitest run` | **9 文件 / 98 用例全通过**（新增 29 条，其中 1 条落在 adapters） |
| `npx vite build` | 通过（11.77s） |
| 后端 `GET /api/*`（9 个只读接口） | 全部 200 |
| 真实环境全路由巡检（内置浏览器 + 真实后端） | **24 条路由全部正常**，顶栏标题正确、无崩溃面板、控制台无 error |
| 真实环境交互验证 | Agent 详情（含运行时页的技能策略区）、Harness 新建对话框、Agents 新建向导、对话页 agent 下拉（显示 `test_agent / react`）、对话页自动选中并改 URL 为 `/chat/test_agent` |

---

## 四、遗留

1. **`/api/resources/skills` 与 `/api/resources/mcp` 都是空的** —— 库里 `md_skill_meta` /
   `md_mcp_server` 0 行，Skills 页与 MCP 页只能看到空态。这是**导入器没跑完**的直接后果
   （见 `2026-09-22_agent-page-split-and-type-immutability.md` §10.5），不是前端问题。
2. **导入器仍未实跑**：`LegacyImportRunner.run()` 没有 try-catch，写库时抛
   `ControlPlaneValidationException` 会让 Spring 启动失败。这解释了「后端反复重启 +
   启动 86 秒」，需要单独一轮处理（含 `/actuator/health` 返回 503 DOWN 的复查）。
3. **冒烟测试让前端单测从 3 秒变成 30 秒**。可以接受（换来的是「页面能不能打开」这个
   最基本的保证），但如果以后更慢，可以把「全部路由」压成一条用例内的循环、
   只保留失败时逐条重跑。
4. **没有覆盖到「点下去会怎样」**：冒烟测试只保证页面能渲染，不保证按钮点得对。
   交互层目前靠浏览器里手点（本轮做了 5 处），还没有自动化。
