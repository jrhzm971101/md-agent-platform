package com.md.agentplatform.server.channel;

import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 渠道类型注册表 —— 进程内所有 {@link ChannelAdapterFactory} 的登记处（由 Spring 自动收集）。
 *
 * <h2>为什么用「构造器注入 List&lt;工厂&gt;」而不是静态注册表</h2>
 * 官方 {@code ChannelTypeRegistry} 是静态 map（官方要在非 Spring 的 bootstrap 里注册它们），
 * 而本项目的装配全部发生在 Spring 容器里。用容器收集有一处实打实的好处：
 * 工厂可以自己注入依赖（长连接工厂需要 {@code InboundMessageHandler}），
 * 静态注册表做不到 —— 它只能把依赖手工传进 register()，
 * 于是"谁持有依赖"这件事就散了，注册代码也就成了启动顺序的隐式约束。
 *
 * <h2>重复类型必须启动即失败</h2>
 * 两个工厂声明同一个 {@code typeId}（例如复制一个工厂时漏改描述）时，静默覆盖会造出
 * "页面上写的是 A、实际装配成 B"。这种错误在运行期几乎查不出来，
 * 所以直接在构造时抛异常 —— 与 {@code ControlPlaneStoreConfig} 那条
 * "依赖集合要一眼可见"是同一类考虑：装配期的错误要在装配期暴露。
 */
@Component
public final class ChannelTypeRegistry {

    private final Map<String, ChannelAdapterFactory> factories;

    public ChannelTypeRegistry(List<ChannelAdapterFactory> discovered) {
        Map<String, ChannelAdapterFactory> map = new LinkedHashMap<>();
        for (ChannelAdapterFactory factory : discovered) {
            String typeId = factory.descriptor().typeId();
            ChannelAdapterFactory previous = map.put(typeId, factory);
            if (previous != null) {
                throw new IllegalStateException("渠道类型重复注册: " + typeId + "（"
                        + previous.getClass().getName() + " 与 " + factory.getClass().getName() + "）");
            }
        }
        this.factories = Map.copyOf(map);
    }

    /** 已注册的类型描述，按 typeId 排序 —— 页面的「类型」下拉与凭据表单用它。 */
    public List<ChannelTypeDescriptor> descriptors() {
        return factories.values().stream()
                .map(ChannelAdapterFactory::descriptor)
                .sorted(Comparator.comparing(ChannelTypeDescriptor::typeId))
                .toList();
    }

    /** 按 typeId 取工厂；未知类型返回空（调用方负责翻成可读报错）。 */
    public Optional<ChannelAdapterFactory> find(String typeId) {
        return typeId == null || typeId.isBlank() ? Optional.empty() : Optional.ofNullable(factories.get(typeId.trim()));
    }

    /** 已注册的 typeId，用于校验报错里"可选值有哪些"。 */
    public List<String> registeredTypes() {
        return factories.keySet().stream().sorted().toList();
    }

    /**
     * 按类型造通道。
     *
     * @throws ChannelValidationException 类型未注册，或凭据不合法
     */
    public ChannelAdapter create(String channelId, String typeId, ChannelConfig routing,
                                 Map<String, Object> properties) {
        ChannelAdapterFactory factory = find(typeId).orElseThrow(() -> new ChannelValidationException(
                "未知的渠道类型: " + typeId + "（已注册: " + registeredTypes() + "）"));
        return factory.create(channelId, routing, properties);
    }
}
