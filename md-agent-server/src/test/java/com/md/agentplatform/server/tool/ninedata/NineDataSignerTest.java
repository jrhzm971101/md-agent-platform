package com.md.agentplatform.server.tool.ninedata;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NineData 签名规则测试。
 *
 * <p>签名错误在 NineData 侧只会表现为统一的鉴权失败，无法从响应反推是哪一位错了，
 * 所以这里把「时间格式」和「签名串拼接」都钉死在已知向量上。
 */
class NineDataSignerTest {

    @Test
    void timestampUsesUtcLiteralZ() {
        assertEquals("2026-10-29T02:30:00Z",
                NineDataSigner.timestamp(Instant.parse("2026-10-29T02:30:00Z")));
        assertEquals("2026-10-29T10:30:00Z",
                NineDataSigner.timestamp(Instant.parse("2026-10-29T18:30:00+08:00")),
                "必须换算成 UTC，而不是直接截掉时区");
    }

    @Test
    void signatureMatchesDocumentedAlgorithm() {
        // SHA256("/openapi/v1/sql/execute" + "/" + "test-secret" + "&" + timestamp)
        assertEquals("603a049fb5e2a582e3cf778ac051fdbd793b507eb2f6d359c0f920dbc5045fd1",
                NineDataSigner.sign("/openapi/v1/sql/execute", "test-secret", "2026-10-29T02:30:00Z"));
    }

    @Test
    void signatureIgnoresNothingButPathSecretAndTimestamp() {
        String expected = NineDataSigner.sha256Hex(
                "/openapi/v1/datasource/list/ak-secret&2026-10-29T02:30:00Z");
        assertEquals(expected,
                NineDataSigner.sign("/openapi/v1/datasource/list", "ak-secret", "2026-10-29T02:30:00Z"));
    }
}