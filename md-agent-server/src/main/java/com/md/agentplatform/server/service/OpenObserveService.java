package com.md.agentplatform.server.service;

import com.md.agentplatform.model.req.OpenObserveSearchReq;
import com.md.agentplatform.model.rsp.OpenObserveSearchRsp;

/**
 * OpenObserve 日志平台接口。
 */
public interface OpenObserveService {

    OpenObserveSearchRsp search(String environment, String stream, OpenObserveSearchReq request);

    OpenObserveSearchRsp around(String environment, String stream, Long keyTimestamp, Integer size);

    OpenObserveSearchRsp aggregate(String environment, String stream, String interval, String filterCondition,
                                   Long startTime, Long endTime, Integer size);
}
