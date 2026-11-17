package com.md.agentplatform.model.agent.tool;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateDocRequest {
    private String title;
    private String folder_token;
}
