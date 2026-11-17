-- =====================================================================================
-- 模型初始数据 —— 把 application.yml 的 spring.ai.model.* 原样搬进 md_model
-- =====================================================================================
-- 为什么需要这个脚本（和 channel-seed.sql 是同一个理由）：
--   改造前「用哪个模型」这件事写在 application.yml 的 spring.ai.model.*，
--   由 ModelBeanConfig 造出一个全局单例 Bean（名字是 openAIChatModel），所有 agent 共用。
--   改造后这件事的唯一真相是 md_model，代码侧只保留 Bean 作为最后一级兜底。
--   **不执行本脚本的后果不是报错，而是页面空着**：兜底链路仍然成立
--   （DbModelFactory 在库里找不到默认模型时会落回 Bean），agent 照常跑，
--   但模型页上一行都没有 —— 你既看不到当前在用哪个模型，也改不了它。
--
-- 与 yml 的逐字段对应关系（要让这一行与 Bean 完全等价，逐字段都得对上）：
--   base-url         → base_url            （https://api.deepseek.com）
--   api-key          → api_key
--   model-name       → model_name          （deepseek-v4-flash）
--   reasoning-effort → reasoning_effort    （low → GenerateOptions.reasoningEffort）
--   embedding-model  → 不进本表            （向量化模型是另一条链路，见 ModelConfigProperties.embeddingModel）
--   其余字段留 NULL = 不下发，与 Bean 的行为一致（Bean 也没设 temperature / topP / maxTokens / contextWindowSize / endpointPath）。
--
-- 两个不能照抄 yml 的地方，抄错了就是线上故障：
--   1) formatter 必须写 deepseek-reasoning。yml 里没有这一项，但 ModelBeanConfig **写死了**
--      DeepSeekReasoningBackfillFormatter；DB 行不写这一格就等于悄悄把思考模式的回填关掉，
--      DeepSeek 思考模式不回填 reasoning_content 会直接 400。
--   2) endpoint_path 留 NULL。Bean 没设 endpointPath，框架默认 /v1/chat/completions；
--      在库里写死一个路径反而会在换厂商时变成绊脚石。
--
-- 状态为什么直接是 PUBLISHED / published_version = 1：
--   这个模型改造前就在线上跑着（进程启动即生效），是当前**所有**没声明 model-ref 的 agent 实际在用的那个。
--   迁入后如果置成 DRAFT，它会因为"未发布"而不参与装配，等于这次重构把线上模型掐了。
--   做迁移数据时，"当前线上的形态"就是已发布形态。
--
-- is_default = 1：
--   库里应当时刻最多只有一行默认模型（该不变量由 MyBatisControlPlaneStore 在事务内维护，
--   MySQL 没有部分唯一索引，DDL 里建不出来）。本脚本只在 INSERT 时给这一格，
--   重复执行不会覆盖你在页面上改成的其它默认值。
--   ⚠️ 若你已经在页面上把别的模型设成了默认，请先取消它再跑本脚本，
--      否则会短暂出现两个默认标记（页面以其中一个为准，装配期取 LIMIT 1）。
--
-- 幂等：ON DUPLICATE KEY UPDATE model_key = model_key 表示"已存在就什么都不做"。
--   重复执行不会覆盖你在页面上改过的端点、密钥或参数（这是最容易被忽视的一点：
--   用 REPLACE 或 ON DUPLICATE KEY UPDATE 全字段，会在误重跑脚本时把线上配置改回去）。
--
-- 执行：mysql -h<host> -P<port> -uroot -p md-agent-platform < model-seed.sql
--   （本项目当前没有 mysql CLI，是用一次性 JDBC 探针执行的，
--     见 docs/develop/2026-09-23_control-plane-model-and-overview.md）
-- =====================================================================================

INSERT INTO `md_model`
(`model_key`, `display_name`, `purpose`, `provider`, `base_url`, `api_key`, `model_name`,
 `endpoint_path`, `formatter`, `reasoning_effort`, `temperature`, `top_p`, `max_tokens`,
 `context_window_size`, `description`, `biz_tags`, `is_default`, `enabled`, `status`,
 `version`, `published_version`, `published_at_ms`, `created_at_ms`, `updated_at_ms`,
 `created_by`, `updated_by`)
VALUES
('ds-flash', 'DeepSeek V4 Flash', '对话', 'deepseek',
 'https://api.deepseek.com',
 'REPLACE_WITH_MODEL_API_KEY',
 'deepseek-v4-flash',
 NULL, 'deepseek-reasoning', 'low',
 NULL, NULL, NULL, NULL,
 '由 application.yml 的 spring.ai.model.* 迁入，与兜底 Bean openAIChatModel 逐字段等价',
 JSON_ARRAY('兜底', 'deepseek'),
 1, 1, 'PUBLISHED',
 1, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000,
 'seed', 'seed')
ON DUPLICATE KEY UPDATE `model_key` = `model_key`;
