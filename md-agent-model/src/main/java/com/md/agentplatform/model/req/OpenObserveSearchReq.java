package com.md.agentplatform.model.req;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OpenObserve /_search 接口请求体。
 * 文档: https://openobserve.ai/docs/reference/api/search/search/
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenObserveSearchReq {

    /** 核心查询主体（必填） */
    private QueryBody query;

    /** 场景标记: ui / dashboards / reports / alerts（可选） */
    @JsonProperty("search_type")
    private String searchType;

    /** 查询超时毫秒，默认 600000（可选） */
    private Integer timeout;

    // ── 内嵌 query 对象 ──

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueryBody {

        /** OpenObserve SQL 查询语句（必填） */
        private String sql;

        /** 查询起始时间，微秒 Unix 时间戳（必填） */
        @JsonProperty("start_time")
        private Long startTime;

        /** 查询结束时间，微秒 Unix 时间戳（必填） */
        @JsonProperty("end_time")
        private Long endTime;

        /** 分页偏移量，默认 0 */
        private Integer from;

        /** 单页返回条数，默认 10；-1 返回全量 */
        private Integer size;

        /** 是否统计匹配总条数，开启会增加查询开销 */
        @JsonProperty("track_total_hits")
        private Boolean trackTotalHits;
    }
}
