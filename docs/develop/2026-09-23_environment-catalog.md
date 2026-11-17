# 环境目录落库（application.yml → md_environment 六张表）

> 日期：2026-09-23
> 范围：`md-agent-server` / `md-agent-bootstrap` / `agent-contro-plane`
> 决策来源：用户 2026-09-23 明确三点 ——
> ① 分组模板**页面可维护**；② 密钥**暂不加密、调试期明文**；③ 环境之间**不继承**。
> 2026-11-03 补充：④ 一个分组**可以定义多套实例**（如两套 NineData / Nacos），所有环境共享实例元数据；
> ⑤ 环境主表的 `country` / `stage` 字段删除，前后端与数据库一起清理；
> ⑥ 实例在页面上**独立维护**；环境只选择绑定哪些实例。
> 2026-11-05 补充：⑦ `application.yml` 环境源已彻底移除；空库导入和数据库故障兜底两条路径都删除，
> 环境信息**只从数据库读取**，库不可用时明确报错。
> 2026-11-07 补充：⑧ 配置值收敛到**实例本身**（`md_env_instance_config`）。环境不再保存值，
> 实例编辑弹窗也不再要求先选“配置环境”；需要不同配置时新建实例。

---

## 一、目标与非目标

**目标**：把「环境」从 `application.yml` 的 `environments.environmentMap` 搬进 MySQL，
并让它**在页面上可增删改查**——不只是改值，还包括**改结构**：

1. 加一个业务分组（结算周期、默认仓库、渠道白名单…）**不用改 Java 代码**；
2. 分组里加一个字段、改标签、调必填，也在页面上完成；
3. 环境本身（有哪些环境）同样在页面上维护，不再需要重启改 yml。

**非目标**：不改工具代码的调用姿势。十几个工具仍在写
`environmentRegistry.k8s(env).getUrl()`，本次改造**一行没动它们**。

---

## 二、为什么要改（原设计的三个疼点）

| 疼点 | 具体表现 |
| --- | --- |
| 结构写死在配置里 | 想加一个「结算周期」分组，要改 yml、改 `EnvironmentProperties` POJO、改强类型访问器、重启 |
| 环境清单不可运维 | 加 `cameroon-pre` 这种环境要动 yml + 重新发版；谁能改、改了什么，没有地方记录 |
| 缺失项不可见 | yml 里 `${CAMEROON_TEST_K8S_TOKEN:}` 是个空占位，跑起来才在工具里报错；页面上看不到"这个环境缺 Token" |

对照实物：`cameroon-test` 的 k8s token、rabbitmq 两条、xxljob 密码、openobserve 三条
在 yml 里本来就是空占位，这些**真实存在的缺口**以前只能靠人肉比对。

---

## 三、数据模型

六张表，`md-agent-bootstrap/src/main/resources/db/env-schema.sql`：

| 表 | 职责 | 关键列 |
| --- | --- | --- |
| `md_environment` | 环境主体（有哪些环境） | `env_code`(PK) / `display_name` / `enabled` / `sort_order` |
| `md_env_group` | 分组模板（有哪些分组） | `group_code`(PK) / `display_name` / `icon` / `builtin` / `sort_order` |
| `md_env_group_key` | 键声明（每组有哪些字段） | `group_code`+`key_code`(PK) / `label` / `value_type` / `required` / `secret` / `placeholder` / `example_value` / `description` |
| `md_env_group_instance` | 分组实例元数据（一组有几套） | `group_code`+`instance_code`(PK) / `display_name` / `enabled` / `sort_order` |
| `md_env_instance_config` | 实例配置（EAV） | `group_code`+`instance_code`+`key_code`(UK) / `config_value` |
| `md_env_instance_binding` | 环境与实例的绑定（当前环境使用哪些实例） | `env_code`+`group_code`+`instance_code`(PK) |

> 2026-11-07：旧表 `md_env_config` 保留为只读备份，业务读写已切到
> `md_env_instance_config`。存量库执行同目录的
> `env-schema-upgrade-2026-11-07-instance-config.sql`。

> 2026-11-03：环境主表的 `country` / `stage` 已移除。存量库执行同目录的
> `env-schema-upgrade-2026-11-03-remove-country-stage.sql`。

### 分组多实例（2026-11-03）

一个分组可以定义多套实例，例如两套 NineData、两套 Nacos。实例是**分组级全局数据**，
包含编码、展示名、启用、排序以及它自己的固定配置值；所有绑定它的环境读到的是同一份值：

```
分组 group_code
  ├─ 实例 default   → 固定 key/value（环境 A、环境 B 都绑定它，就都读到这一份）
  └─ 实例 report    → 固定 key/value（需要另一套地址时新建它，再让环境选择）
```

环境只“选择”实例，不保存任何值。环境与实例的选择关系由 `md_env_instance_binding` 维护；
解绑只删除当前环境的绑定，不删除实例定义，也不删除实例配置。
实例表里也只展示
分组 / 实例编码 / 展示名 / 启用这些基本信息，不展示环境或配置值统计。

实例清单只以 `md_env_group_instance` 为唯一来源。`md_env_instance_config` 里若残留了未声明的
`instance_code`，不会反向“合成”实例元数据，也不会出现在维护区或下拉框中；这类行属于
脏数据，应补齐实例声明或清理。

解析规则固定在 `EnvironmentCatalog`，工具调用方不需要感知实例：

1. 显式传了 `instance` → 必须是当前环境已绑定的实例，否则报错并列出已绑定实例；
2. 没传 → 优先 `default`（前提是它已绑定）；
3. 没有 `default` 但只有一个已绑定实例 → 自动使用它；
4. 有多个实例、没有 `default`、又没显式指定 → 报错并列出可用实例，**不猜**。

强类型访问器保留旧签名（`ninedata(env)`），需要指定实例时用重载（`ninedata(env, "report")`）。
NineData 工具的两个动作也增加了可选参数 `ninedataInstance`，旧调用不传时走上面的默认规则。

### 迁移顺序（存量库）

旧后端与新旧表结构不兼容，需在维护窗口内先停服务，再按顺序执行：

1. `env-schema-upgrade-2026-11-03-remove-country-stage.sql`
2. `env-schema-upgrade-2026-11-03-group-instances.sql`
3. `env-schema-upgrade-2026-11-03-global-group-instances.sql`
4. `env-schema-upgrade-2026-11-06-instance-binding.sql`（新增绑定表，并把存量环境回填为绑定全部实例）
5. `env-schema-upgrade-2026-11-07-instance-config.sql`（新增实例配置表；按值指纹把“同实例不同环境的差异值”拆成独立实例，再回填绑定）

执行完 SQL 后再启动新后端。旧代码的 SQL 仍按三元组读写，新增 `NOT NULL` 列后会直接报错。
第三份脚本会把“每环境一份”的实例按 `(group_code, instance_code)` 去重成全局定义。
新库直接执行 `env-schema.sql`，不要再执行升级脚本。

### 三个关键取舍

**① 为什么是「模板表 + 值表」而不是一张大宽表**
页面上要"加一个分组、加一个字段"，宽表就得 DDL；DDL 又不能由页面发起。
拆成"声明"（分组 / 键 / 实例三张模板表）+ "取值"（EAV 一表）之后，加结构 = 插几行，加环境 = 插一行，
全部是普通 INSERT，页面才可能真的可维护。

**② 为什么 `key_code` 与 POJO 字段名逐字相同**
`EnvironmentCatalog.project(env, "k8s", K8s.class)` 走 Jackson 投影：把该分组的 EAV 行
摊平成 `Map<String,String>` 再转成 `EnvironmentProperties.K8s`。
所以 `serverAddr` / `authorization` / `consoleUrl` 这些名字**不是随便起的**，
它们必须和 POJO 字段名一模一样，否则强类型投影拿不到值。
页面上可以改**标签**、可以**加**字段（内置分组也能加），但改内置分组里**被 POJO 引用**的字段名 = 改代码。
哪些名字动不得，由 `EnvironmentSchema` 反射 `EnvironmentInfo` 算出来的 `reserved` 标记回答，见「九」。

**③ 为什么没有加密列、也没有 parent 列**
加密：用户拍板调试期明文，详见「十、技术债」。
继承：用户拍板不继承。没有 parent 列是**故意的**——留一个空列会让人以为"以后会支持"，
而实际不支持的列最终一定会被误填。

---

## 四、后端改动清单

### 新增

| 文件 | 说明 |
| --- | --- |
| `md-agent-bootstrap/.../resources/db/env-schema.sql` | 六张表 DDL + 多实例规则注释 |
| `md-agent-bootstrap/.../resources/db/env-schema-upgrade-2026-11-03-group-instances.sql` | 存量库升级：实例表 + `instance_code` + 四元组唯一键 |
| `md-agent-bootstrap/.../resources/db/env-schema-upgrade-2026-11-03-global-group-instances.sql` | 存量库升级：每环境实例去重为分组级全局实例 |
| `md-agent-bootstrap/.../resources/db/env-schema-upgrade-2026-11-06-instance-binding.sql` | 存量库升级：环境-实例绑定表与回填 |
| `md-agent-server/.../server/environment/store/entity/*.java` | 五个行模型（新增 `EnvGroupInstanceEntity`） |
| `md-agent-server/.../server/environment/store/mapper/*.java` | 五个 Mapper（MyBatis-Plus）；`EnvConfigMapper.findAgentNamesByEnvironment` 供删除前的引用检查 |
| `md-agent-server/.../server/environment/store/EnvironmentStore.java` | 存储端口（接口），与 MyBatis 解耦，便于以后换实现或做单测替身 |
| `md-agent-server/.../server/environment/store/MyBatisEnvironmentStore.java` | MyBatis 实现 |
| `md-agent-server/.../server/environment/EnvironmentViews.java` | 视图模型（`EnvironmentRow` / `EnvironmentDetail` / `GroupTemplate` / `GroupState` / `GroupStatus` / `InstanceDetail` / `InstanceSaveRequest` / `SaveRequest` / `GroupSaveRequest`） |
| `md-agent-server/.../server/environment/EnvironmentCatalog.java` | **读模型**：整份快照 + 显式 `invalidate()`；仅从数据库读取；`group → instance → key → value`；Jackson 投影 |
| `md-agent-server/.../server/environment/EnvironmentDefaults.java` | 出厂 8 分组模板与键声明种子（只补缺失项） |
| `md-agent-server/.../server/environment/EnvironmentAdminService.java` | 管理用例 + 两档校验 |
| `md-agent-server/.../server/environment/EnvironmentSeeder.java` | 启动播种：补齐内置分组模板和缺失的键声明，不导入环境值 |
| `md-agent-bootstrap/.../controller/environment/EnvironmentController.java` | 15 个 REST 端点 |

### 改造

| 文件 | 改了什么 | 为什么 |
| --- | --- | --- |
| `server/config/EnvironmentRegistry.java` | 内部从读 yml 改为读 `EnvironmentCatalog`；**旧单参签名保留**，另加 `(env, instance)` 重载、通用 `component()` 与 `group()/groups()/value()/listNames()` | 十几个工具调用点因此零改动；需要多实例的工具再显式传实例 |

> 这条是本次改造最重要的约束：**换数据源不等于换调用方**。
> 只要 `nacos(env)` / `k8s(env)` / `rabbit(env)` / `xxljob(env)` / `magic(env)` / `openobserve(env)`
> 六个签名不变，工具层就完全无感。

---

## 五、接口清单

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/environments` | 列表：每行含**每个分组的状态 + 缺失项 label + 完整度**，**不含量** |
| GET | `/api/environments/_meta` | 环境目录统计：`{source: "DB", environments, groups, instanceConfigs}` |
| GET | `/api/environments/{envCode}` | 详情：含每个分组的**全部实例**与每个键的**明文值**（编辑表单用） |
| POST | `/api/environments` | 新建（`env_code` 重名 409） |
| PUT | `/api/environments/{envCode}` | 保存（不存在 404；`group → [instanceCode]` **整体替换绑定**） |
| DELETE | `/api/environments/{envCode}` | 删除（被引用 409 + `referencingAgents` 清单） |
| GET | `/api/env-instances` | 分组实例元数据清单，供页面独立维护区使用 |
| GET | `/api/env-groups/{groupCode}/instances/{instanceCode}` | 实例详情：元数据 + 固定配置值 |
| POST | `/api/env-groups/{groupCode}/instances/{instanceCode}` | 新建实例（已存在 409） |
| PUT | `/api/env-groups/{groupCode}/instances/{instanceCode}` | 更新实例元数据与固定配置值（不存在 404） |
| DELETE | `/api/env-groups/{groupCode}/instances/{instanceCode}` | 删除实例、它的配置值与所有环境绑定（不存在 404） |
| GET | `/api/env-groups` | 分组模板（含键声明） |
| POST | `/api/env-groups` | 新建分组 |
| PUT | `/api/env-groups/{groupCode}` | 保存模板（键声明整体替换；回体含 `removedKeys`） |
| DELETE | `/api/env-groups/{groupCode}` | 删除（内置分组 409） |

两个实现细节值得记一笔：

- **`_meta` 的路由顺序**：`@GetMapping("/environments/_meta")` 声明在
  `@GetMapping("/environments/{envCode}")` 之前。字面量路径优先级本就高于路径变量，
  但把它写在前面能让"不会被吃掉"这件事在源码上可见。
- **`removedKeys`**：改模板时删掉一个字段，各实例上那些值也会被一并清掉。
  这是"删除一个字段"的副作用，回体里明确带出来，前端提示用户。
- **保存请求**：`bindings` 是 `group → [instanceCode]`。环境保存只整体替换绑定，
  不会创建、修改或删除实例元数据，也不写入任何配置值。
- **实例 CRUD**：实例编码 `instanceCode` 固定走 URL，创建后不可修改；请求体只负责
  `displayName / enabled / sortOrder / values`。配置值随实例一起保存，与环境保存互不覆盖。
- **列表状态**：只统计当前环境**已绑定的实例**；全局定义但本环境未绑定的实例不参与完整度，
  也不计入 `requiredKeys` 分母 —— 分母随绑定关系变化（绑几个实例就算几个实例的必填项），
  不存在"所有环境都是模板总数"的固定值。一个分组下任一绑定实例有值就算有值；
  任一绑定实例缺必填就显示 `PARTIAL`。

---

## 六、读模型：整份快照 + 一处失效点

`EnvironmentCatalog` 的取向是**没有 TTL、没有后台刷新任务**：
每次写入后由服务层调 `invalidate()`，下一次读取重新加载整份快照。

- 为什么不做缓存 TTL：工具的每次调用都要解析连接信息，命中率极高，
  但"改了地址却还在连老的"是这类页面**最难排查**的一类问题。把失效点收敛到"写入"这一处，
  人可以在源码里数出来（同 `DbModelFactory` 的取向）。
- 为什么整份快照而不是按环境查：快照最多几十个环境 × 十几个分组，内存里放得下；
  换来的好处是列表、模板、投影三种读法共用一份内存结构，不存在三者口径漂移。

环境目录**只从数据库读取**。`application.yml` 不再绑定 `environments.environmentMap`，
也没有数据库故障时的 yml 兜底；首次读取失败会抛出带原始原因的异常，避免悄悄使用过时配置。
`_meta` 端点保留统计用途，`source` 固定为 `DB`。

---

## 七、校验分两档

这是本次刻意做的分层，别把两档混在一起：

| 档位 | 例子 | 行为 |
| --- | --- | --- |
| **拒绝写入** | `key_code` 不是合法标识符、未声明的键、`value_type=INT` 但值不是整数、`group_code` / `instance_code` 不合法或重复 | 400 + `violations` 逐条列出 |
| **只记不拦** | 必填项为空 | 照常保存，只是在完整度与 `missing` 里如实显示 |

为什么必填不阻断：凭据常常一时拿不到（token 要走审批、密码在别人手里），
这时候**必须能先把环境建起来、把别的分组填进去**。
如果保存被拒，人就只能去用记事本中转一圈再回来——那才是真正会丢数据的地方。

`instance_code` 与 `group_code` 同样限制为 `[A-Za-z0-9_-]{1,64}`；它是 URL、日志和工具参数里
用来定位实例的机器名，不能带空格或中文。展示名（`display_name`）才放人看的文字。

---

## 八、播种与幂等

`EnvironmentSeeder`（`ApplicationRunner`）：

1. 缺失的内置分组 → 按 `EnvironmentDefaults` 补建，并创建 `default` 实例元数据；
2. 已有内置分组 → 只补缺失的键声明，不覆盖已有键定义和任何环境值；
3. 不创建、不导入任何环境，也不写任何配置值；新环境由页面创建；
4. 播种失败只 WARN，不让服务起不来；随后读取环境目录会明确报数据库不可用。

**一条硬规则：环境值的唯一来源是数据库。**
`application.yml` 里的 `environments` 段已删除，`EnvironmentProperties` 只保留强类型投影 POJO，
不再绑定配置前缀。`EnvironmentDefaults` 也不会被回写，页面上的修改永远以库为准。

---

## 九、前端

`agent-contro-plane`，页面 `src/pages/EnvironmentsPage.vue`（重写，约 1240 行）。

### 这一页与其余治理页最根本的差别：列是「算出来的」

表头不是代码里写死的字符串，而是 `/api/env-groups` 返回的分组模板
（`displayName` 上表头，`groupCode` 只在内部对齐列与格时用）。
所以页面上**没有任何一处**写「Nacos / K8s / RabbitMQ」——
后端加一个业务分组，刷新一下就多一列 + 详情里多一块表单。这是本次改造的全部目的的落地。

### 三态视觉：灰 / 红 / 绿

| 状态 | 含义 | 颜色 | 文案 |
| --- | --- | --- | --- |
| READY | 必填项齐了 | 绿 | 已配置 |
| PARTIAL | 有值但缺必填项 | 红 | 缺一项时报字段名（「访问令牌 空」），缺多项时报数量（「缺 3 项」） |
| EMPTY | 一条值都没有 | 灰 | 未配置 |

**灰和红必须分开**：一个只跑 k8s 的环境，其余分组本来就该是空的，
把"未配置"也涂成红，整页会红成一片，人就再也不看颜色了。

### 列表接口为什么一个字的值都不回

模型 / 渠道那两页的约定是"列表打码、详情明文"，因为它们的列表上确实要显示那一格值。
环境页的列表格是"每格一个状态"，**根本不需要值**：值只在服务端参与状态与完整度的计算，
不进响应体。于是"明文过线"这件事从形状上就不成立，也就不存在"打码写错"的可能——
最省事也最安全的做法通常是同一个。

### 两个抽屉

- **环境编辑抽屉**：主表字段 + **分组/实例的绑定选择**，不承载任何键值编辑。
  分组和实例都可按需增删：点击「添加分组」按钮打开弹窗，从模板里挑要用的分组；
  分组行只显示已绑定实例数量，点击右侧「实例」按钮打开多选弹窗，
  勾选即绑定、取消勾选即解绑，选项来自该分组的全局实例列表。
  `移除分组` / 取消勾选只改当前环境的草稿，点「保存」后随整体替换一起落库。
  环境保存不再提交值；值统一在实例编辑弹窗里维护。
- **分组模板抽屉**：左侧分组清单、右侧字段声明表格；模板的增删改入口都在这里。
  内置分组显示「内置」徽标且「删除分组」禁用；改模板删掉字段时按回体 `removedKeys` 提示用户。

### 分组实例

实例按分组在「分组管理 → 管理」抽屉里维护新增、编辑、删除；环境抽屉不创建实例，也不再平铺全局实例。
环境内只做分组与实例的绑定选择（解绑不动实例本身及其它环境的配置），不承担实例 CRUD 与键值编辑。
实例是分组级数据，不在每个环境重复一份；配置值也属于实例，不随环境变化。

- **实例编码 `instanceCode`**：后端与工具按它取值，创建后不可修改，满足
  `[A-Za-z0-9_-]{1,64}` 且同分组内唯一。
- **展示名 `displayName`**：只用于 UI，可随时修改；留空时后端回退成 `instanceCode`。
- 删除实例会连同它的 `md_env_instance_config` 值与所有环境绑定一起删除，确认框会明确提示。

环境抽屉里每个分组只展示**已绑定实例数量**，不展开实例名称；点击分组右侧「实例」按钮，
在多选弹窗中维护当前环境要绑定的实例。分组还没有全局实例时，弹窗会提示先去「分组管理」新增，
环境页不提供「+ 新建实例」。

### 就地增删：模板级 vs 值级

页面上的"增删"有两种粒度，混在一起会出事，所以做成两处入口、两套文案：

| 粒度 | 入口 | 影响面 | 撤销代价 |
| --- | --- | --- | --- |
| **值级** | 环境抽屉里的输入框（清空 → 保存） | 只影响**当前这一个环境** | 重新填回即可 |
| **模板级** | 分组标题右侧「+ 字段」/ 字段行「移除」/ 抽屉底部「+ 新增分组」 | **所有环境**的表单形状都变 | 删字段会连各环境的值一起清，不可恢复 |

- **加字段**：任意分组都能加，内置分组也能加（例：给 `k8s` 加一个 `clusterName`）。
  新键没有任何 Java 代码按名字读它，所以永远是"加了能删"的。
- **删字段**：只删得掉"非强引用"的键，见下一节。
- **加 / 删分组**：随便加随便删，新分组 `keys: []` 也是合法状态，随后用「+ 字段」逐个补。
  唯一限制是**内置分组的 `group_code` 不能改** —— 改名等于让工具里那句
  `environmentRegistry.k8s(env)` 找不到东西，与资源规范里"namespace / slug 建完不可改"是同一条规矩。
- 模板变更后**刻意不立刻刷列表**：`queryKeys.environment` 是 `['environments','detail',code]`，
  刷列表会前缀匹配到详情、把用户正在填的草稿冲掉。改成把失效推迟到抽屉关闭时做（`watch(envDrawerOpen)`）。
- 「加字段」提交的是**只换 keys 段**的请求（其余分组字段从当前模板补齐后整体 PUT），
  避免"只改一个键却因为没带上其它字段而被整单拒"。

### 哪些字段动不得：`reserved` 这个派生标记

"能随意增删"不能推到"能随意改掉一个工具正在用的字段名" —— 把内置分组的 `url` 改成 `clusterUrl`，
`environmentRegistry.k8s(env).getUrl()` 会**静默**拿到 `null`（Jackson 投影认不出的键被忽略），
失败点离"我改了个字段名"非常远。所以每个键的响应里多一个**只读派生**字段 `reserved`：

- `reserved = true` ⇔ 该分组是内置的 **且** 这个 `key_code` 在 `EnvironmentProperties.EnvironmentInfo` 里存在。
- 页面据此把「移除」禁用并 tooltip 解释原因；后端 `EnvironmentAdminService.validateReservedKeys`
  二次拦截（内置分组提交时，强引用键必须都在提交的 keys 里，否则 400 + 中文 violations）。**两道闸门，不靠前端自觉。**
- 请求体里带 `reserved` 会被忽略 —— 它是派生值，跟着代码走，不接受客户端赋值。
- 内置分组的**新**键 `reserved=false`，可以正常删。

"哪些键被强引用"用**反射**算，不用手写名单：`EnvironmentSchema` 扫一遍 `EnvironmentInfo` 的字段名。
手写名单迟早与 POJO 漂移；反射不可能漂移 —— 哪天有人在 `EnvironmentInfo` 里加一个 `private Redis redis;`，
`redis` 分组会自动变成"有强引用"的分组。
（不去反射 `EnvironmentRegistry` 的方法名，是因为方法名 `rabbit()` 与 group_code `rabbitmq` 并不同名，
反推反而要多维护一张对照表。）

### 前端文件清单

| 文件 | 改动 |
| --- | --- |
| `src/api/controlplane/types.ts` | +12 个类型（`EnvRow` / `EnvDetail` / `EnvGroupTemplate` / `EnvGroupKeyTemplate` / `EnvGroupState` / `EnvGroupStatus` / `EnvKeyValue` / `EnvGroupValues` / `EnvSaveRequest` / `EnvGroupSaveRequest` / `EnvGroupSaveResult` / `EnvMeta`）；`EnvGroupKeyTemplate` 另加派生字段 `reserved` |
| `src/api/controlplane/index.ts` | +10 个接口函数 |
| `src/lib/queryKeys.ts` | +4 个查询键（`environments` / `envGroups` / `envMeta` / `environment(envCode)`） |
| `src/pages/EnvironmentsPage.vue` | 重写：动态列列表 + 环境编辑抽屉 + 分组模板抽屉 |
| `src/pages/routes.smoke.test.ts` | +环境夹具（模板 / 两行列表 / meta / 详情）+ 3 条断言（第 3 条钉就地增删） |
| `src/api/environments.ts` | **删除**（旧 mock 页面的取数模块，已无人引用） |
| `src/api/types.ts` | 删掉 `EnvironmentEntry` |
| `src/api/mock/dataset.ts` | 删掉 `environments` 与 `env()` 那 10 行假环境 |

---

## 十、联调实测（2026-09-23，后端 9090 + 前端 dev 5273 真跑）

真实接口：`_meta` → `{"source":"DB","environments":10,"groups":6,"configs":96}`；
`/api/env-groups` → 6 个分组、displayName 与键声明与代码种子逐字一致。

页面上（无头 Chrome / 应用内浏览器实测，非夹具）：

| 环境 | Nacos | K8s | RabbitMQ | XXL-Job | Magic-API | OpenObserve | 完整度 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| cameroon-uat | 已配置 | 已配置 | 已配置 | 已配置 | 已配置 | 已配置 | 15 / 15 |
| cameroon-test | 已配置 | 访问令牌 空 | 未配置 | 密码 空 | 已配置 | 缺 3 项 | 8 / 15 |
| ghana-uat | 密码 空 | 访问令牌 空 | 未配置 | 密码 空 | 已配置 | 缺 3 项 | 7 / 15 |
| uganda-prod | 密码 空 | 访问令牌 空 | 未配置 | 密码 空 | 已配置 | 已配置 | 10 / 15 |
| airtime-test | 已配置 | 已配置 | 已配置 | 已配置 | 已配置 | 已配置 | 15 / 15 |

（10 个环境全部渲染，上表摘 5 行；每格与 `/api/environments` 的返回逐一核对一致。）

另实测：点「编辑」→ 详情接口按 `envCode` 拉取 → 明文值回填进分组表单
（`cameroon-uat` 的 `服务地址` = `https://cmr-uat-nacos.mindigitaltech.com:443`、`用户名` = `develop`）；
「分组模板」抽屉里 6 个分组与 nacos 的 3 行字段声明（含 `password` 的必填=开、凭据=开）全部来自库。

### 验证记录

| 项 | 结果 |
| --- | --- |
| `vue-tsc --noEmit` | 0 error |
| `vitest run` | 14 个文件 / **181 passed**（2026-11-03 全局实例元数据版本） |
| `vite build` | 成功 |
| 后端 `mvn compile` / `mvn test` | 编译通过；环境相关单测通过（`HarnessSessionIdTest` 有一条与本改造无关的既有失败） |

冒烟测试新增两条断言，钉的是这一页**唯一会变的东西**：

1. **表头来自分组展示名**，且不能出现 `openobserve` 这种机器名；
   同时断言 `cameroon-test` 那行的 7 个格子依次是
   `已配置 / 访问令牌 空 / 未配置 / 密码 空 / 已配置 / 缺 3 项 / 8 / 15`
   —— 三态压成两态、或把 EMPTY 说成"缺配置"，这条就会红。
2. **点「编辑」按 envCode 拉详情并把明文值回填进表单**（读 `input.element.value`，
   不是 `wrapper.text()` —— 后者读不到表单控件的值，那种断言会"永远通过"）。

3. **环境抽屉里能就地增删字段与分组**（本轮新增）：断言 6 个「+ 字段」、
   15 个「移除」全 disabled（强引用键）、6 个「删除分组」全 disabled（内置分组）、
   点「+ 字段」打开的是「在「Nacos」里加一个字段」、空表单不能提交、填齐后可提交。

### 就地增删的真点击验证（2026-09-23 追加，后端 19090 + 前端 dev 5274 探针实例）

上面那轮只验到"读"，本轮补验"写"，走的是应用内浏览器的真实点击，不是夹具：

| 步骤 | 动作 | 观察到的结果 |
| --- | --- | --- |
| 1 | 环境抽屉底部「+ 新增分组」 | 弹出「新建分组」，填 `probe_check` / 探针校验 → 提交 |
| 2 | 不刷新页面看抽屉 | 立即多出「探针校验」一块，「+ 字段」可点、「删除分组」**可点** —— 而内置那 6 个的「删除分组」全是禁用的 |
| 3 | 该块「+ 字段」 | 对话框标题「在「探针校验」里加一个字段」，填 `probeKey` / 探针字段 → 提交 |
| 4 | 看抽屉 | 立即多出「探针字段 probeKey」输入框，旁边「移除」**可点** —— 内置那 15 个全禁用 |
| 5 | 该块「删除分组」 | 确认框文案含"会连同它的 1 个字段声明，以及所有环境下这些字段的值一起删掉，且不可恢复" → 确认 |
| 6 | 收尾核对 | 分组回到 6 个；`_meta` 回到 `{"groups":6,"environments":10,"configs":96}`；cameroon-uat 六组仍全 READY |

另在接口层验过：把内置 `k8s` 的 `url` 改名 → **400**；加 `clusterName` → 通过且新键 `reserved=false`；
`DELETE /api/env-groups/k8s` → **409**。


---

## 十一、技术债与已知缺口

| # | 缺口 | 现状与影响 | 建议 |
| --- | --- | --- | --- |
| 1 | **凭据明文落库** | 用户 2026-09-23 拍板：调试期明文，先把"能不能维护"跑通。列表接口不回值，只有详情接口回明文（仅编辑抽屉能看到） | 落地前必须换成加密列或接密钥管理；`secret` 标记已经在模板里，改造点收敛在 `EnvironmentCatalog.project` 与详情接口两处 |
| 2 | **`md_agent_workspace.environment` 语义冲突** | 它同时被两处当成两种东西：① harness 装配把它当**环境描述文本**塞进 prompt（`builder.environment(...)`，官方语义是描述）；② 本次的删除拦截 `findAgentNamesByEnvironment` 把它当**环境代号引用**。且真正的环境代号是**工具入参**（由模型填，如 `cameroon-uat`），与该列无关 | **待决策**：改成"环境代号下拉"（则 ① 的 prompt 语义丢失），或保持文本框（现状）并把 ② 的拦截改成基于"环境目录被引用的真实来源"。本次**未改前端该字段**，仍是文本框 |
| 3 | 环境源仅数据库 | `application.yml` 不再提供环境数据；数据库不可用时不会回退，读取直接报错 | 已闭合；运维需保证 `db/env-schema.sql` 已执行并做好数据库可用性监控 |
| 4 | 内置分组不可删、被强引用的字段名不可改 | 页面可加任意键（内置分组也能加、加了还能删），但改/删内置分组里被 POJO 引用的 `key_code` 等于改代码。已有两道闸门挡住：前端 `reserved` 禁用「移除」+ 后端 `validateReservedKeys` 返回 400 | 已闭合。新增内部分组时同步 `EnvironmentProperties` 即可，`reserved` 会自动跟上 |
| 5 | 多实例的默认选择 | 2026-11-03 已支持一个分组多套全局实例；未指定实例时按 default / 唯一实例解析，多实例且无 default 会报错 | 工具需要明确使用哪套实例时传 `(env, instance)` 重载；NineData 工具用 `ninedataInstance` |
| 6 | 无继承 | 用户拍板不支持 | 表里**故意没留** parent 列，避免误以为支持 |

---

## 十二、一句话总结

环境 = 工具执行时的一组业务配置，其**结构**（有哪些分组、每组有哪些字段）、
**实例**（每组有几套）与**取值**现在都是数据，不是配置；加一个业务分组或一套实例
不需要改 Java，改完下一次工具调用即生效（没有草稿/发布这一层——环境目录不参与 agent 装配）。
代价是凭据暂时明文，这是明确记录在案的技术债。
