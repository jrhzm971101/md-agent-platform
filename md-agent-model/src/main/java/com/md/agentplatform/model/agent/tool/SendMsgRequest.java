package com.md.agentplatform.model.agent.tool;

import lombok.Builder;

@Builder
public record SendMsgRequest(
        String msg,
        String identification,
        String msgType,
        String receiveIdType
) {
}
