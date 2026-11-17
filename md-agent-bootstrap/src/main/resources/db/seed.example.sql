-- =====================================================================================
-- Md-Agent-Platform 可选种子数据（示例）
-- =====================================================================================
-- 前提：已执行 init.sql。
--
-- 说明：
--   1. 本文件不是启动必需项。不执行时，模型走 application.yml 的 spring.ai.model.*，
--      渠道列表为空，可在控制台页面创建。
--   2. 执行前必须替换所有 REPLACE_WITH_* 占位符。
--   3. 渠道种子中的 default_agent_id 指向 deployment_agent / harness_agent，
--      请确认这些 Agent 已存在，否则渠道装配会找不到默认 Agent。
--   4. 两个 INSERT 都是幂等的：记录已存在时不做任何更新。
-- =====================================================================================

-- ---------- 默认模型 ----------
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
 '默认对话模型；与 application.yml 的 spring.ai.model.* 对应',
 JSON_ARRAY('兜底', 'deepseek'),
 1, 1, 'PUBLISHED',
 1, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000,
 'seed', 'seed')
ON DUPLICATE KEY UPDATE `model_key` = `model_key`;

-- ---------- 示例渠道 ----------
INSERT INTO `md_channel`
(`channel_id`, `channel_type`, `display_name`, `description`, `owner_id`, `default_agent_id`,
 `dm_scope`, `reply_style`, `properties`, `enabled`, `status`, `version`, `published_version`,
 `published_at_ms`, `created_at_ms`, `updated_at_ms`, `created_by`, `updated_by`)
VALUES
-- 企微智能机器人（WS 长连接）
('deployment_agent-bot', 'wecom-bot', '企微机器人（deployment_agent）',
 '示例渠道，执行前需替换占位凭据',
 NULL, 'deployment_agent', NULL, NULL,
 JSON_OBJECT('botId', 'REPLACE_WITH_WECOM_BOT_ID',
             'secret', 'REPLACE_WITH_WECOM_BOT_SECRET'),
 1, 'PUBLISHED', 1, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000,
 'seed', 'seed'),
-- 飞书机器人（WS 长连接）
('feishu-main', 'feishu-bot', '飞书机器人（harness_agent）',
 '示例渠道，执行前需替换占位凭据',
 NULL, 'harness_agent', NULL, NULL,
 JSON_OBJECT('appId', 'REPLACE_WITH_FEISHU_APP_ID',
             'appSecret', 'REPLACE_WITH_FEISHU_APP_SECRET',
             'verificationToken', 'REPLACE_WITH_FEISHU_VERIFICATION_TOKEN',
             'encryptKey', 'REPLACE_WITH_FEISHU_ENCRYPT_KEY',
             'baseUrl', 'https://open.feishu.cn'),
 1, 'PUBLISHED', 1, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000,
 'seed', 'seed')
ON DUPLICATE KEY UPDATE `channel_id` = `channel_id`;
