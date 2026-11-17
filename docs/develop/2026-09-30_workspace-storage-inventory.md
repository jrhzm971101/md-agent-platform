# 工作区存储清点：哪些在 MySQL、哪些在本地盘

> 日期：2026-09-30
> 范围：`md-agent-server`（装配）、`md-agent-bootstrap`、`agent-contro-plane`（页面）
> 结论依据：真实代码 + 真实库（`106.53.145.158:30306`）+ 真实磁盘，三方交叉核对
> 相关文档：`2026-09-23_harness-workspace-to-mysql.md`（改造方案与选型）

---

## 一、先看结论

**一句话**：工作区文件"进不进库"由该 agent 的 `fs_backend` 决定；**当前 4 个 agent 里只有 `workwx_agent` 声明了 `REMOTE`** ——
它的工作区数据（记忆 / 会话 / 任务 / 事件 / 消息总线）全部落在 MySQL `md_agent_workspace.agentscope_store`；
其余 3 个是 REACT agent，压根没有"工作区"这个概念；本地盘上那些 `harness/*` 目录属于未声明 `REMOTE` 的 LOCAL 模式 agent。

### 1.1 三个存储面（先分清，后面所有结论都挂在这三张表上）

| 存储面 | 配置项 | 物理位置 | 装什么 | 谁写 |
| --- | --- | --- | --- | --- |
| 控制面元数据 | 主数据源 | 库 `md-agent-platform` | agent 定义 / runtime / prompt / skill 元数据 / run 链路 | 页面（CRUD）+ 框架运行期 |
| 工作区文件 KV | `workspace-kv-database` | 库 `md_agent_workspace` → 表 `agentscope_store` | **本次主角**：工作区文件内容 | 框架 `RemoteFilesystem` |
| 会话上下文 state | `state-store-database` | 库 `md_agent_state`（按 agent 分表） | LLM 消息序列（上下文） | 框架 `MysqlAgentStateStore` |

> ⚠️ 命名坑：库里同时存在
> ① 数据库 `md_agent_workspace`（工作区 KV），
> ② 表 `md-agent-platform.md_agent_workspace`（**agent 的工作区配置**，只有 1 行）。
> 两者同名但完全无关。下文的"KV 表"一律指 ①。

### 1.2 总表：工作区里每个路径存在哪

以 `workwx_agent`（唯一 REMOTE，工作区目录 `workspace/workwx_agent`）为例：

| 工作区路径 | REMOTE 模式（现状） | LOCAL 模式（其它 agent） | 由谁写 |
| --- | --- | --- | --- |
| `AGENTS.md` | KV 命名空间 `agents/<agentId>/users/<uid>/root` | `<工作区>/<uid>/AGENTS.md` | 装配期 / 页面 |
| `MEMORY.md` | 同上 `root` | `<工作区>/<uid>/MEMORY.md` | 框架（记忆合并） |
| `tools.json` | 同上 `root` | `<工作区>/<uid>/tools.json` | 装配期（当前无数据） |
| `memory/` | `…/users/<uid>/memory` | `<工作区>/<uid>/memory/` | 框架（记忆固化） |
| `plans/` | `…/users/<uid>/plans` | `<工作区>/<uid>/plans/` | 框架（计划模式） |
| `subagents/` | `…/users/<uid>/subagents` | `<工作区>/<uid>/subagents/` | 页面（可写） |
| `knowledge/` | `…/users/<uid>/knowledge` | `<工作区>/<uid>/knowledge/` | 页面（可写） |
| `agents/<agentId>/sessions/` | `…/users/<uid>/sessions` | `<工作区>/<uid>/agents/<agentId>/sessions/` | 框架（会话快照） |
| `agents/<agentId>/tasks/` | `…/users/<uid>/tasks` | `<工作区>/<uid>/agents/<agentId>/tasks/` | 框架（任务清单） |
| `<tenant>/<agentId>/<sid>/events/` | `<uid>/transcripts`（**自补路由**） | `<工作区>/<tenant>/<agentId>/<sid>/events/` | 框架（事件重放日志） |
| `.agentscope/bus/` | `bus`（**自补路由**） | `<工作区>/.agentscope/bus/` | 框架（消息总线 / 异步工具登记） |
| `code/`（git 仓库） | **始终留本地**（**自补本地路由**） | 本地 | JGit，绕过 filesystem 抽象 |
| `.index/workspace.db` | **始终留本地**（刻意） | 本地 | 框架索引缓存（可重建） |
| `.skills-cache/` | **始终留本地** | 本地 | 本地技能缓存 |
| `skills/` | **已废弃**（不再扫描，页面也不可写） | 遗留目录 | — |

---

## 二、判定规则：只有显式 `REMOTE` 才进库

### 2.1 代码位置

`md-agent-server/src/main/java/com/md/agentplatform/server/agent/ConfigurableAgentBuilder.java`

| 行 | 方法 | 作用 |
| --- | --- | --- |
| 339-359 | `applyFilesystem(builder, def)` | 工作区后端装配 |
| 367-368 | `isRemoteFs(def)` | **只认显式 `REMOTE`**（大小写不敏感） |
| 548-551 | `resolveHarnessWorkspace(def)` | 决定磁盘上的工作区目录名 |
| 283-286 | `applySkillSourcePolicy` | 无条件 `disableDefaultWorkspaceSkills()` |
| 976+ | `resolveSkillRepository(def)` | 技能来源：本地目录存在才用文件，否则走 DB |

判定逻辑（刻意保守）：

```java
private static boolean isRemoteFs(AgentDefinition def) {
    return "REMOTE".equalsIgnoreCase(def.getFsBackend());
}
```

- `REMOTE` → 走 KV（丢 shell 能力，见 3.4）；
- `null` / 空串 / `LOCAL` / **拼错的值** → 一律按 LOCAL 处理。
  这是刻意设计：生产 agent 不会因为一次误输入被悄悄切成"没有 shell"的形态。

### 2.2 工作区目录怎么拼

```java
private String resolveHarnessWorkspace(AgentDefinition def) {
    return (def.getWorkspace() != null && !def.getWorkspace().isBlank())
            ? def.getWorkspace().trim() : "harness/" + def.getName();
}
```

最终目录 = `platformProperties.workspaceRoot` + `resolveHarnessWorkspace(def)`。
默认根是 `workspace`，所以**默认形态是** `workspace/harness/<agentName>`。

> ⚠️ 实测反例：`workwx_agent` 的 `workspace_path` 被显式设成了 `workwx_agent`，
> 所以它的工作区是 **`workspace/workwx_agent`**（顶层），**不是** `workspace/harness/workwx_agent`。
> 排查问题时按 `harness/<name>` 去找会找不到人。

### 2.3 现状：4 个 agent 各自的后端

实测（`md-agent-platform` 库，2026-09-30）：

| agent_name | display_name | agent_type | 状态 | `fs_backend` | 工作区目录 |
| --- | --- | --- | --- | --- | --- |
| `workwx_agent` | 企微助手 | **HARNESS** | PUBLISHED v3 | **REMOTE** | `workspace/workwx_agent` |
| `workwx_contact_agent` | 企微通讯录助手 | REACT | PUBLISHED v1 | —（无工作区） | — |
| `workwx_doc_agent` | 企微文档助手 | REACT | PUBLISHED v2 | —（无工作区） | — |
| `workwx_notify_agent` | 企微消息推送助手 | REACT | PUBLISHED v3 | —（无工作区） | — |

- `fs_backend` / `workspace_path` 存在表 `md-agent-platform.md_agent_workspace`，**全表只有 1 行**（就是 `workwx_agent`）。
- REACT agent 没有工作区，不参与本节讨论。
- 结论：**当前生产只有 `workwx_agent` 一个 agent 的工作区进了 MySQL。**

---

## 三、进 MySQL 的部分

### 3.1 物理结构

```sql
CREATE TABLE `agentscope_store` (
  `namespace_path` varchar(512) NOT NULL,   -- 命名空间，多段用 U+001F 分隔，且以 U+001F 结尾
  `item_key`       varchar(255) NOT NULL,   -- 路由前缀剥离后的相对路径，以 / 开头
  `value_json`     longtext    NOT NULL,    -- 文件内容（BASE64/JSON 包装）
  `version`        bigint      NOT NULL,    -- 乐观锁版本号，每次写 +1
  `updated_at`     bigint      NOT NULL,    -- 毫秒时间戳
  PRIMARY KEY (`namespace_path`,`item_key`),
  KEY `idx_namespace` (`namespace_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- 库名来自 `agent-platform.workspace-kv-database = md_agent_workspace`；
- 表名 `agentscope_store` 是**方言写死的**（`JdbcStore` 的表名前缀），改不了；
- 建库由 `ControlPlaneStoreConfig#workspaceKvDataSource` 自动 `CREATE DATABASE IF NOT EXISTS`，
  表由 `JdbcStore#initializeSchema(true)` 自动建，**不需要手写 DDL**；
- 复合主键 512+255 字符 × 4 字节 = 3068 < 3072（InnoDB utf8mb4 索引上限），**不能再加长 `namespace_path`**。

### 3.2 路由表：工作区路径 → KV 命名空间

**内置路由**（官方 `RemoteFilesystemSpec.toFilesystem()`，10 个固定前缀）：

| 工作区路径 | 路由段 | 完整命名空间 |
| --- | --- | --- |
| `AGENTS.md` / `MEMORY.md` / `tools.json` | `root` | `agents/<agentId>/users/<uid>/root` |
| `memory/` | `memory` | `…/users/<uid>/memory` |
| `skills/` | `skills` | `…/users/<uid>/skills`（**本项目已关闭**） |
| `subagents/` | `subagents` | `…/users/<uid>/subagents` |
| `knowledge/` | `knowledge` | `…/users/<uid>/knowledge` |
| `plans/` | `plans` | `…/users/<uid>/plans` |
| `agents/<agentId>/sessions/` | `sessions` | `…/users/<uid>/sessions` |
| `agents/<agentId>/tasks/` | `tasks` | `…/users/<uid>/tasks` |

其中 `<uid>` 由 `IsolationScope.USER` 决定：取 `RuntimeContext.getUserId()`，
为空则退回 `sessionId`，再为空则用 `anonymousUserId`（默认 `_default`）。

**本项目额外补的 3 条路由**（`ConfigurableAgentBuilder:351-357`）—— 不补会"一半在库、一半在盘"：

| 工作区路径 | 路由段 | 命名空间 | 为什么必须补 |
| --- | --- | --- | --- |
| `default/`（tenant 前缀） | `transcripts` | `<uid>/transcripts` | 事件重放日志 key 首段是 **tenant**（`TranscriptRef` 兜底成 `default`），匹配不上内置的 `agents/<id>/sessions/` |
| `.agentscope/bus/` | `bus` | `bus` | `WorkspaceMessageBus` 的 busRoot 写死为 `.agentscope/bus`，不在内置路由里 |
| `code/` | — | **留本地** | JGit 直接操作 `<repo>/.git`，**不经过** filesystem 抽象；不补这条 git 工具会读本地、agent 写 KV，两边视图分裂 |

> 实测印证：`bus` 命名空间只有 1 段（没有 `<uid>` 前缀），因为消息总线的写入路径上下文里没有 `userId`；
> `transcripts` 是 2 段（`<uid>/transcripts`）—— 两者形状不同不是 bug，是上下文差异。

### 3.3 Overlay 语义：本地模板作基线 + copy-on-write

每条路由的后端是 `OverlayFilesystem(upper=RemoteFilesystem, lower=只读 LocalFilesystem)`：

- **lower** = 磁盘上的模板目录（如 `workspace/memory`），只读，作为基线；
- **upper** = 按用户隔离的 KV 命名空间；
- 读：upper 没有就落回 lower → **首次读取能看到模板内容**；
- 写：只写 upper（KV）→ 本地模板永不被改。

**结论**：即使进了库，磁盘上的模板目录仍然"可见"，但**不是真相源**。
判断一个文件到底在哪，看的是"最新一次写发生在哪"。
### 3.4 REMOTE 的代价：没有 shell

`RemoteFilesystemSpec` 生成的是 `CompositeFilesystem`，**不是** `AbstractSandboxFilesystem`。
框架据此判定"没有沙箱"，于是**不注册 shell 执行工具**（官方单测
`RemoteFilesystemSpecTest#compositeModeIsNotASandboxFilesystem` 把这条写成了不变量）。

含义：

- 声明 `REMOTE` 的 harness agent **不能执行 shell 命令**；
- 需要 shell 的 agent 只能选 LOCAL，或改用沙箱 spec（本项目未采用）；
- 这是框架设计使然，不是本项目配置问题。

### 3.5 库里现在到底有什么（2026-09-30 实测）

库 `md_agent_workspace` 共 3 张表，其中 `agentscope_sessions` / `agentscope_snapshots` **都是 0 行**（框架建了但本项目没用）：

| 表 | 行数 | 说明 |
| --- | --- | --- |
| `agentscope_store` | **80 条 / 18 个命名空间 / 约 1.02 MB** | 工作区文件内容（唯一在用） |
| `agentscope_sessions` | 0 | 未使用 |
| `agentscope_snapshots` | 0 | 未使用 |

18 个命名空间的完整分布：

| 命名空间 | 条目数 | 对应工作区位置 |
| --- | --- | --- |
| `agents/workwx_agent/users/console/root/` | 1 | `MEMORY.md`（6758 B，v4） |
| `agents/workwx_agent/users/console/memory/` | 2 | `2026-09-28.md`（21850 B，v14）+ `.consolidation_state` |
| `agents/workwx_agent/users/console/sessions/` | 5 | 2 个会话 × (`.jsonl` + `.log.jsonl`) + `sessions.json` |
| `agents/workwx_agent/users/console/tasks/` | 1 | `console-7215a0de.json`（53010 B，**v98**） |
| `console/transcripts/` | 12 | `workwx_agent/<sid>/events/*.jsonl`（事件重放日志） |
| `bus/` | 26 | `queues/agentscope_inbox_*` / `agentscope_wakeups_*` |
| `agents/test_harness_agent/users/{console,jinran,codex-verify}/*` | 26 | 测试 agent 的 root/memory/sessions/tasks |
| `{codex-verify,jinran}/transcripts/` | 5 | 测试与工作流的 events |
| `coordination/periodic/` | 2 | `task-sweep:<agent>` 定时清扫游标 |

**可用的"是否还在写"证据**：

- `coordination/periodic/task-sweep:workwx_agent` 的 `updated_at` = **2026-09-30 18:33（今天）**，version 58
  → 服务现在确实在写 KV，REMOTE 链路是活的；
- `agents/workwx_agent/users/console/tasks/console-7215a0de.json` = **v98**（版本号一直在涨），
  而磁盘上同名文件停留在 09-28 → 印证"写入只落 KV，磁盘是残留"。

**典型命名空间样例**（`workwx_agent` 的会话与事件）：

```
agents/workwx_agent/users/console/sessions/
    /console-7215a0de.jsonl        149685 B  v9
    /console-7215a0de.log.jsonl    149691 B  v9
    /console-73a8196f.jsonl        118532 B  v4
    /console-73a8196f.log.jsonl    118532 B  v4
    /sessions.json                    505 B  v13

console/transcripts/
    /workwx_agent/console-7215a0de/events/0-32-b7e843d6.jsonl      20363 B
    /workwx_agent/console-7215a0de/events/122-123-f1eb434a.jsonl    7931 B
    /workwx_agent/console-73a8196f/events/98-107-6f7d6cc2.jsonl    18319 B
```
---

## 四、留在本地盘的部分

### 4.1 四类硬缺口（**无法**进 MySQL）

| 项 | 磁盘位置 | 为什么必须留本地 |
| --- | --- | --- |
| git 仓库工作树 | `workspace/code/` | JGit 直接读写 `<repo>/.git`，**绕过** filesystem 抽象。agent 在 KV 改文件、git 工具在本地读 `.git`，一旦分离两边视图立即分裂 |
| shell 执行 | —（能力，不是文件） | REMOTE 是 `CompositeFilesystem`，框架判定无沙箱 → 不注册 shell 工具。要 shell 只能 LOCAL 或沙箱 spec |
| 工作区索引缓存 | `<工作区>/.index/workspace.db` | 可由 KV 内容重建的**派生数据**，刻意不占 KV；`ControlPlaneStoreConfig` 注释写明 |
| 本地技能缓存 | `<工作区>/.skills-cache/` | 技能加载期缓存，非真相源（真相源在 `agentscope` 库） |

### 4.2 项目数据目录（**不是** agent 工作区）

`workspace/` 下这几项和"agent 工作区"无关，是平台自身的资源目录，**永远不会进 KV**：

| 路径 | 内容 | 说明 |
| --- | --- | --- |
| `workspace/agents/` | 14 个 `*.md` | agent 定义文档（本地来源） |
| `workspace/code/` | git 仓库 | 上述 git 硬缺口，也是 `code/` 路由的根 |
| `workspace/docs/` | 文档 | 项目级 |
| `workspace/datasource/` | 数据源 | 项目级 |
| `workspace/single/` | — | 项目级 |

### 4.3 LOCAL 模式 agent 的整个工作区都在盘上

只有声明 `REMOTE` 才进库，所以这些目录**整份都是本地文件**：

| 目录 | 内容 |
| --- | --- |
| `workspace/harness/deployment_agent/` | `AGENTS.md`、`.agentscope/`、`agents/`、`console/`、`wo7U1ZEQAApziTCwT6jow9UKLs0xrqlw/`（用户目录） |
| `workspace/harness/diagnosis_agent/` | `AGENTS.md`、`agents/`、`wo7U1…/` |
| `workspace/harness/workwx_batch_agent/` | 上述 + `.skills-cache/` + **`skills/batch-audit-and-notify/SKILL.md`** |

> ⚠️ `workwx_batch_agent/skills/` 是**历史遗留**。装配期已无条件 `disableDefaultWorkspaceSkills()`，
> 框架不再扫描它，`warnIfWorkspaceSkillsPresent` 会在启动时告警。它不生效，但留着会让人误以为"技能在这里维护"，
> 建议直接删掉（技能一律走「技能」页 → `agentscope` 库）。

### 4.4 `workwx_agent` / `test_harness_agent` 的本地残留

这两个 agent 早已切到 REMOTE，但磁盘上还留着切换**之前**写下的文件：

| 目录 | 磁盘内容 | 判定 |
| --- | --- | --- |
| `workspace/workwx_agent/.index/workspace.db` | 1 个文件，20480 B | **活的**（框架索引缓存，刻意留本地） |
| `workspace/workwx_agent/.skills-cache/` | 空目录 | 残留 |
| `workspace/workwx_agent/console/` | 12 个文件（`memory/`、`agents/*/sessions/*.jsonl`、`audit-results/`、`large_tool_results/`、`*/events/*.jsonl`） | **残留，已不再更新** |
| `workspace/test_harness_agent/.index/` | 1 个文件 | 活的 |
| `workspace/test_harness_agent/{console,codex-verify,jinran}/` | 14 个文件 | 残留 |

**判定依据（时间戳）**：`workwx_agent/console/**` 全部文件的最新 mtime = **2026-09-28 16:58**，
而库里同名对象的 version 已涨到 v98 / v14、`updated_at` 到 **09-30 18:33**。
**磁盘停留在切换那天，KV 一直在涨** → 磁盘那份是死数据。

对照示例（同名不同命）：

| 文件 | 磁盘 | KV |
| --- | --- | --- |
| `agents/workwx_agent/sessions/console-7215a0de.jsonl` | 142607 B，**09-28 16:58** | 149685 B，**v9**，09-28 16:58 |
| `memory/2026-09-28.md` | 1228 B，09-28 16:09 | 21850 B，**v14**，09-28 16:58 |
| `tasks/console-7215a0de.json` | 无 | 53010 B，**v98**，09-28 16:52 |

补充两点：

- `console/large_tool_results/`（工具返回值过大时的本地落盘）在 KV 里**没有**对应条目 —— 框架刻意留本地，不进库；
- 磁盘上的 `console/default/general-purpose-subagent/.../events/*.jsonl`（子 agent 事件）在 KV 里也**找不到**同名 key；
  而 `console/transcripts/` 里的 key 首段是 **agentId**（`/workwx_agent/...`），说明子 agent 的 events 走的是另一条
  tenant 前缀。这部分是补 `transcripts` 路由**之前**（或子 agent 上下文缺 userId 时）的落盘遗留，
  需要的话可单独排查，不影响本次结论。
---

## 五、页面的维护边界

页面（`agent-contro-plane` → `/api/workspace/*`）能读写什么，由
`md-agent-server/.../server/workspace/WorkspacePathGuard.java` 三档白名单决定：

| 档 | 内容 | 读 | 写 | 删 |
| --- | --- | --- | --- | --- |
| `MANAGED_ROOT_FILES` | `AGENTS.md`、`MEMORY.md`、`tools.json` | ✅ | ✅ | ✅ |
| `MANAGED_ROOT_DIRS` | `subagents/`、`knowledge/` | ✅ | ✅ | ✅ |
| `READONLY_ROOT_DIRS` | `memory/`、`plans/`、`agents/`、`skills/` | ✅ | ❌ | ❌ |

设计取舍（代码注释已写明）：

- **`skills/` 不可写**：技能的唯一维护入口是「技能」页（内容在 `agentscope` 库）。
  工作区 `skills/` 已废弃，放开写等于把"两份技能哪份生效"重新制造出来；
- **`memory/` / `agents/` 不可写**：写入者是框架，页面改完会被下一次写入静默覆盖，
  表现成"保存成功了但没生效"，所以归到只读；
- **只读 ≠ 可清理**：删 `memory/` 这类运行时产物是「会话记录 / 状态存储」两页的职责，
  文件管理页再开一个入口只会让人分不清该在哪一页动手。

其余校验：路径段字符白名单（**不含 `:`**，挡 NTFS 备用数据流）、Windows 保留设备名、
单段 ≤ 120 字符、≤ 12 段、`normalize()` 后必须仍在工作区内、`userId` 匹配 `[A-Za-z0-9_\-]{1,64}`。

**重要实现细节**：`WorkspaceAdminService` 全部走框架的 `AbstractFilesystem` 接口，
所以它**不需要知道** `fs_backend` 是什么，也**不复刻** 3.2 的路由表 ——
路由由装配期决定，页面只对着"工作区相对路径"操作。这就是为什么同一个页面能同时管 REMOTE 与 LOCAL 的 agent。

---

## 六、与另外两个存储面的关系

三者**刻意分库**，因为数据形态完全不同：

| | 工作区文件 KV | 会话上下文 state | 技能内容 | 控制面元数据 |
| --- | --- | --- | --- | --- |
| 库 | `md_agent_workspace` | `md_agent_state` | `agentscope` | `md-agent-platform` |
| 表 | `agentscope_store` | **按 agent 分表** | `agentscope_skills` / `agentscope_skill_resources` | `md_agent_workspace` / `md_agent_ref` / `md_skill_meta` … |
| 行数 | 80 | 每表 1~14 | 2 / 1 | ~4 |
| 单条体积 | 中（KB 级） | **大**（会话全量 jsonl） | 中 | 小 |
| 写入频率 | 高（每次工具调用/记忆） | 高 | 低（页面发布时） | 低 |
| 真相源 | KV | `md_agent_state` | `agentscope` | `md-agent-platform` |
| 谁写 | 框架 `RemoteFilesystem` | 框架 `MysqlAgentStateStore` | 页面发布 | 页面 CRUD |

为什么要分：

- **state 按 agent 分表**：`state_table` 是显式配置项，一张表一个 agent，
  避免多 agent 共用 `(sessionId, userId)` 导致上下文串味（详见 `Md-Agent-Platform` 相关改造记录）；
- **技能内容独立到 `agentscope` 库**：由官方 `MysqlSkillRepository` 直接读写，
  框架启动时按 `skillNames` 拉取，与 `md_skill_meta`（页面维护的元数据）**职责分离**：
  元数据管"有哪些技能、什么状态"，内容管"技能正文"；
- **工作区 KV 单独成库**：写入频率最高、单条体积中等、需要按命名空间做前缀检索，
  与前两者放一起会互相影响（备份、扩容、慢查询）。

> 注意 `fs_backend=REMOTE` **只影响工作区文件**，与 `state_store` 无关。
> `workwx_agent` 的 state 依然在 `md_agent_state.workwx_agent` 表里（3 行）。

---

## 七、已知缺口与建议

| # | 缺口 | 影响 | 建议 |
| --- | --- | --- | --- |
| 1 | REMOTE 无 shell | harness agent 无法跑命令、无法 `git` | 明确接受；需要 shell 的 agent 保持 LOCAL；后续若要两全需上沙箱 spec |
| 2 | `code/` 永远留本地 | REMOTE agent 的 git 仓库仍是单机文件，多副本不共享 | 明确接受（JGit 限制）；多副本部署时把 `workspace/code` 挂共享卷 |
| 3 | `workwx_agent/console/` 磁盘残留（09-28） | 排查时容易"读到旧内容"，误判 | 确认无用后删除该目录；**保留 `.index/`** |
| 4 | `workwx_batch_agent/skills/batch-audit-and-notify/` 遗留 | 启动告警，且暗示"技能可放工作区" | 删除该目录 |
| 5 | `agentscope_sessions` / `agentscope_snapshots` 两张空表 | 无影响 | 保留（框架建表，删了会重建） |
| 6 | 子 agent events 在 KV 无对应 key | 子 agent 事件重放可能落本地 | 单独排查子 agent 的 `TranscriptRef.tenant` 与上下文中 `userId` 是否缺失 |
| 7 | `.index/workspace.db` 仍本地 | 多副本各自建索引，不共享 | 明确接受（派生数据）；如需共享需改框架 |

---

## 附：核对方法（可复现）

1. **配置**：`md-agent-bootstrap/src/main/resources/application.yml` 的
   `agent-platform.workspace-root / control-plane-skill-database / state-store-database / workspace-kv-database`
2. **库**：连 `106.53.145.158:30306`，查
   `information_schema.schemata`、`md_agent_workspace.agentscope_store`（`namespace_path` 用 `SELECT DISTINCT` 看分隔符）、
   `md-agent-platform.md_agent_workspace.fs_backend`
3. **盘**：`Get-ChildItem workspace -Recurse -File | Sort LastWriteTime -Descending`
   → 最新 mtime 集中在 09-28，而 KV `updated_at` 到 09-30
4. **代码**：`ConfigurableAgentBuilder#applyFilesystem / isRemoteFs / resolveHarnessWorkspace`，
   `RemoteFilesystemSpec#toFilesystem`（官方），`IsolationScope#toNamespaceFactory`（官方）