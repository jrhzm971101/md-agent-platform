package com.md.agentplatform.server.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 项目统一 JSON 工具（Jackson）。
 *
 * <p>取代此前分散的 Gson 用法，收敛为单一共享 {@link ObjectMapper}。
 * 所有方法在解析/序列化失败时抛出 {@link IllegalArgumentException}，
 * 由调用方按需捕获（与 Gson 的宽松语义不同，但更利于尽早暴露坏数据）。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(SerializationFeature.WRAP_ROOT_VALUE);

    private JsonUtil() {
    }

    public static ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode newArray() {
        return MAPPER.createArrayNode();
    }

    /** 解析为 JSON 树；失败抛 {@link IllegalArgumentException}。 */
    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 解析为 {@code Map<String, String>}（替代 Gson 的 {@code TypeToken<Map<String,String>>}）。 */
    public static Map<String, String> parseStringMap(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析为 Map 失败: " + e.getMessage(), e);
        }
    }

    /** 序列化任意对象为 JSON 字符串；失败抛 {@link IllegalArgumentException}。 */
    public static String stringify(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /** 任意对象 → {@link JsonNode}（替代 Gson 的 {@code JsonParser.parse} + 手动建节点）。 */
    public static JsonNode toTree(Object obj) {
        return MAPPER.valueToTree(obj);
    }

    /** 美化 JSON 用于日志；解析失败时回退原文，保证日志永不因坏 JSON 抛错。 */
    public static String pretty(String raw) {
        try {
            return MAPPER.readTree(raw).toPrettyString();
        } catch (Exception e) {
            return raw;
        }
    }
}
