# 2026-09-28 Step 6：子 agent 的同步 / 异步——可控、可观测、可归位

## 一句话结论

此前"子 agent 是同步还是后台跑"完全由模型自己填的 `agent_spawn.timeout_seconds` 决定，页面上两者
**长得一模一样**：一次"丢到后台、结果还没回来"的派发被画成成功，人以为子 agent 已经给了结论。
这次做了五件事把它变成可控 + 可观测：

1. 控制面加两个字段（`subagent-force-sync` / `subagent-sync-timeout-seconds`），把执行形态从模型手里收回到编排者手里；
2. 派发节点收口时给出**结论**（`accepted` / `timeout_promoted` → 页面上的 **wait**，并附一句"结果去哪了"）；
3. 子 agent 的每个阶段都带上自己的会话 id，成树从"猜区间"改成**按子会话精确归位**；
4. run 表新增 `parent_run_id` / `task_id`，孤立链路能指回父链路；
5. 认出台账里那条 `syntheticReminders`（框架注入的 `<system-reminder>` 不再冒充用户消息）。

联调时又实测到一个真 bug：内置工具的结果文本是**被 JSON 编码过一遍**的字符串，导致上面第 2 条静默失效
（`dispatchStatus` 全是 null）。一并修掉，详见第 6 节。

## 一、框架事实（先读这段，后面所有取舍都从这里来）

| 事实 | 出处 |
| --- | --- |
| 同步 / 异步的唯一开关是 `agent_spawn` 的 `timeout_seconds`：`>0`（默认 30，上限 600）同步等待，`=0` 立刻返回后台 | `agentscope-java` → `harness/.../tool/AgentSpawnTool.java` |
| 同步等待超时后框架会**自动 promote 成后台任务**（子 agent 继续跑，父侧拿到 `timeout_promoted`） | 同上（`formatTimeoutPromoted`） |
| 应用侧可以用 ctx 键强制同步：`AgentSpawnTool.CTX_FORCE_SYNC` / `CTX_FORCE_SYNC_TIMEOUT_SECONDS`，**优先级高于模型参数** | 同上（`AgentSpawnTool` 公开常量） |
| 强制同步后：`timeout_seconds=0` 被改写成 30 秒同步（不再提交后台任务）；超时返回 `status: timeout` 并**中断**子 agent，不 promote | 同上 |
| 结果文本格式固定：`agent_key: … / agent_id: … / session_id: sub-… / status: <值> [/ task_id: task_…]` `[reply: <子 agent 回答>]`；`status` ∈ `ok / error / timeout / accepted / timeout_promoted` | 同上（`formatSpawnInfo` / `BG_RESULT_TEMPLATE`） |
| 子实例的 ctx 一定是 `RuntimeContext.builder(parentCtx).sessionId("sub-…")` —— **每个子 agent 一个独立会话 id** | 同上（`sessionId = "sub-" + hash / UUID`） |
| 合成消息带框架元数据：`Msg.METADATA_SYNTHETIC=true`、`Msg.METADATA_REMINDER_KIND="task_delivery"` | `core/message/Msg.java`；产出方 `SubagentsMiddleware#buildDeliveryReminder` |

最后一条是这次最关键的发现在：**"哪个节点属于哪个子 agent"在数据上本来就是确定的**，
Step 5 里"框架不提供 span id、归属只能靠时间区间猜"的判断是错的（已回改 Step 5 的文档）。

## 二、改动

### 后端

| 文件 | 改了什么 |
| --- | --- |
| `db/trace-schema.sql` + `db/trace-schema-upgrade-2026-09-28-subagent.sql` | `md_agent_run` 加 `parent_run_id` / `task_id` 与 `idx_parent_run` |
| `db/control-plane-schema.sql` + `db/control-plane-schema-upgrade-2026-09-28-subagent.sql` | `md_agent_workspace` 加 `subagent_force_sync` / `subagent_sync_timeout_seconds` |
| `trace/AgentTraceRun.java`、`trace/TraceRunInfo.java`、`trace/AgentTraceRegistry.java` | 两个新字段 + 构造器/`start` 重载（老签名保留转发，调用点零改动） |
| `trace/store/entity/AgentRunEntity.java`、`trace/store/MyBatisTraceStore.java` | 落库与回读两列 |
| `trace/AgentRunEvent.java` | 新增词表 `DISPATCH_ACCEPTED` / `DISPATCH_TIMEOUT_PROMOTED` / `BACKGROUND_DISPATCH_STATUSES`（写入侧与成树侧共用一份，避免两处字面量漂移） |
| `trace/AgentRunEventTranslator.java` | 成树改成两条路（先子会话精确归属、再时间区间兜底）；收口状态多一档 `wait`；`metrics` 带出派发结论 |
| `agent/middleware/AgentRunTraceMiddleware.java` | 阶段带 `agentSession`；派发结果解析出 `status/task_id/session_id`；`decodeToolText`；合成提醒识别；异步子 run 记父子关系 |
| `agent/AgentDefinition.java`、`agent/AgentDefinitionResolver.java`、`controlplane/store/entity/AgentWorkspaceEntity.java` | 两个新字段的装载 |
| `agent/AgentManager.java` | `subagentPolicies` + `applySubagentPolicy`：装配时把策略写进 `RuntimeContext` |

### 前端

| 文件 | 改了什么 |
| --- | --- |
| `src/features/traces/model.ts` | `TraceRunInfo` / `TraceRunMeta` 加 `parentRunId` / `taskId` |
| `src/features/traces/adapters.ts` | 两个字段原样透传（事件流里没有这个信息，不做推导） |
| `src/pages/TraceDetailPage.vue` | 「任务」行（task_id + 说明）、「← 回到父链路」按钮、子 agent / 后台运行中图例（后者只在真有 wait 节点时出现） |
| `src/api/controlplane/types.ts` | `AgentWorkspaceEntity` 加 `subagentForceSync` / `subagentSyncTimeoutSeconds` |
| `src/pages/AgentDetailPage.vue` | 能力开关多一项 `subagent-force-sync` + 一个秒数输入（`el-input-number`，清空 = 用框架默认，与填 0 完全相反） |
| `src/pages/HarnessPage.vue`、`src/pages/routes.smoke.test.ts`、`src/features/traces/mockEvents.ts` | 构造/夹具补字段 |

### 1. 控制面：执行形态从模型手里收回

```java
// AgentManager#applySubagentPolicy —— 只在调用方没自己设过时才写（不覆盖显式配置）
if (ctx == null || ctx.get(AgentSpawnTool.CTX_FORCE_SYNC) != null) return;
SubagentPolicy policy = subagentPolicies.get(name);
if (policy == null || !policy.forceSync()) return;
ctx.put(AgentSpawnTool.CTX_FORCE_SYNC, Boolean.TRUE);
if (policy.timeoutSeconds() != null) {           // null 不写：写了就等于"用 0 秒"，而不是"用默认"
    ctx.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, policy.timeoutSeconds());
}
```

三态语义：`NULL` = 完全交给框架（模型填什么就是什么，升级前行为）；`0` = 不强制，同样交给框架；
`1` = 强制同步。**刻意不做"1 就写死 30"**：秒数留空时让框架用它自己的默认值，框架哪天改默认我们跟着走，
不把这个数字抄进我们的库。

### 2. 派发结论：给"结果还没到"一个独立状态

写入侧从结果文本解析出 `status`，翻译侧据此决定节点是 `ok` 还是 `wait`：

| `dispatchStatus` | 含义 | 页面 |
| --- | --- | --- |
| `ok` / `error` | 同步跑完（或同步失败） | 成功 / 失败 |
| `timeout` | 强制同步下的硬超时，子 agent 已被中断 | 成功（有 error 则失败） |
| `accepted` | `timeout_seconds=0`，丢到后台，子 agent 还在跑 | **wait（pending）** |
| `timeout_promoted` | 同步等超时，框架转成后台任务 | **wait（pending）** |

`wait` 这一档不是可有可无的配色差异：它对应"这次调用没有拿到结果"这个事实。节点还会带一句 note
把下一步指出来（`task_id` + 子会话 + "结果会在后续轮次以 `<system-reminder>` 回到父上下文"）。

### 3. 精确归位：按子会话挂树

```java
// 写入侧：每个阶段都记下"我在哪个会话里"
stageInput.put("agentSession", ctx.getSessionId());
// 派发节点额外记下"这次派出去的是哪个子会话"（从结果文本解析）
resultOutput.put("childSession", lineValue(text, "session_id"));

// 成树侧：先按子会话精确归属，拿不到才退回时间区间
Node parent = sessionParentOf(node, dispatchByChildSession);
if (parent == null) { /* 老数据 / 非子 agent 节点：按区间找最内层包含者 */ }
```

为什么非加不可：异步派发在父侧只占一个很短的区间，子 agent 是在那个区间**结束之后**才（继续）跑的，
靠区间归属根本落不进派发节点的区间里。顺带也修掉了 Step 5 遗留的"并发派发子树归属靠猜"。

### 4. 孤立链路能指回父链路

子 agent 若在父 run **收口之后**才进入（`existing.finished == true`），中间件会新开一条 run，
并记下 `parent_run_id`（父 runId）与 `task_id`（从父子共享的映射里取，派发结果解析那一刻就记进去了）。
页面据此给出「← 回到父链路」入口与「任务」行。

### 5. 合成提醒不再冒充用户消息

推理入参里 role=USER、正文是一段 XML 的 `<system-reminder>` 是框架注入的后台任务交付，
判据取框架自己的元数据键（`Msg.METADATA_SYNTHETIC`）而不是正文里的标签 —— 标签会随渲染格式变形。
命中的条数写进推理节点的 `metrics.syntheticReminders`。

## 三、联调实测到的真 bug：结果文本是 JSON 编码过的

**现象**：真机跑一次异步派发（run `muktpso3-257fe2`），工具结果里明明白白写着
`status: accepted` / `task_id: task_…`，但落库的 `dispatchStatus` 是 **null** —— 页面上又被画成"同步成功"，
和这次改造要消灭的误解一模一样，只是换了条路径。

**根因**：MCP 工具的结果是原样文本，而 framework 内置工具（`agent_spawn` / `wait_async_results`）递出来的
delta 是**被 JSON 编码过一遍的字符串字面量**：

```json
"result": "\"agent_key: agent:xxx\\nagent_id: xxx\\nstatus: accepted\\ntask_id: task_xxx\\n..."
```

带一对引号，换行是**字面量 `\n` 两个字符**。按 `\n` 切行自然只得一整行，`lineValue` 返回 null。不报错、
不抛异常，只是结论全丢。

**修法**：新增 `decodeToolText` —— 只在文本以引号开头时动手；去掉首引号与末尾成对引号；
只还原 `\n \r \t \" \\` 五种转义；一个转义都没碰到的（例如正文恰好是 `"hello"`）原样返回，不剥引号。
安全阀是"宁可少改一次，也不要改错一次"：MCP 的 JSON 文本以花括号开头、自由文本不以引号开头，都不会被碰到。

## 四、DDL（已在真库 `md-agent-platform` 执行）

```sql
ALTER TABLE `md_agent_run`
    ADD COLUMN `parent_run_id` VARCHAR(255) DEFAULT NULL AFTER `input`,
    ADD COLUMN `task_id` VARCHAR(128) DEFAULT NULL AFTER `parent_run_id`,
    ADD KEY `idx_parent_run` (`parent_run_id`);

ALTER TABLE `md_agent_workspace`
    ADD COLUMN `subagent_force_sync` TINYINT(1) DEFAULT NULL AFTER `disable_tools_config`,
    ADD COLUMN `subagent_sync_timeout_seconds` INT DEFAULT NULL AFTER `subagent_force_sync`;
```

增量脚本另存为 `trace-schema-upgrade-2026-09-28-subagent.sql` 与
`control-plane-schema-upgrade-2026-09-28-subagent.sql`（不幂等，重复跑报 Duplicate column，无害）。
存量数据两列留 NULL = 行为与升级前完全一致。

## 五、验证

| 项 | 结果 |
| --- | --- |
| 后端测试 | `mvn -o -pl md-agent-server -am test` → **361 通过 / 0 失败**（基线 351 + 新增 10） |
| 新增用例 | 中间件 6 条（阶段带 session、合成提醒、派发解析、JSON 编码文本、异步子 run 记父子、全新调用无父）+ 翻译 4 条（子会话精确归属、后台派发 wait、同步仍 ok、合成提醒指标） |
| 前端 | `npx vue-tsc --noEmit` 干净；`npx vitest run` → **153 通过**（含 2 条基于真机响应 fixture 的新用例） |
| 真机联调 | 见下 |

真机联调（`test_harness_agent` 派发 `test-workwx-doc-agent`，`timeout_seconds=0`，run `muktxn6e-pt1ohn`）：

- 派发节点 `subagent.completed` 的 `status` = **wait**（此前是 ok），`payload.note` 写明
  "子 agent 已在后台继续执行…（task_id=task_9f926eb4-…）"；
- `payload.metrics` = `{dispatchStatus: accepted, taskId: task_…, childSession: sub-926ebad3-…}`；
- 子 agent 的 60+ 个节点（各轮推理 + 工具调用）全部挂在 `1.2.x` —— 也就是那次派发节点（`1.2`）下面，
  靠 `agentSession` 精确归位；
- `GET /api/traces/{runId}` 已返回 `parentRunId` / `taskId` 两个键；
- 那次响应的前 7 条事件原样存为前端 fixture（`run-muktxn6e-async-dispatch-events.json`），
  锁住"wait + 子会话归属"这两个形状。

控制面开关也真机验过一次（run `codex-force-sync-1`）：把 `test_harness_agent` 的
`subagent_force_sync` 置 1、秒数 20，重载后跑同一个 prompt（模型仍旧填 `timeout_seconds=0`），
派发结果是 **`status: ok`** 且子 agent 的回答直接内联在 `reply:` 里（不再是 `accepted` + `task_id`）——
证明"`timeout_seconds=0` 被强制改写成同步等待"这条框架行为确实通过 ctx 生效了。验证完已把两列改回
NULL 并重载，恢复升级前的行为。

## 六、已知取舍与遗留

- **`parent_run_id` 在真机上还没被触发过。** 实测的两种后台派发里，子 agent 都是**在父 run 内**进入的
  （父 run 收口后子 agent 仍继续往同一棵树里写事件），此时它复用父 run，不开新 run —— 这是设计内行为
  （一次用户调用 = 一棵树），子树的归位靠 `childSession` 而不是父子 run。新开 run 那条路径（子 agent 的
  `onAgent` 发生在父 run 收口之后）由单测覆盖，真机需要"父 run 先收口、子 agent 更晚才启动"才会出现。
- run 表仍无 `agent_version / prompt_version`（复现不了"当时用哪版定义"）。
- skill 漂移校验接口 `ControlPlaneStore.listSkillContentNames()` 已有、只缺 controller。
- 鉴权 / CORS / 配置脱敏仍未做。
- 强制同步的秒数没有做"必须小于某个上限"的前端校验（框架会在 600 秒处夹住）。