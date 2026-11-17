# Md-Agent-Platform

**多交互源集中治理的 AI Agent 平台**

用一套 MySQL 控制面统一管理 Agent 的身份、提示词、技能、MCP、工具、模型与渠道配置，发布后热装配到运行中的实例；同一套 Agent 同时服务企业微信、飞书和页面 SSE 对话，并把每次运行沉淀成可回放的事件树。

## 为什么需要它

Agent 从 Demo 走向生产，难点通常不在「模型会不会回答」，而在治理：

- 提示词、技能、MCP 端点散落在 YAML、配置中心和代码里，改一处要重启一次；
- 企微、飞书、钉钉各有回调、会话键和回复格式，同一份能力要写多套入口；
- 链路变长后只能看到最终回复，看不到第几轮推理调了哪个工具、在哪一步失败；
- 多环境、多实例的连接信息没有统一目录，配错一次就是线上事故。

Md-Agent-Platform 把这些事情收敛为 **一个控制面 + 四条运行时链路**。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 控制面集中治理 | Agent / Prompt / Skill / MCP / Tool / Model / Channel / Workflow 全部落 MySQL，支持草稿、发布、快照、回滚与操作人审计 |
| 发布即热装配 | 发布后动态注册、卸载、重载 Agent 实例，同步失效 MCP 连接缓存，无需重启进程 |
| 多 IM 渠道接入 | 企业微信（应用 / 机器人）、飞书（应用 / 机器人）、钉钉、GitLab；统一路由与按渠道能力渲染回复 |
| 页面对话 | `POST /api/agents/{agentId}/chat` 以 SSE 流式返回，runId 语义与 IM 渠道一致 |
| 全链路观测 | run / event 内存热数据 + MySQL 归档 + SSE 实时事件树，支持子 Agent 父子链路与会话聚合 |
| 会话与状态 | 会话记录、按 Agent 隔离的 State Store、工作区 KV（可切换本地 / 远端后端） |
| 工具生态 | MySQL/SQL、Elasticsearch/RAG、SkyWalking、Grafana、GitLab、Nacos、NineData、Workday、RabbitMQ、XXL-Job、容器日志等 |
| 工作流与子 Agent | agent / condition / join 节点编排，子 Agent 支持同步与异步执行形态 |
| 环境目录 | 环境 → 分组 → 实例三级元数据，为工具提供多环境连接信息与凭据引用 |

## 项目演进

- **2026-09-20 ~ 09-22**：运行观测、页面对话 SSE、控制面元数据与渠道迁移。
- **2026-09-23 ~ 09-24**：环境目录、模型、工作流、会话记录，以及稳定性修复。
- **2026-09-28 ~ 09-30**：子 Agent 同步 / 异步、Nacos 退场、统一门户与历史会话。
- **2026-11-03 ~ 11-13**：环境目录实例化、GitLab 工具全 API 化、生产链路分析与就绪检查。
- **2026-11-17**：仓库工程化，SQL 收敛为 `init.sql`，补齐示例配置与时间线。

完整记录见 [`docs/TIMELINE.md`](docs/TIMELINE.md)。

## 架构

```text
                     ┌──────────── 控制面 HTTP API ────────────┐
                     │ Agents · Prompts · Skills · MCP · Tools │
                     │ Models · Channels · Gateway · Workflows │
                     └───────────────────┬─────────────────────┘
                                         │ 草稿 / 发布 / 回滚（快照 + 审计）
                                         ▼
                     ┌───────── MySQL（唯一元数据源）─────────┐
                     │ 控制面 11 张 + 渠道 3 张 + 链路 2 张 … │
                     └──────┬────────────────────────┬────────┘
                            │ 发布触发热装配          │ 读写
                            ▼                        ▼
  企微 / 飞书 / 钉钉 / GitLab ──▶ ChannelRouter ──▶ AgentManager ──▶ Agent 实例
                                                        │
  页面 POST /api/agents/{id}/chat（SSE）────────────────┤
                                                        ▼
                            AgentRunTraceMiddleware ──▶ 内存热数据
                                                        ├─ 异步批量 ─▶ md_agent_run / md_agent_run_event
                                                        └─ SSE 唤醒 ─▶ 执行链路事件树
```

四条运行时链路：

1. **渠道消息**：IM 回调 → 渠道适配器 → `ChannelRouter` → `MessageRouter` → Agent → 渠道渲染器回复。
2. **页面对话**：HTTP SSE → Agent → 统一事件帧 → 前端 Markdown 渲染。
3. **运行观测**：中间件采集 run / event → 内存热数据 → 异步归档 / SSE 实时推送。
4. **发布装配**：控制面写库 → 发布服务生成快照 → `AgentManager` 热重载。

## 模块

| 模块 | 职责 |
| --- | --- |
| `md-agent-model` | 领域模型，无 Spring / DB 依赖 |
| `md-agent-server` | 平台内核：装配、控制面 Store 与校验、渠道运行时、消息路由、渲染、链路观测、工具 |
| `md-agent-bootstrap` | 应用外壳：全部 Controller、启动类、配置、DDL 与部署清单 |

## 技术栈

- Java 21、Spring Boot 3.5（WebFlux）、Spring Cloud 2025
- AgentScope 2.1（harness、OpenAI 兼容模型、渠道扩展、MySQL 扩展）
- MyBatis-Plus 3.5、MySQL 8、Redis、Elasticsearch 9
- Nacos（可选配置中心）、XXL-Job
- Lombok、Swagger Annotations

## 快速开始

### 1. 环境要求

- JDK 21、Maven 3.9+
- MySQL 8.0（必需）
- Redis 6+（检索权限 / 缓存）
- Elasticsearch 9（使用 RAG 检索时需要）
- Nacos、XXL-Job（可选）

### 2. 初始化数据库

创建业务库后执行 `init.sql`（包含 26 张表与 3 个运行时库）：

```bash
mysql -h <host> -P <port> -u <user> -p \
  -e "CREATE DATABASE IF NOT EXISTS \`md-agent-platform\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h <host> -P <port> -u <user> -p md-agent-platform < md-agent-bootstrap/src/main/resources/db/init.sql
```

可选种子数据（默认模型 + 示例渠道）见 `md-agent-bootstrap/src/main/resources/db/seed.example.sql`，执行前替换 `REPLACE_WITH_*` 占位符。

历史增量脚本已归档到 `docs/archive/db-migrations/`，只用于既有库升级。

### 3. 配置

```bash
cd md-agent-bootstrap/src/main/resources
cp application.example.yml application.yml
```

然后填写 `application.yml`，或通过环境变量注入：

| 变量 | 说明 |
| --- | --- |
| `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 控制面数据库 |
| `REDIS_HOST` / `REDIS_PORT` | Redis |
| `ES_URIS` / `ES_USERNAME` / `ES_PASSWORD` | Elasticsearch |
| `MODEL_API_KEY` / `MODEL_BASE_URL` / `MODEL_NAME` | 对话模型 |
| `CONFLUENCE_BASE_URL` / `CONFLUENCE_USERNAME` / `CONFLUENCE_PASSWORD` | Wiki 同步 |
| `XXL_JOB_ADMIN_ADDRESSES` / `XXL_JOB_ACCESS_TOKEN` | 调度中心 |
| `GITLAB_BASE_DIR` | 代码快照目录 |
| `SERVER_CONTEXT_PATH` | 接口前缀，默认 `/agent-platform` |
| `WORKSPACE_ROOT` | Agent 工作区根目录 |

### 4. 构建与启动

```bash
mvn -DskipTests clean package
java -jar md-agent-bootstrap/target/md-agent-bootstrap-0.0.1-SNAPSHOT.jar
```

开发模式：

```bash
mvn -pl md-agent-bootstrap -am spring-boot:run
```

### 5. 验证

```bash
curl http://localhost:9091/agent-platform/actuator/health
curl -N -X POST http://localhost:9091/agent-platform/api/agents/{agentId}/chat \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"你好\"}"
```

## 测试

```bash
mvn test
```

真库集成测试类名以 `IT` 结尾，不在 `mvn test` 默认范围内，需要显式指定并提供 `MYSQL_URL` / `MYSQL_PASSWORD`：

```bash
mvn -Dtest=MyBatisControlPlaneStoreIT -Dsurefire.failIfNoSpecifiedTests=false test
```

## 项目结构

```text
.
├── md-agent-model/                  # 领域模型
├── md-agent-server/                 # 平台内核
│   └── src/main/java/.../server/
│       ├── agent/                   # 装配层与运行时注册表
│       ├── controlplane/            # 控制面 Store / 校验 / 发布
│       ├── channel/  messaging/     # 渠道、网关、消息路由与渲染
│       ├── trace/                   # 运行链路观测
│       ├── workflow/  session/      # 工作流、会话
│       ├── environment/             # 环境目录
│       └── tool/                    # 内置工具集
├── md-agent-bootstrap/              # 启动类、Controller、配置、DDL、K8s 清单
├── docs/                            # 架构与开发文档
└── pom.xml
```

## 文档

- `docs/TIMELINE.md`：项目演进时间线
- `docs/develop/README.md`：开发文档索引
- `docs/develop/2026-09-22_architecture-overview.md`：架构总览与关键取舍
- `docs/develop/2026-09-21_control-plane-metadata.md`：控制面元数据设计
- `docs/develop/2026-09-22_channel-and-gateway-control-plane.md`：渠道与网关
- `docs/develop/2026-09-20_runtime-trace-observability.md`：运行链路观测
- `docs/develop/2026-09-23_workflow-design.md`：工作流设计
- `docs/agent-production-readiness.md`：生产就绪清单
- `md-agent-bootstrap/src/main/resources/db/README.md`：数据库脚本与表清单

## 安全说明

- `application.yml`、`k8s/md-agent-platform-cm.yaml`、Secret 清单和运行时 `workspace/` 均已被 `.gitignore` 忽略。
- 仓库只提交 `application.example.yml` 与 `md-agent-platform-cm.example.yaml`，其中不含真实凭据。
- 如果这些凭据曾经被提交过，请在推送前轮换密码与 Token；仅删除文件不会从 Git 历史中移除它们。
- 文档与工具中仍包含内网地址、公司域名等环境信息，公开前请按需脱敏。

## License

当前未指定开源许可证。如需公开，请补充 `LICENSE`（例如 MIT 或 Apache-2.0）；未声明时默认保留所有权利。
