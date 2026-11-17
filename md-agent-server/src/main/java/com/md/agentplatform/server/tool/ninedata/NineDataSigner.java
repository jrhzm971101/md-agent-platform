package com.md.agentplatform.server.tool.ninedata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * NineData OpenAPI 签名工具。
 *
 * <p>NineData 的签名规则（见 references/openapi-auth.md）:
 * <pre>
 * signature = SHA256(path + "/" + AccessKeySecret + "&" + timestamp)
 * </pre>
 * 其中 {@code path} 是<b>不含 query</b> 的请求路径，{@code timestamp} 是 UTC 的
 * {@code yyyy-MM-dd'T'HH:mm:ss'Z'} 字符串。签名结果是小写十六进制。
 *
 * <p>独立成类而不是塞进客户端，是为了让「时间格式」和「签名串拼接」能被单测直接覆盖 ——
 * 这两处一旦写错，表现为 NineData 统一返回鉴权失败，肉眼很难定位。
 */
public final class NineDataSigner {

    /** 必须输出字面量 Z，不能用 OffsetDateTime 的 +00:00。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private NineDataSigner() {
    }

    /** 生成签名用的 UTC 时间戳；{@code instant} 为空时取当前时间。 */
    public static String timestamp(Instant instant) {
        return TIMESTAMP_FORMAT.format(instant == null ? Instant.now() : instant);
    }

    /** 按 NineData 规则计算签名。 */
    public static String sign(String path, String accessKeySecret, String timestamp) {
        return sha256Hex(path + "/" + accessKeySecret + "&" + timestamp);
    }

    /** UTF-8 文本的 SHA-256 小写十六进制。 */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}