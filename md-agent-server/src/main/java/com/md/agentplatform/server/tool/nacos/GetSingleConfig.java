package com.md.agentplatform.server.tool.nacos;

import com.md.agentplatform.server.config.EnvironmentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Nacos 配置获取工具 —— 从指定环境读取单个配置内容。
 *
 * <p>连接信息通过 {@code environment} 参数从环境目录解析，不依赖 Spring Bean。
 */
@Component
public class GetSingleConfig extends AbstractNacosTool {

    @Tool(
            name = "get-nacos-config",
            description = """
                    从 Nacos 配置中心读取单个配置（Data ID）的内容。

                    适用场景:
                    - 用户询问"某个配置的值是什么"、"看一下 xxx 的配置"
                    - 排查配置问题时，拉取某个 Data ID 的当前配置
                    - 对比不同环境/命名空间的同一份配置

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - dataId: 配置 Data ID（必填），如 lms-backend
                    - group: 配置分组（选填，默认 DEFAULT_GROUP）
                    - timeoutMs: 查询超时毫秒数（选填，默认 5000）

                    命名空间固定取环境目录 nacos.namespace，无需传参（留空表示 public）。

                    返回: 配置内容文本；配置不存在时返回提示。
                    """
    )
    public String get(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "dataId", description = "配置 Data ID（必填），如 lms-backend") String dataId,
            @ToolParam(name = "group", description = "配置分组（选填，默认 DEFAULT_GROUP）") String group,
            @ToolParam(name = "timeoutMs", description = "查询超时毫秒数（选填，默认 5000）") String timeoutMs
    ) {
        logger.info("get-nacos-config called, environment:{}, dataId:{}, group:{}", environment, dataId, group);

        if (!notBlank(environment)) {
            return "[get-nacos-config] 参数缺失: environment 必填";
        }
        if (!notBlank(dataId)) {
            return "[get-nacos-config] 参数缺失: dataId 必填";
        }

        try {
            String g = defaultIfBlank(group, DEFAULT_GROUP);
            long timeout = parseTimeout(timeoutMs);

            EnvironmentProperties.Nacos nacos = environmentRegistry.nacos(environment);
            String ns = nacos.getNamespace();

            String content = getConfig(nacos.getServerAddr(), ns,
                    nacos.getUsername(), nacos.getPassword(), dataId.trim(), g, timeout);
            if (content == null) {
                return "[get-nacos-config] 配置不存在: dataId=" + dataId.trim() + ", group=" + g;
            }
            return "[get-nacos-config] 获取成功, dataId=" + dataId.trim() + ", group=" + g + "\n\n" + content;
        } catch (Exception e) {
            logger.error("get-nacos-config 异常, environment:{}, dataId:{}, error:{}", environment, dataId, e.getMessage(), e);
            return "[get-nacos-config] 获取异常: " + e.getMessage();
        }
    }

    private long parseTimeout(String timeoutMs) {
        if (notBlank(timeoutMs)) {
            try {
                return Long.parseLong(timeoutMs.trim());
            } catch (NumberFormatException ignored) {
                // 解析失败走默认值
            }
        }
        return DEFAULT_TIMEOUT_MS;
    }
}
