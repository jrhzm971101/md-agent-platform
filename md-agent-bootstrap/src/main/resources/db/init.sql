-- =====================================================================================
-- Md-Agent-Platform 数据库初始化脚本（当前完整基线）
-- =====================================================================================
-- 适用：全新环境初始化。脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
--
-- 使用方式：
--   1) 创建业务库（库名可自定义，需与 application.yml 的 MYSQL_URL 一致）：
--        CREATE DATABASE IF NOT EXISTS `md-agent-platform`
--          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--   2) 执行本脚本：
--        mysql -h <host> -P <port> -u <user> -p md-agent-platform < init.sql
--
-- 包含 26 张业务表：
--   控制面 11 张  md_agent / md_agent_ref / md_agent_runtime / md_agent_memory /
--                 md_agent_workspace / md_agent_sub_agent / md_prompt / md_mcp_server /
--                 md_skill_meta / md_resource_version / md_model
--   渠道    3 张  md_channel / md_channel_binding / md_gateway
--   链路    2 张  md_agent_run / md_agent_run_event
--   工作流  4 张  md_workflow / md_workflow_node_ref / md_workflow_run / md_workflow_run_node
--   环境    6 张  md_environment / md_env_group / md_env_group_key /
--                 md_env_group_instance / md_env_instance_config / md_env_instance_binding
--
-- 由框架自动建表的库（本脚本末尾只预创建库）：
--   agentscope          官方 MysqlSkillRepository 的技能内容表
--   md_agent_state      AgentScope MysqlAgentStateStore 的会话状态表
--   md_agent_workspace  工作区 KV（仅 fs_backend=REMOTE 时使用）
--   库名需与 application.yml 的 control-plane-skill-database / state-store-database /
--   workspace-kv-database 保持一致。
--
-- 历史增量脚本已归档到 docs/archive/db-migrations/，只用于既有库升级；
-- 全新库执行本文件即可，不要再执行历史增量脚本。
--
-- 可选种子数据见同目录 seed.example.sql。
-- =====================================================================================

SET NAMES utf8mb4;

-- #####################################################################################
-- 01. 控制面元数据（Agent / Prompt / Skill / MCP / Model）
-- #####################################################################################

-- =====================================================================================
-- 控制面元数据落库脚本 —— Agent / Prompt / Skill / MCP 从 Nacos 迁入 MySQL
-- =====================================================================================
-- 设计文档：docs/develop/2026-09-21_control-plane-metadata.md（先读它再看本脚本）
--
-- 共 11 张表（另 2 张 skill 内容表由官方 MysqlSkillRepository 在第一次构造时自动创建，
-- 不在本脚本里，见下面的「skill 为什么只有治理表」）：
--
--   md_agent               agent 身份 + 生命周期（草稿/发布/版本号）
--   md_agent_ref           agent 的「引用」：prompt-key / tools / middleware / skills / mcp / knowledges
--   md_agent_runtime       通用运行时旋钮 + 模型
--   md_agent_memory        上下文压缩 + 记忆 + 工具结果驱逐
--   md_agent_workspace     Harness 工作区 + 能力开关
--   md_agent_sub_agent     子 agent 声明（一对多）
--   md_prompt              prompt 正文
--   md_mcp_server          MCP 服务端
--   md_skill_meta          skill 的治理元数据（内容不在这里）
--   md_model               模型连接与生成参数（取代 application.yml 里那个全局共享的模型 Bean）
--   md_resource_version    四类资源的版本快照，支撑回滚
--
-- 三条容易踩的设计约定，改脚本前务必先读：
--
--   1）JSON 列必须配 @TableName(autoResultMap = true) + JacksonTypeHandler
--      漏了 autoResultMap 的表现是「能写进去、读出来是 null」——写路径不报错，只有读才暴露。
--      本项目已在 trace 落库（MyBatisTraceStore / AgentRunEntity）踩过一次，沿用同样写法。
--
--   2）一批 TINYINT(1) 开关是「三态」，不是「布尔」
--      NULL = 未设置（用框架默认值）；0 = 显式关闭；1 = 显式开启。
--      页面必须能区分「未设置」和「关闭」：把框架默认值写死进库，会让将来框架升级默认值时
--      本平台的行为与框架脱节，且没有任何提示。
--
--   3）不做外键，也不做级联删除（沿用本项目 trace-schema.sql 的既定风格）
--      外键会把「删除顺序」变成隐式约束，而控制面需要给出明确报错：
--      「prompt foo 正被 agent A、B 引用，无法删除」——这条信息只能由应用层查出来，
--      外键只能抛一个 SQL 异常。引用完整性由 ControlPlaneStore 在事务内校验。
--
-- 时间为什么用 BIGINT（epoch millis）而不是 DATETIME：与 trace 表保持一致。领域模型里就是
-- epoch millis，直存直取不引入时区换算；JDBC URL 上的 serverTimezone 一旦被改动，
-- DATETIME 列的历史数据会整体漂移，而 BIGINT 不受影响。
--
-- 执行方式：见文件头；本文件已合并全部基线表。
-- =====================================================================================

-- ---------- 1. agent 主表：身份 + 生命周期 ----------
-- 只放「我是谁」和「处于什么状态」，不放任何配置项 —— 配置按语义域拆在后面 5 张表里。
-- agent_type 取代了旧 AgentDefinition 里那个需要页面推导的 kind 冗余列。
CREATE TABLE IF NOT EXISTS `md_agent`
(
    `agent_name`        VARCHAR(128) NOT NULL COMMENT 'Agent 唯一名（运行时注册键，也是页面主键）',
    `display_name`      VARCHAR(255) NOT NULL COMMENT '展示名（页面标题用它）。与 agent_name 分开的理由：agent_name 兼任运行时注册键 / 路由参数 / 装配名，改一次等于新建，展示口径不能挂在这种列上。本列不参与装配、不参与唯一键、不可被引用',
    `agent_type`        VARCHAR(16)  NOT NULL DEFAULT 'REACT' COMMENT 'REACT | HARNESS，装配时决定走哪条 builder 链',
    `description`       VARCHAR(1024)         DEFAULT NULL COMMENT '描述，装配时进 builder.description()',
    `biz_tags`          JSON                  DEFAULT NULL COMMENT '业务标签，仅控制面用（分组/筛选），不进装配',
    `category`          VARCHAR(64)           DEFAULT NULL COMMENT '能力分组（页面筛选用）',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
    `version`           INT          NOT NULL DEFAULT 1 COMMENT '内容版本号，每次保存 +1（从 1 开始）',
    `published_version` INT                   DEFAULT NULL COMMENT '已发布版本号；NULL = 从未发布',
    `published_at_ms`   BIGINT                DEFAULT NULL COMMENT '最近一次发布时间',
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否参与装配（停用但不删除）',
    `created_at_ms`     BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms`     BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `created_by`        VARCHAR(64)           DEFAULT NULL COMMENT '创建人（控制面无鉴权阶段先由前端传，暂不校验）',
    `updated_by`        VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`agent_name`),
    KEY `idx_status` (`status`),
    KEY `idx_updated` (`updated_at_ms`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：Agent 主表（身份与生命周期）';

-- ---------- 2. agent 引用表：页面上的这些字段全部是下拉多选，禁止自由输入 ----------
-- 拆分理由：引用会随页面交互频繁变动（勾选/取消），而运行时旋钮很少动。混在一张表里，
-- 每次勾选都会 UPDATE 整行，把配置一起写一遍，出问题不好定位是哪一侧改坏的。
-- 另外「谁引用了 prompt X」这个反查（删除保护）只需要扫这张表，代价很低。
CREATE TABLE IF NOT EXISTS `md_agent_ref`
(
    `agent_name`  VARCHAR(128) NOT NULL COMMENT '所属 agent',
    `prompt_key`  VARCHAR(128)          DEFAULT NULL COMMENT '引用 md_prompt.prompt_key；非空则其正文覆盖 sysPrompt',
    `tools`       JSON                  DEFAULT NULL COMMENT 'Spring 工具 bean 名列表（来源：tool 只读目录），精确声明',
    `middleware`  JSON                  DEFAULT NULL COMMENT '中间件 bean 名列表（来源：middleware 只读目录）',
    `skill_names` JSON                  DEFAULT NULL COMMENT '引用 md_skill_meta.skill_name 列表',
    `mcp_names`   JSON                  DEFAULT NULL COMMENT '引用 md_mcp_server.server_name 列表（顺序即装配顺序）',
    `knowledges`  JSON                  DEFAULT NULL COMMENT 'RAG Knowledge bean 名列表',
    PRIMARY KEY (`agent_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：Agent 引用（这个 agent 用了谁）';

-- ---------- 3. agent 运行时表：通用旋钮 + 模型 ----------
-- 覆盖 ReActAgent.Builder 与 HarnessAgent.Builder 的「共有部分」。
-- 包装类型（Integer/Boolean）对应 DB 的 NULL：NULL 一律表示"不下发这个 setter，用框架默认"，
-- 装配层必须判空后再拆箱 —— 框架侧这些 setter 收的是原始类型，传 null 会 NPE。
CREATE TABLE IF NOT EXISTS `md_agent_runtime`
(
    `agent_name`                   VARCHAR(128) NOT NULL COMMENT '所属 agent',
    -- 模型
    `model_ref`                    VARCHAR(128)          DEFAULT NULL COMMENT '主模型 bean 名，默认 openAIChatModel',
    `fallback_model_ref`           VARCHAR(128)          DEFAULT NULL COMMENT 'fallback 模型 bean 名；空则不套用任何默认模型',
    `reasoning_effort`             VARCHAR(32)           DEFAULT NULL COMMENT 'low | medium | high（模型侧推理强度）',
    -- 迭代与重试
    `max_iters`                    INT                   DEFAULT NULL COMMENT '最大迭代轮次',
    `max_retries`                  INT                   DEFAULT NULL COMMENT '模型调用重试次数',
    `max_context_tokens`           INT                   DEFAULT NULL COMMENT 'HARNESS 最大上下文 token；NULL 表示框架默认',
    -- 行为开关（三态，见文件头第 2 条）
    `check_running`                TINYINT(1)            DEFAULT NULL COMMENT '运行前是否检查 agent 状态',
    `stop_on_reject`               TINYINT(1)            DEFAULT NULL COMMENT '模型拒绝调用时是否停止',
    `enable_meta_tool`             TINYINT(1)            DEFAULT NULL COMMENT '是否启用 meta tool',
    `enable_task_list`             TINYINT(1)            DEFAULT NULL COMMENT '是否开启任务列表',
    `enable_pending_tool_recovery` TINYINT(1)            DEFAULT NULL COMMENT '是否启用未完成工具调用恢复',
    -- 状态与权限
    `conflict_policy`              VARCHAR(32)           DEFAULT NULL COMMENT 'REACT：OVERWRITE | FAIL | APPEND_MERGE',
    -- REACT 专有的技能策略（2026-09-22 从 md_agent_workspace 迁来）：
    -- 三项都出自 ReActAgent.Builder；HarnessAgent.Builder 上无对应方法，
    -- 框架甚至在 HarnessAgent 内部主动 dynamicSkillsEnabled(false) 抑制动态技能。
    -- 放这里不是"就近"，而是为了让 md_agent_workspace 保持纯 HARNESS，能整表归属一个页面。
    `dynamic_skills_enabled`       TINYINT(1)            DEFAULT NULL COMMENT 'REACT：是否允许动态加载技能',
    `skill_code_execution_enabled` TINYINT(1)            DEFAULT NULL COMMENT 'REACT：是否允许技能代码执行',
    `skill_work_dir`               VARCHAR(255)          DEFAULT NULL COMMENT 'REACT：技能执行工作目录（相对 workspace-root）',
    `state_store`                  VARCHAR(255)          DEFAULT NULL COMMENT '状态存储开关；非空 = 启用 MysqlAgentStateStore（建表在 agent-platform.state-store-database）',
    `state_table`                  VARCHAR(128)          DEFAULT NULL COMMENT '状态表名（创建时冻结，改名不改它）；空则回落 agent_name',
    `default_session_id`           VARCHAR(128)          DEFAULT NULL COMMENT '默认会话 ID',
    `long_term_memory_mode`        VARCHAR(32)           DEFAULT NULL COMMENT 'AGENT_CONTROL | STATIC_CONTROL | BOTH',
    `long_term_memory_async`       TINYINT(1)            DEFAULT NULL COMMENT '长期记忆是否异步写入',
    `rag_mode`                     VARCHAR(32)           DEFAULT NULL COMMENT 'RAG 模式',
    -- 结构化配置：字段少、没有按子字段查询的需求，用 JSON 列而不是再开 5 张子表
    -- （拆表只会把装配期变成 5 次额外 join，并不带来任何查询能力）
    `generate_options`             JSON                  DEFAULT NULL COMMENT 'model.GenerateOptions（temperature/topP 等）',
    `model_execution_config`       JSON                  DEFAULT NULL COMMENT 'model.ExecutionConfig（模型侧并发/超时）',
    `tool_execution_config`        JSON                  DEFAULT NULL COMMENT 'model.ExecutionConfig（工具侧并发/超时）',
    `retrieve_config`              JSON                  DEFAULT NULL COMMENT 'rag.model.RetrieveConfig',
    `permission`                   JSON                  DEFAULT NULL COMMENT 'permission.PermissionContextState / PermissionMode',
    PRIMARY KEY (`agent_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：Agent 运行时旋钮（两链共有调优 + REACT 专有项）';
-- ---------- 4. agent 记忆表：上下文压缩 + 记忆 + 工具结果驱逐 ----------
-- 为什么这三块放一张表：它们回答的是同一个问题——「上下文随轮次变长时，怎么收敛」。
-- 调参时这三者相互影响（压缩阈值调大 → 工具结果更容易撑爆上下文 → 驱逐阈值要跟着调），
-- 放一起才能一眼对照；拆成三张表会让"看一眼当前配置"变成三次查询。
-- 对应框架侧：CompactionConfig / MemoryConfig / ToolResultEvictionConfig 三个对象。
CREATE TABLE IF NOT EXISTS `md_agent_memory`
(
    `agent_name`                VARCHAR(128) NOT NULL COMMENT '所属 agent',
    -- ===== 上下文压缩 CompactionConfig =====
    -- 注意 compaction_disabled 与下面一组的优先级：disabled=1 时整组字段忽略（框架侧是
    -- disableCompaction()，它会清掉已配置的 CompactionConfig，两者同时存在时前者胜出）。
    `compaction_disabled`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '对应 disableCompaction()；1 = 完全关闭压缩，本组其余字段忽略',
    `trigger_messages`          INT                   DEFAULT NULL COMMENT '累积多少条消息触发压缩',
    `trigger_tokens`            INT                   DEFAULT NULL COMMENT '累积多少 token 触发压缩',
    `reserved`                  INT                   DEFAULT NULL COMMENT '压缩后预留 token',
    `keep_messages`             INT                   DEFAULT NULL COMMENT '压缩时保留最近多少条消息',
    `keep_tokens`               INT                   DEFAULT NULL COMMENT '压缩时保留多少 token',
    `keep_tokens_min`           INT                   DEFAULT NULL COMMENT '保留 token 下限',
    `keep_tokens_max`           INT                   DEFAULT NULL COMMENT '保留 token 上限',
    `keep_tokens_ratio`         DECIMAL(6, 4)         DEFAULT NULL COMMENT '保留比例（与 min/max 配合，三者共同决定实际保留量）',
    `summary_prompt`            MEDIUMTEXT            DEFAULT NULL COMMENT '摘要提示词；空则用框架默认 CompactionConfig.DEFAULT_SUMMARY_PROMPT',
    `flush_before_compact`      TINYINT(1)            DEFAULT NULL COMMENT '压缩前先落盘记忆',
    `offload_before_compact`    TINYINT(1)            DEFAULT NULL COMMENT '压缩前先转存',
    `compaction_model_ref`      VARCHAR(128)          DEFAULT NULL COMMENT '压缩用模型 bean 名；空则用主模型',
    `truncate_args`             JSON                  DEFAULT NULL COMMENT 'TruncateArgsConfig：triggerMessages/triggerTokens/keepMessages/keepTokens/maxArgLength/truncationText',
    `prune`                     JSON                  DEFAULT NULL COMMENT 'PruneConfig：protectTokens/minimumTokens/maxOutputChars/excludedTools',
    -- ===== 记忆 MemoryConfig =====
    `memory_model_ref`          VARCHAR(128)          DEFAULT NULL COMMENT '记忆整理用模型 bean 名；空则用主模型',
    `flush_prompt`              MEDIUMTEXT            DEFAULT NULL COMMENT '记忆落盘提示词',
    `consolidation_prompt`      MEDIUMTEXT            DEFAULT NULL COMMENT '记忆归并提示词',
    `consolidation_max_tokens`  INT                   DEFAULT NULL COMMENT '归并输出 token 上限',
    `consolidation_min_gap_ms`  BIGINT                DEFAULT NULL COMMENT '两次归并的最小间隔（毫秒，对应框架侧 Duration）',
    `daily_file_retention_days` INT                   DEFAULT NULL COMMENT '日志文件保留天数',
    `session_retention_days`    INT                   DEFAULT NULL COMMENT '会话保留天数',
    `flush_trigger`             JSON                  DEFAULT NULL COMMENT 'FlushTrigger：{mode: ALWAYS|NEVER|THROTTLED, minGapMs}',
    -- ===== 工具结果驱逐 ToolResultEvictionConfig =====
    `evict_disabled`            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '对应 disableToolResultEviction()',
    `evict_max_result_chars`    INT                   DEFAULT NULL COMMENT '单条工具结果超过多少字符转存到磁盘',
    `evict_preview_chars`       INT                   DEFAULT NULL COMMENT '转存后留在上下文里的预览长度',
    `evict_path`                VARCHAR(255)          DEFAULT NULL COMMENT '转存目录',
    `evict_excluded_tools`      JSON                  DEFAULT NULL COMMENT '不参与驱逐的工具名',
    PRIMARY KEY (`agent_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：Agent 上下文压缩 / 记忆 / 工具结果驱逐';

-- ---------- 5. agent 工作区表：Harness 专属 + 能力开关 ----------
-- 这一张表混合了两类东西，是有意为之：
--   (a) 工作区路径类：框架要求必须是本地真实路径（harness 要在里面读写 AGENTS.md / 计划文件），
--       DB 只存"路径字符串"，文件本体仍在磁盘上 —— 这是设计文档 §3.3 说明的边界；
--   (b) disable_* 能力开关类：纯配置，与路径无关，但同属"harness 运行形态"这一个语义域。
-- 本表自 2026-09-22 起是纯 HARNESS 表：REACT 专有的三项技能策略（动态技能 / 技能代码执行 /
-- 技能工作目录）已迁到 md_agent_runtime。迁移原因见那张表里的注释 —— 一句话是
-- "留在本表，本表就无法整表归属于 Harness 工作台页"。
CREATE TABLE IF NOT EXISTS `md_agent_workspace`
(
    `agent_name`                       VARCHAR(128) NOT NULL COMMENT '所属 agent',
    -- (a) 工作区与路径
    `harness_agent_id`                 VARCHAR(128)          DEFAULT NULL COMMENT 'HARNESS Agent 唯一 ID',
    `workspace_path`                   VARCHAR(255)          DEFAULT NULL COMMENT '相对 workspace-root；空则默认 harness/{agentName}',
    `fs_backend`                       VARCHAR(16)           DEFAULT NULL COMMENT '工作区文件后端：LOCAL(默认，本地磁盘+shell) | REMOTE(进 md_agent_workspace KV，无 shell)',
    `environment`                      VARCHAR(64)           DEFAULT NULL COMMENT '运行环境标识（区域/环境名，参与工作区上下文渲染）',
    `environment_memory`               VARCHAR(255)          DEFAULT NULL COMMENT '环境记忆目录（相对 workspace-root）',
    `project_global_skills_dir`        VARCHAR(255)          DEFAULT NULL COMMENT '项目全局技能目录；技能正常走 DB 仓库，此项仅作目录兜底',
    `plan_file_directory`              VARCHAR(255)          DEFAULT NULL COMMENT '计划文件目录',
    `additional_context_files`         JSON                  DEFAULT NULL COMMENT '追加的上下文文件列表',
    `filesystem`                       JSON                  DEFAULT NULL COMMENT 'filesystemSpec（local/sandbox/remote）+ filesystemRoute',
    `use_legacy_xml_workspace_context` TINYINT(1)            DEFAULT NULL COMMENT '是否用遗留 XML 工作区上下文',
    `agent_tracing_log_enabled`        TINYINT(1)            DEFAULT NULL COMMENT '是否开启 agent 链路日志',
    -- 计划模式
    `enable_plan_mode`                 TINYINT(1)            DEFAULT NULL COMMENT '是否开启计划模式',
    `plan_mode_allow_shell`            TINYINT(1)            DEFAULT NULL COMMENT '计划模式下是否允许 shell',
    -- 技能策略（全为 HARNESS 专有；REACT 的三项在 md_agent_runtime）
    `skills_enabled`                   TINYINT(1)            DEFAULT NULL COMMENT '技能总开关',
    `enable_skills`                    JSON                  DEFAULT NULL COMMENT '仅启用这些技能（按名，白名单）',
    `disable_skills`                   JSON                  DEFAULT NULL COMMENT '禁用这些技能（按名，黑名单）',
    `skill_manage_tool_enabled`        TINYINT(1)            DEFAULT NULL COMMENT '是否启用技能管理工具',
    `skill_manage_config`              JSON                  DEFAULT NULL COMMENT 'SkillManageConfig',
    -- (b) 能力开关：对应 builder 上一批 disableXxx()，三态语义
    `disable_filesystem_tools`         TINYINT(1)            DEFAULT NULL COMMENT '关闭文件系统工具',
    `disable_shell_tool`               TINYINT(1)            DEFAULT NULL COMMENT '关闭 shell 工具',
    `disable_memory_tools`             TINYINT(1)            DEFAULT NULL COMMENT '关闭记忆工具',
    `disable_memory_hooks`             TINYINT(1)            DEFAULT NULL COMMENT '关闭记忆钩子',
    `disable_transcript`               TINYINT(1)            DEFAULT NULL COMMENT '关闭过程记录',
    `disable_session_persistence`      TINYINT(1)            DEFAULT NULL COMMENT '关闭会话持久化',
    `disable_workspace_context`        TINYINT(1)            DEFAULT NULL COMMENT '关闭工作区上下文注入',
    `disable_at_path_expansion`        TINYINT(1)            DEFAULT NULL COMMENT '关闭 @路径 展开',
    `disable_subagents`                TINYINT(1)            DEFAULT NULL COMMENT '关闭子 agent 能力',
    `disable_dynamic_subagents`        TINYINT(1)            DEFAULT NULL COMMENT '关闭动态子 agent',
    `disable_dynamic_skills`           TINYINT(1)            DEFAULT NULL COMMENT '关闭动态技能',
    `disable_default_workspace_skills` TINYINT(1)            DEFAULT NULL COMMENT '关闭工作区默认技能扫描',
    `disable_tools_config`             TINYINT(1)            DEFAULT NULL COMMENT '关闭 ToolsConfig（allow/deny/mcpServers）装配',
    -- (c) 子 agent 执行形态（2026-09-28 新增）
    -- 这两列解决的是「子 agent 到底是同步等回来还是丢到后台跑」这件事由谁说了算。
    -- 框架的默认是「由模型决定」：agent_spawn 的 timeout_seconds 是模型自己填的参数，
    -- 0=后台、>0=同步、超时还会自动 promote 成后台任务。模型心情不同，同一段编排每次跑的形态就不同 ——
    -- 对于"必须拿到结果才能继续"的编排，这种不确定性是致命的。
    -- 置 subagent_force_sync=1 之后，应用侧（AgentManager.stream）会在 RuntimeContext 上写
    -- CTX_FORCE_SYNC，框架侧优先级高于模型参数：timeout_seconds=0 被强制改写成同步等待，
    -- 且**不再**把超时的调用 promote 成后台任务，而是中断子 agent 并返回 status: timeout。
    `subagent_force_sync`              TINYINT(1)            DEFAULT NULL COMMENT '子 agent 是否强制同步执行；NULL=用框架默认（由模型决定），1=强制同步',
    `subagent_sync_timeout_seconds`    INT                   DEFAULT NULL COMMENT '强制同步时的等待秒数；NULL=框架默认 30，>600 由框架夹到 600。仅在 force_sync=1 时有意义',
    PRIMARY KEY (`agent_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：Agent 工作区与能力开关（HARNESS 专有）';

-- ---------- 6. agent 子表：子 agent 声明（一对多） ----------
-- 为什么这张表逐字段建列而不是塞 JSON：子 agent 在页面上是一个「可增删的表格」，
-- 每行是一个独立表单，需要按列做校验（比如 maxIters 必须是正整数）、按行排序、按 sub_name 去重。
-- 塞成 JSON 列会把这三件事全推给应用层，且数据库无法约束 (agent_name, sub_name) 唯一。
-- remote/url/headers/remote_* 这组是给远程 A2A 子 agent 用的，本地子 agent 留空即可。
CREATE TABLE IF NOT EXISTS `md_agent_sub_agent`
(
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（无业务含义）',
    `agent_name`                 VARCHAR(128) NOT NULL COMMENT '父 agent（HARNESS）',
    `sub_name`                   VARCHAR(128) NOT NULL COMMENT '子 agent 名；本地子 agent = 另一个 md_agent.agent_name',
    `sort_order`                 INT          NOT NULL DEFAULT 0 COMMENT '路由展示顺序（暴露给模型的路由描述按此排序）',
    `sub_description`            VARCHAR(1024)         DEFAULT NULL COMMENT '派发职责说明，写进模型看到的路由描述；不填则回落到子 agent 自己的 description',
    `workspace_mode`             VARCHAR(16)           DEFAULT NULL COMMENT 'ISOLATED | SHARED（WORKSPACE_MODE）',
    `workspace_path`             VARCHAR(255)          DEFAULT NULL COMMENT '子 agent 工作区路径',
    `inline_agents_body`         MEDIUMTEXT            DEFAULT NULL COMMENT '内联 AGENTS.md 正文；空则读子 agent 自己定义里的提示词',
    `model_ref`                  VARCHAR(128)          DEFAULT NULL COMMENT '覆盖模型 bean 名；空则继承父/框架默认',
    `max_iters`                  INT                   DEFAULT NULL COMMENT '覆盖最大迭代轮次',
    `steps`                      INT                   DEFAULT NULL COMMENT '步数配置',
    `temperature`                DECIMAL(4, 2)         DEFAULT NULL COMMENT '覆盖采样温度',
    `top_p`                      DECIMAL(4, 2)         DEFAULT NULL COMMENT '覆盖 top_p',
    `variant`                    VARCHAR(64)           DEFAULT NULL COMMENT '变体标识（同一子 agent 的不同提示词/行为变体）',
    `mode`                       VARCHAR(16)           DEFAULT NULL COMMENT 'PRIMARY | SUBAGENT | ALL',
    `hidden`                     TINYINT(1)            DEFAULT NULL COMMENT '是否对调用方隐藏',
    `persist_session`            TINYINT(1)            DEFAULT NULL COMMENT '是否持久化子 agent 会话',
    `inherit_parent_permissions` TINYINT(1)            DEFAULT NULL COMMENT '是否继承父 agent 权限',
    `expose_to_user`             TINYINT(1)            DEFAULT NULL COMMENT '是否暴露给终端用户',
    `tools`                      JSON                  DEFAULT NULL COMMENT '子 agent 可用工具；空则继承父',
    `skills`                     JSON                  DEFAULT NULL COMMENT '子 agent 可用技能；空则继承父',
    `remote`                     TINYINT(1)            DEFAULT NULL COMMENT '是否远端子 agent（A2A）',
    `url`                        VARCHAR(512)          DEFAULT NULL COMMENT '远端地址',
    `headers`                    JSON                  DEFAULT NULL COMMENT '远端请求头',
    `remote_streaming`           TINYINT(1)            DEFAULT NULL COMMENT '远端是否流式返回',
    `remote_stream_detail`       VARCHAR(64)           DEFAULT NULL COMMENT '远端流式明细级别',
    `remote_ask_policy`          VARCHAR(64)           DEFAULT NULL COMMENT '远端追问策略',
    `remote_context_attributes`  JSON                  DEFAULT NULL COMMENT '传给远端的上下文字段',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_sub` (`agent_name`, `sub_name`),
    KEY `idx_agent_order` (`agent_name`, `sort_order`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：HARNESS 子 agent 声明';

-- ---------- 7. prompt 正文 ----------
-- 取代两条旧来源：Nacos PromptSpec，以及本地 md 的 frontmatter + 正文。
-- 正文用 MEDIUMTEXT 存「纯文本 / Markdown 原文」，渲染交给前端 —— 后端不做任何 markdown 处理，
-- 这样同一份正文既能喂给模型（原样进 sysPrompt），也能在页面上所见即所得地编辑。
CREATE TABLE IF NOT EXISTS `md_prompt`
(
    `prompt_key`        VARCHAR(128) NOT NULL COMMENT 'prompt 唯一键（agent 通过 prompt-key 引用）',
    `display_name`      VARCHAR(255) NOT NULL COMMENT '展示名（页面标题用它）；不参与装配、不参与唯一键、不可被引用',
    `description`       VARCHAR(1024)         DEFAULT NULL COMMENT '用途说明（旧数据里取自 agent 的 description）',
    `biz_tags`          JSON                  DEFAULT NULL COMMENT '业务标签',
    `body`              MEDIUMTEXT            DEFAULT NULL COMMENT '提示词正文（纯文本 / Markdown）',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
    `version`           INT          NOT NULL DEFAULT 1 COMMENT '内容版本号，每次保存 +1',
    `published_version` INT                   DEFAULT NULL COMMENT '已发布版本号；NULL = 从未发布',
    `published_at_ms`   BIGINT                DEFAULT NULL COMMENT '最近一次发布时间',
    `created_at_ms`     BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms`     BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `created_by`        VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    `updated_by`        VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`prompt_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：Prompt 正文';

-- ---------- 8. MCP 服务端 ----------
-- 字段对齐官方 McpServerConfig（transport/command/args/env/url/headers/queryParams/enableTools/timeout/
-- initializationTimeout），其中 stdio 三件套（command/args/env）保留列但当前运行时明确拒绝：
-- 现有 DbMcpClientFactory 就只支持远端 MCP，将来若放开本地 stdio，不必再改表结构。
--
-- enable_tools / disable_tools 的语义变更（设计文档 D10）：
--   原来它们是 agent 级旋钮（某个 agent 挂这个 MCP 时只注册哪些工具）。
--   现在下沉到 MCP 条目级，表示「这个 MCP 在本平台的工具范围」。
--   ⚠ 历史迁移注意：若两个 agent 曾对同一个 MCP 配了不同的白名单（老定义里这是 agent 级旋钮），
--     归位时会冲突，必须交人工裁决，不允许静默取其一。（原由一次性导入器负责检出；该导入器已于
--     2026-09-22 删除，此后这类冲突只可能来自直接改库，属人工维护范畴。）
CREATE TABLE IF NOT EXISTS `md_mcp_server`
(
    `server_name`       VARCHAR(128) NOT NULL COMMENT 'MCP 唯一名（agent 通过 mcp-names 引用）',
    `display_name`      VARCHAR(255) NOT NULL COMMENT '展示名（页面标题用它）；不参与装配、不参与唯一键、不可被引用',
    `description`       VARCHAR(1024)         DEFAULT NULL COMMENT '用途说明',
    `biz_tags`          JSON                  DEFAULT NULL COMMENT '业务标签',
    `transport`         VARCHAR(32)           DEFAULT NULL COMMENT 'streamable | sse | stateless-http（stdio 暂不支持）',
    `url`               VARCHAR(512)          DEFAULT NULL COMMENT '远端端点 URL',
    `headers`           JSON                  DEFAULT NULL COMMENT '请求头（鉴权 token 等）',
    `query_params`      JSON                  DEFAULT NULL COMMENT '附加查询参数',
    `command`           VARCHAR(512)          DEFAULT NULL COMMENT 'stdio 专用，暂不支持',
    `args`              JSON                  DEFAULT NULL COMMENT 'stdio 专用，暂不支持',
    `env`               JSON                  DEFAULT NULL COMMENT 'stdio 专用，暂不支持',
    `enable_tools`      JSON                  DEFAULT NULL COMMENT '工具白名单；非空则以它为准（原 agent 级旋钮，已下沉到本表）',
    `disable_tools`     JSON                  DEFAULT NULL COMMENT '工具黑名单；enable_tools 非空时忽略本列',
    `timeout_ms`        BIGINT                DEFAULT NULL COMMENT '单次调用超时（毫秒）',
    `init_timeout_ms`   BIGINT                DEFAULT NULL COMMENT '握手 / 初始化超时（毫秒）',
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用（停用后引用它的 agent 装配时跳过并告警）',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
    `version`           INT          NOT NULL DEFAULT 1 COMMENT '内容版本号，每次保存 +1',
    `published_version` INT                   DEFAULT NULL COMMENT '已发布版本号；NULL = 从未发布',
    `published_at_ms`   BIGINT                DEFAULT NULL COMMENT '最近一次发布时间',
    `created_at_ms`     BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms`     BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `created_by`        VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    `updated_by`        VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`server_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：MCP 服务端';

-- ---------- 9. skill 治理元数据 ----------
-- skill 为什么只有治理表、没有内容表：
--   内容（SKILL.md 正文 + 附属文件）由官方 MysqlSkillRepository 自己建表并管理
--   （agentscope_skills / agentscope_skill_resources），它的 save/getSkill 就是内容的唯一权威读写路径。
--   本表只放官方表没有、而控制面必须有的东西：业务标签、草稿/发布态、版本号、展示名。
--
-- 一致性约定（重要）：
--   * 内容的权威位置永远是官方表；本表绝不存正文，避免两处真相；
--   * description 是「缓存」，为了列表页不必逐条回查官方表，保存时同步；
--   * 若有人绕过控制面直接改官方表，本表会漂移 —— 列表接口需要做一次 name 差集校验并告警。
CREATE TABLE IF NOT EXISTS `md_skill_meta`
(
    `skill_name`        VARCHAR(128) NOT NULL COMMENT '与官方 agentscope_skills.name 一一对应（本表不存内容）',
    `display_name`      VARCHAR(255) NOT NULL COMMENT '展示名（官方表只有 name）。必填：页面卡片标题只取它，不回落成 skill_name',
    `description`       VARCHAR(1024)         DEFAULT NULL COMMENT '缓存官方表的 description',
    `biz_tags`          JSON                  DEFAULT NULL COMMENT '业务标签',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | PUBLISHED',
    `version`           INT          NOT NULL DEFAULT 1 COMMENT '内容版本号，每次保存 +1',
    `published_version` INT                   DEFAULT NULL COMMENT '已发布版本号；NULL = 从未发布',
    `published_at_ms`   BIGINT                DEFAULT NULL COMMENT '最近一次发布时间',
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at_ms`     BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `updated_at_ms`     BIGINT       NOT NULL COMMENT '最后修改时刻（epoch millis）',
    `created_by`        VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    `updated_by`        VARCHAR(64)           DEFAULT NULL COMMENT '最后修改人',
    PRIMARY KEY (`skill_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：Skill 治理元数据（内容以官方 agentscope_skills 为准）';

-- ---------- 10. 版本快照：四类资源共用，支撑回滚 ----------
-- 只在「发布」时写入，草稿不落快照 —— 否则反复编辑会灌爆本表。
--
-- snapshot_json 的内容约定：
--   AGENT  = md_agent + 5 张关联表的整行 JSON（含 sub_agents 数组），保证能完整重建一个 agent
--   PROMPT = md_prompt 整行
--   MCP    = md_mcp_server 整行
--   SKILL  = 官方表内容（skill_content + resources）+ md_skill_meta 整行
--
-- 回滚语义：读旧快照 → 写回主表内容 → version += 1（新增版本号，不覆盖历史）→ status 置 DRAFT。
-- 回滚后仍要显式发布一次才生效，避免误操作直接影响线上（设计文档 §4.4）。
CREATE TABLE IF NOT EXISTS `md_resource_version`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `resource_type` VARCHAR(16)  NOT NULL COMMENT 'AGENT | PROMPT | SKILL | MCP',
    `resource_name` VARCHAR(128) NOT NULL COMMENT '对应资源的主键值（agent_name / prompt_key / server_name / skill_name）',
    `version`       INT          NOT NULL COMMENT '快照对应的版本号',
    `snapshot_json` LONGTEXT     NOT NULL COMMENT '整行快照（见上方内容约定）',
    `change_note`   VARCHAR(512)          DEFAULT NULL COMMENT '本次变更说明',
    `created_at_ms` BIGINT       NOT NULL COMMENT '快照生成时刻（epoch millis）',
    `created_by`    VARCHAR(64)           DEFAULT NULL COMMENT '操作人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type_name_ver` (`resource_type`, `resource_name`, `version`),
    KEY `idx_resource` (`resource_type`, `resource_name`, `version` DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='控制面：四类资源的版本快照';

-- ---------- 11. 模型表：Agent 只声明 model_key，连接参数全在库里 ----------
-- 为什么模型必须进库（改造前它是 application.yml 里那一个全局共享的 Model Bean）：
--   1) 一个平台不可能只有一个模型：主模型 / 记忆归并 / 上下文压缩 / 向量化各需不同的端点与
--      生成参数，而 yml 里那一个是"全体共享" —— 换一次模型要改配置 + 重启服务；
--   2) 端点与 apiKey 是会轮换的凭据，改它属于线上变更，需要版本快照与回滚
--      （与另外几类受治理资源同一套流程，见 ResourceType）；
--   3) md_agent_runtime.model_ref / md_agent_memory.*_model_ref 这几列早就在了，但以前填的是
--      Spring Bean 名 —— 页面既没法校验、也没法给下拉，拼错只能在装配期炸。落库后它们填
--      model_key，可以在页面上选、在保存前校验。
--
-- ⚠ api_key 目前是明文存库的（本项目控制面暂无鉴权，这是已知技术债、不是疏忽）。
--   缓解手段在接口层而不是存储层：列表接口一律打码，只有"查看详情"单独返回明文
--   （与 md_channel 的凭据同一套约定，见 ModelController 的类注释）。
--
-- ⚠ is_default 的"全表最多一个"由应用层在事务内保证（写入新默认值时会先清掉旧值），
--   不是数据库约束：MySQL 没有部分唯一索引，而"用 NULL 表示非默认"会与
--   "版本快照要能原样还原一行"的原则冲突（NULL 与 0 在三态语义下不等价）。

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

-- =====================================================================================
-- 附：两个由官方框架自动创建的表 —— 注意它们在**另一个 schema** 里，不在本库
--
-- skill 内容是官方 MysqlSkillRepository 自己管的，首次构造该对象时它会自动 CREATE DATABASE +
-- CREATE TABLE。实测（2026-09-21）：
--   库：agentscope
--   agentscope_skills            id(bigint PK) / name(varchar255 UNIQUE) / description(text) /
--                                skill_content(longtext) / source(varchar255) / metadata_json(longtext) /
--                                created_at / updated_at
--   agentscope_skill_resources   (id, resource_path) 联合主键 / resource_content(longtext) /
--                                created_at / updated_at
--
-- ⚠ 为什么是独立的 agentscope 库、而不是本库：官方实现对 library 名做了标识符白名单校验
--   （只允许 字母/数字/下划线，且首字符必须是字母或下划线），
--   库名 md-agent-platform 含连字符，会被直接拒绝：
--     IllegalArgumentException: Database name contains invalid characters ...
--   因此 databaseName 只能用 agentscope（默认）或另一个下划线命名的库；
--   后者只是"把库拆成两个"的等价结果，没有任何好处，故直接用默认值。
--   连接仍走本项目的数据源（同一 MySQL 实例），跨 schema 查询由 root 权限保证。
-- =====================================================================================

-- #####################################################################################
-- 02. 渠道与网关（Channel / Binding / Gateway）
-- #####################################################################################

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
-- 执行方式：见文件头；本文件已合并全部基线表。
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

-- #####################################################################################
-- 03. 运行链路观测（Run / Event）
-- #####################################################################################

-- =====================================================================================
-- 运行链路观测（trace）落库脚本 —— Step 2
-- =====================================================================================
-- 两张表，职责单一，不做外键（沿用本项目的既定风格：外键会把删除顺序变成隐式约束，
-- 而观测数据是可丢弃的，不值得为它引入写入失败的可能）：
--   md_agent_run        一次 run 一行（run 级属性 + 汇总），主键 run_id
--   md_agent_run_event  一次阶段事件一行（STARTED / COMPLETED），自增主键 + (run_id, seq) 唯一
--
-- 为什么要「事件表」而不是把阶段塞进 run 表的一个 JSON 列：
--   1. 事件是只增不改的（纯 INSERT），没有 UPDATE 竞争，也不需要行锁；
--   2. seq 是前端断点续传的游标，(run_id, seq) 唯一键让"重复投递"变成幂等错误而不是脏数据；
--   3. 一次 run 的事件是几十到几百条，塞 JSON 列会让"按 seq 取增量"变成全量读取 + 应用层过滤。
--
-- 时间为什么用 BIGINT（epoch millis）而不是 DATETIME：
--   领域模型（AgentTraceRun / AgentTraceEvent）里就是 epoch millis，直存直取，
--   不引入任何时区换算 —— 一旦 JDBC URL 上的 serverTimezone 被改动，DATETIME 列的历史数据
--   会整体漂移，而 BIGINT 不受影响。代价是在 SQL 客户端里不便肉眼阅读，用
--   FROM_UNIXTIME(started_at_ms / 1000) 包一层即可。
--
-- 「来源」(source) 为什么必须由入口方写下来，而不是查询时反推：
--   sessionId 由每个入口自己算（企微 = single-<userId>-<当天日期>、群聊 = <chatId>-<日期>、
--   页面对话 = console-<8 位随机>、飞书 = userId、定时任务 = 作业名），格式上互不相干 ——
--   靠前缀猜既猜不全也迟早猜错，而"这条链路是从哪来的"偏偏是排查时的第一个问题。
--   所以四个入口（console / wecom / feishu / schedule）在建 run 时各写各的常量、此后不再变；
--   NULL 表示入口方没标注，页面显示为空而不是编一个值出来。
--
-- 工作流两列为什么直接加在 run 表上、而不是新开一张关联表：
--   关联是 1:1（一次 agent 调用最多属于一个工作流节点），新表意味着每次查链路都要多一次 JOIN，
--   而这两列加起来才 128 字节。加列之后，执行链路列表可以直接按"只看某次工作流运行"筛选。
--   注意它们的 NULL 语义与 source 相反：这里 NULL = 确实不属于任何工作流，
--   而 source 的 NULL = 入口方没标注。
--   新装环境的表定义已包含这两列，无需再执行历史增量脚本。
--
-- parent_run_id / task_id 两列为什么在 run 表上（2026-09-28 增量）：
--   子 agent 默认与父 agent 共用同一个 RunState，也就是"同一棵调用树"，用不着这两列。
--   但异步子 agent（agent_spawn 带 timeout_seconds=0，或同步超时后被 promote 成后台任务）
--   会在父 run 已经收口之后才真正开始跑 —— 那一刻中间件发现上下文里的 RunState 已经 finished，
--   只能给它新开一条 run。于是"这条 run 是被谁派发出来的"就断线了，页面上表现为
--   一棵没有根、也不知道从哪来的孤立链路。
--   parent_run_id = 那个已经收口的父 run，task_id = 框架 TaskRepository 里的后台任务号。
--   两者都可能为 NULL：parent_run_id 的 NULL = 这是一次用户直接发起的 run；task_id 的 NULL =
--   这次 run 不是由后台任务跑起来的（同步子 agent、或框架没给 task_id 的路径）。
--   与 source / workflow_* 一样在 run 建立那一刻冻结，是历史事实。
--
-- idx_parent_run 为什么存在：从父链路点进"它派发出去的异步子链路"是反向查询
--   （WHERE parent_run_id = ?），没有这个索引就只能全表扫。
--
-- idx_user_session 为什么必须带上 user_id：
--   run 表的 session_id 与状态表的 session_id 不是同一个串（后者被框架打包成
--   <userId>:<sessionId>），会话页要把两边对齐必须同时用上 (user_id, session_id)，
--   而会话聚合（/api/sessions）的分组键也正是这两列。
-- =====================================================================================

CREATE TABLE IF NOT EXISTS `md_agent_run`
(
    `run_id`        VARCHAR(255) NOT NULL COMMENT '运行 id，形如 t3k9f2a1-x7b2q9（字典序 = 时间序）',
    `agent_name`    VARCHAR(255) NOT NULL COMMENT 'agent 名（前端叫 agentId）',
    `agent_kind`    VARCHAR(16)  NOT NULL COMMENT 'react | harness，在 run 建立那一刻冻结',
    `session_id`    VARCHAR(255) DEFAULT NULL COMMENT '会话 id',
    `user_id`       VARCHAR(255) DEFAULT NULL COMMENT '发起人（企微 openId / 飞书 userId 等）',
    `source`        VARCHAR(32)  DEFAULT NULL COMMENT '触发来源：console | wecom | feishu | schedule | workflow；NULL=入口方没写',
    `workflow_run_id`   VARCHAR(64) DEFAULT NULL COMMENT '所属工作流运行（md_workflow_run.run_id）；NULL=不是工作流发起的',
    `workflow_node_key` VARCHAR(64) DEFAULT NULL COMMENT '所属工作流节点 key；NULL=不是工作流发起的',
    `status`        VARCHAR(16)  NOT NULL COMMENT 'RUNNING | DONE | ERROR',
    `started_at_ms` BIGINT       NOT NULL COMMENT '开始时刻（epoch millis）',
    `ended_at_ms`   BIGINT       DEFAULT NULL COMMENT '结束时刻；进行中为 NULL',
    `input_tokens`  INT          NOT NULL DEFAULT 0 COMMENT '输入 token 合计',
    `output_tokens` INT          NOT NULL DEFAULT 0 COMMENT '输出 token 合计',
    `cached_tokens` INT          NOT NULL DEFAULT 0 COMMENT '命中缓存的输入 token 合计',
    `error`         TEXT         DEFAULT NULL COMMENT '失败摘要（异常类名 + message）',
    `system_prompt` TEXT         DEFAULT NULL COMMENT '本轮生效的系统提示词（run 级属性，不占事件序号）',
    `input`         JSON         DEFAULT NULL COMMENT '顶层输入摘要（消息条数 + 每条消息的简述）',
    `parent_run_id` VARCHAR(255) DEFAULT NULL COMMENT '派发出这条 run 的父 run；NULL=用户直接发起（见上面 parent_run_id 的说明）',
    `task_id`       VARCHAR(128) DEFAULT NULL COMMENT '框架 TaskRepository 的后台任务号；NULL=不是后台任务跑起来的',
    PRIMARY KEY (`run_id`),
    KEY `idx_agent_started` (`agent_name`, `started_at_ms`),
    KEY `idx_started` (`started_at_ms`),
    KEY `idx_session` (`session_id`),
    KEY `idx_parent_run` (`parent_run_id`),
    KEY `idx_user_session` (`user_id`, `session_id`),
    -- 工作流详情页要按"某次编排"取链路（单看 workflow_run_id 的过滤走最左前缀），
    -- 两列顺序按这个查询来定
    KEY `idx_workflow_run` (`workflow_run_id`, `workflow_node_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='agent 运行链路：run 级汇总';

CREATE TABLE IF NOT EXISTS `md_agent_run_event`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `run_id`         VARCHAR(255) NOT NULL COMMENT '所属 run',
    `seq`            INT          NOT NULL COMMENT '事件序号：同一 run 内从 1 开始、连续、产生即冻结',
    `stage_seq`      INT          NOT NULL COMMENT '所属阶段标识（= 该阶段 STARTED 事件的 seq）',
    `event_type`     VARCHAR(16)  NOT NULL COMMENT 'STARTED | COMPLETED',
    `phase`          VARCHAR(24)  NOT NULL COMMENT 'AGENT | REASONING | ACTING',
    `title`          VARCHAR(512) DEFAULT NULL COMMENT '一句话摘要',
    `status`         VARCHAR(16)  NOT NULL COMMENT 'RUNNING | OK | ERROR',
    `stage_start_ms` BIGINT       NOT NULL COMMENT '阶段开始时刻，父子关系靠它算出来，两种事件都要带',
    `cost_ms`        BIGINT       NOT NULL DEFAULT 0 COMMENT '阶段耗时；STARTED 恒为 0',
    `input`          JSON         DEFAULT NULL COMMENT '阶段入参（只写在 STARTED 上）',
    `output`         JSON         DEFAULT NULL COMMENT '阶段出参（只写在 COMPLETED 上）',
    `error`          TEXT         DEFAULT NULL COMMENT '失败摘要',
    `created_at_ms`  BIGINT       NOT NULL COMMENT '落库时刻，用于排查「事件比 run 晚多久落盘」',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_seq` (`run_id`, `seq`),
    KEY `idx_run_stage` (`run_id`, `stage_seq`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='agent 运行链路：阶段事件（只增不改）';

-- #####################################################################################
-- 04. 工作流（Definition / Run）
-- #####################################################################################

-- =====================================================================================
-- 工作流（Workflow）落库脚本 —— 第一批：agent / condition / join 三类节点
-- =====================================================================================
-- 设计文档：docs/develop/2026-09-23_workflow-design.md（先读它再看本脚本）
--
-- 共 4 张表，分两层职责：
--   定义层 —— 控制面元数据，与 md_prompt / md_mcp_server 同类，走「草稿 / 发布 / 快照 / 回滚」
--     md_workflow           工作流草稿主表（节点图存成一段 JSON）
--     md_workflow_node_ref  发布时投影出的「这个节点引用了谁」，供删除保护反查
--   运行层 —— 执行实例，只增不改，与 md_agent_run 同类
--     md_workflow_run       一次工作流执行一行
--     md_workflow_run_node  该次执行里每个节点一行
--
-- 发布快照（不可变 revision）刻意<b>不在这里</b>：复用 md_resource_version，
-- resource_type 新增取值 WORKFLOW。理由见 ResourceType 枚举的注释 ——
-- 「草稿 / 发布 / 版本快照 / 回滚」这套流程已经被五类资源验证过，工作流是第六类；
-- 另起一张 revision 表意味着版本列表、回滚、快照序列化三处都要再实现一遍，
-- 而它们必须与另外五类永远一致，重复实现的唯一效果是多一个会漏改的地方。
--
-- 三条沿用既有约定的设计决定，改脚本前务必先读：
--   1）时间一律 BIGINT（epoch millis），与 trace-schema.sql / control-plane-schema.sql 一致。
--      理由见那两张脚本的头部注释（避免 serverTimezone 改动导致历史数据整体漂移）。
--   2）不做外键、不做级联删除。这里比别处更彻底：运行数据是可丢弃的观测类数据，
--      连「删除顺序」都不需要维护，级联只会把丢弃变成一次有风险的连锁操作。
--   3）JSON 列在实体上必须配 @TableName(autoResultMap = true) + JacksonTypeHandler。
--      漏了 autoResultMap 的表现是「写得进去、读回来是 null」—— 写路径不报错，只有读才暴露。
--
-- 执行方式：见文件头；本文件已合并全部基线表。
-- =====================================================================================

-- ---------- 1. 工作流定义：草稿态 + 发布状态 ----------
-- 为什么节点图存成一段 JSON 而不是「节点表 + 边表」两张关系表：
--   1）第一批三类节点（agent / condition / join）的配置字段完全异构 ——
--      agent 要 agentId、condition 要表达式、join 要汇合模式。建列的结果必然是
--      要么一张表塞二十个大部分为 NULL 的列，要么每类节点一张表；
--   2）写入的粒度是「整张图」而不是「一个节点」：页面保存时提交的是全量 spec，
--      不存在"只改一个节点"的请求。拆表换来的只是把一次 INSERT 变成一次先删后插的循环；
--   3）读的粒度同样是整张图：校验、发布、执行三处都要完整的 nodes + edges。
-- 代价与补偿：拆表唯一真正的好处是「能反查引用」（删掉 agent X 会毁掉哪些流程），
-- 这个能力由 md_workflow_node_ref 在发布时投影出来补上 —— 反查只需要发布过的引用，
-- 而草稿里的引用本来就不需要保护。
--
-- 为什么 spec 用 JSON 类型而不是 MEDIUMTEXT：与 md_agent_run 的 input 列保持一致，
-- 且 MySQL 会替我们挡住「写进去一段不是 JSON 的东西」这种低级错误。
CREATE TABLE IF NOT EXISTS `md_workflow`
(
    `workflow_key`      VARCHAR(128) NOT NULL COMMENT '工作流标识；装配与引用都用它，页面上不展示',
    `display_name`      VARCHAR(255) NOT NULL COMMENT '展示名 —— 页面上唯一该露给人看的名字',
    `description`       VARCHAR(1024) DEFAULT NULL COMMENT '一句话说明：什么时候该用这条流程',
    `spec_json`         JSON         NOT NULL COMMENT '工作流定义：{nodes:[], edges:[]}，见 WorkflowSpec',
    `version`           INT          NOT NULL DEFAULT 1 COMMENT '草稿版本号，单调递增（保存 / 回滚都推进）',
    `status`            VARCHAR(16)  NOT NULL COMMENT 'DRAFT | PUBLISHED（取值见 GovernedRow）',
    `published_version` INT          DEFAULT NULL COMMENT '线上正在跑的版本号；NULL = 从未发布',
    `published_at_ms`   BIGINT       DEFAULT NULL COMMENT '最近一次发布时间；NULL = 从未发布',
    `created_at_ms`     BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    `created_by`        VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
    `updated_at_ms`     BIGINT       NOT NULL COMMENT '最近修改时刻（epoch millis）',
    `updated_by`        VARCHAR(64)  DEFAULT NULL COMMENT '最近修改人',
    PRIMARY KEY (`workflow_key`),
    KEY `idx_updated` (`updated_at_ms`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工作流定义：草稿内容 + 发布状态';

-- ---------- 2. 工作流节点引用投影：发布时生成，供删除保护反查 ----------
-- 只记「已发布的 revision 引用了谁」：
--   - 草稿里的引用不投影。草稿随时会被改，投影它意味着每次保存都要重算一遍全表，
--     而草稿引用错了不会影响任何线上行为 —— 真正会出事的只有已发布的版本。
--   - 回滚（把旧快照写回草稿）也不投影，理由同上：回滚不改变"线上跑的是哪一版"。
-- 每一列都是「查引用」这件事的必需品，没有冗余：
--   workflow_key + revision  定位"是哪条流程的哪一版会被破坏"
--   node_key                 定位"是哪个节点"（页面要能直接跳过去让人改）
--   node_type                节点类型，将来 condition / join 也可能引用别的资源
--   ref_type + ref_id        被引用的对象（第一批只有 AGENT）
CREATE TABLE IF NOT EXISTS `md_workflow_node_ref`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `workflow_key` VARCHAR(128) NOT NULL COMMENT '所属工作流',
    `revision`     INT          NOT NULL COMMENT '所属发布版本号（= md_resource_version.version）',
    `node_key`     VARCHAR(64)  NOT NULL COMMENT '节点 key（同一流程内唯一）',
    `node_type`    VARCHAR(16)  NOT NULL COMMENT 'AGENT | CONDITION | JOIN',
    `ref_type`     VARCHAR(16)  NOT NULL COMMENT '被引用的资源类型；第一批只有 AGENT',
    `ref_id`       VARCHAR(128) NOT NULL COMMENT '被引用资源的业务键（agent 名）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ref` (`workflow_key`, `revision`, `node_key`, `ref_type`, `ref_id`),
    KEY `idx_target` (`ref_type`, `ref_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工作流节点引用投影（发布时生成，供删除保护反查）';

-- ---------- 3. 一次工作流执行 ----------
-- 与 md_agent_run 的关系是本脚本最容易被误解的一处：
--   一个节点执行一次 agent = 一条 md_agent_run；一次工作流执行 = 多条 md_agent_run。
--   两者是 1:N，所以【不能】合并成一张表 —— md_agent_run 的主键是"一次 agent 调用"，
--   而工作流需要的是"一次编排"这个更高层的对象，它有自己的状态、自己的输入输出。
--   两侧的关联靠 md_agent_run.workflow_run_id + workflow_node_key 两列（见升级脚本）。
--
-- 为什么要冻结 revision：官方设计里最值钱的一条 ——「后续编辑不改变已有运行的拓扑」。
-- 执行期间有人改了草稿并发布，这次执行仍必须按它启动时的版本跑完，
-- 否则页面上会出现"图变了但节点状态对不上"的诡异现象。
--
-- state 第一批只有三种（RUNNING / SUCCEEDED / FAILED），刻意不预留 waiting / paused：
-- 那两种状态只在 timer 与 signal 节点下才有意义（要能挂起并跨进程恢复），
-- 而第一批不含这两类节点。预留一个永远不会被写入的取值，只会让读代码的人
-- 以为"系统支持挂起"，进而在排查超时问题时找错方向。
CREATE TABLE IF NOT EXISTS `md_workflow_run`
(
    `run_id`          VARCHAR(64)  NOT NULL COMMENT '工作流运行 id，形如 t3k9f2a1-x7b2q9',
    `workflow_key`    VARCHAR(128) NOT NULL COMMENT '所属工作流',
    `revision`        INT          NOT NULL COMMENT '本次执行冻结的版本号',
    `trigger_type`    VARCHAR(16)  NOT NULL COMMENT 'manual | cron | event；第一批只有 manual',
    `trigger_ref`     VARCHAR(255) DEFAULT NULL COMMENT '触发者：操作人 / 作业名；NULL = 入口方没写',
    `session_id`      VARCHAR(255) DEFAULT NULL COMMENT '会话 id；缺省时由引擎按 run 生成一个',
    `user_id`         VARCHAR(255) DEFAULT NULL COMMENT '发起人',
    `state`           VARCHAR(24)  NOT NULL COMMENT 'RUNNING | SUCCEEDED | FAILED',
    `input_json`      JSON         DEFAULT NULL COMMENT '本次运行的输入（页面提交的 JSON）',
    `output_json`     JSON         DEFAULT NULL COMMENT '终态输出：每个节点的结果汇总，见 WorkflowRunOutput',
    `failure_code`    VARCHAR(64)  DEFAULT NULL COMMENT '失败分类（VALIDATION | NODE_FAILED | INTERNAL）',
    `failure_message` TEXT         DEFAULT NULL COMMENT '失败原因原文，原样带回页面',
    `policy`          VARCHAR(24)  NOT NULL COMMENT '失败策略 fail_fast | continue；第一批只有 fail_fast',
    `started_at_ms`   BIGINT       NOT NULL COMMENT '开始时刻',
    `ended_at_ms`     BIGINT       DEFAULT NULL COMMENT '结束时刻；进行中为 NULL',
    `created_at_ms`   BIGINT       NOT NULL COMMENT '落库时刻，与 started_at_ms 分开：可查"排了多久才开始"',
    PRIMARY KEY (`run_id`),
    KEY `idx_workflow_started` (`workflow_key`, `started_at_ms`),
    KEY `idx_state` (`state`),
    KEY `idx_session` (`session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工作流运行：一次编排一行';

-- ---------- 4. 一次执行里的每个节点 ----------
-- 为什么 node 表要独立，而不是把节点状态塞进 run 的一个 JSON 列：
--   1）页面按节点渲染执行图，每个节点要单独取状态与输入输出；
--      塞 JSON 会让"某一轮里哪些节点跑完了"变成全量读取 + 应用层过滤；
--   2）它是只增不改的（每个节点最多被写三次：RUNNING / 终态），没有行竞争；
--   3）(run_id, node_key) 唯一键让"重复派发同一节点"变成一个幂等错误而不是脏数据 ——
--      这是第二批引入重试 / 租约时最需要的一道防线。
--
-- agent_run_id 是这里通向"执行链路观测"的钥匙：页面从工作流运行详情
-- 点进某个 AGENT 节点，就是拿它去 /api/traces/{runId}。
-- 它可空：CONDITION / JOIN 节点不调用 agent，永远为空。
CREATE TABLE IF NOT EXISTS `md_workflow_run_node`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `run_id`          VARCHAR(64)  NOT NULL COMMENT '所属工作流运行',
    `node_key`        VARCHAR(64)  NOT NULL COMMENT '节点 key（取自冻结的 spec）',
    `node_type`       VARCHAR(16)  NOT NULL COMMENT 'AGENT | CONDITION | JOIN',
    `state`           VARCHAR(24)  NOT NULL COMMENT 'PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED',
    `input_json`      JSON         DEFAULT NULL COMMENT '节点入参：AGENT 是发给 agent 的消息，CONDITION 是求值上下文',
    `output_json`     JSON         DEFAULT NULL COMMENT '节点出参：AGENT 是最终文本，JOIN 是汇合到的东西',
    `branch_taken`    VARCHAR(64)  DEFAULT NULL COMMENT 'CONDITION 选中的出边目标节点 key；未选中分支记 SKIPPED',
    `failure_code`    VARCHAR(64)  DEFAULT NULL COMMENT '失败分类（AGENT_NOT_FOUND | TIMEOUT | INTERNAL）',
    `failure_message` TEXT         DEFAULT NULL COMMENT '失败原因原文',
    `agent_run_id`    VARCHAR(64)  DEFAULT NULL COMMENT '该节点呼出的执行链路 id，跳 /api/traces/{id} 用',
    `started_at_ms`   BIGINT       DEFAULT NULL COMMENT '节点开始时刻；PENDING / SKIPPED 为 NULL',
    `ended_at_ms`     BIGINT       DEFAULT NULL COMMENT '节点结束时刻；未结束为 NULL',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_node` (`run_id`, `node_key`),
    KEY `idx_run_state` (`run_id`, `state`),
    KEY `idx_agent_run` (`agent_run_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工作流运行：节点级状态与输入输出';

-- #####################################################################################
-- 05. 环境目录（Environment / Group / Instance）
-- #####################################################################################

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
-- （加 *_cipher / *_iv，或整体换成密文串 + 服务层加解密），
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

-- #####################################################################################
-- 06. 运行时数据库（表由框架自动创建，这里只预创建库）
-- #####################################################################################
-- 库名必须与 application.yml 中以下配置一致；若由 DBA 预先创建，可跳过本节。
--   agent-platform.control-plane-skill-database -> agentscope
--   agent-platform.state-store-database         -> md_agent_state
--   agent-platform.workspace-kv-database        -> md_agent_workspace
CREATE DATABASE IF NOT EXISTS `agentscope`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `md_agent_state`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `md_agent_workspace`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 初始化完成。
