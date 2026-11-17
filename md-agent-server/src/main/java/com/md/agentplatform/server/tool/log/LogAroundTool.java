package com.md.agentplatform.server.tool.log;

import com.md.agentplatform.model.rsp.OpenObserveSearchRsp;
import com.md.agentplatform.server.service.OpenObserveService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 日志上下文查询工具 —— 调用 OpenObserve /_around 接口。
 */
@Component
public class LogAroundTool {

    private static final Logger logger = LoggerFactory.getLogger(LogAroundTool.class);

    @Autowired
    private OpenObserveService openObserveService;

    @Tool(
            name = "log-around",
            description = """
                    日志上下文查询工具，根据 _timestamp 时间戳查询该条日志前后 5 分钟窗口内的上下文日志。

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，OpenObserve 连接信息从环境目录解析

                    注意:
                    - _timestamp 必须从 log-search 返回结果中获取，不可自行编造
                    - 时间戳单位是微秒（μs）
                    """
    )
    public String around(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "stream", description = "日志流名称，必填，与 log-search 保持一致") String stream,
            @ToolParam(name = "_timestamp", description = "目标日志的微秒时间戳，必填，从 log-search 返回结果中获取") String timestamp,
            @ToolParam(name = "size", description = "返回的上下文日志总条数，选填，默认 10") String size
    ) {
        logger.info("log-around called, environment:{}, stream:{}, _timestamp:{}", environment, stream, timestamp);

        if (environment == null || environment.isBlank()) {
            return "[log-around] 参数缺失: environment 必填";
        }

        try {
            long keyMicros = Long.parseLong(timestamp.trim());
            int s = LogSearchTool.parseSize(size, 10);

            OpenObserveSearchRsp rsp = openObserveService.around(environment, stream, keyMicros, s);

            return formatAroundResult(rsp, stream, keyMicros, s);
        } catch (NumberFormatException e) {
            return "[log-around] 失败: _timestamp 格式错误，需要微秒时间戳数值，当前值: " + timestamp;
        } catch (Exception e) {
            logger.error("log-around 执行失败, stream:{}, error:{}", stream, e.getMessage(), e);
            return "[log-around] 查询失败: " + e.getMessage();
        }
    }

    private String formatAroundResult(OpenObserveSearchRsp rsp, String stream, long keyMicros, int size) {
        StringBuilder sb = new StringBuilder();
        sb.append("[log-around] 流: ").append(stream)
                .append(", 目标时间戳: ").append(LogSearchTool.formatMicros(keyMicros))
                .append(" (").append(keyMicros).append(")")
                .append(", 窗口: ±5分钟")
                .append(", 返回: ").append(rsp.getHits() != null ? rsp.getHits().size() : 0).append(" 条\n");

        if (rsp.getHits() == null || rsp.getHits().isEmpty()) {
            sb.append("(无上下文日志)");
            return sb.toString();
        }

        for (Map<String, Object> hit : rsp.getHits()) {
            sb.append("---\n");
            Object ts = hit.get("_timestamp");
            if (ts instanceof Number) {
                long micros = ((Number) ts).longValue();
                String marker = micros == keyMicros ? " ★ 目标日志" : "";
                sb.append("_timestamp: ").append(LogSearchTool.formatMicros(micros))
                        .append(" (").append(micros).append(")").append(marker).append("\n");
            }
            Object log = hit.get("log");
            if (log != null) {
                String logStr = log.toString();
                if (logStr.length() > 800) {
                    logStr = logStr.substring(0, 800) + "...";
                }
                sb.append("log: ").append(logStr).append("\n");
            }
        }
        return sb.toString();
    }
}
