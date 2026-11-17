# Harness 工作区数据进 MySQL（A 路线）

> 日期：2026-09-23
> 范围：`md-agent-server` / `md-agent-bootstrap` / `agent-contro-plane`
> 前置：本方案承接《工作区文件清点》结论，采用官方 `RemoteFilesystemSpec` + `JdbcStore`
> 约束来源：用户明确要求 —— **skills 与 state 一律走页面（DB）维护，不在 workspace 维护**

---

## 一、目标

把 Harness 工作区里"属于运行时数据"的部分从本地磁盘搬进 MySQL，使：

1. 多副本部署时工作区天然共享（当前 `LocalFilesystemWithShell` 是单进程语义）；
2. 工作区文件具备与 state 同级的可备份 / 可观测 / 可运维能力；
3. **skills 与 state 的"真相源"唯一**，都收敛到控制面 DB，页面可查可改。

**非目标**：把 git 仓库、shell 执行、框架缓存搬进库（见第六节硬缺口）。

---

## 二、现状核查（2026-09-23 实测）

| 项 | 实测结果 |
| --- | --- |
| `md_agent` | 只有 `test_agent`(REACT) / `test_harness`(HARNESS) 两个 |
| `md_agent_ref.skill_names` | 两者均为 `null`，**尚未挂任何技能** |
| `md_agent_runtime.state_store` | 两者均为 `mysql`，`state_table` = `test_agent` / `test_harness` |
| `md_agent_state` 库 | 已有 `test_agent`(4 行) / `test_harness`(0 行) 两张分表 |
| 技能真相源 | `md_skill_meta` 1 条 `git-checkout`(PUBLISHED)，内容在 `agentscope.agentscope_skills` |
| `md_agent_workspace` 库 | **不存在**（需要新建） |
| 磁盘 `workspace/harness/test_harness` | **不存在** —— 即**当前没有 Harness 存量工作区数据要迁移** |

**两条关键结论：**

- **state 已经满足用户要求**：`ConfigurableAgentBuilder.resolveStateStore()` 对 REACT 与 HARNESS 走同一条路径，都是 `MysqlAgentStateStore`（库 `md_agent_state`、按 agent 分表）。本次不需要改 state 落点，只需在文档层面确认"页面即真相源"。
- **skills 的双落点只差一步治理**：技能内容本来就在 DB（官方 `MysqlSkillRepository`），但 Harness 装配时框架**仍会扫描工作区 `skills/` 目录**并让磁盘版本覆盖 DB 版本（详见第五节）。这是本次必须关掉的。

---

## 三、目标架构：路径 → KV 命名空间映射

`RemoteFilesystemSpec.toFilesystem()` 生成 `CompositeFilesystem(defaultBackend=LocalFilesystem, routes=…)`，
每条路由的后端是 `OverlayFilesystem(upper=RemoteFilesystem, lower=本地只读模板)`——即"本地模板作基线，用户改动 copy-on-write 进库"。

命名空间由 `IsolationScope.USER` 决定，形状为 `agents/<agentId>/users/<userId>/<routeSegment>`；
`item_key` 是剥离路由前缀后的相对路径。

| 工作区路径 | 路由段 | KV 命名空间 | 来源 |
| --- | --- | --- | --- |
| `AGENTS.md` / `MEMORY.md` / `tools.json` | `root` | `agents/<id>/users/<uid>/root` | 内置 |
| `memory/` | `memory` | `…/users/<uid>/memory` | 内置 |
| `skills/` | `skills` | `…/users/<uid>/skills` | 内置（**本方案关掉，见第五节**） |
| `subagents/` | `subagents` | `…/users/<uid>/subagents` | 内置 |
| `knowledge/` | `knowledge` | `…/users/<uid>/knowledge` | 内置 |
| `plans/` | `plans` | `…/users/<uid>/plans` | 内置 |
| `agents/<id>/sessions/` | `sessions` | `…/users/<uid>/sessions` | 内置 |
| `agents/<id>/tasks/` | `tasks` | `…/users/<uid>/tasks` | 内置 |
| `<tenant>/<agentId>/<sid>/events/` | `transcripts` | `…/users/<uid>/transcripts` | **需补路由** |
| `.agentscope/bus/` | `bus` | `…/users/<uid>/bus` | **需补路由** |
| `code/`（git 仓库） | — | 留本地 | **需补本地路由** |

> 更正一处早前判断：`ObjectStoreTranscriptStore` 的 key 首段是 **tenant**（`default/...`），
> 匹配不上内置的 `agents/<agentId>/sessions/` 路由，因此**不会自动进库**，必须补 `filesystemRoute("<tenant>/", …)`。
> `.agentscope/bus/`（`HarnessAgent.java:2512` 的 `WorkspaceMessageBus`）同理。

---

## 四、改动清单

### 4.1 DDL

新建工作区 KV 库（表由框架 `initializeSchema(true)` 自建，此处仅记录结构）：

```sql
CREATE DATABASE IF NOT EXISTS `md_agent_workspace`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 框架自动建，等价 DDL（记录用）：
CREATE TABLE IF NOT EXISTS agentscope_store (
  namespace_path VARCHAR(512)  NOT NULL,
  item_key       VARCHAR(255)  NOT NULL,
  value_json     LONGTEXT      NOT NULL,
  version        BIGINT        NOT NULL,
  updated_at     BIGINT        NOT NULL,
  PRIMARY KEY (namespace_path, item_key),
  INDEX idx_namespace (namespace_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

说明：`(512 + 255) * 4 = 3068` 字节，卡在 InnoDB utf8mb4 的 3072 字节复合主键上限内，不要再加长 `namespace_path`。

`md_agent_workspace` 表新增一列：

```sql
ALTER TABLE `md_agent_workspace`
  ADD COLUMN `fs_backend` VARCHAR(16) DEFAULT NULL
  COMMENT '工作区文件后端：LOCAL(默认，本地磁盘+shell) | REMOTE(进 md_agent_workspace KV，无 shell)';
```

### 4.2 后端

| 文件 | 改动 |
| --- | --- |
| `md-agent-server/…/config/AgentPlatformProperties.java` | 加 `workspaceKvDatabase`（默认 `md_agent_workspace`） |
| `md-agent-server/…/config/ControlPlaneStoreConfig.java` | 加 `workspaceKvDataSource` + `BaseStore` bean（`JdbcStore.builder(ds).dialect(dialect).initializeSchema(true)`） |
| `md-agent-server/…/agent/AgentDefinition.java` | 加 `fsBackend` 字段 |
| `md-agent-server/…/controlplane/store/entity/AgentWorkspaceEntity.java` | 加 `fsBackend` 字段（对应新列） |
| `md-agent-server/…/controlplane/store/MyBatisControlPlaneStore.java` | 读写映射补 `fs_backend` |
| `md-agent-server/…/agent/ConfigurableAgentBuilder.java` | `buildHarness()`：按 `fsBackend` 装配；`applyWorkspaceExtensions()`：打开 `disableDefaultWorkspaceSkills` |
| `md-agent-bootstrap/…/resources/db/workspace-kv-schema-upgrade-2026-09-23.sql` | 新建，记录建库 + 加列 |

装配骨架（`buildHarness` 内）：

```java
// 本地（默认）：保持现状 —— 本地磁盘 + shell，单进程语义
// REMOTE：工作区进 KV，代价是 composite 模式没有 shell
if (isRemoteFs(def)) {
    builder.filesystem(new RemoteFilesystemSpec(workspaceKvStore)
            .isolationScope(IsolationScope.USER));
    // 补三条内置路由未覆盖的路径
    builder.filesystemRoute(transcriptTenant + "/", remoteFor("transcripts"));
    builder.filesystemRoute(".agentscope/bus/",   remoteFor("bus"));
    // git 仓库必须留本地：JGit 与工作区工具直接操作 <repo>/.git，不走 filesystem 抽象
    builder.filesystemRoute("code/", localCodeFilesystem());
}
```

`filesystemRoute` 与 `filesystem(RemoteFilesystemSpec)` 可共存（`HarnessAgent.java:2484` 会把路由再包一层 `CompositeFilesystem`，外层最长前缀优先）；只有 `abstractFilesystem()` 与三种 `filesystem(...)` 互斥，本方案不使用它。

### 4.3 前端

| 文件 | 改动 |
| --- | --- |
| `src/pages/HarnessPage.vue` | 工作区区块加"文件后端"选择（本地 / 进库），并在选中"进库"时给出"该模式无 shell"的警示 |
| `src/api/controlplane/types.ts` | `HarnessWorkspaceRow` 补 `fsBackend` |
| `src/pages/AgentsPage.vue` | 已有 `el-select multiple filterable` 选 `skillNames`，保持不动 |

---

## 五、skills 与 state 的统一治理（本方案重点）

### 5.1 state：已经统一，无需改动

`ConfigurableAgentBuilder.applyStateStore()` 对 REACT 与 HARNESS 是同一份实现，落点三处刻意分离：

- **库**由 `agent-platform.state-store-database`（`md_agent_state`）决定，不写死；
- **表**取 agent 定义里冻结的 `state_table`，为空才回落 agent 名 —— 改名不丢上下文；
- 表名过白名单校验，防注入（框架会把表名拼进 SQL 标识符位）。

页面 `StoragePage` / `AgentStateAdminService` 即 state 的唯一维护入口。

### 5.2 skills：关闭 Harness 的工作区扫描

Harness 的技能来源是四层叠加，**同名 later wins**（`HarnessSkillMiddleware.java:53-55`）：

| 层 | 来源 | 关闭方式 |
| --- | --- | --- |
| L1 | `projectGlobalSkillsDir` | 不配即无 |
| L2 | 页面选中的 `skillNames` → `NamedSkillRepository`(DB) | **本方案的目标层，唯一保留** |
| L3 | `<workspace>/skills` 本地目录 | **框架无开关，只能让目录不存在** |
| L4 | filesystem 命名空间视图 `skills/` | `disableDefaultWorkspaceSkills()` |

实测证据：`workspace/harness/workwx_batch_agent/skills/batch-audit-and-notify` 真实存在，**L3 会静默覆盖 DB 里同名技能** —— 页面改了内容却不生效。

因此 Harness 装配必须同时做三件事：

```java
// ① L4：关掉命名空间视图那条
if (Boolean.TRUE.equals(def.getDisableDefaultWorkspaceSkills())) {
    builder.disableDefaultWorkspaceSkills();
}
// ② L2：只放行页面选中的名字（合并后按名过滤，是"硬隔离"兜底）
if (!def.getSkillNames().isEmpty()) {
    builder.enableSkills(def.getSkillNames().toArray(new String[0]));
}
// ③ L3：无开关，只能在装配期清理 —— 见下
```

对 L3 的处置：Harness 装配前检查 `<workspace>/skills`，**存在且非空则记 WARN 并列出技能名**，
提示"技能请在页面维护"，由运维/迁移脚本清空。不自动删除用户文件。

新 Harness agent 的 `disable_default_workspace_skills` 默认置 `true`（页面创建时写入），
使"关闭工作区技能扫描"成为默认行为而非可选项。

### 5.3 技能迁移（如有存量）

`workspace/**/skills/<name>/SKILL.md` → 官方技能库：

1. 读 `SKILL.md`（YAML frontmatter + 正文）与同目录附属文件；
2. 经控制面 `POST /api/resources/skills` 写入 `agentscope.agentscope_skills` + 资源表；
3. 页面在 agent 上勾选对应 `skillNames`；
4. 清空原磁盘目录。

当前 `test_harness` 磁盘工作区不存在，**本步暂无对象**，保留为脚本以备后续。

---

## 六、硬缺口（A 路线下仍进不去）

| 对象 | 原因 |
| --- | --- |
| `.skills-cache/` | `MarketplaceStager` 用裸 `Path` + `Files.*`（`:164/257/274/312`） |
| `.index/workspace.db` | `WorkspaceIndex.open` 直连本地 SQLite（`:82-91`），仅作读加速缓存，可重建 |
| `subagents/*.md` 声明扫描 | `HarnessAgentBuilderSupport.java:204` 用 `Files.isDirectory` |
| 技能目录扫描 | 同文件 `:886`、`:902` |
| `rules/`、`KNOWLEDGE.md` | 常量在 `WorkspaceConstants.java:32/33` 声明，但路由表未覆盖 |
| shell / `execute` | `RemoteFilesystemSpec` 的 default backend 是 `LocalFilesystem`（无 shell），composite 模式无 shell |
| `workspace/agents/*.md`、`datasource/`、`docs/` | 项目自有目录，不属于 Harness 工作区 |

**shell 是本方案唯一的功能性代价**：开启 `REMOTE` 的 agent 不能再执行 shell 命令。
需要 shell + 进库并存的场景，须走 C 路线（`DockerFilesystemSpec` 沙箱），不在本次范围。

---

## 七、验收清单

1. 建库 `md_agent_workspace`，执行加列 DDL；
2. 把 `test_harness.fs_backend` 置为 `REMOTE`，重启后端；
3. 页面发起一轮对话；
4. 校验 `md_agent_workspace.agentscope_store`：应出现 `namespace_path` 含 `test_harness` 的行；
5. 校验磁盘：`workspace/harness/test_harness/` 下**不再**新增 `MEMORY.md` / `memory/` / `sessions/` / `events/`；
6. 校验 `disable_default_workspace_skills=1` 时，`<workspace>/skills` 里的技能**不出现**在系统提示 `<available_skills>` 段；
7. 校验页面勾选的 `skillNames` 正常生效（`git-checkout`）；
8. 停服重启后再发起对话，上下文完整重建（这是"真进库"的决定性验证）；
9. 回归：确认 `REMOTE` 的 agent 没有依赖 `execute` 的工具，否则会静默失效。

---

## 八、回滚

单开关回退：`fs_backend` 置回 `LOCAL` 并重启即可。

**陷阱**：切回 `LOCAL` 后，本地工作区只剩模板 —— REMOTE 期间的写入都在 KV 里，
本地 per-user 层是空的。因此灰度期必须保留一份 `workspace/` 快照，
或保留"KV → 本地"的导出脚本兜底。当前 `test_harness` 无存量数据，风险为零。

---

## 九、实施记录（2026-09-23）

### 9.1 已完成

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 配置 | `application.yml` | 加 `agent-platform.workspace-kv-database: md_agent_workspace` |
| 配置 | `AgentPlatformProperties` | 加 `workspaceKvDatabase` |
| 配置 | `ControlPlaneStoreConfig` | 加 `workspaceKvDataSource`（先 CREATE DATABASE，连接串从主库 URL 派生，库名过白名单）+ `workspaceKvStore` |
| 模型 | `AgentWorkspaceEntity` | 加 `fsBackend` |
| 模型 | `AgentDefinition` / `AgentDefinitionResolver` | 加 `fsBackend` 字段并做映射 |
| 装配 | `ConfigurableAgentBuilder#applyFilesystem` | 按 `fsBackend` 选后端；补 `transcripts` / `bus` / `code` 三条路由 |
| 装配 | `ConfigurableAgentBuilder#applySkillSourcePolicy` | 无条件 `disableDefaultWorkspaceSkills()`；L3 目录存在则告警并列出技能名 |
| DDL | `db/workspace-kv-schema-upgrade-2026-09-23.sql` | 新建：建库 + 加列 |
| DDL | `db/control-plane-schema.sql` | `md_agent_workspace` 加 `fs_backend` 列 |
| 前端 | `types.ts` / `HarnessPage.vue` | `fsBackend` 字段 + 「工作区文件后端」下拉（选项文案内联了"无 shell"警示） |

**一处对方案的偏离**：`disable_default_workspace_skills` 这个 DB 字段最终**没有**接线。
HARNESS 的技能来源改为**无条件**关闭工作区扫描（`applySkillSourcePolicy`）——
约定是"技能只在页面维护"，做成可选项只会留下"配了但没生效"的空间。
字段保留在表里仅为存量数据兼容，对 HARNESS 已无意义（原接线点已加注释说明）。

### 9.2 验证状态

- 后端 `mvn -o -pl md-agent-server -am test` → **277 通过**，BUILD SUCCESS
- 前端 `vue-tsc --noEmit` 0 错；`vitest run` → **118 通过**
- `fs_backend=REMOTE` 的端到端实跑**尚未做**（见 9.3 第 1 条）

### 9.3 下一阶段

1. `fs_backend=REMOTE` 的端到端实跑：建库 → 置位 → 对话 → 查 `agentscope_store` → 确认磁盘不再增长；
2. **workspace 后端 CRUD 治理**：把工作区文件的浏览 / 读取 / 写入 / 删除做成接口 —— **已完成，见 §10.2**；
3. **workspace 前端维护页** —— **已完成，见 §10.3**。

关于第 2、3 条，实现路径已确认可行，**不需要自造轮子**：

框架的 `AbstractFilesystem` 已提供完整 CRUD ——
`ls` / `read` / `write` / `edit` / `grep` / `glob` / `delete` / `move` / `exists`，
并且 **LOCAL 与 REMOTE 共用同一套抽象**。控制器侧只需：

```java
HarnessAgent agent = (HarnessAgent) agentManager.get(agentName);
// 绑定 (userId, sessionId) 的隔离视图；不修改 agent 的共享状态，可并发调用
WorkspaceManager view = agent.workspaceFor(userId, sessionId);
AbstractFilesystem fs = view.getFilesystem();
fs.ls(rc, "memory");   // 命名空间与路由解析由框架完成
```

这样做的关键好处：**不必复刻 `RemoteFilesystemSpec` 的路由表**。
命名空间拼接、路由匹配、overlay 回落全部由框架负责，本项目只做鉴权、路径校验与呈现 ——
否则一旦框架调整内置路由，页面的文件视图就会与 agent 的真实视图悄悄错位。

---

## 十、workspace 治理面（2026-09-23 第二阶段）

前九节把工作区的**存储**搬进 MySQL；这一节把工作区的**维护界面**补上。

在此之前，改一个 `AGENTS.md` 只能去磁盘上找那个目录 —— 而切成 REMOTE 之后 agent 在磁盘上
根本没有目录，那条路会**静默失败**（文件写进了本地，agent 读的仍是库里那一份）。

### 10.1 先定边界，再写代码

这一页是全项目唯一能读写服务器文件的地方，「能碰哪些路径」就是它的全部安全语义，
所以范围先拍死：

| 档 | 范围 | 页面行为 |
| --- | --- | --- |
| **可写** | 根级 `AGENTS.md` / `MEMORY.md` / `tools.json`，以及 `subagents/**`、`knowledge/**` | 读 / 写 / 删（只删单个文件，或**空**目录） |
| **只读可见** | `memory/**`、`plans/**`、`agents/**`、`skills/**` | 只能列与读；不给写入口，也不给删除口 |
| **不暴露** | `code/` 与一切以 `.` 开头的目录 | 列不出来，路径也过不了校验 |

四条取舍的理由：

1. **`skills/` 移出可写白名单**。技能的唯一维护入口是「技能」页（内容在 agentscope 库），
   HARNESS 装配期也已不再扫描工作区的 `skills/`。页面若还能写，就等于把
   「两份技能到底哪份生效」这个问题重新制造出来。
2. **`memory/` `agents/` 不放开写**。它们的写入者是框架，页面改完会被下一次写入静默覆盖，
   表现成「保存成功了但没生效」—— 这比直接报错难查得多。
3. **`memory/` 等也不放开删**。删运行时产物是「会话记录 / 状态存储」两页的职责，
   在文件管理页上再开一个入口，只会让人分不清该在哪一页动手。

   > 顺带修掉了 `WorkspacePathGuard.READONLY_ROOT_DIRS` 的一处注释：它原先写着「看见并清理」，
   > 而服务层的 `delete` 走的是 `assertManaged`，本来就不允许删。注释与代码必须一致，
   > 否则下一个人照着注释去用，拿到的是 400。

4. **根级清单由白名单驱动，不用 `ls("/")`**。REMOTE 模式下工作区根落在 `CompositeFilesystem`
   的 defaultBackend（本地盘），而 `memory/`、`agents/` 这些在 KV 路由上，`ls("/")` 根本列不到它们。
   所以根级条目是「白名单里的项 + 逐个 `exists` / `ls` 探测」拼出来的。

### 10.2 后端

| 文件 | 职责 |
| --- | --- |
| `server/workspace/WorkspacePathGuard.java` | 纯字符串校验：字符白名单（挡 NTFS 数据流、保留设备名、段尾点/空格）→ 相对路径约束（挡绝对路径、隐藏路径、`..`）→ 落点仍在工作区内。另提供 `assertManaged` / `assertVisible` / `isManaged` / `isVisible` 与三份白名单的只读视图 |
| `server/workspace/WorkspaceViews.java` | 对外契约（5 个 record）：`AgentRow` / `EntryRow` / `DirView` / `FileView` / `DeleteResult`。字段改名等于接口破坏，所以集中一处 |
| `server/workspace/WorkspaceAdminException.java` | 业务拒绝，带 `notFound` 标记：`true` → 404（agent 未装配，页面该提示刷新），`false` → 400（用户改一下输入即可） |
| `server/workspace/WorkspaceAdminService.java` | 五个动作：`listAgents` / `listDir` / `readFile` / `writeFile` / `delete` |
| `controller/workspace/WorkspaceController.java` | `/api/workspace/agents`、`/files`、`/file`(GET / PUT / DELETE) |
| `controller/workspace/WorkspaceExceptionHandler.java` | 异常翻译。响应形态与 `ControlPlane*` 一致（`{error, violations}`），并给路径拒绝加 `kind: PATH_GUARD` —— 正常页面操作产不出这种请求，出现就意味着有人在手工构造 |

四个关键实现事实（都验证过）：

- **全部走 `AbstractFilesystem`，不自己拼磁盘路径**。LOCAL 与 REMOTE 被框架统一成同一套
  `ls` / `read` / `delete`，本层**不需要知道 `fs_backend`**，更不复刻 `RemoteFilesystemSpec`
  的路由表。自己拼路径的代价不是「多写点代码」，而是**视图会分叉** —— 一旦框架调整内置路由，
  页面看到的目录树就和 agent 真实看到的不是同一棵树，而这种错位不报错，只让人得出错误结论。
- **覆盖写不能用 `filesystem.write`**。框架的 `write` 是**创建语义**（文件已存在直接返回 error），
  而页面上的「保存」绝大多数是覆盖，走它必然一直失败。覆盖写走
  `WorkspaceManager#writeUtf8WorkspaceRelative` —— 内部投到 `uploadFiles`（last-write-wins），
  且 filesystem 优先、不写本地盘。
- **删目录只允许空的**。框架的 `delete` 对目录是递归语义，一次误点就能带走整个 `knowledge/`；
  服务层先 `ls` 探一次，非空直接 400。工作区文件没有回收站，这种操作不提供「顺手」的入口。
- **逐个方法 `subscribeOn(boundedElastic)`**。项目是 WebFlux，而下面这层可能是磁盘 IO（LOCAL）、
  也可能是阻塞 JDBC（REMOTE 的 KV），两者都会卡住 Netty 事件循环。

### 10.3 前端

| 文件 | 改动 |
| --- | --- |
| `api/controlplane/types.ts` | 加 5 个形状，与 `WorkspaceViews.java` 一一对应 |
| `api/controlplane/index.ts` | 加 5 个函数。三个查询参数全部走 query，空串由 `buildUrl` 丢掉（`userId=` 传过去会被当成空串校验，漏传才是「基线层」这个合法视图） |
| `lib/queryKeys.ts` | `workspaceAgents` / `workspaceDir` / `workspaceFile` / `workspaceAll`（前缀失效用） |
| `app/router.ts` | `/workspace`（`meta.wide`） |
| `app/nav.ts` | 「编排」组加「工作区」 |
| `app/AppShell.vue` | 导航图标 `Folder` |
| `pages/WorkspacePage.vue` | 新建：左栏 agent 清单（含 `filesystemType`）+ 隔离维度（userId），右栏面包屑 + 条目表 / 文件编辑器 |
| `pages/routes.smoke.test.ts` | 夹具 + `/workspace` 路由用例 + 两条规则断言 |

页面上的三条设计决定：

1. **「能写什么」完全消费后端的 `writable`**，前端不另抄一份白名单 —— 两份必然漂移，
   而漂移的表现是「前端说能写、后端 400」。
2. **新建要填整条相对路径**（如 `subagents/planner.md`）。后端没有 mkdir，目录是写文件时顺带建的；
   而还没写过文件的 `subagents/`、`knowledge/` 不会出现在 `ls` 结果里，那时用户没法
   「先进入目录再新建」。
3. **编辑器草稿与服务端内容分成两份 ref**。直接 `v-model` 到查询结果的话，保存成功后缓存一刷新，
   用户正在写的字会被服务端返回的内容替换掉。

另外 `filesystemType`（真正装配出来的实现类名）是这一页唯一的**自证字段**：
`fs_backend` 只是配置，排查「配了 REMOTE 但文件还落在磁盘」时先看它。

### 10.4 验证

| 项 | 结果 |
| --- | --- |
| 后端 `mvn -o -pl md-agent-server -am test` | BUILD SUCCESS；`WorkspacePathGuardTest` **9 通过** |
| 前端 `vue-tsc --noEmit` | 0 错 |
| 前端 `vitest run` | **121 通过**（新增 2 条工作区用例） |
| 前端 `vite build` | 成功 |
| 真实后端联调 | **未做** —— 后端未启动（9090 拒绝连接） |

### 10.5 还没做的

1. **`fs_backend=REMOTE` 端到端实跑**（§9.3 第 1 条仍挂着）：
   `db/workspace-kv-schema-upgrade-2026-09-23.sql` **已补执行**（见下面的 10.6），
   剩下的是把 `test_harness.fs_backend` 置为 `REMOTE` → 对话一次 → 查 KV 表 → 确认磁盘不再增长。
   好消息是 `test_harness` 的磁盘工作区**尚不存在**，所以这次切换是**零存量迁移**。
2. **页面与真实后端的联调**：起服务后打开 `/workspace`，走一遍
   「列目录 → 打开 `AGENTS.md` → 改一处 → 保存 → 刷新确认」。

### 10.6 一个必须记下来的坑：先改代码、后跑 DDL

做这一页时顺手查了库，发现 `md_agent_workspace` 表上**没有 `fs_backend` 列** ——
而上一节（§9.1）已经把它写进了 `AgentWorkspaceEntity`。这中间的状态是**跑不起来的**，
而且失败点比想象中靠前得多：

> `AgentWorkspaceEntity.fsBackend` 是 MyBatis-Plus 的映射字段，`selectById` 会把**全部**
> 映射列写进 SQL。库里没有这一列时，`AgentDefinitionResolver.applyWorkspace` 与
> `/api/workspace/agents` 都会直接抛 `Unknown column 'fs_backend' in 'field list'`。
> 换句话说，暴露面不是一个页面 500，而是**整条 HARNESS 装配链**。

所以 2026-09-23 补执行了两句（都是可加不可减的 DDL）：

| 语句 | 结果 |
| --- | --- |
| `CREATE DATABASE IF NOT EXISTS md_agent_workspace` | 库已建（框架启动时也会自建，这里只是把它提前） |
| `ALTER TABLE md_agent_workspace ADD COLUMN fs_backend VARCHAR(16) NULL` | 列已加；`test_harness` 现有行为 `NULL`（= LOCAL，行为不变） |

**结论写成规矩**：凡是给 `entity` 加列，DDL 必须与代码在**同一次改动里**落地，
或者至少让代码对缺列容错。MyBatis-Plus 不会替你检查这件事 —— 它只会把列名拼进 SQL。

---

## 十一、启动失败排查：四个只有「真起一次服务」才暴露的问题

> 起因：第一次真正启动后端时直接崩在 `BeanCurrentlyInCreationException`。
> 顺着这条线查下去，一共挖出**四个**问题，其中三个是纯单测（当时 285 条全绿）
> 永远碰不到的 —— 它们分别躲在 Bean 装配期、连接串拼接、以及"裸名 vs 带层级"这条分支里。

### 11.1 现象

```
The dependencies of some of the beans in the application context form a cycle:

   agentController (field ... ControlPlaneStore store)
┌─────┐
|  workspaceKvDataSource defined in ... ControlPlaneStoreConfig
└─────┘
```

### 11.2 根因：声明了第二个 DataSource，Spring Boot 的主库自动配置整块退出

`DataSourceAutoConfiguration$PooledDataSourceConfiguration` 上挂着

```java
@ConditionalOnMissingBean({ DataSource.class, XADataSource.class })
```

（用 `javap -v` 对着 `spring-boot-autoconfigure-3.5.2.jar` 核过，不是推测。）
也就是说：容器里**只要出现任意一个** DataSource Bean，主库那条自动配置就整块退出，
`spring.datasource.*` 再也没人读。而 §9 新增的 `workspaceKvDataSource` 正是这样一个 Bean。

两者叠加的后果有两层，**第二层比第一层严重得多**：

1. **启动就挂**：KV 池的构造方法要一个 `DataSource` 参数（拿它执行 CREATE DATABASE）。
   主库 Bean 不存在时，按类型注入只能解析到 KV 池**自己** → 循环依赖 → 应用起不来；
2. **如果它"能启动"，反而更糟**：容器里唯一的 DataSource 变成 KV 池，
   12 个 Mapper、skill 仓库、状态存储会全部连到 `md_agent_workspace` 上 ——
   不报错，只是数据写进了另一个库。第一层那个报错其实是替我们挡住了这一层。

**修复**：新增 `md-agent-server/.../config/DataSourceConfig.java`，显式声明主库并标 `@Primary`：

```java
@Bean @Primary @ConfigurationProperties("spring.datasource")
public DataSourceProperties dataSourceProperties() { return new DataSourceProperties(); }

@Bean("dataSource") @Primary
public DataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().build();   // Boot 自己的绑定方式，不手抄
}
```

用 `DataSourceProperties` 而不是把四个键再抄一遍：`initializeDataSourceBuilder()` 就是 Boot
绑定 url / username / password / driver-class-name 的入口，连 `spring.datasource.type` 都继承。
一处已知损失写进了类注释：`spring.datasource.hikari.*` 池级细调现在不生效（yml 里没用到）。

**由此立一条规矩**：<b>本项目每新增一个 DataSource Bean，就必须同时显式声明主库并标 `@Primary`</b>。
KV 池的类注释里也补了一句硬约束：它<b>永远不能</b>标 `@Primary`。

### 11.3 修复循环依赖后立刻暴露的第二个问题：连接串把库名拼了两遍

启动日志（现在回头看，这行日志本身就是证据）：

```
工作区 KV 库连接串已派生：
jdbc:mysql://106.53.145.158:30306/md_agent_workspacemd-agent-platformuseUnicode=true&...
                                  ↑ 库名拼了两遍、问号也没了
```

**根因**：`DATABASE_SEGMENT_PATTERN.matcher(mainUrl).replaceFirst("/" + database + "$1")` ——
正则的 `$1` 正是「库名」那一组，于是替换结果 = 新库名 + 旧库名，还吃掉了 `?`。
它**不抛异常**，只会连到一个不存在的库，然后在别处以
「Identifier name ... is too long」收场 —— 从报错完全看不出问题在连接串上。

**修复**：删掉正则，改成 `ControlPlaneStoreConfig#deriveDatabaseUrl(mainUrl, database)`
（包内可见的静态方法），用下标切分表达"切三段、只换中间那段"，
并补 5 条单测（`ControlPlaneStoreConfigTest`）：真实那一串、无查询参数、
查询参数里含 `/`、没写库名、空串。断的是**全等**而不是"包含库名" ——
出问题的那次结果里确实"包含"库名。

### 11.4 第三个：根级目录名（裸名）一律被拒，页面「列得出来、点不进去」

真机调 `/api/workspace/files?path=skills` 时拿到：

```
400 {"error":"不在可见范围，根级只允许 [MEMORY.md, tools.json, AGENTS.md]: skills"}
```

但 `skills/` 明明在**只读可见**白名单里。看 `assertWithin` 才明白：它按「有没有 `/`」分两支，
**裸名那支只查了文件白名单**。而页面把根级条目渲染成"可点进去的目录"，
点击时传的正是 `subagents`、`skills` 这种裸名 —— 于是每一个根级目录都是 400，
页面等于只能看不能进。（当时那批用例全带层级，正好绕过了这条分支。）

**修复**（`WorkspacePathGuard`）：

- `assertWithin` 的裸名分支改成查「文件白名单 ∪ 本档目录白名单」；
- 新增 `assertWritableFile`：写操作在 `assertManaged` 之上**再拒掉裸目录名**
  （写 `subagents` 不叫"写一个叫 subagents 的文件"，直接拦下并给出正确写法）；
- `WorkspaceAdminService#writeFile` 改用它；
- 补 2 条用例：`acceptsBareRootDirectoryNames`、`writeScopeRejectsBareDirectoryName`
  （守卫测试 9 → **11 条**）。

### 11.5 第四个（契约层面）：删除不存在的路径回 `deleted: true`

框架的 `delete` 对"根本不存在的路径"也返回成功，于是页面会对一个早已不存在的东西弹「已删除」。
看着无害，但那是假话 —— 两个标签页同时开着时，第二个人永远不知道自己删的是空气。

**修复**：`delete` 先 `fs.exists` 探一次，不存在就抛 `WorkspaceAdminException(msg, notFound=true)`
→ **404**；前端在 save / delete 的 `onError` 里对 404 顺手 `invalidateQueries`，
让过期列表自己刷新（否则用户会对着"删不掉的幽灵行"反复点）。

### 11.6 实测回执（真起服务，`--server.port=0` 随机端口，验完即停）

| 动作 | 结果 |
| --- | --- |
| `GET /api/workspace/agents` | 200，列出 3 个 HARNESS agent（`filesystemType=ShellAwareOverlay`，`fsBackend=null`=LOCAL） |
| `GET /files`（根） | 200：`agents`(只读) / `skills`(只读) / `AGENTS.md`(可写) —— 白名单生效 |
| `GET /files?path=skills` | 修复前 400 → **修复后 200** |
| `GET /file?path=AGENTS.md` | 200，返回真实 AGENTS.md 全文（1002 字符，`truncated=false`） |
| `PUT /file` 写 `knowledge/smoke.md` | 200 `{saved:true}` —— **目录由写入顺带创建** |
| 再 `GET /files`（根） | 200，`knowledge` 出现且 `writable=true` |
| `DELETE` 该文件 → 该空目录 | 都是 200；根目录回到原样（**测试无残留**，已核对磁盘时间戳） |
| 再 `DELETE` 同一个目录 | 修复前 200（假成功）→ **修复后 404** |
| `path=code` / `path=../pom.xml` / `path=agents/whatever` | 全部被拒（`kind: PATH_GUARD` / 400） |
| `agentName=nope` | 404「agent 不存在或未装配」 |
| `userId=bad/id` | 400「userId 非法」 |

**最终验证**：后端 `mvn -o test` → **308 通过**（server 285 + bootstrap 23），BUILD SUCCESS；
前端 `vue-tsc` 0 错、`vitest` **121 通过**、`vite build` 成功。

### 11.7 这次的教训（比改动本身更值钱）

「285 条单测全绿」和「能启动」之间隔着三层：**Bean 装配期**、**真实文件系统**、**真实 HTTP 契约**。
这三个问题一个都不在那 285 条里，因为它们全都不靠搭一个 Spring 容器、也不靠真磁盘就能"通过"。

于是补三条规矩：

1. 凡改动 **`@Bean` 装配 / 数据源 / 路径校验**，必须**真起一次服务**才算验过。
   用 `--server.port=0` 起随机端口就不会占开发用的 9090，也不会和正在跑的实例抢；
2. 凡给 entity 加列，**DDL 与代码同批落地**（见 §10.6，那个也是同类问题）；
3. 路径类校验的用例要同时覆盖**裸名**与**带层级**两种形态 ——
   这次就是漏了裸名那一支，代价是"页面点不进去"。
