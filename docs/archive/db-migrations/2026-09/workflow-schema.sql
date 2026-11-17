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
-- 执行方式（手工，不做自动建表，与其它 schema 脚本一致）：
--   mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < workflow-schema.sql
--   mysql -h106.53.145.158 -P30306 -uroot -p md-agent-platform < workflow-schema-upgrade-2026-09-23.sql
-- （第二个脚本给已存在的 md_agent_run 补两列，见该文件头的说明）
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