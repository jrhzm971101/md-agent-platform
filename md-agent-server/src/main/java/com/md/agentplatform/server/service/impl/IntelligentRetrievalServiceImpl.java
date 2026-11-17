package com.md.agentplatform.server.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.md.agentplatform.model.rsp.DoubaoEmbeddingRsp;
import com.md.agentplatform.model.rsp.ElasticDocRsp;
import com.md.agentplatform.server.config.RrfProperties;
import com.md.agentplatform.server.service.EmbeddingService;
import com.md.agentplatform.server.service.IntelligentRetrievalService;
import com.md.agentplatform.server.tools.AiTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 手动 RRF 混合检索：BM25 全文 + 稀疏向量 + 稠密向量 (kNN)。
 */
@Component
public class IntelligentRetrievalServiceImpl implements IntelligentRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(IntelligentRetrievalServiceImpl.class);

    @Autowired
    private RrfProperties rrf;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ElasticsearchClient esClient;

    @Override
    public String search(String query) {
        return doSearch(query);
    }

    @Override
    public String search(String query, String userKey) {
        return doSearch(query);
    }

    private String doSearch(String query) {
        DoubaoEmbeddingRsp embeddingRsp;
        try {
            embeddingRsp = embeddingService.getVectorByText(query);
        } catch (Exception e) {
            logger.error("[RRF] 向量化失败，降级为纯 BM25 检索 query={} err={}", query, e.getMessage());
            List<Hit<ElasticDocRsp>> bm25Hits = searchBM25(query);
            return buildResult(bm25Hits);
        }

        long start = System.currentTimeMillis();

        CompletableFuture<List<Hit<ElasticDocRsp>>> bm25Future = CompletableFuture.supplyAsync(() ->
                searchBM25(query));
        CompletableFuture<List<Hit<ElasticDocRsp>>> sparseFuture = CompletableFuture.supplyAsync(() ->
                searchSparseVector(embeddingRsp.getSpareEmbeddings()));
        CompletableFuture<List<Hit<ElasticDocRsp>>> knnFuture = CompletableFuture.supplyAsync(() ->
                searchKnn(embeddingRsp.getDenseEmbeddings()));

        CompletableFuture.allOf(bm25Future, sparseFuture, knnFuture).join();

        List<Hit<ElasticDocRsp>> bm25Hits = bm25Future.join();
        List<Hit<ElasticDocRsp>> sparseHits = sparseFuture.join();
        List<Hit<ElasticDocRsp>> knnHits = knnFuture.join();

        logger.info("[RRF] 三路检索完成 BM25={} sparse={} knn={} 耗时={}ms",
                bm25Hits.size(), sparseHits.size(), knnHits.size(),
                System.currentTimeMillis() - start);

        List<Hit<ElasticDocRsp>> merged = rrfMerge(bm25Hits, sparseHits, knnHits);
        return buildResult(merged);
    }

    private List<Hit<ElasticDocRsp>> searchBM25(String query) {
        try {
            SearchResponse<ElasticDocRsp> resp = esClient.search(s -> s
                    .index(rrf.getIndexName())
                    .size(rrf.getRrfWindow())
                    .query(q -> q.multiMatch(mm -> mm
                            .fields("title^2", "content", "summary")
                            .query(query))),
                    ElasticDocRsp.class);
            return resp.hits().hits();
        } catch (Exception e) {
            logger.error("[RRF] BM25 检索失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<Hit<ElasticDocRsp>> searchSparseVector(Map<String, Float> sparseEmbeddings) {
        if (sparseEmbeddings == null || sparseEmbeddings.isEmpty()) {
            return List.of();
        }
        try {
            SearchResponse<ElasticDocRsp> resp = esClient.search(s -> s
                    .index(rrf.getIndexName())
                    .size(rrf.getRrfWindow())
                    .query(q -> q.sparseVector(sv -> sv
                            .field("summarySparse")
                            .queryVector(sparseEmbeddings))),
                    ElasticDocRsp.class);
            return resp.hits().hits();
        } catch (Exception e) {
            logger.error("[RRF] 稀疏向量检索失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<Hit<ElasticDocRsp>> searchKnn(Float[] denseEmbeddings) {
        if (denseEmbeddings == null || denseEmbeddings.length == 0) {
            return List.of();
        }
        try {
            SearchResponse<ElasticDocRsp> resp = esClient.search(s -> s
                    .index(rrf.getIndexName())
                    .knn(k -> k
                            .field("contentDense")
                            .k(rrf.getKnnK())
                            .numCandidates(rrf.getKnnCandidates())
                            .queryVector(Arrays.asList(denseEmbeddings))),
                    ElasticDocRsp.class);
            return resp.hits().hits();
        } catch (Exception e) {
            logger.error("[RRF] kNN 检索失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<Hit<ElasticDocRsp>> rrfMerge(
            List<Hit<ElasticDocRsp>> bm25Hits,
            List<Hit<ElasticDocRsp>> sparseHits,
            List<Hit<ElasticDocRsp>> knnHits) {

        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Hit<ElasticDocRsp>> docMap = new LinkedHashMap<>();

        accumulate(bm25Hits, rrfScores, docMap);
        accumulate(sparseHits, rrfScores, docMap);
        accumulate(knnHits, rrfScores, docMap);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(rrf.getTopN())
                .map(e -> docMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private void accumulate(List<Hit<ElasticDocRsp>> hits,
                            Map<String, Double> rrfScores,
                            Map<String, Hit<ElasticDocRsp>> docMap) {
        for (int i = 0; i < hits.size(); i++) {
            Hit<ElasticDocRsp> hit = hits.get(i);
            String docId = hit.id();
            if (docId == null) {
                continue;
            }

            int rank = i + 1;
            double score = 1.0 / (rrf.getRrfK() + rank);
            rrfScores.merge(docId, score, Double::sum);
            docMap.putIfAbsent(docId, hit);
        }
    }

    private String buildResult(List<Hit<ElasticDocRsp>> hits) {
        StringBuilder result = new StringBuilder();
        for (int idx = 0; idx < hits.size(); idx++) {
            Hit<ElasticDocRsp> hit = hits.get(idx);
            ElasticDocRsp source = hit.source();
            if (source == null) {
                continue;
            }

            String cleaned = AiTool.cleanText(source.getContent());
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }

            String channel = source.getChannel();
            String channelLabel = channel != null ? channelLabel(channel) : "";

            result.append('[').append(idx + 1).append(']');
            if (!channelLabel.isEmpty()) {
                result.append(" 来源:").append(channelLabel);
            }
            result.append('\n');

            String title = source.getTitle();
            if (title != null && !title.isBlank()) {
                result.append("文档标题: ").append(title).append('\n');
            }

            String summary = source.getSummary();
            if (summary != null && !summary.isBlank()) {
                result.append("文档摘要: ").append(summary).append('\n');
            }

            result.append("文档内容:\n")
                    .append(cleaned).append('\n')
                    .append("文档来源: ")
                    .append(Objects.toString(source.getSourceUrl(), "未知来源"))
                    .append("\n\n");
        }
        return result.toString();
    }

    private static String channelLabel(String channel) {
        return switch (channel) {
            case "wiki" -> "Wiki";
            case "upload" -> "上传文档";
            case "lark" -> "飞书";
            case "workwx" -> "企业微信";
            case "web" -> "网页";
            default -> "";
        };
    }
}
