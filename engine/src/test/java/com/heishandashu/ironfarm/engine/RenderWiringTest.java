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
 * 渲染事件的接线契约。
 *
 * <p>这一组测试针对一个**会让整个渲染功能静默失效**的坑：</p>
 *
 * <pre>
 * Fabric 的 WorldRenderContext.consumers() javadoc：
 *   "This property is null before BEFORE_ENTITIES and after BEFORE_DEBUG_RENDER"
 * 事件顺序：BEFORE_DEBUG_RENDER → AFTER_TRANSLUCENT → LAST → END
 * </pre>
 *
 * <p>所以注册在 {@code LAST} 时 {@code consumers()} 恒为 null，
 * 渲染方法里那句 {@code if (buffers == null) return;} 会每次直接返回 ——
 * 一帧都没画过，但**不报任何错**。我第一版就是这么写的。</p>
 */
class RenderWiringTest {

    // ==================================================================
    // 事件选择
    // ==================================================================

    /**
     * 渲染必须挂在 {@code AFTER_ENTITIES}。
     *
     * <p>这是唯一同时满足两个契约的事件：</p>
     * <ul>
     *   <li>{@code consumers()} 非 null：它在 {@code BEFORE_ENTITIES} 到
     *       {@code BEFORE_DEBUG_RENDER} 之间可用；</li>
     *   <li>{@code matrixStack()} 非 null：只有 {@code AFTER_ENTITIES} 及之后才可用。</li>
     * </ul>
     *
     * <p>事件顺序：
     * {@code ... → BEFORE_ENTITIES → AFTER_ENTITIES → ... → BEFORE_DEBUG_RENDER
     *  → AFTER_TRANSLUCENT → LAST → END}</p>
     */
    @Test
    void renderIsRegisteredOnAfterEntities() throws IOException {
        String code = stripComments(readSrc("IronFarmClient.java"));

        assertTrue(code.contains("WorldRenderEvents.AFTER_ENTITIES"),
                "渲染必须注册在 AFTER_ENTITIES");
    }

    /**
     * 回归钉子：这几个事件都会让 consumers() 为 null，渲染会静默失效。
     *
     * <p>我在这上面错了两次：先挂 LAST，再改 AFTER_TRANSLUCENT，
     * 两次都在 BEFORE_DEBUG_RENDER 之后。所以这里把**所有**不可用的事件
     * 都列出来禁掉，避免下次又挑一个看起来合适的。</p>
     */
    @Test
    void renderIsNotRegisteredOnEventsWhereConsumersIsNull() throws IOException {
        String code = stripComments(readSrc("IronFarmClient.java"));

        for (String bad : new String[]{
                "WorldRenderEvents.LAST",
                "WorldRenderEvents.AFTER_TRANSLUCENT",
                "WorldRenderEvents.END",
                "WorldRenderEvents.START",
                "WorldRenderEvents.AFTER_SETUP"}) {
            assertFalse(code.contains(bad),
                    "不能注册在 " + bad + "（consumers() 为 null 或 matrixStack() 为 null）");
        }
    }

    @Test
    void rendererDocumentsTheEventContract() throws IOException {
        String src = readSrc("render/WorldOverlayRenderer.java");
        // 注释里必须写清楚两个契约，否则下一个人很容易再挑错事件
        assertTrue(src.contains("AFTER_ENTITIES"), "必须写明正确的事件");
        assertTrue(src.contains("BEFORE_DEBUG_RENDER"),
                "必须写明 consumers 在 BEFORE_DEBUG_RENDER 之后为 null");
        assertTrue(src.contains("matrixStack"),
                "必须写明 matrixStack 只在 AFTER_ENTITIES 及之后可用");
    }

    // ==================================================================
    // 顶点 API
    // ==================================================================

    /**
     * 回归钉子：{@code RenderType.text()} 的顶点格式是
     * {@code POSITION_COLOR_TEX_LIGHTMAP}，**没有法线**。
     * 在上面调 {@code setNormal} 会被 BufferBuilder 静默跳过（不崩，但是死代码）。
     */
    @Test
    void textRenderingDoesNotSetNormal() throws IOException {
        String src = readSrc("render/IconRenderer.java");

        // 定位 vertex() 方法体
        int start = src.indexOf("private static void vertex(");
        assertTrue(start > 0);
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
        assertFalse(body.contains("setNormal"),
                "text 层的顶点格式没有法线，setNormal 是死代码");
        assertTrue(body.contains("setUv2"),
                "text 层需要 uv2（光照贴图坐标）");
    }

    /**
     * 线宽改用四边形实现后，顶点格式从 POSITION_COLOR_NORMAL 变成 POSITION_COLOR，
     * 不再需要法线。这里断言换成"走 debugQuads + 不写法线"。
     *
     * <p>（历史：最初用 RenderType.lines()，那时法线是必需的。
     * 后来为了加粗改成四边形 —— 详见 LineThicknessTest 里的说明。）</p>
     */
    @Test
    void lineRenderingUsesColorOnlyQuads() throws IOException {
        String src = readSrc("render/IconRenderer.java");
        int start = src.indexOf("private static void emitQuadColor");
        assertTrue(start > 0, "应当有 emitQuadColor");
        String body = src.substring(start, Math.min(src.length(), start + 800));
        assertTrue(body.contains("setColor"), "四边形顶点必须设置颜色");
        // debugQuads 是 POSITION_COLOR，没有法线和 uv
        assertTrue(!body.contains("setNormal"),
                "debugQuads 格式没有法线，写了也是死代码");
    }

    @Test
    void rendererUsesCameraRelativeCoordinates() throws IOException {
        String src = stripComments(readSrc("render/WorldOverlayRenderer.java"));
        // Fabric 要求 all vertex coordinates relative to the camera
        assertTrue(src.contains("getPosition"),
                "顶点坐标必须相对摄像机");
        assertTrue(src.contains("translate(-camPos.x"),
                "必须减去摄像机位置");
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
