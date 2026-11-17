# 2026-09-29 彻底移除 Nacos 作为 agent 元数据来源 —— 改由控制面 DB 初始化

> 目标：**项目中「AI 相关资源」对 Nacos 的引用全量删除**，agent / prompt / skill / mcp 一律由控制面
> MySQL 表初始化（`source=db`，也是唯一生产路径）。
> 本文只记录本次改动，不重复 `2026-09-21_control-plane-metadata.md` 里已经写过的迁移设计。

---

## 一、先看结论

1. **Nacos 作为「agent 元数据的来源」这条链路已整体删除**：4 个类的字段/分支、6 个文件、3 个 Maven 依赖、1 段 yml 配置。
2. **Nacos 作为「配置中心」的用法不动**：`bootstrap.yml` 里的 `spring.cloud.nacos.config` /
   `spring.config.import` 仍在——生产 profile 靠它拉 `md-agent-platform.yaml`，删了服务就起不来。
3. **Nacos 作为「被 agent 操作的业务系统」不动**：`server/tool/nacos/*` 三个工具（后续已改为直连 OpenAPI，不再依赖 `nacos-client`）、
   `application.yml` 里各 `environment.*.nacos` 凭据全部保留（`nacos_agent` 与其两个 skill 在用）。
4. **数据库无需任何 DDL 变更**：表结构本来就已是控制面的形状，本次只删代码与配置。

判断口径：**「AI 资源来源」= 删；「AI 的资源被某个 agent 操作」= 留。** 两者只是都叫 Nacos 而已。

---

## 二、删除范围（`source=nacos` 这条链路）

### 2.1 删掉的文件（各项目 6 个，两个项目同步）

| 文件 | 原职责 |
| --- | --- |
| `md-agent-server/.../agent/NacosAgentSpecRepository.java` | 从 Nacos 全量拉 AgentSpec 当 agent 定义 |
| `md-agent-server/.../agent/AgentSpecConverter.java` | Nacos `AgentSpec` → `AgentDefinition` 映射 |
| `md-agent-server/.../agent/NacosMcpClientFactory.java` | 按名从 Nacos 拉 MCP 端点并建链 |
| `md-agent-server/.../config/NacosAgentProperties.java` | `agent-platform.nacos.*` 绑定 |
| `md-agent-server/.../config/AiServiceBeanConfig.java` | 构造 Nacos `AiService` bean |
| `md-agent-server/src/test/.../agent/NacosMcpClientFactoryTest.java` | 上述工厂的单测 |

### 2.2 改掉的文件

| 文件 | 改动 |
| --- | --- |
| `agent/AgentManager.java` | `init()` 由 db / local / nacos 三分支收敛为两分支：`local` 走本地 MD/JSON，**其余（默认）走 DB**；删掉 `nacosAgentSpecRepository` 字段与其 javadoc |
| `agent/AgentDefinition.java` | 6 处注释去掉「promptKey / skillNames / mcpNames 来自 Nacos」的措辞 |
| `agent/AgentProperties.java` | `source` 默认值 `nacos` → `db`；javadoc 同步为 `db（默认，唯一生产路径）\| local` |
| `agent/ConfigurableAgentBuilder.java` | 删 3 个 import、3 个字段（`aiService` / `nacosAgentProperties` / `mcpClientFactory`）与 `isDbSource()`；`resolveMcpBindings()` 与 `resolveSkillRepository()` 各去掉 Nacos 分支后**只剩单路**（MCP 名单的唯一归属变成 `md_mcp_server` 行） |
| `controlplane/ControlPlanePublishService.java` | 热装配门禁 `!"db".equalsIgnoreCase(source)` → `"local".equalsIgnoreCase(source)`；语义不变（默认已是 db），措辞去掉 nacos |
| `config/BaseConfig.java` | 删 4 行无用 nacos import，以及同样已成死代码的 `java.util.Properties` |
| `agent/DbMcpClientFactory.java` | javadoc 不再以「与 `NacosMcpClientFactory` 同构」自述（那个类已不存在），改为直接描述自身设计 |
| `controlplane/store/entity/McpServerEntity.java` | 注释里指向 `NacosMcpClientFactory` 的两处改为 `DbMcpClientFactory` |
| `controller/test/TestController.java` | 删 nacos import、`GET /test` 端点（通篇是 `AiFactory.createAiService` 样例）、文件末尾的 `main()`。保留 `/test/wecom-mcp` 与 `/test/workspace/*` |
| `md-agent-bootstrap/src/main/resources/application.yml` | 删 `agent-platform.nacos.*` 整段与注释掉的 `# source: nacos`；`agents.source` 说明改为 `db（默认）\| local` |
| `md-agent-bootstrap/src/main/resources/bootstrap.yml`（仅生产项目） | 两处注释原本写「application.yml 里的 `md.agent.nacos.*`（AgentSpec 来源）取这里的坐标」——已失效，改为说明这一节只服务「读配置中心」 |
| `md-agent-server/pom.xml` | 删 3 个依赖：`agentscope-extensions-nacos-prompt` / `-skill` / `-a2a`（含各自的 exclusion 块）。`nacos-client` 不再直接声明；后续 Nacos 工具改为直连 OpenAPI，服务端代码已无 `com.alibaba.nacos` 引用 |
| `md-agent-bootstrap/.../db/control-plane-schema.sql` | 注释 `NacosMcpClientFactory` → `DbMcpClientFactory` |
| `docs/agent-production-readiness.md` | 过期事实修正：B-1 的「`source=nacos`（默认）」、C-3 标题与现状里的 Nacos 措辞、相关代码列表里的 `NacosMcpClientFactory` |
| `docs/agent-definition-nacos-migration.md`、`docs/agent-mcp-nacos.md` | 移入 `docs/archive/nacos-ai-removal/`，并加「已废弃，不要照此实现」横幅（保留设计推演过程，不删历史） |

### 2.3 Nacos 工具改为直连 OpenAPI

`server/tool/nacos/` 下的 `AbstractNacosTool` / `GetSingleConfig` / `PublishConfig` 已不再 import
`com.alibaba.nacos.api.*`，改为直接调用 Nacos HTTP OpenAPI：
- 登录：`POST {serverAddr}/nacos/v1/auth/users/login` 换取 `accessToken`（进程内缓存，按 `tokenTtl` 过期）；
- 读取：`GET {serverAddr}/nacos/v1/cs/configs?dataId=&group=&tenant=`，404 视为配置不存在；
- 发布：`POST {serverAddr}/nacos/v1/cs/configs`（form-urlencoded，含 `dataId/group/content/type/tenant`）。

因此 `md-agent-server` 不再需要编译期 `nacos-client`。根 pom 仍保留 `spring-cloud-starter-alibaba-nacos-config`
用于生产配置中心，`nacos-client` 仅作为它的传递依赖存在，版本跟随 `spring-cloud-alibaba-dependencies`
BOM（当前 `2025.0.0.0` 管理 `3.0.3`）。

---

## 三、刻意保留项（连同理由）

| 保留 | 理由 |
| --- | --- |
| `bootstrap.yml` 的 `spring.config.import: optional:nacos:...`、`spring.cloud.nacos.config`、根 pom 的 `spring-cloud-starter-bootstrap` / `spring-cloud-starter-alibaba-nacos-config`（`nacos-client` 版本随其 BOM） | **配置中心**。生产 profile（`SPRING_PROFILES_ACTIVE=prod`）靠它从 Nacos 拉 `md-agent-platform.yaml`。这不是 AI 资源，删了生产直接起不来 |
| `server/tool/nacos/*`、`EnvironmentProperties.Nacos`、`EnvironmentRegistry.nacos()`、`environment.*.nacos` 凭据 | **被 agent 操作的业务系统**。`nacos_agent` 与 `workspace/single/skills/nacos_agent/{get,publish}-nacos-config` 在用 |
| `docs/agent-definitions.nacos.json` | `source=local` 的种子定义（内含业务 agent `nacos_agent`），是数据不是引用 |

---

## 四、验证

| 项 | 结果 |
| --- | --- |
| 编译（本地 + 生产，4 个模块） | BUILD SUCCESS |
| 单测（本地 + 生产） | 各 **388** 通过（`md-agent-server` 365 + `md-agent-bootstrap` 23），0 失败 0 错误 |
| 启动冒烟（本地 9090，真连库） | `[Agent] 加载完成（source=db），共 4 个: [workwx_notify_agent, workwx_contact_agent, workwx_doc_agent, workwx_agent]`；`Started MdAgentPlatformApplication` |
| 启动日志中的 nacos / AiService 字样 | 0 条；无 ERROR |
| 两个项目业务代码一致性 | `md-agent-server/src` 全量 MD5 一致（`application.yml` / `bootstrap.yml` 因本地与生产的部署形态不同，**有意保留差异**） |
| 源码编码 | UTF-8 无 BOM + LF（仅本地 `bootstrap.yml` 原本就是 CRLF，本次未触碰） |

---

## 五、需要知情 / 后续

1. **`GET /test` 端点已删**。它原本只是 `AiFactory` 的连通性样例，没有其它调用方。
2. **`source` 的语义现在是二元的**：`db`（默认）/ `local`。任何 `--agent-platform.agents.source=nacos`
   的启动参数、脚本、部署清单都应删掉（全仓已无此字符串）。
3. **`.claude/settings.local.json` 里还留着几条 nacos 的 mvn 白名单**（本机 CLI 工具配置，不是项目代码），按需自行清理。
4. **既有死代码未动**：例如 `ConfigurableAgentBuilder.emptyToNull(...)` 在本次改动之前就没有调用方，
   属历史遗留，与 Nacos 无关，未一并清理以保持改动面最小。
