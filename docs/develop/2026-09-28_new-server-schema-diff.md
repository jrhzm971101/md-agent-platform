# 新服务器库 vs 本机库 表结构核查报告

> 日期：2026-09-28
> 被核查对象：`nlb-280ub5yt9wzy81g1jlj7n3p8a.nlb.ap-southeast-1.bytepluses.com:30306`（下称**新库**，部署目标）
> 基准对象：`106.53.145.158:30306`（下称**本机库**，当前日常使用、结构已验证可用）
> 目标：确保应用部署到新库后**不因表结构报错**

---

## 一、结论（TL;DR）

| 维度 | 结果 |
| --- | --- |
| 库清单 | ✅ 两边完全一致（9 个库） |
| 代码声明的 24 张表 | ✅ 新库全部存在 |
| **列缺口** | 🔴 **发现 2 张表缺 4 列** → 会让应用**直接起不来**（不是接口 500）。**已修复** |
| **索引缺口** | 🔴 缺 `md_agent_run.idx_parent_run`。**已修复** |
| 其余列定义差异 | ⚠️ 6 处，且新库方向**更贴合代码**，无需处理 |
| 数据 | 🔴 **新库几乎是空库** → 结构没问题，但服务起来后页面全空、企微渠道不工作 |

**一句话**：结构缺口已补齐，实测应用对新库可以正常启动、接口 200；**下一步必须决策数据是否迁移**。

---

## 二、核查方法（为什么不能拿两个库直接对）

一开始直觉是「`SHOW CREATE TABLE` 两边对着看」，但这会把结论带偏：

- 新库的**注释比本机库更新**（例：新库 `md_agent_run.source` 的注释已经把 `workflow` 写进去了，本机库还没更新）。
- 新库在某些列上是**更严格**的（`display_name` 是 `NOT NULL`），本机库是宽松的。
- 新库某些表**列更多**（来自不同时期的增量脚本）。

所以「谁是标准」不能靠肉眼比较得出。**唯一权威的基准是代码**，它由两部分共同构成：

1. `md-agent-bootstrap/src/main/resources/db/*.sql` —— 声明 DDL（5 个建表脚本，覆盖 24 张表）
2. `md-agent-server/.../store/entity/*.java` —— MyBatis-Plus 行模型（**这才是运行时真正决定 SQL 的东西**）

> ⚠️ 这里有个必须知道的坑：**MyBatis-Plus 的 SQL 是按实体字段生成的**。
> `selectList()` 会展开成 `SELECT run_id, ..., parent_run_id, task_id ... FROM md_agent_run`。
> 所以"实体里有、库里没有"的列 = 运行时 `Unknown column`，与 DDL 文件写没写无关。
> 本次核查严格要求「代码 schema ∪ Java 实体」都覆盖到。

核查手段：通过 JDBC 直连两个库，把 `information_schema.COLUMNS` / `STATISTICS` / `TABLES` 全量导出为结构化数据后逐项比对（本机无 mysql 客户端，全部走 JDBC）。

---

## 三、差异清单

### 3.1 🔴 会导致启动失败的缺列（已修复）

**为什么是"启动失败"而不是"接口报错"**：`AgentManager` 的 `@PostConstruct` 会调用
`AgentDefinitionResolver.resolveAll()` → `store.loadAllAgentBundles()` → 读 `md_agent_workspace`。
`AgentWorkspaceEntity` 含 `subagentForceSync` / `subagentSyncTimeoutSeconds`，
生成的 SELECT 命中不存在的列 → Spring 容器初始化失败 → **进程起不来**。

| 表 | 缺失列 | 类型 | 语义 |
| --- | --- | --- | --- |
| `md_agent_run` | `parent_run_id` | `VARCHAR(255) DEFAULT NULL` | 派发出这条 run 的父 run；NULL=用户直接发起 |
| `md_agent_run` | `task_id` | `VARCHAR(128) DEFAULT NULL` | 框架 `TaskRepository` 后台任务号；NULL=非后台任务 |
| `md_agent_workspace` | `subagent_force_sync` | `TINYINT(1) DEFAULT NULL` | 子 agent 强制同步执行；NULL=用框架默认（由模型决定） |
| `md_agent_workspace` | `subagent_sync_timeout_seconds` | `INT DEFAULT NULL` | 强制同步的等待秒数；NULL=框架默认 30 |
| `md_agent_run` | 索引 `idx_parent_run(parent_run_id)` | 普通索引 | 服务「从父链路反查它派发的异步子链路」（`WHERE parent_run_id = ?`） |

新库 `md_agent_run` 17 列 / `md_agent_workspace` 32 列，代码要求 19 / 34 列。

**为什么 `idx_parent_run` 是普通索引**：一个父 run 可扇出多个异步子 run，天然一对多，不能做唯一索引。

**修复脚本**：`docs/archive/db-migrations/2026-09/2026-09-28_deploy-schema-precheck.sql`

### 3.2 ⚠️ 其余列定义差异（新库方向更贴合代码，无需处理）

| 表 | 列 | 本机库 | 新库 | 判定 |
| --- | --- | --- | --- | --- |
| `md_agent` | `display_name` | `VARCHAR(255) NULL` | `VARCHAR(255) NOT NULL` | ✅ 新库正确 |
| `md_prompt` | `display_name` | `VARCHAR(255) NULL` | `VARCHAR(255) NOT NULL` | ✅ 新库正确 |
| `md_mcp_server` | `display_name` | `VARCHAR(255) NULL` | `VARCHAR(255) NOT NULL` | ✅ 新库正确 |
| `md_skill_meta` | `display_name` | `VARCHAR(255) NULL` | `VARCHAR(255) NOT NULL` | ✅ 新库正确 |
| `md_channel` | `display_name` | `VARCHAR(128) NULL` | `VARCHAR(128) NOT NULL` | ✅ 新库正确 |
| `md_iam.platform_user` | `role` 默认值 | `DEFAULT 'USER'` | `DEFAULT 'VIEWER'` | ✅ 新库正确 |

判定依据（代码侧证据）：

- `control-plane-schema.sql` / `channel-schema.sql` 里这 5 列**全部声明为 `NOT NULL`**，
  且 `MyBatisControlPlaneStore#pickDisplayName` 专门写了三选一兜底，注释原文：
  *"快照里没有这个字段 —— 直接 insert 会把它写成 NULL（库列是 NOT NULL，直接报错）"*。
  即**代码本来就是按 NOT NULL 语义写的**，本机库反而是漂移的那一方。
- `md-iam` 的 `UserController:48`：`req.role() == null ? "VIEWER" : req.role()` —— 默认值就是 `VIEWER`。
- 这 5 列都是**宽松方向之外**（库更严、代码也按严的写），所以**不会报错**。

> 附带结论：本项目 `md_agent` / `md_prompt` / ... 的 `display_name` 在**本机库上允许 NULL**，
> 属于本机库的历史漂移。无害，但若想彻底对齐，可以单独出一份 ALTER 把本机库改成 NOT NULL。

### 3.3 无害差异（可忽略）

| 对象 | 差异 | 判定 |
| --- | --- | --- |
| `xxl_job.xxl_job_log.job_id` | 新库有 `MUL` 索引，本机库没有 | ✅ 新库更优；且 agent 平台不使用 xxl_job |
| `state_store.rag_agent` / `trading_anomaly_agent` | 新库缺 `version bigint` 列 | ✅ `state_store` 是**已废弃旧库**，代码中无引用 |
| `state_store` 库表数量 | 本机库 10 张，新库 2 张 | ✅ 同上，废弃库 |
| `nacos_config` 库 | 仅索引写法（`USING BTREE`）与顺序不同，索引集合一致 | ✅ Nacos AI 已按既定方案舍弃 |

### 3.4 表级差异：只有"运行时自动建表"这一类

新库比本机库少的表**全部**是框架自动创建的运行时表，无需手工 DDL：

| 库 | 本机库 | 新库 | 说明 |
| --- | --- | --- | --- |
| `md_agent_state` | 19 张（每 agent 一张） | **0 张** | 由框架 `MysqlAgentStateStore` 在首次写入时自动 `CREATE DATABASE` + `CREATE TABLE`。表名 = `md_agent_runtime.state_table` |
| `state_store` | 10 张 | 2 张 | 已废弃旧库，代码无引用 |

---

## 四、已执行的修复

已在新库上执行（纯增量 DDL，无数据风险）：

```
ALTER TABLE `md-agent-platform`.`md_agent_run`
    ADD COLUMN `parent_run_id` VARCHAR(255) DEFAULT NULL ... AFTER `input`,
    ADD COLUMN `task_id`       VARCHAR(128) DEFAULT NULL ... AFTER `parent_run_id`,
    ADD KEY `idx_parent_run` (`parent_run_id`);

ALTER TABLE `md-agent-platform`.`md_agent_workspace`
    ADD COLUMN `subagent_force_sync`           TINYINT(1) DEFAULT NULL ... AFTER `disable_tools_config`,
    ADD COLUMN `subagent_sync_timeout_seconds` INT        DEFAULT NULL ... AFTER `subagent_force_sync`;
```

执行结果自检：

```
OK   ALTER TABLE `md-agent-platform`.`md_agent_run` ...
OK   ALTER TABLE `md-agent-platform`.`md_agent_workspace` ...
--- 列自检 ---
  md_agent_run.parent_run_id              varchar(255) null=YES
  md_agent_run.task_id                    varchar(128) null=YES
  md_agent_workspace.subagent_force_sync           tinyint(1) null=YES
  md_agent_workspace.subagent_sync_timeout_seconds int        null=YES
--- 索引自检 ---
  idx_parent_run(parent_run_id) non_unique=1
```

修复后复核（权威基准 = 代码 schema ∪ 实体）：

- 列：**24 张表 / 379 个代码声明列全部存在，缺列归零**
- 索引：**新库缺失索引 0 项，定义不一致 0 项**
- 表：仅剩上述"框架自动建"的运行时表

---

## 五、实测启动验证（最硬的证据）

把应用**直接指向新库**真实启动一次（独立端口 9191，不碰 9090）：

```
java -jar md-agent-bootstrap-0.0.1-SNAPSHOT.jar \
  --server.port=9191 \
  --spring.datasource.url=jdbc:mysql://nlb-...:30306/md-agent-platform?... \
  --spring.datasource.password=*** \
  --agent-platform.agents.source=db
```

结果：

| 检查点 | 结果 |
| --- | --- |
| 启动 | ✅ `Started MdAgentPlatformApplication in 24.822 seconds` |
| `Unknown column` | ✅ 零出现 |
| Agent 装载 | ✅ `[Agent] 加载完成（source=db），共 0 个: []`（空库不阻塞启动） |
| 渠道装配 | ✅ `[Channel] 全量装配完成：库中 0 个渠道，启用 0 个` |
| `GET /api/agents` | ✅ 200 `[]` |
| `GET /api/overview` | ✅ 200（返回完整指标结构） |
| `GET /api/models` | ✅ 200（读到新库的 `ds-flash` 模型） |
| 对业务表的写入 | ✅ **零写入**（启动前后逐表行数比对完全一致，新库未被污染） |

关键设计确认：`ChannelRuntime#startFromDatabase` 捕获 `RuntimeException` 后只记日志，
**渠道装配失败不会让进程起不来**；`EnvironmentSeeder` 在"库中已有环境目录"时以库为准、不写库。

---

## 六、🔴 遗留问题：数据是空的（比结构问题更实际）

结构已经没问题，但**新库有表无数据**。服务起来后表现是「页面全空、没有 agent 可用」。

### 6.1 必须迁移的数据（否则功能不可用）

| 表 | 本机库 | 新库 | 影响 |
| --- | --- | --- | --- |
| `md_agent` | 4 | **0** | 页面无 agent，网关/渠道找不到落点 |
| `md_agent_ref` | 4 | **0** | agent 引用的 tools / middleware / prompt / skills / mcp 全丢 |
| `md_agent_runtime` | 4 | **0** | REACT 侧运行参数丢失 |
| `md_agent_workspace` | 1 | **0** | HARNESS 工作区配置丢失 |
| `md_agent_sub_agent` | 3 | **0** | HARNESS 子 agent 声明丢失 |
| `md_prompt` | 16 | **0** | 所有提示词丢失 |
| `md_mcp_server` | 4 | **0** | MCP 工具丢失 |
| `md_skill_meta` | 2 | **0** | 技能元数据丢失 |
| `md_resource_version` | 36 | **0** | 草稿/发布历史快照丢失（回滚能力） |
| `md_channel` | 1 | **0** | **企微渠道不工作** |
| `agentscope.agentscope_skills` | 2 | **0** | 技能正文丢失（`git-checkout`、`timesheet-audit`） |
| `md_env_config` | 96 | **0** | 环境配置全空 |
| `md_environment` | 10 | **1**（仅 `cameroon-uat`） | 环境目录只剩 1 个 |
| `md_agent_workspace.agentscope_store` | 80 | **0** | HARNESS 工作区落盘文件丢失 |

### 6.2 建议评估是否迁移（历史/观测数据）

| 表 | 本机库 | 新库 | 说明 |
| --- | --- | --- | --- |
| `md_agent_state.*`（19 张） | 有数据 | 0 张 | agent 的**真实上下文**。不迁 = 服务器上所有历史会话断掉 |
| `md_agent_run` / `md_agent_run_event` | 78 / 3651 | 0 / 0 | 链路观测历史。`trace-retention-days: 30` 会自动清理，可按需只迁近期 |

### 6.3 不需要迁移（两边一致或框架自建）

`md_gateway`（两边都是 `id=1, main_agent_id=null`）、`md_model`（1 行 `ds-flash`）、
`md_env_group`（6 行）、`md_env_group_key`（15 行）、`md_iam.platform`（3 行）、
`md_iam.platform_user`（1 行 `admin/SUPER_ADMIN`）。

---

## 七、上线检查清单

- [x] 新库补齐 4 列 + 1 索引（已执行并验证）
- [x] 应用对新库实测启动成功、接口 200、无 `Unknown column`
- [ ] **决策并执行数据迁移**（§6.1 必迁；§6.2 按需）
- [ ] 迁移后确认 `md_agent` / `md_prompt` 等表行数与预期一致
- [ ] 确认新库 `md_agent_workspace` 库名与配置 `agent-platform.workspace-kv-database` 一致（当前两边都是 `md_agent_workspace` ✅）
- [ ] 确认新库 `md_agent_state` 库名与配置 `agent-platform.state-store-database` 一致（当前两边都是 `md_agent_state` ✅）
- [ ] 可选：清理废弃库 `state_store`（确认后再删）
- [ ] 可选：把本机库 5 个 `display_name` 也改成 `NOT NULL`，消除最后的结构漂移

---

## 附：本次核查用到的判定依据（代码位置）

| 结论 | 依据 |
| --- | --- |
| 缺列会阻塞启动 | `AgentManager#init`（`@PostConstruct`）→ `AgentDefinitionResolver#resolveAll` → `AgentDefinitionResolver:271-272` |
| 实体列会进 SQL | `AgentRunEntity`（`parentRunId` / `taskId`）、`AgentWorkspaceEntity:154,157` |
| `display_name` 应为 NOT NULL | `control-plane-schema.sql:233`、`channel-schema.sql`、`MyBatisControlPlaneStore#pickDisplayName` 注释 |
| IAM 默认角色是 VIEWER | `md-iam/.../controller/UserController.java:48` |
| 渠道装配失败不阻塞启动 | `ChannelRuntime#startFromDatabase`（catch `RuntimeException` 后仅记日志） |
| 环境以库为唯一来源 | `EnvironmentCatalog` 只读数据库；`EnvironmentSeeder` 只补分组/键模板，不导入环境值 |
