-- =====================================================================================
-- 渠道与网关落库脚本 —— Channel / ChannelBinding / Gateway 从 application.yml 迁入 MySQL
-- =====================================================================================
-- 设计文档：docs/develop/2026-09-22_channel-and-gateway-control-plane.md（先读它再看本脚本）
--
--   md_channel           渠道实例（一个 IM 机器人 = 一行），含类型特有凭据 properties
--   md_channel_binding   该渠道的路由绑定规则（一条消息该交给哪个 agent），一对多
--   md_gateway           网关配置（当前只有主 agent），单行表 id=1
--
-- 三条与 control-plane-schema.sql 一致的约定（那里写得更细，这里不重复）：
--   1）JSON 列必须配 @TableName(autoResultMap = true) + JacksonTypeHandler；
--   2）TINYINT(1) 开关是「三态」：NULL = 未设置，0 = 显式关闭，1 = 显式开启；
--   3）不做外键、不做级联删除，引用完整性由应用层校验并给出可读报错。
--
-- 执行方式（手工，不做自动建表，与 control-plane-schema.sql / trace-schema.sql 一致）：
--   mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < channel-schema.sql
-- =====================================================================================

-- ---------- 1. 渠道实例 ----------
-- 一行 = 一个「已接通的消息入口」。同一平台可以有多个实例（多机器人 / 多应用），
-- 彼此的凭据互不影响，因此 channel_id 是业务主键，而不是 (type) 唯一。
--
-- properties 存类型特有的连接凭据：
--   wecom  : botId / secret            （企微智能机器人 WS 长连接，非自建应用回调）
--   feishu : appId / appSecret / verificationToken / encryptKey / baseUrl
-- 为什么不逐字段建列：不同平台的凭据字段完全不同（企微两个、飞书五个，将来钉钉又是另一套），
-- 逐字段建列会让「加一个平台」= 改 DDL。而它在页面上是「按类型动态渲染的一组输入框」，
-- 不是可增删的表格，也就没有按子字段查询/排序的需求 —— 与 md_agent_sub_agent 的判断依据相反。
-- 需要按子字段校验（缺 botId 直接拒）由 ChannelValidator 在应用层做，报错比 SQL 更可读。
--
-- default_agent_id：该渠道的兜底 agent（绑定规则都没命中时用），对应官方 ChannelConfig.defaultAgentId。
-- dm_scope：单聊会话键的收敛范围，取值与官方 DmScope 同名（MAIN / PER_PEER / PER_CHANNEL_PEER /
--           PER_ACCOUNT_CHANNEL_PEER）。直接沿用官方枚举名，是为了路由实现能原样交给官方 ChannelRouter。
-- reply_style：本项目扩展列（官方没有）。企微用流式思考卡片、飞书用卡片，形态由平台决定，
--           所以它属于「渠道」而不属于「agent」——同一个 agent 从企微和飞书进来可以长得不一样。
CREATE TABLE IF NOT EXISTS `md_channel`
(
    `channel_id`        VARCHAR(128) NOT NULL COMMENT '渠道唯一名（运行时注册键，也是页面主键）',
    `channel_type`      VARCHAR(32)  NOT NULL COMMENT 'wecom | feishu，决定用哪个适配器工厂装配',
    `display_name`      VARCHAR(128) NOT NULL COMMENT '展示名。必填：页面列表首列取它，channel_id 降为次要素引；不参与装配、不参与唯一键',
    `description`       VARCHAR(1024)         DEFAULT NULL COMMENT '用途说明，仅控制面用，不进装配',
    `owner_id`          VARCHAR(64)           DEFAULT NULL COMMENT '归属人（对齐官方 ChannelConfigEntry.ownerId）',
    `default_agent_id`  VARCHAR(128)          DEFAULT NULL COMMENT '兜底 agent；NULL = 用网关主 agent',
    `dm_scope`          VARCHAR(32)           DEFAULT NULL COMMENT '单聊会话键范围；NULL = 官方默认 MAIN',
    `reply_style`       VARCHAR(32)           DEFAULT NULL COMMENT '回复渲染风格；NULL = 按平台推断',
    `properties`        JSON                  DEFAULT NULL COMMENT '类型特有凭据（见上方说明）',
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否参与装配（停用但不删除）',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
    `version`           INT          NOT NULL DEFAULT 1 COMMENT '内容版本号，每次保存 +1',
    `published_version` INT                   DEFAULT NULL COMMENT '已发布版本号；NULL = 从未发布',
    `published_at_ms`   BIGINT                DEFAULT NULL COMMENT '最近一次发布时间',
    `created_at_ms`     BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms`     BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `created_by`        VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    `updated_by`        VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`channel_id`),
    KEY `idx_type` (`channel_type`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：渠道实例（IM 消息入口）';

-- ---------- 2. 路由绑定 ----------
-- 一行 = 一条「什么消息交给哪个 agent」的规则。对应官方 ChannelBinding record，字段逐一对应，
-- 目的是让路由能直接交给官方 ChannelRouter 求值，而不是自己写一套分层匹配（那必然与官方漂移）。
--
-- 匹配优先级（官方 ChannelRouter 的层级，高 → 低）：
--   peer > parent_peer > guild+roles > guild > team > account > channel > default_agent_id > 网关主 agent
-- 同一层内按 sort_order 升序取第一条命中。
--
-- 各列的取值来源（本项目两种平台的映射）：
--   wecom  : peer = 单聊 userid / 群聊 chatid；account = botId
--   feishu : peer = open_id / chat_id；           account = appId
-- 其余列（guild / team / roles / parent_peer）当前没有数据来源，保留是为了「将来接 Discord/Slack
-- 这类有服务器与角色概念的平台时不用改 DDL」—— 这与官方字段集保持一致，而不是我们多设计出来的。
CREATE TABLE IF NOT EXISTS `md_channel_binding`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（仅作 MyBatis-Plus 的把手）',
    `channel_id`    VARCHAR(128) NOT NULL COMMENT '所属渠道',
    `agent_id`      VARCHAR(128) NOT NULL COMMENT '命中后交给哪个 agent',
    `peer`          VARCHAR(255)          DEFAULT NULL COMMENT '精确 peer key，形如 direct:u_42（最高优先级）',
    `parent_peer`   VARCHAR(255)          DEFAULT NULL COMMENT '父 peer key（线程继承）',
    `guild`         VARCHAR(128)          DEFAULT NULL COMMENT '服务器/工作区 id',
    `roles`         JSON                  DEFAULT NULL COMMENT '角色 id 数组；与 guild 同时给出时为 guild+roles 层',
    `team`          VARCHAR(128)          DEFAULT NULL COMMENT '团队 id',
    `account`       VARCHAR(128)          DEFAULT NULL COMMENT '账号 id（多机器人区分）',
    `channel`       VARCHAR(128)          DEFAULT NULL COMMENT '渠道 id（同渠道配置内极少用，对齐官方）',
    `session_scope` VARCHAR(32)           DEFAULT NULL COMMENT '该绑定的会话范围覆盖；NULL = 用渠道级 dm_scope',
    `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '同层内的匹配顺序，从 0 开始',
    PRIMARY KEY (`id`),
    KEY `idx_channel` (`channel_id`, `sort_order`),
    KEY `idx_agent` (`agent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：渠道的路由绑定规则';

-- ---------- 3. 网关配置（单行） ----------
-- 为什么用「单行表」而不是配置表 + 若干行：网关当前只有一个决策项 —— 主 agent
-- （没有任何绑定与渠道默认值命中时，消息交给谁）。把它做成多行会让页面出现
-- 「到底哪一行生效」这种必须靠约定回答的问题，而一个部署本来就只有一套网关。
-- 将来若出现真正的多网关需求（多租户各一套），那时再加 tenant 列或另起一张表，
-- 比现在预留一个用不上的维度更省事。
--
-- 这一行必须由脚本预置（id=1）：运行时读不到时会退回「主 agent 未设置」，
-- 语义与「库里没有这行」不同，所以不做「读不到就插入」的隐式兜底。
CREATE TABLE IF NOT EXISTS `md_gateway`
(
    `id`            INT          NOT NULL COMMENT '固定 1（单行表）',
    `main_agent_id` VARCHAR(128)          DEFAULT NULL COMMENT '网关主 agent；NULL = 未设置',
    `description`   VARCHAR(512)          DEFAULT NULL COMMENT '备注',
    `updated_at_ms` BIGINT                DEFAULT NULL COMMENT '最后修改时刻（epoch millis）',
    `updated_by`    VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：网关配置（单行）';

INSERT INTO `md_gateway` (`id`, `main_agent_id`, `description`, `updated_at_ms`, `updated_by`)
VALUES (1, NULL, '网关主 agent：所有绑定规则与渠道兜底都没命中时的落点', NULL, NULL)
ON DUPLICATE KEY UPDATE `id` = `id`;
