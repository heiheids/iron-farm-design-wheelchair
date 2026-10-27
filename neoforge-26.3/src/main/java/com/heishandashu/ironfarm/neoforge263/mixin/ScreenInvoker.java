package com.heishandashu.ironfarm.neoforge263.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 开放 {@code Screen.addRenderableWidget}。
 *
 * <p>用 {@code @Invoker} 而不是 AccessTransformer，原因与 1.21.1 模块完全相同：
 * NeoForge 的 AT 处理在给这个泛型覆写方法提权时直接失败：</p>
 *
 * <pre>
 * ERROR Line: 320, ...CreateWorldScreen..&lt;T&gt;addRenderableWidget(T)
 *                  .......Screen..&lt;T&gt;addRenderableWidget(T)
 * </pre>
 *
 * <p>{@code @Invoker} 走 Mixin 的调用转发，不改类结构，没有这个问题。</p>
 */
@Mixin(net.minecraft.client.gui.screens.Screen.class)
public interface ScreenInvoker {

    @Invoker("addRenderableWidget")
    GuiEventListener ironfarm$addRenderableWidget(GuiEventListener widget);
}