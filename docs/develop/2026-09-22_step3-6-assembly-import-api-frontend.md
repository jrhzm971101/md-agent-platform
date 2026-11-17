# Step 3–6 落地记录：装配换源 DB / 导入器 / 控制面接口 / 前端治理页

> 对应设计文档 `2026-09-21_control-plane-metadata.md` 的 Step 3、4、5、6。
> 记录「做了什么、与设计哪里不同、验证到什么程度、还剩什么」。

## 0. 一句话结论

四类元数据（agent / prompt / skill / mcp）的**装配源已从 Nacos + 本地 md 换成 MySQL**，
控制面提供完整 CRUD + 发布 / 回滚 + 只读目录，前端 5 个治理页已重写为「卡片墙 + 编辑抽屉」。
后端 265 个测试、前端 68 个测试全绿。

两件事**代码完成但还没实跑**，需要你在真环境点一次：

1. 一次性导入器（Step 4）—— 从未执行过，库里 10 张表目前仍是空的；
2. 前端页面（Step 6）—— 未与后端做过联调，接口路径已用契约测试锁住但未跑通真数据。

---

## 1. Step 3：装配层换源（md-agent-server）

| 文件 | 新增/修改 | 作用 |
| --- | --- | --- |
| `NamedSkillRepository` | 新增 | **技能名白名单装饰器**。框架的 `DynamicSkillMiddleware` 会对传入的仓库调 `getAllSkills()` 拼 `<available_skills>`，直接把全库仓库给进去 = 每个 agent 都看得见全部技能。它只收窄 `getSkill/getAllSkills/getAllSkillNames` 三个读口；`close()` 有意留空（底层是全局单例，关掉会连累其它 agent） |
| `AgentDefinitionResolver` | 新增 | **6 表行束 → AgentDefinition 的唯一转换点**。`enabled=0` 返回 null（停用 = 不参与装配）；`prompt_key → md_prompt.body` 填 sysPrompt；`knowledges` 有意不映射（框架 `knowledge()` 已废弃） |
| `DbMcpClientFactory` | 新增 | `md_mcp_server` → MCP client。比 Nacos 版多了 `enabled` 开关与 **`invalidate(name)`**；stdio 一律拒绝 |
| `McpServerBinding` | 新增 | record(name, client, enableTools, disableTools)。**工具白/黑名单必须与连接成对传递** —— 名单语义已下沉到 MCP 级（设计 D10），拆开传迟早出现"连接对了、名单串到另一个 MCP 上" |
| `AgentDefinition` | 修改 | 补齐 typed 字段：压缩 12 项 / 记忆 9 项 / 驱逐 5 项 / 13 个 `disableXxx` 三态 / 子 agent 扩展列 |
| `ConfigurableAgentBuilder` | 修改 | 注入 `DbMcpClientFactory` 与官方 skill 仓库；按 `agent-platform.agents.source` 分派 DB / local / Nacos 三条源 |
| `AgentManager` | 修改 | `init()` 增加 `source=db` 分支；新增 `unregister` / `reload` / `reloadAll` |

**设计文档没写到、动手才发现的点**

1. **技能白名单是必须的**（`NamedSkillRepository`）。不装饰的话，框架会把「全库技能」列进每个
   agent 的可用技能提示里 —— 这是个静默的行为放大：功能"能用"，但模型看到的边界是错的。
2. **MCP 连接缓存必须可失效**。只改库不失效缓存，会出现"端点改了、发布也点了、还是连旧会话"。
   所以发布 MCP 时的顺序固定为：`invalidate(name)` → 重建引用它的 agent。
3. **三态开关"没配就不传"**。装配期对每个可空旋钮只在非 null 时调 setter。把 null 当成 false
   传下去，等于用平台的库把框架默认值钉死，且没有任何地方会提示这件事。

---

## 2. Step 4：一次性导入器（🗑 本节代码已于 2026-09-22 整体删除）

> 决定不再迁移历史元数据、改为在页面上手工建立：`ControlPlaneImporter` / `ImportReport` /
> `LegacyImportRunner` 与 `import-legacy-on-start` / `import-legacy-prefer-nacos-prompt` 两个开关已删除。
> 下面这段保留为**设计记录** —— 它记录的取舍（先对账后写入、零写入保证、幂等）对将来任何
> 「批量灌数据」的工具仍然适用。

| 文件 | 位置 | 作用 |
| --- | --- | --- |
| `ControlPlaneImporter` | md-agent-server | 两阶段：先读源 + 对账，**任一红项 → 零写入**；再按 prompt → mcp → skill → agent 顺序 upsert |
| `ImportReport` | md-agent-server | 三态结论（DONE / REJECTED / ABORTED）+ counts + checks + conflicts |
| `LegacyImportRunner` | md-agent-bootstrap | `@ConditionalOnProperty(agent-platform.import-legacy-on-start=true)` 的 ApplicationRunner，跑完自动 `reloadAll()` |

**几个刻意的设计**

- **先对账、后写入**，不做"边导边报错"。半截导入比不导更糟：库里会同时存在新旧两套名字，
  而装配只看库，没人能判断当前是什么状态。
- **技能从本地目录导入**（`<skills 目录>/*/SKILL.md`、`harness/<ws>/skills/*`），
  description 取 SKILL.md 的 frontmatter。
- **白名单归位冲突记 ERROR 让人裁决**（设计 D10）。多 agent 引用同一 MCP 但白名单不同时，
  静默取其一等于悄悄改掉某个 agent 的工具范围。
- `toBundle` 是**导入专用**的反向映射，刻意不做成公共组件：它的存在只为了让老数据能进来一次。

**实测数据（源侧）**：14 个 agent md、32 个技能目录（实际被引用 30 个，孤儿 1 个
`workwx-registry`）、Nacos 侧 15 AgentSpec / 15 PromptSpec / 36 Skill / 4 MCP。

---

## 3. Step 5：控制面接口（全部在 md-agent-bootstrap）

| 文件 | 作用 |
| --- | --- |
| `controller/controlplane/ResourceController` | `/api/resources`：prompt / skill / mcp 三类同构 CRUD + publish / rollback / versions + 只读目录 |
| `controller/controlplane/ControlPlanePublishService` | 发布 = 落快照 + 热装配；**回滚不热装配** |
| `controller/controlplane/ControlPlaneValidator` | §6.4 七条规则，收集式一次报全 |
| `controller/controlplane/MiddlewareCatalog` | 扫 `MiddlewareBase` bean 成目录 |
| `controller/controlplane/ControlPlaneExceptionHandler` | 校验失败 → 400 + `{ error, violations[] }` |
| `controller/agent/AgentController` | 改写：列表 / 详情 / 版本 / CRUD / publish / rollback / reload / 工具目录 |

**接口层的三个约定**

1. **删除被引用 → 409 + `referencingAgents`**。400 是"你提交的内容不合法"，409 是"内容合法但
   当前状态不允许"。带引用清单回去，页面才能列出"先去解绑谁"。
2. **路径是权威**。`PUT /api/resources/prompts/{key}` 会覆盖 body 里的 key，防止"改个名字绕过
   主键"变成隐式另存为。agent 同理，并且会把从属行的 `agent_name` 统一改写。
3. **`X-Operator` 缺省 `console`**。这次不做登录，但不留空：审计列一旦允许为空，
   后面再想补"谁改的"就补不回来了。

**列表行改了一次（AgentRow）**：原本回的是四个引用**计数**，改成回引用**名清单**
（`promptKey` / `tools` / `middleware` / `skillNames` / `mcpNames` / `subAgentNames`）。
理由：资源页要回答"这个 prompt 被哪些 agent 引用"，而那是反查；列表本来就是全量行束读，
ref 已经在手里，把名字一起回出去不增加任何数据库代价，计数交给前端 `names.length`。

---

## 4. Step 6：前端 5 个治理页（agent-contro-plane）

### 4.1 新增

| 文件 | 作用 |
| --- | --- |
| `src/api/controlplane/types.ts` | DB 领域模型，字段与后端 JSON 逐字对应 |
| `src/api/controlplane/index.ts` | 真实接口层（**不做 mock 分叉**）+ `referencingAgentsOf` / `violationsOf` |
| `src/api/controlplane/contract.test.ts` | 契约测试：15 个用例锁住 method + path + 载荷字段 |
| `src/components/ui/TriStateSelect.vue` | 三态开关（含"未设置"档） |
| `src/components/ui/JsonField.vue` | JSON 列编辑器（解析失败不写坏父级草稿） |
| `src/components/ui/KeyValueEditor.vue` + `src/lib/kvRows.ts` | headers / query-params 的行编辑与转换 |
| `src/components/controlplane/VersionsPanel.vue` | 版本历史 + 回滚入口（四类资源共用） |
| `src/features/controlplane/feedback.ts` | 失败反馈：400 逐条列问题、409 列引用方、其它一句话 |

### 4.2 重写

`PromptsPage` / `SkillsPage` / `McpPage` / `AgentsPage` / `AgentDetailPage` / `MiddlewarePage`
→ 卡片墙 + 编辑抽屉（agent 详情是分域表单 + 7 个页签）。

### 4.3 删掉

`src/api/{prompts,skills,mcp,agents,middleware}.ts` —— 旧的 mock 包装层，已被控制面模块取代，
零引用后删除。

### 4.4 前端上的三个关键取舍

1. **不保留 mock 分叉**。DB 已是唯一真相，页面读到的就是库里的东西。保留 mock 只有一种结果：
   页面看着挺好，但与库毫无关系。
2. **三态必须渲染成三选**。用复选框渲染会把 null 一保存写成 false，那一刻框架默认值就永久
   失效了。`TriStateSelect` 内部用字符串哨兵过 el-option（`null` 会被组件当成"未选中"）。
3. **"被谁引用"在前端本地算**。agent 列表已带回每个 agent 的引用名清单，一次 O(agents) 生成
   反向表，避免按卡片逐条回查后端（14 个 agent × N 个资源）。

---

## 5. 验证结果

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量测试 | `mvn -o test` | **265 通过**（server 247 + bootstrap 18），BUILD SUCCESS |
| 后端编译 | `mvn -o -pl md-agent-bootstrap -am test-compile` | 通过 |
| 前端类型 | `npx vue-tsc --noEmit` | 92 个文件，0 错误 |
| 前端构建 | `npx vite build` | 通过 |
| 前端测试 | `npx vitest run` | **68 通过**（53 原有 + 15 新增契约测试） |

**没验证到的（需要你在真环境点一遍）**

1. 导入器从未执行 —— 库表目前是空的，所以导入的对账表、白名单归位冲突、
   `preferNacosPrompt` 两条分支都还是"纸面正确"。
2. 前端页面未联调 —— 路径与载荷由契约测试锁住，但真实响应的形状没跑过。
   建议第一次联调按设计文档 §9 的顺序走：建 prompt → 建 agent 引用它 → 发布 → 改 prompt → 发布 → 回滚。
3. 企微 / 飞书 / 页面对话 / 链路观测四块回归需要运行中的服务，我这边没启服务。

---

## 6. 下一步

### 6.1 Step 7 的拆分（重要）

原 Step 7 = "清理 Nacos 运行时依赖 + 回归"。按你在计划评审时的决定
（"暂时先保留，等其他落地完了之后再清理，避免服务无法启动"），**本次不做清理**，
只做回归。清理时机建议：导入实跑通过 + 页面联调通过 + 连续观察一段时间无回退需求之后。

清理时要小心的两件事：

- `nacos-config` 不能一并删：`bootstrap.yml` 的配置中心还在用它拉配置；
- 可删的是 `agentscope-extensions-nacos-skill` / `-nacos-prompt` / `-mcp-nacos` /
  `nacos-client`（先确认没有任何代码路径在 `source=nacos` 下还会被调用）。

### 6.2 建议的下一步顺序

1. ~~实跑导入~~ **（2026-09-22 作废：导入器已删除）** 改为在控制面页面手工建立 agent / prompt /
   skill / mcp；建完记得 `POST /api/agents/reload-all`（或重启）让运行时注册表生效。
2. **前端联调**：按上面 §5 的顺序走一遍全流程，重点看 409 引用保护与 400 校验提示。
3. **补两处已知缺口**（设计文档 §10）：
   - skill 列表的**漂移校验**（官方内容表 name 集合 vs `md_skill_meta`）—— 后端已有
     `listSkillContentNames()`，但还没有接口暴露给页面；
   - run 上记 `agent_version` / `prompt_version`，才能真正复现"当时拿哪版定义跑的"。