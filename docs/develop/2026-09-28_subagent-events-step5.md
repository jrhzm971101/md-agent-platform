# 2026-09-28 Step 5：子 agent 派发正名为独立事件 + 两个真 bug

## 一句话结论

Harness 父 agent 派发子 agent，在框架里就是一次普通工具调用（工具名 `agent_spawn`），所以此前它
和普通工具一起混在 `ACTING` 里 —— 页面上只能看到一个"叫 agent_spawn 的工具"，看不出这里**派发过一层
子 agent**。这次把它正名为独立阶段 `SUBAGENT` → 前端 `subagent.dispatched / subagent.completed`，
顺带修掉两个在真库上实测到的 bug：**并发轮次抢号**、**同一批并发的节点会被"跑得最久的那个"吞成子节点**。

前端零改动 —— `src/features/traces/eventPresentation.ts` 早就备好了 `subagent.*` 的图标与图例。

## 问题现场

| # | 现象 | 数据上的真相 |
| --- | --- | --- |
| 1 | 执行链路页只有一层工具节点，看不出子 agent 被派发过 | 全库 1887 条事件里 `phase` 只有 `AGENT=113 / REASONING=642 / ACTING=1132`，没有一条 `SUBAGENT`；派发的 9 次调用躺在 `ACTING` 里，工具名是 `agent_spawn` |
| 2 | 子 agent 的推理标题轮次乱序 | `第 20 / 24 / 22 / 25 / 23 / 26 / 27 / 21 / 28 轮推理 · workwx_doc_agent` —— 9 个并发子 agent 共用一个整轮计数器在抢号 |
| 3 | 一次扇出 N 个，页面上却会套成链 | 未验证到"链"（当时还没正名），但同一毫秒开始的 9 次派发耗时从十几秒到几分钟不等，区间必然重叠，按"区间包含"判父子一定串 |

## 根因

**① 写入侧没有"子 agent"这个概念。** `AgentRunTraceMiddleware.onActing` 对一批工具调用一视同仁，
阶段名硬编码 `PHASE_ACTING`、标题硬编码 `"工具调用 · <工具名> · <agent>"`。框架的 `AgentEvent` 流里
也没有任何"这是子 agent"的标记位，`RuntimeContext` 又是父子共享的 —— **工具名是此刻唯一可用的判据**。

**② 成树侧判父子用的是"区间包含"，它在并发批次上不成立。** `AgentRunEventTranslator.contains` 原本是：

```java
outer.startMs <= inner.startMs && inner.endMs <= outer.endMs
        && (outer.startMs < inner.startMs || inner.endMs < outer.endMs)
```

"至少有一边真包含"这一关只挡得住**区间完全相同**的两个节点（例如同一批并行工具、耗时恰好一致）。
一旦开始时刻相同而耗时不同（并发派发的常态），跑得最久的那一个就真的"包含"了它之后的兄弟 →
父子链。旧注释里写的"同一批并行工具区间完全重合"只对耗时相同的那一批成立。

**③ 轮次计数器挂在 RunState 上、整轮共享。** 子 agent 与父 agent 共用同一个 `RunState`，
`state.iterations.incrementAndGet()` 在 9 个并发子 agent 之间抢号 —— 标题里的"第 N 轮"直接失去意义。

## 证据（2026-09-23 真库）

run `mub3rqvg-esxkhw` / agent `workwx_batch_agent` / 528 条事件：

- 派发工具名 = `agent_spawn`，`input.args` = `{"task":"…第 5 片执行者…","label":"roster-reader-3","agent_id":"workwx_doc_agent","timeout_seconds":…}`
- `output` = `{"state":"SUCCESS","result":"agent_key: agent:workwx_doc_agent:…\nsession_id: sub-bc425729-…\nstatus: ok\nreply:…","resultLength":5052}`
- `stage_start_ms=1789986753079` 那一毫秒**同时开始 9 个 `agent_spawn`** —— 并发是真的
- 9 个子 agent 的推理标题轮次乱序（见上表 #2）

## 改动

| 文件 | 改了什么 |
| --- | --- |
| `md-agent-server/src/main/java/com/md/agentplatform/server/trace/AgentTraceEvent.java` | 新增 `PHASE_SUBAGENT`（`phase` 列是 `VARCHAR(24)`，**不需要 DDL**） |
| `.../agent/middleware/AgentRunTraceMiddleware.java` | `onActing` 按工具名分流阶段与标题；`iterations` 改按 agent 名分桶；新增 `argText` / `subagentTitle` |
| `.../trace/AgentRunEventTranslator.java` | 4 个映射函数补 SUBAGENT 分支；`isNodePhase` 收编；`contains` 判据改为"外层开始得更早" |
| `.../agent/middleware/AgentRunTraceMiddlewareTest.java` | 新增 2 条用例（分流 + 轮次分桶） |
| `.../trace/AgentRunEventTranslatorTest.java` | 新增 2 条用例（词表/source + 并发平级） |

### 1. 写入侧：按工具名分流，新阶段的标题带目标与批次

```java
if (TOOL_AGENT_SPAWN.equals(call.getName())) {
    phase = PHASE_SUBAGENT;
    String targetAgent = argText(call, "agent_id");   // 直接从结构化入参取，不解析已 digest 的 args
    String label       = argText(call, "label");
    stageInput.put("targetAgent", targetAgent);       // 供成树方当节点 source
    stageInput.put("label", label);
    title = subagentTitle(targetAgent, label);        // 子 agent 派发 · workwx_doc_agent · roster-reader-3
} else {
    phase = PHASE_ACTING;
    title = "工具调用 · " + call.getName() + " · " + agent.getName();
}
```

- 标题格式：`子 agent 派发 · <目标agent> · <label>`。不带派发方（父）agent 名 —— 派发节点的父辈就是
  父的推理节点，重复父名只会挤占宽度；9 个并发节点并排时，靠"给谁 + 第几批"才能彼此区分。
- `targetAgent` 额外写进 `input`：成树方用它填节点的 `source`（页面上标题旁的小字），页面细节面板里
  也能直接看到"这次派发给谁、批次叫啥"。取的是结构化入参，与写入侧同源，不会出现开始/结束显示不一致。
- 目标 agent 拿不到时标题写"未知目标"，而不是让标题变成 `子 agent 派发 · · 第 3 批` 那种看起来像排版错乱的样子。

### 2. 轮次按 agent 分桶

```java
// RunState
private final Map<String, AtomicInteger> iterationsByAgent = new ConcurrentHashMap<>();

int nextIteration(String agentName) { … }   // 取用即自增，各 agent 从 1 开始
int totalIterations() { … }                 // 各桶求和 = run 级"一共做了多少次推理"
```

与既有的 `modelCallFolds` 同一套路：只依赖"agent 名"这一确定性输入。父 + 9 个子 agent 的轮次从此
互不打扰；run 级 `output.iterations` 语义变成"父跑掉的轮次 + 每个子 agent 各自跑掉的轮次"之和
（旧值本来也是所有 agent 累加，只是被抢号污染过）。

### 3. 成树侧：并发批次一律平级

```java
// 旧：outer.startMs <= inner.startMs && inner.endMs <= outer.endMs
//                     && (outer.startMs < inner.startMs || inner.endMs < outer.endMs)
// 新：
return outer.startMs < inner.startMs && inner.endMs <= outer.endMs;
```

判据从"区间包含"收紧为"**外层开始得更早**"：

- 同一毫秒开始的一律平级 —— 正是"一次扇出 N 个"要一眼看清的东西；
- 确实更早开始的容器（推理包住它内部的工具/派发）不受影响；
- 顺带修掉并行工具的老问题：它们此前同样会按耗时排成父子链（旧注释里"区间完全重合"的假设只对
  耗时相同的那一批成立）。

### 4. 翻译侧：4 个映射 + 收编

`startedTypeOf / completedTypeOf / kindOf / sourceOf` 各加一个 SUBAGENT 分支 →
`subagent.dispatched / subagent.completed`、`kind=subagent`、`source=<目标agent 名>`；
`isNodePhase` 收编 SUBAGENT，让它能成为树上的节点。 **老数据不回填**：历史事件 `phase` 仍是 `ACTING`，
继续按普通工具渲染。

## 验证

- 后端：`mvn -o -pl md-agent-server -am test` → **351 通过 / 0 失败**（新增 4 条：中间件 2 + 翻译 2）
- 翻译侧新增用例专门守住两件事：副词表闭集（`AgentRunEvent.TYPES/KINDS` 必须认 `subagent.*`，
  否则前端查表会在渲染时抛异常）、并发派发必须并排（`1.1 / 1.2 / 1.3` 而不是套成链）
- 手工回归（需重启后端）：用 `workwx_batch_agent` 跑一次批量任务 → 打开执行链路页，应看到
  ①每一批一个 `子 agent 派发 · <目标agent> · <批次label>` 节点；
  ②同一批的 9 个派发节点并排（不再套成链）；
  ③每个子 agent 自己的推理/工具节点挂在其派发节点下；
  ④老 run（如 `mub3rqvg-esxkhw`）仍能正常打开、不白屏

## 已知取舍与遗留

- ~~**子 agent 内部节点的归属不精确。**~~ **已解决（2026-09-28 Step 6）。** 当时的判断
  "框架不提供 span id，所以归位只能靠时间区间"是**错的**：框架给每个子实例发的 ctx 是
  `RuntimeContext.builder(parentCtx).sessionId("sub-…")`，子 agent 的每个回调都带这个 sessionId，
  而派发结果文本里恰好写着它。于是写入侧把两边都记下来（节点的 `input.agentSession`、
  派发节点的 `output.childSession`），成树方按"这条节点属于哪个子会话"直接挂到对应的派发节点下 ——
  并发与异步都精确，不再猜。详见 `2026-09-28_subagent-sync-async-step6.md`。
- ~~**子 agent 的会话 id 没有单独提字段**~~ **已提为字段（Step 6）**：`input.agentSession`（每个阶段
  属于哪个会话）+ `output.childSession`（这次派发出去的是哪个子会话）。此前只能从 `result` 文本里肉眼找。
- run 表仍无 `agent_version / prompt_version`（复现不了"当时用哪版定义"）。
- skill 漂移校验接口 `ControlPlaneStore.listSkillContentNames()` 已有、只缺 controller。
- 鉴权 / CORS / 配置脱敏仍未做。