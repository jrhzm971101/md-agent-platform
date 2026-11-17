-- =====================================================================================
-- 存量环境目录升级：分组单实例 → 分组多实例
-- =====================================================================================
-- 适用：已经执行过 env-schema.sql（或 2026-11-03 之前的等价 DDL）的数据库。
-- 新库直接执行 env-schema.sql 即可，不要再执行本脚本。
--
-- 前置顺序：
--   1. 先部署支持 instance_code 的新后端；
--   2. 再按顺序执行本目录的两份升级脚本：
--      - env-schema-upgrade-2026-11-03-remove-country-stage.sql
--      - env-schema-upgrade-2026-11-03-group-instances.sql
--   3. 最后执行 env-schema-upgrade-2026-11-03-global-group-instances.sql，
--      把每环境一份的实例元数据收敛为分组级全局实例。
--
-- 顺序不能反、也不能在旧后端仍运行时执行：旧代码的 SELECT / INSERT 仍按三元组
-- (env_code, group_code, key_code) 读写，新增 NOT NULL 列后旧后端会直接报错。
--
-- 本脚本只做纯增量：
--   * 新增 md_env_group_instance；
--     （该表在本脚本阶段仍含 env_code，下一份 global 脚本会去掉它）
--   * md_env_config 增加 instance_code，存量行回填 default；
--   * 唯一键从三元组扩成四元组。
-- =====================================================================================


CREATE TABLE IF NOT EXISTS `md_env_group_instance`
(
    `env_code`      VARCHAR(64)  NOT NULL COMMENT '所属环境',
    `group_code`    VARCHAR(64)  NOT NULL COMMENT '所属分组',
    `instance_code` VARCHAR(64)  NOT NULL COMMENT '实例标识；default 为兼容旧调用的默认实例',
    `display_name`  VARCHAR(128) NOT NULL COMMENT '实例展示名',
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '同一分组内的实例顺序',
    `created_at_ms` BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms` BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    PRIMARY KEY (`env_code`, `group_code`, `instance_code`),
    KEY `idx_group` (`group_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：分组实例';


ALTER TABLE `md_env_config`
    ADD COLUMN `instance_code` VARCHAR(64) NOT NULL DEFAULT 'default'
        COMMENT '所属实例；存量单实例数据统一为 default' AFTER `group_code`;


-- 每个已经有配置值的 (env_code, group_code) 建一条 default 实例。
-- 没有配置值的分组不预先建实例：页面新增变量时会按需创建，避免给所有环境灌入一批空分组。
INSERT INTO `md_env_group_instance`
    (`env_code`, `group_code`, `instance_code`, `display_name`, `enabled`, `sort_order`,
     `created_at_ms`, `updated_at_ms`)
SELECT c.`env_code`,
       c.`group_code`,
       'default',
       CONCAT(COALESCE(NULLIF(g.`display_name`, ''), c.`group_code`), '（默认）'),
       1,
       COALESCE(g.`sort_order`, 0),
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED),
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)
FROM `md_env_config` c
         LEFT JOIN `md_env_group` g ON g.`group_code` = c.`group_code`
GROUP BY c.`env_code`, c.`group_code`, g.`display_name`, g.`sort_order`
ON DUPLICATE KEY UPDATE `updated_at_ms` = VALUES(`updated_at_ms`);


ALTER TABLE `md_env_config`
    DROP INDEX `uk_env_group_key`,
    ADD UNIQUE KEY `uk_env_group_instance_key`
        (`env_code`, `group_code`, `instance_code`, `key_code`),
    ADD KEY `idx_instance` (`env_code`, `group_code`, `instance_code`);


-- 回滚（仅在尚未写入多实例数据时可用）：
--   ALTER TABLE md_env_config
--       DROP INDEX uk_env_group_instance_key,
--       DROP INDEX idx_instance,
--       DROP COLUMN instance_code,
--       ADD UNIQUE KEY uk_env_group_key (env_code, group_code, key_code);
--   DROP TABLE md_env_group_instance;
