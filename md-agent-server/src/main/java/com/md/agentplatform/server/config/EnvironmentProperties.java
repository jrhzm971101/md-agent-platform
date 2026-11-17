package com.md.agentplatform.server.config;

import lombok.Data;

/**
 * 环境分组的强类型投影模型 —— 多环境 × 多组件连接信息。
 *
 * <p>环境值只从数据库环境目录读取，不再绑定 {@code application.yml}。
 * 工具通过 {@code environment} 参数引用环境名，由 {@link EnvironmentRegistry} 解析出
 * 对应组件的连接信息（地址 / 命名空间 / 凭证等）。
 *
 * <p>这里的嵌套类只用于把数据库行投影成强类型对象；字段名必须与
 * {@code EnvironmentDefaults} 中的 key_code 一致。
 */
@Data
public class EnvironmentProperties {

    @Data
    public static class EnvironmentInfo {
        private Nacos nacos;
        private K8s k8s;
        private Rabbit rabbitmq;
        private XxlJob xxljob;
        private Magic magic;
        private OpenObserve openobserve;
        private NineData ninedata;
        private Mysql mysql;
        private Gitlab gitlab;
        private Grafana grafana;
        private SkyWalking skywalking;
    }

    @Data
    public static class Nacos {
        private String serverAddr;
        /** 命名空间 ID；工具固定从环境目录读取，public 命名空间留空。 */
        private String namespace;
        private String username;
        private String password;
    }

    @Data
    public static class K8s {
        private String url;
        /** 命名空间；工具固定从环境目录读取，不允许为空。 */
        private String namespace;
        private String token;
    }

    @Data
    public static class Rabbit {
        /** 管理 HTTP API 基础地址，如 https://cmr-uat-mq.mindigitaltech.com */
        private String url;
        /** 虚拟主机；工具固定从环境目录读取，未配置按 / 处理。 */
        private String vhost;
        /** Basic 鉴权头完整值，如 "Basic bXRuX3VzZXI6..." */
        private String authorization;
    }

    @Data
    public static class XxlJob {
        private String consoleUrl;
        private String username;
        private String password;
    }

    @Data
    public static class Magic {
        private String url;
    }

    @Data
    public static class OpenObserve {
        private String baseUrl;
        private String organization;
        private String username;
        private String password;
    }

    /**
     * 某个环境自己的 GitLab 连接信息。
     *
     * <p>字段名必须与 {@code EnvironmentDefaults#gitlab()} 里的 key_code 一致。
     */
    @Data
    public static class Gitlab {
        /** GitLab 站点根地址，如 https://git.huojintech.com（可带 /api/v4，也可不带）。 */
        private String url;
        /** 完整 API 根地址（可选）。配置后优先于 url + /api/v4。 */
        private String apiUrl;
        /** 静态 Personal Access Token（可选）。非空时优先使用，通过 PRIVATE-TOKEN 头传递。 */
        private String token;
        /**
         * 鉴权模式：auto / token / session / oauth。
         * auto（默认）：有 token 用 token，否则用 username/password 走 session。
         */
        private String authMode;
        /** 动态鉴权登录名。 */
        private String username;
        /** 动态鉴权密码；session / oauth 模式使用。 */
        private String password;
        /** OAuth 应用 client_id（可选，password grant 需要时填写）。 */
        private String oauthClientId;
        /** OAuth 应用 client_secret（可选）。 */
        private String oauthClientSecret;
        /** OAuth scope（可选），如 read_api；不填则由 GitLab 按应用授权范围决定。 */
        private String oauthScope;
        /** OAuth token 地址（可选）。默认取站点根地址 + /oauth/token。 */
        private String oauthTokenUrl;
        /** 动态 token 缓存秒数（可选，默认 3600；0 表示每次调用都重新获取）。 */
        private Integer tokenCacheSeconds;
    }

    /**
     * 某个环境自己的 NineData OpenAPI 连接信息。
     *
     * <p>字段名必须与 {@code EnvironmentDefaults#ninedata()} 里的 key_code 一致，
     * 否则 {@code EnvironmentCatalog#project} 拿不到值。
     */
    @Data
    public static class NineData {
        /** OpenAPI 根地址，如 https://cmr-prod-ninedata.mindigitaltech.com */
        private String endpoint;
        /** AccessKeyId */
        private String accessKeyId;
        /** AccessKeySecret，仅用于本地签名 */
        private String accessKeySecret;
        /** 默认库名，可空 */
        private String defaultDbName;
        /** 默认 schema，可空 */
        private String defaultSchemaName;
    }

    /**
     * 某个环境的 MySQL 直连信息。
     *
     * <p>直连只建议用于 test 环境；prod / uat 仍走 NineData 受控查询。
     * {@code allowWrite=false} 时工具只允许只读 SQL，开启后才允许 DML / DDL。
     */
    @Data
    public static class Mysql {
        /** JDBC URL，如 jdbc:mysql://host:3306/db?useUnicode=true&characterEncoding=utf8 */
        private String url;
        private String username;
        private String password;
        /** 默认库；list-tables / describe-table 未传 database 时使用 */
        private String defaultDatabase;
        /** 是否允许写操作；仅 test 环境建议设为 true */
        private Boolean allowWrite;
        /** 单次查询最多返回行数，默认 200 */
        private Integer maxRows;
        /** SQL 执行超时秒数，默认 30 */
        private Integer queryTimeoutSeconds;
    }

    /** 某个环境的 Grafana 连接信息。查询条件、看板 UID、数据源 UID 仍由工具参数传入。 */
    @Data
    public static class Grafana {
        /** Grafana 基础地址，可包含子路径，如 https://cmr-grafana.mindigitaltech.com/clusters/global/grafana */
        private String url;
        /** Basic Auth 用户名；为空时不发送认证头。 */
        private String username;
        /** Basic Auth 密码。 */
        private String password;
    }

    /** 某个环境的 SkyWalking GraphQL 连接信息。traceId、时间窗等业务参数仍由工具参数传入。 */
    @Data
    public static class SkyWalking {
        /** SkyWalking GraphQL 端点，如 https://cmr-sky.mindigitaltech.com/graphql */
        private String url;
        /** Basic Auth 用户名；为空时不发送认证头。 */
        private String username;
        /** Basic Auth 密码。 */
        private String password;
    }
}
