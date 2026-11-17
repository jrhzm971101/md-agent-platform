-- =====================================================================================
-- 实例配置从「按环境」收敛为「按实例」
-- =====================================================================================
-- 背景：md_env_group_instance 已经是分组级全局元数据，但值仍然存在
-- md_env_config(env_code, group_code, instance_code, key_code) 里，导致同一个实例
-- 在不同环境可以有不同的值，实例编辑弹窗还必须先选一个「配置环境」。
--
-- 目标模型：
--   实例 = 固定元数据 + 固定配置值；
--   环境 = 只选择绑定哪些实例。
-- 需要不同配置时，用户新建一个实例即可。
--
-- 迁移策略：
--   1. 新建 md_env_instance_config(group_code, instance_code, key_code)；
--   2. 对每个实例，按「值指纹」把各个环境分组：
--        指纹相同的环境共用同一个实例；
--        指纹不同的环境，保留一个实例给第一组，其余环境克隆出独立实例；
--   3. 更新环境绑定，让每个环境指向它实际对应的实例；
--   4. 复制配置值到 md_env_instance_config；
--   5. 保留 md_env_config 作为只读备份，不删除。
--
-- 这是为了避免把「cameroon-prod 的 k8s 地址」和「ghana-prod 的 k8s 地址」
-- 合并成同一份数据。执行前请确认已停止旧后端。
-- =====================================================================================

SET SESSION group_concat_max_len = 1024 * 1024;

CREATE TABLE IF NOT EXISTS `md_env_instance_config`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `group_code`    VARCHAR(64)   NOT NULL COMMENT '所属分组',
    `instance_code` VARCHAR(64)   NOT NULL COMMENT '所属实例',
    `key_code`      VARCHAR(64)   NOT NULL COMMENT '键名',
    `config_value`  VARCHAR(4096)          DEFAULT NULL COMMENT '值。当前明文，见 env-schema.sql 文件头第四节',
    `updated_at_ms` BIGINT        NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `updated_by`    VARCHAR(64)            DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_instance_key` (`group_code`, `instance_code`, `key_code`),
    KEY `idx_group` (`group_code`),
    KEY `idx_instance` (`group_code`, `instance_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：实例配置值';


-- 绑定关系备份：迁移会改写 instance_code，保留一份原样数据用于回滚排查。
CREATE TABLE IF NOT EXISTS `md_env_instance_binding_backup_20261107` AS
SELECT * FROM `md_env_instance_binding`;


-- 每个环境在每个旧实例上的值指纹。只比较 key/value，不比较顺序。
DROP TEMPORARY TABLE IF EXISTS `tmp_env_instance_variants`;
CREATE TEMPORARY TABLE `tmp_env_instance_variants` AS
SELECT c.`group_code`,
       c.`instance_code`,
       c.`env_code`,
       MD5(GROUP_CONCAT(CONCAT(c.`key_code`, '=', IFNULL(c.`config_value`, ''))
                        ORDER BY c.`key_code` SEPARATOR '|')) AS `fingerprint`
FROM `md_env_config` c
GROUP BY c.`group_code`, c.`instance_code`, c.`env_code`;


-- 每个指纹选一个代表环境；variant_no = 1 继续使用原实例编码。
DROP TEMPORARY TABLE IF EXISTS `tmp_env_instance_targets`;
CREATE TEMPORARY TABLE `tmp_env_instance_targets` AS
SELECT x.`group_code`,
       x.`instance_code`,
       x.`fingerprint`,
       x.`rep_env`,
       ROW_NUMBER() OVER (
           PARTITION BY x.`group_code`, x.`instance_code`
           ORDER BY x.`min_sort`, x.`rep_env`
       ) AS `variant_no`
FROM (
    SELECT v.`group_code`,
           v.`instance_code`,
           v.`fingerprint`,
           MIN(IFNULL(e.`sort_order`, 2147483647)) AS `min_sort`,
           SUBSTRING_INDEX(
               GROUP_CONCAT(v.`env_code`
                            ORDER BY IFNULL(e.`sort_order`, 2147483647), v.`env_code`),
               ',', 1) AS `rep_env`
    FROM `tmp_env_instance_variants` v
    LEFT JOIN `md_environment` e ON BINARY e.`env_code` = BINARY v.`env_code`
    GROUP BY v.`group_code`, v.`instance_code`, v.`fingerprint`
) x;

ALTER TABLE `tmp_env_instance_targets` ADD COLUMN `target_instance` VARCHAR(64);

UPDATE `tmp_env_instance_targets`
SET `target_instance` = CASE
        WHEN `variant_no` = 1 THEN `instance_code`
        ELSE CONCAT(
            LEFT(`instance_code`, GREATEST(1, 64 - LEAST(CHAR_LENGTH(`rep_env`), 48) - 4)),
            '__', LEFT(`rep_env`, 48), '_', `variant_no`)
    END;


-- 环境 → 目标实例映射。绑定改写必须按 (env_code, group_code, 原实例) 找到目标实例，
-- 不能只用 (group_code, instance_code) 去关联，否则同一实例的多个环境会互相覆盖。
DROP TEMPORARY TABLE IF EXISTS `tmp_env_instance_map`;
CREATE TEMPORARY TABLE `tmp_env_instance_map` AS
SELECT v.`group_code`,
       v.`instance_code` AS `old_instance_code`,
       v.`env_code`,
       t.`target_instance`
FROM `tmp_env_instance_variants` v
JOIN `tmp_env_instance_targets` t
  ON BINARY t.`group_code` = BINARY v.`group_code`
 AND BINARY t.`instance_code` = BINARY v.`instance_code`
 AND BINARY t.`fingerprint` = BINARY v.`fingerprint`;


-- 为发生分歧的指纹克隆实例元数据。展示名带上代表环境，方便页面辨认。
INSERT IGNORE INTO `md_env_group_instance`
    (`group_code`, `instance_code`, `display_name`, `enabled`, `sort_order`, `created_at_ms`, `updated_at_ms`)
SELECT t.`group_code`,
       t.`target_instance`,
       LEFT(CONCAT(LEFT(i.`display_name`, 80), ' · ', LEFT(IFNULL(e.`display_name`, t.`rep_env`), 40)), 128),
       i.`enabled`,
       i.`sort_order` + t.`variant_no`,
       UNIX_TIMESTAMP() * 1000,
       UNIX_TIMESTAMP() * 1000
FROM `tmp_env_instance_targets` t
JOIN `md_env_group_instance` i
  ON BINARY i.`group_code` = BINARY t.`group_code`
 AND BINARY i.`instance_code` = BINARY t.`instance_code`
LEFT JOIN `md_environment` e ON BINARY e.`env_code` = BINARY t.`rep_env`
WHERE t.`variant_no` > 1;


-- 每个指纹复制一份代表环境的值到目标实例。
INSERT IGNORE INTO `md_env_instance_config`
    (`group_code`, `instance_code`, `key_code`, `config_value`, `updated_at_ms`, `updated_by`)
SELECT t.`group_code`,
       t.`target_instance`,
       c.`key_code`,
       c.`config_value`,
       UNIX_TIMESTAMP() * 1000,
       c.`updated_by`
FROM `tmp_env_instance_targets` t
JOIN `md_env_config` c
  ON BINARY c.`group_code` = BINARY t.`group_code`
 AND BINARY c.`instance_code` = BINARY t.`instance_code`
 AND BINARY c.`env_code` = BINARY t.`rep_env`;


-- 先把绑定改到目标实例，再删除旧的绑定行，避免复合主键冲突。
INSERT IGNORE INTO `md_env_instance_binding`
    (`env_code`, `group_code`, `instance_code`, `created_at_ms`, `updated_at_ms`)
SELECT m.`env_code`,
       m.`group_code`,
       m.`target_instance`,
       b.`created_at_ms`,
       b.`updated_at_ms`
FROM `md_env_instance_binding` b
JOIN `tmp_env_instance_map` m
  ON BINARY m.`group_code` = BINARY b.`group_code`
 AND BINARY m.`old_instance_code` = BINARY b.`instance_code`
 AND BINARY m.`env_code` = BINARY b.`env_code`
WHERE BINARY b.`instance_code` <> BINARY m.`target_instance`;

DELETE b
FROM `md_env_instance_binding` b
JOIN `tmp_env_instance_map` m
  ON BINARY m.`group_code` = BINARY b.`group_code`
 AND BINARY m.`old_instance_code` = BINARY b.`instance_code`
 AND BINARY m.`env_code` = BINARY b.`env_code`
WHERE BINARY b.`instance_code` <> BINARY m.`target_instance`;


-- 旧数据里可能存在“有值但没绑定”的环境；补上绑定，避免配置在页面上消失。
INSERT IGNORE INTO `md_env_instance_binding`
    (`env_code`, `group_code`, `instance_code`, `created_at_ms`, `updated_at_ms`)
SELECT m.`env_code`, m.`group_code`, m.`target_instance`,
       UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
FROM `tmp_env_instance_map` m;


DROP TEMPORARY TABLE IF EXISTS `tmp_env_instance_map`;
DROP TEMPORARY TABLE IF EXISTS `tmp_env_instance_targets`;
DROP TEMPORARY TABLE IF EXISTS `tmp_env_instance_variants`;


-- 校验：配置行是否都落在已声明的实例上（期望返回 0 行）。
-- SELECT c.group_code, c.instance_code
-- FROM md_env_instance_config c
-- LEFT JOIN md_env_group_instance i
--   ON i.group_code = c.group_code AND i.instance_code = c.instance_code
-- WHERE i.instance_code IS NULL;


-- 回滚（仅在尚未写入新实例配置时可用）：
--   1. 用 md_env_instance_binding_backup_20261107 恢复绑定；
--   2. 删除迁移克隆出来的实例（instance_code 含 '__' 且对应绑定已恢复）；
--   3. 删除 md_env_instance_config。
-- 注意：md_env_config 未被修改，可作为最终数据备份。
