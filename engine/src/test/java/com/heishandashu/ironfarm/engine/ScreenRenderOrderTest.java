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
 * 界面渲染顺序的契约。
 *
 * <p><b>真实事故（用户描述："依然被遮蔽，似乎和关闭按钮图层有关"）：</b></p>
 *
 * <p>两个界面的 {@code render()} 都写成了这个顺序：</p>
 * <pre>
 * render() {
 *     renderBackground(...);    // ① 自己画一次背景
 *     画面板 + 所有内容          // ② 内容
 *     super.render(...);        // ③ ← 这里又画一次背景，把②盖住
 * }
 * </pre>
 *
 * <p>而 {@code Screen.render(...)} 内部本来就会调用 {@code renderBackground}。
 * 所以第 ③ 步等于把背景重画在内容之上 ——
 * 表现为面板蒙了一层半透明黑、文字发暗、按钮浮在内容上面。</p>
 *
 * <p>原版 {@code AbstractContainerScreen.render} 的顺序是先
 * {@code super.render(...)} 再画自己的内容。照这个来。</p>
 */
class ScreenRenderOrderTest {

    // ==================================================================
    // 顺序
    // ==================================================================

    /**
     * render() 里 super.render(...) 必须最先调用。
     *
     * <p>注意：面板底板已经移到 {@code renderBackground}，
     * 所以 render() 里**不该再有 g.fill**。这里改为断言
     * "super.render 是所有绘制调用中最先出现的"。</p>
     */
    @Test
    void statusScreenCallsSuperRenderBeforeAnyContent() throws IOException {
        String body = stripComments(renderMethodBody("gui/VillagerStatusScreen.java"));

        int superCall = body.indexOf("super.render(");
        assertTrue(superCall >= 0, "必须调用 super.render(...)（背景 + 按钮）");

        // super.render 之前不应有任何内容绘制
        String before = body.substring(0, superCall);
        assertFalse(before.contains("g.fill("),
                "super.render 之前不应绘制内容");
        assertFalse(before.contains("drawString("),
                "super.render 之前不应绘制文字");
    }

    @Test
    void settingsScreenCallsSuperRenderBeforeAnyContent() throws IOException {
        String body = stripComments(renderMethodBody("gui/SettingsScreen.java"));

        int superCall = body.indexOf("super.render(");
        assertTrue(superCall >= 0);
        String before = body.substring(0, superCall);
        assertFalse(before.contains("g.fill("), "super.render 之前不应绘制内容");
        assertFalse(before.contains("drawString("), "super.render 之前不应绘制文字");
    }

    /**
     * 自己不许再调 {@code renderBackground}。
     *
     * <p>{@code super.render(...)} 内部已经调了，自己再调一次
     * 会让背景画两遍（虽然顺序对了不会盖住内容，但属于重复工作，
     * 而且一旦有人把 super.render 挪到后面又会复现旧 bug）。</p>
     */
    @Test
    void screensDoNotCallRenderBackgroundThemselves() throws IOException {
        for (String f : new String[]{"gui/VillagerStatusScreen.java", "gui/SettingsScreen.java"}) {
            String body = stripComments(renderMethodBody(f));
            assertFalse(body.contains("renderBackground("),
                    f + ": 不要再自己调用 renderBackground —— super.render 内部已经调了");
        }
    }

    @Test
    void statusScreenDoesNotCallSuperRenderTwice() throws IOException {
        String body = stripComments(renderMethodBody("gui/VillagerStatusScreen.java"));
        int count = body.split("super\\.render\\(", -1).length - 1;
        assertTrue(count == 1,
                "super.render 只应调用一次（实际 " + count + " 次）；"
                        + "第二次会把背景重画在内容之上");
    }

    @Test
    void settingsScreenDoesNotCallSuperRenderTwice() throws IOException {
        String body = stripComments(renderMethodBody("gui/SettingsScreen.java"));
        int count = body.split("super\\.render\\(", -1).length - 1;
        assertTrue(count == 1, "super.render 只应调用一次（实际 " + count + " 次）");
    }

    // ------------------------------------------------------------------

    /** 取出 render(GuiGraphics,...) 的方法体（按大括号配对）。 */
    private static String renderMethodBody(String relative) throws IOException {
        String src = readSrc(relative);
        int start = src.indexOf("public void render(GuiGraphics");
        assertTrue(start > 0, relative + ": 找不到 render(GuiGraphics, ...) 方法");
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

    // ==================================================================
    // 按钮样式：面板底板必须在 renderBackground 里
    // ==================================================================

    /**
     * 回归钉子（用户："所有按钮没了按钮样式"）。
     *
     * <p>面板底板是一块**不透明**的 fill。如果它在 {@code render()} 里
     * （super.render 之后），就会盖住刚画好的按钮。</p>
     *
     * <p>正确做法：底板写进 {@code renderBackground()}，
     * 它在控件之前执行，按钮自然浮在上面。</p>
     */
    @Test
    void panelPlateIsDrawnInRenderBackground() throws IOException {
        for (String f : new String[]{"gui/VillagerStatusScreen.java", "gui/SettingsScreen.java"}) {
            String src = readSrc(f);

            String bgBody = methodBody(src, "public void renderBackground(GuiGraphics");
            assertTrue(bgBody.contains("g.fill("),
                    f + ": renderBackground 里必须画面板底板");

            String renderBody = stripComments(methodBody(src, "public void render(GuiGraphics"));
            // render() 里 super.render 之后不应再有不透明的整块面板
            int superCall = renderBody.indexOf("super.render(");
            String afterSuper = superCall >= 0 ? renderBody.substring(superCall) : renderBody;
            assertFalse(afterSuper.contains("0xFF1A1A1E"),
                    f + ": super.render 之后不能再画不透明面板底板 —— 会盖住按钮");
        }
    }

    // ------------------------------------------------------------------

    /** 按签名取方法体（大括号配对）。 */
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
}