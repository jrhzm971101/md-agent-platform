package com.md.agentplatform.server.tool.lark;

import com.md.agentplatform.server.service.LarkOperationService;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentRespBody;
import com.lark.oapi.service.docx.v1.model.Table;
import com.lark.oapi.service.docx.v1.model.TableProperty;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 飞书文档写入工具（多 Bot 感知）。
 */
@Slf4j
@Component
public class LarkMdConvertAndWriteToDocTool {

    @Autowired
    private LarkOperationService larkOperationService;

    @Tool(
            name = "lark-write-to-doc",
            description = "可以将文本内容写入到指定的飞书文档"
    )
    public String MakeMDToBlock(
            @ToolParam(name = "mdText", description = "需要写入飞书文档的内容", required = true) String mdText,
            @ToolParam(name = "documentId", description = "飞书文档的ID, 创建文档后会返回", required = true) String documentId,
            @ToolParam(name = "appName", description = "飞书 Bot 名，默认 default") String appName
    ) {
        if (appName == null || appName.isBlank()) {
            appName = "default";
        }
        log.info("开始将文本转换为block");
        ConvertDocumentRespBody respBody = larkOperationService.MdTextBlocksConvert(appName, mdText);
        Arrays.stream(respBody.getBlocks()).forEach(block -> {
            Table table = block.getTable();
            if (table != null) {
                TableProperty property = table.getProperty();
                property.setMergeInfo(null);
            }
        });
        log.info("开始写入doc");
        return larkOperationService.createBlockDescendant(appName, respBody, documentId);
    }
}
