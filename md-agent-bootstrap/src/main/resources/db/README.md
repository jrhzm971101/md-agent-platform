# 数据库脚本

## 文件

| 文件 | 用途 |
| --- | --- |
| `init.sql` | 全新环境初始化：26 张业务表 + 3 个运行时数据库 |
| `seed.example.sql` | 可选种子数据：默认模型 + 示例渠道（含占位符） |

## 初始化步骤

```bash
# 1. 创建业务库（库名可自定义）
mysql -h <host> -P <port> -u <user> -p \
  -e "CREATE DATABASE IF NOT EXISTS \`md-agent-platform\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. 建表
mysql -h <host> -P <port> -u <user> -p md-agent-platform < init.sql

# 3. 可选：写入默认模型与示例渠道，执行前替换 REPLACE_WITH_*
mysql -h <host> -P <port> -u <user> -p md-agent-platform < seed.example.sql
```

## 表清单

| 域 | 表 |
| --- | --- |
| 控制面（11） | `md_agent` `md_agent_ref` `md_agent_runtime` `md_agent_memory` `md_agent_workspace` `md_agent_sub_agent` `md_prompt` `md_mcp_server` `md_skill_meta` `md_resource_version` `md_model` |
| 渠道（3） | `md_channel` `md_channel_binding` `md_gateway` |
| 链路（2） | `md_agent_run` `md_agent_run_event` |
| 工作流（4） | `md_workflow` `md_workflow_node_ref` `md_workflow_run` `md_workflow_run_node` |
| 环境（6） | `md_environment` `md_env_group` `md_env_group_key` `md_env_group_instance` `md_env_instance_config` `md_env_instance_binding` |

## 由框架自动建表

以下库只需存在，表结构由 AgentScope 在首次使用时创建，`init.sql` 末尾只预创建库：

| 库 | 用途 | 配置项 |
| --- | --- | --- |
| `agentscope` | 技能内容表（`MysqlSkillRepository`） | `agent-platform.control-plane-skill-database` |
| `md_agent_state` | 会话状态表（`MysqlAgentStateStore`） | `agent-platform.state-store-database` |
| `md_agent_workspace` | 工作区 KV（仅 `fs_backend=REMOTE`） | `agent-platform.workspace-kv-database` |

## 约定

- 所有 JSON 列在实体上必须配 `@TableName(autoResultMap = true)` + `JacksonTypeHandler`，否则会出现「写得进去、读回来是 null」。
- `TINYINT(1)` 开关是三态：`NULL` = 未设置，`0` = 显式关闭，`1` = 显式开启。
- 不使用外键与级联删除，引用完整性由应用层校验。
- 时间统一使用 `BIGINT` 存 epoch millis，不引入时区换算。
- 历史增量脚本已归档到 `docs/archive/db-migrations/`，只用于既有库升级；全新库只执行 `init.sql`。
