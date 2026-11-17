package com.md.agentplatform.server.tool.ninedata;

/**
 * NineData OpenAPI 调用异常。
 *
 * <p>保留 HTTP 状态码、平台 {@code requestId} 与原始响应体，是为了让工具层能把
 * 「是哪一次请求失败、NineData 怎么说的」原样交给模型和用户；
 * 只抛一句 {@code I/O error} 会让排障退化成猜。
 */
public class NineDataException extends RuntimeException {

    private final Integer httpStatus;
    private final String requestId;
    private final String responseBody;

    public NineDataException(String message) {
        this(message, null, null, null, null);
    }

    public NineDataException(String message, Integer httpStatus, String requestId,
                             String responseBody, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.requestId = requestId;
        this.responseBody = responseBody;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getResponseBody() {
        return responseBody;
    }
}