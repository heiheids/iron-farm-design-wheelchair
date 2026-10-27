package com.heishandashu.ironfarm.neoforge263;

import com.heishandashu.ironfarm.neoforge263.event.RightClickHandler;
import com.heishandashu.ironfarm.neoforge263.gui.SettingsScreen;
import com.heishandashu.ironfarm.neoforge263.render.WorldOverlayRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 26.3 客户端入口。
 *
 * <p><b>好消息：26.3 的 NeoForge 事件类名与 21.1 完全一致</b> ——
 * {@code ClientTickEvent.Post}、{@code RenderLevelStageEvent}、
 * {@code RegisterKeyMappingsEvent} 都能直接编过。
 * 这些没有跟着 GUI 一起重写，因为它们属于世界渲染管线，不是 GUI 提取模型。</p>
 *
 * <p>真正变的是三处，与 Fabric 26.3 相同：</p>
 * <ul>
 *   <li>界面：{@code render} → {@code extractRenderState}，
 *       {@code GuiGraphics} → {@code GuiGraphicsExtractor}</li>
 *   <li>资源 ID：{@code ResourceLocation} → {@code Identifier}</li>
 *   <li>界面切换：{@code Minecraft.setScreen} → {@code Minecraft.gui.setScreen}</li>
 * </ul>
 */
@Mod(value = "ironfarm", dist = Dist.CLIENT)
public final class IronFarmClient {

    public static final String MOD_ID = "ironfarm";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static KeyMapping keyOpenSettings;
    public static KeyMapping.Category keyCategory;

    @SuppressWarnings("unused")
    private final ModContainer container;

    public IronFarmClient(IEventBus modBus, ModContainer container) {
        this.container = container;
        modBus.addListener(this::onRegisterKeys);
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevel);
        // 头顶纹理图标：RenderLevelStageEvent 不给 SubmitNodeCollector，
        // 必须用这个专门的事件（见 WorldOverlayRenderer.onCustomGeometry）。
        NeoForge.EVENT_BUS.addListener(WorldOverlayRenderer::onCustomGeometry);
        NeoForge.EVENT_BUS.addListener(RightClickHandler::onEntityInteract);

        // 漏斗销毁必须挂在**服务端 tick** 上。
        //
        // ServerChunkCache.getChunkNow 第一行就是
        //   if (Thread.currentThread() != this.mainThread) return null;
        // 所以从客户端 tick 调它永远拿到 null（日志里 chunks=0 就是这个）。
        NeoForge.EVENT_BUS.addListener(IronFarmClient::onServerTick);
    }

    private void onRegisterKeys(RegisterKeyMappingsEvent event) {
        keyCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        keyOpenSettings = new KeyMapping(
                "ironfarm.key.open_settings",
                InputConstants.KEY_I,
                keyCategory);
        event.register(keyOpenSettings);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            IronFarmClientState state = IronFarmClientState.get();
            state.loadSettings();
            state.loadStats();
            new ServerStatsCollector(state.stats()).register();
            LOG.info("[ironfarm] client initialized (NF 26.3): overlay + status screen ready");
        });
    }

    private void onClientTick(ClientTickEvent.Post event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        IronFarmClientState.get().tick(mc);
        while (keyOpenSettings != null && keyOpenSettings.consumeClick()) {
            mc.gui.setScreen(new SettingsScreen(mc.gui.screen()));
        }
    }

    /**
     * 世界渲染。
     *
     * <p><b>26.3 把 {@code Stage} 枚举拆成了独立的事件子类</b>，
     * 不能再写 {@code getStage() == Stage.AFTER_ENTITIES} —— 那个方法已经不存在。
     * 现在按阶段分别监听不同的子类事件。</p>
     *
     * <p>为什么选 {@code AfterTranslucentFeatures}：它在实体与半透明几何之后、
     * 粒子之前，此时深度缓冲已写入，图标不会被实体遮挡，
     * 又还没到粒子阶段（粒子之后深度测试的行为会变）。
     * 这与 1.21.1 的 {@code AFTER_ENTITIES} 语义最接近。</p>
     */
    /**
     * 服务端 tick：处理漏斗销毁。
     *
     * <p>⚠ 必须在服务端主线程 —— {@code getChunkNow} 会检查线程，
     * 客户端线程直接返回 null。</p>
     *
     * <p>遍历所有已加载世界，各自处理（支持多维度）。</p>
     */
    private static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        IronFarmClientState.get().tickHopperOnServer(event.getServer());
    }

    private void onRenderLevel(RenderLevelStageEvent.AfterTranslucentFeatures event) {
        WorldOverlayRenderer.onRender(event);
    }
}