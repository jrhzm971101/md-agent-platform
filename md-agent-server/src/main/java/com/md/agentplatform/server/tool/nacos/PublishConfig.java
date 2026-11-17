package com.md.agentplatform.server.tool.nacos;

import com.md.agentplatform.server.config.EnvironmentProperties;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Nacos 配置推送工具 —— 向指定环境新增或更新一个配置。
 *
 * <p>连接信息通过 {@code environment} 参数从环境目录解析，不依赖 Spring Bean。
 */
@Component
public class PublishConfig extends AbstractNacosTool {

    @Tool(
            name = "publish-nacos-config",
            description = """
                    向 Nacos 配置中心推送（新增或更新）一个配置。

                    适用场景:
                    - 用户说"帮我新增/修改某个配置"
                    - 把一份配置写入指定环境/分组

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat，连接信息从环境目录解析
                    - dataId: 配置 Data ID（必填），如 agent-test
                    - content: 配置内容（必填），完整的配置文本
                    - group: 配置分组（选填，默认 DEFAULT_GROUP）
                    - type: 配置类型（选填，默认 yaml），可选 yaml/properties/json/text/xml/html

                    命名空间固定取环境目录 nacos.namespace，无需传参（留空表示 public）。

                    返回: 推送结果（成功/失败及原因）。
                    """
    )
    public String publish(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat") String environment,
            @ToolParam(name = "dataId", description = "配置 Data ID（必填），如 agent-test") String dataId,
            @ToolParam(name = "content", description = "配置内容（必填），完整的配置文本") String content,
            @ToolParam(name = "group", description = "配置分组（选填，默认 DEFAULT_GROUP）") String group,
            @ToolParam(name = "type", description = "配置类型（选填，默认 yaml），可选 yaml/properties/json/text/xml/html") String type
    ) {
        logger.info("publish-nacos-config called, environment:{}, dataId:{}, group:{}, type:{}",
                environment, dataId, group, type);

        if (!notBlank(environment)) {
            return "[publish-nacos-config] 参数缺失: environment 必填";
        }
        if (!notBlank(dataId)) {
            return "[publish-nacos-config] 参数缺失: dataId 必填";
        }
        if (content == null) {
            return "[publish-nacos-config] 参数缺失: content 必填";
        }

        try {
            String g = defaultIfBlank(group, DEFAULT_GROUP);
            String t = defaultIfBlank(type, "yaml");

            EnvironmentProperties.Nacos nacos = environmentRegistry.nacos(environment);
            String ns = nacos.getNamespace();

            boolean ok = publishConfig(nacos.getServerAddr(), ns,
                    nacos.getUsername(), nacos.getPassword(), dataId.trim(), g, content, t, DEFAULT_PUBLISH_TIMEOUT_MS);
            return ok
                    ? "[publish-nacos-config] ✅ 推送成功, dataId=" + dataId.trim() + ", group=" + g + ", type=" + t
                    : "[publish-nacos-config] ❌ 推送失败, dataId=" + dataId.trim() + ", group=" + g;
        } catch (Exception e) {
            logger.error("publish-nacos-config 异常, environment:{}, dataId:{}, error:{}", environment, dataId, e.getMessage(), e);
            return "[publish-nacos-config] 推送异常: " + e.getMessage();
        }
    }
}
