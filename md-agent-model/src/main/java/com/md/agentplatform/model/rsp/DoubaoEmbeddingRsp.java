package com.md.agentplatform.model.rsp;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class DoubaoEmbeddingRsp {

    private Float[] denseEmbeddings;
    private Map<String, Float> spareEmbeddings;
}
