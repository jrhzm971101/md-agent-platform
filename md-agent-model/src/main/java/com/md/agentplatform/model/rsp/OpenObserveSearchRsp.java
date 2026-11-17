package com.md.agentplatform.model.rsp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * OpenObserve /_search 和 /_around 接口的统一响应体。
 * hits 使用 List&lt;Map&gt; 适配各 stream 不同的字段结构。
 * ignoreUnknown = true: OpenObserve 会返回未文档化的附加字段（如 took_detail），忽略即可。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenObserveSearchRsp {

    /** 查询耗时，毫秒 */
    private Integer took;

    /** 匹配结果数据集 */
    private List<Map<String, Object>> hits;

    /** 匹配到的日志总条数 */
    private Integer total;

    /** 分页偏移量（around 接口固定为 0） */
    private Integer from;

    /** 请求传入的 size */
    private Integer size;

    /** 本次扫描数据量，单位 MB */
    @JsonProperty("scan_size")
    private Long scanSize;
}
