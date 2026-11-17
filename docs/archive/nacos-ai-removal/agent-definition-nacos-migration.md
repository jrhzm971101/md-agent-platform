> ⛔ **已废弃（2026-09-29）**
> 本文描述的「agent 元数据由 Nacos 承载」方案已整体删除：Agent / Prompt / Skill / MCP 全部改由
> 控制面 MySQL 表承载（`md_agent` / `md_prompt` / `md_skill` / `md_mcp_server`）。
> 相关代码（`AiService` / `NacosAgentSpecRepository` / `AgentSpecConverter` / `NacosSkillRepository` /
> `NacosMcpClientFactory` / `NacosAgentProperties`）与 3 个 `agentscope-extensions-nacos-*` 依赖均已移除。
> 保留本文仅为留档这段设计推演过程，**不要照此实现**。
> 变更说明：`docs/develop/2026-09-29_nacos-ai-removal.md`。

# Agent 定义迁移到 Nacos 设计方案

> 日期：2026-08-31
> 状态：方案讨论阶段（仅讨论，未编码）
> 目标：实现"通过 Nacos 控制台快速编排 agent，无需修改服务器文件"

## 1. 背景与目标

当前 agent 定义写在 `workspace/agents/*.md`（YAML frontmatter + 正文即 sysPrompt），修改 agent 需要上服务器改文件后重启。目标是把定义迁移到 Nacos，让编排动作（新建/改参/换 prompt）全部在 Nacos 控制台完成，平台侧自动加载并热生效。

## 2. 已确认决策

| 决策点 | 结论 |
|---|---|
| 数据承载 | Nacos AI 原生：AgentCard + AgentSpec + Prompt |
| agent 清单 | 自动发现（读 AgentSpec 索引 dataId） |
| md 兼容 | 配置开关 `agent.definition.source = md|nacos`，默认 nacos，可回退 |
| 热更新 | 本期做（subscribeAgentSpec / subscribePrompt 订阅监听实时重建） |

## 3. 数据模型映射

利用 Nacos 3.2.0 AI 原生能力（`com.alibaba.nacos.api.ai.*`，随 nacos-client 3.2.0 提供，**无需额外引入 a2a-java-sdk**）：

| 数据 | Nacos 承载 | 访问 API | 说明 |
|---|---|---|---|
| AgentCard 基本信息 | `AgentCard`（A2A 格式，nacos-api 自带） | `A2aService.getAgentCard(name)` | 只填 name / description |
| sysPrompt | `Prompt`（带版本/label/订阅） | `AiService.getPrompt(name)` / `subscribePrompt` | 独立热维护 |
| 其余元数据 | `AgentSpec`（name 即 key） | `AiService.loadAgentSpec(name)` / `subscribeAgentSpec` | `content` 放 YAML 定义，`resource` 放命名资源 |

要点：
- **AgentSpec.content**：建议把现有 md 的 frontmatter（去掉 name/description/sysPrompt 三项）原样作为 YAML，复用 `AgentDefinition` 的 Jackson `KEBAB_CASE` 反序列化。
- **Prompt 与 Agent 分离**：一个 agent 的完整定义管理两处（AgentSpec.content + Prompt），待定是否在 content 里加 `promptRef: <prompt名>` 显式引用。
- **AgentCard 定位**：内部加载时 name 实际取自 AgentSpec，AgentCard 承担"门面/简介"，价值在将来对外开放 A2A 发现；因零成本（nacos-api 自带）而保留。
- **依赖现状**：`agentscope-extensions-nacos-prompt` / `-nacos-skill` / `-nacos-a2a` 已在 `md-agent-server/pom.xml` 声明，尚未接线；`agentscope-extensions-nacos-a2a` 于 2026-08-31 重新启用（用于 A2A AgentCard 注册）。

## 4. 加载链路（改造后）

```
启动 → AgentManager.init()
  ├─ 开关 agent.definition.source = nacos|md
  ├─ 自动发现：读 AgentSpec 索引 dataId → agent name 列表
  ├─ 逐个：loadAgentSpec(name) → content 解析为 AgentDefinition
  │         getPrompt(name) → sysPrompt
  │         getAgentCard(name) → name/description 校验
  └─ AgentManager.create(def) → buildReact/buildHarness → agents[name]
```

复用现有装配：`AgentManager.create()` / `buildReactAgent()` / `buildHarness()` 不动；`AgentDefinitionMdLoader.parse()` 的 YAML 解析逻辑换数据源复用。

## 5. 热更新链路

`subscribeAgentSpec(name, listener)` + `subscribePrompt(name, listener)` → 变更回调 → 重建对应 Agent 实例 → `agents.replace(name, newAgent)`。

会话不丢失：会话状态在外置 `stateStore`（json/文件），agent 实例本身是无状态壳。

## 6. 必须先验证的风险点

1. **Nacos server AI 能力是否全开**：`getPrompt` 已验证可用（TestController），`getAgentCard` / `loadAgentSpec` 同属 AI 模块，大概率可用，落地前需探针验证。
2. **自动发现索引格式**：`AgentSpecUtils.AGENTSPEC_INDEX_DATA_ID` 的内容结构未知，需实测"读索引"还是"扫配置列表"拿到 name 清单。这是方案里唯一有不确定性的环节。
3. **namespace 归属**：现有约定 namespace 是游离参数（EnvironmentRegistry 里 nacos 只有 serverAddr/username/password）。AgentCard/AgentSpec 存于某 namespace 下，平台侧须确定统一使用的 namespace。

## 7. 待商榷的连带问题

- **REST 写路径**：`AgentController /api/agents` 现写 `workspace/agent-definitions/*.json`。建议本期一并切到 Nacos（releaseAgentCard + 发布 AgentSpec），否则两套数据源打架。
- **AgentSpec.content 是否含 `promptRef`** 显式引用。
- 是否先写只读探针验证索引格式。

## 8. 关键文件索引

| 文件 | 职责 |
|---|---|
| `workspace/agents/*.md` | 当前 agent 定义源（将被 Nacos 取代，配置开关回退用） |
| `md-agent-server/.../agent/AgentDefinition.java` | Agent 配置 POJO（REACT/HARNESS） |
| `md-agent-server/.../agent/AgentDefinitionMdLoader.java` | md 解析（换数据源复用其 parse） |
| `md-agent-server/.../agent/AgentManager.java` | 装配/注册表（agents[name] map，create/stream） |
| `md-agent-bootstrap/.../controller/agent/AgentController.java` | REST 治理（写路径待切换） |
| `md-agent-bootstrap/.../TestController.java` | AiService 连接样例（SERVER_ADDR/NAMESPACE/USERNAME/PASSWORD + nacosAiTransportMode=http） |
| `md-agent-server/pom.xml` | nacos-prompt/skill/a2a 扩展声明 |
| nacos-api（nacos-client 3.2.0 内含） | `AiService`/`A2aService`、`AgentCard`、`AgentSpec`、`AgentSpecUtils` |
