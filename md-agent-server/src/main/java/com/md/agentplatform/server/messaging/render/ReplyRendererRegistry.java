package com.md.agentplatform.server.messaging.render;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 回复渲染器注册表：收集所有 {@link ReplyRenderer} bean，按 {@link ReplyStyle} 索引。
 */
@Component
public class ReplyRendererRegistry {

    private final Map<ReplyStyle, ReplyRenderer> renderers = new EnumMap<>(ReplyStyle.class);

    public ReplyRendererRegistry(List<ReplyRenderer> rendererList) {
        for (ReplyRenderer renderer : rendererList) {
            ReplyRenderer prev = renderers.put(renderer.style(), renderer);
            if (prev != null) {
                throw new IllegalStateException("回复风格重复注册: " + renderer.style());
            }
        }
    }

    public ReplyRenderer get(ReplyStyle style) {
        ReplyRenderer renderer = renderers.get(style);
        if (renderer == null) {
            throw new IllegalStateException("未注册的回复风格: " + style);
        }
        return renderer;
    }
}
