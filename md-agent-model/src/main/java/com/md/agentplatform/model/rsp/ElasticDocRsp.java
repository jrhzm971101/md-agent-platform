package com.md.agentplatform.model.rsp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
//@JsonIgnoreProperties(ignoreUnknown = true)
public class ElasticDocRsp {

    private String id;
    private String title;
    private String content;
    private String summary;              // LLM 生成的文档级摘要
    private String parentDocId;          // 父文档 ID，同文档所有 chunk 共享
    private Integer chunkIndex;          // chunk 在原文档中的序号
    private String channel;              // 来源渠道：wiki / upload / lark / workwx / web
    private List<String> tags;           // 跨渠道统一标签
    private String sourceFileName;
    private String sourceUrl;
    private Integer pageNumber;
    private String mediaType;

    // ===== Wiki 空间文档扩展字段 =====
    private String docType;
    private String confluenceId;
    private String spaceKey;
    private String spaceId;
    private String spaceName;
    private String versionNumber;
    private String lastModified;
    private List<String> allowedUserKeys;
    private List<String> allowedGroupNames;
}
