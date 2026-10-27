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
 * 指示线粗细的契约。
 *
 * <p><b>为什么不能用 {@code RenderSystem.lineWidth}：</b>
 * {@code RenderType.lines()} 画的是 GL 线段（GL_LINES）。
 * 在 OpenGL core profile 下，多数驱动把线宽钳制为 1.0，
 * 调 {@code glLineWidth(2)} 要么无效、要么抛 GL_INVALID_VALUE。
 * 所以「加粗」不能靠它。</p>
 *
 * <p>正确做法：把每条线段展开成一个面向摄像机的四边形（billboard strip），
 * 粗细是真实的几何宽度，任何驱动/距离都一致。</p>
 */
class LineThicknessTest {

    @Test
    void linesDoNotUseGlLineStrips() throws IOException {
        String code = stripComments(readSrc("render/IconRenderer.java"));

        assertFalse(code.contains("RenderType.lines()"),
                "不能用 RenderType.lines() —— GL 线宽在 core profile 下被钳到 1 像素，加粗无效");
        assertFalse(code.contains("RenderSystem.lineWidth"),
                "不要依赖 glLineWidth，多数驱动会忽略或报错");
    }

    @Test
    void linesAreDrawnAsQuads() throws IOException {
        String code = stripComments(readSrc("render/IconRenderer.java"));

        assertTrue(code.contains("RenderType.debugQuads()"),
                "粗线应当用 debugQuads 画四边形");
        assertTrue(code.contains("emitQuadColor"),
                "需要专门的纯色四边形发射方法");
    }

    /**
     * debugQuads 的顶点格式是 {@code POSITION_COLOR}（无 uv、无法线）。
     * 在上面调 setUv / setNormal 会被静默跳过（死代码）。
     */
    @Test
    void quadVertexOnlySetsPositionAndColor() throws IOException {
        String src = readSrc("render/IconRenderer.java");
        String body = methodBody(src, "private static void emitQuadColor");

        assertTrue(body.contains("setColor"), "必须设置颜色");
        assertFalse(body.contains("setUv("),
                "POSITION_COLOR 格式没有 uv，调 setUv 是死代码");
        assertFalse(body.contains("setNormal"),
                "POSITION_COLOR 格式没有法线");
    }

    @Test
    void lineWidthIsScaledUp() throws IOException {
        String code = readSrc("render/IconRenderer.java");
        assertTrue(code.contains("LINE_WIDTH"),
                "应当有一个明确的线宽常量");
        assertTrue(code.contains("LINE_WIDTH_SCALE"),
                "应当有倍率常量，方便一处调整粗细");
    }

    /**
     * 所有线（普通线、虚线、范围框）都必须走同一条粗线路径，
     * 否则会出现"有的线粗有的线细"。
     */
    @Test
    void boxAndDashedLineUseThickPath() throws IOException {
        String body = stripComments(readSrc("render/IconRenderer.java"));

        // drawBox 与 drawDashedLine 内部都应调用 drawLine
        String boxBody = stripComments(methodBody(
                readSrc("render/IconRenderer.java"), "public static void drawBox"));
        assertTrue(boxBody.contains("drawLine("),
                "范围框的 12 条棱必须走 drawLine（否则粗细不一致）");

        String dashBody = stripComments(methodBody(
                readSrc("render/IconRenderer.java"), "public static void drawDashedLine"));
        assertTrue(dashBody.contains("drawLine("),
                "虚线的每一段必须走 drawLine");
    }

    // ------------------------------------------------------------------

    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue(start > 0, "找不到方法: " + signature);
        int brace = src.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(brace, i + 1);
                }
            }
        }
        return src.substring(brace);
    }

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
