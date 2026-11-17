# 模型进控制面 + 概览页：把「总览」与「模型」两个页面接上真实数据

> 整理时间：2026-09-23。本轮把 `md_model` 立成第六类受治理资源，并新增 `GET /api/overview`。
> 两个原本由前端 mock 撑着的页面（总览、模型）从此只读真接口。
> 后端接口清单见第 7 节；两个页面「怎么改、为什么这么改」见第 8 节。

---

## 0. 结论速览

| 项 | 改造前 | 改造后 |
|---|---|---|
| 「用哪个模型」的唯一真相 | `application.yml` 的 `spring.ai.model.*` → 一个全局单例 Bean `openAIChatModel` | `md_model` 表；Bean 降为**最后一级兜底** |
| 页面上能看到的模型 | 无（模型页是前端 mock） | `GET /api/models`，含打码密钥、引用 agent 数、近 7 日 token/次数 |
| 改模型要做什么 | 改 yml + 重启进程 | 页面改草稿 → 发布 → 内容戳变化 → 自动重建实例（不重启） |
| 模型能不能回滚 | 不能（只能改文件） | 能。走与 agent/prompt/skill/mcp/channel 同一套快照/回滚 |
| 总览页数字 | 前端 mock 写死 | 单端点 `GET /api/overview`，同一时刻快照 |
| 受治理资源种类 | 5 类（AGENT/PROMPT/SKILL/MCP/CHANNEL） | 6 类（+`MODEL`） |
| 库表数 | 15 | 16（`md_model`，`control-plane-schema.sql` 第 11 节） |

两条必须手工执行的 SQL（本轮新增/沿用）：

```bash
# 1) 建表（幂等）
mysql ... md-agent-platform < db/model-schema-upgrade-2026-09-23.sql
# 2) 把当前 yml 里那个模型搬进表（幂等，不执行不会报错，只是模型页空着）
mysql ... md-agent-platform < db/model-seed.sql
```

---

## 1. 现状与问题

### 1.1 改造前

模型这件事在代码里只有三行配置 + 一个 Bean：

```yaml
spring:
  ai:
    model:
      api-key: ${MODEL_API_KEY:sk-...}
      base-url: ${MODEL_BASE_URL:https://api.deepseek.com}
      model-name: ${MODEL_NAME:deepseek-v4-flash}
      reasoning-effort: low
```

```java
// ModelBeanConfig —— 全部 agent 共用这一个实例
@Bean("openAIChatModel")
public Model getOpenAIModel(ModelConfigProperties p) { ... }
```

`ConfigurableAgentBuilder.resolveModel` 当时只有一句 `getBean(ref, Model.class)`。由此产生四个问题：

1. **平台上不可能只有一个模型**：换厂商、加一个便宜的归并模型、给某个 agent 单独指一个长上下文模型，都要改 yml + 重启；
2. **端点与密钥是需要回滚的线上凭据**，却躺在一个纯文本、没有版本、没有审计的文件里；
3. `md_agent_runtime.model_ref` 早就在库里了，但**填的是 Bean 名**，写错只在装配期炸一句「Bean 不存在」；
4. 模型页是前端 mock：页面上那些「成本观测 61% / 412k」全是编的，数字与线上无关。

### 1.2 目标

- 模型成为**第六类受治理资源**，享有与其它五类完全一致的草稿/发布/快照/回滚/审计；
- 存量零成本：升级后不执行任何数据脚本，老 agent 也必须照原样跑；
- 页面上的模型数据**全部来自真实调用**，不再有任何编造的成本/延迟数字；
- 总览页的每个数字都要能回溯到「哪个口径、哪张表」。

---

## 2. 决策记录

| 决策 | 备选 | 为什么选它 |
|---|---|---|
| 模型**进 DB**（方案 B），而不是只加一个只读接口 | 方案 A：页面只读 yml | 只读解决不了「改」和「回滚」。端点/apiKey 是凭据，凭据就该有版本 |
| 保留 `openAIChatModel` Bean 作为**兜底** | 直接删掉 Bean，逼所有引用改库 | 删 Bean 要求「上线前把库里 model_ref 全改完」，而 model_ref 里存的是 Bean 名。保留兜底 = 迁移风险降到 0 |
| 新增**独立 seed 脚本**，DDL 不回填 | 建表脚本里直接 INSERT | 结构变更与数据变更是两次可独立回滚的动作；混一起出问题时说不清是哪一步 |
| 概览用**单端点** | 拆 `/api/overview/agents`、`/models`… | 一屏数字必须同一时刻快照。拆开会出「Agent 14 个、已发布 15 个」 |
| 概览的「最近执行」取**内存注册表** | 取 `md_agent_run` | 前者能点进链路详情（有 runId），后者只有落库后的摘要 |

---

## 3. 类型体系：第六类受治理资源

`ResourceType` 增加 `MODEL`。加完这一处，以下四处 switch 必须同步补齐（漏掉的表现是「能存不能发布」或「删了不报引用」）：`MyBatisControlPlaneStore` 的 publish / rollback / `findReferencingAgents`，以及 `ResourceController` 的资源分派。

**引用落点**（`findReferencingAgents` 对 MODEL 反查三处）：

```
md_agent_runtime.model_ref        ← 主模型
md_agent_runtime.embedding_ref    ← 向量化（预留给 RAG）
md_agent_memory.*_model_ref       ← 记忆归并/压缩用的模型
```

`md_model` 被引用时 `DELETE` 返回 **409 + referencingAgents**，与其它五类一致。

**发布要多做一件事**：重建那些**没有声明 model_ref** 的 agent。它们用的是默认模型，不会出现在任何「按 model_key 反查」的结果里 —— 这是本轮唯一一处「引用反查反不出来」的传播，代码里单独写了注释。

---

## 4. 数据模型：`md_model`

### 4.1 字段分四组

| 组 | 字段 | 说明 |
|---|---|---|
| 身份 | `model_key`(PK) / `display_name` / `purpose` / `provider` | `model_key` 是被 `model_ref` 引用的键；`display_name` 是页面上唯一该露给人看的名字；`purpose`/`provider` 只做筛选，**不进装配** |
| 连接 | `base_url` / `api_key` / `model_name` / `endpoint_path` | `endpoint_path` 空 = 框架默认 `/v1/chat/completions` |
| 生成参数 | `formatter` / `reasoning_effort` / `temperature` / `top_p` / `max_tokens` / `context_window_size` | 全部**三态**：NULL = 不下发，用厂商默认。`temperature=0` 与「没填」是两件事 |
| 治理 | `is_default` / `enabled` / `status` / `version` / `published_version` / `published_at_ms` / `created_*` / `updated_*` / `biz_tags` / `description` | 与其它五类同构 |

### 4.2 两处「照抄 yml 会出事」的地方

做 seed 时最容易抄错，抄错了就是线上故障：

1. **`formatter` 必须写 `deepseek-reasoning`**。yml 里根本没有这一项，但 `ModelBeanConfig` **写死了** `DeepSeekReasoningBackfillFormatter`。DB 行不写这一格 = 悄悄关掉思考模式回填，而 DeepSeek 思考模式不回填 `reasoning_content` 会**直接 400**。
   > 本轮顺手修了一个相关的展示 bug：`ModelAdminService.defaults()` 在「没有 DB 默认模型、走 yml 兜底」那一支把 formatter 显示成 `default`。而它恰恰是排查 400 的关键线索 —— 看到「default」的人不会往 `reasoning_content` 上想。现已改为如实显示 `deepseek-reasoning`，并在 note 里注明「来自 ModelBeanConfig，不是可选项」。

2. **`endpoint_path` 留 NULL**。Bean 没设 `endpointPath`。在库里写死一个路径，换厂商时会变成绊脚石。

### 4.3 `is_default` 的「全表唯一」为什么不进 DDL

要表达的是「最多一行 `is_default = 1`」，但 MySQL 没有部分唯一索引（Postgres 的 `CREATE UNIQUE INDEX ... WHERE`）。两个退而求其次的写法都更差：

- 唯一索引建在 `is_default` 上 → 只允许一行 `is_default = 0`，等于禁止第二行；
- 用 NULL 表示「非默认」→ NULL 在三态语义里是「未设置」，会和版本快照的还原冲突（快照里记的是 0，还原回来是「显式否」）。

所以这条不变量由 `MyBatisControlPlaneStore` **在事务内**维护：写入新默认值前先清掉旧的（`clearDefaultModel`）。

---

## 5. 装配层：`DbModelFactory`

`ConfigurableAgentBuilder.resolveModel` 从 `getBean(ref)` 改为 `modelFactory.resolve(ref)`。解析是**四级**：

```
resolve(modelRef)
  │
  ├─ ref 为空 / 空串 ──▶ 库里的默认模型（is_default=1 AND enabled=1）
  │                        └─ 库里没有 ──▶ 兜底 Bean openAIChatModel（= 改造前行为）
  ├─ ref 命中 md_model.model_key ──▶ 按行构建
  ├─ ref 不是库里的键，但容器里有同名 Bean ──▶ 用它（**存量兼容**）
  └─ 都没有 ──▶ 抛「模型不存在：既不在 md_model 表里，也不是 Spring Bean 名」
```

两个刻意的设计：

- **缓存失效靠内容戳，不靠事件通知**。每个 `OpenAIChatModel` 自带连接池与重试配置，14 个 agent 建 14 份纯属浪费，所以按 `model_key` 缓存。每次解析读一次行（主键查询，代价可忽略），拿 `updated_at_ms` 与缓存里的戳比对，不一致就重建。于是「发布」「保存」天然换掉实例，**不需要谁记得调 invalidate** —— 那种约定迟早漏一处，漏掉的表现是「页面上改了、线上还是旧连接」。`invalidate` 只留给删除场景。
- **引用了停用的模型直接抛，不静默退回默认**。静默回退会让「我停用了一个贵模型」变成账单上一笔没人看得懂的用量。

---

## 6. 概览：一屏一个端点

### 6.1 为什么单端点

一屏上的数字必须来自**同一时刻**。拆成多个端点，前端就要自己做 join，于是「Agent 14 个、已发布 15 个」这种自相矛盾的画面迟早出现。

### 6.2 三个口径（写在代码注释里，页面不许再算一遍）

1. **时间窗口是服务器本地时区的自然日**（与 Cron、日志日报一致）。前端拿本地时区算「今天」是错的：两边时区不同时，页面的「今日」会比后端统计的窗口多一段或少一段。
2. **增量不拼成 "+12%" 字符串**。后端只给 `today.total` 与 `yesterdayTotal` 两个原始数字，口径（今日 vs 昨日、是否含进行中）留在页面上可见，而不是藏在一个后端拼好的百分数里。
3. **健康度是比率不是数量**。条形的长度与标签上的数字必须同义，否则「122 个工具满格」与「3/4 个 MCP 也满格」无法比较。

### 6.3 数据从哪来

| 指标 | 来源 | 理由 |
|---|---|---|
| Agents / 两类型 / 已发布 / 已启用 | `md_agent` 打表 | 要统计口径 |
| 本地工具 | 容器扫 `@Tool` bean | 编译期决定，页面改不了，如实说 |
| MCP 服务 | `md_mcp_server` | — |
| 模型 total/enabled/defaultKey | `md_model` + `defaultModelKey()` | `defaultKey` 为 null 表示走 yml 兜底，页面要如实显示 |
| 今日执行 / 成功率 / 昨日同口径 | `md_agent_run` 打表 | 需要「昨日」这个对比值 |
| 今日平均耗时 / p95 / 最慢 agent | `md_agent_run` | 窗口内无执行时显示「—」（`total === 0`） |
| 今日 token / 缓存命中率 | `md_agent_run` | 命中率是「命中 token / 输入 token」，输入为 0 时是 `0%` |
| **最近执行** | `AgentTraceRegistry` 内存注册表 | 要能点进链路详情（有 runId）；注册表只保最近 200 条 |
| 需要关注 | 由上面几项推导 | 失败执行、近 7 日闲置的 harness |
| 最近变更 | `md_resource_version` 的 recentHeaders | 六类资源的发布动作汇到一处 |

---

## 7. 接口清单

`ModelController`（`/api/models`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/models` | 列表。**密钥打码**（`******`）+ 引用 agent 数 + 近 7 日 token/次数；默认模型另带 `fallbackForAgents`（靠它兜底的 agent 数）|
| GET | `/api/models/defaults` | 「调用参数默认值」卡片：当前生效的兜底是 DB 还是 yml，逐字段列出来 |
| GET | `/api/models/{key}` | 详情。**明文密钥**（用途是回填表单）+ usedByAgents |
| POST | `/api/models` | 新建草稿。`model_key` 重名 → 409 |
| PUT | `/api/models/{key}` | 保存草稿（version +1、状态回 DRAFT）|
| DELETE | `/api/models/{key}` | 被引用 → 409 + referencingAgents；否则连同快照一起删 |
| POST | `/api/models/{key}/publish` | 发布（写快照 + 热装配，含「没声明 model_ref 的 agent」）|
| POST | `/api/models/{key}/rollback` | body `{version}`。只写草稿，不热装配 |
| GET | `/api/models/{key}/versions` | 快照列表 |
| POST | `/api/models/{key}/test` | 连通性：真发 `GET {base_url}/models` |

`OverviewController`：`GET /api/overview`。

**连通性为什么探 `/models` 而不是真跑一次对话**：它是 OpenAI 兼容协议的公共列表接口，不消耗 token、不产生费用、不会被安全策略拦，但同样走「DNS 解析 → TLS 握手 → 鉴权 → 业务响应」四步 —— 正好覆盖配错端点、密钥失效、网络不通三类实际故障。真跑对话反而会引入「模型在思考」这种假失败。失败原因**原样透传**（401 / UnknownHost / ConnectException 对应三种不同修法）。

**密钥为什么整串打码、不做「前 6 后 4」**：部分暴露只对「人肉比对两个 key 是否相同」有帮助，而那件事不该在列表页做；代价却是把密钥的一截写进浏览历史、日志与截屏。

---

## 8. 前端

### 8.1 两个页面

| 页面 | 数据源 | 关键点 |
|---|---|---|
| `src/pages/OverviewPage.vue` | `GET /api/overview` 一次请求 | 7 张指标卡 + 最近执行表（点行进 `/traces/{runId}`）+ 能力资源健康度条形 + 「这一屏的口径」说明卡 + 需要关注 + 最近变更 |
| `src/pages/ModelsPage.vue` | `/api/models`、`/defaults`、`/{key}`、`/{key}/versions` | chips 计数 + 关键字/用途/状态筛选 + 卡片网格 + 编辑抽屉（连通性测试、版本历史、引用它的 agent）+ 「调用参数默认值」PaneCard |

**原页面上写死的「已知坑」「成本观测」（61% / 412k）已彻底删除**，没有任何编造数字残留。

### 8.2 引用类字段一律下拉

`model_ref` 是引用字段，只能从已有模型里选。为此新增 `src/lib/modelOptions.ts`，其中 `withCurrentModel(options, current)` 解决一个容易被忽视的坑：**清单外的当前值必须补进候选**（存量值可能是 Bean 名，或那行模型已被删）—— 否则「打开详情页直接点保存」会把这个字段**静默清空**。

同步改造了 `AgentsPage` / `HarnessPage` / `AgentDetailPage` 里的 5 处自由输入。

### 8.3 契约校验（本轮实测）

把实时 JSON 的字段与前端 TS 接口逐字段比对（后端少给一个字段，页面会静默显示 undefined）：

```
Overview 顶层 / metrics 6 项 / health / attention / changes / runs / generatedAtMs   → 全部已声明
ModelRow（29 字段）/ ModelDefaults / DefaultsItem                                   → 全部已声明
```

---

## 9. 迁移与上线

### 9.1 两步

```bash
mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < db/model-schema-upgrade-2026-09-23.sql
mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < db/model-seed.sql
```

### 9.2 不执行 seed 的后果

**不会报错**。`DbModelFactory` 在库里找不到默认模型时会落回 Bean，agent 照常跑。代价仅仅是模型页上一行都没有 —— 看不到、也改不了当前在用的模型。

### 9.3 seed 的两个设计点

- **状态直接是 `PUBLISHED` / `published_version = 1`**：这个模型改造前就在线上跑着（进程启动即生效）。迁入后若置成 DRAFT，它会因为「未发布」而不参与装配，等于这次重构把线上模型掐了。做迁移数据时，「当前线上的形态」就是已发布形态。
- **幂等靠 `ON DUPLICATE KEY UPDATE model_key = model_key`**（已存在就什么都不做）。不用 `REPLACE`、不用全字段 UPDATE —— 那会在误重跑脚本时把你**在页面上改过的端点/密钥/参数改回去**。

---

## 10. 取舍与已知风险

| 项 | 现状 | 风险 / 下一步 |
|---|---|---|
| `api_key` 明文入库、详情接口回明文 | 与 `md_channel.properties` 同构 | 库被读走等于密钥泄露。要收口就得引入 KMS/加密列，属于独立议题 |
| 图片/向量模型未纳表 | `defaults` 卡片如实标注「来自 application.yml，尚未纳入模型表（RAG 检索在用）」| 不装作已纳管 —— 那会让人以为页面上能改 |
| 近 7 日用量 | 由 `md_agent_run` 的 `model_key` 聚合 | 没记录 `model_key` 的历史执行（改造前的存量）会被归到**当前默认模型**名下 —— 否则「各模型用量加起来比 run 表总量少一截」。没有默认模型时如实不显示，不编一个不存在的模型名 |
| 「最近执行」只保最近 200 条 | 内存注册表 | 概览页要的是 runId，不是台账。要完整台账去「执行链路」页 |
| 概览无缓存 | 每次请求打 4~5 张表的聚合 | 当前量级（2 个 agent、7 次执行/日）完全无压力；真到压力线再加 TTL |

---

## 11. 验证

### 11.1 自动化

| 项 | 结果 |
|---|---|
| `mvn -o test` | **300 通过**（server 277 + bootstrap 23） |
| `mvn -o test -Dtest=MyBatisControlPlaneStoreIT` | **11 通过**（真库集成测试） |
| 前端 `vue-tsc --noEmit` | 0 错 |
| 前端 `vitest run` | **118 例通过** |
| 前端 `vite build` | 通过 |

### 11.2 本轮修掉的三个集成测试错误

这三个是**第一次真正运行 IT** 才暴露的（`mvn test` 的 surefire 默认不含 `*IT.java`，平时不跑）：

| 用例 | 报错 | 成因 | 处理 |
|---|---|---|---|
| `agentBundleWithJsonColumnsSurvivesARoundTrip` | `子 agent 必须是 REACT 类型: itest-cp-child（当前是 HARNESS）` | 夹具 `newAgentBundle` 一律造 HARNESS，而生产校验要求「子 agent 必须是 REACT」（防止编排体套编排体） | 加 `newAgentBundle(name, agentType)` 重载，当子 agent 用的夹具显式建 REACT。**没有放宽生产校验** —— 那条规则是对的 |
| `referencingAgentsAreFoundForEveryResourceKind` | 同上 | 同上 | 同上 |
| `modelRowRoundTripPublishRollbackAndDefaultFlag` | NPE：`getTemperature()` 返回 null | 新增用例断言「草稿温度是 0.7」，但夹具 `newModel` 把温度刻意留成 null（为了验三态）。断言与夹具对不上 | 在用例里显式给 v2 草稿设 `0.7`。这样 v1 快照是 null、v2 草稿是 0.7，回滚时才验得出「null 真的写回去了，不是沿用草稿值」|

> 顺带确认了一件事：`restoreDefaultModels` 写在 `finally` 里，所以即使用例中途失败，开发库的 `is_default` 标记也会被还原。这个用例**必然**会动到开发库里那行真实的默认模型（因为「新默认值上岗 → 清掉旧的」是它要验的语义），所以夹具在跑之前先 `captureDefaultModels()` 存一份、结束时还原。

### 11.3 真库联调实测

```
GET /api/models            → 1 行：ds-flash（PUBLISHED · v1 · 默认 · tokens7d=66118 · runs7d=7）
GET /api/models/defaults   → fallbackSource=md_model、fallbackKey=ds-flash、
                             formatter=deepseek-reasoning
GET /api/models/ds-flash   → 明文密钥 + usedByAgents
GET /api/overview          → agents{total:2,harness:1,react:1,published:2,enabled:2}、
                             tools{local:53}、models{total:1,defaultKey:"ds-flash"}…
```

**本轮发现并修掉的一处观感矛盾**（默认模型那张卡片）：

```
改造前：0 个 agent 在用 · 近 7 日 66k token        ← 自己打自己
改造后：0 个 agent 指定 · 兜底 2 个 · 近 7 日 66k token
```

那 66k 恰恰来自「没写 model_ref」的 agent，而它们**不在**引用反查的结果里（反查找的是「谁写了 `model_ref = X`」）。所以后端在 `ModelRow` 上补了一格 `fallbackForAgents`（复用已有的 `ModelMapper.findAgentsWithoutModelRef()`，只在默认模型上算），页面把「指定」与「兜底」分开说。

前端对「后端还没补上这一格」是容错的（`undefined > 0` 为假，那一小段不渲染），所以前后端可以分别上线，不会出现空白卡片。

页面实测：总览页显示 `Agents 2 / ReAct 1 · Harness 1 · 已发布 2 · 已启用 2`、`本地工具 53`、`模型 1 · 默认 ds-flash · 启用 1`；模型页显示 `DeepSeek V4 Flash` 卡片（默认徽标、`近 7 日 66k token`）—— 均为真实数据。

---

## 12. 缺口与下一步

1. **向量化模型未纳表**（见第 10 节）。
2. **密钥加密存储**（见第 10 节）。
3. **本轮的 Java 改动需要重启后端才生效**：`ModelAdminService` 的 formatter 展示修复与 `fallbackForAgents` 在正在运行的进程里还是旧代码（数据是好的，只是这两格）。