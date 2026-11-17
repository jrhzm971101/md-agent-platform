package com.md.agentplatform.server.tool.rag;

import com.md.agentplatform.server.service.IntelligentRetrievalService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RagIntelligentRetrievalTool {

    @Autowired
    private IntelligentRetrievalService intelligentRetrievalService;

    @Tool(
            name = "intelligent-retrieval",
            description = "内部知识库查询工具，支持基于用户权限的文档检索"
    )
    public String search(
            @ToolParam(name = "query", description = "需要进行查询的问题") String query,
            UserContext userCtx) {
        log.info("开始调用内部知识库查询, userId={}",
                userCtx != null ? userCtx.userId() : null);
        String userKey = userCtx != null ? userCtx.userKey() : null;
        return intelligentRetrievalService.search(query, userKey);
    }
}
