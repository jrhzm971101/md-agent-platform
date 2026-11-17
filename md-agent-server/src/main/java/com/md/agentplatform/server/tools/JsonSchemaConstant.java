package com.md.agentplatform.server.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON 序列化 / 飞书卡片 schema 常量工具。
 */
public class JsonSchemaConstant {

    public final static String DEFAULT_ELEMENT =
            "[{\"tag\":\"markdown\",\"content\":\"\",\"text_align\":\"left\",\"text_size\":\"normal_v2\",\"margin\":\"0px 0px 0px 0px\",\"element_id\":\"%s\"}]";

    public static String toJson(Object obj) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.disable(SerializationFeature.WRAP_ROOT_VALUE);
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    public static <T> T jsonToObject(String json, Class<T> clazz) {
        if (json == null || json.isBlank() || clazz == null) {
            throw new IllegalArgumentException("JSON字符串或目标Class不能为空");
        }
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static String getDefaultElement(String elementId) {
        if (elementId == null || elementId.trim().isEmpty()) {
            throw new IllegalArgumentException("elementId 不能为空！");
        }
        return String.format(DEFAULT_ELEMENT, elementId);
    }
}
