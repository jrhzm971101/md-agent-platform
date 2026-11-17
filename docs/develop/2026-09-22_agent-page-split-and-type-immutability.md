# Agent 页面拆分 与 agentType 不可变 —— 改造清单（评审稿）

> 状态：**已落地**（2026-09-22，五步全部完成，见文末 §十）。本文保留评审时的推理过程，
> 若要直接看改了什么、验证结果如何，跳到 §十。
> 起因：「Agent 页该不该创建 harness agent」这一问。
> 结论：页面入口不是主要矛盾 —— **表的归属和数据完整性才是**。同一次排查还翻出两个现存缺陷。

---

## 一、四条待确认的决策

| # | 决策 | 一句话理由 |
|---|---|---|
| D1 | Agent 页的「新建」不再让用户选类型，固定提交 `REACT` | 一个表单扛两种结构，等于让人先选结构再填内容 |
| D2 | `agentType` 创建后不可变，由后端强制 | 现在可自由切换、有损、且绕过前端就能发生 |
| D3 | REACT 专有的 3 列从 `md_agent_workspace` 迁到 `md_agent_runtime` | 迁完这张表才是纯 HARNESS，才能整表归编排页 |
| D4 | 「Harness 编排」页的「新建」复用 `POST /api/agents`，不自己拼行束 | HARNESS 也要写 `md_agent/_ref/_runtime`，不能只写编排域 |

---

## 二、事实认定（先把证据钉住，执行时不用重查）

### 2.1 那 3 个字段是 REACT 专属

三条独立证据：

**框架层** —— 三个方法只定义在 `ReActAgent.Builder` 上，`HarnessAgent.Builder` 根本没有：

- `dynamicSkillsEnabled(boolean)` — `agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java:5342`
- `skillCodeExecutionEnabled(boolean)` — 同文件 `:5361`
- `skillWorkDir(Path)` — 同文件 `:5371`

**框架自己就主动抑制** —— `agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java:2938` 与 `:2948` 里写的是 `inner.dynamicSkillsEnabled(false)`。
harness 在构造内部那个 ReActAgent 时**强制关掉动态技能**，改用自己那套 `SkillManageConfig` / `enableSkillManageTool`。这是「这 3 项是 REACT 的」最硬的证据。

**本项目已认定**：

- `ControlPlaneValidator.java:44` → `REACT_ONLY = List.of("conflictPolicy", "dynamicSkillsEnabled", "skillCodeExecutionEnabled", "skillWorkDir")`
- 同文件 `:185-193`，HARNESS 分支逐项报错「（REACT 专有）」
- 装配层 `ConfigurableAgentBuilder.java:240-248` 在 `applyTuning(ReActAgent.Builder, ...)` 里调用它们；`applyTuning(HarnessAgent.Builder, ...)` 里没有

### 2.2 `md_agent_workspace` 的列是按链劈成两半的

全表 34 列，剔除主键 `agent_name` 后 33 列：

| 归属 | 列数 | 列 |
|---|---|---|
| **REACT** | 3 | `dynamic_skills_enabled` / `skill_code_execution_enabled` / `skill_work_dir` |
| **HARNESS** | 30 | `harness_agent_id`、`workspace_path`、`environment`、`environment_memory`、`project_global_skills_dir`、`plan_file_directory`、`additional_context_files`、`filesystem`、`use_legacy_xml_workspace_context`、`agent_tracing_log_enabled`、`enable_plan_mode`、`plan_mode_allow_shell`、`skills_enabled`、`enable_skills`、`disable_skills`、`skill_manage_tool_enabled`、`skill_manage_config`，加 13 个 `disable_*` |

而 `AgentDefinitionResolver.applyWorkspace()`（`AgentDefinitionResolver.java:227-265`）是**类型无关**的：两条链读同一张表的同一行，REACT 只取那 3 列。

⇒ 所以「`md_agent_workspace` 整表归 Harness 编排页」**不成立** —— REACT agent 也有这张表的行。这正是 D3 的存在理由。

### 2.3 现存缺陷一：这 3 项在页面上根本够不着

`AgentDetailPage.vue:943` 的「工作区」tab 是 `v-if="isHarness"`，而承载这 3 项的「技能」PaneCard（`AgentDetailPage.vue:995`）正落在这个 tab 里。

⇒ REACT agent 打开详情页**没有「工作区」tab**，这 3 个字段无法编辑。当前只有两条路能写进去：`ControlPlaneImporter` 从 md 导入，或直接改库。

后端认、装配读、页面够不着 —— 这是 D3 最直接的动机（不只是"位置不好看"）。

### 2.4 现存缺陷二：`agentType` 可变，而且有损

- `GovernedRow.advanceDraft()`（`GovernedRow.java:98-107`）只继承 `created_at` / `created_by` / `published_*`，**不继承 `agentType`** ⇒ 请求体里的类型会直接覆盖旧值
- `MyBatisControlPlaneStore.replaceAgentRows()`（`:337`）是「先删后插」；`replaceRow()`（`:357`）在 row 为 `null` 时**删掉整行**
- 快照只在 `publish()` 时写（`md_resource_version`）⇒ **切换前没发布过就无从回滚**
- 现在靠前端 `AgentDetailPage.onTypeChange()`（`:214-236`）在切换瞬间清空 memory / workspace / subAgents

三个后果：

1. 绕过前端（API 直调、脚本、并发编辑）会留下「type=REACT 却带 workspace 行」或反向的脏数据
2. HARNESS→REACT 会**静默丢弃** `md_agent_sub_agent` 全部行，而别的 agent 可能正引它当子 agent
3. 前端那 30 行清场逻辑本来应该是后端的职责

⇒ D2 要解决的其实是这个，比页面入口紧急。

---

## 三、表结构改动（D3）

### 3.1 迁去哪

**推荐：迁进 `md_agent_runtime`。**

- 该表已经是「两链共有调优 + REACT 专有项」的家 —— `conflict_policy` 就在那儿，且同属 `REACT_ONLY` 名单，3 项放进去语义同族
- 不新增表（不过度设计）
- 代价：`md_agent_runtime` 的定位会从「运行调优」松成「装配期不属于别处的字段」。可接受，但要在这张表的注释里写清楚

**备选：新建 `md_agent_skill_policy`。** 语义最干净，但为 3 列单开一张表 + entity + mapper + 页面面板，成本不划算；只有确认「REACT 技能策略以后会显著膨胀」时才选它。

### 3.2 需要同步改的落点（行号已核）

**后端**

| 文件 | 行 | 改什么 |
|---|---|---|
| `md-agent-bootstrap/src/main/resources/db/control-plane-schema.sql` | `:196-198` | 3 列定义移位（加进 `md_agent_runtime`） |
| `.../controlplane/store/entity/AgentWorkspaceEntity.java` | `:89, :92, :95`、类注释 `:29` | 字段移除 |
| `.../controlplane/store/entity/AgentRuntimeEntity.java` | — | 字段新增 |
| `.../agent/AgentDefinitionResolver.java` | `:246-248` | 改读 runtime 行 |
| `.../agent/ControlPlaneImporter.java` | `:626-628`（写入）、`:687`（`hasWorkspaceConfig` 判断） | 见下方风险说明 |
| `.../controlplane/ControlPlaneValidator.java` | `:44` 与 `:185-193` | 规则不变，取值域从 workspace 换成 runtime |
| `.../agent/AgentDefinition.java` | `:119, :122, :125` | 字段语义注释 |

**前端**

| 文件 | 行 |
|---|---|
| `src/api/controlplane/types.ts` | `:279, :281, :283`（从 workspace 类型移到 runtime 类型） |
| `src/pages/AgentDetailPage.vue` | `:167-168`（REACT 专有三态开关清单）、`:291-293`（`blankWorkspace` 里的默认值） |
| `src/pages/AgentsPage.vue` | `:291-293`（创建表单给它预填的位置） |

**文档（容易被漏）**

- `docs/develop/2026-09-21_control-plane-metadata.md:295-297`（表定义）与 `:519-521`（字段映射表）
- `docs/agent-production-readiness.md:520` 那一行的覆盖度结论

不更新的话，下次照文档改会踩空。

### 3.3 迁移成本：接近零

已核库：`md_agent` = 0 行、`md_agent_workspace` = 0 行。改 `control-plane-schema.sql` 重跑建表即可，**没有数据搬迁**。

⇒ 建议**在跑导入器之前**先改完。等导进 14 个 agent 再动就得写迁移脚本了。

---

## 四、后端校验改动（D2）

### 4.1 类型不可变落在哪

落点：`MyBatisControlPlaneStore.saveAgent()`（`:282`），在 `validateAgentContent()` **之前**加一条：

- `existing == null` → 保持现状（返回 `false`，语义是"该 agent 不存在"）
- `existing.getAgentType() != incoming.getAgentType()` → 抛 `ControlPlaneValidationException`

抛异常而不是返回 `false`，因为 `false` 已经被「该 agent 不存在」占用，复用会让调用方分不清两种失败。

异常 → HTTP 映射：现有 `ControlPlaneExceptionHandler` 已经处理 `ControlPlaneValidationException` → **400 + `{error, violations[]}`**，大概率不用新增映射，执行时确认一下。

文案要给得出出路，例如：

> agent 类型不可变更（REACT → HARNESS）。需要 Harness 请新建一个，再把 `<name>` 挂成它的子 agent。

另外：除「新建」外，后端应**忽略请求体里的 `agentType`**，否则会出现「校验拦住了、字段还是被写」的裂缝。

### 4.2 类型规则怎么跟着改

`ControlPlaneValidator` 第 7 条（类型与字段不匹配）逻辑不变，只是 3 项的取值域从 `bundle.workspace` 换成 `bundle.runtime`。改完还可以顺势收紧：

- REACT → **不应存在 workspace 行**（迁走 3 列后，这张表对 REACT 已无意义）
- HARNESS → 不应配置 `conflictPolicy`（仍在 runtime 里）与那 3 项

### 4.3 子 agent 只能选 REACT（已拍板）

现状**不限制类型**：

- 后端 `validateAgentContent` 只要求 `subName` 存在，不校验类型
- 前端 `AgentDetailPage.vue:110-116` 的 `subAgentOptions` 是全量 agent，只过滤了自引用

两个选择：

- **限制成 REACT**：语义是「子 agent = 被派发的执行体，只有能力单元能当」。要同时收紧后端校验和前端下拉。
- **不限制**：允许 harness 嵌套 harness。但那要考虑路由深度与环 —— 现有校验只挡自引用（`subName.equals(agentName)`），**挡不住 A→B→A**，要新加环检测。

我倾向**限制成 REACT**：harness 是编排体、子 agent 是被派发的执行体，角色本就不重叠。但这会挡掉将来「父子 harness」的用法，需要你定。

---

## 五、前端改动

### 5.1 `AgentsPage.vue`（列表 + 新建）

- 去掉「类型」下拉（`:469-472`），新建时固定 `agentType: 'REACT'`
- **保留**类型筛选 chip（`:70`）和列表里的类型徽标（`:437-438`）—— 列表要能一眼看出谁是 harness
- HARNESS 行的操作从「编辑」改成「去 Harness 编排页」（跳转）

### 5.2 `AgentDetailPage.vue`（详情）

- 类型改成只读展示（去掉 `:562` 那个 select，保留 `:519` 的徽标）
- **整块删除 `onTypeChange()`**（`:214-236`）及为它服务的 `blankMemory()` / `blankWorkspace()` 惰性构造
- 「工作区」tab 的 `v-if="isHarness"` 保留 —— 迁走 3 列后它才真正是纯 HARNESS，`:1033` 那条「REACT 专有项不显示」的说明可以删
- **新增**：REACT agent 详情页要有一处能编辑这 3 项（放到「行为开关」PaneCard 旁，或 runtime 面板里）。否则迁完之后比现在更难编辑

### 5.3 Harness 编排页（新）

- 列表：只列 `agentType = HARNESS` 的 agent
- 新建：调 `POST /api/agents`，`agentType` 固定 `HARNESS` + 能力域给最小默认值（promptKey 空、tools 空、runtime 用默认），成功后才进编排编辑面
- 编辑面：`md_agent_workspace` + `md_agent_memory` + `md_agent_sub_agent` 三域，组件从现有 `AgentDetailPage` 里搬

---

## 六、页面归属（改完后的 1:1 对应）

| 页面 | 拥有的表 | 内容 |
|---|---|---|
| Agent | `md_agent` / `md_agent_ref` / `md_agent_runtime` | 身份 + 能力域 + 运行调优（含 REACT 技能策略 3 项、`conflict_policy`） |
| Harness 编排 | `md_agent_workspace` / `md_agent_memory` / `md_agent_sub_agent` | 编排域：工作区、上下文压缩、子 agent 路由 |
| （将来）Channel | channel 自己的表 | 建议独立成页；编排页只放只读列表 + 跳转 |

注意：**HARNESS 的「新建」依然要写 `md_agent` + `_ref` + `_runtime`**（model / max-iters / prompt 不分类型都要有），所以创建必须是**一条原子接口**，不能由编排页自己直插 `md_agent_workspace` 拼半份行束。

---

## 七、建议执行顺序

1. **表结构迁移**（3 列移位）+ 后端 6 个文件、前端 3 个文件跟着改 —— 现在 0 行，最便宜
2. **后端类型不可变** + 第 7 条规则改读取值域
3. **前端**：`AgentsPage` 固定 REACT、`AgentDetailPage` 类型只读（删 `onTypeChange`）
4. **Harness 编排页**：列表 + 新建 + 三域编辑
5. **测试**：后端 `mvn -o test`（基线 265 通过）、前端 `vue-tsc` + `vitest`（基线 68 通过）

---

## 八、风险与影响面

- **导入器语义会变**：`ControlPlaneImporter.hasWorkspaceConfig()`（`:687`）现在把这 3 项算作「有 workspace 配置」的理由。迁走后 REACT agent 不再因此拥有 workspace 行 ⇒ 导入出来的 REACT agent 可能**没有 workspace 行**。这是期望结果，但要确认 `AgentDefinitionResolver.applyWorkspace()` 在行缺失时能正常装配（`:227` 起有 `w == null` 早退，看起来没问题）
- **第二个错位别留下来**：`md_agent_workspace.harness_agent_id` 目前被 REACT 也读（`AgentDefinitionResolver.java:231` 无条件 `setAgentId`）。迁移时要一并确认它到底属哪条链
- **文档过期**：`docs/develop/2026-09-21_control-plane-metadata.md:519-521` 的字段映射表必须同步改，否则后面照它改会踩空
- **前端契约测试**：`src/api/controlplane/contract.test.ts` 目前没断言这 3 个字段（已查），但字段从 workspace 类型挪到 runtime 类型会动 `types.ts`，跑一遍确认

---

## 九、要你拍板的三件事

1. 3 列迁进 `md_agent_runtime`，还是新建 `md_agent_skill_policy`？（我推荐前者）
2. 子 agent 是否限制只能选 REACT？（我倾向限制）
3. 「Harness 编排」这个名字容易和 Gateway「编排多个 harness agent」混淆，要不要换中性名（如「Harness 工作台」）？

### 9.1 拍板结果（2026-09-22）

1. **迁进 `md_agent_runtime`** —— 不新建表。理由：这三项是 ReActAgent.Builder 上的调优旋钮，
   与 `conflict_policy`（同在 runtime）是同一类东西；为 3 个字段单开一张表，多一张表要维护、
   多一次 join，换不来任何好处。
2. **限制成 REACT** —— 前后端同时收紧（前端下拉过滤 + 后端校验）。
3. **换名** —— 页面与导航从「Harness 编排」改为「**Harness 工作台**」。

---

## 十、落地结果（2026-09-22）

### 10.1 Step 1 表结构迁移

库当时三张表**均为 0 行**，所以是纯 DDL 变更，无数据搬迁。已实际执行（临时 Java 脚本带行数守卫）：

- `md_agent_runtime` 23 → **26 列**（`dynamic_skills_enabled` / `skill_code_execution_enabled` / `skill_work_dir`，
  插在 `conflict_policy` 之后）
- `md_agent_workspace` 34 → **31 列**（删同三列）

代码侧 6 个文件：

| 文件 | 改动 |
|---|---|
| `db/control-plane-schema.sql` | 3 列换位置；`md_agent_workspace` 注释标"纯 HARNESS 表" |
| `store/entity/AgentRuntimeEntity.java` | 新增 3 字段 |
| `store/entity/AgentWorkspaceEntity.java` | 删 3 字段，类注释改为"HARNESS 专有" |
| `agent/AgentDefinitionResolver.java` | `applyRuntime()` 加 3 行、`applyWorkspace()` 删 3 行 |
| `agent/ControlPlaneImporter.java` | 同上；`if (HARNESS \|\| hasWorkspaceConfig(def))` 收敛成 `if (HARNESS)`，`hasWorkspaceConfig()` 整个删掉（§八 那条风险随之处掉） |
| `controlplane/ControlPlaneValidator.java` | 删 `REACT_ONLY` 常量与 `hasAnyDisableFlag()`；REACT 分支 5 条字段级规则收敛成 1 条「不应有 workspace 行」 |

### 10.2 Step 2 后端校验

- `MyBatisControlPlaneStore.saveAgent()`：类型不可变检查放在 `validateAgentContent()` **之前**
  （不合法就不必再跑字段级校验），抛 `ControlPlaneValidationException` → 400 + `{error, violations[]}`。
- 拿掉"只在 null 时继承"的分支，改为**无条件** `incoming.setAgentType(existingType)` —— 光拦不写不住，
  请求体里那个字段必须被丢弃。
- 新增 `normalizeType(String)`（trim + toUpperCase），让 `react` / `REACT` 判等。
- 子 agent 限 REACT：`validateAgentContent` 与 `ControlPlaneValidator.validateSubAgents` 各加一条。

### 10.3 Step 3–4 前端

- `AgentsPage.vue`：删类型下拉，固定 `REACT`；HARNESS 卡片点击进 `/harness/:name`。
- `AgentDetailPage.vue`：类型改只读；删 `onTypeChange()` / `blankMemory()` / `blankWorkspace()`；
  新增「技能策略（REACT 专有）」PaneCard（`v-if="!isHarness"`，写 `md_agent_runtime`）；
  子 agent 下拉只列 REACT；返回标签随入口变化（`/agents` ↔ `/harness`）。
- `HarnessPage.vue`：**整页重写** —— 原文件 239 行全是硬编码 mock（拓扑图 / 子 agent 列表 / 策略 / 发布记录），
  与后端无一处连接。新版是「HARNESS 列表 + 关键字筛选 + 新建对话框」，点卡片进 `/harness/:agentId`。
- `router.ts` / `nav.ts`：加 `/harness/:agentId`（复用 `AgentDetailPage`，不复制 400 行表单）；导航改「Harness 工作台」。

### 10.4 Step 5 验证

- 后端 `mvn -o test`：**265 通过**；`compile` 通过。
- 前端 `vue-tsc --noEmit`：**0 错误**；`vitest run`：**68 通过**；`vite build`：通过（14s）。

### 10.5 遗留

- `AgentDetailPage.vue` 与 `AgentsPage.vue` 的 `buildBundle()` 是两份几乎同构的实现（差异只在
  "哪张表有行"）。目前是刻意的（两个向导的字段集合不同），**但如果第三处出现同样的组装逻辑，就该抽成
  共享的 `buildAgentBundle()`**，否则三份必然会漂移。
- 导入器（§八 提到的那条链路）本轮未重跑。原先 REACT 带 workspace 行会让 `writeAgents` 抛校验异常，
  而 `LegacyImportRunner.run()` 没有 try-catch —— 那会让 Spring 启动失败。改完 REACT 分支后此路径已通，
  但**导入器整体仍需实跑一次确认**（`/actuator/health` 之前返回 503 DOWN 也一并复查）。
