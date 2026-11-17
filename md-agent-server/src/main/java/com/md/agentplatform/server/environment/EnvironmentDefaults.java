package com.md.agentplatform.server.environment;

import com.md.agentplatform.server.environment.store.entity.EnvGroupEntity;
import com.md.agentplatform.server.environment.store.entity.EnvGroupKeyEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码侧注册的内置分组与键声明 —— <b>用于启动时补齐缺失的内置分组</b>，已有分组以库为准。
 * {@code md_env_group} / {@code md_env_group_key} 里。
 *
 * <h2>一、为什么"页面可维护"还需要一份代码里的默认值</h2>
 * 用户 2026-09-23 的决定是「分组模板页面可维护」。但可维护不等于"从零开始手敲"：
 * 这些内置分组的键名（{@code serverAddr} / {@code authorization} / {@code consoleUrl} …）
 * <b>不是随便起的</b>，它们必须与 {@code EnvironmentProperties} 里 POJO 的字段名逐字相同，
 * 否则强类型投影拿不到值。让操作员凭记忆敲这些名字，等于给每次新建部署都埋一颗雷。
 *
 * <p>所以职责这样分：
 * <ul>
 *   <li>本类 = 出厂默认值，启动时只为缺失的内置分组补建模板；</li>
 *   <li>库 = 运行期的真相，播种之后改标签、加键、调必填都走页面；</li>
 *   <li>两边不一致时以库为准，<b>本类不会被回写</b>（否则页面上的修改会在下次重启时被覆盖，
 *       那正是"页面可维护"最坏的失败模式）。</li>
 * </ul>
 *
 * <h2>二、为什么 required 给得比较严</h2>
 * 只有"少了它工具就一定用不了"的键才标必填。例如 k8s 少了 token 就只能拿到 401，
 * 而 openobserve 少了 organization 连查询都发不出去。
 * 反过来，{@code description} 这类纯备注一律不必填。
 * 标严的收益是完整度列能真的指路（"这个环境还缺 Token"），代价是种子数据里
 * 几个没配全的环境会如实显示成红的 —— 那本来就是事实，不该被抹平。
 *
 * <h2>三、example_value 里绝不出现真实凭据</h2>
 * 它是"格式示意"，不是"默认值"。种子里的示例一律写成 {@code eyJhbGci...} 这种占位形态，
 * 免得将来有人把它误当成可用值，或者更糟 —— 把真 token 提交进 git。
 */
public final class EnvironmentDefaults {

    private EnvironmentDefaults() {
    }

    /** 一个内置分组 + 它的键声明。 */
    public record GroupSeed(EnvGroupEntity group, List<EnvGroupKeyEntity> keys) {
    }

    /**
     * 出厂默认分组。
     *
     * <p>顺序即页面上的列顺序（sort_order 按 10 递增，留出中间插队空间）。
     */
    public static List<GroupSeed> seeds() {
        List<GroupSeed> seeds = new ArrayList<>();
        seeds.add(nacos());
        seeds.add(k8s());
        seeds.add(rabbitmq());
        seeds.add(xxljob());
        seeds.add(magic());
        seeds.add(openobserve());
        seeds.add(ninedata());
        seeds.add(mysql());
        seeds.add(gitlab());
        seeds.add(grafana());
        seeds.add(skywalking());
        return seeds;
    }

    // ==================================================================================
    // 十一个内置分组
    // ==================================================================================

    private static GroupSeed nacos() {
        EnvGroupEntity group = group("nacos", "Nacos", 10,
                "配置中心的地址、命名空间与账号。get-config / publish-config 这类 nacos 工具靠它连上去。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("nacos", "serverAddr", "服务地址", "STRING", true, false, 10,
                "https://nacos-test.mindigitaltech.com", "不含命名空间的根地址；带 :443 也可以"));
        keys.add(key("nacos", "namespace", "命名空间 ID", "STRING", false, false, 15,
                "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
                "命名空间 ID；工具固定从环境目录读取，public 命名空间留空"));
        keys.add(key("nacos", "username", "用户名", "STRING", true, false, 20, "nacos", null));
        keys.add(key("nacos", "password", "密码", "STRING", true, true, 30, null,
                "当前明文存储（调试期决定），见 env-schema.sql 文件头第四节"));
        return new GroupSeed(group, keys);
    }

    private static GroupSeed k8s() {
        EnvGroupEntity group = group("k8s", "K8s", 20,
                "集群地址、命名空间与访问令牌。get-pod-logs / get-component-pods 这类工具靠它查集群。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("k8s", "url", "集群地址", "STRING", true, false, 10,
                "https://cmr-uat-k8s.mindigitaltech.com/kubernetes/global",
                "要把 /kubernetes/global 这段带上，少一段会 404"));
        keys.add(key("k8s", "namespace", "命名空间", "STRING", false, false, 15, "cmr-uat",
                "命名空间；工具固定从环境目录读取，不能为空"));
        keys.add(key("k8s", "token", "访问令牌", "STRING", true, true, 20, "eyJhbGciOiJSUzI1NiIs...",
                "ServiceAccount 的 Bearer Token。当前明文存储（调试期决定）"));
        return new GroupSeed(group, keys);
    }

    private static GroupSeed rabbitmq() {
        EnvGroupEntity group = group("rabbitmq", "RabbitMQ", 30,
                "管理 HTTP API 的地址、虚拟主机与鉴权串。查队列 / 加交换机这类工具靠它。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("rabbitmq", "url", "管理地址", "STRING", true, false, 10,
                "https://cmr-uat-mq.mindigitaltech.com", "管理插件（management）的根地址"));
        keys.add(key("rabbitmq", "vhost", "虚拟主机", "STRING", false, false, 15, "/",
                "虚拟主机；工具固定从环境目录读取，留空按 / 处理"));
        keys.add(key("rabbitmq", "authorization", "鉴权串", "STRING", true, true, 20,
                "Basic bXRuX3VzZXI6...",
                "整条 Authorization 头的值，形如 Basic base64(用户名:密码)。"
                        + "为什么存整串而不是分开存账号密码：工具是原样透传这个头的，"
                        + "拆开再拼反而多一次可能拼错的编码"));
        return new GroupSeed(group, keys);
    }

    private static GroupSeed xxljob() {
        EnvGroupEntity group = group("xxljob", "XXL-Job", 40,
                "调度中心地址与登录账号。查执行器 / 加任务这类工具靠它。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("xxljob", "consoleUrl", "控制台地址", "STRING", true, false, 10,
                "https://cmr-uat-job.mindigitaltech.com/xxl-job-admin", "要带上下文路径"));
        keys.add(key("xxljob", "username", "用户名", "STRING", true, false, 20, "admin", null));
        keys.add(key("xxljob", "password", "密码", "STRING", true, true, 30, null,
                "当前明文存储（调试期决定）"));
        return new GroupSeed(group, keys);
    }

    private static GroupSeed magic() {
        EnvGroupEntity group = group("magic", "Magic-API", 50,
                "magic-api 取数入口。MagicApiDataSearchTool 靠它按国家取数。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("magic", "url", "取数地址", "STRING", true, false, 10,
                "https://cmr-uat-magic-api.mindigitaltech.com/mtn/getDataBySelect",
                "整条取数 URL（含路径），不是一个根地址"));
        return new GroupSeed(group, keys);
    }

    private static GroupSeed openobserve() {
        EnvGroupEntity group = group("openobserve", "OpenObserve", 60,
                "日志平台。日志检索 / 聚合 / 前后文这类工具靠它。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("openobserve", "baseUrl", "服务地址", "STRING", true, false, 10,
                "https://cmr-prod-o2.mindigitaltech.com", null));
        keys.add(key("openobserve", "organization", "组织标识", "STRING", true, false, 20,
                "3ILQGFwgBq6ptK2iAZmDM7C2Zbt",
                "OpenObserve 的 organization id。少了它查询直接 400，所以标必填"));
        keys.add(key("openobserve", "username", "用户名", "STRING", true, false, 30,
                "devops@mindigitalgroup.com", null));
        keys.add(key("openobserve", "password", "密码", "STRING", true, true, 40, null,
                "当前明文存储（调试期决定）"));
        return new GroupSeed(group, keys);
    }

    private static GroupSeed ninedata() {
        EnvGroupEntity group = group("ninedata", "NineData", 70,
                "NineData OpenAPI 的地址与 AccessKey。ninedata 工具按 environment 读取这里，"
                        + "不再依赖全局唯一的一套凭据。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("ninedata", "endpoint", "OpenAPI 地址", "STRING", true, false, 10,
                "https://cmr-prod-ninedata.mindigitaltech.com",
                "NineData 服务根地址，不要带 /openapi 路径"));
        keys.add(key("ninedata", "accessKeyId", "AccessKeyId", "STRING", true, false, 20,
                "Rl2K9QSM9LOLZSMU",
                "NineData 控制台 → 账户管理 → AccessKey 管理中创建的 AccessKeyId"));
        keys.add(key("ninedata", "accessKeySecret", "AccessKeySecret", "STRING", true, true, 30,
                null, "仅用于本地 SHA-256 签名，不会随请求发送"));
        keys.add(key("ninedata", "defaultDbName", "默认库名", "STRING", false, false, 50,
                "uat_lms_backend", "调用 sql-execute 未传 databaseName 时使用"));
        keys.add(key("ninedata", "defaultSchemaName", "默认 Schema", "STRING", false, false, 60,
                null, "PostgreSQL / Oracle 等按 Schema 组织的库才需要；MySQL 留空"));
        return new GroupSeed(group, keys);
    }

    private static GroupSeed gitlab() {
        EnvGroupEntity group = group("gitlab", "GitLab", 80,
                "GitLab 站点地址与鉴权信息。git-clone / git-checkout / gitlab-* 这类工具按 environment 读取这里。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("gitlab", "url", "站点地址", "STRING", true, false, 10,
                "https://git.huojintech.com",
                "GitLab 根地址。可以带 /api/v4，也可以不带；不带时工具自动补 /api/v4"));
        keys.add(key("gitlab", "apiUrl", "API 根地址", "STRING", false, false, 20,
                "https://git.huojintech.com/api/v4",
                "可选。GitLab API 不在站点根路径下时填写；填了优先于 url"));
        keys.add(key("gitlab", "authMode", "鉴权方式", "STRING", false, false, 25,
                "auto",
                "auto / token / session / oauth。auto：有 token 用 token，否则用 username/password 走 session，session 端点不存在时自动回退 oauth；"
                        + "session：POST /api/v4/session 换 private_token；oauth：POST /oauth/token 密码模式换 Bearer token"));
        keys.add(key("gitlab", "token", "静态访问令牌", "STRING", false, true, 30,
                "glpat-xxxxxxxxxxxxxxxxxxxx",
                "可选。Personal Access Token，通过 PRIVATE-TOKEN 头传递；非空时优先使用。"
                        + "留空则按 authMode 用 username/password 动态获取。注意 glft- 开头的 Feed Token 不能用于 API"));
        keys.add(key("gitlab", "username", "用户名", "STRING", false, false, 40, "jinran",
                "动态鉴权登录名；使用静态 token 时仅作备注"));
        keys.add(key("gitlab", "password", "密码", "STRING", false, true, 50, "********",
                "动态鉴权密码。authMode=session/oauth 时使用；不会写入日志或工具返回"));
        keys.add(key("gitlab", "oauthClientId", "OAuth Client ID", "STRING", false, false, 60, null,
                "可选。GitLab OAuth 应用 client_id；password grant 需要时填写"));
        keys.add(key("gitlab", "oauthClientSecret", "OAuth Client Secret", "STRING", false, true, 70, null,
                "可选。GitLab OAuth 应用 client_secret"));
        keys.add(key("gitlab", "oauthScope", "OAuth Scope", "STRING", false, false, 80, "read_api",
                "可选。OAuth scope，如 read_api；不填则由 GitLab 按应用授权范围决定"));
        keys.add(key("gitlab", "oauthTokenUrl", "OAuth Token 地址", "STRING", false, false, 90, null,
                "可选。默认取站点根地址 + /oauth/token；GitLab 部署在子路径时填写"));
        keys.add(key("gitlab", "tokenCacheSeconds", "Token 缓存秒数", "INT", false, false, 100, "3600",
                "动态 token 缓存秒数，默认 3600；0 表示每次调用都重新获取"));
        return new GroupSeed(group, keys);
    }

    /**
     * MySQL 直连分组。生产 / UAT 仍优先使用 NineData；该分组通常只绑定到 test 环境。
     */
    private static GroupSeed mysql() {
        EnvGroupEntity group = group("mysql", "MySQL 直连", 75,
                "MySQL JDBC 直连信息。mysql 工具按 environment 读取这里，"
                        + "仅建议用于 test 环境；allowWrite=true 后才允许 DML / DDL。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("mysql", "url", "JDBC 地址", "STRING", true, false, 10,
                "jdbc:mysql://host:3306/db?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai",
                "完整 JDBC URL。密码不要写进 URL，单独填 password"));
        keys.add(key("mysql", "username", "用户名", "STRING", true, false, 20, "ninedata", null));
        keys.add(key("mysql", "password", "密码", "STRING", true, true, 30, null,
                "当前明文存储（调试期决定），不会回显到工具返回"));
        keys.add(key("mysql", "defaultDatabase", "默认库名", "STRING", false, false, 40,
                "uat_lms_backend", "list-tables / describe-table 未传 database 时使用"));
        keys.add(key("mysql", "allowWrite", "允许写操作", "BOOL", false, false, 50,
                "false", "仅 test 环境建议设为 true；false 时工具只允许 SELECT / SHOW / DESCRIBE / EXPLAIN"));
        keys.add(key("mysql", "maxRows", "最大返回行数", "INT", false, false, 60,
                "200", "单次查询结果最多返回行数；工具内部硬上限 5000"));
        keys.add(key("mysql", "queryTimeoutSeconds", "查询超时秒数", "INT", false, false, 70,
                "30", "单条 SQL 的执行超时；工具内部硬上限 300 秒"));
        return new GroupSeed(group, keys);
    }

    /** Grafana 只读工具：仅保存地址与 Basic Auth，查询条件仍由工具参数传入。 */
    private static GroupSeed grafana() {
        EnvGroupEntity group = group("grafana", "Grafana", 85,
                "Grafana 地址与认证信息。grafana-query / grafana-dashboards / grafana-alerts "
                        + "按 environment 读取这里；PromQL、看板 UID、时间窗等业务参数仍由工具参数传入。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("grafana", "url", "Grafana 地址", "STRING", true, false, 10,
                "https://cmr-grafana.mindigitaltech.com/clusters/global/grafana",
                "Grafana 基础地址，可包含子路径；不要追加业务 API 路径"));
        keys.add(key("grafana", "username", "用户名", "STRING", false, false, 20, "md_guest",
                "Basic Auth 用户名；留空表示不发送认证头"));
        keys.add(key("grafana", "password", "密码", "STRING", false, true, 30, null,
                "Basic Auth 密码；不会写入日志或工具返回"));
        return new GroupSeed(group, keys);
    }

    /** SkyWalking 查询工具：仅保存 GraphQL 地址与 Basic Auth，trace 业务参数不进入环境目录。 */
    private static GroupSeed skywalking() {
        EnvGroupEntity group = group("skywalking", "SkyWalking", 90,
                "SkyWalking GraphQL 地址与认证信息。skywalking-* 工具按 environment 读取这里；"
                        + "traceId、时间窗、serviceId 等业务参数仍由工具参数传入。");
        List<EnvGroupKeyEntity> keys = new ArrayList<>();
        keys.add(key("skywalking", "url", "GraphQL 地址", "STRING", true, false, 10,
                "https://cmr-sky.mindigitaltech.com/graphql",
                "SkyWalking GraphQL 端点，必须指向 /graphql"));
        keys.add(key("skywalking", "username", "用户名", "STRING", false, false, 20, null,
                "Basic Auth 用户名；SkyWalking 未开启认证时留空"));
        keys.add(key("skywalking", "password", "密码", "STRING", false, true, 30, null,
                "Basic Auth 密码；不会写入日志或工具返回"));
        return new GroupSeed(group, keys);
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    /** builtin=true：被工具代码强引用，页面上可改可加键，但不允许删除。 */
    private static EnvGroupEntity group(String code, String name, int sort, String description) {
        EnvGroupEntity entity = new EnvGroupEntity();
        entity.setGroupCode(code);
        entity.setDisplayName(name);
        entity.setDescription(description);
        entity.setBuiltin(Boolean.TRUE);
        entity.setSortOrder(sort);
        return entity;
    }

    private static EnvGroupKeyEntity key(String groupCode, String keyCode, String label, String valueType,
                                         boolean required, boolean secret, int sort,
                                         String placeholder, String description) {
        EnvGroupKeyEntity entity = new EnvGroupKeyEntity();
        entity.setGroupCode(groupCode);
        entity.setKeyCode(keyCode);
        entity.setLabel(label);
        entity.setValueType(valueType);
        entity.setRequired(required);
        entity.setSecret(secret);
        entity.setSortOrder(sort);
        entity.setPlaceholder(placeholder);
        entity.setDescription(description);
        return entity;
    }
}
