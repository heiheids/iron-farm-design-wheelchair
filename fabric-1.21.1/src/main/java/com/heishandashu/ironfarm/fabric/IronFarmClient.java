package com.heishandashu.ironfarm.fabric;

import com.heishandashu.ironfarm.fabric.event.RightClickHandler;
import com.heishandashu.ironfarm.fabric.gui.SettingsScreen;
import com.heishandashu.ironfarm.fabric.render.WorldOverlayRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口。
 *
 * <p>接通三条链路：</p>
 * <ol>
 *   <li>每 tick 采集 → 判定 → 缓存（数据层）；</li>
 *   <li>世界渲染时画头顶三个图标（{@link WorldOverlayRenderer}）；</li>
 *   <li>右键村民打开状态页（{@link RightClickHandler}）。</li>
 * </ol>
 */
public final class IronFarmClient implements ClientModInitializer {

    public static final String MOD_ID = "ironfarm";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** 快捷键：打开设置。 */
    public static KeyMapping keyOpenSettings;

    private ServerStatsCollector statsCollector;

    @Override
    public void onInitializeClient() {
        keyOpenSettings = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "ironfarm.key.open_settings",
                GLFW.GLFW_KEY_I,
                "ironfarm.key.category"));

        // 服务端事件在单机时可用；多人时 ENTITY_LOAD 只对集成服务端生效，
        // 所以统计在多人下不完整 —— 这一点在文档里如实写了。
        IronFarmClientState state = IronFarmClientState.get();
        state.loadSettings();   // 先读配置，后面的对象构造都依赖它
        state.loadStats();      // 再读统计数据（关游戏不再清零）
        statsCollector = new ServerStatsCollector(state.stats());
        statsCollector.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            IronFarmClientState.get().tick(client);
            while (keyOpenSettings.consumeClick()) {
                client.setScreen(new SettingsScreen(client.screen));
            }
        });

        // 世界内渲染：头顶三个图标 + 线条 + 框。
        //
        // ⚠ 必须是 AFTER_ENTITIES。这是唯一同时满足
        //    「consumers() 可用」与「matrixStack() 可用」的事件。
        //
        //    consumers() 在 BEFORE_DEBUG_RENDER 之后为 null，
        //    而 LAST 与 AFTER_TRANSLUCENT 都排在它之后 → 两者都会静默地一帧不画。
        //    （我先挂 LAST，改到 AFTER_TRANSLUCENT，两次都是 null，
        //      直到回去核对 javadoc 的事件序才发现 —— 见经验文档坑 61。）
        WorldRenderEvents.AFTER_ENTITIES.register(WorldOverlayRenderer::onRender);

        // 右键村民：无交易界面时直接打开状态页，
        // 有交易界面时由 MerchantScreenMixin 在底部加按钮
        RightClickHandler.register();

        // 漏斗销毁必须在**服务端主线程**上跑：
        // ServerChunkCache.getChunkNow 会检查线程，非服务端线程返回 null。
        // 用 executeIfPossible 把任务投递到服务端线程。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var server = client.getSingleplayerServer();
            if (server == null) {
                return;
            }
            if (!IronFarmClientState.get().settings().woolHopperEnabled) {
                return;
            }
            if (Thread.currentThread() == server.getRunningThread()) {
                IronFarmClientState.get().tickHopperOnServer(server);
                return;
            }
            server.executeIfPossible(
                    () -> IronFarmClientState.get().tickHopperOnServer(server));
        });

        LOG.info("[ironfarm] client initialized: overlay + status screen ready");
    }
}
