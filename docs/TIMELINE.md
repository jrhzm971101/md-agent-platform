# 项目演进时间线

> 记录 Md-Agent-Platform 从运行时骨架到可治理平台的持续迭代。
> 开发文档按 `YYYY-MM-DD_主题.md` 命名，目录内按文件名排序即时间顺序；本文提供跨文档的阶段视图。

相关入口：

- [开发文档索引](develop/README.md)
- [数据库初始化脚本](../md-agent-bootstrap/src/main/resources/db/README.md)
- [历史 SQL 迁移归档](archive/db-migrations/README.md)

## 阶段总览

| 阶段 | 时间 | 主题 | 关键产出 |
| --- | --- | --- | --- |
| 1 | 2026-09-20 ~ 09-22 | 运行时链路与控制面迁移 | 运行观测、页面对话 SSE、Agent / Prompt / Skill / MCP 入 MySQL、渠道控制面 |
| 2 | 2026-09-23 | 平台能力补全 | 模型、环境目录、工作流、会话记录、工作区落库 |
| 3 | 2026-09-24 | 稳定性与体验修复 | 错误响应体、MCP 装配、工作流页面缺陷 |
| 4 | 2026-09-28 ~ 09-30 | 子 Agent 与平台收口 | 子 Agent 事件 / 同步异步、Nacos 退场、统一门户与历史会话 |
| 5 | 2026-11-03 ~ 11-07 | 环境目录实例化 | 多实例、全局实例元数据、环境绑定、配置归实例 |
| 6 | 2026-11-04 ~ 11-13 | 工具生态与生产化 | GitLab 全 API 化、SkyWalking 分析、生产就绪清单 |
| 7 | 2026-11-17 | 仓库工程化 | README / 示例配置、SQL 收敛为 `init.sql`、运行时数据清理、时间线 |

## 详细时间线

### 2026-09-20 · 预研与观测起点

- 剖析 AgentScope Service 的前端与整体架构，确定技术栈与平台边界：  
  [`agentscope-service-frontend-anatomy.md`](agentscope-service-frontend-anatomy.md)
- 完成控制台页面预览稿：  
  [`design/console-preview.html`](design/console-preview.html)
- 建立运行链路观测模型（run / event / 阶段树），完成 Step 1 与前端联调：  
  [`develop/2026-09-20_runtime-trace-observability.md`](develop/2026-09-20_runtime-trace-observability.md)

### 2026-09-21 · 控制面迁移与三条链路打通

- Agent / Prompt / Skill / MCP 从 Nacos 迁入 MySQL，确定「单源控制面」：  
  [`develop/2026-09-21_control-plane-metadata.md`](develop/2026-09-21_control-plane-metadata.md)
- 运行观测 Step 2：run / event 落库，统一 runId 语义：  
  [`develop/2026-09-21_trace-persistence-and-runid.md`](develop/2026-09-21_trace-persistence-and-runid.md)
- 运行观测 Step 3：SSE 实时事件流：  
  [`develop/2026-09-21_trace-sse-live-stream.md`](develop/2026-09-21_trace-sse-live-stream.md)
- 页面对话 SSE 链路：  
  [`develop/2026-09-21_chat-page-and-sse.md`](develop/2026-09-21_chat-page-and-sse.md)
- Controller 全部收口到 bootstrap，固定模块边界：  
  [`develop/2026-09-21_controller-consolidation.md`](develop/2026-09-21_controller-consolidation.md)
- 对应 SQL：`archive/db-migrations/2026-09/control-plane-schema.sql`、`archive/db-migrations/2026-09/trace-schema.sql`

### 2026-09-22 · 渠道、状态与前端治理

- 渠道与网关从 `application.yml` 迁入 MySQL，建立 Channel / Binding / Gateway 控制面：  
  [`develop/2026-09-22_channel-and-gateway-control-plane.md`](develop/2026-09-22_channel-and-gateway-control-plane.md)
- 会话状态从业务库 / 本地文件收敛到独立状态库：  
  [`develop/2026-09-22_state-store-refactor.md`](develop/2026-09-22_state-store-refactor.md)
- 装配换源 DB、元数据导入器、控制面接口与前端治理页：  
  [`develop/2026-09-22_step3-6-assembly-import-api-frontend.md`](develop/2026-09-22_step3-6-assembly-import-api-frontend.md)
- Agent 页面拆分与 `agentType` 不可变：  
  [`develop/2026-09-22_agent-page-split-and-type-immutability.md`](develop/2026-09-22_agent-page-split-and-type-immutability.md)
- 前端运行时缺陷复盘与全路由冒烟：  
  [`develop/2026-09-22_frontend-runtime-bugs-and-route-smoke.md`](develop/2026-09-22_frontend-runtime-bugs-and-route-smoke.md)
- 形成第一版架构总览：  
  [`develop/2026-09-22_architecture-overview.md`](develop/2026-09-22_architecture-overview.md)
- 对应 SQL：`archive/db-migrations/2026-09/channel-schema.sql`、`archive/db-migrations/2026-09/channel-seed.sql`

### 2026-09-23 · 平台能力补全

- 模型进入控制面，概览页接入真实数据：  
  [`develop/2026-09-23_control-plane-model-and-overview.md`](develop/2026-09-23_control-plane-model-and-overview.md)
- 环境目录落库（环境 / 分组 / 键 / 实例 / 配置 / 绑定六表）：  
  [`develop/2026-09-23_environment-catalog.md`](develop/2026-09-23_environment-catalog.md)
- Harness 工作区数据进入 MySQL（本地 / 远端双后端）：  
  [`develop/2026-09-23_harness-workspace-to-mysql.md`](develop/2026-09-23_harness-workspace-to-mysql.md)
- 会话记录按 `(user_id, session_id)` 聚合，补上「人」这一层：  
  [`develop/2026-09-23_session-management.md`](develop/2026-09-23_session-management.md)
- 工作流 DAG 第一批能力（agent / condition / join）：  
  [`develop/2026-09-23_workflow-design.md`](develop/2026-09-23_workflow-design.md)
- 对应 SQL：`archive/db-migrations/2026-09/env-schema.sql`、`archive/db-migrations/2026-09/workflow-schema.sql`、`archive/db-migrations/2026-09/model-schema-upgrade-2026-09-23.sql`、`archive/db-migrations/2026-09/model-seed.sql`、`archive/db-migrations/2026-09/workspace-kv-schema-upgrade-2026-09-23.sql`

### 2026-09-24 · 稳定性与体验修复

- 资源不存在统一返回可读错误体：  
  [`develop/2026-09-24_error-response-bodies.md`](develop/2026-09-24_error-response-bodies.md)
- 修复页面发布后 MCP 工具丢失（事件循环注册问题）：  
  [`develop/2026-09-24_mcp-not-registered-on-event-loop.md`](develop/2026-09-24_mcp-not-registered-on-event-loop.md)
- 修复工作流详情从缓存进入时空白：  
  [`develop/2026-09-24_workflow-designer-empty-from-cache.md`](develop/2026-09-24_workflow-designer-empty-from-cache.md)
- 补齐工作流运行入口与自动弹窗判断：  
  [`develop/2026-09-24_workflow-run-entry.md`](develop/2026-09-24_workflow-run-entry.md)

### 2026-09-28 · 子 Agent 与交付预检

- 子 Agent 派发正名为独立事件，修复两个真实缺陷：  
  [`develop/2026-09-28_subagent-events-step5.md`](develop/2026-09-28_subagent-events-step5.md)
- 子 Agent 同步 / 异步可控、可观测、可归位：  
  [`develop/2026-09-28_subagent-sync-async-step6.md`](develop/2026-09-28_subagent-sync-async-step6.md)
- 工时审计技能 v3 → v4，收紧取数纪律：  
  [`develop/2026-09-28_timesheet-audit-skill-v3.md`](develop/2026-09-28_timesheet-audit-skill-v3.md) ·  
  [`develop/2026-09-28_timesheet-audit-skill-v4.md`](develop/2026-09-28_timesheet-audit-skill-v4.md)
- 新服务器库与本机库结构核查：  
  [`develop/2026-09-28_new-server-schema-diff.md`](develop/2026-09-28_new-server-schema-diff.md)
- 对应 SQL：`archive/db-migrations/2026-09/control-plane-schema-upgrade-2026-09-28-subagent.sql`、`archive/db-migrations/2026-09/trace-schema-upgrade-2026-09-28-subagent.sql`、`archive/db-migrations/2026-09/2026-09-28_deploy-schema-precheck.sql`

### 2026-09-29 · 热生效与 Nacos 退场

- 渠道热生效补齐路由表刷新，解决「Agent 不存在: main」：  
  [`develop/2026-09-29_channel-route-table-hot-reload.md`](develop/2026-09-29_channel-route-table-hot-reload.md)
- 彻底移除 Nacos 作为 Agent 元数据来源，控制面 DB 成为唯一生产路径：  
  [`develop/2026-09-29_nacos-ai-removal.md`](develop/2026-09-29_nacos-ai-removal.md)
- 早期 Nacos 方案归档：  
  [`archive/nacos-ai-removal/`](archive/nacos-ai-removal/)

### 2026-09-30 · 门户化与存储清点

- 平台切换弹窗、返回统一门户、对话页历史会话：  
  [`develop/2026-09-30_platform-switch-portal-and-chat-history.md`](develop/2026-09-30_platform-switch-portal-and-chat-history.md)
- 工作区存储清点，明确哪些在 MySQL、哪些在本地盘：  
  [`develop/2026-09-30_workspace-storage-inventory.md`](develop/2026-09-30_workspace-storage-inventory.md)

### 2026-11-03 ~ 11-07 · 环境目录实例化

- **11-03**：一个分组支持多套实例；实例元数据收敛为分组级全局数据；删除 `country` / `stage`：  
  `archive/db-migrations/2026-11/env-schema-upgrade-2026-11-03-group-instances.sql` ·  
  `archive/db-migrations/2026-11/env-schema-upgrade-2026-11-03-global-group-instances.sql` ·  
  `archive/db-migrations/2026-11/env-schema-upgrade-2026-11-03-remove-country-stage.sql`
- **11-05**：`application.yml` 环境源彻底移除，空库导入与数据库故障兜底路径一并删除。
- **11-06**：环境只记录「使用了哪些实例」，解绑不影响实例定义：  
  `archive/db-migrations/2026-11/env-schema-upgrade-2026-11-06-instance-binding.sql`
- **11-07**：配置值收敛到实例本身，唯一键改为 `(group_code, instance_code, key_code)`：  
  `archive/db-migrations/2026-11/env-schema-upgrade-2026-11-07-instance-config.sql`
- 环境目录文档同步补记以上四次演进：  
  [`develop/2026-09-23_environment-catalog.md`](develop/2026-09-23_environment-catalog.md)

### 2026-11-04 ~ 11-05 · Git 工具全 API 化

- 移除 JGit 依赖，`git-*` 工具全部改为 GitLab REST API + 工作区物化：  
  [`gitlab-api-workspace-tools.md`](gitlab-api-workspace-tools.md)
- GitLab API 工具能力说明：  
  [`gitlab-api-tools.md`](gitlab-api-tools.md)
- 从部署镜像反查流水线与代码：  
  [`gitlab-pipeline-image-to-code.md`](gitlab-pipeline-image-to-code.md)
- 旧 JGit 实现归档说明：  
  [`git-tools-jgit-capabilities.md`](git-tools-jgit-capabilities.md)

### 2026-11-10 · 生产链路分析

- SkyWalking 链路耗时分析：  
  [`analysis/skywalking-customers-encryptPhone-20261110.md`](analysis/skywalking-customers-encryptPhone-20261110.md)
- 17:00 后请求突增分析：  
  [`analysis/skywalking-openapi-17h-burst-20261110.md`](analysis/skywalking-openapi-17h-burst-20261110.md)

### 2026-11-13 · 生产就绪

- 工时统计 Agent 的生产就绪检查清单（逐项缺口与验收）：  
  [`agent-production-readiness.md`](agent-production-readiness.md)
- Nacos 退场文档补充后续结论：  
  [`develop/2026-09-29_nacos-ai-removal.md`](develop/2026-09-29_nacos-ai-removal.md)

### 2026-11-17 · 仓库工程化收口

- 新增仓库级 `README.md`、`.gitignore`、`application.example.yml` 与 K8s 配置样例。
- 清理运行时数据、日志、备份与构建产物，仓库只保留源码、文档与定义类资产。
- 把 19 份分域基线与增量 SQL 收敛为一份当前完整基线：  
  [`../md-agent-bootstrap/src/main/resources/db/init.sql`](../md-agent-bootstrap/src/main/resources/db/init.sql)
- 可选种子数据独立为：  
  [`../md-agent-bootstrap/src/main/resources/db/seed.example.sql`](../md-agent-bootstrap/src/main/resources/db/seed.example.sql)
- 历史脚本按月份归档到 [`archive/db-migrations/`](archive/db-migrations/)，并在本文件建立总时间线。
