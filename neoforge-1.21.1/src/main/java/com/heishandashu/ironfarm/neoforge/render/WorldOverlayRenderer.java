package com.heishandashu.ironfarm.neoforge.render;

import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.panic.PanicAnalysis;
import com.heishandashu.ironfarm.engine.panic.PanicLink;
import com.heishandashu.ironfarm.engine.panic.ScaryMobSnapshot;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import com.heishandashu.ironfarm.neoforge.IronFarmClientState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

/**
 * 世界内的线条与矩形框绘制。
 *
 * <p>对应需求里的四个可视化开关：床连线、恐吓连线、可刷范围框、傀儡检测范围框。</p>
 *
 * <p><b>颜色约定</b>（与布局方案一致）：</p>
 * <ul>
 *   <li>床连线 —— 青色 {@code #3FE0FF}</li>
 *   <li>恐吓连线生效 —— 橙红 {@code #FF7040} 实线</li>
 *   <li>恐吓连线距离不够 —— 同色虚线</li>
 *   <li>恐吓连线视线被挡 —— 同色点线（更短的段）</li>
 *   <li>可刷范围框 —— 黄色 {@code #FFD24A}</li>
 *   <li>傀儡检测框 —— 紫色 {@code #B060FF}，透明度更低</li>
 * </ul>
 */
public final class WorldOverlayRenderer {

    /** 图标离村民头顶的高度（格）。 */
    private static final float ICON_HEIGHT = 0.6f;
    /** 图标边长（格）。 */
    private static final float ICON_SIZE = 0.35f;
    /** 三个图标之间的间距（格）。 */
    private static final float ICON_GAP = 0.08f;

    /** 床连线颜色。 */
    private static final int COLOR_BED_LINE = 0xFF3FE0FF;
    /** 恐吓连线颜色。 */
    private static final int COLOR_PANIC_LINE = 0xFFFF7040;
    /** 可刷范围框颜色。 */
    private static final int COLOR_SPAWN_BOX = 0xFFFFD24A;
    /** 傀儡检测框颜色。 */
    private static final int COLOR_GOLEM_BOX = 0xFFB060FF;

    /** 村民眼睛高度（连线端点）。 */
    private static final float VILLAGER_EYE = 1.62f;

    private WorldOverlayRenderer() {
    }

    /**
     * 世界渲染回调。
     *
     * <p><b>⚠ 必须注册在 {@code AFTER_ENTITIES}。</b>这是唯一同时满足
     * 「{@code consumers()} 可用」与「{@code matrixStack()} 可用」的事件。</p>
     *
     * <p>踩过两次坑，记清楚：</p>
     *
     * <p>事件顺序（来自 Fabric 的 {@code WorldRenderEvents} javadoc）：</p>
     * <pre>
     * START → AFTER_SETUP → BEFORE_ENTITIES → AFTER_ENTITIES
     *       → BEFORE_BLOCK_OUTLINE → BLOCK_OUTLINE
     *       → BEFORE_DEBUG_RENDER → AFTER_TRANSLUCENT → LAST → END
     * </pre>
     *
     * <p>{@code consumers()} 的契约：</p>
     * <blockquote>
     * null before {@code BEFORE_ENTITIES} and after {@code BEFORE_DEBUG_RENDER}
     * </blockquote>
     *
     * <p>所以：</p>
     * <ul>
     *   <li>第一次我挂 {@code LAST} → null。{@code if (buffers == null) return;} 每次命中，
     *       <b>一帧都没画</b>，且不报错。</li>
     *   <li>第二次我改挂 {@code AFTER_TRANSLUCENT} → <b>仍然是 null</b>，
     *       因为 {@code AFTER_TRANSLUCENT} 排在 {@code BEFORE_DEBUG_RENDER} 之后。
     *       这一改等于没改 —— 说明"改了一处"不等于"修好了"，必须回到契约本身去核对。</li>
     * </ul>
     *
     * <p>{@code matrixStack()} 的契约：只有 {@code AFTER_ENTITIES} 及之后才非 null。
     * 两条合起来，只有 {@code AFTER_ENTITIES} 可用。</p>
     *
     * <p>这个阶段在实体之后、方块实体与半透明之前，图标依然不会被实体遮挡。</p>
     *
     * <p>javadoc 还要求顶点坐标**相对摄像机**，下面的 {@code translate(-camPos)} 就是做这个。</p>
     */
    public static void onRender(RenderLevelStageEvent event) {
        // NeoForge 的 RenderLevelStageEvent 提供 PoseStack / Camera，
        // 但**不直接提供 MultiBufferSource** —— 需要自己按缓冲类型取。
        // 这与 Fabric 的 WorldRenderContext.consumers() 是关键差异。
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        IronFarmClientState state = IronFarmClientState.get();
        if (!state.settings().masterEnabled) {
            return;
        }
        com.heishandashu.ironfarm.neoforge.Diagnostics.onRenderCallback(state.lastGameTime());
        PoseStack pose = event.getPoseStack();
        net.minecraft.client.renderer.MultiBufferSource buffers = NeoRenderBuffers.get();
        if (pose == null || buffers == null) {
            com.heishandashu.ironfarm.neoforge.Diagnostics.onRenderNullConsumers();
            return;
        }
        Camera cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f camPos = cam.getPosition().toVector3f();

        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);

        // 1) 头顶图标
        if (state.settings().villagerOverlayEnabled) {
            renderIcons(pose, buffers, event, state, mc);
        }

        // 2) 床连线
        if (state.settings().bedLinesEnabled) {
            renderBedLines(pose, buffers, state, mc);
        }

        // 3) 恐吓连线
        if (state.settings().panicLinesEnabled) {
            renderPanicLines(pose, buffers, state, mc);
        }

        // 4) 可刷范围框 + 傀儡检测框
        if (state.settings().spawnBoxEnabled) {
            renderSpawnBoxes(pose, buffers, state);
        }
        if (state.settings().golemDetectBoxEnabled) {
            renderGolemDetectBoxes(pose, buffers, state);
        }

        pose.popPose();

        // NeoForge 没有现成的缓冲源，必须自己提交
        NeoRenderBuffers.flush();
    }

    // ==================================================================
    // 头顶图标
    // ==================================================================

    private static void renderIcons(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, RenderLevelStageEvent event,
                                    IronFarmClientState state, net.minecraft.client.Minecraft mc) {
        Camera cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        // billboard 用相机朝向四元数（见 IconRenderer.drawIcon 的说明）
        org.joml.Quaternionf billboard = cam.rotation();
        double maxDist = state.settings().overlayMaxDistance;

        java.util.List<OverlayCuller.Entry> entries = OverlayCuller.cull(
                state.reports(),
                OverlayCuller.fromLevel(mc),
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                maxDist);
        if (entries.isEmpty()) {
            com.heishandashu.ironfarm.neoforge.Diagnostics.onRenderEmptyList(state.reports().size());
            return;
        }
        com.heishandashu.ironfarm.neoforge.Diagnostics.onRenderDrew(entries.size());

        for (OverlayCuller.Entry e : entries) {
            float scale = scaleFor(e.distance, maxDist, state.settings().scaleByDistance);
            float size = ICON_SIZE * scale;
            float gap = ICON_GAP * scale;

            pose.pushPose();
            pose.translate(e.x, e.y + ICON_HEIGHT, e.z);

            // 三个图标必须**整组**旋转后再排布，否则它们会被转到不同平面、
            // 从侧面看互相穿插遮挡（用户截图看到的就是这个）。
            // 详见 IconRenderer.drawIconRow 的说明。
            IconRenderer.drawIconRow(
                    pose, buffers,
                    new int[]{IconRenderer.COL_CHECK, IconRenderer.COL_SKULL,
                            IconRenderer.COL_SPEAKER},
                    new int[]{e.checkColor, e.skullColor, e.speakerColor},
                    size, gap, billboard, 1.0f);

            pose.popPose();
        }
    }

    // ==================================================================
    // 床连线
    // ==================================================================

    private static void renderBedLines(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                                       IronFarmClientState state,
                                       net.minecraft.client.Minecraft mc) {
        double maxDist = state.settings().lineMaxDistance;
        double max2 = maxDist * maxDist;
        for (VillagerSnapshot v : state.villagers()) {
            if (v.bedPos == null) {
                continue;
            }
            Entity e = mc.level.getEntity(v.entityId);
            if (e == null) {
                continue;
            }
            double d2 = e.distanceToSqr(mc.player);
            if (d2 > max2) {
                continue;
            }
            // 脚部 → 床方块中心
            IconRenderer.drawLine(pose, buffers,
                    (float) e.getX(), (float) (e.getY() + 0.1), (float) e.getZ(),
                    v.bedPos.x + 0.5f, v.bedPos.y + 0.5f, v.bedPos.z + 0.5f,
                    COLOR_BED_LINE, 0.8f);
        }
    }

    // ==================================================================
    // 恐吓连线
    // ==================================================================

    private static void renderPanicLines(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                                         IronFarmClientState state,
                                         net.minecraft.client.Minecraft mc) {
        PanicAnalysis analysis = state.panicAnalysis();
        if (analysis == null || analysis.links.isEmpty()) {
            return;
        }
        double maxDist = state.settings().lineMaxDistance;
        double max2 = maxDist * maxDist;

        for (PanicLink link : analysis.links) {
            Entity villager = mc.level.getEntity(link.villagerEntityId);
            Entity mob = mc.level.getEntity(link.mobEntityId);
            if (villager == null || mob == null) {
                continue;
            }
            if (villager.distanceToSqr(mc.player) > max2) {
                continue;
            }
            float vx = (float) villager.getX();
            float vy = (float) (villager.getY() + VILLAGER_EYE);
            float vz = (float) villager.getZ();
            float mx = (float) mob.getX();
            float my = (float) (mob.getY() + mob.getEyeHeight());
            float mz = (float) mob.getZ();

            if (link.effective) {
                // 生效：实线
                IconRenderer.drawLine(pose, buffers, mx, my, mz, vx, vy, vz,
                        COLOR_PANIC_LINE, 0.9f);
            } else if (link.blockedByDistance()) {
                // 距离不够：虚线
                IconRenderer.drawDashedLine(pose, buffers, mx, my, mz, vx, vy, vz,
                        COLOR_PANIC_LINE, 0.45f);
            } else {
                // 视线被挡：点线（段更短、间隔更大）
                IconRenderer.drawDashedLine(pose, buffers, mx, my, mz, vx, vy, vz,
                        COLOR_PANIC_LINE, 0.25f);
            }
        }
    }

    // ==================================================================
    // 可刷范围框
    // ==================================================================

    /**
     * 画出所有村民的可刷范围。
     *
     * <p><b>⚠ 不要加"条件满足才画"的过滤</b>：第一版我加了
     * 「硬条件满足 且 正在被恐吓/闲聊」的过滤，理由写的是"避免满屏框"。
     * 结果用户看到的是**框时有时无** —— 村民的 panic / gossip 状态逐 tick 变化，
     * 框就跟着一闪一闪，根本没法用来判断布局。</p>
     *
     * <p>而且这个过滤本身就违背了开关的用途：玩家打开"显示可刷范围"，
     * 是想**规划机器布局**，跟村民此刻有没有被恐吓毫无关系。</p>
     *
     * <p>现在：只要开关打开、村民在扫描范围内，就画。</p>
     */
    private static void renderSpawnBoxes(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, IronFarmClientState state) {
        VersionProfile profile = state.profile;
        int hw = profile.spawnHalfWidth;
        int hh = profile.spawnHalfHeight;

        for (VillagerReport r : state.reports()) {
            BlockPos3 c = r.pos();
            // 外层：整个搜索体积（17×13×17）
            IconRenderer.drawBox(pose, buffers,
                    c.x - hw, c.y - hh, c.z - hw,
                    c.x + hw + 1, c.y + hh + 1, c.z + hw + 1,
                    COLOR_SPAWN_BOX, 0.35f);

            // 内层：真正合法的生成点，每格画一个很小的十字标记。
            //
            // ⚠ 只画外框是不够的。玩家看到框之后的下一个问题是
            //    "框里哪一格真的能刷" —— 尤其是台面被台阶/水/红石占了一部分的时候。
            //    引擎的 SpawnScanResult.validPoints() 已经算好了全部合法点，
            //    这里直接用，不需要再判断一次。
            SpawnScanResult scan = state.spawnScanOf(r.entityId());
            if (scan != null && scan.areaLoaded) {
                for (BlockPos3 p : scan.validPoints()) {
                    drawSpawnPointMarker(pose, buffers, p);
                }
            }
        }
    }

    /** 在一个合法生成点上画一个小小的立体十字。 */
    private static void drawSpawnPointMarker(
            PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, BlockPos3 p) {
        float x = p.x + 0.5f;
        float y = p.y + 0.05f;   // 贴在地面上方一点，避免与方块表面重叠闪烁
        float z = p.z + 0.5f;
        float s = 0.18f;
        IconRenderer.drawLine(pose, buffers, x - s, y, z, x + s, y, z, COLOR_SPAWN_BOX, 0.75f);
        IconRenderer.drawLine(pose, buffers, x, y, z - s, x, y, z + s, COLOR_SPAWN_BOX, 0.75f);
        IconRenderer.drawLine(pose, buffers, x, y, z, x, y + s, z, COLOR_SPAWN_BOX, 0.75f);
    }

    // ==================================================================
    // 傀儡检测框
    // ==================================================================

    /**
     * 画出村民的傀儡检测范围（±16 立方体）。
     *
     * <p>同样**不加条件过滤**（原因见 {@link #renderSpawnBoxes}）：
     * 第一版只给"硬条件已满足"的村民画，导致框时有时无。</p>
     *
     * <p>这个框是 33³，比可刷范围大得多，所以透明度压到 0.18 避免糊屏。</p>
     */
    private static void renderGolemDetectBoxes(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, IronFarmClientState state) {
        int r = state.profile.sensorRange;
        for (VillagerReport report : state.reports()) {
            BlockPos3 c = report.pos();
            IconRenderer.drawBox(pose, buffers,
                    c.x - r, c.y - r, c.z - r,
                    c.x + r + 1, c.y + r + 1, c.z + r + 1,
                    COLOR_GOLEM_BOX, 0.18f);
        }
    }

    // ==================================================================

    /**
     * 距离缩放：24 格内不缩放；24 格到最大距离之间线性缩到 70%。
     *
     * <p>为什么不缩到更小：太小就看不见了，还不如干脆不画。</p>
     */
    public static float scaleFor(double distance, double maxDistance, boolean enabled) {
        if (!enabled) {
            return 1.0f;
        }
        double farStart = Math.min(24.0, maxDistance * 0.75);
        if (distance <= farStart) {
            return 1.0f;
        }
        double t = (distance - farStart) / Math.max(0.001, maxDistance - farStart);
        if (t > 1.0) {
            t = 1.0;
        }
        return (float) (1.0 - 0.3 * t);
    }
}
