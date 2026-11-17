package com.md.agentplatform.server.tool.lark;

import com.md.agentplatform.model.rsp.SearchWikiRsp;
import com.md.agentplatform.server.service.LarkOperationService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 飞书维基搜索工具（多 Bot 感知）。
 */
@Component
public class LarkSearchWikiTool {

    @Autowired
    private LarkOperationService larkOperationService;

    @Tool(
            name = "lark-search-wiki",
            description = "可以根据关键词进行飞书文档检索, 查询到相关文档, 并且输出文档的链接"
    )
    public String searchWiki(
            @ToolParam(name = "query", description = "需要查询的关键字或长句内容") String query,
            @ToolParam(name = "appName", description = "飞书 Bot 名，默认 default") String appName
    ) {
        if (appName == null || appName.isBlank()) {
            appName = "default";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("查询到如下飞书文档地址: ").append("\n");
        List<SearchWikiRsp> searchWikiRsps = larkOperationService.searchWiki(appName, query);
        searchWikiRsps.forEach(searchWikiRsp ->
                builder.append(searchWikiRsp.getFileUrl()).append("\n"));
        return builder.toString();
    }
}
