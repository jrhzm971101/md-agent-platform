package com.md.agentplatform.server.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 主库（业务库 {@code md-agent-platform}）连接池。
 *
 * <h2>为什么必须显式声明它（2026-09-23 修）</h2>
 * Spring Boot 的 {@code DataSourceAutoConfiguration$PooledDataSourceConfiguration} 上挂着
 * {@code @ConditionalOnMissingBean({DataSource.class, XADataSource.class})} ——
 * 容器里只要出现<b>任意一个</b> DataSource Bean，它<b>整块退出</b>，
 * 于是 {@code spring.datasource.*} 再也没人读，主库连接池也就不存在了。
 *
 * <p>{@link ControlPlaneStoreConfig#workspaceKvDataSource} 正是这样一个 Bean
 * （工作区文件 KV 的池）。两者叠加的后果有两层，<b>第二层比第一层严重得多</b>：
 *
 * <ol>
 *   <li><b>启动就挂</b>：KV 池的构造方法要一个 {@code DataSource} 参数（拿它执行
 *       CREATE DATABASE）。主库 Bean 不存在时，按类型注入只能解析到 KV 池自己，
 *       Spring 报「bean 之间形成循环依赖」，应用起不来；</li>
 *   <li><b>如果它"能启动"，反而更糟</b>：容器里唯一的 DataSource 变成 KV 池，
 *       于是 12 个 Mapper、skill 仓库、状态存储全部连到 {@code md_agent_workspace} 上 ——
 *       不报错，只是数据写进了另一个库。第一层那个报错其实是替我们挡住了这一层。</li>
 * </ol>
 *
 * <p>所以这里把主库补回来，并标 {@code @Primary}：容器里有两个 DataSource 时，
 * 按类型注入一律命中主库（12 个 Mapper、skill 仓库、状态存储都靠"按类型"注入），
 * KV 池只有显式 {@code @Qualifier} 才拿得到。
 *
 * <h2>为什么用 DataSourceProperties 而不是把 spring.datasource.* 再抄一遍</h2>
 * {@code initializeDataSourceBuilder()} 就是 Spring Boot 自己绑定 url / username /
 * password / driver-class-name 的入口，连 {@code spring.datasource.type} 这种"换池实现"的
 * 开关也一并继承。手抄一遍等于把 Boot 的绑定规则复制了一份，它以后多一个键，我们这份就悄悄落后。
 *
 * <p>一处已知的能力损失：{@code spring.datasource.hikari.*} 这类池级细调由 Boot 的
 * {@code DataSourceConfiguration.Hikari} 用 {@code @ConfigurationProperties} 直接绑到池上，
 * 而那条自动配置现在已经退出，所以本项目暂时没有池级细调入口（实测 yml 里也没用到）。
 * 真需要时在这里补一个 {@code @ConfigurationProperties("spring.datasource.hikari")} 的
 * {@code HikariConfig} 即可 —— 那样会同时把池实现锁死成 Hikari，所以不是默认选择。
 */
@Configuration
public class DataSourceConfig {

    /**
     * 主库连接参数。
     *
     * <p>bean 名保持 {@code dataSourceProperties}，与 Boot 的默认命名习惯一致 ——
     * 将来若有自动配置按名字找它，不会找不到。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 业务库连接池。
     *
     * <p>{@code @Primary} 是这里唯一的重点：本项目有且只有一个 DataSource 该被「按类型」
     * 注入到别人身上，就是它。工作区 KV 那个池必须继续留在「要 {@code @Qualifier} 才拿得到」
     * 的位置上，否则第 2 层后果就会真的发生。
     */
    @Bean("dataSource")
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }
}