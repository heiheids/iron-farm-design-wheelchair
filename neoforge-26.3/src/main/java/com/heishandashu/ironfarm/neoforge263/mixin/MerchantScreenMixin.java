package com.heishandashu.ironfarm.neoforge263.mixin;

import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.neoforge263.IronFarmClientState;
import com.heishandashu.ironfarm.neoforge263.gui.VillagerStatusScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在交易界面底部追加一个「村民状态」按钮。
 *
 * <p><b>为什么用 Mixin 而不是"子类化 + 替换屏幕"</b>：
 * 我第一版写了个 {@code IronFarmMerchantScreen extends MerchantScreen}，
 * 结果发现 {@code MerchantScreen} 的构造参数（Inventory、Component）
 * 没有 getter，只能在"打开界面的那一刻"截下来。
 * 而一旦采用替换屏幕的做法，原屏幕的滚动位置、选中的交易项、
 * 拖拽状态全部丢失 —— 玩家会看到界面"跳一下"。</p>
 *
 * <p>Mixin 在 {@code init()} 的 RETURN 处注入，原版刚把所有控件建好，
 * 我们只是追加一个，**原屏幕对象完全不变**，状态全部保留。</p>
 *
 * <p>注入点选 {@code init} 而不是 {@code render}：控件只能在 init 阶段添加，
 * 在 render 里加会每帧重复添加，最后卡死。</p>
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin implements ScreenInvoker {

    /** 记录本次交易界面对应的村民 id，避免每帧反查。 */
    @Unique
    private int ironfarm$villagerId = -1;

    /**
     * 原版 init() 结束时追加按钮。
     */
    @Inject(method = "init", at = @At("RETURN"))
    private void ironfarm$addStatusButton(CallbackInfo ci) {
        MerchantScreen self = (MerchantScreen) (Object) this;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        IronFarmClientState state = IronFarmClientState.get();
        if (!state.settings().masterEnabled) {
            return;
        }

        // 找最近的、有交易界面且在 8 格内的村民
        int vid = ironfarm$findVillager(mc);
        if (vid < 0) {
            return;
        }
        // 只有在引擎里真的扫到过这只村民才给按钮，否则点开是空的
        VillagerReport report = state.reportOf(vid);
        if (report == null) {
            return;
        }
        this.ironfarm$villagerId = vid;

        // MerchantScreen 的布局字段是 protected：leftPos / topPos / imageWidth / imageHeight。
        // addRenderableWidget 也是 protected，**不能**通过 (Screen)(Object)this 强转调用
        // （编译期报"protected 访问控制"，因为强转后的静态类型 Screen 不持有该访问权）。
        // 所以这里用一个 @Unique 的实例方法转发，借助 Mixin 合入后的本类作用域访问。
        int bx = ironfarm$leftPos() + ironfarm$imageWidth() / 2 - 40;
        int by = ironfarm$topPos() + ironfarm$imageHeight() + 2;

        ironfarm$addWidget(Button.builder(
                        Component.translatable("ironfarm.gui.villager_status"),
                        b -> mc.gui.setScreen(new VillagerStatusScreen(
                                (MerchantScreen) (Object) this, vid)))
                .bounds(bx, by, 80, 16)
                .build());
    }

    /**
     * 往交易界面加按钮。
     *
     * <p>必须用 {@code addRenderableWidget}：它同时把控件加进
     * children / renderables / narratables 三张表。
     * 只加 children 的话按钮会**画出来但点不动**（我第一版就是这么错的）。</p>
     *
     * <p>它是 protected，所以通过 accessWidener 开放，
     * 再用本方法（运行在 MerchantScreen 作用域内）调用。</p>
     */
    @Unique
    private void ironfarm$addWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
        // 走 @Invoker（ScreenInvoker），不依赖 AccessTransformer ——
        // NeoForge 的 AT 无法给被泛型覆写的 addRenderableWidget 提权。
        ironfarm$addRenderableWidget(widget);
    }

    @Unique
    private int ironfarm$findVillager(Minecraft mc) {
        Villager nearest = null;
        double best = Double.MAX_VALUE;
        for (Villager v : mc.level.getEntitiesOfClass(
                Villager.class, mc.player.getBoundingBox().inflate(8.0))) {
            double d = v.distanceToSqr(mc.player);
            if (d < best) {
                best = d;
                nearest = v;
            }
        }
        return nearest == null ? -1 : nearest.getId();
    }

    // ---- 访问 MerchantScreen 的布局字段 ----
    //
    // ⚠⚠ 这里刻意**不用 @Shadow**，改用 AccessWidener 开放字段。
    //
    // 崩溃实录（用户："打开漏斗崩溃了"，实际是打开交易界面）：
    //     InvalidMixinException: @Shadow field field_2776 was not located
    //     in the target class net.minecraft.class_492. No refMap loaded.
    //
    // 原因：Mixin 的 @Shadow 靠 refmap（named → intermediary 对照表）
    // 把源码里的 leftPos 翻译成运行时的 field_2776。而 Loom 1.18 【默认不再
    // 生成 refmap】（它自己的提示：
    //   "The mixin annotation is no longer enabled by default,
    //    you should remove any loom.mixin configuration"），
    // 于是 @Shadow 找不到目标字段，整个类变换失败 → 客户端崩溃。
    //
    // 为什么之前没暴露：这个 Mixin 是 @Inject 到 init()，
    // 只有**第一次打开交易界面**时那个类才会被加载并变换。
    // 不碰村民交易就永远走不到。
    //
    // 现在的做法：用 AW 把这 4 个 protected 字段变成 public，
    // 直接强转读取，完全不依赖 refmap。
    // 好处：少一个会静默失效的机制，打包产物也更干净。

    @Unique
    private net.minecraft.client.gui.screens.inventory.MerchantScreen ironfarm$self() {
        return (net.minecraft.client.gui.screens.inventory.MerchantScreen) (Object) this;
    }

    @Unique
    private int ironfarm$leftPos() {
        return ironfarm$self().leftPos;
    }

    @Unique
    private int ironfarm$topPos() {
        return ironfarm$self().topPos;
    }

    @Unique
    private int ironfarm$imageWidth() {
        return ironfarm$self().imageWidth;
    }

    @Unique
    private int ironfarm$imageHeight() {
        return ironfarm$self().imageHeight;
    }
}
