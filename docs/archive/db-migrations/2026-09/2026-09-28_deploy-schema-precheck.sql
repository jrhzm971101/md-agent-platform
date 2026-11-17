-- =====================================================================================
-- 部署前置脚本（2026-09-28）—— 新服务器库补齐 4 列 + 1 索引
-- =====================================================================================
-- 【这个脚本是干什么的】
--   把「本机开发库已升过、但新服务器库还没升」的增量结构补齐，让应用在新库上能正常启动。
--
-- 【为什么必须执行，不能跳过】
--   md_agent_run / md_agent_workspace 两张表对应的 Java 行模型是 MyBatis-Plus 实体
--   （AgentRunEntity / AgentWorkspaceEntity）。MyBatis-Plus 的 SQL 是<b>按实体字段生成列名</b>的：
--   selectList() 会展开成 `SELECT run_id, ..., parent_run_id, task_id, ... FROM md_agent_run`。
--   列不存在 → MySQL 直接抛 "Unknown column 'parent_run_id' in 'field list'"。
--
--   更要命的是爆炸位置：AgentManager 的 @PostConstruct 会调用
--   AgentDefinitionResolver.resolveAll() → store.loadAllAgentBundles() → 读 md_agent_workspace。
--   也就是说这不是"某个接口 500"，而是**应用直接起不来**（Spring 容器初始化失败）。
--
-- 【为什么单独一份脚本，不并进 control-plane-schema.sql / trace-schema.sql】
--   那两份是「全新建库」脚本，用的是 CREATE TABLE IF NOT EXISTS —— 对已存在的表是空操作。
--   把 ALTER 混进去会让"重跑一遍建表脚本"变成不安全的事（本项目既定约定，
--   与 control-plane-schema-upgrade-2026-09-28-subagent.sql 等增量脚本同一套路）。
--
-- 【幂等性】
--   MySQL 8 不支持 ADD COLUMN IF NOT EXISTS（那是 MariaDB 扩展），所以本脚本**不是**幂等的。
--   重复执行会报：
--     ERROR 1060 (42S21): Duplicate column name 'parent_run_id'
--     ERROR 1061 (42000): Duplicate key name 'idx_parent_run'
--   见到这两种报错说明已经升过了，可以直接跳过，不是故障。
--
-- 【执行前建议：确认现状】
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA='md-agent-platform' AND TABLE_NAME='md_agent_run'
--      AND COLUMN_NAME IN ('parent_run_id','task_id');
--   -- 返回 2 行 = 已升级完成，无需再执行本脚本
--
-- 【存量数据】
--   新库这两张表当前都是 0 行，所以新列落下来都是 NULL，不存在兼容问题。
--   即使有存量数据，这 4 列全部 DEFAULT NULL、语义就是"未设置"，
--   NULL 的表现与升级前完全一致（parent_run_id 为空 = 用户直接发起；subagent_force_sync 为空 = 仍由框架/模型决定）。
-- =====================================================================================

USE `md-agent-platform`;

-- -------------------------------------------------------------------------------------
-- 一、md_agent_run：补 parent_run_id / task_id 两列 + idx_parent_run 索引
-- -------------------------------------------------------------------------------------
-- 为什么加（业务背景）：
--   异步子 agent（agent_spawn 的 timeout_seconds=0，或同步等待超时后被框架 promote 成后台任务）
--   会在父 run 收口之后才真正开跑。中间件发现上下文里的 RunState 已 finished，只能新开一条 run ——
--   此时若不记下"父是谁"，页面上就会出现一棵不知道从哪来的孤立链路。
--   task_id 则把它和框架 TaskRepository 里的后台任务号对上，便于按任务号反查。
--
-- 为什么 idx_parent_run 是普通索引而不是唯一索引：
--   一个父 run 可以派发出多个异步子 run（扇出），所以 parent_run_id 天然一对多。
--   这个索引服务的是「从父链路点进它派发出去的异步子链路」这一反向查询
--   （WHERE parent_run_id = ?），没有它就只能全表扫。
--
-- 列位置放在 `input` 之后：与 trace-schema.sql 的声明顺序保持一致，
--   这样将来对比"代码 schema vs 线上库"时不会因为列序不同产生假差异。

ALTER TABLE `md_agent_run`
    ADD COLUMN `parent_run_id` VARCHAR(255) DEFAULT NULL
        COMMENT '派发出这条 run 的父 run；NULL=用户直接发起（见上面 parent_run_id 的说明）'
        AFTER `input`,
    ADD COLUMN `task_id` VARCHAR(128) DEFAULT NULL
        COMMENT '框架 TaskRepository 的后台任务号；NULL=不是后台任务跑起来的'
        AFTER `parent_run_id`,
    ADD KEY `idx_parent_run` (`parent_run_id`);

-- -------------------------------------------------------------------------------------
-- 二、md_agent_workspace：补 subagent_force_sync / subagent_sync_timeout_seconds 两列
-- -------------------------------------------------------------------------------------
-- 为什么加（业务背景）：
--   框架默认「子 agent 同步还是异步」是由模型自己填的 agent_spawn.timeout_seconds 决定的
--   （0=后台、>0=同步、超时还会自动 promote 成后台任务）。对"必须拿到结果才能继续"的编排来说，
--   这种不确定性是致命的 —— 同一份编排每次跑的形态都可能不同。
--   置 subagent_force_sync=1 后，应用侧（AgentManager.stream）会在 RuntimeContext 上写
--   CTX_FORCE_SYNC，其优先级高于模型参数：timeout_seconds=0 被强制改写成同步等待，
--   超时也不再 promote，而是中断子 agent 并返回 status: timeout。
--
-- 为什么是 TINYINT(1) 而不是 BOOLEAN 的"显式 0/1 二值"：
--   这是三态列。NULL = "没说，用框架默认（由模型决定）"；1 = 显式强制同步。
--   刻意不用 NOT NULL DEFAULT 0：那会让所有存量 harness agent 在升级瞬间变成"显式不强制"，
--   语义上与"未表态"混为一谈 —— 而"强制同步"会改变已有编排的运行时行为
--   （后台派发变成阻塞等待），必须是页面上显式打开的结果。

ALTER TABLE `md_agent_workspace`
    ADD COLUMN `subagent_force_sync` TINYINT(1) DEFAULT NULL
        COMMENT '子 agent 是否强制同步执行；NULL=用框架默认（由模型决定），1=强制同步'
        AFTER `disable_tools_config`,
    ADD COLUMN `subagent_sync_timeout_seconds` INT DEFAULT NULL
        COMMENT '强制同步时的等待秒数；NULL=框架默认 30，>600 由框架夹到 600。仅在 force_sync=1 时有意义'
        AFTER `subagent_force_sync`;

-- =====================================================================================
-- 执行完成后的自检（应返回 4 行）
-- =====================================================================================
-- SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
--   FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = 'md-agent-platform'
--    AND ( (TABLE_NAME='md_agent_run'       AND COLUMN_NAME IN ('parent_run_id','task_id'))
--       OR (TABLE_NAME='md_agent_workspace' AND COLUMN_NAME IN ('subagent_force_sync','subagent_sync_timeout_seconds')) )
--  ORDER BY TABLE_NAME, ORDINAL_POSITION;
--
-- 索引自检（应返回 1 行）
-- SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
--   FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA='md-agent-platform' AND TABLE_NAME='md_agent_run' AND INDEX_NAME='idx_parent_run';
-- =====================================================================================