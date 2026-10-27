package com.heishandashu.ironfarm.fabric.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 头顶图标的绘制原语。
 *
 * <p><b>为什么用"白色贴图 + 顶点着色"</b>：三个图标 × 三种状态 = 9 种组合，
 * 如果每个组合一张图，改配色要重画 9 张。白色蒙版 + 顶点色之后只要 3 张，
 * 颜色由这里的常量决定，改一次就全变。</p>
 *
 * <p>⚠ 1.21.1 的 {@link VertexConsumer} 用的是
 * {@code addVertex(...)} / {@code setColor(...)} / {@code setUv(...)} 链式 API，
 * **不是** 1.20 及更早的 {@code vertex(...).color(...).endVertex()}。
 * 用旧写法会报 "找不到符号 vertex(Matrix4f,float,float,int)"。</p>
 */
public final class IconRenderer {

    /** 贴图集位置。 */
    public static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath("ironfarm", "textures/icon_atlas.png");

    /** 单格尺寸（像素）。必须与 tools/MakeIconAtlas.java 的 TILE 一致。 */
    public static final int TILE = 32;
    /** 贴图集总宽（像素）：3 格。 */
    public static final int ATLAS_WIDTH = TILE * 3;

    /** 图标在贴图集里的列索引。 */
    public static final int COL_CHECK = 0;
    public static final int COL_SKULL = 1;
    public static final int COL_SPEAKER = 2;

    // ---- 状态配色 ----
    /** 绿色：条件满足。 */
    public static final int COLOR_OK = 0xFF4CD964;
    /** 红色：确定不满足。 */
    public static final int COLOR_BAD = 0xFFE5484D;
    /** 灰色：数据不可得（多人游戏里的常态，必须与"不满足"区分开）。 */
    public static final int COLOR_UNKNOWN = 0xFFA0A0A8;
    /**
     * 黄色：个体条件都满足，但群体条件或生成点还差。
     *
     * <p>没有这个色的话，那种"看起来什么都对但就是不刷"的村民
     * 会显示红叉，玩家会去查一个根本没问题的地方。</p>
     */
    public static final int COLOR_WARN = 0xFFFFD24A;

    /** 满亮度，避免图标在暗处看不见 —— 这是我们自己的指示物，不该受光照影响。 */
    private static final int FULL_BRIGHT = 0xF000F0;

    private IconRenderer() {
    }

    /**
     * 在给定位置上画一个面朝镜头的图标。
     *
     * <p><b>⚠ billboard 的朝向：直接 {@code mulPose(cameraOrientation)}，不要取共轭。</b></p>
     *
     * <p>这里错了两次，都记下来：</p>
     * <ol>
     *   <li><b>手动算 yaw/pitch</b>（{@code Axis.YP.rotationDegrees(-camYaw)} 之类）——
     *       图标完全看不见。因为 {@code PoseStack} 进入世界渲染阶段时
     *       已经包含了相机朝向，再叠加一次等于转两次，四边形侧对镜头。</li>
     *   <li><b>取共轭</b>（{@code new Quaternionf(rot).conjugate()}）——
     *       图标能看见，但**形状是斜的、侧看就变成一条线**，而且方向颠倒。
     *       因为多转了一次 180°。</li>
     * </ol>
     *
     * <p>正确答案来自原版名字牌的做法（{@code EntityRenderer.renderNameTag}），
     * 用 javap 核实：</p>
     * <pre>
     * EntityRenderDispatcher.prepare():
     *     this.cameraOrientation = camera.rotation();      // 直接赋值，无共轭
     * EntityRenderer.renderNameTag():
     *     poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
     * </pre>
     *
     * <p>所以这里就是 {@code pose.mulPose(billboard)}，参数直接传
     * {@code Camera.rotation()}。</p>
     *
     * @param billboard 相机朝向四元数（调用方从 {@code Camera.rotation()} 取，勿取共轭）
     */
    public static void drawIcon(
            PoseStack pose,
            MultiBufferSource buffers,
            int column,
            int color,
            float size,
            org.joml.Quaternionf billboard,
            float alpha) {

        pose.pushPose();
        if (billboard != null) {
            pose.mulPose(billboard);
        }
        emitIcon(pose, buffers, column, color, size, alpha, 0f);
        pose.popPose();
    }

    /**
     * 把三个图标**作为一个整体**绘制。
     *
     * <p><b>为什么必须整组绘制</b>：第一版是三次独立的 {@code drawIcon}，
     * 每次内部各自 {@code mulPose(billboard)}，而横向偏移
     * {@code translate(±step, 0, 0)} 是在**世界坐标**里做的。
     * 结果三个图标被转到三个不同的平面上：
     * 从侧面看时它们互相穿插、重叠、只剩一条边（用户截图看到的就是这个）。</p>
     *
     * <p>正确顺序：先旋转（建立"面向镜头"的局部坐标系），
     * 再在局部坐标里横向排布。这样三个四边形天然共面，永远不会互相遮挡。</p>
     *
     * @param columns      三列在图集中的列索引
     * @param colors       三个颜色
     * @param size         单个图标边长
     * @param gap          图标间距
     * @param billboard    相机朝向
     */
    public static void drawIconRow(
            PoseStack pose,
            MultiBufferSource buffers,
            int[] columns,
            int[] colors,
            float size,
            float gap,
            org.joml.Quaternionf billboard,
            float alpha) {

        float step = size + gap;

        pose.pushPose();
        // 1) 先转到面向镜头（整组共用同一个旋转）
        if (billboard != null) {
            pose.mulPose(billboard);
        }
        // 2) 在已经转好的局部坐标系里横向排布：左 -step、中 0、右 +step
        pose.pushPose();
        pose.translate(-step, 0f, 0f);
        emitIcon(pose, buffers, columns[0], colors[0], size, alpha, 0f);
        pose.popPose();

        emitIcon(pose, buffers, columns[1], colors[1], size, alpha, 0f);

        pose.pushPose();
        pose.translate(step, 0f, 0f);
        emitIcon(pose, buffers, columns[2], colors[2], size, alpha, 0f);
        pose.popPose();

        pose.popPose();
    }

    /**
     * 在当前 pose 下发出一个图标的四边形（不旋转、不平移）。
     *
     * @param zBias 深度偏移，用于避免同一位置上多个四边形 z-fighting
     */
    private static void emitIcon(
            PoseStack pose,
            MultiBufferSource buffers,
            int column,
            int color,
            float size,
            float alpha,
            float zBias) {

        VertexConsumer vc = buffers.getBuffer(RenderType.text(ATLAS));
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();

        float h = size * 0.5f;
        float u0 = (float) column * TILE / ATLAS_WIDTH;
        float u1 = (float) (column + 1) * TILE / ATLAS_WIDTH;
        // ⚠ v0 = 纹理顶部、v1 = 纹理底部。
        //    第一版把"屏幕上方的顶点"配了 v1，导致图标上下颠倒。
        float v0 = 0.0f;
        float v1 = 1.0f;

        int a = (int) (((color >>> 24) & 0xFF) * alpha);
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;

        float z = zBias;
        // 左上 → 左下 → 右下 → 右上（U/V 与位置严格对应：上=0，下=1）
        quad(vc, last, m, r, g, b, a,
                -h, +h, z, u0, v0,
                -h, -h, z, u0, v1,
                +h, -h, z, u1, v1,
                +h, +h, z, u1, v0);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Matrix4f m,
                             int r, int g, int b, int a,
                             float x0, float y0, float z0, float u0, float v0,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3) {
        vertex(vc, pose, m, r, g, b, a, x0, y0, z0, u0, v0);
        vertex(vc, pose, m, r, g, b, a, x1, y1, z1, u1, v1);
        vertex(vc, pose, m, r, g, b, a, x2, y2, z2, u2, v2);
        vertex(vc, pose, m, r, g, b, a, x3, y3, z3, u3, v3);
    }

    /**
     * 写一个顶点。
     *
     * <p>⚠ 这里**不能**调 {@code setNormal}：{@code RenderType.text()} 用的是
     * {@code DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP}，
     * 这个格式只含 Position / Color / UV0 / UV2，<b>没有法线</b>。
     * {@code BufferBuilder.setNormal} 内部会先调 {@code beginElement(NORMAL)}，
     * 当前格式不含该元素时它返回 -1 并**静默跳过** ——
     * 不会崩，但那行代码是死的，还让人误以为需要算法线。</p>
     *
     * <p>已用 javap 核实：
     * {@code TEXT} 的格式是 POSITION_COLOR_TEX_LIGHTMAP，
     * {@code LINES} 的格式是 POSITION_COLOR_NORMAL（见 {@link #drawLine}）。</p>
     */
    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Matrix4f m,
                               int r, int g, int b, int a,
                               float x, float y, float z, float u, float v) {
        vc.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setUv2(FULL_BRIGHT & 0xFFFF, FULL_BRIGHT >> 16);
    }

    /**
     * 线宽（格）—— 相对摄像机方向的实际厚度。
     *
     * <p><b>为什么不用 {@code RenderSystem.lineWidth}：</b>
     * {@code RenderType.lines()} 画的是 GL 线段，其宽度在 core profile 下
     * 多数驱动会强制钳到 1 像素，改 {@code glLineWidth} 经常无效或直接报错。
     * 用户反馈"射线和边框太细"，靠 lineWidth 调是不可靠的。</p>
     *
     * <p>所以改成**用四边形模拟粗线**：把每条线段沿"屏幕方向"扩成一个
     * 面向摄像机的长条（billboard）。这样粗细是真实的几何宽度，
     * 任何驱动、任何距离都一致，而且还能按需要做渐细。</p>
     */
    public static final float LINE_WIDTH = 0.055f;

    /** 粗细的全局倍率，方便一处调整。当前按用户要求"加粗一倍"。 */
    public static final float LINE_WIDTH_SCALE = 2.0f;

    /**
     * 画一条线段（用四边形模拟，可控粗细）。
     *
     * <p>做法：把线段两端点各自沿"端点指向摄像机的方向 × 线段方向的垂直方向"
     * 撑开半个线宽，得到一个 4 顶点的长条。
     * 用 {@link RenderType#debugQuads()} —— 它接受纯色顶点、不采样纹理、
     * 并且会按距离排序，适合这种半透明的指示线。</p>
     */
    public static void drawLine(
            PoseStack pose,
            MultiBufferSource buffers,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            int color, float alpha) {
        drawThickLine(pose, buffers, x1, y1, z1, x2, y2, z2,
                color, alpha, LINE_WIDTH * LINE_WIDTH_SCALE, 0f);
    }

    /** 带额外偏移的粗线（zBias 用于同位置多条线错开，避免 z-fighting）。 */
    public static void drawThickLine(
            PoseStack pose,
            MultiBufferSource buffers,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            int color, float alpha, float width, float zBias) {

        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-5f) {
            return;
        }
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        // 摄像机位置（世界坐标）→ 转成相对 pose 的偏移，用于 billboard 展开方向
        var mc = net.minecraft.client.Minecraft.getInstance();
        float camX = 0f, camY = 0f, camZ = 0f;
        if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
            var cp = mc.gameRenderer.getMainCamera().getPosition();
            camX = (float) cp.x;
            camY = (float) cp.y;
            camZ = (float) cp.z;
        }

        float mx = (x1 + x2) * 0.5f;
        float my = (y1 + y2) * 0.5f;
        float mz = (z1 + z2) * 0.5f;
        // 从线中点指向摄像机的方向
        float vx = camX - mx;
        float vy = camY - my;
        float vz = camZ - mz;
        float vlen = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vlen < 1.0e-5f) {
            return;
        }
        vx /= vlen;
        vy /= vlen;
        vz /= vlen;

        // 展开方向 = 视线方向 × 线段方向（垂直于两者，落在屏幕平面内）
        float sx = vy * nz - vz * ny;
        float sy = vz * nx - vx * nz;
        float sz = vx * ny - vy * nx;
        float slen = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (slen < 1.0e-5f) {
            // 视线与线段平行（正对着线看），此时退化，随便取一个垂直方向
            sx = ny;
            sy = -nx;
            sz = 0f;
            slen = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (slen < 1.0e-5f) {
                return;
            }
        }
        float hw = width * 0.5f;
        sx = sx / slen * hw;
        sy = sy / slen * hw;
        sz = sz / slen * hw;

        int a = (int) (((color >>> 24) & 0xFF) * alpha);
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;

        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();

        // 四个角：起点±展开、终点±展开
        float ax0 = x1 + sx, ay0 = y1 + sy, az0 = z1 + sz;
        float bx0 = x1 - sx, by0 = y1 - sy, bz0 = z1 - sz;
        float ax1 = x2 + sx, ay1 = y2 + sy, az1 = z2 + sz;
        float bx1 = x2 - sx, by1 = y2 - sy, bz1 = z2 - sz;

        // 顺序：A0 → B0 → B1 → A1（同一个绕序，debugQuads 不剔除背面）
        emitQuadColor(vc, last, ax0, ay0, az0, bx0, by0, bz0,
                bx1, by1, bz1, ax1, ay1, az1, r, g, b, a);
    }

    /** 发出一个纯色四边形（debugQuads 的格式是 POSITION_COLOR）。 */
    private static void emitQuadColor(VertexConsumer vc, PoseStack.Pose pose,
                                      float x0, float y0, float z0,
                                      float x1, float y1, float z1,
                                      float x2, float y2, float z2,
                                      float x3, float y3, float z3,
                                      int r, int g, int b, int a) {
        vc.addVertex(pose, x0, y0, z0).setColor(r, g, b, a);
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
    }

    /**
     * 画一条虚线。
     *
     * <p>切成小段分别画 —— 原版没有虚线图元，这是最直接的做法。
     * 段长 0.25 格、间隔 0.15 格（与布局方案一致）。</p>
     */
    public static void drawDashedLine(
            PoseStack pose,
            MultiBufferSource buffers,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            int color, float alpha) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001f) {
            return;
        }
        float seg = 0.25f;
        float step = seg + 0.15f;
        int count = (int) (len / step);
        if (count <= 0) {
            return;
        }
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        for (int i = 0; i < count; i++) {
            float t0 = i * step;
            float t1 = Math.min(t0 + seg, len);
            drawLine(pose, buffers,
                    x1 + nx * t0, y1 + ny * t0, z1 + nz * t0,
                    x1 + nx * t1, y1 + ny * t1, z1 + nz * t1,
                    color, alpha);
        }
    }

    /**
     * 画一个线框立方体（可刷范围框 / 傀儡检测范围框）。
     *
     * <p>12 条棱分别画，不画面 —— 面会挡住视线且开销大。</p>
     */
    public static void drawBox(
            PoseStack pose,
            MultiBufferSource buffers,
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            int color, float alpha) {
        float fx0 = (float) x0, fy0 = (float) y0, fz0 = (float) z0;
        float fx1 = (float) x1, fy1 = (float) y1, fz1 = (float) z1;

        // 底面 4 条
        drawLine(pose, buffers, fx0, fy0, fz0, fx1, fy0, fz0, color, alpha);
        drawLine(pose, buffers, fx1, fy0, fz0, fx1, fy0, fz1, color, alpha);
        drawLine(pose, buffers, fx1, fy0, fz1, fx0, fy0, fz1, color, alpha);
        drawLine(pose, buffers, fx0, fy0, fz1, fx0, fy0, fz0, color, alpha);
        // 顶面 4 条
        drawLine(pose, buffers, fx0, fy1, fz0, fx1, fy1, fz0, color, alpha);
        drawLine(pose, buffers, fx1, fy1, fz0, fx1, fy1, fz1, color, alpha);
        drawLine(pose, buffers, fx1, fy1, fz1, fx0, fy1, fz1, color, alpha);
        drawLine(pose, buffers, fx0, fy1, fz1, fx0, fy1, fz0, color, alpha);
        // 4 条竖棱
        drawLine(pose, buffers, fx0, fy0, fz0, fx0, fy1, fz0, color, alpha);
        drawLine(pose, buffers, fx1, fy0, fz0, fx1, fy1, fz0, color, alpha);
        drawLine(pose, buffers, fx1, fy0, fz1, fx1, fy1, fz1, color, alpha);
        drawLine(pose, buffers, fx0, fy0, fz1, fx0, fy1, fz1, color, alpha);
    }
}
