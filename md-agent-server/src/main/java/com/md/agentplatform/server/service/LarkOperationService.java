package com.md.agentplatform.server.service;

import com.md.agentplatform.model.agent.tool.CreateDocRequest;
import com.md.agentplatform.model.agent.tool.CreateDocRsp;
import com.md.agentplatform.model.agent.tool.ReplayMsgRequest;
import com.md.agentplatform.model.agent.tool.SendMsgRequest;
import com.md.agentplatform.model.rsp.SearchWikiRsp;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentRespBody;

import java.util.List;

/**
 * 飞书消息/卡片/文档操作接口（多 Bot 感知）。
 *
 * <p>所有依赖 Lark Client 的方法都以 {@code appName} 定位目标飞书 Bot。
 */
public interface LarkOperationService {

    void replyMsg(String appName, ReplayMsgRequest request, String messageId);

    void sendMsg(String appName, SendMsgRequest request);

    String defaultCardCreate(String appName);

    String replayCardByMessageId(String appName, String messageId);

    /** 为指定卡片分配下一个递增序列号（按 cardId 隔离，与 app 无关）。 */
    int nextSeq(String cardId);

    /** 卡片会话结束时清理序列号计数器，防止内存泄漏。 */
    void removeCardSequence(String cardId);

    void streamUpdateCardElement(String appName, String cardId, String elementId, String content);

    void addElementToCard(String appName, String cardId, String element, String uuid);

    CreateDocRsp createDoc(String appName, CreateDocRequest createDocRequest);

    ConvertDocumentRespBody MdTextBlocksConvert(String appName, String mdText);

    String createBlockDescendant(String appName, ConvertDocumentRespBody resp, String docId);

    List<SearchWikiRsp> searchWiki(String appName, String query);
}
