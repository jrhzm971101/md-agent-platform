package com.md.agentplatform.controller.channel;

import com.md.agentplatform.server.channel.ChannelRuntime;
import com.md.agentplatform.server.channel.GatewaySettingsStore;
import com.md.agentplatform.server.channel.ManagedGateway;
import com.md.agentplatform.server.controlplane.store.entity.GatewayEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网关配置接口（{@code md_gateway} 单行）。
 *
 * <h2>一、网关是什么</h2>
 * 它是"消息进来之后、找不到任何绑定与渠道兜底时，交给谁"的那一个决策项，
 * 同时也是官方 {@code GatewayBootstrap} 的装配输入 —— 纳管哪些 HARNESS agent、
 * 谁是主 agent。所以本接口只有两个动作：读、改主 agent。
 *
 * <h2>二、为什么网关<b>不是</b>受治理资源（没有草稿/发布/回滚）</h2>
 * 它没有"线上形态"这个概念：改了主 agent 就等于要重新装配，中间不存在一个"已提交但未生效"的中间态。
 * 硬给它套上发布流程，会造出"网关 v3 已发布"这种没人能解释的状态
 * （渠道就不同：改错一个 botId 会直接断掉线上入口，所以渠道有发布与回滚）。
 *
 * <h2>三、候选 agent 从哪来（这是本接口唯一"绕"的地方）</h2>
 * 只有 HARNESS agent 能被纳管（官方 {@code Gateway#registerAgent} 只接受 {@code HarnessAgent}；
 * REACT agent 走的是另一条链：由回复渲染器直接调 {@code AgentManager.stream}）。
 * 所以候选集 = 运行时里类型为 HARNESS 的那些，而 {@link ManagedGateway#agentIds()} 正是它 ——
 * 本接口直接把它当候选返回，不在控制器里再写一遍 {@code instanceof HarnessAgent} 的过滤。
 * 少一份过滤逻辑，就少一处"页面能选、装配却说没有"的不一致。
 *
 * <h2>四、改完立刻重新装配（而不是只落库）</h2>
 * 保存后调 {@code ChannelRuntime.applyAll()}：它内部先 {@code gatewayRuntime.reload()}
 * （重建 bootstrap 与路由兜底），再按需重启通道。一次动作把"网关 + 通道 + 路由表"三处一起对齐，
 * 页面不必再点一次「重载」。反过来说，这里不提供"只落库不生效"的选项 ——
 * 那只会让页面与线上差一拍，而这正是最难排查的一类问题。
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    @Autowired
    private GatewaySettingsStore settingsStore;

    @Autowired
    private ChannelRuntime channelRuntime;

    /** 读网关配置 + 当前装配状态 + 可选的 HARNESS agent 列表。 */
    @GetMapping
    public GatewayView get() {
        return view();
    }

    /**
     * 保存主 agent 并立刻重新装配。
     *
     * <p>这里<b>不</b>对"主 agent 不在候选集里"回 400：用户完全可能先配好网关、
     * 稍后再把那个 agent 启起来（顺序不该被接口强制）。这种情况由 {@link ManagedGateway#note()}
     * 明确说清"未装配 + 为什么"，页面顶部照原样显示 —— 让状态可读，而不是让操作失败。
     */
    @PutMapping
    public ResponseEntity<GatewayView> save(@RequestBody GatewaySave body,
                                            @RequestHeader(value = "X-Operator", required = false) String operator) {
        settingsStore.save(blankToNull(body.mainAgentId()), body.description(), operator(operator));
        channelRuntime.applyAll();
        return ResponseEntity.ok(view());
    }

    /** 重新装配（不修改任何配置）：读库里的主 agent + 运行时 HARNESS agent，重建网关并同步通道。 */
    @PostMapping("/reload")
    public GatewayView reload() {
        channelRuntime.applyAll();
        return view();
    }

    private GatewayView view() {
        GatewayEntity settings = settingsStore.load();
        ManagedGateway runtime = channelRuntime.gatewaySnapshot();
        return new GatewayView(
                settings.getMainAgentId(),
                settings.getDescription(),
                settings.getUpdatedAtMs(),
                settings.getUpdatedBy(),
                runtime,
                runtime.agentIds());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String operator(String raw) {
        return raw == null || raw.isBlank() ? "console" : raw.trim();
    }

    /** 保存载荷。 */
    public record GatewaySave(String mainAgentId, String description) {}

    /**
     * 页面用的网关视图 —— 配置 + 装配结果 + 候选列表。
     *
     * @param mainAgentId  库里配的主 agent（可能为 null = 未设置）
     * @param runtime      装配结果：是否成功、纳管了哪些 agent、兜底是谁、没装成的原因
     * @param candidates   可选为"主 agent"的候选（= 运行时已注册的 HARNESS agent，升序）
     */
    public record GatewayView(
            String mainAgentId,
            String description,
            Long updatedAtMs,
            String updatedBy,
            ManagedGateway runtime,
            List<String> candidates) {}
}
