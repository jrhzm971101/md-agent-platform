package com.md.agentplatform.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.md.agentplatform.model.req.OpenObserveSearchReq;
import com.md.agentplatform.model.rsp.OpenObserveSearchRsp;
import com.md.agentplatform.server.config.EnvironmentProperties;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.service.OpenObserveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class OpenObserveServiceImpl implements OpenObserveService {

    private static final Logger logger = LoggerFactory.getLogger(OpenObserveServiceImpl.class);

    @Autowired
    private EnvironmentRegistry environmentRegistry;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpHeaders headers(EnvironmentProperties.OpenObserve oo) {
        String credentials = oo.getUsername() + ":" + oo.getPassword();
        String auth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", auth);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    @Override
    public OpenObserveSearchRsp search(String environment, String stream, OpenObserveSearchReq request) {
        EnvironmentProperties.OpenObserve oo = environmentRegistry.openobserve(environment);
        URI url = UriComponentsBuilder.fromHttpUrl(oo.getBaseUrl())
                .pathSegment("api", oo.getOrganization(), "_search")
                .build()
                .toUri();

        logger.info("OpenObserve search, environment:{}, stream:{}, url:{}", environment, stream, url);
        try {
            HttpEntity<OpenObserveSearchReq> entity = new HttpEntity<>(request, headers(oo));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("OpenObserve search 失败, HTTP " + response.getStatusCode());
            }
            return objectMapper.readValue(response.getBody(), OpenObserveSearchRsp.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("OpenObserve search 异常, stream:{}, error:{}", stream, e.getMessage(), e);
            throw new RuntimeException("OpenObserve search 请求异常: " + e.getMessage(), e);
        }
    }

    @Override
    public OpenObserveSearchRsp around(String environment, String stream, Long keyTimestamp, Integer size) {
        EnvironmentProperties.OpenObserve oo = environmentRegistry.openobserve(environment);
        URI url = UriComponentsBuilder.fromHttpUrl(oo.getBaseUrl())
                .pathSegment("api", oo.getOrganization(), stream, "_around")
                .queryParam("key", keyTimestamp)
                .queryParam("size", size)
                .build()
                .toUri();

        logger.info("OpenObserve around, environment:{}, stream:{}, key:{}, size:{}", environment, stream, keyTimestamp, size);
        try {
            HttpEntity<Void> entity = new HttpEntity<>(headers(oo));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("OpenObserve around 失败, HTTP " + response.getStatusCode());
            }
            return objectMapper.readValue(response.getBody(), OpenObserveSearchRsp.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("OpenObserve around 异常, stream:{}, key:{}, error:{}", stream, keyTimestamp, e.getMessage(), e);
            throw new RuntimeException("OpenObserve around 请求异常: " + e.getMessage(), e);
        }
    }

    @Override
    public OpenObserveSearchRsp aggregate(String environment, String stream, String interval, String filterCondition,
                                          Long startTime, Long endTime, Integer size) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT histogram(_timestamp, '").append(interval).append("') AS key, ")
           .append("COUNT(*) AS num ")
           .append("FROM \"").append(stream).append("\" ");
        if (filterCondition != null && !filterCondition.isBlank()) {
            sql.append("WHERE ").append(filterCondition).append(" ");
        }
        sql.append("GROUP BY key ORDER BY key");

        logger.info("OpenObserve aggregate, environment:{}, stream:{}, interval:{}", environment, stream, interval);

        return search(environment, stream, OpenObserveSearchReq.builder()
                .query(OpenObserveSearchReq.QueryBody.builder()
                        .sql(sql.toString())
                        .startTime(startTime)
                        .endTime(endTime)
                        .size(size != null ? size : 100)
                        .build())
                .build());
    }
}
