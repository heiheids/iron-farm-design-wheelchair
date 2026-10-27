package com.heishandashu.ironfarm.neoforge;

import com.heishandashu.ironfarm.neoforge.event.RightClickHandler;
import com.heishandashu.ironfarm.neoforge.gui.SettingsScreen;
import com.heishandashu.ironfarm.neoforge.render.WorldOverlayRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
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
 * NeoForge 客户端入口。
 *
 * <p><b>与 Fabric 版的结构差异</b>（这是跨加载器移植的主要工作）：</p>
 *
 * <pre>
 *   职责        Fabric                          NeoForge
 *   ---------   ----------------------------    ------------------------------
 *   入口        ClientModInitializer            @Mod 构造器 + FMLClientSetupEvent
 *   每 tick     ClientTickEvents.END_CLIENT_TICK ClientTickEvent.Post
 *   世界渲染    WorldRenderEvents.AFTER_ENTITIES RenderLevelStageEvent (AFTER_ENTITIES)
 *   按键注册    KeyBindingHelper                 RegisterKeyMappingsEvent
 *   右键实体    UseEntityCallback                PlayerInteractEvent.EntityInteract
 * </pre>
 *
 * <p>业务逻辑（采集、判定、渲染、界面）与 Fabric 版完全相同 ——
 * 因为两边都是 Mojang 名，引擎模块更是直接共用同一份源码。</p>
 */
@Mod(value = "ironfarm", dist = Dist.CLIENT)
public final class IronFarmClient {

    public static final String MOD_ID = "ironfarm";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** 快捷键：打开设置。 */
    public static KeyMapping keyOpenSettings;

    @SuppressWarnings("unused")
    private final ModContainer container;

    public IronFarmClient(IEventBus modBus, ModContainer container) {
        this.container = container;

        // 模组总线：按键注册、客户端初始化
        modBus.addListener(this::onRegisterKeys);
        modBus.addListener(this::onClientSetup);

        // 游戏总线：每 tick、渲染、交互
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(RightClickHandler::onEntityInteract);

        // 漏斗销毁必须在**服务端主线程**上跑：
        // ServerChunkCache.getChunkNow 会检查线程，非服务端线程返回 null。
        NeoForge.EVENT_BUS.addListener(IronFarmClient::onServerTick);
    }

    // ==================================================================
    // 模组总线
    // ==================================================================

    private void onRegisterKeys(RegisterKeyMappingsEvent event) {
        keyOpenSettings = new KeyMapping(
                "ironfarm.key.open_settings",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_I,
                "ironfarm.key.category");
        event.register(keyOpenSettings);
    }

    /**
     * 客户端初始化。
     *
     * <p>顺序很重要：先读配置，后面的对象构造都依赖它。</p>
     */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            IronFarmClientState state = IronFarmClientState.get();
            state.loadSettings();
            state.loadStats();
            new ServerStatsCollector(state.stats()).register();
            LOG.info("[ironfarm] client initialized: overlay + status screen ready");
        });
    }

    // ==================================================================
    // 游戏总线
    // ==================================================================

    private void onClientTick(ClientTickEvent.Post event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        IronFarmClientState.get().tick(mc);
        while (keyOpenSettings != null && keyOpenSettings.consumeClick()) {
            mc.setScreen(new SettingsScreen(mc.screen));
        }
    }

    /**
     * 世界渲染。
     *
     * <p>⚠ 阶段必须是 {@code AFTER_ENTITIES}。</p>
     *
     * <p>Fabric 版在这上面错过两次：consumers() 在 BEFORE_DEBUG_RENDER
     * 之后为 null，而 LAST 与 AFTER_TRANSLUCENT 都排在它之后 ——
     * 挂错就静默地一帧都不画。</p>
     */
    /**
     * 服务端 tick：处理漏斗销毁（必须服务端主线程）。
     */
    private static void onServerTick(
            net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        IronFarmClientState.get().tickHopperOnServer(event.getServer());
    }

    private void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        WorldOverlayRenderer.onRender(event);
    }
}
