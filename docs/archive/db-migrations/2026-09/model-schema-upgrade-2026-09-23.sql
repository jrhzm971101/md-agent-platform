-- =====================================================================================
-- 增量升级脚本（2026-09-23）：新增 md_model —— 模型从 application.yml 迁到控制面
-- =====================================================================================
-- 为什么单独一份，而不是并进 control-plane-schema.sql：
--   那个文件是「全新建库」脚本。md_model 已经是它的第 11 节，新库直接建得出来；
--   而已经在跑的这个库需要一条**可以现在就执行**的语句。把"给存量库补表"的动作
--   混进建库脚本里，会让人分不清"我刚执行的是新库初始化还是热库升级"。
--
-- 幂等性：本脚本是**幂等**的 —— 与 trace-schema-upgrade 那份不同，这里只是 CREATE TABLE，
--   MySQL 8 的 CREATE TABLE IF NOT EXISTS 对已存在的表是空操作，重复执行无害。
--   （那份脚本要用 ALTER，而 MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，只能标注"不幂等"。）
--
-- 执行方式：在有 mysql 客户端的环境里 source 本文件，或用等价语句手工执行。
--   本项目当前没有 mysql CLI，是用一次性 JDBC 探针执行的
--   （见 docs/develop/2026-09-23_control-plane-model-and-overview.md）。
--
-- 存量数据：**建表脚本不回填**，回填在同目录的 model-seed.sql 里 —— 两者拆开是有意的：
--   建表是"结构变更"，回填是"数据变更"，混在一个脚本里，出问题时说不清是哪一步的错。
--   回填前不会出故障：原先是 application.yml 里那个兜底 Bean（openAIChatModel）在给所有
--   没声明 model_ref 的 agent 用；库里的 model_ref 值（如 "openAIChatModel"）在没有对应
--   md_model 行时仍然按 Bean 名解析（DbModelFactory 的兜底分支），
--   所以只建表不执行 seed 时，老 agent 继续照原样跑，不会因为"表是空的"而装配失败 ——
--   代价仅仅是模型页上一行都没有（看不到、也改不了当前在用的模型）。
-- =====================================================================================

CREATE TABLE IF NOT EXISTS `md_model`
(
    `model_key`           VARCHAR(128) NOT NULL COMMENT '引用键（md_agent_runtime.model_ref / md_agent_memory.*_model_ref 填它）',
    `display_name`        VARCHAR(255) NOT NULL COMMENT '展示名（页面上唯一该露给人看的名字，理由同 md_agent.display_name）',
    `purpose`             VARCHAR(64)           DEFAULT NULL COMMENT '用途分组（对话 / 记忆归并 / 向量化…）；仅页面筛选，不进装配',
    `provider`            VARCHAR(64)           DEFAULT NULL COMMENT '厂商标识（deepseek / ark / openai…）；仅展示与筛选，路由只看 base_url',
    `base_url`            VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容端点根，如 https://api.deepseek.com',
    `api_key`             VARCHAR(512) NOT NULL COMMENT '密钥。列表接口打码，详情接口才给明文',
    `model_name`          VARCHAR(128) NOT NULL COMMENT '厂商侧模型名，如 deepseek-v4-flash',
    `endpoint_path`       VARCHAR(255)          DEFAULT NULL COMMENT '补全路径；空 = 框架默认（/v1/chat/completions）',
    `formatter`           VARCHAR(32)           DEFAULT NULL COMMENT '回填格式化器：default | deepseek-reasoning（DeepSeek 思考模式必须原样回传 reasoning_content，否则 400）',
    `reasoning_effort`    VARCHAR(32)           DEFAULT NULL COMMENT 'low | medium | high；空 = 不下发',
    `temperature`         DECIMAL(4, 3)         DEFAULT NULL COMMENT 'NULL = 不下发，用厂商默认',
    `top_p`               DECIMAL(4, 3)         DEFAULT NULL COMMENT 'NULL = 不下发',
    `max_tokens`          INT                   DEFAULT NULL COMMENT '单次生成上限；NULL = 不下发',
    `context_window_size` INT                   DEFAULT NULL COMMENT '上下文窗口（对应框架 contextWindowSize）；NULL = 不下发',
    `description`         VARCHAR(1024)         DEFAULT NULL COMMENT '备注',
    `biz_tags`            JSON                  DEFAULT NULL COMMENT '业务标签，仅控制面用',
    `is_default`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否全局兜底模型：agent 没声明 model_ref 时用它；0 = 否，1 = 是',
    `enabled`             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '停用后不参与装配',
    `status`              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
    `version`             INT          NOT NULL DEFAULT 1 COMMENT '内容版本号，每次保存 +1（从 1 开始）',
    `published_version`   INT                   DEFAULT NULL COMMENT '已发布版本号；NULL = 从未发布',
    `published_at_ms`     BIGINT                DEFAULT NULL COMMENT '最近一次发布时间',
    `created_at_ms`       BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms`       BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `created_by`          VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    `updated_by`          VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`model_key`),
    KEY `idx_status` (`status`),
    KEY `idx_default` (`is_default`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：模型连接与生成参数';

-- 附：为什么 is_default 的「全表唯一」不在 DDL 里
--   想表达的约束是"最多只有一行 is_default = 1"，但 MySQL 没有部分唯一索引
--   （Postgres 的 `CREATE UNIQUE INDEX ... WHERE is_default = 1` 在本库做不到）。
--   两个退而求其次的写法都比现状差：
--     · 唯一索引建在 is_default 上 —— 那就只允许一行 is_default = 0 了，等于禁止第二行；
--     · 用 NULL 表示"非默认" —— NULL 在三态语义里是"未设置"，会和版本快照的还原冲突
--       （快照里记的是 0，还原回来是"显式否"，而 NULL 是"没说"，两者在页面上一个样、
--        在装配层的取值判断上不一样）。
--   所以这条不变量由 MyBatisControlPlaneStore 在事务内保证：写入新默认值前先清掉旧的。
-- =====================================================================================
