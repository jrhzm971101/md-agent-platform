package com.md.agentplatform.server.tool.ninedata;

/**
 * 一次 NineData 调用所使用的目标地址与凭据。
 *
 * <p>地址和 AK/SK 只来自环境目录里每个环境自己的 {@code ninedata} 分组，没有全局兜底。
 * 客户端只认这个值对象，不再直接读 {@code NineDataProperties}，
 * 避免"某个环境显式指定了地址，结果客户端仍拿别的环境配置去请求"这类串环境问题。
 *
 * @param endpoint        OpenAPI 根地址（不含 /openapi 路径）
 * @param accessKeyId     AccessKeyId
 * @param accessKeySecret AccessKeySecret，仅参与本地签名
 */
public record NineDataTarget(String endpoint, String accessKeyId, String accessKeySecret) {

    /** 三项都非空才算可用。 */
    public boolean isComplete() {
        return endpoint != null && !endpoint.isBlank()
                && accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank();
    }
}
