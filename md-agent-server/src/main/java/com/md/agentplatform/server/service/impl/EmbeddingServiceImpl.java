package com.md.agentplatform.server.service.impl;

import com.md.agentplatform.model.rsp.DoubaoEmbeddingRsp;
import com.md.agentplatform.server.config.ModelConfigProperties;
import com.md.agentplatform.server.service.EmbeddingService;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingInput;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingRequest;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingResult;
import com.volcengine.ark.runtime.model.multimodalembeddings.SparseEmbeddingInput;
import com.volcengine.ark.runtime.service.ArkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 火山引擎 Ark 多模态向量化实现。
 */
@Component
public class EmbeddingServiceImpl implements EmbeddingService {

    private final Logger logger = LoggerFactory.getLogger(EmbeddingServiceImpl.class);

    @Autowired
    private ArkService arkService;

    @Autowired
    private ModelConfigProperties modelConfigProperties;

    @Override
    public DoubaoEmbeddingRsp getVectorByText(String text) {
        logger.info("开始向量化转换");

        List<MultimodalEmbeddingInput> inputForReqList = new ArrayList<>();
        MultimodalEmbeddingInput element = new MultimodalEmbeddingInput();
        element.setType("text");
        element.setText(text);
        inputForReqList.add(element);

        MultimodalEmbeddingRequest req = MultimodalEmbeddingRequest.builder()
                .model(modelConfigProperties.getEmbeddingModel())
                .input(inputForReqList)
                .sparseEmbedding(SparseEmbeddingInput.builder().type("enabled").build())
                .dimensions(1024)
                .encodingFormat("float")
                .build();

        MultimodalEmbeddingResult result = arkService.createMultiModalEmbeddings(req);

        Map<String, Float> sparseEmbeddings = result.getData().getSparseEmbedding()
                .stream()
                .collect(Collectors.toMap(
                        se -> String.valueOf(se.getIndex()),
                        se -> se.getValue().floatValue()));

        List<Float> denseEmbeddings = result.getData().getEmbedding()
                .stream()
                .map(Double::floatValue)
                .toList();

        return DoubaoEmbeddingRsp.builder()
                .denseEmbeddings(denseEmbeddings.toArray(new Float[0]))
                .spareEmbeddings(sparseEmbeddings)
                .build();
    }
}
