-- =====================================================================================
-- 渠道初始数据 —— 把 application.yml 里原来的 messaging.bots[] 原样搬进 md_channel
-- =====================================================================================
-- 为什么需要这个脚本：
--   改造前「有哪些机器人」写在 application.yml 的 agent-platform.messaging.bots[]，
--   改造后这件事的唯一真相是 md_channel。代码侧已不再读那段 yml（MessagingProperties 已删除），
--   所以**不执行本脚本 = 原来那两个机器人不会启动**（页面上的表现是"渠道列表是空的"）。
--
-- 与 yml 的逐字段对应关系：
--   name          → channel_id            （运行时注册键，也是企微/飞书后台回调地址里的那一段）
--   platform      → channel_type          （WECOM → wecom-bot，FEISHU → feishu-bot）
--   default-agent → default_agent_id      （绑定规则都没命中时的落点）
--   wecom.*       → properties  {botId, secret}
--   feishu.*      → properties  {appId, appSecret, verificationToken, encryptKey, baseUrl}
--
-- 状态为什么直接是 PUBLISHED / published_version = 1：
--   这两个机器人改造前就在线上跑着（配置在 yml 里、进程启动即连），
--   迁入库后如果置成 DRAFT，它们会因为"未发布"而不再装配 —— 那等于这次重构把线上入口掐了。
--   做迁移数据时，"当前线上的形态"就是已发布形态。
--
-- 幂等：ON DUPLICATE KEY UPDATE channel_id = channel_id 表示"已存在就什么都不做"，
--   重复执行不会覆盖你在页面上改过的凭据（这是最容易被忽视的一点：
--   用 REPLACE 或 ON DUPLICATE KEY UPDATE 全字段，会在误重跑脚本时把线上配置改回去）。
--
-- 执行：mysql -h<host> -P<port> -u<user> -p <database> < channel-seed.sql
--
-- 凭据不再随仓库分发：下面的 REPLACE_WITH_* 必须先替换为部署环境自己的值，
-- 或者直接不执行本脚本，改在渠道管理页面创建。
-- =====================================================================================

INSERT INTO `md_channel`
(`channel_id`, `channel_type`, `display_name`, `description`, `owner_id`, `default_agent_id`,
 `dm_scope`, `reply_style`, `properties`, `enabled`, `status`, `version`, `published_version`,
 `published_at_ms`, `created_at_ms`, `updated_at_ms`, `created_by`, `updated_by`)
VALUES
-- 企微智能机器人（WS 长连接）
('deployment_agent-bot', 'wecom-bot', '企微机器人（deployment_agent）',
 '由 application.yml 的 messaging.bots[] 迁入，执行前需替换占位凭据',
 NULL, 'deployment_agent', NULL, NULL,
 JSON_OBJECT('botId', 'REPLACE_WITH_WECOM_BOT_ID',
             'secret', 'REPLACE_WITH_WECOM_BOT_SECRET'),
 1, 'PUBLISHED', 1, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000,
 'seed', 'seed'),
-- 飞书机器人（WS 长连接）
('feishu-main', 'feishu-bot', '飞书机器人（harness_agent）',
 '由 application.yml 的 messaging.bots[] 迁入，执行前需替换占位凭据',
 NULL, 'harness_agent', NULL, NULL,
 JSON_OBJECT('appId', 'REPLACE_WITH_FEISHU_APP_ID',
             'appSecret', 'REPLACE_WITH_FEISHU_APP_SECRET',
             'verificationToken', 'REPLACE_WITH_FEISHU_VERIFICATION_TOKEN',
             'encryptKey', 'REPLACE_WITH_FEISHU_ENCRYPT_KEY',
             'baseUrl', 'https://open.feishu.cn'),
 1, 'PUBLISHED', 1, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000,
 'seed', 'seed')
ON DUPLICATE KEY UPDATE `channel_id` = `channel_id`;
