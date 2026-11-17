-- =====================================================================================
-- 环境目录（Environment Catalog）落库脚本
-- =====================================================================================
-- 六张表，职责单一，不做外键（沿用本项目既定风格：外键把删除顺序变成隐式约束，
-- 而这里的删除顺序由服务层显式表达，报错时才说得清"先删谁"）：
--
--   md_environment    环境主表         一个环境一行
--   md_env_group      分组模板         声明"一共存在哪些分组"
--   md_env_group_key  分组内的键声明   声明"这一组有哪些键、哪些必填、哪些是凭据"
--   md_env_group_instance  分组实例    声明某个分组下有哪些实例（全局元数据，环境共享）
--   md_env_instance_config  实例配置   某个分组实例 / 键的实际值（配置属于实例，与绑定环境无关）
--   md_env_instance_binding  环境实例绑定  某个环境使用了分组下的哪些实例
--
-- -------------------------------------------------------------------------------------
-- 一、为什么把「组件」抽象成「分组」
-- -------------------------------------------------------------------------------------
-- 改造之前，组件是 Java 里写死的 6 个内部类（Nacos / K8s / Rabbit / XxlJob / Magic /
-- OpenObserve），加一个组件要改代码、重编译、重启。而环境目录真正要装的东西，一半是
-- 「连接信息」，另一半是跟组件毫无关系的业务常量（结算周期、默认仓库、对接人……）。
-- 两者共用一套「读环境名 → 拿一组键值」的语义，唯一差别是分组名。
-- 所以这里统一成 group；k8s / rabbitmq 只是恰好被工具代码引用的分组而已（builtin=1）。
--
-- -------------------------------------------------------------------------------------
-- 二、为什么键值行存（EAV）而不是 components 一个 JSON 列
-- -------------------------------------------------------------------------------------
-- 页面要的三件事都要求"按 key 查"：
--   1. 完整度（几个必填键填了 / 一共几个必填键）—— 要按 required 聚合；
--   2. 每格的「已配置 / Token 空 / 未配置」—— 要按 key 判断有没有值；
--   3. 筛选"缺哪个键的环境"。
-- 塞 JSON 列的话这三件事都变成全量读取 + 应用层解析，而且 MySQL 的 JSON 路径查询
-- 没法用普通索引。行存的代价是行数变多（9 个环境 × 6 组 × 3~4 键 ≈ 200 行），
-- 这个量级完全不构成问题。
--
-- -------------------------------------------------------------------------------------
-- 三、key_code 为什么与 POJO 的字段名逐字一致
-- -------------------------------------------------------------------------------------
-- 环境值只从数据库读取，但强类型投影仍靠 key_code 与 POJO 字段名同名。
-- 字段名是 serverAddr / authorization / consoleUrl / baseUrl / organization 这种驼峰。
-- 让 key_code 与它逐字相同，换来两个好处：
--   1. 回投影零映射（config 行 compose 成 Map 后 Jackson 直接喂进
--      EnvironmentProperties.K8s 这类 POJO，字段名自动对上）；
--   2. 报错信息、日志、页面字段名保持一致，不做"脑内翻译"。
--
-- -------------------------------------------------------------------------------------
-- 四、为什么没有加密列（这是明确的技术债，不是遗漏）
-- -------------------------------------------------------------------------------------
-- 2026-09-23 明确决定：调试期先明文，把「能不能维护」这条链路先跑通。
-- 因此 config_value 现在是 VARCHAR 明文。将来要加密时改这一个列即可
--（加 *_cipher / *_iv，或整体换成密文串 + 服务层加解密），
-- 不需要改表结构以外的任何东西 —— 读写都收在 EnvironmentStore 里。
-- 现在必须知情的是：下面这几个键目前是明文，包括 k8s token、nacos/xxljob 口令、
-- rabbitmq 的 Basic 鉴权串、openobserve 口令。
--
-- -------------------------------------------------------------------------------------
-- 五、为什么没有 parent_env_code（继承），以及分组实例怎么表达
-- -------------------------------------------------------------------------------------
-- 2026-09-23 明确决定：不做环境继承。2026-11-03 补充：一个分组下可以定义多个实例
-- （例如两套 NineData、两套 Nacos），实例元数据是分组级模板，所有环境共享。
-- 2026-11-07 进一步收敛：配置值属于实例本身，唯一键为
-- (group_code, instance_code, key_code)，不再带 env_code。
-- 环境只通过 md_env_instance_binding 选择自己使用哪些实例；需要不同配置时新建实例。
-- 旧调用不感知实例：没有显式指定时优先读 default；没有 default 且只有一个实例时
-- 自动使用它；有多个实例却没有 default 时必须显式指定，否则报错并列出可用实例。
--
-- 时间为什么用 BIGINT（epoch millis）：与本项目其余表一致，不引入时区换算。
-- =====================================================================================


CREATE TABLE IF NOT EXISTS `md_environment`
(
    `env_code`      VARCHAR(64)  NOT NULL COMMENT '环境标识。工具 environment 参数填它，也是 agent 工作区引用的值',
    `display_name`  VARCHAR(128) NOT NULL COMMENT '展示名。页面上唯一该露给人看的名字，理由同 md_model.display_name',
    `description`   VARCHAR(512)          DEFAULT NULL COMMENT '备注',
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '停用后不进工具的可选环境清单；已有配置保留',
    `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '页面排序（同值按 env_code 兜底排序）',
    `created_at_ms` BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms` BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `created_by`    VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    `updated_by`    VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`env_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：环境主表';


CREATE TABLE IF NOT EXISTS `md_env_group`
(
    `group_code`    VARCHAR(64)  NOT NULL COMMENT '分组标识，键值路径的第一段，如 k8s / rabbitmq / biz',
    `display_name`  VARCHAR(128) NOT NULL COMMENT '展示名（表格列头用它）',
    `icon`          VARCHAR(64)           DEFAULT NULL COMMENT '前端图标名；空 = 不画图标',
    `description`   VARCHAR(512)          DEFAULT NULL COMMENT '备注（页面鼠标悬停提示）',
    `builtin`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = 被工具代码强引用（k8s/rabbitmq/…），页面可改可加键，但删除会被拒绝',
    `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '表格列顺序',
    `created_at_ms` BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms` BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    PRIMARY KEY (`group_code`),
    KEY `idx_sort` (`sort_order`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：分组模板';


CREATE TABLE IF NOT EXISTS `md_env_group_key`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键。键声明没有天然稳定业务键（键可以删了再加），不虚构一个',
    `group_code`    VARCHAR(64)  NOT NULL COMMENT '所属分组',
    `key_code`      VARCHAR(64)  NOT NULL COMMENT '键名。与 yml / 强类型 POJO 字段名逐字一致，见文件头第三节',
    `label`         VARCHAR(128) NOT NULL COMMENT '表单上的字段名（中文）',
    `value_type`    VARCHAR(16)  NOT NULL DEFAULT 'STRING' COMMENT 'STRING | INT | BOOL；决定表单控件与保存时的格式校验',
    `required`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = 缺失计入完整度缺口（注意：不阻断保存，见 EnvironmentAdminService）',
    `secret`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = 凭据。列表视图只显示"有无"，不进关键字检索',
    `placeholder`   VARCHAR(255)          DEFAULT NULL COMMENT '表单占位提示',
    `example_value` VARCHAR(255)          DEFAULT NULL COMMENT '示例值（只展示，绝不落成默认值）',
    `description`   VARCHAR(512)          DEFAULT NULL COMMENT '字段说明',
    `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '表单内顺序',
    `created_at_ms` BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms` BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_key` (`group_code`, `key_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：分组内的键声明';


CREATE TABLE IF NOT EXISTS `md_env_group_instance`
(
    `group_code`    VARCHAR(64)  NOT NULL COMMENT '所属分组',
    `instance_code` VARCHAR(64)  NOT NULL COMMENT '实例标识，如同一环境下的 ninedata-main / ninedata-report；default 为兼容旧调用的默认实例',
    `display_name`  VARCHAR(128) NOT NULL COMMENT '实例展示名，如 主库 NineData / 报表 NineData',
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '停用后仍保留配置，但页面上弱化展示；工具解析仍以显式选择为准',
    `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '同一分组内的实例顺序',
    `created_at_ms` BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms` BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    PRIMARY KEY (`group_code`, `instance_code`),
    KEY `idx_group` (`group_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：分组实例';


CREATE TABLE IF NOT EXISTS `md_env_instance_config`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `group_code`    VARCHAR(64)   NOT NULL COMMENT '所属分组',
    `instance_code` VARCHAR(64)   NOT NULL DEFAULT 'default' COMMENT '所属实例；存量单实例数据统一为 default',
    `key_code`      VARCHAR(64)   NOT NULL COMMENT '键名（必须与 md_env_group_key 里已声明的键同名）',
    `config_value`  VARCHAR(4096)          DEFAULT NULL COMMENT '值。当前明文，见文件头第四节。空串按"未配置"处理，保存时直接删行',
    `updated_at_ms` BIGINT        NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `updated_by`    VARCHAR(64)            DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_instance_key` (`group_code`, `instance_code`, `key_code`),
    KEY `idx_group` (`group_code`),
    KEY `idx_instance` (`group_code`, `instance_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='环境目录：实例配置值';


-- 环境与实例的绑定关系。实例元数据是分组级全局数据，这里只记录“这个环境使用了哪些实例”。
-- 解绑只删这一行，不删除实例定义，也不删除实例自带的配置值。
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
