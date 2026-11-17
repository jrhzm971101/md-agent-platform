# 2026-09-29 渠道热生效漏刷路由表 —— 「Agent 不存在: main」的根因与修复

> 现象：企微发一条消息，回复流已经推了一半，然后
> `ERROR ... MessageRouter : [Router] 处理异常 channel=test_channel platform=WECOM: Agent 不存在: main`
> 疑问是「为什么走的是 main agent」。

---

## 一、结论（先看这一段）

**这句话里的 `main` 不是库里的任何配置，是官方 `ChannelRouter` 构造器里的字面量兜底。**
走到它说明「三层路由漏斗全都没接住」：

| 层级 | 该由谁接住 | 当时的实际状态 | 结果 |
| --- | --- | --- | --- |
| 绑定规则 | `md_channel_binding` 的规则（peer / account / role …） | 库里 **0 行** | 没命中（属正常，本来就该走下一层） |
| 渠道兜底 | 该渠道的 `default_agent_id` | **路由表里查不到这个渠道** → 退回空配置，`defaultAgentId = null` | 没命中 |
| 全局兜底 | `md_gateway.main_agent_id` | **NULL**（两个库都是） | 没命中 |
| 框架兜底 | —— | 官方 `new ChannelRouter(null)` 的常量 `"main"` | 报 `Agent 不存在: main` |

**所以这不是"配错了兜底 agent"，而是"路由表里压根没有这个渠道"。**
证据链：

- 库里 `md_channel` 该渠道 `default_agent_id = workwx_agent`，且 `status = PUBLISHED`（新服务器 v1）；
- `md_channel_binding` 两个库都是 0 行 → 预期路径本来就是走「渠道兜底」；
- `md_gateway.main_agent_id` 两个库都是 NULL → 全局兜底也接不住；
- `AgentGatewayRuntime.warnIfNothingCaught()`（`md-agent-server/.../channel/AgentGatewayRuntime.java:179`）
  打的那条 warn 就是为了解释这件事而写的 —— 它同时给出「渠道兜底 agent=（空）」和「网关主 agent 未设置」。

---

## 二、根因

### 2.1 路由表只有「全量重载」会写

`ChannelRouteTable` 是一份**内存里的只读路由快照**（把库里的渠道配置翻成官方 `ChannelConfig`）。
在本次修复前，**只有 `ChannelRuntime.applyAll()` 会调 `replaceAll(...)` 刷新它**。
而「发布渠道 / 点重载」走的是另一条路：`ChannelController` → `channelRuntime.apply(channelId)`
→ `applyOne(bundle)`，**只装配实例，完全不碰路由表**。

### 2.2 触发条件：服务启动时库里还没有这个渠道

`ChannelRuntime.applyAll()` 在网关重建时（启动 / 网关配置变化）执行一次，此时它按**当时的库内容**建出路由快照。
于是出现这条时间线：

```
服务启动 → 库里还没有 workwx_harness_channel → 全量装配建出一份"空渠道"的路由表
        ↓
页面上新建渠道 → 发布并生效 → WS 长连接真的连上了（能收到消息）
        ↓
message 进来 → 路由表里查不到这个渠道 → 退回空配置 → 一路掉到 main
```

**症状的特征就是：长连接是活的，路由表里却没有这个渠道。**
这也是为什么"有兜底 agent 但还是报错"——兜底 agent 配在库里，路由表根本没读到那一行。

### 2.3 为什么本地/单渠道环境测不出来

单渠道环境里 `applyAll()` 与 `apply()` 的效果看起来一样（都只涉及一个渠道），
**只有当"启动时库里的渠道集合"与"运行期发布过的渠道集合"不一致时才暴露**。
更危险的是：如果当时把单渠道热生效实现成「拼一个单元素列表去调 `replaceAll`」，
在单渠道环境测试全绿，一到多渠道部署就会变成
「发布 A 渠道，B 渠道的消息开始报 Agent 不存在」。这个坑在代码注释里写明了。

---

## 三、修复内容（4 个文件）

| 文件 | 改动 | 为什么 |
| --- | --- | --- |
| `md-agent-server/.../channel/ChannelRouteTable.java` | 新增 `upsert(CachedChannelRoute)`、`remove(String)`；把写入收口到私有的 `swap(Map)` | 单渠道写入必须"读现快照 → 改一条 → 整体换掉"，**不能借道 `replaceAll`**（那会抹掉其它渠道） |
| `md-agent-server/.../channel/ChannelRuntime.java` | `apply(channelId)` 补刷路由表：`bundle == null` → `stopNow + routeTable.remove`；否则 **先 `routeTable.upsert(...)` 再判断启用**。新增 `forget(channelId)` | 让单渠道热生效与全量重载**做同一件事**；`upsert` 放在启用判断之前，是因为"路由是知识、能不能收消息是另一件事"（与 `applyAll` 覆盖全部渠道含已停用同一口径） |
| `md-agent-bootstrap/.../controller/channel/ChannelController.java` | `delete()` 由 `channelRuntime.stopNow(...)` 改为 `channelRuntime.forget(...)` | 删除渠道必须**同时摘掉路由条目**，否则残留在途消息会按一份已删除的配置路由、而通道已停 → "消息静默无回应"，比报错更难查。用 `forget` 而非 `apply`，是因为主动删除是正常操作，不该在状态里留红字 |
| `md-agent-server/src/test/.../channel/ChannelRouteTableTest.java` | 新增 5 个回归用例 | 钉死"写一条不能动其它条"，并反向钉死 `replaceAll` 仍是「以库为准的全量快照」 |

关键代码位置（`Md-Agent-Platform`）：

- `ChannelRouteTable.replaceAll` = `ChannelRouteTable.java:67`，`upsert` = `:87`，`remove` = `:102`，`swap` = `:117`
- `ChannelRuntime.apply` = `ChannelRuntime.java:212`，`forget` = `:245`
- `ChannelController.delete` = `ChannelController.java:205`

### 3.1 路由表写入语义（修复后的唯一口径）

| 场景 | 调用 | 语义 |
| --- | --- | --- |
| 启动 / 网关重建 / 页面「全量重载」 | `replaceAll(list)` | 以库为准的**全量快照**（库里删过的渠道会被刷掉） |
| 发布 / 单渠道「重载」 | `upsert(entry)` | 只改自己那一条，其它渠道原封不动 |
| 删除渠道 | `forget(id)` → `remove(id)` | 幂等地摘掉自己那一条，其它渠道原封不动 |
| 渠道只是被**停用** | `upsert(entry)`（仍写入） | 路由条目保留：在途消息 / 回调重放仍需要它算出"该给谁" |

---

## 四、验证

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 新增单测（本地测试项目） | `mvn -pl md-agent-server -am test -Dtest=ChannelRouteTableTest -Dsurefire.failIfNoSpecifiedTests=false` | Tests run: 5, Failures: 0, Errors: 0 |
| md-agent-server 全量单测 | `mvn -pl md-agent-server -am test` | **Tests run: 366, Failures: 0, Errors: 0 —— BUILD SUCCESS** |
| bootstrap 编译（含改动过的 Controller） | `mvn -pl md-agent-bootstrap -am test-compile` | 通过 |
| 生产项目（`md-admin-platform/agent-platform`） | `mvn -pl agent-platform/md-agent-server -am test -Dtest=ChannelRouteTableTest ...` | Tests run: 5, Failures: 0, Errors: 0 |

新增用例覆盖的行为：

1. 全量装载两个渠道后 `upsert` 一个 → **另一个必须还在**（本次 bug 的核心回归）；
2. 路由表为空时 `upsert` 能新增（不只是覆盖已有）；
3. `replaceAll` 仍是全量快照（避免修 bug 时把全量语义也改坏）；
4. `remove` 只摘自己，且对「重复删除 / null / 不存在的 id」幂等；
5. `all()` 返回副本，调用方无法改坏内部快照。

---

## 五、当前环境该怎么恢复

代码修复只在**重新部署**后生效。在那之前，线上那份路由表还是启动时的旧快照，可用两种方式立即恢复：

| 手段 | 操作 | 说明 |
| --- | --- | --- |
| 全量重载（推荐，免重启） | Channels 页面点「全量重载」，或 `POST /api/channels/reload-all` | 走 `applyAll()` → 重刷路由表 |
| 重启服务 | —— | 启动时 `applyAll()` 同样会重建路由表 |

> 也就是说：**「重启一下就正常了」「点一下全量重载就正常了」是这个 bug 的典型指纹** ——
> 因为它丢的只是内存快照，库里的配置一直是对的。

---

## 六、顺带说明的两个设计口径

1. **`upsert` 早于启用判断是有意的**：路由是"知识"，`enabled` 是"该不该在收消息"。
   渠道停用后仍可能有在途消息（长连接刚断、回调重放），有路由才能算出该给谁、并在链路里显示命中了哪层。
2. **`main` 这个名字从哪来**：官方 `ChannelRouter` 构造器在没给兜底 agent 时用字面量 `"main"`。
   它不是本项目的常量，运行时里不可能存在，所以这句报错的字面信息量是 0 ——
   `warnIfNothingCaught()` 就是为了补上"病因"这半句话。