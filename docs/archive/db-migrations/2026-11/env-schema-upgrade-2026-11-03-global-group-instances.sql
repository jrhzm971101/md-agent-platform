-- =====================================================================================
-- 存量环境目录升级：每环境一份实例元数据 → 分组级全局实例元数据
-- =====================================================================================
-- 背景：实例只是“分组下有几套连接信息”的元数据，不该在每个环境各存一份。
-- 升级后 md_env_group_instance 的主键从
--   (env_code, group_code, instance_code)
-- 收敛为
--   (group_code, instance_code)
-- 实际值仍然按环境隔离，留在 md_env_config（四元组不变）。
--
-- 前置条件：
--   1. 已停止旧后端（旧代码仍按 env_code 读写实例表）；
--   2. 已执行 env-schema-upgrade-2026-11-03-group-instances.sql。
-- 执行完成后启动支持全局实例的新后端。
--
-- 数据处理：同一个 (group_code, instance_code) 在多环境下的重复行会合并为一行：
--   display_name 取 MIN，enabled 取 MAX，sort_order 取 MIN，created_at_ms 取 MIN，
--   updated_at_ms 取 MAX。配置值不受影响。
--
-- 本脚本不是幂等的：重复执行会因为旧列不存在而失败。
-- =====================================================================================


CREATE TABLE `md_env_group_instance_global`
(
    `group_code`    VARCHAR(64)  NOT NULL COMMENT '所属分组',
    `instance_code` VARCHAR(64)  NOT NULL COMMENT '实例标识；default 为兼容旧调用的默认实例',
    `display_name`  VARCHAR(128) NOT NULL COMMENT '实例展示名',
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '同一分组内的实例顺序',
    `created_at_ms` BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms` BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    PRIMARY KEY (`group_code`, `instance_code`),
    KEY `idx_group` (`group_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：分组实例（全局元数据）';


INSERT INTO `md_env_group_instance_global`
    (`group_code`, `instance_code`, `display_name`, `enabled`, `sort_order`,
     `created_at_ms`, `updated_at_ms`)
SELECT `group_code`,
       `instance_code`,
       MIN(`display_name`),
       MAX(`enabled`),
       MIN(`sort_order`),
       MIN(`created_at_ms`),
       MAX(`updated_at_ms`)
FROM `md_env_group_instance`
GROUP BY `group_code`, `instance_code`;


DROP TABLE `md_env_group_instance`;
RENAME TABLE `md_env_group_instance_global` TO `md_env_group_instance`;


-- 回滚（仅在尚未写入新实例元数据时可用）：
--   CREATE TABLE md_env_group_instance_old (... env_code ...);
--   INSERT ... SELECT env_code FROM md_environment CROSS JOIN md_env_group_instance;
--   DROP TABLE md_env_group_instance;
--   RENAME TABLE md_env_group_instance_old TO md_env_group_instance;
