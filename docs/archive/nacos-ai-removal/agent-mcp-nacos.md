> ⛔ **已废弃（2026-09-29）**
> 本文描述的「agent 元数据由 Nacos 承载」方案已整体删除：Agent / Prompt / Skill / MCP 全部改由
> 控制面 MySQL 表承载（`md_agent` / `md_prompt` / `md_skill` / `md_mcp_server`）。
> 相关代码（`AiService` / `NacosAgentSpecRepository` / `AgentSpecConverter` / `NacosSkillRepository` /
> `NacosMcpClientFactory` / `NacosAgentProperties`）与 3 个 `agentscope-extensions-nacos-*` 依赖均已移除。
> 保留本文仅为留档这段设计推演过程，**不要照此实现**。
> 变更说明：`docs/develop/2026-09-29_nacos-ai-removal.md`。

# Agent 挂载 Nacos MCP 说明

> 位置: `md-agent-server/src/main/java/com/md/agentplatform/server/agent/`
> 相关类: `AgentDefinition#mcpNames` / `#enableTools` / `#disableTools`、`NacosMcpClientFactory`、
> `ConfigurableAgentBuilder#buildToolkit` / `#registerMcpClients`
> 配置: `agent-platform.nacos.*`（`NacosAgentProperties`，即 AgentSpec / skill / prompt 用的同一套连接）
> 对照实现: skills 走 `NacosSkillRepository`，MCP 走 `NacosMcpClientFactory`，两者都是「agent 里只写名字，内容从 Nacos 拉」

Agent 的工具来源有两类：本地 Spring 工具 bean（`tools`）和远端 MCP（`mcp-names`）。
MCP 的端点、传输协议、鉴权头全部在 Nacos 侧维护，agent 定义里只声明**要哪些 MCP**，
装配期由平台解析并注册进 `Toolkit`，改 MCP 不用动 agent 定义，改 agent 引用不用动 MCP 注册。
只想挂某个 MCP 的**部分工具**时，用 `enable-tools` / `disable-tools`（见第七节）。

## 一、配置方式

`AgentDefinition` 新增 typed 字段 `mcpNames`，与 `skillNames` 同级、同源。三种定义源都自动识别，
无需改 loader / converter：

| 定义源 | 写法 |
| --- | --- |
| Nacos AgentSpec `content`（JSON，kebab-key） | `"mcp-names": ["workwx-doc-mcp", "workwx-message-mcp"]` |
| 本地 MD frontmatter（kebab-key） | `mcp-names: [workwx-doc-mcp, workwx-message-mcp]` |
| REST body（camel-case） | `{"mcpNames": ["workwx-doc-mcp"]}` |

示例（Nacos AgentSpec content）：

```json
{
  "name": "git_agent",
  "type": "REACT",
  "tools": ["gitlabProjectEvents", "workWxSendMsgTool"],
  "mcp-names": ["workwx-doc-mcp"],
  "skill-names": ["gitlab-project-events"],
  "max-iters": 50
}
```

## 二、解析链路

```
AgentManager.create(def)
  └─ ConfigurableAgentBuilder.buildReact/buildHarness
       └─ buildToolkit(def)
            ├─ tools        → applicationContext.getBean(name) → toolkit.registerTool
            └─ mcp-names    → NacosMcpClientFactory.resolveAll
                                ├─ AiService.getMcpServer(name)       # 按 namespace 隔离
                                ├─ backendEndpoints[0]（空则 frontendEndpoints[0]）
                                ├─ McpClientBuilder.streamableHttpTransport(url)
                                │                 .headers(端点鉴权头).timeout(120s).buildSync()
                                ├─ client.initialize()                # 握手，只缓存连上的
                                └─ toolkit.registerMcpClient(client).block()
```

MCP 注册进 Toolkit 后，框架把 `listTools()` 返回的每个 MCP tool 包成 `McpTool` 注册，
工具名、参数 schema 直接沿用 MCP 服务端声明，无需平台侧再写 `@Tool`。

## 三、URL 与传输协议规则

- URL = `protocol://address[:port]path`，`port` 只有非协议默认值（http 80 / https 443）时才拼接。
  已带 scheme 的 `address` 原样透传。
- 传输方式按 protocol 判定：含 `sse` 走 SSE（`sseTransport`），其余（`mcp-streamable` / `http` / `https`）走 streamable-http。
- 类型为 stdio 的 MCP（本地进程）不支持，直接判失败——平台只接远端 MCP。
- 端点上配了 headers 时（Nacos MCP 注册页维护），全部作为请求头带上，可用于 apikey 类的 MCP 鉴权。

## 四、失败与生命周期

- **单个 MCP 失败只告警跳过**：名字写错、Nacos 里不存在、端点缺失、握手失败都不会中断 agent 装配，
  更不会拖垮平台启动；失败项以 `[MCP] xxx 挂载失败，已跳过` 记 ERROR 日志，按日志逐项修即可。
- **同名 MCP 跨 agent 复用**：`NacosMcpClientFactory` 以 mcpName 为键缓存 client（一个 client 即一次 MCP 会话），
  多个 agent 引用同一个 MCP 不重复建链。
- **关闭时机**：client 随容器销毁在 `@PreDestroy` 里统一关闭，不随单个 agent 重建回收
  （agent 重建（PUT /api/agents）不会断掉其它 agent 正在用的 MCP 会话）。

## 五、运维步骤

1. 在 Nacos 控制台「MCP 管理」注册 MCP server（协议选 `mcp-streamable`，填服务地址与导出路径），发布上线。
2. 在目标 agent 的 AgentSpec `content` 里加 `"mcp-names": ["<上一步的 mcp 名>"]`，发布。
3. 重启平台（或经 `PUT /api/agents/{name}` 触发该 agent 重建）即可生效。
4. 验证：启动日志应出现 `[MCP] <名> 已建链: <url>` 与 `[Agent] <agent> 挂载 Nacos MCP: [...] (成功 N 个)`；
   若数量少于声明数，按 ERROR 日志逐条排查。

## 六、当前已注册的 MCP（namespace `md-agent-platform`）

| MCP 名 | 说明 | 工具数 |
| --- | --- | --- |
| `workwx-doc-mcp` | 企业微信文档 | 62 |
| `workwx-address-mcp` | 企业微信通讯录 | 1 |
| `workwx-message-mcp` | 企业微信消息发送 | 6 |

> 工具数会随 MCP 服务端版本变化，仅供参考，以运行时日志为准。

## 七、只挂部分工具（enable-tools / disable-tools）

MCP 声明的工具往往远多于单个 agent 实际要用的（如 `workwx-doc-mcp` 有 62 个）。
全量注册的代价不只是「模型看到的选择变多」，还有**每次请求都带一份工具 schema**——
72 个 MCP 工具实测约 9 万 prompt token。只挂需要的工具用这两个字段：

| 定义源 | 写法 |
| --- | --- |
| Nacos AgentSpec `content`（JSON，kebab-key） | `"enable-tools": ["smartsheet_records_list", "smartsheet_fields_list"]` |
| 本地 MD frontmatter（kebab-key） | `enable-tools: [smartsheet_records_list, smartsheet_fields_list]` |
| REST body（camel-case） | `{"enableTools": ["smartsheet_records_list"]}` |

- `enable-tools`：**白名单**。非空时该 agent 的 MCP 客户端只注册名单内的工具。
- `disable-tools`：**黑名单**。`enable-tools` 非空时以它为准（框架侧 enable 覆盖 disable）。
- 两个都留空 = 不过滤，注册该 MCP 声明的全部工具（保持原行为）。
- 工具名必须与 MCP 服务端声明的**原始工具名**一致，即启动日志里
  `Registered tool '<name>' in group 'ungrouped'` 的那个名字。白名单里写了不存在的名字不会报错，
  只是不生效，可对着日志核对。

装配期是**按 MCP 客户端分别过滤**的：名单是同一个，但每个 MCP 只注册自己声明过且在名单内的工具，
不会把别的 MCP 的工具带出来。

### 作用范围与限制

- **只作用于 MCP 工具**。本地 Spring 工具 bean（`tools` 字段）本来就要逐个显式声明，已是白名单语义，
  不受这两个字段影响；框架侧 `Toolkit.registration()` 的 `tool(...)` 分支也不接收这两个参数。
- 过滤发生在**注册期**（`Toolkit.registration().mcpClient(c).enableTools(...).disableTools(...).apply()`），
  未入选的工具不会进 `Toolkit`，因此不进模型 schema —— 省的是 token，不只是可见性。
- 与 `enable-meta-tool`（工具组切换的元工具）无关，不要混着理解：
  工具组是「注册了但暂时隐藏」，enable-tools 是「根本不注册」。
- HARNESS agent 另有一条更粗的通道：workspace 下的 `tools.json`（`{allow, deny}`），
  它作用在**最终工具集**上，连本地工具一起过滤，两者可叠加使用。

### 建议做法

1. 先按启动日志把该 MCP 的工具名抄全（`Registered tool '<name>' ...`）。
2. 只保留该 agent 职责内会用到的，写进 `enable-tools`。
3. 重启后核对日志 `[Agent] <name> MCP 工具过滤: enable=[...] disable=...`
   与 `Registered tool '<name>'` 的实际条数是否与预期一致。
