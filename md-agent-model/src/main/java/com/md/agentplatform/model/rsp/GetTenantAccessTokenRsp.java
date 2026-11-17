package com.md.agentplatform.model.rsp;

import lombok.Data;

@Data
public class GetTenantAccessTokenRsp {
    private int code;
    private int expire;
    private String msg;
    private String tenantAccessToken;
}
