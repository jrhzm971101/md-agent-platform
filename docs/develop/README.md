# 开发文档索引

> 按时间顺序整理。文件名前缀为 `YYYY-MM-DD`，目录内排序即时间线。
> 跨阶段的整体演进见 [../TIMELINE.md](../TIMELINE.md)。

## 2026-09-20 ~ 09-21 · 观测与控制面

| 日期 | 文档 | 主题 |
| --- | --- | --- |
| 09-20 | [`2026-09-20_runtime-trace-observability.md`](2026-09-20_runtime-trace-observability.md) | 运行链路观测 Step 1：模型与前端联调 |
| 09-21 | [`2026-09-21_control-plane-metadata.md`](2026-09-21_control-plane-metadata.md) | Agent / Prompt / Skill / MCP 迁入 MySQL |
| 09-21 | [`2026-09-21_trace-persistence-and-runid.md`](2026-09-21_trace-persistence-and-runid.md) | 观测 Step 2：落库与 runId 改造 |
| 09-21 | [`2026-09-21_trace-sse-live-stream.md`](2026-09-21_trace-sse-live-stream.md) | 观测 Step 3：SSE 实时事件流 |
| 09-21 | [`2026-09-21_chat-page-and-sse.md`](2026-09-21_chat-page-and-sse.md) | 页面对话的流式链路 |
| 09-21 | [`2026-09-21_controller-consolidation.md`](2026-09-21_controller-consolidation.md) | Controller 收口到 bootstrap |

## 2026-09-22 · 渠道、状态与前端治理

| 日期 | 文档 | 主题 |
| --- | --- | --- |
| 09-22 | [`2026-09-22_channel-and-gateway-control-plane.md`](2026-09-22_channel-and-gateway-control-plane.md) | Channel / Gateway 迁入 MySQL |
| 09-22 | [`2026-09-22_state-store-refactor.md`](2026-09-22_state-store-refactor.md) | 会话状态收敛到独立状态库 |
| 09-22 | [`2026-09-22_step3-6-assembly-import-api-frontend.md`](2026-09-22_step3-6-assembly-import-api-frontend.md) | 装配换源、导入器、控制面接口与前端 |
| 09-22 | [`2026-09-22_agent-page-split-and-type-immutability.md`](2026-09-22_agent-page-split-and-type-immutability.md) | Agent 页面拆分与类型不可变 |
| 09-22 | [`2026-09-22_frontend-runtime-bugs-and-route-smoke.md`](2026-09-22_frontend-runtime-bugs-and-route-smoke.md) | 前端缺陷复盘与全路由冒烟 |
| 09-22 | [`2026-09-22_architecture-overview.md`](2026-09-22_architecture-overview.md) | 第一版架构总览 |

## 2026-09-23 · 平台能力补全

| 日期 | 文档 | 主题 |
| --- | --- | --- |
| 09-23 | [`2026-09-23_control-plane-model-and-overview.md`](2026-09-23_control-plane-model-and-overview.md) | 模型进入控制面 + 概览页 |
| 09-23 | [`2026-09-23_environment-catalog.md`](2026-09-23_environment-catalog.md) | 环境目录六表（后续在 11 月实例化） |
| 09-23 | [`2026-09-23_harness-workspace-to-mysql.md`](2026-09-23_harness-workspace-to-mysql.md) | Harness 工作区数据进入 MySQL |
| 09-23 | [`2026-09-23_session-management.md`](2026-09-23_session-management.md) | 会话记录按 user / session 聚合 |
| 09-23 | [`2026-09-23_workflow-design.md`](2026-09-23_workflow-design.md) | 工作流 DAG 第一批能力 |

## 2026-09-24 · 稳定性与体验修复

| 日期 | 文档 | 主题 |
| --- | --- | --- |
| 09-24 | [`2026-09-24_error-response-bodies.md`](2026-09-24_error-response-bodies.md) | 资源不存在统一返回可读错误体 |
| 09-24 | [`2026-09-24_mcp-not-registered-on-event-loop.md`](2026-09-24_mcp-not-registered-on-event-loop.md) | 页面发布后 MCP 工具丢失 |
| 09-24 | [`2026-09-24_workflow-designer-empty-from-cache.md`](2026-09-24_workflow-designer-empty-from-cache.md) | 工作流详情从缓存进入时空白 |
| 09-24 | [`2026-09-24_workflow-run-entry.md`](2026-09-24_workflow-run-entry.md) | 工作流运行入口与弹窗判断 |

## 2026-09-28 · 子 Agent 与交付预检

| 日期 | 文档 | 主题 |
| --- | --- | --- |
| 09-28 | [`2026-09-28_subagent-events-step5.md`](2026-09-28_subagent-events-step5.md) | 子 Agent 派发独立事件 + 缺陷修复 |
| 09-28 | [`2026-09-28_subagent-sync-async-step6.md`](2026-09-28_subagent-sync-async-step6.md) | 子 Agent 同步 / 异步可控可观测 |
| 09-28 | [`2026-09-28_timesheet-audit-skill-v3.md`](2026-09-28_timesheet-audit-skill-v3.md) | 工时审计技能 v3 |
| 09-28 | [`2026-09-28_timesheet-audit-skill-v4.md`](2026-09-28_timesheet-audit-skill-v4.md) | 工时审计技能 v4：收紧取数纪律 |
| 09-28 | [`2026-09-28_new-server-schema-diff.md`](2026-09-28_new-server-schema-diff.md) | 新服务器库与本机库结构核查 |

## 2026-09-29 ~ 09-30 · 平台化收口

| 日期 | 文档 | 主题 |
| --- | --- | --- |
| 09-29 | [`2026-09-29_channel-route-table-hot-reload.md`](2026-09-29_channel-route-table-hot-reload.md) | 渠道热生效补齐路由表刷新 |
| 09-29 | [`2026-09-29_nacos-ai-removal.md`](2026-09-29_nacos-ai-removal.md) | 彻底移除 Nacos 元数据来源 |
| 09-30 | [`2026-09-30_platform-switch-portal-and-chat-history.md`](2026-09-30_platform-switch-portal-and-chat-history.md) | 统一门户与对话历史 |
| 09-30 | [`2026-09-30_workspace-storage-inventory.md`](2026-09-30_workspace-storage-inventory.md) | 工作区存储清点 |

## 跨阶段资料

- [`../agent-production-readiness.md`](../agent-production-readiness.md)：生产就绪检查清单
- [`../agentscope-service-frontend-anatomy.md`](../agentscope-service-frontend-anatomy.md)：AgentScope Service 架构预研
- [`../gitlab-api-tools.md`](../gitlab-api-tools.md)、[`../gitlab-api-workspace-tools.md`](../gitlab-api-workspace-tools.md)：GitLab 工具 API 化
- [`../analysis/`](../analysis/)：SkyWalking 生产链路分析
- [`../archive/db-migrations/`](../archive/db-migrations/)：历史 SQL 迁移
