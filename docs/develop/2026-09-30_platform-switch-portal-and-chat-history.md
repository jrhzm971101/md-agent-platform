# 2026-09-30 平台切换弹窗 / 返回统一门户 / 对话页左侧历史会话

> 三个由控制台使用体验提出的优化点，合在一次改动里：都落在控制台壳（AppShell）与对话页上。
> ① 品牌区加「切换平台」按钮 → 弹窗跳其它子平台；② 顶栏加「返回统一门户」；
> ③ 对话页左侧补「最近会话」，点一条即接着它继续聊。
>
> ③ 附带一个后端新端点（回放数据源），是本轮**唯一**动后端的地方。

---

## 一、结论（先看这一段）

| # | 需求 | 落点 | 数据源 / 机制 |
| --- | --- | --- | --- |
| ① | 切换平台抽屉（**从左侧滑出**） | `src/app/AppShell.vue`（品牌区）+ 新增 `src/app/platforms.ts` | 前端常量目录（与门户 `md-portal-homepage/src/lib/platforms.ts` 逐条对齐），`window.location.assign` 整页跳转 |
| ② | 返回统一门户 | `src/app/AppShell.vue`（顶栏最右） | 同上，`PORTAL_URL` 兜底 `/` |
| ③ | 对话页左侧最近会话 | `src/pages/ChatPage.vue` + 新增 `src/components/ChatSessionList.vue` + `src/features/chat/history.ts` | 清单：`GET /api/sessions?agentId=&limit=50`；点开回放：`GET /api/state/tables` → `GET /api/state/sessions/messages`（**本轮新增**） |

**一句话记住两个数据口径**：左栏清单回答「它被聊过哪些次」（run 表按 `(user_id, session_id)` 聚合，
覆盖页面 / 企微 / 飞书全部入口）；点开后的内容回答「那次对话模型到底看到了什么」（状态表的会话槽位）。
**两者不是同一份数据，也不该合并。**

---

## 二、① 切换平台抽屉（从左侧滑出）

### 2.1 目录为什么写死在前端

服务端有出处：一体化平台的 `md_iam.platform` 表（code / name / home_path / api_prefix / status / sort）。
但 **iam 还没有 `GET /api/platforms`**，所以门户首页也把这份目录写死在前端
（`md-portal-homepage/src/lib/platforms.ts`）。本平台新增的 `src/app/platforms.ts` 与门户那份
**逐条对齐**：同样三条、同样 code（`data` / `agent` / `admin`）、同样的 `VITE_*_URL` 覆盖 + 路径兜底、
同样的卡片文案。

> 两边同时写死可以接受，**两边写得不一样不行** —— 那会让「门户 → Agent 平台 → 数据平台」
> 这条路径出现两个地址，而地址不一致只在点击切换的那一刻暴露。
> iam 出了接口后，两边各自把常量换成一次请求即可，字段名不用改。

### 2.2 跳转为什么必须整页跳转

这三个是**不同的前端应用**，各有自己的 router 与 history。`vue-router` 只认本应用注册过的路由表，
push 一个别的应用的路径会落到本应用的兜底路由 —— 表现是「点了切换平台，页面重渲染一次，位置没变」。
跨应用只有 `window.location.assign` 这一种做法。

### 2.3 位置与形态

- 触发器在**品牌区**（"Agent Platform" 之后），只放格状图标、不放文字：
  品牌区宽度 = 侧栏宽度（`--side-w`，244px），再加一句文字会把 "Agent Platform" 挤成省略号。
- 面板形态是 **`el-drawer` + `direction="ltr"`（从左侧滑出）、宽 360px、整屏高**，
  竖列卡片：两条子平台 + 一条「统一门户」；不含当前平台（`otherPlatforms()` 过滤 `current`）。

> **为什么不居中弹窗**：触发器钉在左上角品牌区，面板就从同一条边上出来 —— 动作和结果挨在一起。
> 居中弹窗与触发器之间隔着半个屏幕（原来那颗按钮在左上角、面板从正中冒出来，视线要横跨一次）。
> 竖列也正好适合抽屉的窄栏：每条都要放一句说明，横排会把说明压成省略号。
- 地址是**相对路径**（`/data/`、`/admin/`）：生产上三个平台与门户同域，是同一 nginx 端口下的不同路径前缀。

---

## 三、② 返回统一门户

- 顶栏最右 `.portbtn`（`HomeFilled` + 文案「门户」）。放顶栏而不是品牌区，理由同 2.3 的宽度约束。
- 门户地址 `PORTAL_URL`：门户不在任何一级路径前缀里（它的 `home_path` 就是 `/`），兜底值也是 `/`。

### `portalIsSelf` 现在的角色：只改文案，不拦点击

| 环境 | `BASE_URL`（vite `base`） | `PORTAL_URL` | `portalIsSelf` | 按钮 |
| --- | --- | --- | --- | --- |
| 本地 dev | `/` | `/`（未配时兜底值） | **true** | 可点；`title` 说明"本部署里门户就是本应用根路径，点了回到概览页" |
| 生产（`.env.deploy`） | `/agent/` | `/` | false | 可点；`title` 是「返回统一门户」 |

**第一版是按 `portalIsSelf` 禁用的**，理由本身成立（本地两者重合，点下去只是回到本应用概览页），
但形态错了：顶栏常驻一颗**永久灰着的**按钮，比"点了之后没跳远"更像坏了 ——
灰是在**渲染前**就宣告此路不通，而通不通取决于部署形态，用户看不到那个条件。
所以改成：**永远可点**，把实际目标写进 `title`，由用户自己判断。
`portalIsSelf` 因此从"开关"降级成"文案分支"；`routes.smoke.test.ts` 里有一条断言
把这个决定钉住（`.portbtn` 不许带 `disabled`）。

> 本地想让这颗按钮真的跳到门户：在 `.env.local` 里设 `VITE_PORTAL_URL`。
> 门户（`md-portal-homepage`）的开发端口是 **5274**，与本控制台的 5273 相邻 ——
> 两个都起时注意别撞在一起（本控制台撞端口会顺延到 5274，正好把门户的位置占了）。

---

## 四、③ 对话页左侧「最近会话」

### 4.1 清单数据源为什么是 `/api/sessions` 而不是状态存储

| 候选 | 覆盖 | 取舍 |
| --- | --- | --- |
| 状态存储的槽位清单 | 只有「还有记忆的」会话 | 不用：记忆可能被清，agent 也可能没开状态存储 → 会漏 |
| `GET /api/sessions`（run 表聚合） | **页面 / 企微 / 飞书全部入口** | ✅ 用它 |

会话记录页用的是同一个接口，只是那边带筛选与分页；左栏固定 `limit=50`、不翻页
（它要的是"最近几条"，找完整历史有会话记录页）。
### 4.2 为什么同时显示 `userId`

同一个 agent 会被很多人聊。只显示 `sessionId` 的话，两条 `single-xxx-2026-09-29` 长得一模一样，
而它们其实是**两个人**的上下文 —— 点错一条就是把话说给了另一个人（下文会续进对方的记忆里）。
这栏里唯一能防住这件事的信息就是那个 `userId`。高亮判定也要求 `userId` 与 `sessionId` **同时**相等
（只看 `sessionId` 会误判）。

### 4.3 点一条会话 → 面板里发生了什么

```
点一条会话
  ├─ ChatSessionList  emit('select', userId, sessionId)
  ├─ useChat.openSession(userId, sessionId)
  │    ├─ 会话键切到它（此后 send 落到同一个 (userId, sessionId) 记忆分区）
  │    └─ history.loadConversation(agentId, userId, sessionId)
  │         ├─ GET /api/state/tables        → 解析这个 agent 的「状态表名」
  │         └─ GET /api/state/sessions/messages?table=&slotId=<userId>:<sessionId>
  └─ 整族失效 queryKeys.sessionsList（清单的次数 / 时间随之刷新）
```

**为什么要先查表名**：表名取 `md_agent_runtime.state_table`，为空时回落 agent 名，所以它
**不等于** agentId（改过名的 agent 就是这个差值）。拿 agentId 当表名去查，结果是一条 404，
而且只在改过名的 agent 上出现。表清单在模块级缓存一次（一次页面停留期间不会变），刷新页面即失效；
**缓存的是表清单、不是消息内容**，内容每次都重拉。

**槽位键的拼法**：`<userId>:<sessionId>` —— 这是框架在 MySQL 状态存储里的分区键。
注意它与 run 表**不同**：run 表把 `user_id` / `session_id` 分两列存
（实测 `session_id` 就是纯 sessionId，如 `console-b4f694f5`），所以**委托时不需要拆串**，
拆串只发生在状态表这边。

### 4.4 竞态守卫（`openSeq`）

连点两条会话时，后一次的请求可能先回来。`useChat` 给每个 agent 记一个自增序号，
**只有序号仍是最新的那次才允许写回结果**（含清 `loadingHistory`）；同时 `send` 在
`loadingHistory` 期间直接返回 —— 否则会把新提问插进还没替换完的旧上下文里。

### 4.5 历史轮次的渲染口径（`toChatTurns`）

| 后端角色 | 处理 |
| --- | --- |
| `USER` | 画成用户气泡 |
| `ASSISTANT` | 画成回答气泡；正文 / 思考 / 工具名分开取，思考**默认折叠** |
| `TOOL` | **丢弃** —— 那是工具结果，不是"模型说的话"，画成气泡会误导 |
| 只有 `tool_use`、正文与思考都空的 `ASSISTANT` | **不画** —— 会是一个空气泡，信息已在工具名里 |

- 工具名挂到 `activity` 一行（"调用过 a、b"），超过 3 个只报个数 ——
  不必为此给领域模型加一个只在历史里用到的字段。
- 历史轮次的 `runId` **留空**：一条 assistant 消息未必等于「一次提问」（可能分多条、还夹着工具调用），
  硬塞一个坐标比留空更糟 —— 点进去会是一条不相干的链路。
- 历史思考的 `thinkingTouched` 置位，是为了让它不再被"自动展开 / 收起"那套规则碰到
  （那些规则只服务于正在流式的那条）。

### 4.6 窄窗口收起

`@media (max-width: 1080px)` 下整栏 `display:none`。它是**辅助导航**而不是主内容：
窄窗口里优先保住对话本身，且 264px 从窄窗口里拿走后正文气泡会被折成很多行。

---

## 五、后端新端点 `GET /api/state/sessions/messages`

| 项 | 值 |
| --- | --- |
| 路径 | `/api/state/sessions/messages`（`StateStoreController`） |
| 参数 | `table`（必填）、`slotId`（必填，`<userId>:<sessionId>`）、`limit`（默认 200） |
| 返回 | `List<Message>`，`Message(role, text, thinking, tools[], timestamp)` |
| 上限 | 条数封顶 `MAX_MESSAGES = 500`；单字段封顶 `MESSAGE_FIELD_LIMIT = 20_000` 字 |
| 错误语义 | 无 `agent_state` 槽位 → **404 NOT_FOUND**；JSON 解析失败 → **400 INVALID**；`context` 缺失 → 空列表 |

**与既有 `/sessions/detail` 的区别是刻意的**：

| | `/sessions/detail` | `/sessions/messages`（新） |
| --- | --- | --- |
| 定位 | 「这个会话现在处在什么状态」 | 「那次对话到底是什么内容」 |
| 形态 | 摘要：最后 20 条、每条截 400 字、正文与思考拼一行 | 原文：正文 / 思考 / 工具名分开、不截 400 字 |
| 前端用途 | 状态存储页的详情抽屉 | 对话页按 Markdown 原样渲染 |

两者形状不同不是重复实现 —— 一个要一眼扫完，一个要能原样渲染。

---

## 六、验证

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 前端类型 | `npx vue-tsc --noEmit` | 退出码 0 |
| 前端单测 | `npx vitest run` | **13 文件 / 175 测试全绿**（改前 11 / 156） |
| 后端单测 | `mvn -o -pl md-agent-server -Dtest=AgentStateAdminServiceTest test` | **24 测试全绿**，BUILD SUCCESS |
| 后端编译 | `mvn -o -pl md-agent-server,md-agent-bootstrap -am -DskipTests compile` | BUILD SUCCESS |
| 视觉（1440x900） | in-app browser 打开 `/chat/test_agent` | 左栏「最近会话」渲染出来；点「切换平台」实测 DOM 为 `.el-drawer.ltr`（top=0、height=900、width=360、`el-dialog` 计数为 0），列出 数据平台 / 行政管理平台 / 统一门户 |
| 视觉·门户按钮 | 同上，读 `.portbtn` | `disabled: false`（改前是 `true`），`title` 为「本部署里门户就是本应用的根路径…」 |

---

## 七、部署注意

1. **后端必须重启**：新端点 `/api/state/sessions/messages` 不重启不生效，
   对话页点历史会话会是一条 404。
2. `.env.deploy` 追加了 4 个平台地址变量（`VITE_PORTAL_URL` / `VITE_DATA_URL` /
   `VITE_ADMIN_URL` / `VITE_AGENT_URL`），**前端要重新 build 才生效**。
   不加也能跑（走相对路径兜底），但换域名 / 端口时就没有覆盖口。
3. 本地 dev 时「门户」按钮**可点，但会回到本应用的概览页** —— 因为本部署里门户地址
   （`VITE_PORTAL_URL`，未配时兜底 `/`）就是本应用自己的根路径（`BASE_URL` = `/`）。
   部署到 `/agent/` 之后两者分开，它就跳到真门户。
   本地想跳真门户：`.env.local` 里设 `VITE_PORTAL_URL` 指向门户地址。
   另外本地也看不到左栏的会话数据 —— 后端没起时 Vite 代理会报 500。
4. 生产仓库（`md-admin-platform/agent-platform`）已同步那 4 个后端业务 / 测试文件，**未动任何配置文件**。
   前端只有 `agent-contro-plane` 一份（一体化平台里没有前端工程），改完重新 build 即可。

---

## 八、改动文件清单

**前端（`agent-contro-plane`）**

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `src/app/platforms.ts` | 新增 | 平台目录 + `PORTAL_URL` / `portalIsSelf` / `otherPlatforms()` |
| `src/app/platforms.test.ts` | 新增 | 5 测试 |
| `src/components/ChatSessionList.vue` | 新增 | 对话页左栏 |
| `src/features/chat/history.ts` | 新增 | 会话回放（表名解析 + 消息映射） |
| `src/features/chat/history.test.ts` | 新增 | 5 测试 |
| `src/app/AppShell.vue` | 改 | 品牌区切换按钮 + **左侧抽屉**；顶栏门户按钮 |
| `src/pages/ChatPage.vue` | 改 | 两栏布局 + 左栏；`hasThread` 口径 |
| `src/features/chat/useChat.ts` | 改 | `loadingHistory` + `openSeq` 竞态守卫 + `openSession` |
| `src/api/state.ts` | 改 | `StateMessage` 类型 + `fetchStateMessages()` |
| `src/lib/queryKeys.ts` | 改 | 抽出 `SESSIONS_LIST` 常量与 `sessionsList` |
| `src/features/chat/useChat.test.ts` | 改 | +4 测试 |
| `src/pages/routes.smoke.test.ts` | 改 | +5 测试（含新接口夹具），并钉住切换面板是 `.el-drawer.ltr` |
| `.env.deploy` | 改 | 追加 4 个平台地址变量 |

**后端（`Md-Agent-Platform`，已同步到 `md-admin-platform/agent-platform`）**

| 文件 | 说明 |
| --- | --- |
| `md-agent-server/.../server/state/AgentStateViews.java` | + `Message` record |
| `md-agent-server/.../server/state/AgentStateAdminService.java` | + `messages()` / `message()` / `collectBlock()` |
| `md-agent-bootstrap/.../controller/state/StateStoreController.java` | + `GET /sessions/messages` |
| `md-agent-server/src/test/.../AgentStateAdminServiceTest.java` | + 5 测试 |