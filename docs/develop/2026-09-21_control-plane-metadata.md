# 控制面元数据：Agent / Prompt / Skill / MCP 从 Nacos 迁入 MySQL

> 记录时间：2026-09-21
> 涉及仓库：后端 `D:\jinran\code\Md-Agent-Platform`（主）+ 前端 `D:\jinran\code\agent-contro-plane`
> 覆盖范围：数据模型定稿（表 / 字段 / 版本快照）、装配链路改造、一次性迁移方案、接口约定
> 状态：**设计定稿（Step 0）**，尚未落代码。实现见 Step 1~7（本文第 9 节）
> 组织方式：结论 → 背景 → 决策 → 架构 → 数据模型 → 字段映射 → 接口 → 迁移 → 取舍 → 验证 → 缺口
>
> **2026-09-22 补充**：本文第 6 条决策（「五类资源共用一套版本机制」）中的第 5 类
> —— **渠道（`ResourceType.CHANNEL`）** —— 的落库与装配已在
> `docs/develop/2026-09-22_channel-and-gateway-control-plane.md` 中定稿并实现，
> 新增 `md_channel` / `md_channel_binding` / `md_gateway` 三张表。
> 渠道与其它四类的唯一流程差异是「发布即热生效」，其余（草稿 / 发布 / 快照 / 回滚 / 引用保护）完全一致。

---

## 0. 结论速览

1. **运行时数据源从三套收敛为一套**：现状是「Nacos AgentSpec + 本地 `agents/*.md` + 本地 `agent-definitions/*.json`」三套并存，本次全部收敛到 **MySQL 单源**，Nacos AI 整体弃用。
2. **元数据分两种性质**，不能一刀切：
   - **可管理资源**（prompt / skill / mcp）= 内容是数据 → 进 DB，页面增删改查；
   - **代码资产**（tool / middleware）= 内容是 Spring bean → **不落库**，页面只有只读目录，agent 页面上是下拉多选。
3. **agent 是编排产物**：它自身不存任何正文，只存「引用 + 旋钮」。页面上 `prompt-key / tools / middleware / skill-names / mcp-names / sub-agents` 全部是下拉，**不允许自由输入**。
4. **skill 不造轮子**：内容直接用官方 `io.agentscope:agentscope-extensions-skill-mysql-repository:2.0.3`（自带建库建表）；我们只加一张 `md_skill_meta` 放官方表没有的治理元数据（业务标签 / 草稿发布 / 版本）。
5. **agent 配置面按语义域拆表**：`md_agent` 主表 + 5 张关联表 + 1 张子 agent 表，覆盖 ReAct 与 Harness 两个 Builder 的**全部可设置项**（含上下文压缩、记忆、工具结果驱逐、子 agent 声明），不是拍脑袋凑的字段。
6. **草稿 / 发布两态 + 统一快照表** `md_resource_version`：发布即落快照，回滚 = 快照写回主表并置回草稿。五类资源共用一套版本机制。
7. **存量数据靠一次性导入工具搬运**，不做运行时兜底 —— 切库后唯一数据源就是 DB，DB 空就是空。

---

## 1. 背景与目标

### 1.1 现状：同一个概念有三套真相

| 来源 | 载体 | 谁在用 | 问题 |
| --- | --- | --- | --- |
| Nacos AgentSpec | 远端配置中心 | `NacosAgentSpecRepository` 全量加载（`source=nacos`） | 页面改不了，改配置要登 Nacos 控制台 |
| 本地 MD | `workspace/agents/*.md`（frontmatter + 正文） | `AgentDefinitionMdLoader`（`source=local`，**当前生效值**） | 正文即 `sysPrompt`，改完要重启；skills/mcp 还要另去 Nacos 拉 |
| 本地 JSON | `workspace/agent-definitions/{name}.json` | `AgentDefinitionStore`，`AgentController` 的 CRUD 只写这里 | 名字很误导（看着像"权威定义库"），实际只是一份旁路持久化 |

配套还有三处"按名从 Nacos 拉"的装配代码：prompt（`prompt-key`）、skill 包（`skill-names` → `NacosSkillRepository`）、MCP（`mcp-names` → `NacosMcpClientFactory`）。**结果就是：想改一个 agent 的提示词，要同时知道改哪套数据源生效**。

### 1.2 要解决什么

- 把 agent / prompt / skill / mcp 的**编辑权收回到自己的控制面页面**，彻底不依赖 Nacos 控制台。
- 装配期数据来源**只有一个**，消除"改了不生效"的歧义。
- 引入**草稿 / 发布 / 回滚**，让"改了线上提示词导致行为变化"这件事可追溯、可撤销。
- 为后续「执行链路观测 ↔ 当时用的是哪一版定义」留出关联能力（现在改了 prompt，历史 run 无法复现当时用的版本）。

### 1.3 验收标准

| 项 | 标准 |
| --- | --- |
| 单源 | 启动日志里 agent 全部来自 DB；Nacos 相关类不再出现在装配路径上 |
| 等价 | 导入后 14 个 agent 的名称 / 类型 / 工具 / 子 agent 关系与导入前**完全一致**，装配无异常 |
| 可编辑 | 页面上能改 prompt 正文、skill 内容、mcp 端点，保存后**不重启**即生效 |
| 可回滚 | 任意资源能回到历史任一版本 |
| 不打断 | 存量企微 / 飞书通道、页面对话、链路观测三块功能不受影响 |
| 引用完整 | agent 引用了不存在的 prompt/skill/mcp 时，保存被拒绝并明确报错；删除被引用的资源被拒绝并列出使用方 |

---

## 2. 决策记录

| 编号 | 决策 | 取舍 |
| --- | --- | --- |
| D1 | 运行时**只读 MySQL**，不回落 Nacos | 迁移期没有双保险；换来来源唯一、行为可预期 |
| D2 | 存量数据用**一次性导入工具**搬运，不做运行时兜底 | 导入脚本要单独写一次；换来装配路径干净 |
| D3 | tool / middleware **不落库**，只提供只读目录 | 这两类的目录不能手工维护；但它们本来就是代码资产（`@Tool` 注解的 bean、`MiddlewareBase` 的 bean），落库只会制造"库里有一条但容器里没有"的假象 |
| D4 | skill 内容用官方 `MysqlSkillRepository`，**不自己写存储** | 官方表没有草稿/发布/标签；换来零自研 + 官方后续演进自动受益 |
| D5 | 另建 `md_skill_meta` 存 skill 的治理元数据 | 治理视图与内容分两张表（内容永远以官方表为准，不存在两处真相）；换来 skill 也能草稿/发布/回滚，与另外三类资源体验一致 |
| D6 | agent 配置按**语义域**拆成 1 主表 + 5 关联表 + 1 子表，不做大宽表 | 装配时要多读几张表；换来每张表职责单一、加字段不影响其他域，且页面表单天然按域分区 |
| D7 | 嵌套小配置（`prune` / `truncateArgs` / `flushTrigger` / `executionConfig` / `filesystemSpec`）用 **JSON 列**而非再开表 | 失去按子字段查询的能力；换来少 5 张表和 5 次 join —— 这些子配置没有单独查询需求 |
| D8 | 草稿 / 发布**两态**，不做官方那套 editing / reviewing / online 三段式 + 审批 | 没有审批流；换来流程简单，个人/小团队使用足够 |
| D9 | 版本快照**五类资源共用一张表** `md_resource_version` | 单表数据量大时需按 `resource_type` 分区/清理；换来一套回滚逻辑服务五类资源 |
| D10 | `enable-tools` / `disable-tools` 从 **agent 级下沉到 MCP 条目级** | 是语义变更，迁移时要归位；换来"同一个 MCP 在不同 agent 下白名单不同"这件事写在该 agent 的引用条目上，而不是散在 agent 上 |
| D11 | Nacos 相关类**先保留不删**，只退出调用路径 | 短期留死代码；换来切库出问题时可快速回退，Step 7 再清理 |
| D12 | MCP 表按官方 `McpServerConfig` **全字段**建（含 stdio 的 `command/args/env`） | 有暂时用不上的列；换来将来支持本地 stdio MCP 时不用改表结构。运行时仍按现有逻辑拒绝 stdio |
| D13 | skill 内容接受落在**独立的 `agentscope` 库**（跨 schema），不强行并进业务库 | 备份/权限/迁移要多带一个 schema；换来能用官方仓库（原方案"指到现有库"实测被官方库名校验拒绝，见 §4.3.1） |
| D14 | `md_skill_meta.description` / `source` / `skill_content` 的 NOT NULL 约束由写入方保证 | 页面必须给描述、后端补 `source`；换来不触发官方表的 NOT NULL 报错 |
| D15 | 保存 / 回滚一律用**事务内先删后插**（整体替换），只有"发布"这种只改状态的动作用 `updateById` | 每次保存多一次 DELETE、子表自增 id 会涨；换来"提交什么就存什么" —— MyBatis-Plus 的 `updateById` 默认只更新非 null 字段，会把"清空描述"这类操作静默忽略（页面显示清空了、库里还留着） |
| D16 | 删除资源时**一并删除它的版本快照** | 审计历史随之丢失；换来不会出现"同名重建后看到上一世的版本历史、点回滚能把旧内容复活"。若将来要审计，应做不可删的审计表或改软删，而不是让回滚语义夹带审计语义 |

---

## 3. 目标架构

### 3.1 装配链路（改造后）

```
                    ┌─────────────────────────────────────────┐
   控制面页面  ──►  │  Controller（bootstrap）                │
                    │  /api/resources/{prompts|skills|mcp}    │
                    │  /api/agents                            │
                    └───────────────┬─────────────────────────┘
                                    │ 写
                    ┌───────────────▼─────────────────────────┐
                    │  ControlPlaneStore（server，@Transactional）
                    │  MySQL：md_agent / md_prompt / md_mcp_server
                    │         md_skill_meta / md_resource_version
                    │         官方 agentscope_skills（skill 内容）
                    └───────────────┬─────────────────────────┘
                                    │ 读（装配期）
                    ┌───────────────▼─────────────────────────┐
                    │  AgentDefinitionResolver  ← 唯一解析入口 │
                    │  1. md_agent(+5 关联表) → AgentDefinition │
                    │  2. md_prompt.prompt_key → sysPrompt     │
                    │  3. skill-names → MysqlSkillRepository   │
                    │  4. mcp-names  → DbMcpClientFactory      │
                    └───────────────┬─────────────────────────┘
                                    │
                    ┌───────────────▼─────────────────────────┐
                    │  ConfigurableAgentBuilder（不动）        │
                    │  ReActAgent / HarnessAgent               │
                    └───────────────┬─────────────────────────┘
                                    │
                            AgentManager（内存注册表，不动）

   只读目录（不落库，扫容器）：
     tool        → AgentManager.listAvailableTools()     扫 @Tool 注解的 bean
     middleware  → MiddlewareCatalog                     扫 MiddlewareBase 的 bean
```

### 3.2 装配期只有四个接入点需要改

| 项 | 现有实现 | 改造后 | 说明 |
| --- | --- | --- | --- |
| prompt | `def.getSysPrompt()` 由 loader 填 | `AgentDefinitionResolver` 从 `md_prompt.body` 填入 | 框架侧 `builder.sysPrompt(...)` 不动 |
| skill | `NacosSkillRepository` / `FileSystemSkillRepository` | `MysqlSkillRepository` | `builder.skillRepository(repo)` 不动，ReAct 与 Harness 两条链都支持 |
| mcp | `NacosMcpClientFactory` | `DbMcpClientFactory` | 照现有实现改造数据来源：从 Nacos `getMcpServer` 换成读 `md_mcp_server` 一行 |
| agent | `NacosAgentSpecRepository` / `AgentDefinitionMdLoader` / `AgentDefinitionStore` | `ControlPlaneStore.loadDefinition()` | `AgentManager.init()` 的加载来源换掉 |

**框架侧一行都不用改** —— 这是本次改造最重要的可行性依据：所有数据源替换都发生在 `AgentDefinition` 组装阶段，`ConfigurableAgentBuilder` 拿到的仍然是一个 `AgentDefinition`。

### 3.3 harness 的 workspace 仍然必须落盘

`HarnessAgent.Builder.workspace(Path)` 是框架**强制的本地路径**，DB 替代不了（框架要在该目录下读写 `AGENTS.md`、计划文件、会话产物）。所以：

- **保留**：harness 的 workspace 目录、`environment-memory` 目录、`plan-file-directory`；
- **进 DB**：skills（框架侧走 `skillRepository`，不再扫 workspace 的 skills 目录）；
- 即：**workspace 里只留"必须有实体文件才能工作"的东西，其余全部进 DB**。


---

## 4. 数据模型

### 4.1 表清单

| 表 | 职责 | 与谁关联 |
| --- | --- | --- |
| `md_agent` | agent 身份 + 生命周期 | PK `agent_name` |
| `md_agent_ref` | **引用**（页面全是下拉） | ← `md_agent` |
| `md_agent_runtime` | 通用运行时旋钮 + 模型 + REACT 专有项 | ← `md_agent` |
| `md_agent_memory` | **上下文压缩 + 记忆 + 工具结果驱逐** | ← `md_agent` |
| `md_agent_workspace` | **纯 HARNESS 表**：工作区 + disableXxx 开关（2026-09-22 起不再有 REACT 行） | ← `md_agent` |
| `md_agent_sub_agent` | 子 agent 声明（一对多） | ← `md_agent` |
| `md_prompt` | prompt 正文 | 被 `md_agent_ref.prompt_key` 引用 |
| `md_mcp_server` | MCP 端点与工具白/黑名单 | 被 `md_agent_ref.mcp_names` 引用 |
| `md_skill_meta` | skill 治理元数据（内容在官方表） | 被 `md_agent_ref.skill_names` 引用 |
| `md_resource_version` | 五类资源的版本快照 | `(resource_type, resource_name)` |
| `agentscope_skills` | **skill 内容**（官方建，不碰；在 **另一个 schema `agentscope`** 里） | `name` |
| `agentscope_skill_resources` | **skill 附属文件**（官方建，不碰；同上） | `(id, resource_path)` 联合主键 |

### 4.2 DDL

> 交付物：`md-agent-bootstrap/src/main/resources/db/control-plane-schema.sql`（手工执行，与 `trace-schema.sql` 一致的做法，不做自动建表）
>
> ⚠️ **2026-09-22 起本节的 DDL 已不是最新**：`md_agent` / `md_prompt` / `md_mcp_server`
> 各新增了一列 `display_name`，且 `md_skill_meta` / `md_channel` 的 `display_name`
> 由选填收紧为 **NOT NULL 必填**。下面这段保留成当时的设计记录，**要以脚本文件为准**
> （原委见 `2026-09-22_architecture-overview.md` §2.1 约定 4）。

```sql
-- ============================================================
-- 控制面元数据：agent / prompt / mcp / skill 治理元数据 + 版本快照
-- 手工执行：mysql -h... -u root -p md-agent-platform < control-plane-schema.sql
-- 注意：MyBatis-Plus 映射 JSON 列必须 @TableName(autoResultMap = true)，
--       否则会出现能写进去、读出来是 null 的经典问题（见 MyBatisTraceStore 的注释）。
-- ============================================================

-- ---------- 1. agent 主表：身份 + 生命周期 ----------
CREATE TABLE IF NOT EXISTS `md_agent` (
  `agent_name`        VARCHAR(128)  NOT NULL                COMMENT 'Agent 唯一名（运行时注册键，也是页面主键）',
  `agent_type`        VARCHAR(16)   NOT NULL DEFAULT 'REACT' COMMENT 'REACT | HARNESS（取代原来的 kind 冗余列，由它推导）',
  `description`       VARCHAR(1024)     NULL                COMMENT '描述，装配时进 builder.description()',
  `biz_tags`          JSON              NULL                COMMENT '业务标签，仅控制面用（分组/筛选），不进装配',
  `category`          VARCHAR(64)       NULL                COMMENT '能力分组（页面筛选用）',
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
  `version`           INT           NOT NULL DEFAULT 1       COMMENT '内容版本号，每次保存 +1（从 1 开始）',
  `published_version` INT               NULL                COMMENT '已发布版本号；NULL = 从未发布',
  `published_at_ms`   BIGINT            NULL                COMMENT '最近一次发布时间',
  `enabled`           TINYINT(1)    NOT NULL DEFAULT 1       COMMENT '是否参与装配（停用但不删除）',
  `created_at_ms`     BIGINT        NOT NULL,
  `updated_at_ms`     BIGINT        NOT NULL,
  `created_by`        VARCHAR(64)       NULL,
  `updated_by`        VARCHAR(64)       NULL,
  PRIMARY KEY (`agent_name`),
  KEY `idx_status` (`status`),
  KEY `idx_updated` (`updated_at_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 主表（身份与生命周期）';

-- ---------- 2. agent 引用表：页面上这些字段全部是下拉多选 ----------
CREATE TABLE IF NOT EXISTS `md_agent_ref` (
  `agent_name`  VARCHAR(128) NOT NULL                      COMMENT '所属 agent',
  `prompt_key`  VARCHAR(128)     NULL                      COMMENT '引用 md_prompt.prompt_key；非空则其正文覆盖 sysPrompt',
  `tools`       JSON             NULL                      COMMENT 'Spring 工具 bean 名列表（来源：tool 只读目录），精确声明，非白名单过滤',
  `middleware`  JSON             NULL                      COMMENT '中间件 bean 名列表（来源：middleware 只读目录）',
  `skill_names` JSON             NULL                      COMMENT '引用 md_skill_meta.skill_name 列表',
  `mcp_names`   JSON             NULL                      COMMENT '引用 md_mcp_server.server_name 列表（顺序即装配顺序）',
  `knowledges`  JSON             NULL                      COMMENT 'RAG Knowledge bean 名列表',
  PRIMARY KEY (`agent_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 引用（谁被这个 agent 用了）';

-- ---------- 3. agent 运行时表：通用旋钮 + 模型 ----------
CREATE TABLE IF NOT EXISTS `md_agent_runtime` (
  `agent_name`                  VARCHAR(128) NOT NULL,
  -- 模型
  `model_ref`                   VARCHAR(128)     NULL      COMMENT '主模型 bean 名，默认 openAIChatModel',
  `fallback_model_ref`          VARCHAR(128)     NULL      COMMENT 'fallback 模型 bean 名（可空；空则不套默认模型）',
  `reasoning_effort`            VARCHAR(32)      NULL      COMMENT 'low | medium | high（模型侧推理强度）',
  -- 迭代与重试
  `max_iters`                   INT              NULL      COMMENT '最大迭代轮次',
  `max_retries`                 INT              NULL      COMMENT '模型调用重试次数',
  `max_context_tokens`          INT              NULL      COMMENT 'HARNESS 最大上下文 token；0/NULL 表示框架默认',
  -- 行为开关
  `check_running`               TINYINT(1)       NULL      COMMENT '运行前是否检查 agent 状态',
  `stop_on_reject`              TINYINT(1)       NULL      COMMENT '模型拒绝调用时是否停止',
  `enable_meta_tool`            TINYINT(1)       NULL      COMMENT '是否启用 meta tool',
  `enable_task_list`            TINYINT(1)       NULL      COMMENT '是否开启任务列表',
  `enable_pending_tool_recovery` TINYINT(1)      NULL      COMMENT '是否启用未完成工具调用恢复',
  -- 状态与权限
  `conflict_policy`             VARCHAR(32)      NULL      COMMENT 'REACT：OVERWRITE | FAIL | APPEND_MERGE',
  -- 技能策略（REACT 专有，2026-09-22 从 md_agent_workspace 迁来）
  -- 理由：这三项只定义在 ReActAgent.Builder 上（HarnessAgent.Builder 没有对应方法，
  -- HarnessAgent 反而在内部把 dynamicSkillsEnabled 主动关掉）。放在"workspace"表里
  -- 会让人以为它们是工作区属性，而 REACT 根本没有工作区行。
  `dynamic_skills_enabled`      TINYINT(1)       NULL      COMMENT 'REACT：是否允许动态加载技能',
  `skill_code_execution_enabled` TINYINT(1)      NULL      COMMENT 'REACT：是否允许技能代码执行',
  `skill_work_dir`              VARCHAR(255)     NULL      COMMENT 'REACT：技能执行工作目录（相对 workspace-root）',
  `state_store`                 VARCHAR(255)     NULL      COMMENT '状态存储标记；非空 = 启用 MysqlAgentStateStore（按 agent 名隔离建表）',
  `default_session_id`          VARCHAR(128)     NULL      COMMENT '默认会话 ID',
  `long_term_memory_mode`       VARCHAR(32)      NULL      COMMENT 'AGENT_CONTROL | STATIC_CONTROL | BOTH',
  `long_term_memory_async`      TINYINT(1)       NULL      COMMENT '长期记忆是否异步写入',
  `rag_mode`                    VARCHAR(32)      NULL      COMMENT 'RAG 模式',
  -- 结构化配置（字段少、无独立查询需求，用 JSON 列）
  `generate_options`            JSON             NULL      COMMENT 'model.GenerateOptions（temperature/topP 等）',
  `model_execution_config`      JSON             NULL      COMMENT 'model.ExecutionConfig（模型侧并发/超时）',
  `tool_execution_config`       JSON             NULL      COMMENT 'model.ExecutionConfig（工具侧并发/超时）',
  `retrieve_config`             JSON             NULL      COMMENT 'rag.model.RetrieveConfig',
  `permission`                  JSON             NULL      COMMENT 'permission.PermissionContextState / PermissionMode',
  PRIMARY KEY (`agent_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 运行时旋钮（ReAct 与 Harness 共有）';

-- ---------- 4. agent 记忆表：上下文压缩 + 记忆 + 工具结果驱逐 ----------
-- 这三块都是"上下文怎么随轮次增长而收敛"的同一语义域，放一张表便于对照调参。
CREATE TABLE IF NOT EXISTS `md_agent_memory` (
  `agent_name`                VARCHAR(128) NOT NULL,
  -- 上下文压缩 CompactionConfig
  `compaction_disabled`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'disableCompaction()；为 1 时下面压缩字段全部忽略',
  `trigger_messages`          INT              NULL          COMMENT '累积多少条消息触发压缩',
  `trigger_tokens`            INT              NULL          COMMENT '累积多少 token 触发压缩',
  `reserved`                  INT              NULL          COMMENT '压缩后预留 token',
  `keep_messages`             INT              NULL          COMMENT '压缩时保留最近多少条消息',
  `keep_tokens`               INT              NULL          COMMENT '压缩时保留多少 token',
  `keep_tokens_min`           INT              NULL          COMMENT '保留 token 下限',
  `keep_tokens_max`           INT              NULL          COMMENT '保留 token 上限',
  `keep_tokens_ratio`         DECIMAL(6,4)     NULL          COMMENT '保留比例（与 min/max 配合）',
  `summary_prompt`            MEDIUMTEXT       NULL          COMMENT '摘要提示词（空则用框架默认 DEFAULT_SUMMARY_PROMPT）',
  `flush_before_compact`      TINYINT(1)       NULL          COMMENT '压缩前先落盘记忆',
  `offload_before_compact`    TINYINT(1)       NULL          COMMENT '压缩前先转存',
  `compaction_model_ref`      VARCHAR(128)     NULL          COMMENT '压缩用模型；空则用主模型',
  `truncate_args`             JSON             NULL          COMMENT 'TruncateArgsConfig（triggerMessages/triggerTokens/keepMessages/keepTokens/maxArgLength/truncationText）',
  `prune`                     JSON             NULL          COMMENT 'PruneConfig（protectTokens/minimumTokens/maxOutputChars/excludedTools）',
  -- 记忆 MemoryConfig
  `memory_model_ref`          VARCHAR(128)     NULL          COMMENT '记忆整理用模型；空则用主模型',
  `flush_prompt`              MEDIUMTEXT       NULL          COMMENT '记忆落盘提示词',
  `consolidation_prompt`      MEDIUMTEXT       NULL          COMMENT '记忆归并提示词',
  `consolidation_max_tokens`  INT              NULL          COMMENT '归并输出 token 上限',
  `consolidation_min_gap_ms`  BIGINT           NULL          COMMENT '两次归并最小间隔（毫秒，对应 Duration）',
  `daily_file_retention_days` INT              NULL          COMMENT '日志文件保留天数',
  `session_retention_days`    INT              NULL          COMMENT '会话保留天数',
  `flush_trigger`             JSON             NULL          COMMENT 'FlushTrigger（mode=ALWAYS|NEVER|THROTTLED, minGapMs）',
  -- 工具结果驱逐 ToolResultEvictionConfig
  `evict_disabled`            TINYINT(1)       NOT NULL DEFAULT 0 COMMENT 'disableToolResultEviction()',
  `evict_max_result_chars`    INT              NULL          COMMENT '单条工具结果超过多少字符转存',
  `evict_preview_chars`       INT              NULL          COMMENT '转存后留在上下文里的预览长度',
  `evict_path`                VARCHAR(255)     NULL          COMMENT '转存目录',
  `evict_excluded_tools`      JSON             NULL          COMMENT '不参与驱逐的工具名',
  PRIMARY KEY (`agent_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 上下文压缩 / 记忆 / 工具结果驱逐';

-- ---------- 5. agent 工作区表：Harness 专属 + 能力开关 ----------
CREATE TABLE IF NOT EXISTS `md_agent_workspace` (
  `agent_name`                     VARCHAR(128) NOT NULL,
  `harness_agent_id`               VARCHAR(128)     NULL      COMMENT 'HARNESS Agent 唯一 ID',
  `workspace_path`                 VARCHAR(255)     NULL      COMMENT '相对 workspace-root；空则默认 harness/{agentName}（框架强制本地路径，必须落盘）',
  `environment`                    VARCHAR(64)      NULL      COMMENT '运行环境标识',
  `environment_memory`             VARCHAR(255)     NULL      COMMENT '环境记忆目录（相对 workspace-root）',
  `project_global_skills_dir`      VARCHAR(255)     NULL      COMMENT '项目全局技能目录（仅兜底，正常走 DB skill 仓库）',
  `plan_file_directory`            VARCHAR(255)     NULL      COMMENT '计划文件目录',
  `additional_context_files`       JSON             NULL      COMMENT '追加的上下文文件列表',
  `filesystem`                     JSON             NULL      COMMENT 'filesystemSpec（local/sandbox/remote）+ filesystemRoute',
  `use_legacy_xml_workspace_context` TINYINT(1)     NULL      COMMENT '是否用遗留 XML 工作区上下文',
  `agent_tracing_log_enabled`      TINYINT(1)       NULL      COMMENT '是否开启 agent 链路日志',
  -- 计划模式
  `enable_plan_mode`               TINYINT(1)       NULL      COMMENT '是否开启计划模式',
  `plan_mode_allow_shell`          TINYINT(1)       NULL      COMMENT '计划模式下是否允许 shell',
  -- 技能策略
  `skills_enabled`                 TINYINT(1)       NULL      COMMENT '技能总开关',
  `enable_skills`                  JSON             NULL      COMMENT '仅启用这些技能（按名）',
  `disable_skills`                 JSON             NULL      COMMENT '禁用这些技能（按名）',
  `skill_manage_tool_enabled`      TINYINT(1)       NULL      COMMENT 'HARNESS：是否启用技能管理工具',
  `skill_manage_config`            JSON             NULL      COMMENT 'HARNESS：SkillManageConfig',
  -- 能力开关（对应 builder 上一批 disableXxx()）
  `disable_filesystem_tools`       TINYINT(1)       NULL,
  `disable_shell_tool`             TINYINT(1)       NULL,
  `disable_memory_tools`           TINYINT(1)       NULL,
  `disable_memory_hooks`           TINYINT(1)       NULL,
  `disable_transcript`             TINYINT(1)       NULL,
  `disable_session_persistence`    TINYINT(1)       NULL,
  `disable_workspace_context`      TINYINT(1)       NULL,
  `disable_at_path_expansion`      TINYINT(1)       NULL,
  `disable_subagents`              TINYINT(1)       NULL,
  `disable_dynamic_subagents`      TINYINT(1)       NULL,
  `disable_dynamic_skills`         TINYINT(1)       NULL,
  `disable_default_workspace_skills` TINYINT(1)     NULL,
  `disable_tools_config`           TINYINT(1)       NULL,
  PRIMARY KEY (`agent_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 工作区与能力开关（HARNESS 为主）';

-- ---------- 6. agent 子表：子 agent 声明（一对多） ----------
CREATE TABLE IF NOT EXISTS `md_agent_sub_agent` (
  `id`                           BIGINT       NOT NULL AUTO_INCREMENT,
  `agent_name`                   VARCHAR(128) NOT NULL         COMMENT '父 agent（HARNESS）',
  `sub_name`                     VARCHAR(128) NOT NULL         COMMENT '子 agent 名（引用另一个 md_agent.agent_name，或远端声明）',
  `sort_order`                   INT          NOT NULL DEFAULT 0 COMMENT '路由展示顺序',
  `sub_description`              VARCHAR(1024)    NULL         COMMENT '派发给子 agent 时的职责说明，参与暴露给模型的路由描述',
  `workspace_mode`               VARCHAR(16)      NULL         COMMENT 'ISOLATED | SHARED',
  `workspace_path`               VARCHAR(255)     NULL,
  `inline_agents_body`           MEDIUMTEXT       NULL         COMMENT '内联 AGENTS.md 正文（空则读子 agent 自己的定义）',
  `model_ref`                    VARCHAR(128)     NULL         COMMENT '子 agent 覆盖模型；空则继承',
  `max_iters`                    INT              NULL,
  `steps`                        INT              NULL,
  `temperature`                  DECIMAL(4,2)     NULL,
  `top_p`                        DECIMAL(4,2)     NULL,
  `variant`                      VARCHAR(64)      NULL,
  `mode`                         VARCHAR(16)      NULL         COMMENT 'PRIMARY | SUBAGENT | ALL',
  `hidden`                       TINYINT(1)       NULL,
  `persist_session`              TINYINT(1)       NULL,
  `inherit_parent_permissions`   TINYINT(1)       NULL,
  `expose_to_user`               TINYINT(1)       NULL,
  `tools`                        JSON             NULL         COMMENT '子 agent 可用的工具（空则继承父）',
  `skills`                       JSON             NULL         COMMENT '子 agent 可用的技能（空则继承父）',
  `remote`                       TINYINT(1)       NULL         COMMENT '是否远端子 agent（A2A）',
  `url`                          VARCHAR(512)     NULL         COMMENT '远端地址',
  `headers`                      JSON             NULL         COMMENT '远端请求头',
  `remote_streaming`             TINYINT(1)       NULL,
  `remote_stream_detail`         VARCHAR(64)      NULL,
  `remote_ask_policy`            VARCHAR(64)      NULL,
  `remote_context_attributes`    JSON             NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_sub` (`agent_name`, `sub_name`),
  KEY `idx_agent` (`agent_name`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HARNESS 子 agent 声明';

-- ---------- 7. prompt ----------
CREATE TABLE IF NOT EXISTS `md_prompt` (
  `prompt_key`        VARCHAR(128)  NOT NULL                COMMENT 'prompt 唯一键（agent 通过 prompt-key 引用）',
  `description`       VARCHAR(1024)     NULL,
  `biz_tags`          JSON              NULL,
  `body`              MEDIUMTEXT        NULL                COMMENT '提示词正文（纯文本 / Markdown，渲染交给前端）',
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
  `version`           INT           NOT NULL DEFAULT 1,
  `published_version` INT               NULL,
  `published_at_ms`   BIGINT            NULL,
  `created_at_ms`     BIGINT        NOT NULL,
  `updated_at_ms`     BIGINT        NOT NULL,
  `created_by`        VARCHAR(64)       NULL,
  `updated_by`        VARCHAR(64)       NULL,
  PRIMARY KEY (`prompt_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prompt 正文（原 Nacos PromptSpec + 本地 md frontmatter 正文）';

-- ---------- 8. MCP 服务端 ----------
CREATE TABLE IF NOT EXISTS `md_mcp_server` (
  `server_name`      VARCHAR(128)  NOT NULL                COMMENT 'MCP 唯一名（agent 通过 mcp-names 引用）',
  `description`      VARCHAR(1024)     NULL,
  `biz_tags`         JSON              NULL,
  `transport`        VARCHAR(32)       NULL                COMMENT 'streamable | sse（stdio 保留字段但运行时拒绝）',
  `url`              VARCHAR(512)      NULL                COMMENT '远端端点 URL',
  `headers`          JSON              NULL                COMMENT '请求头（鉴权 token 等）',
  `query_params`     JSON              NULL                COMMENT '附加查询参数',
  `command`          VARCHAR(512)      NULL                COMMENT 'stdio 专用，暂不支持',
  `args`             JSON              NULL                COMMENT 'stdio 专用，暂不支持',
  `env`              JSON              NULL                COMMENT 'stdio 专用，暂不支持',
  `enable_tools`     JSON              NULL                COMMENT '该 MCP 在本平台的工具白名单；非空则以它为准（原 agent 级旋钮，本次下沉到 MCP 级）',
  `disable_tools`    JSON              NULL                COMMENT '工具黑名单，enable_tools 非空时忽略',
  `timeout_ms`       BIGINT            NULL                COMMENT '单次调用超时',
  `init_timeout_ms`  BIGINT            NULL                COMMENT '握手/初始化超时',
  `enabled`          TINYINT(1)    NOT NULL DEFAULT 1,
  `status`           VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
  `version`          INT           NOT NULL DEFAULT 1,
  `published_version` INT              NULL,
  `published_at_ms`  BIGINT            NULL,
  `created_at_ms`    BIGINT        NOT NULL,
  `updated_at_ms`    BIGINT        NOT NULL,
  `created_by`       VARCHAR(64)       NULL,
  `updated_by`       VARCHAR(64)       NULL,
  PRIMARY KEY (`server_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务端（原 Nacos MCP server）';

-- ---------- 9. skill 治理元数据（内容在官方表 agentscope_skills） ----------
CREATE TABLE IF NOT EXISTS `md_skill_meta` (
  `skill_name`        VARCHAR(128)  NOT NULL                COMMENT '与官方 agentscope_skills.name 一一对应（本表不存内容）',
  `display_name`      VARCHAR(255)      NULL                COMMENT '展示名（官方表只有 name）',
  `description`       VARCHAR(1024)     NULL                COMMENT '缓存官方表的 description，避免列表页逐条回查官方表',
  `biz_tags`          JSON              NULL,
  `status`            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
  `version`           INT           NOT NULL DEFAULT 1,
  `published_version` INT               NULL,
  `published_at_ms`   BIGINT            NULL,
  `enabled`           TINYINT(1)    NOT NULL DEFAULT 1,
  `created_at_ms`     BIGINT        NOT NULL,
  `updated_at_ms`     BIGINT        NOT NULL,
  `created_by`        VARCHAR(64)       NULL,
  `updated_by`        VARCHAR(64)       NULL,
  PRIMARY KEY (`skill_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 治理元数据（内容以官方 agentscope_skills 为准）';

-- ---------- 10. 版本快照（五类资源共用） ----------
CREATE TABLE IF NOT EXISTS `md_resource_version` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `resource_type` VARCHAR(16)  NOT NULL         COMMENT 'AGENT | PROMPT | SKILL | MCP',
  `resource_name` VARCHAR(128) NOT NULL         COMMENT '对应资源的主键值',
  `version`       INT          NOT NULL         COMMENT '快照对应的版本号',
  `snapshot_json` LONGTEXT     NOT NULL         COMMENT 'AGENT/PROMPT/MCP = 主表整行 JSON；SKILL = 内容 + 附属文件',
  `change_note`   VARCHAR(512)     NULL         COMMENT '本次变更说明',
  `created_at_ms` BIGINT       NOT NULL,
  `created_by`    VARCHAR(64)      NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_name_ver` (`resource_type`, `resource_name`, `version`),
  KEY `idx_resource` (`resource_type`, `resource_name`, `version` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='五类资源的版本快照，支撑回滚';
```

### 4.3 skill 的双表结构（说明为什么不是两处真相）

| 数据 | 存在哪 | 谁写 | 谁读 |
| --- | --- | --- | --- |
| skill 正文（`SKILL.md`） | 官方 `agentscope_skills.skill_content` | 官方 `MysqlSkillRepository.save()` | 官方 `getSkill()` → 装配期 `AgentSkillRepository` |
| skill 附属文件 | 官方 `agentscope_skill_resources` | 同上 | 同上 |
| 业务标签 / 草稿发布态 / 版本 | 我们 `md_skill_meta` | `ControlPlaneStore` | 控制面页面 |

**不存在两处真相**：内容只有一个权威位置（官方表），`md_skill_meta` 只承载官方表**没有**的治理字段，且它的 `name` 与官方表一一对应。`description` 是**缓存**（列表页要展示，逐条回查官方表代价高），以官方表为准，保存时同步。

### 4.3.1 ⚠ 实测修正：skill 内容只能落在独立的 `agentscope` 库（2026-09-21）

**原设计写的是"用 `databaseName("md-agent-platform")` 指到现有库"，实测不成立。** 官方实现对库名做标识符白名单校验（只允许字母/数字/下划线，首字符必须是字母或下划线），`md-agent-platform` 含连字符，直接抛：

```
java.lang.IllegalArgumentException: Database name contains invalid characters.
Only alphanumeric characters and underscores are allowed, and it must start with a letter or underscore.
Invalid value: md-agent-platform
```

可选值只剩 `agentscope`（默认）或另一个下划线命名的库；后者只是"把库拆成两个"的等价结果，没有额外好处，因此**采用默认值 `agentscope`**。连接仍走本项目的数据源（同一个 MySQL 实例），跨 schema 查询由账号权限保证（当前 root）。

**实测确认可用**（探针跑通：自建库表 → 写入 → 读回含附属文件 → 删除 → 计数归零）：

| 表 | 真实结构（`information_schema` 读取） |
| --- | --- |
| `agentscope_skills` | `id` bigint PK · `name` varchar(255) UNIQUE · `description` text NOT NULL · `skill_content` longtext NOT NULL · `source` varchar(255) NOT NULL · `metadata_json` longtext NULL · `created_at` / `updated_at` timestamp |
| `agentscope_skill_resources` | `(id, resource_path)` 联合主键 · `resource_path` varchar(500) · `resource_content` longtext NOT NULL · `created_at` / `updated_at` timestamp |

注意：`description` / `source` / `skill_content` 都是 **NOT NULL**，页面新建 skill 时这三项必须给值（`source` 可由后端填固定值，如 `control-plane`）。

### 4.4 版本与发布 / 回滚语义

```
保存草稿：  主表.version += 1，status = DRAFT          （不落快照，草稿可以随便改）
发布：      status = PUBLISHED；published_version = version
            → 写 md_resource_version(type, name, version, snapshot_json)
            → 触发热装配（AgentManager 重建该 agent）
回滚到 vN： 读 md_resource_version 的 vN 快照
            → 写回主表内容，version += 1（新版本，不改写历史）
            → status = DRAFT（回滚后仍需显式发布，避免手滑直接影响线上）
```

- 快照只在**发布**时落，草稿不落 —— 否则反复编辑会灌爆快照表。
- 回滚**新增**一个版本号而不是覆盖历史，保证版本序列单向递增、可审计。
- 回滚后置 `DRAFT`：回滚是"准备一个旧内容的新版本"，是否上线由人再按一次发布。

---

## 5. 字段映射（Builder setter ↔ 表列）

> 这张表是"字段够不够用"的自证：左边是**框架实际提供的全部可设置项**（来自 `javap ReActAgent$Builder` / `HarnessAgent$Builder`），右边是对应落点。没有落点的项在第 5.7 节说明为什么不需要。

### 5.1 两类型共有 → `md_agent_runtime` / `md_agent_ref`

| Builder 方法 | 落点 |
| --- | --- |
| `name(String)` | `md_agent.agent_name` |
| `description(String)` | `md_agent.description` |
| `sysPrompt(String)` | **不存**，装配期由 `md_agent_ref.prompt_key` → `md_prompt.body` 填入 |
| `model(Model/String)` | `md_agent_runtime.model_ref` |
| `fallbackModel(Model/String)` | `md_agent_runtime.fallback_model_ref` |
| `maxIters(int)` | `md_agent_runtime.max_iters` |
| `maxRetries(int)` | `md_agent_runtime.max_retries` |
| `checkRunning(boolean)` | `md_agent_runtime.check_running` |
| `stopOnReject(boolean)` | `md_agent_runtime.stop_on_reject` |
| `enableMetaTool(boolean)` | `md_agent_runtime.enable_meta_tool` |
| `enableTaskList(boolean)` | `md_agent_runtime.enable_task_list` |
| `enablePendingToolRecovery(boolean)` | `md_agent_runtime.enable_pending_tool_recovery` |
| `stateStore(AgentStateStore)` | `md_agent_runtime.state_store`（标记位） |
| `defaultSessionId(String)` | `md_agent_runtime.default_session_id` |
| `toolkit(Toolkit)` | 由 `md_agent_ref.tools` + `mcp_names` 组装 |
| `middleware(MiddlewareBase)` | `md_agent_ref.middleware`（bean 名） |
| `hooks(List<Hook>)` | 暂不开放（见 5.7） |
| `skillRepository(...)` | 固定为 `MysqlSkillRepository`，由 `md_agent_ref.skill_names` 决定取哪些 |
| `generateOptions(GenerateOptions)` | `md_agent_runtime.generate_options` |
| `modelExecutionConfig(ExecutionConfig)` | `md_agent_runtime.model_execution_config` |
| `toolExecutionConfig(ExecutionConfig)` | `md_agent_runtime.tool_execution_config` |
| `permissionContext(PermissionContextState)` | `md_agent_runtime.permission` |
| `longTermMemory / longTermMemoryMode / longTermMemoryAsyncRecord` | `md_agent_runtime.long_term_memory_mode / long_term_memory_async` |
| `knowledge / knowledges / ragMode / retrieveConfig` | `md_agent_ref.knowledges` + `md_agent_runtime.rag_mode / retrieve_config` |
| `toolExecutionContext(...)` | 运行期注入，非配置 |

### 5.2 ReAct 专有

| Builder 方法 | 落点 |
| --- | --- |
| `conflictPolicy(ConflictPolicy)` | `md_agent_runtime.conflict_policy` |
| `dynamicSkillsEnabled(boolean)` | `md_agent_runtime.dynamic_skills_enabled` |
| `skillCodeExecutionEnabled(boolean)` | `md_agent_runtime.skill_code_execution_enabled` |
| `skillWorkDir(Path)` | `md_agent_runtime.skill_work_dir` |
| `skillBox / skillRepositories / skillFilter` | 由 `skill_names` 统一表达，不再单独暴露 |

### 5.3 Harness 专有

| Builder 方法 | 落点 |
| --- | --- |
| `agentId(String)` | `md_agent_workspace.harness_agent_id` |
| `workspace(Path/String)` | `md_agent_workspace.workspace_path` |
| `environment(String)` | `md_agent_workspace.environment` |
| `environmentMemory(String)` | `md_agent_workspace.environment_memory` |
| `projectGlobalSkillsDir / projectGlobalSkillDir` | `md_agent_workspace.project_global_skills_dir` |
| `additionalContextFile(String)` | `md_agent_workspace.additional_context_files` |
| `filesystem(...) / filesystemRoute(...) / abstractFilesystem(...)` | `md_agent_workspace.filesystem` |
| `useLegacyXmlWorkspaceContext(boolean)` | `md_agent_workspace.use_legacy_xml_workspace_context` |
| `enableAgentTracingLog(boolean)` | `md_agent_workspace.agent_tracing_log_enabled` |
| `enablePlanMode(...) / planFileDirectory(...) / allowShellInPlanMode(...)` | `md_agent_workspace.enable_plan_mode / plan_file_directory / plan_mode_allow_shell` |
| `skillsEnabled / enableSkills / disableSkills` | `md_agent_workspace.skills_enabled / enable_skills / disable_skills` |
| `enableSkillManageTool(...)` | `md_agent_workspace.skill_manage_tool_enabled / skill_manage_config` |
| `maxContextTokens(int)` | `md_agent_runtime.max_context_tokens` |
| `subagent / subagents / subagentFactory / buildSubagentEntries` | `md_agent_sub_agent` 逐行 + `AgentManager` 的 `agents::get` 惰性路由 |
| 一批 `disableXxx()`：filesystem/shell/memory-tools/memory-hooks/transcript/session-persistence/workspace-context/at-path-expansion/subagents/dynamic-subagents/dynamic-skills/default-workspace-skills/tools-config | `md_agent_workspace` 对应 `disable_*` 列 |
| `disableCompaction() / disableToolResultEviction()` | `md_agent_memory.compaction_disabled / evict_disabled` |
| `artifactDeliveryTarget / messageBus / asyncToolRegistry / taskRepository / transcriptStore / distributedStore / teamsMode / mcpServerRegistrationListener / modelResolver / externalSubagentTool / enableSkillPromotionGate / enableSkillCurator` | 需运行时 bean 或复杂对象，**页面不开放**（见 5.7） |

### 5.4 上下文压缩 / 记忆 / 驱逐（你点名要覆盖的部分）

| 配置 | 落点 |
| --- | --- |
| `CompactionConfig`：triggerMessages / triggerTokens / reserved / keepMessages / keepTokens / keepTokensMin / keepTokensMax / keepTokensRatio / summaryPrompt / flushBeforeCompact / offloadBeforeCompact / model | `md_agent_memory` 同名扁列 + `compaction_model_ref` |
| `CompactionConfig.TruncateArgsConfig`：triggerMessages / triggerTokens / keepMessages / keepTokens / maxArgLength / truncationText | `md_agent_memory.truncate_args`（JSON） |
| `CompactionConfig.PruneConfig`：protectTokens / minimumTokens / maxOutputChars / excludedTools | `md_agent_memory.prune`（JSON） |
| `MemoryConfig`：model / flushPrompt / consolidationPrompt / consolidationMaxTokens / consolidationMinGap / dailyFileRetentionDays / sessionRetentionDays / flushTrigger | `md_agent_memory` 同名扁列 + `flush_trigger`（JSON） |
| `ToolResultEvictionConfig`：maxResultChars / previewChars / evictionPath / excludedToolNames | `md_agent_memory.evict_*` |

### 5.5 子 agent 声明 → `md_agent_sub_agent`

`SubagentDeclaration` 的字段（name / description / workspaceMode / workspacePath / inlineAgentsBody / model / maxIters / steps / temperature / topP / variant / mode / hidden / persistSession / inheritParentPermissions / exposeToUser / tools / skills / remote / url / headers / remoteStreaming / remoteStreamDetail / remoteAskPolicy / remoteContextAttributes）**逐字段建列**，不做 JSON —— 因为子 agent 是页面上的一个可编辑列表（增删行 + 每行一个表单），必须能按列校验与查询。

### 5.6 MCP → `md_mcp_server`

`McpServerConfig` 的 10 个字段（transport / command / args / env / url / headers / queryParams / enableTools / timeout / initializationTimeout）**全覆盖**，外加我们自己的 `disable_tools` 与治理字段。

### 5.7 明确不落库的项（以及理由）

| 项 | 理由 |
| --- | --- |
| `hooks(List<Hook>)` | 框架 Hook 是代码对象，没有"配置化"表达方式；现有代码也没用 |
| `artifactDeliveryTarget / messageBus / asyncToolRegistry / taskRepository / transcriptStore / distributedStore / teamsMode / mcpServerRegistrationListener / modelResolver / externalSubagentTool` | 都需要运行期 bean 或复杂对象引用，写进 DB 只能存一个 bean 名，价值低且容易写出"配了但不生效"的假象 |
| `enableSkillPromotionGate / enableSkillCurator` | 技能治理流程（晋升/筛选）依赖框架内部实现，个人场景暂不需要 |
| `abstractFilesystem` | 用 `filesystem`（Spec 形式）表达即可，两者二选一 |

---

## 6. 接口约定

> 前端 `agent-contro-plane` 里 `src/api/*.ts` 已经按这些路径写好了，后端补齐即可。

### 6.1 资源 CRUD（prompt / skill / mcp 同构）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/resources/prompts` | 列表（含状态、版本、更新时间） |
| GET | `/api/resources/prompts/{key}` | 详情（含正文） |
| POST | `/api/resources/prompts` | 新建（默认 DRAFT） |
| PUT | `/api/resources/prompts/{key}` | 保存草稿（version +1） |
| DELETE | `/api/resources/prompts/{key}` | 删除（被引用则 409 + 使用方列表） |
| POST | `/api/resources/prompts/{key}/publish` | 发布（落快照 + 热装配引用它的 agent） |
| GET | `/api/resources/prompts/{key}/versions` | 版本列表 |
| POST | `/api/resources/prompts/{key}/rollback` | 回滚到指定版本（body: version），结果置 DRAFT |

`skills` / `mcp` 同构替换路径段即可。

### 6.2 只读目录（代码资产）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/agents/tools/available` | **已实现**，扫 `@Tool` bean（`ToolMeta`） |
| GET | `/api/resources/middleware/catalog` | 待实现，扫 `MiddlewareBase` bean |
| GET | `/api/resources/middleware` | 当前生效的中间件链（含框架内置的 `AgentRunTraceMiddleware`） |

### 6.3 Agent

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/agents` | 列表（含类型 / 状态 / 版本 / 引用计数） |
| GET | `/api/agents/{name}` | 详情（引用解析成名字列表，供下拉回显） |
| POST | `/api/agents` | 新建 |
| PUT | `/api/agents/{name}` | 保存草稿 |
| DELETE | `/api/agents/{name}` | 删除（被 sub-agents 引用则拒绝） |
| POST | `/api/agents/{name}/publish` | 发布 + 重建实例 |
| POST | `/api/agents/{name}/reload` | 仅重建实例（DB 内容不变，用于运行期复位） |

### 6.4 保存时的校验规则（前后端都要做，后端为准）

1. `prompt_key` 必须在 `md_prompt` 存在；
2. `skill_names` 每项必须在 `md_skill_meta`（且官方表有内容）存在；
3. `mcp_names` 每项必须在 `md_mcp_server` 存在且 `enabled`；
4. `tools` 每项必须命中 tool 只读目录；
5. `middleware` 每项必须命中 middleware 只读目录；
6. `sub_agents` 每项必须是已存在的 agent、**不允许自引用**，且**只允许 REACT**（HARNESS 是编排体，不能再当别人的子 agent）；
7. `type=REACT` 时不允许填 HARNESS 专属字段（反向同理）—— 避免"配了但不生效"；落在行结构上就是：
   REACT **不应有** `md_agent_workspace` 行，HARNESS **不应有** `conflict_policy` 与三项技能策略（2026-09-22 调整，见 §9.3）；
8. `agent_type` **创建后不可变更**（改类型等于把整套行结构换掉：REACT 没有 workspace 行，HARNESS 多了子 agent 与上下文压缩）——
   需要另一种形态请新建，再把原 agent 挂成它的子 agent。

---

## 7. 迁移方案（一次性导入）

### 7.1 为什么必须有这一步

当前 `agent-platform.agents.source=local`，14 个 agent 的正文都在 `workspace/agents/*.md`；prompt / skill / mcp 在 Nacos 上。切库后运行时只看 DB，**不导入就是空注册表**。

### 7.2 导入内容与来源

| 目标表 | 来源 |
| --- | --- |
| `md_agent` + 5 张关联表 | `workspace/agents/*.md` 的 frontmatter（14 个文件） |
| `md_prompt` | 同上——每个 md 的**正文**即 sysPrompt，按 `prompt-key` 建一条 prompt |
| `md_mcp_server` | Nacos MCP `list`（4 个：workwx-doc / workwx-address / workwx-message / workwx-email） |
| `agentscope_skills` + `md_skill_meta` | Nacos Skill `list`（36 个，其中被引用的若干） |

### 7.3 实施方式

- 写一个**一次性的 @Component**（`ControlPlaneImporter`），由 `POST /api/admin/import-legacy` 触发，或用 `CommandLineRunner` + 开关控制；
- **幂等**：按主键 upsert，重复执行不产生重复数据；
- 导入完成后打印对账表（每个 agent 的引用是否都能在库里解析到），**对账不通过则整批回滚**；
- 导入验证通过后，`AgentManager.init()` 改为纯 DB 加载。

### 7.4 特殊处理：`enable-tools / disable-tools` 的归位（D10）

现有 agent 定义里 `enable-tools` / `disable-tools` 是 **agent 级**的 MCP 工具白/黑名单。下沉到 MCP 级后，要按"这个 agent 引用了哪些 MCP"逐个归位。**注意**：若两个 agent 对同一个 MCP 的白名单不同，归位会有冲突——本次导入时若检出冲突，**打 ERROR 日志并人工裁决**，不静默取其中一个。

---

## 8. 设计取舍

| 取舍 | 选了 | 放弃了什么 | 为什么 |
| --- | --- | --- | --- |
| 单源 vs 双源兜底 | 单源 MySQL | 迁移期没有 Nacos 兜底 | 双源会让"改了不生效"永远存在，这正是要消灭的问题 |
| 按域拆表 vs 大宽表 | 6 张表 | 多几次 join | 每个域独立演进，页面表单天然分区，加字段不波及其他域 |
| JSON 列 vs 子表 | 小配置用 JSON | 失去按子字段查询 | 这些子配置没有单独查询需求，拆表只增加 join 成本 |
| 官方 skill 仓库 vs 自研 | 用官方 | 表结构受官方约束、多一张治理表 | 零自研、官方后续演进（版本/标签能力）自动受益 |
| 快照只在发布时落 | 是 | 草稿无历史 | 否则反复编辑灌爆快照表 |
| 回滚生成新版本 | 是 | 版本号不"回到过去" | 保证版本序列单向递增、可审计 |
| Nacos 类先保留 | 是 | 短期死代码 | 切库出问题可快速回退，Step 7 再清 |

---

## 9. 实施步骤与验证

| Step | 内容 | 验证方式 |
| --- | --- | --- |
| 0 | 本文（设计定稿） | 人工 review |
| 1 | `control-plane-schema.sql` + 引入官方 skill 仓库依赖 | ✅ 已完成：10 张表已在真库建好（2026-09-21）；官方依赖解析成功；官方 skill 仓库已实测自建 `agentscope` 库表并跑通读/写/删 |
| 2 | 实体 / Mapper / `ControlPlaneStore`（写路径加 `@Transactional`，本项目首个事务点） | ✅ 已完成（2026-09-22）：`ControlPlaneGovernanceTest` 5/5、`MyBatisControlPlaneStoreIT` 9/9（真库）、`mvn -o test` 265 通过。落地清单与实测结论见 §9.1 |
| 3 | `AgentDefinitionResolver`（单源 DB）+ `AgentManager` 改 DB 加载 + `DbMcpClientFactory` | ⏳ 代码完成、编译与单测通过；**启动日志与导入前一致这一条要等导入实跑后才能验**。见 §9.2 |
| 4 | 一次性导入工具 + 对账 | 🗑 **已删除（2026-09-22）**：决定不迁移历史数据，改为在页面上手工建立 —— `ControlPlaneImporter` / `ImportReport` / `LegacyImportRunner` 与两个配置开关一并删除。见 §9.2 |
| 5 | controller（CRUD + publish/rollback + 只读目录） | ⏳ 代码完成、编译与测试通过；**接口逐个实测要在服务起来后做**。见 §9.2 |
| 6 | 前端 5 个卡片页 + 编辑抽屉 + agent 引用下拉 | ✅ 代码完成：类型 / 构建 / 测试全绿，路径由契约测试锁住；**未与真后端联调**。见 §9.2 |
| 7 | 清理 Nacos 运行时依赖 + 回归 | ⏸ **清理暂缓**（你决定先保留，避免服务起不来）；回归部分已完成：后端 265 测试、前端 68 测试、构建均通过。见 §9.2 §6.1 |

### 9.1 Step 2 落地清单（2026-09-22）

**新增代码**（`md-agent-server`）

| 文件 | 作用 |
| --- | --- |
| `controlplane/store/entity/*.java` | 12 个行模型 + `GovernedRow`（五类资源主表共有的 8 个治理字段抽象，用默认方法实现"新建 / 保存 / 发布"的字段推进） |
| `controlplane/store/mapper/*.java` | 10 个 Mapper。带自定义 SQL 的只有三个：`AgentRefMapper`（三个 `JSON_CONTAINS` 反查）、`AgentSubAgentMapper.findAgentNamesBySubName`、`ResourceVersionMapper.listVersionHeaders`（投影查询，不取 `snapshot_json`） |
| `controlplane/store/ControlPlaneStore.java` | 存储契约，六节：agent 定义读 / agent 写 / prompt / mcp / skill / 发布·回滚·版本·引用 |
| `controlplane/store/MyBatisControlPlaneStore.java` | 唯一实现；每个写方法标 `@Transactional` —— 本项目首个事务点 |
| `controlplane/store/AgentDefinitionBundle.java` | 一个 agent 的 6 张表行束：装配读、保存写、发布快照三用 |
| `controlplane/store/SkillSnapshot.java` / `ResourceVersionInfo.java` | skill 快照载荷 / 版本列表投影 |
| `controlplane/store/ControlPlaneValidationException.java` | 收集式校验异常（一次报全，由控制器翻译成 400 / 409） |
| `config/ControlPlaneStoreConfig.java` | 装配官方 skill 仓库 + Store；skill 库名走 `agent-platform.control-plane-skill-database`（默认 `agentscope`） |
| `ControlPlaneGovernanceTest` / `MyBatisControlPlaneStoreIT` | 治理字段单测 5 个 / 真库 IT 9 个 |

**与原设计的差异（都是动手后发现的）**

1. `loadDefinition()` 改名为 `loadAgentBundle()`：它返回的是**行束**而不是 `AgentDefinition`。实体到领域对象的转换只发生在 Step 3 的 `AgentDefinitionResolver` 一处（`AgentEntity` 类注释里的约定），接口方法名不该抢那个语义。
2. 新增 `GovernedRow`：四张资源主表的 8 个治理字段逐字相同，"新建 / 保存 / 发布"的字段推进抽成默认方法后只写一遍，四个实体只需 `implements`。
3. 保存 / 回滚用**事务内先删后插**（D15），只有"发布"这类只改状态的动作用 `updateById`。
4. 删除资源时**一并删除版本快照**（D16）。
5. 子 agent 表的自增主键在每次替换时清空：回滚时子行来自快照，带着上一世的自增 id，不清会撞主键。

**实测结论（真库验证过，Step 3 直接受影响）**

- **官方 skill 仓库本身不慢，慢的是连接方式**：Hikari 连接池下 `skillExists` 29ms / `getSkill` 58ms / `getAllSkillNames` 30ms；而**无池数据源**下一模一样的调用是 **5~7 秒一次**（它每次都自己 `getConnection()`，公网握手一次就要 1 秒）。IT 因此改用 Hikari，耗时从 219s 降到 33s。结论：装配期**按需**读 skill 完全没问题，但不要每次装配都把 36 个 skill 全量拉一遍。
- **官方 `getSkill` 对不存在的名字抛 `IllegalArgumentException`**，不返回 null。`findSkillContent` 因此先用 `skillExists` 判存在，把"没有"统一成 null —— 接口层不该让调用方靠捕获异常来判断资源存在性。
- **10 张表的列与实体字段已逐列核对一致**（`SHOW COLUMNS` 对过），所以"整体替换"不会丢列。
- **`@Transactional` 的生效前提**：`spring-boot-starter-jdbc` 已在依赖里，容器会自动装配 `DataSourceTransactionManager`，无需额外配置；但**事务方法不能被同类内部调用**（那样绕过代理、注解形同虚设），所以实现里的私有辅助方法一律不标注解，由公开的事务方法调用。
- **IT 验证不到事务回滚**：它不启 Spring 容器，`@Transactional` 在其中不生效（见 §10.8）。

---

### 9.2 Step 3–6 落地清单（2026-09-22）

Step 3 到 Step 6 的完整记录（新增文件、与设计的差异、实测数据、验证结果、
以及两件"代码完成但没实跑"的事）见
[`2026-09-22_step3-6-assembly-import-api-frontend.md`](./2026-09-22_step3-6-assembly-import-api-frontend.md)。

要看三件事直接跳过去：

1. **技能白名单装饰器为什么必须有** —— 不给白名单，框架会把全库技能列进每个 agent 的提示词；
2. **导入器分阶段的取舍** —— 先对账后写入（任一红项零写入），不做"边导边报错"；
3. **AgentRow 从计数改成名字清单的理由** —— 资源页要反查"谁引用了我"，而列表本来就是全量读。

---

### 9.3 Step 7 落地清单（2026-09-22）：页面按类型拆分 + 类型不可变

完整记录见
[`2026-09-22_agent-page-split-and-type-immutability.md`](./2026-09-22_agent-page-split-and-type-immutability.md)。
三条**改了数据模型**的结论，改前先读这里：

1. **三项技能策略（`dynamic_skills_enabled` / `skill_code_execution_enabled` / `skill_work_dir`）
   从 `md_agent_workspace` 迁到 `md_agent_runtime`**。它们只定义在 `ReActAgent.Builder` 上，
   而 REACT 根本不建 workspace 行 —— 留在旧表里等于"REACT 的字段放在 HARNESS 的表上"，
   既读不到也说不通。迁移后 `md_agent_workspace` 是**纯 HARNESS 表**。
2. **子 agent 只允许 REACT**。HARNESS 是编排体（它去派发别人），再让它当别人的子 agent
   会让"谁是编排根"变得不确定，也绕开了治理面（工作区、上下文压缩在父链上无人配置）。
3. **`agent_type` 创建后不可变更**（后端硬校验，返回 400）。改类型不是改一个字段，而是换整套
   行结构：REACT 没有 workspace 行与子 agent，HARNESS 没有冲突策略与三项技能策略。
   改的瞬间草稿会丢掉一半配置（`GovernedRow.advanceDraft()` 不继承 `agentType`，
   `replaceAgentRows()` 先删后插），所以宁可直接拒绝。

---

## 10. 已知缺口与后续

1. **历史 run 与定义版本的关联**：现在 run 只存了 prompt 正文快照，没有记录 `agent_version`。做完版本快照后，应在 run 上记录当时用的 `agent_version` / `prompt_version`，才能真正复现"当时是拿哪版定义跑的"。**本次不做，列为下一个迭代**。
2. **官方 skill 表的写入没有事务保护**：官方 `save()` 是"先删后插"，且不接受外部事务。若在删完之后进程崩溃，会留下半个 skill。缓解：先写官方表再写 `md_skill_meta`，并在对账时校验两边是否一致。
3. **草稿/发布态与"已发布但未生效"**：发布触发的是**进程内热装配**，多实例部署时其他实例不会自动感知。当前是单实例，**多实例广播（Redis pub/sub 或 MQ）不在本次范围**。
4. **三态开关**：一批 `disableXxx` 是「未设置 / true / false」三态，DB 用 `NULL` 表达"未设置（用框架默认）"，`0/1` 表达显式关闭/开启。页面必须能区分"未设置"和"false"，否则会把框架默认值写死到库里。
5. **Nacos 依赖清理**：`agentscope-extensions-nacos-skill` / `-nacos-prompt` / `-mcp-nacos` / `nacos-client` 在 Step 7 评估是否可移除（注意：`nacos-config` 仍可能被 `bootstrap.yml` 的配置中心用于拉配置，不能一并删）。
6. **删除的引用保护目前只覆盖 agent**：定时任务、channel 绑定也可能引用 prompt，Step 5 需把引用检查做成可扩展的（先查 agent，预留其他引用方）。
7. **`agentscope_skills` 与 `md_skill_meta` 的一致性**：如果有人绕过控制面直接改官方表，meta 会漂移。缓解：列表页读取时用官方表 name 集合做一次差集校验，发现孤立记录时告警。
8. **IT 覆盖不到事务回滚**：`MyBatisControlPlaneStoreIT` 不启 Spring 容器，`@Transactional` 在其中不生效（注解要有代理才生效）。"6 张表要么全写、要么全不写"这条要靠 Step 5 的容器级测试来守。
9. **版本快照只增不减**：删资源会连快照一起删（D16），但每次发布都会新增一份，长期没有保留期或归档策略。量大了要补一个类似 `TraceRetentionJob` 的清理任务，或者只保留最近 N 版 + 所有已发布版本。
