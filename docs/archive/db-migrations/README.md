# 历史数据库脚本（归档）

> 全新环境请使用 `md-agent-bootstrap/src/main/resources/db/init.sql`，不要执行本目录脚本。
> 本目录保留 2026-09 ~ 2026-11 的分域基线与增量迁移，只用于既有库升级或历史追溯。
> 基线日期按对应设计文档 / 首次落库时间标注，增量脚本以文件名中的日期为准。

## 2026-09

| 日期 | 脚本 | 内容 | 现状 |
| --- | --- | --- | --- |
| 09-21 | [`2026-09/control-plane-schema.sql`](2026-09/control-plane-schema.sql) | 控制面 11 表基线 | 已合并进 `init.sql` |
| 09-21 | [`2026-09/trace-schema.sql`](2026-09/trace-schema.sql) | run / event 观测基线 | 已合并进 `init.sql` |
| 09-22 | [`2026-09/channel-schema.sql`](2026-09/channel-schema.sql) | 渠道 3 表基线 | 已合并进 `init.sql` |
| 09-22 | [`2026-09/channel-seed.sql`](2026-09/channel-seed.sql) | 示例渠道种子 | 已合并进 `seed.example.sql` |
| 09-23 | [`2026-09/model-schema-upgrade-2026-09-23.sql`](2026-09/model-schema-upgrade-2026-09-23.sql) | `md_model` 建表 | 已并入控制面基线 |
| 09-23 | [`2026-09/model-seed.sql`](2026-09/model-seed.sql) | 默认模型种子 | 已合并进 `seed.example.sql` |
| 09-23 | [`2026-09/env-schema.sql`](2026-09/env-schema.sql) | 环境目录 6 表基线 | 已合并进 `init.sql` |
| 09-23 | [`2026-09/workflow-schema.sql`](2026-09/workflow-schema.sql) | 工作流 4 表基线 | 已合并进 `init.sql` |
| 09-23 | [`2026-09/workflow-schema-upgrade-2026-09-23.sql`](2026-09/workflow-schema-upgrade-2026-09-23.sql) | run 补工作流两列 | 已并入 trace 基线 |
| 09-23 | [`2026-09/trace-schema-upgrade-2026-09-23-source.sql`](2026-09/trace-schema-upgrade-2026-09-23-source.sql) | run 补 `source` 与工作流列 | 已并入 trace 基线 |
| 09-23 | [`2026-09/workspace-kv-schema-upgrade-2026-09-23.sql`](2026-09/workspace-kv-schema-upgrade-2026-09-23.sql) | 工作区 KV 库 + `fs_backend` | 已并入基线 |
| 09-28 | [`2026-09/control-plane-schema-upgrade-2026-09-28-subagent.sql`](2026-09/control-plane-schema-upgrade-2026-09-28-subagent.sql) | 子 Agent 同步两列 | 已并入控制面基线 |
| 09-28 | [`2026-09/trace-schema-upgrade-2026-09-28-subagent.sql`](2026-09/trace-schema-upgrade-2026-09-28-subagent.sql) | run 补 `parent_run_id` / `task_id` | 已并入 trace 基线 |
| 09-28 | [`2026-09/2026-09-28_deploy-schema-precheck.sql`](2026-09/2026-09-28_deploy-schema-precheck.sql) | 新服务器库补列预检 | 历史运维脚本 |

## 2026-11

| 日期 | 脚本 | 内容 | 现状 |
| --- | --- | --- | --- |
| 11-03 | [`2026-11/env-schema-upgrade-2026-11-03-group-instances.sql`](2026-11/env-schema-upgrade-2026-11-03-group-instances.sql) | 分组单实例 → 多实例 | 历史升级 |
| 11-03 | [`2026-11/env-schema-upgrade-2026-11-03-global-group-instances.sql`](2026-11/env-schema-upgrade-2026-11-03-global-group-instances.sql) | 实例元数据收敛为分组级全局数据 | 历史升级 |
| 11-03 | [`2026-11/env-schema-upgrade-2026-11-03-remove-country-stage.sql`](2026-11/env-schema-upgrade-2026-11-03-remove-country-stage.sql) | 删除 `country` / `stage` | 历史升级 |
| 11-06 | [`2026-11/env-schema-upgrade-2026-11-06-instance-binding.sql`](2026-11/env-schema-upgrade-2026-11-06-instance-binding.sql) | 环境与实例绑定 | 历史升级 |
| 11-07 | [`2026-11/env-schema-upgrade-2026-11-07-instance-config.sql`](2026-11/env-schema-upgrade-2026-11-07-instance-config.sql) | 配置值收敛到实例本身 | 历史升级 |

> 增量脚本中的部分 `ALTER` 不幂等，禁止在新库执行；新库结构以 `init.sql` 为准。
