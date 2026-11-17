-- =====================================================================================
-- 运行链路观测（trace）落库脚本 —— Step 2
-- =====================================================================================
-- 两张表，职责单一，不做外键（沿用本项目的既定风格：外键会把删除顺序变成隐式约束，
-- 而观测数据是可丢弃的，不值得为它引入写入失败的可能）：
--   md_agent_run        一次 run 一行（run 级属性 + 汇总），主键 run_id
--   md_agent_run_event  一次阶段事件一行（STARTED / COMPLETED），自增主键 + (run_id, seq) 唯一
--
-- 为什么要「事件表」而不是把阶段塞进 run 表的一个 JSON 列：
--   1. 事件是只增不改的（纯 INSERT），没有 UPDATE 竞争，也不需要行锁；
--   2. seq 是前端断点续传的游标，(run_id, seq) 唯一键让"重复投递"变成幂等错误而不是脏数据；
--   3. 一次 run 的事件是几十到几百条，塞 JSON 列会让"按 seq 取增量"变成全量读取 + 应用层过滤。
--
-- 时间为什么用 BIGINT（epoch millis）而不是 DATETIME：
--   领域模型（AgentTraceRun / AgentTraceEvent）里就是 epoch millis，直存直取，
--   不引入任何时区换算 —— 一旦 JDBC URL 上的 serverTimezone 被改动，DATETIME 列的历史数据
--   会整体漂移，而 BIGINT 不受影响。代价是在 SQL 客户端里不便肉眼阅读，用
--   FROM_UNIXTIME(started_at_ms / 1000) 包一层即可。
--
-- 「来源」(source) 为什么必须由入口方写下来，而不是查询时反推：
--   sessionId 由每个入口自己算（企微 = single-<userId>-<当天日期>、群聊 = <chatId>-<日期>、
--   页面对话 = console-<8 位随机>、飞书 = userId、定时任务 = 作业名），格式上互不相干 ——
--   靠前缀猜既猜不全也迟早猜错，而"这条链路是从哪来的"偏偏是排查时的第一个问题。
--   所以四个入口（console / wecom / feishu / schedule）在建 run 时各写各的常量、此后不再变；
--   NULL 表示入口方没标注，页面显示为空而不是编一个值出来。
--
-- 工作流两列为什么直接加在 run 表上、而不是新开一张关联表：
--   关联是 1:1（一次 agent 调用最多属于一个工作流节点），新表意味着每次查链路都要多一次 JOIN，
--   而这两列加起来才 128 字节。加列之后，执行链路列表可以直接按"只看某次工作流运行"筛选。
--   注意它们的 NULL 语义与 source 相反：这里 NULL = 确实不属于任何工作流，
--   而 source 的 NULL = 入口方没标注。
--   与 workflow-schema-upgrade-2026-09-23.sql 的关系：那份给已存在的库补列，
--   这份给新装环境用，两边的列定义必须保持一致。
--
-- parent_run_id / task_id 两列为什么在 run 表上（2026-09-28 增量）：
--   子 agent 默认与父 agent 共用同一个 RunState，也就是"同一棵调用树"，用不着这两列。
--   但异步子 agent（agent_spawn 带 timeout_seconds=0，或同步超时后被 promote 成后台任务）
--   会在父 run 已经收口之后才真正开始跑 —— 那一刻中间件发现上下文里的 RunState 已经 finished，
--   只能给它新开一条 run。于是"这条 run 是被谁派发出来的"就断线了，页面上表现为
--   一棵没有根、也不知道从哪来的孤立链路。
--   parent_run_id = 那个已经收口的父 run，task_id = 框架 TaskRepository 里的后台任务号。
--   两者都可能为 NULL：parent_run_id 的 NULL = 这是一次用户直接发起的 run；task_id 的 NULL =
--   这次 run 不是由后台任务跑起来的（同步子 agent、或框架没给 task_id 的路径）。
--   与 source / workflow_* 一样在 run 建立那一刻冻结，是历史事实。
--
-- idx_parent_run 为什么存在：从父链路点进"它派发出去的异步子链路"是反向查询
--   （WHERE parent_run_id = ?），没有这个索引就只能全表扫。
--
-- idx_user_session 为什么必须带上 user_id：
--   run 表的 session_id 与状态表的 session_id 不是同一个串（后者被框架打包成
--   <userId>:<sessionId>），会话页要把两边对齐必须同时用上 (user_id, session_id)，
--   而会话聚合（/api/sessions）的分组键也正是这两列。
-- =====================================================================================

CREATE TABLE IF NOT EXISTS `md_agent_run`
(
    `run_id`        VARCHAR(255) NOT NULL COMMENT '运行 id，形如 t3k9f2a1-x7b2q9（字典序 = 时间序）',
    `agent_name`    VARCHAR(255) NOT NULL COMMENT 'agent 名（前端叫 agentId）',
    `agent_kind`    VARCHAR(16)  NOT NULL COMMENT 'react | harness，在 run 建立那一刻冻结',
    `session_id`    VARCHAR(255) DEFAULT NULL COMMENT '会话 id',
    `user_id`       VARCHAR(255) DEFAULT NULL COMMENT '发起人（企微 openId / 飞书 userId 等）',
    `source`        VARCHAR(32)  DEFAULT NULL COMMENT '触发来源：console | wecom | feishu | schedule | workflow；NULL=入口方没写',
    `workflow_run_id`   VARCHAR(64) DEFAULT NULL COMMENT '所属工作流运行（md_workflow_run.run_id）；NULL=不是工作流发起的',
    `workflow_node_key` VARCHAR(64) DEFAULT NULL COMMENT '所属工作流节点 key；NULL=不是工作流发起的',
    `status`        VARCHAR(16)  NOT NULL COMMENT 'RUNNING | DONE | ERROR',
    `started_at_ms` BIGINT       NOT NULL COMMENT '开始时刻（epoch millis）',
    `ended_at_ms`   BIGINT       DEFAULT NULL COMMENT '结束时刻；进行中为 NULL',
    `input_tokens`  INT          NOT NULL DEFAULT 0 COMMENT '输入 token 合计',
    `output_tokens` INT          NOT NULL DEFAULT 0 COMMENT '输出 token 合计',
    `cached_tokens` INT          NOT NULL DEFAULT 0 COMMENT '命中缓存的输入 token 合计',
    `error`         TEXT         DEFAULT NULL COMMENT '失败摘要（异常类名 + message）',
    `system_prompt` TEXT         DEFAULT NULL COMMENT '本轮生效的系统提示词（run 级属性，不占事件序号）',
    `input`         JSON         DEFAULT NULL COMMENT '顶层输入摘要（消息条数 + 每条消息的简述）',
    `parent_run_id` VARCHAR(255) DEFAULT NULL COMMENT '派发出这条 run 的父 run；NULL=用户直接发起（见上面 parent_run_id 的说明）',
    `task_id`       VARCHAR(128) DEFAULT NULL COMMENT '框架 TaskRepository 的后台任务号；NULL=不是后台任务跑起来的',
    PRIMARY KEY (`run_id`),
    KEY `idx_agent_started` (`agent_name`, `started_at_ms`),
    KEY `idx_started` (`started_at_ms`),
    KEY `idx_session` (`session_id`),
    KEY `idx_parent_run` (`parent_run_id`),
    KEY `idx_user_session` (`user_id`, `session_id`),
    -- 工作流详情页要按"某次编排"取链路（单看 workflow_run_id 的过滤走最左前缀），
    -- 两列顺序按这个查询来定
    KEY `idx_workflow_run` (`workflow_run_id`, `workflow_node_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='agent 运行链路：run 级汇总';

CREATE TABLE IF NOT EXISTS `md_agent_run_event`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `run_id`         VARCHAR(255) NOT NULL COMMENT '所属 run',
    `seq`            INT          NOT NULL COMMENT '事件序号：同一 run 内从 1 开始、连续、产生即冻结',
    `stage_seq`      INT          NOT NULL COMMENT '所属阶段标识（= 该阶段 STARTED 事件的 seq）',
    `event_type`     VARCHAR(16)  NOT NULL COMMENT 'STARTED | COMPLETED',
    `phase`          VARCHAR(24)  NOT NULL COMMENT 'AGENT | REASONING | ACTING',
    `title`          VARCHAR(512) DEFAULT NULL COMMENT '一句话摘要',
    `status`         VARCHAR(16)  NOT NULL COMMENT 'RUNNING | OK | ERROR',
    `stage_start_ms` BIGINT       NOT NULL COMMENT '阶段开始时刻，父子关系靠它算出来，两种事件都要带',
    `cost_ms`        BIGINT       NOT NULL DEFAULT 0 COMMENT '阶段耗时；STARTED 恒为 0',
    `input`          JSON         DEFAULT NULL COMMENT '阶段入参（只写在 STARTED 上）',
    `output`         JSON         DEFAULT NULL COMMENT '阶段出参（只写在 COMPLETED 上）',
    `error`          TEXT         DEFAULT NULL COMMENT '失败摘要',
    `created_at_ms`  BIGINT       NOT NULL COMMENT '落库时刻，用于排查「事件比 run 晚多久落盘」',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_seq` (`run_id`, `seq`),
    KEY `idx_run_stage` (`run_id`, `stage_seq`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='agent 运行链路：阶段事件（只增不改）';
