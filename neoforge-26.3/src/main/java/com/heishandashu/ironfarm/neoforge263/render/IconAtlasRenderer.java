package com.heishandashu.ironfarm.neoforge263.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 用**纹理图集**画头顶图标（26.3 NeoForge 版）。
 *
 * <p><b>为什么不用 {@code Gizmos.billboardText}：</b></p>
 *
 * <p>26.3 的 gizmo 只有直线、方块、点、**文字**，没有纹理四边形。
 * 我第一版因此把图标换成了文字符号（✓✗?☠♪），
 * 但用户明确要求原来的像素图标（绿勾 / 红骷髅 / 红喇叭）。</p>
 *
 * <p>正确路径是 26.3 的提交式渲染管线：
 * {@code SubmitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> ...)}，
 * 自己发四边形顶点。原版的 {@code ExperienceOrbRenderer} 就是这么画经验球贴图的，
 * 这里照它的写法。</p>
 *
 * <p>⚠ 关键点（都在 ExperienceOrbRenderer 里验证过）：</p>
 * <ul>
 *   <li>用 {@code RenderTypes.entityTranslucent(IDENTIFIER)} —— 支持半透明，</li>
 *   <li>顶点要 {@code setOverlay(NO_OVERLAY)} + {@code setLight(全亮)} +
 *       {@code setNormal(pose, 0, 1, 0)}，缺一个就可能不显示或全黑，</li>
 *   <li>billboard 用 {@code poseStack.rotate(camera.rotation())}，
 *       与 1.21.1 版的做法一致（不加 conjugate）。</li>
 * </ul>
 */
public final class IconAtlasRenderer {

    /** 图集：三格横排（对钩 / 骷髅 / 喇叭），每格 32px。 */
    private static final Identifier ATLAS =
            Identifier.fromNamespaceAndPath("ironfarm", "textures/icon_atlas.png");

    /** 必须与 tools/MakeIconAtlas.java 的 TILE 一致。 */
    private static final int TILE = 32;
    private static final int ATLAS_WIDTH = TILE * 3;
    private static final int ATLAS_HEIGHT = TILE;

    /** 满亮度：这是我们自己的指示物，不该受光照影响。 */
    private static final int FULL_BRIGHT = 0xF000F0;

    public static final int COL_CHECK = 0;
    public static final int COL_SKULL = 1;
    public static final int COL_SPEAKER = 2;

    private static RenderType renderType;

    private IconAtlasRenderer() {
    }

    private static RenderType type() {
        if (renderType == null) {
            renderType = RenderTypes.entityTranslucent(ATLAS);
        }
        return renderType;
    }

    /**
     * 画一排图标（左中右三格），整排共用一次 billboard 旋转。
     *
     * @param collector 渲染上下文给的提交器
     * @param pose      当前 pose（已在世界坐标系，含相机偏移）
     * @param center    图标行的世界坐标中心
     * @param columns   三格各自用图集的哪一列
     * @param colors    三格各自的 ARGB 颜色（白 = 原色，其它色会乘上去）
     * @param size      单格边长（格）
     * @param gap       间距（格）
     * @param alpha     整体透明度
     */
    public static void drawIconRow(SubmitNodeCollector collector,
                                   PoseStack pose,
                                   Vec3 center,
                                   Quaternionf orientation,
                                   int[] columns,
                                   int[] colors,
                                   float size,
                                   float gap,
                                   float alpha) {
        // ⚠ 用传入的 orientation，不要自己从 Camera 取。
        //
        // 本事件的 PoseStack 在**相机空间**（单位矩阵），
        // 与之配套的朝向必须是 cameraRenderState.orientation ——
        // 那是这一帧实际使用的渲染朝向。
        // 从 Camera 现取 rotation() 在部分情况下（第三人称、调试相机、
        // 回放）与它不一致，表现为图标朝向错误或完全侧对镜头（看不见）。
        Quaternionf billboard = new Quaternionf(orientation);
        float step = size + gap;

        pose.pushPose();
        pose.translate(center.x, center.y, center.z);
        // 整排只转一次：转好之后在局部坐标系里横向排布，
        // 三个图标就永远保持同一朝向（1.21.1 版踩过"各转各的导致错位"）。
        pose.rotate(billboard);

        collector.submitCustomGeometry(pose, type(), (p, buffer) -> {
            emit(buffer, p, -step, 0f, columns[0], colors[0], size, alpha);
            emit(buffer, p, 0f, 0f, columns[1], colors[1], size, alpha);
            emit(buffer, p, step, 0f, columns[2], colors[2], size, alpha);
        });

        pose.popPose();
    }

    /** 在当前 pose 下发一个图标的四边形。 */
    private static void emit(VertexConsumer buffer, PoseStack.Pose pose,
                             float ox, float oy, int column, int color,
                             float size, float alpha) {
        float h = size * 0.5f;
        float x0 = ox - h;
        float x1 = ox + h;
        float y0 = oy - h;
        float y1 = oy + h;

        float u0 = (float) column * TILE / ATLAS_WIDTH;
        float u1 = (float) (column + 1) * TILE / ATLAS_WIDTH;
        // ⚠ v0 = 纹理顶部、v1 = 纹理底部。
        //    1.21.1 版第一稿把"屏幕上方的顶点"配了 v1，导致图标上下颠倒。
        float v0 = 0.0f;
        float v1 = 1.0f;

        int a = (int) (((color >>> 24) & 0xFF) * alpha);
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;

        // 左上 → 左下 → 右下 → 右上（U/V 与位置严格对应：上=0，下=1）
        vertex(buffer, pose, r, g, b, a, x0, y1, u0, v0);
        vertex(buffer, pose, r, g, b, a, x0, y0, u0, v1);
        vertex(buffer, pose, r, g, b, a, x1, y0, u1, v1);
        vertex(buffer, pose, r, g, b, a, x1, y1, u1, v0);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose,
                               int r, int g, int b, int a,
                               float x, float y, float u, float v) {
        buffer.addVertex(pose, x, y, 0.0f)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
    }
}