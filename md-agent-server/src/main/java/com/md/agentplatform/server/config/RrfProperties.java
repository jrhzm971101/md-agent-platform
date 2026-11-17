package com.md.agentplatform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RRF 混合检索可调参数。前缀 {@code retrieval}。
 */
@Component
@ConfigurationProperties(prefix = "retrieval")
public class RrfProperties {

    private String indexName = "doc_vector";
    private int rrfWindow = 50;
    private int rrfK = 60;
    private int topN = 20;
    private int knnCandidates = 500;
    private int knnK = 20;

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public int getRrfWindow() { return rrfWindow; }
    public void setRrfWindow(int rrfWindow) { this.rrfWindow = rrfWindow; }

    public int getRrfK() { return rrfK; }
    public void setRrfK(int rrfK) { this.rrfK = rrfK; }

    public int getTopN() { return topN; }
    public void setTopN(int topN) { this.topN = topN; }

    public int getKnnCandidates() { return knnCandidates; }
    public void setKnnCandidates(int knnCandidates) { this.knnCandidates = knnCandidates; }

    public int getKnnK() { return knnK; }
    public void setKnnK(int knnK) { this.knnK = knnK; }
}
