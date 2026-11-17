package com.md.agentplatform.server.service;

public interface IntelligentRetrievalService {

    /** 检索知识库（无权限过滤，向后兼容）。 */
    String search(String query);

    /** 检索知识库，带用户权限过滤（userKey 为 null 时不作过滤）。 */
    String search(String query, String userKey);
}
