package com.md.agentplatform.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Bot 注册中心 —— 「按名字取连接」的唯一入口，<b>不再负责创建与启停</b>。
 *
 * <h2>一、为什么把它削成一个纯注册表</h2>
 * 改造前这里的 {@code @PostConstruct} 会读 {@code application.yml} 的 {@code messaging.bots[]}、
 * 逐个 new 出 {@link BotInstance} 并启动 —— 也就是说「有哪些渠道」这件事被写死在配置文件里。
 * 现在渠道是库里的受治理资源（{@code md_channel}），装配由 {@code ChannelRuntime} 负责：
 * 它造出通道、启动、然后<b>把 BotInstance 注册到这里</b>。
 * 于是本类的职责收敛成一句："运行中的长连接机器人，谁能按名字取到。"
 *
 * <h2>二、为什么"停止"也不在这里</h2>
 * 启停必须成对：{@code ChannelRuntime} 启动时注册、停止时注销（见 {@code stopNow}）。
 * 如果本类也做关闭，就会出现两个释放点，而 {@code BotInstance} 的 {@code stop()} 是
 * 关闭 OkHttp / 线程池这种不可重复的动作 —— 谁先谁后都要靠约定，那必然会错。
 *
 * <h2>三、发送侧为什么不改</h2>
 * {@code WorkWxReplyServiceImpl} / {@code LarkOperationServiceImpl} 等发送侧一直用
 * {@code get(name)} + {@code instanceof} 取具体平台的客户端，这套 API 原样保留，
 * 发送链路上没有任何改动。
 */
@Component
public class BotInstanceManager {

    private static final Logger log = LoggerFactory.getLogger(BotInstanceManager.class);

    /** 启动后延迟上报健康快照的毫秒数，给企微异步订阅握手留出落地时间。 */
    private static final long HEALTH_SNAPSHOT_DELAY_MS = 5000;

    /**
     * 运行中的实例，key = 渠道名（{@code md_channel.channel_id}）。
     *
     * <p>用 ConcurrentHashMap 是因为写（装配线程）与读（消息线程）并发：
     * 企微的消息回调在 WS 线程里查连接，而装配可能同时在启动另一个渠道。
     */
    private final Map<String, BotInstance> instances = new ConcurrentHashMap<>();

    // ==================== 注册中心 API ====================

    /** 注册（同名的旧实例由调用方先 {@code stop()} 并注销，这里直接覆盖）。 */
    public void register(BotInstance instance) {
        instances.put(instance.getName(), instance);
    }

    /**
     * 注销一个实例。
     *
     * <p>只在名字确实存在时打日志：装配路径上"因为装配失败而没注册过"是常见情况，
     * 每次都打一行 warn 会把真正的失败刷掉。
     */
    public void unregister(String name) {
        BotInstance removed = instances.remove(name);
        if (removed != null) {
            log.info("[Bot] 已注销 {}", name);
        }
    }

    public BotInstance get(String name) {
        return instances.get(name);
    }

    public List<BotInstance> getAll() {
        return new ArrayList<>(instances.values());
    }

    public List<BotInstance> getByPlatform(BotPlatform platform) {
        return instances.values().stream()
                .filter(b -> b.getPlatform() == platform)
                .collect(Collectors.toList());
    }

    public void logHealth() {
        if (instances.isEmpty()) {
            log.warn("[健康检查] 未注册任何 Bot（渠道是否都停用了？见渠道页）");
            return;
        }
        instances.values().forEach(b ->
                log.info("[健康检查] {} ({}) : {}",
                        b.getName(), b.getPlatform(), b.isAlive() ? "✓" : "✗"));
    }

    /**
     * 延迟上报一次健康快照：异步平台（企微订阅）落地前先不打扰，落地后由 onHealthChange 回调再刷新。
     *
     * <p>公开给 {@code ChannelRuntime} 调用（它在装配完成后安排这一跳）。
     * 这一段原来在本类的 {@code @PostConstruct} 里，随装配职责一起搬走。
     */
    public void scheduleHealthSnapshot() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(HEALTH_SNAPSHOT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            logHealth();
        }, "bot-health-snapshot");
        t.setDaemon(true);
        t.start();
    }
}
