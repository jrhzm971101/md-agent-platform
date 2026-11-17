-- ============================================================================
-- 工作区文件后端（A 路线）配套 DDL —— 2026-09-23
--
-- 本脚本做两件事：
--   1) 新建工作区 KV 库 md_agent_workspace；
--   2) 给 md_agent_workspace 表加 fs_backend 列（注意：这个表名同时也是库名）。
--
-- 第 1 步是「幂等记录」：库与表都由框架在启动时自动创建
--   （ControlPlaneStoreConfig#workspaceKvDataSource 先 CREATE DATABASE，
--    JdbcStore.initializeSchema(true) 再建 agentscope_store 等表），
-- 手动执行一次也无副作用，它的价值是让 review 时能一眼看到库里该有什么。
-- 第 2 步必须手工执行 —— 框架管不到业务表。
--
-- 方案、路径 → 命名空间映射、硬缺口清单：
--   docs/develop/2026-09-23_harness-workspace-to-mysql.md
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `md_agent_workspace`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 框架在建库后自动创建，此处仅记录结构，便于 review 与手工排查：
--
--   CREATE TABLE IF NOT EXISTS agentscope_store (
--     namespace_path VARCHAR(512) NOT NULL,
--     item_key       VARCHAR(255) NOT NULL,
--     value_json     LONGTEXT     NOT NULL,
--     version        BIGINT       NOT NULL,
--     updated_at     BIGINT       NOT NULL,
--     PRIMARY KEY (namespace_path, item_key),
--     INDEX idx_namespace (namespace_path)
--   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--
-- 复合主键 (512 + 255) * 4 = 3068 字节，卡在 InnoDB utf8mb4 的 3072 字节上限内。
-- 不要为了「放大 namespace_path」而改这张表 —— 会直接建表失败。

ALTER TABLE `md_agent_workspace`
    ADD COLUMN `fs_backend` VARCHAR(16) DEFAULT NULL
        COMMENT '工作区文件后端：LOCAL(默认，本地磁盘+shell) | REMOTE(进 md_agent_workspace KV，无 shell)';
