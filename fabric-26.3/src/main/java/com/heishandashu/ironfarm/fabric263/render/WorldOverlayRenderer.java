package com.heishandashu.ironfarm.fabric263.render;

import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.panic.PanicAnalysis;
import com.heishandashu.ironfarm.engine.panic.PanicLink;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import com.heishandashu.ironfarm.fabric263.IronFarmClientState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Fabric 26.3 的世界内绘制。
 *
 * <p>业务逻辑与 fabric-26.3 版**逐行相同** —— 都用 MC 自己的
 * {@code Gizmos} API，那是 Minecraft 提供的，不依赖加载器。
 * 唯一的差异是回调参数类型：
 * Fabric 给 {@code LevelRenderContext}，NeoForge 给 {@code RenderLevelStageEvent}。</p>
 *
 * <p>所以这里只保留一个 {@link #onRender(RenderLevelStageEvent)} 入口，
 * 其余方法与 Fabric 版一致。</p>
 */
public final class WorldOverlayRenderer {

    private static final int COLOR_BED_LINE = 0xFF3FE0FF;
    private static final int COLOR_PANIC_LINE = 0xFFFF7040;
    private static final int COLOR_SPAWN_BOX = 0xFFFFD24A;
    private static final int COLOR_GOLEM_BOX = 0xFFB060FF;

    /**
     * 线宽。
     *
     * <p><b>⚠ 单位是屏幕像素，不是方块！</b></p>
     *
     * <p>我第一版按 1.21.1 的经验填了 0.11（那版线宽单位是"格"），
     * 结果框线细到看不出是实线，用户形容为"非常细，看上去像虚线"。</p>
     *
     * <p>核实 26.3 的 {@code GizmoStyle} 源码：默认值就是
     * {@code DEFAULT_WIDTH = 2.5F}，而 {@code Gizmos.line} 的默认宽度是 3.0F。
     * 所以这个参数是**屏幕像素宽度**，合理范围 1~4。</p>
     *
     * <p>取 2.5 —— 与原版画碰撞箱的框线一致。</p>
     */
    private static final float LINE_WIDTH = 2.5f;
    private static final float VILLAGER_EYE = 1.62f;

    private WorldOverlayRenderer() {
    }

    public static void onRender(LevelRenderContext event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        IronFarmClientState state = IronFarmClientState.get();
        if (!state.settings().masterEnabled) {
            return;
        }

        if (state.settings().villagerOverlayEnabled) {
            renderIcons(state, mc, event.poseStack(), event.submitNodeCollector(), event);
        }
        if (state.settings().bedLinesEnabled) {
            renderBedLines(state, mc);
        }
        if (state.settings().panicLinesEnabled) {
            renderPanicLines(state, mc);
        }
        if (state.settings().spawnBoxEnabled) {
            renderSpawnBoxes(state);
        }
        if (state.settings().golemDetectBoxEnabled) {
            renderGolemDetectBoxes(state);
        }
    }


    /**
     * 头顶三个标记，**各自独立着色**。
     *
     * <p><b>语义定义（用户逐条确认，这就是需求本身）：</b></p>
     * <ul>
     *   <li>对钩 = 成年 + 睡过觉 + 30 秒内没识别到铁傀儡 → 绿</li>
     *   <li>骷髅 = 附近有僵尸等威胁正在恐吓 → 绿</li>
     *   <li>喇叭 = 闲聊刷铁傀儡的前提满足 → 绿</li>
     * </ul>
     *
     * <p><b>为什么改回 conditionIcon()：</b> 上一版我改成
     * {@code panicFarmReady() || gossipFarmReady()}，想解决"头顶与点进去不同步"。
     * 那是改错了方向 —— 对钩本来就不代表"整台机器能刷"，
     * 它只代表个体条件。用 ready 判定会让对钩在"生成点不合法"时变红，
     * 而状态页明明写着"睡过 / 没有傀儡"都满足，这才是真正的不一致。</p>
     *
     * <p><b>为什么分三次发 billboardText：</b> 26.3 的 API 一次一个颜色。
     * 三个标记各自有意义，用一个颜色合并会丢信息 ——
     * 比如"睡过了但没在恐吓"，合并后整行红，玩家会误以为睡眠有问题。</p>
     */
    /**
     * 头顶三个图标（纹理图集）。
     *
     * <p><b>语义定义（用户逐条确认，这就是需求本身）：</b></p>
     * <ul>
     *   <li>对钩 = 成年 + 睡过觉 + 30 秒内没识别到铁傀儡 → 绿</li>
     *   <li>骷髅 = 附近有僵尸等威胁正在恐吓 → 绿</li>
     *   <li>喇叭 = 闲聊刷铁傀儡的前提满足 → 绿</li>
     * </ul>
     *
     * <p><b>为什么用纹理而不是文字符号：</b>我一度改成 ✓✗?☠♪ 文本，
     * 因为 26.3 的 Gizmos 没有纹理四边形。但用户要的是原来的像素图标
     * （绿勾 / 红骷髅 / 红喇叭）。改用提交式渲染管线的
     * {@code submitCustomGeometry} 就能画任意纹理 —— 见
     * {@link IconAtlasRenderer} 的说明。</p>
     *
     * <p>三个图标各自独立着色，不合并：合并会丢信息
     * （比如"睡过了但没在恐吓"，整行红会让人以为睡眠也有问题）。</p>
     */
    private static void renderIcons(IronFarmClientState state, Minecraft mc, PoseStack pose,
                                    SubmitNodeCollector collector,
                                    net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        var camState = context.levelState().cameraRenderState;
        Vec3 cam = camState.pos;
        org.joml.Quaternionf orientation = camState.orientation;
        double maxDist = state.settings().overlayMaxDistance;
        double max2 = maxDist * maxDist;

        for (VillagerReport r : state.reports()) {
            Entity e = mc.level.getEntity(r.entityId());
            if (e == null || e.distanceToSqr(mc.player) > max2) {
                continue;
            }
            // ⚠ 必须是相机相对坐标：本事件的 PoseStack 在相机空间，
            // 传世界坐标会把图标画到几百格外（表现为完全看不见）。
            // 原版 submitEntities 就是 state.x - camX。
            Vec3 center = new Vec3(
                    e.getX() - cam.x,
                    e.getY() + e.getBbHeight() + 0.62 - cam.y,
                    e.getZ() - cam.z);

            IconAtlasRenderer.drawIconRow(
                    collector, pose, center, orientation,
                    new int[]{
                            IconAtlasRenderer.COL_CHECK,
                            IconAtlasRenderer.COL_SKULL,
                            IconAtlasRenderer.COL_SPEAKER},
                    new int[]{
                            colorOf(r.conditionIcon()),
                            colorOf(r.panicking()
                                    ? VillagerReport.Icon.CHECK
                                    : VillagerReport.Icon.CROSS),
                            colorOf(r.gossipIcon())},
                    0.34f,   // 单格边长（格）
                    0.05f,   // 间距
                    1.0f);   // 不透明
        }
    }

    private static int colorOf(VillagerReport.Icon icon) {
        switch (icon) {
            case CHECK:
                return 0xFF4CD964;   // 绿：条件具备
            case CROSS:
                return 0xFFE5484D;   // 红：确定不满足
            default:
                return 0xFFA0A0A8;   // 灰：数据不可得（不能画成红）
        }
    }


    private static void renderBedLines(IronFarmClientState state, net.minecraft.client.Minecraft mc) {
        double maxDist = state.settings().lineMaxDistance;
        double max2 = maxDist * maxDist;
        for (VillagerSnapshot v : state.villagers()) {
            if (v.bedPos == null) {
                continue;
            }
            var e = mc.level.getEntity(v.entityId);
            if (e == null || e.distanceToSqr(mc.player) > max2) {
                continue;
            }
            Gizmos.line(
                    new Vec3(e.getX(), e.getY() + 0.1, e.getZ()),
                    new Vec3(v.bedPos.x + 0.5, v.bedPos.y + 0.5, v.bedPos.z + 0.5),
                    COLOR_BED_LINE,
                    LINE_WIDTH);
        }
    }

    /**
     * 恐吓连线。
     *
     * <p><b>三种状态用三种线型表示（与 1.21.1 版一致）：</b></p>
     * <pre>
     *   生效        实线      亮橙红
     *   距离不够    虚线      短段 + 等间隔
     *   视线被挡    点线      更短的段 + 更大的间隔
     * </pre>
     *
     * <p><b>为什么必须用线型而不只是颜色：</b>26.3 的 {@code Gizmos.line}
     * 没有虚线参数，我第一版只改了颜色深浅。用户反馈
     * "视线受阻威胁失效的时候没有变成虚线" —— 颜色深浅在远处分不出来，
     * 而虚线/点线在任何距离都能一眼区分。</p>
     *
     * <p><b>怎么画虚线：</b>{@code Gizmos.line} 一次画一段。
     * 把整条线按参数切成 N 段，只画偶数段即可。
     * 段长用"格"算（世界坐标），不随距离变化，所以近处远处都是同样的虚线。</p>
     */
    private static void renderPanicLines(IronFarmClientState state, Minecraft mc) {
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
            Vec3 from = new Vec3(mob.getX(), mob.getY() + mob.getEyeHeight(), mob.getZ());
            Vec3 to = new Vec3(villager.getX(), villager.getY() + VILLAGER_EYE, villager.getZ());

            if (link.effective) {
                // 生效：实线
                Gizmos.line(from, to, COLOR_PANIC_LINE, LINE_WIDTH);
            } else if (link.blockedByDistance()) {
                // 距离不够：虚线（段长 0.55 格，间隔 0.55 格）
                dashedLine(from, to, COLOR_PANIC_LINE, LINE_WIDTH, 0.55);
            } else {
                // 视线被挡：点线（段更短、间隔更大）
                dashedLine(from, to, COLOR_PANIC_LINE, LINE_WIDTH * 0.85f, 0.25);
            }
        }
    }

    /**
     * 画一条虚线。
     *
     * <p>沿世界坐标从 from 走到 to，每 {@code dash} 格画一段、跳过 {@code dash} 格。
     * 这样段长与距离无关，近处远处都是同样的虚线观感。</p>
     *
     * <p>段数上限 120，防止超长线生成过多 gizmo（每段都是一个独立 gizmo）。</p>
     */
    private static void dashedLine(Vec3 from, Vec3 to, int color, float width, double dash) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double total = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (total < 1.0e-4 || dash <= 0) {
            return;
        }
        // 单位方向
        double ux = dx / total;
        double uy = dy / total;
        double uz = dz / total;

        double period = dash * 2.0;
        int count = (int) Math.ceil(total / period);
        count = Math.min(count, 120);

        for (int i = 0; i < count; i++) {
            double s0 = i * period;
            double s1 = Math.min(s0 + dash, total);
            if (s1 <= s0) {
                break;
            }
            Vec3 a = new Vec3(
                    from.x + ux * s0,
                    from.y + uy * s0,
                    from.z + uz * s0);
            Vec3 b = new Vec3(
                    from.x + ux * s1,
                    from.y + uy * s1,
                    from.z + uz * s1);
            Gizmos.line(a, b, color, width);
        }
    }
    private static void renderSpawnBoxes(IronFarmClientState state) {
        VersionProfile profile = state.profile;
        int hw = profile.spawnHalfWidth;
        int hh = profile.spawnHalfHeight;
        for (VillagerReport r : state.reports()) {
            BlockPos3 c = r.pos();
            AABB box = new AABB(
                    c.x - hw, c.y - hh, c.z - hw,
                    c.x + hw + 1, c.y + hh + 1, c.z + hw + 1);
            Gizmos.cuboid(box, GizmoStyle.stroke(COLOR_SPAWN_BOX, LINE_WIDTH));

            SpawnScanResult scan = state.spawnScanOf(r.entityId());
            if (scan != null && scan.areaLoaded) {
                for (BlockPos3 p : scan.validPoints()) {
                    double x = p.x + 0.5, y = p.y + 0.05, z = p.z + 0.5;
                    double s = 0.18;
                    Gizmos.line(new Vec3(x - s, y, z), new Vec3(x + s, y, z), COLOR_SPAWN_BOX, 1.5f);
                    Gizmos.line(new Vec3(x, y, z - s), new Vec3(x, y, z + s), COLOR_SPAWN_BOX, 1.5f);
                }
            }
        }
    }

    private static void renderGolemDetectBoxes(IronFarmClientState state) {
        int r = state.profile.sensorRange;
        for (VillagerReport report : state.reports()) {
            BlockPos3 c = report.pos();
            AABB box = new AABB(
                    c.x - r, c.y - r, c.z - r,
                    c.x + r + 1, c.y + r + 1, c.z + r + 1);
            Gizmos.cuboid(box, GizmoStyle.stroke(0xA0B060FF, LINE_WIDTH));
        }
    }
}
