-- =====================================================================================
-- 环境实例绑定：让“从环境解绑”只影响当前环境
-- =====================================================================================
-- 背景：md_env_group_instance 是分组级全局实例元数据，md_env_config 只存值。
-- 之前详情接口按全局实例清单展开，导致任何实例都会出现在所有环境里，无法按环境解绑。
--
-- 本脚本新增 md_env_instance_binding，并把存量环境回填为“绑定全部当前全局实例”，
-- 保持升级前的页面表现。之后保存环境时按提交的实例集合整体替换绑定关系；
-- 解绑只会删除该环境的值与绑定，不会删除实例定义。
--
-- 执行顺序：先执行本脚本，再启动支持绑定关系的新后端。
-- 本脚本可重复执行（CREATE IF NOT EXISTS / INSERT IGNORE）。
-- =====================================================================================

CREATE TABLE IF NOT EXISTS `md_env_instance_binding`
(
    `env_code`      VARCHAR(64) NOT NULL COMMENT '所属环境',
    `group_code`    VARCHAR(64) NOT NULL COMMENT '所属分组',
    `instance_code` VARCHAR(64) NOT NULL COMMENT '绑定的实例',
    `created_at_ms` BIGINT      NOT NULL COMMENT '绑定时刻（epoch millis）',
    `updated_at_ms` BIGINT      NOT NULL COMMENT '最后修改时刻（epoch millis）',
    PRIMARY KEY (`env_code`, `group_code`, `instance_code`),
    KEY `idx_env` (`env_code`),
    KEY `idx_group` (`group_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：环境与分组实例的绑定';


INSERT IGNORE INTO `md_env_instance_binding`
    (`env_code`, `group_code`, `instance_code`, `created_at_ms`, `updated_at_ms`)
SELECT e.`env_code`,
       i.`group_code`,
       i.`instance_code`,
       UNIX_TIMESTAMP() * 1000,
       UNIX_TIMESTAMP() * 1000
FROM `md_environment` e
CROSS JOIN `md_env_group_instance` i;


-- 回滚（会丢失按环境解绑的信息，恢复成“所有环境都绑定所有实例”）：
--   DROP TABLE `md_env_instance_binding`;
