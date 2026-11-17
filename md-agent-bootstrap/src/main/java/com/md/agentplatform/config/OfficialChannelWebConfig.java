package com.md.agentplatform.config;

import io.agentscope.extensions.channel.feishu.FeishuCallbackController;
import io.agentscope.extensions.channel.gitlab.GitLabWebhookController;
import io.agentscope.extensions.channel.wecom.WeComCallbackController;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 把官方渠道扩展里的「回调 Controller」挂进本应用的 Web 容器。
 *
 * <h2>为什么需要这么个类（不做会怎样）</h2>
 * 官方 channel 扩展的生产 jar 里<b>没有任何 Spring 自动装配</b>：既没有
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}，
 * 也没有 {@code spring.factories}（已逐个开包确认，本地仓库里 2.0.3 的全部 agentscope jar 都没有）。
 * 换句话说，引入 {@code agentscope-extensions-channel-wecom} 这类依赖，
 * 只是把类放到 classpath 上，<b>不会</b>把 {@code WeComCallbackController} 注册成 Bean。
 *
 * <p>而本应用的组件扫描根是 {@code com.md.agentplatform}（见 {@code MdAgentPlatformApplication}
 * 上的 {@code @SpringBootApplication}），扫不到 {@code io.agentscope.extensions.channel.*}。结果是：
 * <ul>
 *   <li>渠道装配、{@code start()}、注册进官方静态 Registry —— 全部成功，页面上显示「运行中」；</li>
 *   <li>平台把消息回调到 {@code /api/channels/wecom/{channelId}/callback} —— <b>404</b>。</li>
 * </ul>
 * 这个组合最难排查：控制面一切正常，只有「用户发消息机器人不回」，
 * 服务端连一行请求日志都没有（请求根本没进 DispatcherHandler）。所以必须显式声明扫描范围。
 *
 * <h2>为什么用「扫描」而不是在本模块里自己写 @RestController</h2>
 * 回调协议里的细节（企微的 AES 解密 + 签名校验、飞书的 encryptKey、GitLab 的
 * constant-time token 比对、事件去重与防机器人回环）全部在官方实现里。自己重写等于
 * 把这套逻辑复制一份，且以后官方修 bug 我们收不到。这里只负责「让它被 Spring 看见」。
 *
 * <h2>为什么落在 bootstrap 模块</h2>
 * 项目约定：HTTP 边界只有一个落点，就是本模块（server 模块不出现任何 Web 注解）。
 * 官方这几个类虽是第三方代码，但它们的身份就是 HTTP 入口，因此由本模块负责装配。
 *
 * <h2>挂载出来的路径（由官方 Controller 的 @RequestMapping 决定，已按 jar 核对）</h2>
 * <ul>
 *   <li>{@code GET|POST /api/channels/wecom/{channelId}/callback}</li>
 *   <li>{@code POST /api/channels/feishu/{channelId}/callback}</li>
 *   <li>{@code POST /api/channels/gitlab/{channelId}/webhook}</li>
 * </ul>
 * 注意这里<b>没有钉钉</b>：钉钉的 {@code DingTalkCallbackController} 只存在于上游源码，
 * 已发布的 2.0.3 jar 里没有（2.0.3 的钉钉是纯 stream 反向连接，不需要回调地址）。
 * 等升级到带该类的版本、且确实要用 http 模式时，再把它加进来。
 *
 * <p>它们都按路径里的 {@code channelId} 去官方静态 Registry 里找实例，而实例只有在
 * {@code ChannelRuntime} 调过 {@code start()} 之后才存在 —— 所以「渠道没启用 / 装配失败」时
 * 这些路径同样返回 404（并打一行 {@code no channel registered for id=...}）。
 * 排查 404 时先看这行日志，再怀疑 Bean 有没有挂上。
 *
 * <h2>注意：路径空间与自研控制面接口是重叠的</h2>
 * 本项目自己的 {@code ChannelController} 挂在 {@code /api/channels} 上。二者目前不冲突
 * （官方路径 5 段，自研 2~3 段），但<b>渠道名不能叫</b> {@code wecom} / {@code feishu} /
 * {@code gitlab} / {@code types} / {@code reload-all}，否则 {@code GET /api/channels/{id}}
 * 会与官方前缀或自研固定路径撞车。
 *
 * <h2>以后接新平台</h2>
 * 加一个官方渠道时，除了在 {@code md-agent-server/pom.xml} 加依赖、
 * 在 {@code server/channel/official} 加一个工厂，<b>还要在这里补上它的 Controller</b>，
 * 否则就是上面那个「一切正常但收不到消息」的状态。用 {@code basePackageClasses} 而不是写
 * 字符串包名，就是为了让「依赖被删掉」这件事在编译期直接报错。
 */
@Configuration
@ComponentScan(basePackageClasses = {
    WeComCallbackController.class,
    FeishuCallbackController.class,
    GitLabWebhookController.class
})
public class OfficialChannelWebConfig {
}
