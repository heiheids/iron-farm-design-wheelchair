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
 * 界面尺寸自适应的契约。
 *
 * <p><b>真实事故</b>：两个界面都写死了尺寸。
 * <ul>
 *   <li>设置页 400×300 —— GUI scale 4 的 720p 下 GUI 只有 320×180，面板比屏幕还大；</li>
 *   <li>状态页 320×240 —— GUI scale 3 的 720p 下垂直只有 240，内容区仅剩约 198，
 *       内容和统计页一样被挤到面板外。</li>
 * </ul>
 * 两次都是用户截图发现的，两次症状一样（"被遮挡"）。</p>
 *
 * <p>根因是同一个：**把"首选尺寸"当成了"实际尺寸"**。
 * Minecraft 的 GUI 坐标是 GUI 像素，不是屏幕像素，
 * 写死尺寸前必须先算 GUI scale 下还剩多少。</p>
 */
class GuiSizingTest {

    // ==================================================================
    // 设置页
    // ==================================================================

    @Test
    void settingsScreenSizesToScreen() throws IOException {
        String code = stripComments(readSrc("gui/SettingsScreen.java"));

        assertTrue(code.contains("panelWidth()"), "设置页宽度必须按屏幕算");
        assertTrue(code.contains("panelHeight()"), "设置页高度必须按屏幕算");

        // 不许再有任何固定的 PANEL_WIDTH / PANEL_HEIGHT 引用
        assertFalse(code.contains("PANEL_WIDTH"),
                "不能再用固定宽度常量布局");
        assertFalse(code.contains("PANEL_HEIGHT"),
                "不能再用固定高度常量布局");
    }

    @Test
    void settingsScreenClampsToScreenWithMargin() throws IOException {
        String code = stripComments(readSrc("gui/SettingsScreen.java"));
        assertTrue(code.contains("Math.min(PREFERRED_WIDTH"),
                "宽度应取 min(首选值, 屏幕可用空间)");
        assertTrue(code.contains("Math.min(PREFERRED_HEIGHT"),
                "高度应取 min(首选值, 屏幕可用空间)");
        assertTrue(code.contains("SCREEN_MARGIN"),
                "要留出边距，别贴屏幕边缘");
    }

    // ==================================================================
    // 状态页
    // ==================================================================

    @Test
    void statusScreenSizesToScreen() throws IOException {
        String code = stripComments(readSrc("gui/VillagerStatusScreen.java"));

        assertTrue(code.contains("panelWidth()"), "状态页宽度必须按屏幕算");
        assertTrue(code.contains("panelHeight()"), "状态页高度必须按屏幕算");
        assertFalse(code.contains("PANEL_WIDTH"), "不能再用固定宽度常量");
        assertFalse(code.contains("PANEL_HEIGHT"), "不能再用固定高度常量");
    }

    // ==================================================================
    // 滚动
    // ==================================================================

    /**
     * 尺寸自适应之后内容仍可能超出，两个页面都必须能滚。
     */
    @Test
    void statusScreenSupportsScrolling() throws IOException {
        String code = stripComments(readSrc("gui/VillagerStatusScreen.java"));
        assertTrue(code.contains("mouseScrolled"), "状态页要支持滚轮");
        assertTrue(code.contains("enableScissor"), "内容必须裁剪，不能画到面板外");
        assertTrue(code.contains("contentHeight"), "需要知道内容总高才能算滚动上限");
    }

    @Test
    void settingsStatsTabSupportsScrolling() throws IOException {
        String code = stripComments(readSrc("gui/SettingsScreen.java"));
        assertTrue(code.contains("mouseScrolled"), "统计页要支持滚轮");
        assertTrue(code.contains("statsContentHeight"), "要记录统计内容总高");
        assertTrue(code.contains("enableScissor"), "统计内容必须裁剪在面板内");
    }

    /**
     * 回归钉子：listScroll 字段曾经定义了但从未使用
     * —— 典型的"写了但没接线"，界面上完全看不出区别，只有内容溢出时才发现。
     */
    @Test
    void listScrollIsActuallyUsed() throws IOException {
        String code = stripComments(readSrc("gui/SettingsScreen.java"));

        int decl = code.indexOf("private int listScroll");
        assertTrue(decl > 0, "应当有 listScroll 字段");

        // 除声明外，至少要有一处读取（用于 y 偏移）和一处写入（滚动时）
        String rest = code.substring(0, decl) + code.substring(decl + 40);
        assertTrue(rest.contains("listScroll"),
                "listScroll 不能只定义不适用 —— 必须在渲染时作为 y 偏移");
    }

    // ------------------------------------------------------------------

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
