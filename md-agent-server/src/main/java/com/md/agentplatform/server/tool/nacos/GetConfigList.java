package com.md.agentplatform.server.tool.nacos;

import com.md.agentplatform.server.config.EnvironmentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Nacos 配置列表工具 —— 查询指定环境（命名空间由环境目录提供）下的配置清单。
 *
 * <p>只返回元数据（dataId / group / type / appName），不返回配置内容；
 * 需要内容时再调用 get-nacos-config。
 */
@Component
public class GetConfigList extends AbstractNacosTool {

    @Tool(
            name = "get-nacos-config-list",
            description = """
                    查询 Nacos 配置中心某个命名空间下的配置列表（不返回配置内容）。

                    适用场景:
                    - 用户询问"这个环境有哪些配置"、"列出某个命名空间的所有配置"
                    - 排查配置缺失、确认某个 Data ID 是否存在
                    - 需要按 dataId / group 模糊检索配置

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - dataId: Data ID 过滤（选填，支持模糊匹配）
                    - group: 分组过滤（选填，支持模糊匹配）
                    - timeoutMs: 查询超时毫秒数（选填，默认 5000）

                    命名空间固定取环境目录 nacos.namespace，无需传参（留空表示 public）。

                    返回: 配置总数与 dataId / group / type / appName 清单；配置过多时自动截断并提示。
                    需要配置内容时再用 get-nacos-config 按 dataId + group 读取。
                    """
    )
    public String list(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "dataId", description = "Data ID 过滤（选填，支持模糊匹配）") String dataId,
            @ToolParam(name = "group", description = "分组过滤（选填，支持模糊匹配）") String group,
            @ToolParam(name = "timeoutMs", description = "查询超时毫秒数（选填，默认 5000）") String timeoutMs
    ) {
        logger.info("get-nacos-config-list called, environment:{}, dataId:{}, group:{}",
                environment, dataId, group);

        if (!notBlank(environment)) {
            return "[get-nacos-config-list] 参数缺失: environment 必填";
        }

        try {
            EnvironmentProperties.Nacos nacos = environmentRegistry.nacos(environment);
            String ns = nacos.getNamespace();
            ConfigList result = listConfigs(nacos.getServerAddr(), ns,
                    nacos.getUsername(), nacos.getPassword(), dataId, group, parseTimeout(timeoutMs));

            String scope = "environment=" + environment
                    + ", namespace=" + (notBlank(ns) ? ns : "(public)");
            StringBuilder filter = new StringBuilder();
            if (notBlank(dataId)) {
                filter.append(", dataId~").append(dataId.trim());
            }
            if (notBlank(group)) {
                filter.append(", group~").append(group.trim());
            }

            if (result.items().isEmpty()) {
                return "[get-nacos-config-list] 未查询到配置, " + scope + filter;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[get-nacos-config-list] ").append(scope).append(filter)
                    .append(", 共 ").append(result.totalCount()).append(" 个配置");
            if (result.truncated()) {
                sb.append("（仅返回前 ").append(result.items().size())
                        .append(" 个，请用 dataId / group 缩小范围）");
            }
            sb.append("\n\n");
            for (ConfigItem item : result.items()) {
                sb.append("- ").append(item.dataId())
                        .append("  [").append(item.group()).append("]");
                if (notBlank(item.type())) {
                    sb.append("  type=").append(item.type());
                }
                if (notBlank(item.appName())) {
                    sb.append("  app=").append(item.appName());
                }
                sb.append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            logger.error("get-nacos-config-list 异常, environment:{}, error:{}",
                    environment, e.getMessage(), e);
            return "[get-nacos-config-list] 查询异常: " + e.getMessage();
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
