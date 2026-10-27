package com.heishandashu.ironfarm.neoforge.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * NeoForge 侧的顶点缓冲管理。
 *
 * <p><b>为什么需要它：</b></p>
 *
 * <p>Fabric 的 WorldRenderContext.consumers() 直接给一个现成的
 * MultiBufferSource（世界渲染器内部那个，会在合适时机 flush）。
 * 而 NeoForge 的 RenderLevelStageEvent 只给 PoseStack 和 Camera，
 * <b>不提供缓冲源</b>，必须自己建。</p>
 *
 * <p><b>做法</b>：用 1.21.1 自带的
 * MultiBufferSource.immediate(ByteBufferBuilder) —— 它已经是标准实现。
 * 渲染结束后 flush()，由 BufferSource.endBatch() 把批次画出去。</p>
 *
 * <p>⚠ 顶点坐标必须是相机相对坐标（调用方已做 translate(-camPos)）。
 * 这一点与 Fabric 完全相同。</p>
 *
 * <p>⚠ 本类只在一个渲染阶段内有效，flush 后即作废。</p>
 */
public final class NeoRenderBuffers {

    private final ByteBufferBuilder shared = new ByteBufferBuilder(1536);
    private final MultiBufferSource.BufferSource source =
            MultiBufferSource.immediate(shared);

    private NeoRenderBuffers() {
    }

    private static NeoRenderBuffers current;

    /** 取当前阶段的缓冲源；没有就新建。 */
    public static MultiBufferSource get() {
        if (current == null) {
            current = new NeoRenderBuffers();
        }
        return current.source;
    }

    /**
     * 提交并清理。渲染阶段末尾必须调用。
     */
    public static void flush() {
        if (current == null) {
            return;
        }
        try {
            current.source.endBatch();
        } catch (Exception ignored) {
            // 渲染失败不该崩游戏
        } finally {
            try {
                current.shared.close();
            } catch (Exception ignored) {
                // 忽略
            }
            current = null;
        }
    }
}