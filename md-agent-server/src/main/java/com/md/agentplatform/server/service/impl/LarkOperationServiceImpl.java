package com.md.agentplatform.server.service.impl;

import com.md.agentplatform.model.agent.tool.CreateDocRequest;
import com.md.agentplatform.model.agent.tool.CreateDocRsp;
import com.md.agentplatform.model.agent.tool.ReplayMsgRequest;
import com.md.agentplatform.model.agent.tool.SendMsgRequest;
import com.md.agentplatform.model.feishu.CardMsg;
import com.md.agentplatform.model.feishu.Data;
import com.md.agentplatform.model.feishu.FeiShuText;
import com.md.agentplatform.model.rsp.GetTenantAccessTokenRsp;
import com.md.agentplatform.model.rsp.SearchWikiRsp;
import com.md.agentplatform.server.messaging.BotInstance;
import com.md.agentplatform.server.messaging.BotInstanceManager;
import com.md.agentplatform.server.messaging.config.FeishuBotCredentials;
import com.md.agentplatform.server.messaging.feishu.FeishuBotInstance;
import com.md.agentplatform.server.service.LarkOperationService;
import com.md.agentplatform.server.tools.JsonSchemaConstant;
import com.md.agentplatform.server.util.JsonUtil;
import com.lark.oapi.Client;
import com.lark.oapi.core.response.BaseResponse;
import com.lark.oapi.service.cardkit.v1.model.*;
import com.lark.oapi.service.docx.v1.model.*;
import com.lark.oapi.service.im.v1.model.*;
import com.lark.oapi.service.search.v2.model.DocMeta;
import com.lark.oapi.service.search.v2.model.SearchDocWikiRespBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.md.agentplatform.server.tools.JsonSchemaConstant.toJson;

/**
 * 飞书消息/卡片/文档操作实现（多 Bot 感知，裁剪自原 LarkOperationServiceImpl）。
 *
 * <p>已剔除：视频生成回调、入群欢迎等依赖媒体/存储/AI 服务的能力。
 */
@Component
public class LarkOperationServiceImpl implements LarkOperationService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /** @Lazy 打破 BotInstanceManager → MessageRouter → 本服务 → BotInstanceManager 的循环。 */
    @Lazy
    @Autowired
    private BotInstanceManager botInstanceManager;

    private static final String DEFAULT_CARD_JSON = "{\"schema\":\"2.0\",\"config\":{\"streaming_mode\":true,\"streaming_config\":{\"print_frequency_ms\":{\"default\":10,\"android\":10,\"ios\":10,\"pc\":10},\"print_step\":{\"default\":10,\"android\":10,\"ios\":10,\"pc\":10},\"print_strategy\":\"fast\"},\"update_multi\":true,\"style\":{\"text_size\":{\"normal_v2\":{\"default\":\"normal\",\"pc\":\"normal\",\"mobile\":\"heading\"}}}},\"body\":{\"direction\":\"vertical\",\"horizontal_spacing\":\"8px\",\"vertical_spacing\":\"8px\",\"horizontal_align\":\"left\",\"vertical_align\":\"top\",\"padding\":\"12px 12px 12px 12px\",\"elements\":[{\"tag\":\"column_set\",\"flex_mode\":\"flow\",\"horizontal_spacing\":\"0px\",\"horizontal_align\":\"left\",\"columns\":[{\"tag\":\"column\",\"width\":\"72px\",\"elements\":[{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"\"},\"type\":\"default\",\"width\":\"default\",\"size\":\"medium\",\"icon\":{\"tag\":\"standard_icon\",\"token\":\"refresh_outlined\"},\"hover_tips\":{\"tag\":\"plain_text\",\"content\":\"重新生成\"},\"margin\":\"0px 0px 0px 0px\"}],\"vertical_spacing\":\"8px\",\"horizontal_align\":\"left\",\"vertical_align\":\"top\"},{\"tag\":\"column\",\"width\":\"72px\",\"elements\":[{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"\"},\"type\":\"default\",\"width\":\"default\",\"size\":\"medium\",\"icon\":{\"tag\":\"standard_icon\",\"token\":\"unmaximize-win_outlined\"},\"hover_tips\":{\"tag\":\"plain_text\",\"content\":\"复制文本\"},\"margin\":\"0px 0px 0px 0px\"}],\"vertical_spacing\":\"8px\",\"horizontal_align\":\"left\",\"vertical_align\":\"top\"},{\"tag\":\"column\",\"width\":\"72px\",\"elements\":[{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"\"},\"type\":\"default\",\"width\":\"default\",\"size\":\"medium\",\"icon\":{\"tag\":\"standard_icon\",\"token\":\"thumbsup_outlined\"},\"hover_tips\":{\"tag\":\"plain_text\",\"content\":\"对我有用\"},\"margin\":\"0px 0px 0px 0px\"}],\"vertical_spacing\":\"8px\",\"horizontal_align\":\"left\",\"vertical_align\":\"top\"},{\"tag\":\"column\",\"width\":\"72px\",\"elements\":[{\"tag\":\"overflow\",\"width\":\"default\",\"options\":[{\"text\":{\"tag\":\"plain_text\",\"content\":\"深度思考\"}},{\"text\":{\"tag\":\"plain_text\",\"content\":\"分享内容\"}}]}],\"vertical_spacing\":\"8px\",\"horizontal_align\":\"left\",\"vertical_align\":\"top\"}],\"margin\":\"8px 8px 8px 8px\",\"element_id\":\"button\"}]},\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"Aimian助手\"},\"subtitle\":{\"tag\":\"plain_text\",\"content\":\"正在回答中...\"},\"template\":\"purple\",\"icon\":{\"tag\":\"standard_icon\",\"token\":\"ai-common_colorful\"},\"padding\":\"16px 12px 16px 24px\"}}";

    private final ConcurrentHashMap<String, AtomicInteger> cardSequences = new ConcurrentHashMap<>();

    private Client resolveClient(String appName) {
        BotInstance b = botInstanceManager.get(appName);
        if (b instanceof FeishuBotInstance fb && fb.getClient() != null) {
            return fb.getClient();
        }
        throw new IllegalStateException("Feishu bot 不存在或未就绪: " + appName);
    }

    private FeishuBotCredentials resolveConfig(String appName) {
        BotInstance b = botInstanceManager.get(appName);
        if (b instanceof FeishuBotInstance fb) {
            return fb.getConfig();
        }
        throw new IllegalStateException("Feishu bot 不存在: " + appName);
    }

    @Override
    public int nextSeq(String cardId) {
        return cardSequences.computeIfAbsent(cardId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    @Override
    public void removeCardSequence(String cardId) {
        cardSequences.remove(cardId);
    }

    @Override
    public void replyMsg(String appName, ReplayMsgRequest request, String messageId) {
        Client larkClient = resolveClient(appName);
        FeiShuText text = new FeiShuText(request.msg());

        ReplyMessageReq req = ReplyMessageReq.newBuilder()
                .messageId(messageId)
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .content(toJson(text))
                        .msgType("text")
                        .build())
                .build();

        ReplyMessageResp resp = new ReplyMessageResp();
        try {
            resp = larkClient.im().message().reply(req);
        } catch (Exception e) {
            logger.error("发送飞书消息失败: {}", e.getMessage());
        }

        if (!resp.success()) {
            logger.error("回复飞书消息失败, code:{}, msg:{}, reqId:{}, body:{}",
                    resp.getCode(), resp.getMsg(), resp.getRequestId(),
                    JsonUtil.pretty(new String(resp.getRawResponse().getBody(), StandardCharsets.UTF_8)));
        }
    }

    @Override
    public void sendMsg(String appName, SendMsgRequest request) {
        Client larkClient = resolveClient(appName);
        String msg = request.msg();
        if ("text".equals(request.msgType())) {
            msg = toJson(new FeiShuText(msg));
        }

        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType(request.receiveIdType())
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(request.identification())
                        .msgType(request.msgType())
                        .content(msg)
                        .uuid(UUID.randomUUID().toString())
                        .build())
                .build();

        CreateMessageResp resp = new CreateMessageResp();
        try {
            resp = larkClient.im().v1().message().create(req);
        } catch (Exception e) {
            throw new RuntimeException("发送飞书消息失败: " + e.getMessage());
        }
        if (!resp.success()) {
            throw new RuntimeException("发送飞书消息失败: " + resp.getMsg());
        }
        logger.info("发送飞书消息成功");
    }

    @Override
    public String defaultCardCreate(String appName) {
        Client larkClient = resolveClient(appName);
        CreateCardReq req = CreateCardReq.newBuilder()
                .createCardReqBody(CreateCardReqBody.newBuilder()
                        .type("card_json")
                        .data(DEFAULT_CARD_JSON)
                        .build())
                .build();

        CreateCardResp resp;
        try {
            resp = larkClient.cardkit().v1().card().create(req);
        } catch (Exception e) {
            throw new RuntimeException("创建飞书卡片失败: " + e.getMessage(), e);
        }
        if (!resp.success()) {
            throw new RuntimeException(String.format("创建飞书卡片失败, code:%s, msg:%s, reqId:%s, body:%s",
                    resp.getCode(), resp.getMsg(), resp.getRequestId(),
                    JsonUtil.pretty(new String(resp.getRawResponse().getBody(), StandardCharsets.UTF_8))));
        }
        return resp.getData().getCardId();
    }

    @Override
    public String replayCardByMessageId(String appName, String messageId) {
        Client larkClient = resolveClient(appName);
        String cardId = this.defaultCardCreate(appName);

        CardMsg cardMsg = new CardMsg();
        cardMsg.setType("card");
        cardMsg.setData(new Data(cardId));

        ReplyMessageReq req = ReplyMessageReq.newBuilder()
                .messageId(messageId)
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .content(toJson(cardMsg))
                        .msgType("interactive")
                        .build())
                .build();

        ReplyMessageResp resp;
        try {
            resp = larkClient.im().message().reply(req);
        } catch (Exception e) {
            throw new RuntimeException("回复飞书卡片消息失败: " + e.getMessage(), e);
        }
        if (!resp.success()) {
            throw new RuntimeException(String.format("回复飞书卡片消息失败, code:%s, msg:%s, reqId:%s, body:%s",
                    resp.getCode(), resp.getMsg(), resp.getRequestId(),
                    JsonUtil.pretty(new String(resp.getRawResponse().getBody(), StandardCharsets.UTF_8))));
        }
        return cardId;
    }

    @Override
    public void streamUpdateCardElement(String appName, String cardId, String elementId, String content) {
        Client larkClient = resolveClient(appName);
        int seq = nextSeq(cardId);
        ContentCardElementReq req = ContentCardElementReq.newBuilder()
                .cardId(cardId)
                .elementId(elementId)
                .contentCardElementReqBody(ContentCardElementReqBody.newBuilder()
                        .content(content)
                        .sequence(seq)
                        .build())
                .build();
        ContentCardElementResp resp;
        try {
            resp = larkClient.cardkit().v1().cardElement().content(req);
        } catch (Exception e) {
            throw new RuntimeException("流式更新卡片元素失败: " + e.getMessage(), e);
        }
        if (!resp.success()) {
            throw new RuntimeException(String.format(
                    "流式更新卡片元素失败, cardId:%s, elementId:%s, seq:%d, code:%s, msg:%s, reqId:%s",
                    cardId, elementId, seq, resp.getCode(), resp.getMsg(), resp.getRequestId()));
        }
    }

    @Override
    public void addElementToCard(String appName, String cardId, String element, String uuid) {
        Client larkClient = resolveClient(appName);
        int seq1 = nextSeq(cardId);
        int seq2 = nextSeq(cardId);

        CreateCardElementReq updateReq = CreateCardElementReq.newBuilder()
                .cardId(cardId)
                .createCardElementReqBody(CreateCardElementReqBody.newBuilder()
                        .uuid(uuid)
                        .type("append")
                        .sequence(seq1)
                        .elements(element)
                        .build())
                .build();

        CreateCardElementReq addHrReq = CreateCardElementReq.newBuilder()
                .cardId(cardId)
                .createCardElementReqBody(CreateCardElementReqBody.newBuilder()
                        .uuid(uuid + "_hr")
                        .type("append")
                        .sequence(seq2)
                        .elements("[{\"tag\":\"hr\",\"margin\":\"8px 0px 8px 0px\"}]")
                        .build())
                .build();

        CreateCardElementResp rsp;
        CreateCardElementResp addHr;
        try {
            rsp = larkClient.cardkit().v1().cardElement().create(updateReq);
            addHr = larkClient.cardkit().v1().cardElement().create(addHrReq);
        } catch (Exception e) {
            throw new RuntimeException("添加卡片组件失败, cardId:" + cardId + ", uuid:" + uuid
                    + ", seq:" + seq1 + "/" + seq2 + " — " + e.getMessage(), e);
        }
        if (!rsp.success()) {
            throw new RuntimeException(String.format(
                    "添加卡片元素失败, cardId:%s, uuid:%s, seq:%d, code:%s, msg:%s, reqId:%s",
                    cardId, uuid, seq1, rsp.getCode(), rsp.getMsg(), rsp.getRequestId()));
        }
        if (!addHr.success()) {
            throw new RuntimeException(String.format(
                    "添加分割线失败, cardId:%s, uuid:%s, seq:%d, code:%s, msg:%s, reqId:%s",
                    cardId, uuid + "_hr", seq2, addHr.getCode(), addHr.getMsg(), addHr.getRequestId()));
        }
    }

    @Override
    public CreateDocRsp createDoc(String appName, CreateDocRequest createDocRequest) {
        Client larkClient = resolveClient(appName);
        CreateDocumentReq req = CreateDocumentReq.newBuilder()
                .createDocumentReqBody(CreateDocumentReqBody.newBuilder()
                        .folderToken(createDocRequest.getFolder_token())
                        .title(createDocRequest.getTitle())
                        .build())
                .build();

        CreateDocumentResp resp;
        try {
            resp = larkClient.docx().v1().document().create(req);
        } catch (Exception e) {
            throw new RuntimeException("创建飞书文档失败: " + e.getMessage(), e);
        }
        if (!resp.success()) {
            throw new RuntimeException(String.format("创建飞书文档失败, code:%s, msg:%s, reqId:%s, body:%s",
                    resp.getCode(), resp.getMsg(), resp.getRequestId(),
                    JsonUtil.pretty(new String(resp.getRawResponse().getBody(), StandardCharsets.UTF_8))));
        }

        CreateDocRsp docRsp = new CreateDocRsp();
        docRsp.setDocumentId(resp.getData().getDocument().getDocumentId());
        docRsp.setTitle(resp.getData().getDocument().getTitle());
        return docRsp;
    }

    @Override
    public ConvertDocumentRespBody MdTextBlocksConvert(String appName, String mdText) {
        Client larkClient = resolveClient(appName);
        ConvertDocumentReq req = ConvertDocumentReq.newBuilder()
                .convertDocumentReqBody(ConvertDocumentReqBody.newBuilder()
                        .contentType("markdown")
                        .content(mdText)
                        .build())
                .build();

        ConvertDocumentResp resp;
        try {
            resp = larkClient.docx().v1().document().convert(req);
        } catch (Exception e) {
            throw new RuntimeException("Markdown 转文档 Blocks 失败: " + e.getMessage(), e);
        }
        if (!resp.success()) {
            throw new RuntimeException(String.format("Markdown 转文档 Blocks 失败, code:%s, msg:%s, reqId:%s, body:%s",
                    resp.getCode(), resp.getMsg(), resp.getRequestId(),
                    JsonUtil.pretty(new String(resp.getRawResponse().getBody(), StandardCharsets.UTF_8))));
        }
        return resp.getData();
    }

    @Override
    public String createBlockDescendant(String appName, ConvertDocumentRespBody resp, String docId) {
        Client larkClient = resolveClient(appName);
        Arrays.stream(resp.getBlocks()).forEach(block -> {
            Table table = block.getTable();
            if (table != null) {
                TableProperty property = table.getProperty();
                property.setMergeInfo(null);
            }
        });

        CreateDocumentBlockDescendantReq createDocumentBlockDescendantReq = CreateDocumentBlockDescendantReq.newBuilder()
                .documentId(docId)
                .blockId(docId)
                .documentRevisionId(-1)
                .createDocumentBlockDescendantReqBody(
                        CreateDocumentBlockDescendantReqBody.newBuilder()
                                .childrenId(resp.getFirstLevelBlockIds())
                                .descendants(resp.getBlocks())
                                .build()
                )
                .build();

        CreateDocumentBlockDescendantResp descendantResp;
        try {
            descendantResp = larkClient.docx().v1().documentBlockDescendant().create(createDocumentBlockDescendantReq);
        } catch (Exception e) {
            throw new RuntimeException("写入文档 Block 失败: " + e.getMessage(), e);
        }
        if (!descendantResp.success()) {
            return String.format("code:%s,msg:%s,reqId:%s",
                    descendantResp.getCode(), descendantResp.getMsg(), descendantResp.getRequestId());
        }
        return "写入飞书文档成功";
    }

    @Override
    public List<SearchWikiRsp> searchWiki(String appName, String query) {
        FeishuBotCredentials config = resolveConfig(appName);
        RestTemplate restTemplate = new RestTemplate();
        List<SearchWikiRsp> searchWikiRsps = new ArrayList<>();

        try {
            HttpHeaders header = new HttpHeaders();
            header.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            Map<String, String> paramMap = new HashMap<>();
            paramMap.put("app_id", config.appId());
            paramMap.put("app_secret", config.appSecret());

            HttpEntity<Map<String, String>> request1 = new HttpEntity<>(paramMap, header);
            ResponseEntity<String> response1 =
                    restTemplate.exchange(config.baseUrl() + "/open-apis/auth/v3/tenant_access_token/internal",
                            HttpMethod.POST, request1, String.class);

            GetTenantAccessTokenRsp getTenantAccessTokenRsp =
                    JsonSchemaConstant.jsonToObject(response1.getBody(), GetTenantAccessTokenRsp.class);

            String url = config.baseUrl() + "/open-apis/search/v2/doc_wiki/search";
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + getTenantAccessTokenRsp.getTenantAccessToken());
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> docFilter = new HashMap<>();
            docFilter.put("folder_tokens", Collections.singletonList("FkuJfF3g8laOrGd4OEicygdOnUb"));
            requestMap.put("doc_filter", docFilter);
            requestMap.put("page_size", 15);
            requestMap.put("query", query);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestMap, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);

            BaseResponse baseResponse = JsonSchemaConstant.jsonToObject(response.getBody(), BaseResponse.class);
            SearchDocWikiRespBody searchDocWikiRespBody =
                    JsonSchemaConstant.jsonToObject(JsonSchemaConstant.toJson(baseResponse.getData()),
                            SearchDocWikiRespBody.class);

            if (searchDocWikiRespBody.getResUnits() != null) {
                Arrays.stream(searchDocWikiRespBody.getResUnits()).forEach(resUnit -> {
                    DocMeta resultMeta = resUnit.getResultMeta();
                    searchWikiRsps.add(SearchWikiRsp.builder()
                            .fileUrl(resultMeta.getUrl())
                            .fileToken(resultMeta.getToken())
                            .fileTitle(resUnit.getTitleHighlighted())
                            .build());
                });
            }
        } catch (Exception e) {
            logger.error("搜索飞书文档失败, query={}, error={}", query, e.getMessage(), e);
        }
        return searchWikiRsps;
    }
}
