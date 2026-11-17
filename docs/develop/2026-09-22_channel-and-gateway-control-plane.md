# 渠道与网关控制面：Channel / Gateway 从 application.yml 迁入 MySQL

> 记录时间：2026-09-22
> 涉及仓库：后端 `D:\jinran\code\Md-Agent-Platform`（主）+ 前端 `D:\jinran\code\agent-contro-plane`
> 覆盖范围：渠道实例（`md_channel`）/ 路由绑定（`md_channel_binding`）/ 网关（`md_gateway`）的落库、
> 纳入治理（草稿-发布-版本-回滚）、运行时装配、页面维护、官方扩展的接入方式
> 前置文档：`docs/develop/2026-09-21_control-plane-metadata.md`（控制面总设计，第 5 类资源见本文）、
> `docs/develop/2026-09-22_step3-6-assembly-import-api-frontend.md`
> 状态：**已落代码，后端 267 用例 / 前端 107 用例全绿，前端 `vite build` 通过**。
> 数据库脚本**需手工执行**（见第 9 节）。
> 组织方式：结论 → 现状 → 决策 → 类型体系 → 数据模型 → 装配时序 → 官方扩展的真实形态 →
> 接口 → 前端 → 迁移 → 取舍 → 验证 → 缺口

---

## 0. 结论速览

1. **渠道的唯一真相从配置文件变成数据库**。改造前「有哪些机器人」写在
   `application.yml` 的 `agent-platform.messaging.bots[]`；现在写在 `md_channel`，
   yml 里那段已删除（`MessagingProperties` 一并删除）。
2. **渠道是第 5 类受治理资源**（`ResourceType.CHANNEL`），与 agent / prompt / skill / mcp
   共用完全相同的草稿 / 发布 / 版本快照 / 回滚。理由：改错一个 `botId` 会让消息从此进不来，
   这类改动比改提示词更需要一键回滚。
3. **网关不是受治理资源**。它只有「主 agent」一个决策项，是一次装配的输入，没有草稿态，
   因此 `GatewayEntity` 不实现 `GovernedRow`，页面上就是一张表单。
4. **发布 = 落库 + 立刻热生效**，这是渠道与其它四类唯一的流程差异：其它四类发布后等下次装配，
   渠道没有引用方，「生效」的方式就是把自己重新装配一遍。顺序必须是**先落库、后热生效**。
5. **6 个 typeId，两个族**：
   - `BOT` 族：`wecom-bot` / `feishu-bot`（本项目实现的长连接机器人，自带回复渲染）;
   - `OFFICIAL` 族：`wecom` / `feishu` / `dingtalk` / `gitlab`（官方扩展实现，回复走官方 outbound，
     **必须先装配网关**）。
   同一个平台的两套接入方式做成两个 type，而不是一个 mode 开关 —— 凭据集合与回复形态都不同。
6. **官方 channel jar 没有任何 Spring 自动装配**，回调 Controller 必须由我们显式扫描
   （`OfficialChannelWebConfig`）。不这么做的话：装配全成功、页面显示「运行中」，
   但平台回调进来是 **404**，服务端连一行请求日志都没有。这是本次最容易漏的一处，见第 6 节。
7. **官方源码树 ≠ 已发布的 2.0.3 jar**。钉钉在源码里已经支持 http 回调（`mode` / `aesKey` /
   `DingTalkCallbackController`），但 2.0.3 的 jar 里**都没有**。本文所有结论都按 **jar** 核对
   （`javap` 看 record 组件与 `from()` 的常量池），不照抄源码。

---

## 1. 现状与问题

### 1.1 改造前

```yaml
agent-platform:
  messaging:
    bots:
      - name: deployment_agent-bot        # → BotInstanceManager 的注册键
        platform: WECOM
        default-agent: deployment_agent
        wecom: { botId: ..., secret: ... }
      - name: feishu-main
        platform: FEISHU
        default-agent: harness_agent
        feishu: { appId: ..., appSecret: ..., verificationToken: ..., encryptKey: ... }
```

问题：

| 问题 | 后果 |
| --- | --- |
| 改一个凭据要改 yml 并**重启进程** | 企微长连接一断，几十秒内收不到消息；生产上不敢随手改 |
| 没有草稿 / 回滚 | 手滑改错 `botId`，只能靠记忆改回来，且没有任何历史可查 |
| 「消息该交给哪个 agent」是每条 bot 的一个字段 | 无法表达「同一个机器人里，A 群给 agent1、B 群给 agent2」 |
| 没有运行时视图 | 页面上看不出「这个机器人到底连上没有、上次为什么失败」 |
| 只有两种平台 | 加平台要改 yml 结构 + 枚举 + 装配代码 |

### 1.2 目标

- 渠道的**增删改查与启停**全部在页面完成，不重启进程。
- 每个渠道可配**多条路由绑定**，支持「哪个 peer / 账号 / 群 → 哪个 agent」。
- 有一个**运行时状态视图**：启用没有、跑起来没有、上次失败原因是什么。
- 接入官方 4 个平台的通道实现，**不自己重写**协议（AES 解密、签名校验、去重、防回环）。
- 支持**接一个新的 harness agent 后，用同一个 HTTP 入口按 agentId 路由**（网关）。

---

## 2. 决策记录

| 编号 | 决策 | 取舍 |
| --- | --- | --- |
| C1 | 渠道纳入受治理资源（第 5 类），走草稿 / 发布 / 版本 / 回滚 | 比其它资源多一套「发布即热生效」的逻辑；换来线上入口改错可回滚 |
| C2 | **网关不做治理**，只有一张单行表 + 一张表单 | 网关没有草稿态；将来真要多网关（多租户）再单独设计 |
| C3 | 同一个平台的两套接入 = 两个 typeId（`wecom` 与 `wecom-bot`） | 页面类型下拉变长；换来「凭据集合 + 回复形态」不用在一个类型里分叉 |
| C4 | 凭据存 `properties` JSON 列，**不逐字段建列** | 不能按子字段查询/排序；换来「加一个平台」不改 DDL。理由：它在页面上是按类型动态渲染的一组输入框，不是可增删的表格 |
| C5 | 凭据 key 必须与官方 `XxxChannelProperties.from()` **逐字一致** | 写错的表现是「页面上明明填了、装配却说必填缺失」。已逐个用 `javap` 核对（见第 6 节） |
| C6 | **不提供「只停运行时」接口**：停用 = `enabled=0` + 发布 | 少一个按钮；换来「运行状态」永远是持久状态的投影，不会出现「重启后又跑起来了」 |
| C7 | 列表**打码**敏感凭据，详情接口**明文** | 控制面暂无登录，「一半的遮掩」只造安全错觉；且编辑要回填，回传打码值会永久损坏凭据 |
| C8 | 路由求值**原样交给官方 `ChannelRouter`**，自己不写分层匹配 | 需要把库里的绑定翻译成官方 `ChannelConfig`；换来不会与官方七层优先级漂移 |
| C9 | **不做定时轮询**（官方是控制面 + 调度面两个进程，只能轮询） | 绕过控制面直接改库的人需要手点「全量重载」；换来变更即刻生效，也不必设计「轮询间隔内改两次」的合并策略 |
| C10 | 单渠道装配失败**记进状态、不抛异常、不回滚数据库** | 页面要展示失败原因；换来「一个渠道填错不会让其它渠道跟着不工作」 |
| C11 | 用**内容指纹**决定要不要重启通道 | 指纹序列化失败时退化成「每次都重启」（安全方向）；换来「改 A 渠道的绑定不会把 B 渠道的长连接掐断」 |
| C12 | 官方回调 Controller 由 `OfficialChannelWebConfig` 显式扫描 | 多一个类；换来官方通道真的能收到消息（见第 6 节） |

---

## 3. 类型体系

`ChannelKind` 只有两个值，**分族看的不是网络形态，而是「回复怎么发出去」**：

| 族 | 取值 | 回复路径 | 是否需要网关 | 类型 |
| --- | --- | --- | --- | --- |
| `BOT` | `wecom-bot` / `feishu-bot` | 本项目发送侧（`MessageRouter` + `*BotInstance`），回复形态由 `ReplyStyle` 决定 | 不需要 | 本项目实现 |
| `OFFICIAL` | `wecom` / `feishu` / `dingtalk` / `gitlab` | 官方 `Channel.dispatch()` → `Gateway.run()` → 官方 outbound 直发 | **需要** | 官方扩展实现 |

> 钉钉是 `OFFICIAL` 却不需要公网回调地址（2.0.3 只有 stream 反向连接），
> 容易被误当成 `BOT`。判据是上表第三列：它的回复走官方 outbound，所以必须有网关。

`ReplyStyle`（`md_channel.reply_style`，本项目扩展列，官方没有）只有两个值
`WECOM_THINK` / `FEISHU_CARD`，空值按平台推断。它属于**渠道**而不属于 agent ——
同一个 agent 从企微进来和从飞书进来，可以长得不一样。

### 3.1 行为参数按类型声明（`capabilities`）

`dm_scope` 与 `reply_style` 是渠道表上的全局列，但**并非每个类型都会读它们**。
所以由每个类型自己声明支持情况（`ChannelCapability`，随 `GET /api/channels/types` 返回），
页面按它决定渲不渲染控件 —— 摆一个"填了不生效"的下拉，比不摆更糟。

| 类型 | `dm_scope` | `reply_style` | 依据 |
| --- | --- | --- | --- |
| `wecom` | 四种 | — | `WeComInboundMapper` 恒为 `PeerKind.DIRECT`，dm_scope 是这里唯一能分上下文的开关 |
| `feishu` | 四种 | — | 入站分 `DIRECT` / `GROUP`，dm_scope 只作用于单聊那支 |
| `dingtalk` | 四种 | — | 同上 |
| `gitlab` | — | — | `GitLabInboundMapper` 只产出 `PeerKind.THREAD`，而 dm_scope 只在 `DIRECT` 分支被读 |
| `wecom-bot` | — | `WECOM_THINK` | 会话 id 由 `WeComThinkRenderer#resolveSessionId` 自己算（群按群、单聊按人） |
| `feishu-bot` | — | `FEISHU_CARD` | 同上（`FeishuCardRenderer` 拿 userId 当 sessionId） |

两条规律：`OFFICIAL` 族的 `reply_style` 整列不适用（回复由官方 outbound 直发，
不经过本项目的 `ReplyRendererRegistry`）；`BOT` 族的 `dm_scope` 整列不适用
（不经过官方 `ChannelRouter`，`MessageRouter` 只从路由结论里取 `agentId` / `matchedBy`，
`sessionKey` 仅进日志）。

读 2.0.3 字节码核对出来的两个官方细节：

- `PER_PEER` 与 `PER_CHANNEL_PEER` 在 2.0.3 里**行为完全相同**：
  `ChannelRouter.buildDmContext` 两个 case 都是 `new MsgContext(channel, null, peerId, null, null, extra)`，
  而 `channel` 本来就参与 `canonicalKey()` —— "把渠道名并进 key"的区分度是零，选哪个都一样。
- `MAIN` 是官方默认值，语义是"该 agent 的所有单聊共用一个会话"，多用户部署必须显式换掉。

新增一个平台要改的地方（这就是工厂模式要解决的问题）：

1. `md-agent-server/pom.xml` 加依赖；
2. `server/channel/official/` 加一个 `ChannelAdapterFactory`
   （自带类型描述 + 凭据字段清单 + 行为参数支持情况）；
3. 如果它有回调 Controller，**在 `OfficialChannelWebConfig` 里补上**（见第 6 节）；
4. 前端不用改（类型列表来自 `GET /api/channels/types`）。

---

## 4. 数据模型

三张表，DDL 见 `md-agent-bootstrap/src/main/resources/db/channel-schema.sql`（含逐列注释）。

| 表 | 关系 | 说明 |
| --- | --- | --- |
| `md_channel` | 主表，PK = `channel_id` | 一行 = 一个消息入口。`properties` 存类型特有凭据（JSON）；`default_agent_id` 是兜底 agent；`dm_scope` 沿用官方 `DmScope` 枚举名 |
| `md_channel_binding` | 一对多，`channel_id` 外键（应用层保证） | 一行 = 一条「什么消息交给哪个 agent」，字段与官方 `ChannelBinding` 逐一对应 |
| `md_gateway` | **单行表**，PK = `id` 固定为 1 | 只有 `main_agent_id` + 备注。由脚本预置该行，运行时不做「读不到就插入」的隐式兜底 |

### 4.1 两条与主控制面一致的约定

1. JSON 列必须配 `@TableName(autoResultMap = true)` + `JacksonTypeHandler`；
2. 三个治理状态列不可为 `null`，历史数据缺值时按「启用 / 从未发布」处理 ——
   **静默停掉一个用户以为在用的渠道，比多起一个实例更难发现**。

### 4.2 为什么 `md_channel_binding` 要逐字段建列，而 `properties` 不建

判据是「有没有按子字段查询/排序的需求」：

- `properties` 是**一组固定输入框**（按类型渲染），没有按 `botId` 检索的场景 → JSON；
- `binding` 要按 `sort_order` 排序、按 `channel_id` 过滤、将来可能要按 `agent_id` 反查 → 建列。

### 4.3 版本快照

渠道复用 `md_resource_version`（`resource_type = 'CHANNEL'`，`resource_name = channel_id`）。
`md_resource_version` 的 DDL 与语义见 `2026-09-21_control-plane-metadata.md`，这里不重复。

---

## 5. 运行时装配

### 5.1 数据流

```
md_channel / md_channel_binding / md_gateway     （唯一真相，页面维护）
     │
     │  ChannelRuntime.applyAll()  ← 启动时 / 点「全量重载」/ 保存网关 / 发布渠道
     ▼
AgentGatewayRuntime.reload()  ──► GatewayBootstrap（主 agent + 纳管 harness agent）
     │                                │
     │                                └─► Gateway（回调式通道用它跑 agent）
     ▼
ChannelValidator.problems()  +  gatewayBlockReason()      ← 凭据齐全？类型已注册？网关装了？
     │
     ├─► ChannelRouteTable.replaceAll(...)   路由快照（消息进来时算 agentId）
     │
     └─► 逐个 applyOne(bundle)
             │  内容指纹没变且实例活着 → 跳过（不重启长连接）
             │
             ├─ ChannelTypeRegistry.create(channelId, type, routing, properties)
             │      ├─ BOT 族     → BotChannelAdapter（构造即 new *BotInstance）
             │      └─ OFFICIAL 族 → OfficialChannelAdapter
             │
             ├─ OFFICIAL：official.bindGateway(gateway)
             └─ adapter.start()
                    ├─ BOT：建立 WS 长连接 + 注册进 BotInstanceManager
                    └─ OFFICIAL：注册进官方静态 Registry（**回调 Controller 靠它找实例**）
```

### 5.2 报文的两个方向

| 方向 | BOT 族 | OFFICIAL 族 |
| --- | --- | --- |
| 收（平台 → 我们） | WS 线程推给 `InboundMessageHandler`（= `MessageRouter`） | 平台 HTTP 回调 → 官方 `*CallbackController` → 官方 Registry → `Channel.dispatch()` |
| 算（选 agent） | `ChannelRouteTable.route(channelId, msg)` | 官方 `ChannelRouter.resolveRoute(config, msg)` |
| 发（我们 → 平台） | `ReplyRenderer` + `BotInstance` 发送 | 官方 outbound 直发（我们不参与） |

`ChannelRouteTable` 是**解依赖环的隔离层**，环是：
`ChannelRuntime → BotInstanceManager → MessageRouter → ChannelRuntime`。

### 5.3 网关重建后必须重启官方通道

官方 `Channel.init(gateway)` 只在 `gateway == null` 时生效（`if (this.gateway == null)`），
而通道是**靠持有的 gateway 去查 agent 注册表**的。所以网关一旦重建，
活着的官方通道会继续指向**旧注册表** —— 现象是「改了主 agent / 停用了某个 agent 之后，
回调式通道仍按旧注册表路由」。

`applyAll()` 因此持有一个 `boundGateway` 引用标识，发现网关实例换了就把所有
`OfficialChannelAdapter` 先 `stopNow` 再重建。回调式通道的 stop/start 只是注册表增删
（没有网络连接），重建代价可以忽略 —— 这与 BOT 族长连接的取舍相反。

### 5.4 发布 = 落库 + 立刻热生效

```
POST /api/channels/{id}/publish
  → ChannelValidator 校验
  → store.publish(CHANNEL, id, ...)      ← 先落库（版本快照也在这里）
  → channelRuntime.apply(id)             ← 后热生效
  → 返回 ChannelView（含运行时状态）
```

顺序不能反：先热生效再落库的话，落库失败就会留下「跑着但库里没有」的实例，
而页面上看不到它（页面以库为骨架），只能靠重启进程收敛。

---

## 6. 官方扩展的真实形态（本节是本次最容易踩坑的地方）

### 6.1 官方 jar 没有任何 Spring 自动装配

逐个开包确认过：本地仓库里 2.0.3 的全部 agentscope jar **都没有**
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，
也没有 `spring.factories`。官方 channel 包里唯一的 Spring 注解是 5 个
`@RestController`（wecom / feishu / dingtalk / github / gitlab 各一个），
没有任何 `@Component` / `@Configuration` / `@Bean`。

**而已发布的 2.0.3 里钉钉没有那个 Controller**（只有 `DingTalkStreamClient` 等 7 个类）。

而本应用的组件扫描根是 `com.md.agentplatform`（`@SpringBootApplication` 的默认值），
扫不到 `io.agentscope.extensions.channel.*`。于是：

| | 结果 |
| --- | --- |
| 渠道装配 / `start()` / 注册进官方 Registry | **全部成功**，页面显示「运行中」 |
| 平台回调 `POST /api/channels/wecom/{channelId}/callback` | **404** |

这个组合最难排查：控制面一切正常，只有「用户发消息机器人不回」，
服务端连一行请求日志都没有（请求根本没进 `DispatcherHandler`）。

**解法**：`md-agent-bootstrap/.../config/OfficialChannelWebConfig.java` ——
用 `@ComponentScan(basePackageClasses = {...})` 把三个官方 Controller 挂上。
用 `basePackageClasses` 而不是字符串包名，是为了让「依赖被删掉」在**编译期**就报错。

由 `OfficialChannelWebConfigTest` 钉死：断言这三个 Controller 是 Bean、
且路径前缀仍是 `/api/channels/`。它不启整个容器（不连 MySQL / 网关 / 模型），
所以能在 CI 里稳定跑。

### 6.2 已核对：2.0.3 jar 里各类型真正会读的凭据 key

用 `javap` 看 record 组件与 `from()` 的常量池（不是抄源码）：

| typeId | 官方类 | `from()` 读的 key |
| --- | --- | --- |
| `wecom` | `WeComChannelProperties` | `corpId` `agentId` `secret` `token` `encodingAesKey` `callbackPath` `apiBase` |
| `feishu` | `FeishuChannelProperties` | `appId` `appSecret` `encryptKey` `verificationToken` `callbackPath` `apiBase` |
| `dingtalk` | `DingTalkChannelProperties` | `appKey` `appSecret` `robotCode` `apiBase` `oapiBase` `streamRegisterUrl` |
| `gitlab` | `GitLabChannelProperties` | `token` `webhookToken` `apiBase` `webhookPath` |

**上表的 dingtalk 一行与上游源码不同**：源码里的 record 有 8 个组件
（多了 `mode` / `aesKey`，用于新增的 http 回调模式），2.0.3 的 jar 只有 6 个。
本项目的 `DingTalkChannelFactory` 因此**只声明这 6 个** —— 多放一个 `mode` 输入框的结果是
「页面上填了、装配时完全没人读」，比缺字段更难发现。

> 教训：核对外部依赖的接口时，**以 jar 为准**。本地 `agentscope-java-main` 源码树
> 是上游 main 分支，领先于已发布版本。

### 6.3 官方回调路径（路径空间与自研控制面重叠）

| 类型 | 路径 |
| --- | --- |
| wecom | `GET|POST /api/channels/wecom/{channelId}/callback` |
| feishu | `POST /api/channels/feishu/{channelId}/callback` |
| gitlab | `POST /api/channels/gitlab/{channelId}/webhook` |

自研 `ChannelController` 挂在 `/api/channels` 上。二者目前不冲突（官方路径 5 段、自研 2~3 段），
但**渠道名不能叫** `wecom` / `feishu` / `gitlab` / `types` / `reload-all`，
否则 `GET /api/channels/{id}` 会与官方前缀或自研固定路径撞车。

另外：这些路径在「渠道没启用 / 装配失败」时也返回 404（找不到实例），
日志里会有 `no channel registered for id=...`。**排查 404 时先看这行日志**，
再怀疑 Bean 有没有挂上。

### 6.4 平台后台要填的回调地址

因为官方 Controller 按路径里的 `channelId` 找实例，**最后一段必须等于渠道名**：

```
https://<公网域名>/api/channels/wecom/<channelId>/callback
https://<公网域名>/api/channels/feishu/<channelId>/callback
https://<公网域名>/api/channels/gitlab/<channelId>/webhook
```

只有反向代理改了前缀时才需要填 `callbackPath` / `webhookPath` 覆盖。

---

## 7. 接口清单

### 7.1 渠道 `ChannelController`（`/api/channels`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/channels` | 列表，含运行时状态；敏感凭据打码 `******` |
| GET | `/api/channels/types` | 已注册的类型 + 凭据字段清单 + 行为参数支持情况（页面据此动态渲染表单） |
| GET | `/api/channels/{id}` | 详情，**凭据明文**（编辑要回填） |
| GET | `/api/channels/{id}/versions` | 版本历史 |
| POST | `/api/channels` | 新建（草稿） |
| PUT | `/api/channels/{id}` | 保存（草稿）；路径上的 id 是权威 |
| DELETE | `/api/channels/{id}` | 删除：先删库、再 `stopNow` 摘掉运行实例 |
| POST | `/api/channels/{id}/publish` | 校验 → 落库（含快照）→ 热生效 → 返回 `ChannelView` |
| POST | `/api/channels/{id}/rollback` | 回滚到指定版本；**不热生效**（要再点重载/发布） |
| POST | `/api/channels/{id}/reload` | 重新装配单个渠道 |
| POST | `/api/channels/reload-all` | 全量重载：重建网关 + 路由表 + 按需重启全部通道，返回 `{gateway, channels}` |

**故意没有**「只停运行时」接口：停用 = `enabled=0` + 发布（决策 C6）。

### 7.2 网关 `GatewayController`（`/api/gateway`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/gateway` | 当前配置 + 运行时状态 + 主 agent 候选列表 |
| PUT | `/api/gateway` | 保存并 `applyAll()`；**未装配不算保存失败，但响应里必须体现** |
| POST | `/api/gateway/reload` | 只重新装配网关 |

> 「未装配不算失败」这条很关键：主 agent 可能填了一个还没发布的 agent，
> 那时配置本身是合法的，只是装配不起来。若把它当失败返回，用户会以为没保存上。

### 7.3 与资源控制面的关系

`ResourceController` 的 `exists()` 已补 `case CHANNEL`，
因此渠道也能走通用的「引用检查 / 版本列表」路径。

---

## 8. 前端

| 文件 | 内容 |
| --- | --- |
| `src/pages/ChannelsPage.vue` | 渠道列表 + 880px 编辑抽屉（基础信息 / 动态凭据表单 / 路由绑定规则表 / 版本历史+回滚） |
| `src/pages/GatewayPage.vue` | 装配状态 + 主 agent 表单 + 纳管 agent 徽标列表 |
| `src/api/controlplane/index.ts` | `listChannels` / `listChannelTypes` / `getChannel` / `createChannel` / `saveChannel` / `deleteChannel` / `publishChannel` / `rollbackChannel` / `channelVersions` / `reloadChannel` / `reloadAllChannels` / `getGateway` / `saveGateway` / `reloadGateway` |
| `src/app/nav.ts` | 「接入」组：Channels → Gateway → Endpoints（顺序 = 排查顺序：先看入口、再看落点） |

四个容易踩的前端坑（已在代码注释里写明）：

1. **绑定规则的两栏映射**：页面用「条件类型 + 值」，库里有七个层级字段。
   `toRule` / `toBinding` 双向映射必须成对维护；
2. **不要用 `structuredClone` 复制后端对象**：拿到的是 Vue 的 `Proxy`，会抛 `DataCloneError`。
   `toDraft()` 手工映射；
3. **打开编辑抽屉要先 `await refetch()` 再填草稿，不能用 `watch(data)`**：
   详情走 query 缓存，命中缓存时 `data` 不变，`watch` 不触发，抽屉会空着；
4. **类型切换用 `@change="clearProperties"`，不能用 `watch`**：`watch` 会把刚填入的凭据清掉。

---

## 9. 迁移与上线步骤

### 9.1 必须手工执行的两个 SQL

```bash
# 1) 建表（三张表 + 预置 md_gateway 的单行）
mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < channel-schema.sql

# 2) 把原来的两个机器人搬进 md_channel
mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < channel-seed.sql
```

两个脚本都在 `md-agent-bootstrap/src/main/resources/db/` 下。

### 9.2 不执行 seed 的后果

**原来那两个机器人不会启动**，页面上表现为「渠道列表是空的」。

### 9.3 seed 脚本的两个设计点

1. 状态直接置 `PUBLISHED / published_version = 1`：这两个机器人在改造前就在线上跑着，
   置成 `DRAFT` 会因为「未发布」而不装配 —— 那等于这次重构把线上入口掐了。
   做迁移数据时，「当前线上的形态」就是已发布形态。
2. 幂等用 `ON DUPLICATE KEY UPDATE channel_id = channel_id`（已存在就什么都不做），
   **不是**全字段 `UPDATE`：后者会在误重跑脚本时把你页面上改过的凭据改回去。

### 9.4 已废弃的东西

| 位置 | 状态 |
| --- | --- |
| `application.yml` 的 `agent-platform.messaging.bots[]` | **已删除**，原地留了两行说明注释 |
| `messaging/config/MessagingProperties.java` | **已删除** |
| `application.yml` 的 `agent-platform.messaging.wecom-webhook` | **保留**（`WeComWebhookProperties` 仍在用，与渠道无关） |
| `messaging/config/WeComBotCredentials`、`FeishuBotCredentials` | 新建，替代原 `MessagingProperties` 内部的凭据 record |
| `BotInstanceManager` | 重写为**纯注册中心**（去掉 `@PostConstruct`/`@PreDestroy` 与 yml 依赖） |

---

## 10. 取舍与已知风险

| 项 | 说明 |
| --- | --- |
| 列表打码、详情明文 | 控制面暂无登录。等接入登录后应改为「详情也需要权限」而不是「列表打码」 |
| 敏感凭据以明文存 MySQL | 未做字段级加密。当前依赖「库的访问权限」这一层保护 |
| 绕过控制面改库要手点重载 | 决策 C9 的代价。页面上有「全量重载」按钮 |
| 单进程假设 | 决策 C9 的另一面：本方案假设「控制面 + 运行时同一个进程」。若将来拆成两个进程，需要引入官方那套轮询/推送 |
| `md_gateway` 单行表 | 多租户多网关时需要重新设计（加 tenant 列或另起表） |
| 官方 Registry 是静态单例 | 同一进程内 `channelId` 全局唯一；这也意味着多实例部署时，「start 的进程才收得到回调」——需要外部负载均衡把回调打到正确的实例 |

---

## 11. 验证

### 11.1 自动化

| 范围 | 结果 |
| --- | --- |
| 后端 `mvn -o test` | **267 用例全绿**（md-agent-server 247 + md-agent-bootstrap 20） |
| 前端 `npx vue-tsc --noEmit` | 通过 |
| 前端 `npx vitest run` | 9 文件 / **107 用例全绿**（含 `/gateway` 路由冒烟、渠道与网关的路由契约） |
| 前端 `npx vite build` | 通过（`ChannelsPage` 21.5 kB、`GatewayPage` 5.2 kB，均已分包） |

本次新增/更新的用例：

- `OfficialChannelWebConfigTest`（新）：官方 3 个回调 Controller 是 Bean + 路径前缀断言；
- `contract.test.ts`（前端）：URL 编码、`changeNote` 载荷、`reload-all` 路径等 6 条；
- `routes.smoke.test.ts`（前端）：新增 `/gateway` 夹具与冒烟用例。

### 11.2 联调清单（服务由使用者自己启动）

```
GET  /api/channels/types      → 应返回 6 个类型，凭据字段清单与第 6.2 节一致，
                                dmScope / replyStyle 的支持情况与第 3.1 节一致
GET  /api/channels            → 执行完 seed 后应有 2 条（wecom-bot / feishu-bot）
GET  /api/gateway             → 应返回单行配置 + 运行时状态
页面 /channels                → 列表有数据、编辑抽屉能回填、发布后状态变化
页面 /gateway                 → 保存主 agent 后装配状态跟着变
```

---

## 12. 缺口与下一步

1. **回调公网可达性**：`wecom` / `feishu` / `gitlab` 三个 OFFICIAL 类型需要公网回调地址，
   本地开发需要内网穿透才能真跑通「平台发消息 → agent 回复」的闭环；
   `dingtalk`（stream）与两个 BOT 类型不受此限制。
2. **`ChannelValidator.gatewayBlockReason` 对 OFFICIAL 类型要求网关已装配**：
   只配了 BOT 渠道时行为与改造前一致；配 OFFICIAL 类型前必须先在 `/gateway` 设置主 agent。
3. **敏感凭据加密**：见第 10 节。
4. **多实例部署下的回调路由**：官方 Registry 是进程内静态单例，多实例时需要保证
   回调打到「跑着这个渠道的实例」上（负载均衡策略或共享路由层）。
5. **`md_resource_version` 里渠道快照的清理**：与其它四类共用一张表，量大时需按
   `resource_type` 分区/清理（继承 `2026-09-21_control-plane-metadata.md` 的第 D9 条决策）。
