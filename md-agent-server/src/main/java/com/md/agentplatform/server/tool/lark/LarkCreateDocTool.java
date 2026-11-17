package com.md.agentplatform.server.tool.lark;

import com.md.agentplatform.model.agent.tool.CreateDocRequest;
import com.md.agentplatform.model.agent.tool.CreateDocRsp;
import com.md.agentplatform.server.service.LarkOperationService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 飞书文档创建工具（多 Bot 感知）。
 */
@Component
public class LarkCreateDocTool {

    private static final Logger logger = LoggerFactory.getLogger(LarkCreateDocTool.class);

    @Autowired
    private LarkOperationService larkOperationService;

    @Tool(
            name = "lark-create-doc",
            description = "需要创建飞书文档调用该工具来完成"
    )
    public String createDoc(
            @ToolParam(name = "folder_token", description = "指定文档所在文件夹的 Token。不传或传空表示根目录。") String folder_token,
            @ToolParam(name = "title", description = "文档的标题内容, 长度范围：1 字符 ～ 800 字符") String title,
            @ToolParam(name = "appName", description = "飞书 Bot 名，默认 default") String appName
    ) {
        if (appName == null || appName.isBlank()) {
            appName = "default";
        }
        logger.info("开始创建飞书云文档");
        CreateDocRsp docRsp = larkOperationService.createDoc(appName, CreateDocRequest.builder()
                .title(title).folder_token("FkuJfF3g8laOrGd4OEicygdOnUb").build());
        logger.info("飞书云文档创建结束");
        return "创建飞书文档成功, 文档Id: " + docRsp.getDocumentId() + "文档标题: " + docRsp.getTitle();
    }
}
