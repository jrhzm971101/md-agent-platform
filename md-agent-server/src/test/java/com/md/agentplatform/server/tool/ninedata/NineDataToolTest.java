package com.md.agentplatform.server.tool.ninedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.NineDataProperties;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NineData 工具测试 —— 环境必填、参数合并、只读 SQL 闸门、配置缺失提示、异常转 JSON。
 *
 * <p>HTTP 层由 {@link NineDataClientTest} 用本地桩覆盖，这里用 StubClient 隔离，
 * 避免测试同时依赖网络与参数拼装，失败时定位不到是哪一层。
 */
class NineDataToolTest {

    private static final String ENV = "cameroon-uat";
    private static final String ENDPOINT = "https://env.example.com";
    private static final String AK = "ak-env";
    private static final String SK = "sk-env";

    private NineDataProperties properties;
    private StubClient client;
    private StubEnvironmentRegistry registry;
    private NineDataTool tool;

    @BeforeEach
    void setUp() {
        properties = new NineDataProperties();
        properties.setDefaultPageSize(50);
        properties.setSourceClient("md-agent-platform");

        client = new StubClient(properties);
        registry = new StubEnvironmentRegistry();
        registry.put(ENV, envConfig(ENDPOINT, AK, SK, "app_db", "public"));
        tool = new NineDataTool(properties, client, registry);
    }

    // ==================== 环境必填 ====================

    @Test
    void sqlExecuteUsesEnvironmentConfigAndDefaults() {
        client.response = JsonUtil.parse("{\"success\":true,\"data\":{\"decision\":\"EXECUTED\"}}");

        String output = tool.sqlExecute(ENV, "select 1", "ds_default", null, null,
                null, null, null, null, null);

        ObjectNode sent = client.lastBody;
        assertEquals(ENDPOINT, client.lastTarget.endpoint());
        assertEquals(AK, client.lastTarget.accessKeyId());
        assertEquals(SK, client.lastTarget.accessKeySecret());
        assertEquals("ds_default", sent.path("datasourceId").asText());
        assertEquals("app_db", sent.path("databaseName").asText());
        assertEquals("public", sent.path("schemaName").asText());
        assertEquals("select 1", sent.path("sql").asText());
        assertEquals(1, sent.path("current").asInt());
        assertEquals(50, sent.path("pageSize").asInt());
        assertFalse(sent.path("useSessionPersistence").asBoolean());
        assertTrue(sent.path("ignoreTips").asBoolean(), "只读查询需要显式告诉 NineData 已完成提示确认");
        assertEquals("NINEDATA_SKILL", sent.path("source").asText());
        assertEquals("md-agent-platform", sent.path("sourceClient").asText());
        assertEquals("sql-execute", sent.path("toolId").asText());
        assertEquals("1.2.2", sent.path("skillVersion").asText());
        assertEquals("enus", sent.path("language").asText());

        JsonNode result = JsonUtil.parse(output);
        assertTrue(result.path("success").asBoolean());
        assertEquals("EXECUTED", result.path("data").path("decision").asText(),
                "平台响应必须原样透出，模型才能按 decision 判断");
    }

    @Test
    void missingEnvironmentRejectsListDatasource() {
        String output = tool.listDatasource(null, null, null, null, null);

        JsonNode result = JsonUtil.parse(output);
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("environment 必填"),
                result.path("error").asText());
        assertEquals(0, client.callCount, "没有 environment 时不应发起请求");
    }

    @Test
    void blankEnvironmentRejectsSqlExecute() {
        String output = tool.sqlExecute("   ", "select 1", "ds_1", null, null,
                null, null, null, null, null);

        JsonNode result = JsonUtil.parse(output);
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("environment 必填"),
                result.path("error").asText());
        assertEquals(0, client.callCount, "空白 environment 不应发起请求");
    }

    @Test
    void unknownEnvironmentReturnsErrorWithAvailableNames() {
        String output = tool.listDatasource("not-exist", null, null, null, null);

        JsonNode result = JsonUtil.parse(output);
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("环境不存在"),
                result.path("error").asText());
        assertTrue(result.path("error").asText().contains(ENV),
                "错误信息必须列出可用环境: " + result.path("error").asText());
        assertEquals(0, client.callCount);
    }

    @Test
    void environmentOverridesEndpointAndCredentials() {
        registry.put("other-uat", envConfig("https://other.example.com", "ak-other", "sk-other",
                "other_db", null));
        client.response = JsonUtil.parse("{\"success\":true}");

        tool.listDatasource("other-uat", null, null, null, null);

        assertEquals("https://other.example.com", client.lastTarget.endpoint());
        assertEquals("ak-other", client.lastTarget.accessKeyId());
        assertEquals("sk-other", client.lastTarget.accessKeySecret());
    }

    @Test
    void explicitParametersOverrideEnvironmentDefaults() {
        client.response = JsonUtil.parse("{\"success\":true}");

        tool.sqlExecute(ENV, "select 2", "ds_x", "db_x", "schema_x", "cluster_x",
                "核对订单量", 3, 20, true);

        ObjectNode sent = client.lastBody;
        assertEquals("ds_x", sent.path("datasourceId").asText());
        assertEquals("db_x", sent.path("databaseName").asText());
        assertEquals("schema_x", sent.path("schemaName").asText());
        assertEquals("cluster_x", sent.path("clusterName").asText());
        assertEquals("核对订单量", sent.path("reason").asText());
        assertEquals(3, sent.path("current").asInt());
        assertEquals(20, sent.path("pageSize").asInt());
        assertTrue(sent.path("useSessionPersistence").asBoolean());
    }

    @Test
    void missingDatasourceIdFailsWithoutFallback() {
        String output = tool.sqlExecute(ENV, "select 1", null, null, null,
                null, null, null, null, null);

        JsonNode result = JsonUtil.parse(output);
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("datasourceId"),
                result.path("error").asText());
        assertEquals(0, client.callCount, "没有数据源 ID 时不应发起请求");
    }

    @Test
    void listDatasourceAppliesPagingDefaults() {
        client.response = JsonUtil.parse("{\"success\":true}");

        tool.listDatasource(ENV, "  MySQL  ", "  订单  ", null, 0);

        assertEquals("MySQL", client.lastDatasourceType);
        assertEquals("订单", client.lastKeyword);
        assertEquals(1, client.lastCurrent);
        assertEquals(50, client.lastPageSize, "pageSize <= 0 时回落到配置值");
    }

    @Test
    void environmentWithoutCredentialsReturnsError() {
        registry.put(ENV, envConfig(ENDPOINT, AK, null, null, null));

        String output = tool.listDatasource(ENV, null, null, null, null);

        JsonNode result = JsonUtil.parse(output);
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("accessKeySecret"),
                result.path("error").asText());
        assertEquals(0, client.callCount);
    }

    @Test
    void environmentWithoutEndpointReturnsError() {
        registry.put(ENV, envConfig(null, AK, SK, null, null));

        String output = tool.listDatasource(ENV, null, null, null, null);

        JsonNode result = JsonUtil.parse(output);
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("endpoint"),
                result.path("error").asText());
        assertEquals(0, client.callCount);
    }

    // ==================== 只读闸门 ====================

    @Test
    void rejectsMissingSqlBeforeCallingNineData() {
        String output = tool.sqlExecute(ENV, "   ", "ds_1", null, null,
                null, null, null, null, null);

        assertFalse(JsonUtil.parse(output).path("success").asBoolean());
        assertTrue(JsonUtil.parse(output).path("error").asText().contains("sql"));
        assertEquals(0, client.callCount);
    }

    @Test
    void rejectsNonReadOnlySqlBeforeCallingNineData() {
        String output = tool.sqlExecute(ENV, "update orders set status = 1", "ds_1", null,
                null, null, null, null, null, null);

        JsonNode result = JsonUtil.parse(output);
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("只读"));
        assertEquals(0, client.callCount, "非只读 SQL 不应到达 NineData");
    }

    @Test
    void acceptsLeadingCommentsBeforeSelect() {
        client.response = JsonUtil.parse("{\"success\":true}");

        String output = tool.sqlExecute(ENV, "-- 核对订单\n/* 只读 */\nSELECT 1", "ds_1", null,
                null, null, null, null, null, null);

        assertTrue(JsonUtil.parse(output).path("success").asBoolean());
        assertEquals(1, client.callCount);
        assertEquals("-- 核对订单\n/* 只读 */\nSELECT 1", client.lastBody.path("sql").asText(),
                "SQL 原文必须保留，不能因为校验被改写");
    }

    // ==================== 异常 ====================

    @Test
    void nineDataExceptionIsTurnedIntoStructuredError() {
        client.failure = new NineDataException(
                "NineData OpenAPI 请求失败: HTTP 500, boom", 500, "req-500",
                "{\"message\":\"boom\"}", null);

        String output = tool.sqlExecute(ENV, "select 1", "ds_1", null, null,
                null, null, null, null, null);

        JsonNode result = JsonUtil.parse(output);
        assertFalse(result.path("success").asBoolean());
        assertEquals(500, result.path("httpStatus").asInt());
        assertEquals("req-500", result.path("requestId").asText());
        assertTrue(result.path("response").asText().contains("boom"));
    }

    // ==================== 配置绑定 ====================

    @Test
    void propertiesBindOnlyEnvironmentIndependentSettings() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("ninedata.source-client", "md-agent-platform");
        source.put("ninedata.default-page-size", "100");
        source.put("ninedata.timeout-ms", "30000");
        source.put("ninedata.max-retries", "3");
        source.put("ninedata.retry-backoff-ms", "100");

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", source));
        NineDataProperties bound = Binder.get(environment)
                .bind("ninedata", NineDataProperties.class)
                .get();

        assertEquals("md-agent-platform", bound.getSourceClient());
        assertEquals(100, bound.getDefaultPageSize());
        assertEquals(30000, bound.getTimeoutMs());
        assertEquals(3, bound.getMaxRetries());
        assertEquals(100, bound.getRetryBackoffMs());
    }

    // ==================== AgentScope 注册 ====================

    @Test
    void registersWithAgentScopeAndMarksOnlyRequiredParameters() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        assertTrue(toolkit.getToolNames().containsAll(
                        Set.of("ninedata-list-datasource", "ninedata-sql-execute")),
                "两个 @Tool 必须都能被 AgentScope 注册: " + toolkit.getToolNames());

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        ToolSchema listSchema = schemaOf(schemas, "ninedata-list-datasource");
        ToolSchema executeSchema = schemaOf(schemas, "ninedata-sql-execute");

        assertEquals(Set.of("environment"), requiredOf(listSchema));
        assertEquals(Set.of("environment", "sql", "datasourceId"), requiredOf(executeSchema));

        Map<String, Object> properties = propertiesOf(executeSchema);
        assertTrue(properties.containsKey("environment"));
        assertTrue(properties.containsKey("sql"));
        assertTrue(properties.containsKey("datasourceId"));
        assertTrue(properties.containsKey("pageSize"));
        assertTrue(properties.containsKey("useSessionPersistence"));
        assertEquals("integer", typeOf(properties.get("pageSize")),
                "Integer 参数必须生成 JSON Schema integer");
        assertEquals("boolean", typeOf(properties.get("useSessionPersistence")),
                "Boolean 参数必须生成 JSON Schema boolean");
    }

    private static ToolSchema schemaOf(List<ToolSchema> schemas, String name) {
        return schemas.stream()
                .filter(schema -> name.equals(schema.getName()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parametersOf(ToolSchema schema) {
        return (Map<String, Object>) schema.getParameters();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(ToolSchema schema) {
        return (Map<String, Object>) parametersOf(schema).get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> requiredOf(ToolSchema schema) {
        Object required = parametersOf(schema).get("required");
        return required == null ? Set.of() : Set.copyOf((List<String>) required);
    }

    @SuppressWarnings("unchecked")
    private static String typeOf(Object schema) {
        return String.valueOf(((Map<String, Object>) schema).get("type"));
    }

    private static EnvironmentProperties.NineData envConfig(String endpoint, String accessKeyId,
                                                            String accessKeySecret,
                                                            String defaultDbName,
                                                            String defaultSchemaName) {
        EnvironmentProperties.NineData config = new EnvironmentProperties.NineData();
        config.setEndpoint(endpoint);
        config.setAccessKeyId(accessKeyId);
        config.setAccessKeySecret(accessKeySecret);
        config.setDefaultDbName(defaultDbName);
        config.setDefaultSchemaName(defaultSchemaName);
        return config;
    }

    // ==================== 测试夹具 ====================

    private static class StubEnvironmentRegistry extends EnvironmentRegistry {

        private final Map<String, EnvironmentProperties.NineData> configs = new LinkedHashMap<>();

        StubEnvironmentRegistry() {
            super(null);
        }

        void put(String environment, EnvironmentProperties.NineData config) {
            configs.put(environment, config);
        }

        @Override
        public EnvironmentProperties.NineData ninedata(String environment) {
            return ninedata(environment, null);
        }

        @Override
        public EnvironmentProperties.NineData ninedata(String environment, String instance) {
            EnvironmentProperties.NineData config = configs.get(environment);
            if (config == null) {
                throw new IllegalArgumentException(
                        "环境不存在: " + environment + "，可用环境: " + configs.keySet());
            }
            return config;
        }
    }

    private static class StubClient extends NineDataClient {

        JsonNode response = JsonUtil.parse("{\"success\":true}");
        NineDataException failure;
        ObjectNode lastBody;
        NineDataTarget lastTarget;
        String lastDatasourceType;
        String lastKeyword;
        Integer lastCurrent;
        Integer lastPageSize;
        int callCount;

        StubClient(NineDataProperties properties) {
            super(properties);
        }

        @Override
        public JsonNode listDatasources(NineDataTarget target, String datasourceType, String keyword,
                                        Integer current, Integer pageSize) {
            callCount++;
            lastTarget = target;
            lastDatasourceType = datasourceType;
            lastKeyword = keyword;
            lastCurrent = current;
            lastPageSize = pageSize;
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public JsonNode executeSql(NineDataTarget target, ObjectNode body) {
            callCount++;
            lastTarget = target;
            lastBody = body;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
