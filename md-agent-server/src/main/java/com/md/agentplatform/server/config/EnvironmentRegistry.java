package com.md.agentplatform.server.config;

import com.md.agentplatform.server.config.EnvironmentProperties.Gitlab;
import com.md.agentplatform.server.config.EnvironmentProperties.Grafana;
import com.md.agentplatform.server.config.EnvironmentProperties.K8s;
import com.md.agentplatform.server.config.EnvironmentProperties.Magic;
import com.md.agentplatform.server.config.EnvironmentProperties.Mysql;
import com.md.agentplatform.server.config.EnvironmentProperties.Nacos;
import com.md.agentplatform.server.config.EnvironmentProperties.NineData;
import com.md.agentplatform.server.config.EnvironmentProperties.OpenObserve;
import com.md.agentplatform.server.config.EnvironmentProperties.Rabbit;
import com.md.agentplatform.server.config.EnvironmentProperties.SkyWalking;
import com.md.agentplatform.server.config.EnvironmentProperties.XxlJob;
import com.md.agentplatform.server.environment.EnvironmentCatalog;
import org.springframework.stereotype.Component;

/**
 * 环境目录解析服务 —— 工具侧唯一的入口。
 *
 * <p>只保留 11 个内置分组 + 实例重载的强类型访问器。页面自由创建的业务分组当前没有
 * 运行时消费方，因此不再保留 {@code group/value/groups} 这类通用读取口，避免形成
 * “页面能建、工具读不到”的悬空能力。
 */
@Component
public class EnvironmentRegistry {

    private final EnvironmentCatalog catalog;

    public EnvironmentRegistry(EnvironmentCatalog catalog) {
        this.catalog = catalog;
    }

    /** 指定环境的 Nacos 连接信息；未配置则抛异常（消息里指出是哪个环境）。 */
    public Nacos nacos(String environment) {
        return catalog.project(environment, "nacos", Nacos.class);
    }

    public Nacos nacos(String environment, String instance) {
        return catalog.project(environment, "nacos", instance, Nacos.class);
    }

    /** 指定环境的 K8s 连接信息；未配置则抛异常。 */
    public K8s k8s(String environment) {
        return catalog.project(environment, "k8s", K8s.class);
    }

    public K8s k8s(String environment, String instance) {
        return catalog.project(environment, "k8s", instance, K8s.class);
    }

    /** 指定环境的 RabbitMQ 管理 API 信息；未配置则抛异常。 */
    public Rabbit rabbit(String environment) {
        return catalog.project(environment, "rabbitmq", Rabbit.class);
    }

    public Rabbit rabbit(String environment, String instance) {
        return catalog.project(environment, "rabbitmq", instance, Rabbit.class);
    }

    /** 指定环境的 XXL-Job 控制台信息；未配置则抛异常。 */
    public XxlJob xxljob(String environment) {
        return catalog.project(environment, "xxljob", XxlJob.class);
    }

    public XxlJob xxljob(String environment, String instance) {
        return catalog.project(environment, "xxljob", instance, XxlJob.class);
    }

    /** 指定环境的 magic-api 取数入口；未配置则抛异常。 */
    public Magic magic(String environment) {
        return catalog.project(environment, "magic", Magic.class);
    }

    public Magic magic(String environment, String instance) {
        return catalog.project(environment, "magic", instance, Magic.class);
    }

    /** 指定环境的 OpenObserve 信息；未配置则抛异常。 */
    public OpenObserve openobserve(String environment) {
        return catalog.project(environment, "openobserve", OpenObserve.class);
    }

    public OpenObserve openobserve(String environment, String instance) {
        return catalog.project(environment, "openobserve", instance, OpenObserve.class);
    }

    /** 指定环境的 NineData OpenAPI 地址与 AccessKey；未配置则抛异常。 */
    public NineData ninedata(String environment) {
        return catalog.project(environment, "ninedata", NineData.class);
    }

    /** 指定环境的某套 NineData 实例，例如同一环境下的主库与报表库各一套。 */
    public NineData ninedata(String environment, String instance) {
        return catalog.project(environment, "ninedata", instance, NineData.class);
    }

    /** 指定环境的 GitLab 连接信息（url/apiUrl/token）；未配置则抛异常。 */
    public Gitlab gitlab(String environment) {
        return catalog.project(environment, "gitlab", Gitlab.class);
    }

    /** 指定环境的某套 GitLab 实例；同一环境需要访问多个 GitLab 时使用。 */
    public Gitlab gitlab(String environment, String instance) {
        return catalog.project(environment, "gitlab", instance, Gitlab.class);
    }

    /** 指定环境的 MySQL 直连信息；未配置则抛异常。 */
    public Mysql mysql(String environment) {
        return catalog.project(environment, "mysql", Mysql.class);
    }

    /** 指定环境的某套 MySQL 实例，例如 test 环境的主库与报表库各一套。 */
    public Mysql mysql(String environment, String instance) {
        return catalog.project(environment, "mysql", instance, Mysql.class);
    }

    /** 指定环境的 Grafana 地址与 Basic Auth；未配置则抛异常。 */
    public Grafana grafana(String environment) {
        return catalog.project(environment, "grafana", Grafana.class);
    }

    public Grafana grafana(String environment, String instance) {
        return catalog.project(environment, "grafana", instance, Grafana.class);
    }

    /** 指定环境的 SkyWalking GraphQL 地址与 Basic Auth；未配置则抛异常。 */
    public SkyWalking skywalking(String environment) {
        return catalog.project(environment, "skywalking", SkyWalking.class);
    }

    public SkyWalking skywalking(String environment, String instance) {
        return catalog.project(environment, "skywalking", instance, SkyWalking.class);
    }
}
