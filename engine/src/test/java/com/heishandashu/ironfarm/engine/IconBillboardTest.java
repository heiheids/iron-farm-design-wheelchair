package com.heishandashu.ironfarm.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头顶图标的绘制契约。
 *
 * <p>这三条都是**用户截图暴露的真实 bug**，不是风格检查：</p>
 * <ol>
 *   <li>billboard 不能自己算 yaw/pitch，也不能取共轭；</li>
 *   <li>三个图标必须整组旋转后再排布，否则被转到不同平面；</li>
 *   <li>UV 的 V 方向：屏幕上方顶点必须配 v=0（纹理顶部）。</li>
 * </ol>
 */
class IconBillboardTest {

    // ==================================================================
    // billboard
    // ==================================================================

    /**
     * 回归钉子：{@code Camera.rotation()} 直接乘，不要 conjugate。
     *
     * <p>原版证据（javap 核实）：</p>
     * <pre>
     * EntityRenderDispatcher.prepare():
     *     this.cameraOrientation = camera.rotation();      // 无共轭
     * EntityRenderer.renderNameTag():
     *     poseStack.mulPose(cameraOrientation());
     * </pre>
     *
     * <p>我取共轭后图标歪斜、侧看变一条线、方向颠倒。</p>
     */
    @Test
    void billboardIsNotConjugated() throws IOException {
        String code = stripComments(readSrc("render/IconRenderer.java"));
        assertFalse(code.contains(".conjugate()"),
                "不能对 camera.rotation() 取共轭 —— 会多转 180°，图标歪斜且颠倒");
        assertTrue(code.contains("mulPose(billboard)"),
                "应当直接 mulPose(cameraOrientation)");
    }

    @Test
    void billboardDoesNotComputeYawPitchByHand() throws IOException {
        String code = stripComments(readSrc("render/IconRenderer.java"));
        assertFalse(code.contains("rotationDegrees"),
                "不能手算 yaw/pitch 来抵消相机旋转 —— 矩阵栈已含相机朝向");
        assertFalse(code.contains("Axis.YP"),
                "不能用 Axis.YP 手动 billboard");
    }

    // ==================================================================
    // 整组旋转
    // ==================================================================

    /**
     * 三个图标必须共面。
     *
     * <p>错误做法（第一版）：每个图标各自 {@code pushPose + mulPose(billboard)}，
     * 横向偏移 {@code translate(±step,0,0)} 在世界坐标里做 →
     * 三个图标转到三个不同平面 → 侧面看互相穿插遮挡。</p>
     */
    @Test
    void iconRowSharesOneRotation() throws IOException {
        String src = readSrc("render/IconRenderer.java");
        int start = src.indexOf("public static void drawIconRow");
        assertTrue(start > 0, "应当有整组绘制方法 drawIconRow");

        // 取方法体
        int brace = src.indexOf('{', start);
        int depth = 0;
        int end = brace;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        String body = stripComments(src.substring(brace, end + 1));

        // 整组只应有一次 mulPose
        int count = body.split("mulPose\\(billboard\\)", -1).length - 1;
        assertTrue(count == 1,
                "整组只应旋转一次（实际 " + count + " 次）；多次旋转会让图标不共面");
        // 旋转必须发生在三次 emitIcon 之前
        int rot = body.indexOf("mulPose(billboard)");
        int firstEmit = body.indexOf("emitIcon");
        assertTrue(rot > 0 && rot < firstEmit,
                "必须先整组旋转，再排布图标");
    }

    @Test
    void rendererUsesWholeRowDraw() throws IOException {
        String code = stripComments(readSrc("render/WorldOverlayRenderer.java"));
        assertTrue(code.contains("drawIconRow"),
                "渲染层应当调用整组绘制，而不是三次独立 drawIcon");
    }

    // ==================================================================
    // UV 方向
    // ==================================================================

    /**
     * 回归钉子：V 轴方向。
     *
     * <p>屏幕上方（y = +h）的顶点必须配 {@code v0 = 0}（纹理顶部）。
     * 第一版配了 v1=1.0，图标整个上下颠倒 —— 用户截图确认。</p>
     */
    @Test
    void topVertexUsesV0() throws IOException {
        String src = readSrc("render/IconRenderer.java");
        int start = src.indexOf("private static void emitIcon");
        assertTrue(start > 0, "应当有 emitIcon 方法");

        int brace = src.indexOf('{', start);
        int depth = 0;
        int end = brace;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        String body = src.substring(brace, end + 1);

        // 找到 quad(...) 的调用，检查第一组参数（左上角）
        int q = body.indexOf("quad(");
        assertTrue(q > 0);
        String call = body.substring(q, body.indexOf(')', q) + 1);

        // 左上角：y 为正（+h），v 应为 v0
        assertTrue(call.contains("-h, +h, z, u0, v0"),
                "屏幕上方顶点必须配 v0（纹理顶部），否则图标上下颠倒。实际: " + call);
    }

    // ==================================================================

    private static String readSrc(String relative) throws IOException {
        Path p = Path.of("fabric-1.21.1/src/main/java/com/heishandashu/ironfarm/fabric/" + relative);
        if (!Files.exists(p)) {
            p = Path.of("../" + p);
        }
        assertTrue(Files.exists(p), "找不到文件: " + relative);
        try (InputStream in = Files.newInputStream(p)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inBlock) {
                if (c == '*' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < src.length()) {
                char n = src.charAt(i + 1);
                if (n == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (n == '/') {
                    while (i < src.length() && src.charAt(i) != '\n') {
                        i++;
                    }
                    out.append('\n');
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
