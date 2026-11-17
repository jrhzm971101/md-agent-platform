package com.md.agentplatform.model.rsp;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchWikiRsp {
    String fileUrl;
    String fileTitle;
    String fileToken;
}
