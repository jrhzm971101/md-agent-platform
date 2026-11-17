package com.md.agentplatform.server.service;

import com.md.agentplatform.model.rsp.DoubaoEmbeddingRsp;

/**
 * 向量化服务（裁剪自原 AIOperationService，仅保留检索所需的能力）。
 */
public interface EmbeddingService {

    /** 文本向量化，返回稠密 + 稀疏向量。 */
    DoubaoEmbeddingRsp getVectorByText(String text);
}
