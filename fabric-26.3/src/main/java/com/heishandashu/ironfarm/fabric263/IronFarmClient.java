package com.heishandashu.ironfarm.fabric263;

import com.heishandashu.ironfarm.fabric263.event.RightClickHandler;
import com.heishandashu.ironfarm.fabric263.gui.SettingsScreen;
import com.heishandashu.ironfarm.fabric263.render.WorldOverlayRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 26.3 客户端入口。
 *
 * <p><b>与 1.21.1 模块的差异</b>（全部来自 26.3 的真实 API，已用编译器核实）：</p>
 *
 * <pre>
 *   职责        1.21.1                          26.3
 *   ----------  ------------------------------  ----------------------------------
 *   按键        KeyBindingHelper                 KeyMappingHelper（包名也换了）
 *   按键类别    String 分类键                    KeyMapping.Category（record）
 *   世界渲染    WorldRenderEvents.AFTER_ENTITIES LevelRenderEvents.BeforeGizmos
 *   界面绘制    render(GuiGraphics)               extractRenderState(GuiGraphicsExtractor)
 *   资源 ID     ResourceLocation                  Identifier
 *   实体包      npc.Villager                      npc.villager.Villager
 * </pre>
 *
 * <p><b>为什么渲染挂在 BeforeGizmos：</b>26.3 的 WorldRenderEvents 已经不存在，
 * 取而代之的是 LevelRenderEvents。BeforeGizmos 触发在
 * {@code submitFeatures} 里、{@code finalizeGizmoCollection()} 之前 ——
 * 此时 gizmo 收集器仍然开着，是唯一能安全提交世界几何的窗口
 * （见《我的世界开发skill》26.3-client-rendering.md 第 5 节）。</p>
 */
public final class IronFarmClient implements ClientModInitializer {

    public static final String MOD_ID = "ironfarm";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** 快捷键：打开设置。 */
    public static KeyMapping keyOpenSettings;

    /** 26.3 的按键分类是 record，需要一个 Identifier。 */
    public static KeyMapping.Category keyCategory;

    @Override
    public void onInitializeClient() {
        keyCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        // 26.3 的 KeyMapping 构造器收 Category 而不是字符串分类名
        keyOpenSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "ironfarm.key.open_settings",
                InputConstants.KEY_I,
                keyCategory));

        IronFarmClientState state = IronFarmClientState.get();
        state.loadSettings();
        state.loadStats();
        new ServerStatsCollector(state.stats()).register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            IronFarmClientState.get().tick(client);
            while (keyOpenSettings.consumeClick()) {
                // ⚠ 26.3 没有 Minecraft.screen 字段，界面在 gui 上
                client.gui.setScreen(new SettingsScreen(client.gui.screen()));
            }
        });

        // 世界渲染：必须挂在 BeforeGizmos（唯一的开放窗口）
        LevelRenderEvents.BEFORE_GIZMOS.register(WorldOverlayRenderer::onRender);

        RightClickHandler.register();

        // 漏斗销毁必须在**服务端主线程**上跑：
        // ServerChunkCache.getChunkNow 第一行就是
        //   if (Thread.currentThread() != this.mainThread) return null;
        // 从客户端 tick 直接调永远拿到 null（日志里 chunks=0 就是这个）。
        //
        // 用 MinecraftServer.executeIfPossible 把任务投递到服务端线程 ——
        // 这比依赖某个具体的 tick 事件更稳（不同加载器/版本的事件名不一致）。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var server = client.getSingleplayerServer();
            if (server == null) {
                return;
            }
            // 已经在服务端线程就直接跑（某些加载器会这样调），否则投递过去。
            if (Thread.currentThread() == server.getRunningThread()) {
                IronFarmClientState.get().tickHopperOnServer(server);
                return;
            }
            if (!IronFarmClientState.get().settings().woolHopperEnabled) {
                return;
            }
            // 每 tick 投递一次；ServerLevel 内部的节流会挡住多余的开销。
            server.executeIfPossible(
                    () -> IronFarmClientState.get().tickHopperOnServer(server));
        });

        LOG.info("[ironfarm] client initialized (26.3): overlay + status screen ready");
    }
}