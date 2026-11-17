package com.md.agentplatform.server.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器配置。
 *
 * <p>{@code @XxlJob} 注解仅由 {@link XxlJobSpringExecutor}（实现 {@code SmartInitializingSingleton}）
 * 扫描并注册到 handler 仓库。xxl-job-core 3.x 不提供 Spring Boot 自动装配，
 * agentscope 的 scheduler 扩展也只是普通类，因此必须显式声明该 Bean，否则所有
 * {@code @XxlJob} 方法都不会被 Admin 调度到。
 */
@Configuration
public class XxlJobConfig {

    private static final Logger log = LoggerFactory.getLogger(XxlJobConfig.class);

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties props) {
        log.info(">>>>>>>>>>> xxl-job executor init. admin={}, appname={}, port={}, ip={}",
                props.getAdminAddresses(), props.getAppName(), props.getExecutorPort(),
                props.getExecutorIp() == null ? "auto" : props.getExecutorIp());

        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(props.getAdminAddresses());
        executor.setAppname(props.getAppName());
        executor.setAccessToken(props.getAccessToken());
        executor.setPort(props.getExecutorPort());
        executor.setIp(props.getExecutorIp());
        executor.setLogPath(props.getLogPath());
        executor.setLogRetentionDays(props.getLogRetentionDays());
        return executor;
    }
}
