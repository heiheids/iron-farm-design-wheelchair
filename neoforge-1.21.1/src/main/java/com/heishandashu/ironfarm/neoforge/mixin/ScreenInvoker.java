package com.heishandashu.ironfarm.neoforge.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 开放 {@code Screen.addRenderableWidget}。
 *
 * <p><b>为什么用 {@code @Invoker} 而不是 AccessTransformer：</b></p>
 *
 * <p>Fabric 侧用的是 AccessWidener，一句话就够。但 NeoForge 的 AT 处理
 * 在这个方法上会直接失败：</p>
 *
 * <pre>
 * ERROR Line: 320, ...CreateWorldScreen..&lt;T&gt;addRenderableWidget(T)
 *                  .......Screen..&lt;T&gt;addRenderableWidget(T)
 * </pre>
 *
 * <p>原因是它被大量子类以泛型方式覆写（{@code <T extends GuiEventListener & Renderable>}），
 * AT 在给父类方法提权时会与这些覆写的签名发生冲突。</p>
 *
 * <p>{@code @Invoker} 走的是 Mixin 自己的调用转发机制，
 * 不修改类结构，因此没有这个问题 —— 这也是 Mixin 官方推荐的
 * "调用 protected 成员"的做法。</p>
 *
 * <p>⚠ 必须写进 mixins.json 才有生效。</p>
 */
@Mixin(net.minecraft.client.gui.screens.Screen.class)
public interface ScreenInvoker {

    /**
     * 等价于 {@code Screen.addRenderableWidget(widget)}。
     *
     * <p>它同时把控件加进 children / renderables / narratables 三张表。
     * 只加 children 的话按钮会**画出来但点不动**（Fabric 版踩过）。</p>
     */
    @Invoker("addRenderableWidget")
    GuiEventListener ironfarm$addRenderableWidget(GuiEventListener widget);
}