package com.md.agentplatform.server.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilTest {

    @Test
    void parseAndReadFields() {
        JsonNode node = JsonUtil.parse("{\"url\":\"https://x\",\"n\":3}");
        assertEquals("https://x", node.get("url").asText());
        assertEquals(3, node.get("n").asInt());
    }

    @Test
    void parseStringMap() {
        Map<String, String> m = JsonUtil.parseStringMap("{\"text\":\"hello\"}");
        assertEquals("hello", m.get("text"));
    }

    @Test
    void stringifyRoundTrip() {
        assertEquals("{\"a\":\"1\"}", JsonUtil.stringify(JsonUtil.newObject().put("a", "1")));
    }

    @Test
    void prettyFallsBackToRawOnInvalidJson() {
        assertEquals("not json", JsonUtil.pretty("not json"));
    }

    @Test
    void prettyFormatsValidJson() {
        assertTrue(JsonUtil.pretty("{\"a\":1}").contains("\n"));
    }

    @Test
    void parseThrowsOnInvalid() {
        assertThrows(IllegalArgumentException.class, () -> JsonUtil.parse("{bad"));
    }
}
