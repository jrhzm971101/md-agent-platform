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
-- 执行方式（手工，不做自动建表，与 trace-schema.sql 一致）：
--   mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < control-plane-schema.sql
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
