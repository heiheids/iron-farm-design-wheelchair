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
 * 设置页与渲染层的契约测试。
 *
 * <p>本机跑不起客户端，界面只能靠"读代码 + 断言关键约束"来验证。
 * 这里钉的都是**踩过的坑**，不是泛泛的风格检查。</p>
 */
class SettingsScreenContractTest {

    // ==================================================================
    // 设置页：状态只能有一份
    // ==================================================================

    /**
     * 回归钉子：第一版用静态 {@code IdentityHashMap<Button, Boolean>} 记开关状态，
     * 有三个问题 —— 静态 Map 持有 Button 造成泄漏、状态有两个来源、
     * 手改配置重载后界面还是旧值。
     *
     * <p>现在 {@code ModSettings} 是唯一真源。</p>
     */
    @Test
    void settingsScreenHasNoStaticToggleState() throws IOException {
        String code = stripComments(readSrc("gui/SettingsScreen.java"));

        assertFalse(code.contains("TOGGLE_STATE"),
                "不能再有平行的静态状态表：ModSettings 才是唯一真源");
        assertFalse(code.contains("IdentityHashMap"),
                "静态 IdentityHashMap 持有 Button 会泄漏");
        assertFalse(code.contains("static final java.util.Map<Button"),
                "不能按按钮对象缓存开关值");
    }

    @Test
    void togglesReadFromSettingsNotFromCache() throws IOException {
        String code = stripComments(readSrc("gui/SettingsScreen.java"));
        // 翻转逻辑必须是 "!getter.getAsBoolean()" —— 从设置读，而不是读缓存
        assertTrue(code.contains("getter.getAsBoolean()"),
                "开关翻转必须基于设置里的真实值");
        assertTrue(code.contains("setter.accept"),
                "必须写回设置");
    }

    // ==================================================================
    // 统计页：清空要二次确认
    // ==================================================================

    @Test
    void statsResetRequiresConfirmation() throws IOException {
        String code = stripComments(readSrc("gui/SettingsScreen.java"));
        assertTrue(code.contains("resetArmed"),
                "清空统计必须有二次确认状态");
        assertTrue(code.contains("resetArmTicks"),
                "二次确认要有超时，否则按钮会一直停在'确定？'");
        assertTrue(code.contains("reset_confirm"),
                "要有单独的确认文案");
    }

    @Test
    void resetConfirmIsLocalizedInBothLanguages() throws IOException {
        assertTrue(readFile("engine/src/main/resources/assets/ironfarm/lang/zh_cn.lang")
                .contains("ironfarm.gui.stats.reset_confirm"));
        assertTrue(readFile("engine/src/main/resources/assets/ironfarm/lang/en_us.lang")
                .contains("ironfarm.gui.stats.reset_confirm"));
    }

    // ==================================================================
    // 生成点标记
    // ==================================================================

    /**
     * 只画外框不够：玩家看到 17×13×17 的框之后，下一个问题是
     * "框里哪一格真的能刷"。引擎的 SpawnScanResult.validPoints() 已经算好了，
     * 渲染层必须用上。
     */
    @Test
    void spawnBoxAlsoDrawsValidPoints() throws IOException {
        String code = stripComments(readSrc("render/WorldOverlayRenderer.java"));
        assertTrue(code.contains("validPoints()"),
                "可刷范围必须画出合法点，而不只是一个外框");
        assertTrue(code.contains("drawSpawnPointMarker"),
                "需要专门的合法点标记方法");
    }

    @Test
    void clientStateExposesSpawnScan() throws IOException {
        String code = stripComments(readSrc("IronFarmClientState.java"));
        assertTrue(code.contains("spawnScanOf"),
                "渲染层要能按村民取到扫描结果");
        assertTrue(code.contains("spawnScans"),
                "扫描结果要缓存下来，不能每帧重扫");
    }

    @Test
    void spawnScanIsClearedWithOtherState() throws IOException {
        String code = stripComments(readSrc("IronFarmClientState.java"));
        // clearScanOnly 里必须清 spawnScans，否则村民走了以后框还在。
        // ⚠ 取从方法名开始到下一个方法结束的这一段，别用固定长度窗口 ——
        //    第一版我用了 600 字符，而 clearScanOnly 的方法体只有 6 行，
        //    但前面的注释被 strip 掉后位置变了，导致找到的是另一个方法，
        //    误报"没有清空"。改成按大括号配对截取。
        String body = methodBody(code, "private void clearScanOnly()");
        assertTrue(body.contains("spawnScans.clear()"),
                "clearScanOnly 必须清空扫描缓存，否则村民走后框还残留");
        assertTrue(body.contains("villagers = new ArrayList"),
                "顺带断言：其它状态也要清");
    }

    /** 截取一个方法的方法体（按大括号配对）。 */
    private static String methodBody(String code, String signature) {
        int idx = code.indexOf(signature);
        assertTrue(idx >= 0, "找不到方法: " + signature);
        int brace = code.indexOf('{', idx);
        assertTrue(brace > 0, "找不到方法体: " + signature);
        int depth = 0;
        for (int i = brace; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(brace, i + 1);
                }
            }
        }
        return code.substring(brace);
    }

    // ==================================================================
    // 颜色与透明度约定
    // ==================================================================

    @Test
    void overlayColorsMatchTheAgreedPalette() throws IOException {
        String code = readSrc("render/WorldOverlayRenderer.java");
        assertTrue(code.contains("0xFF3FE0FF"), "床连线应为青色 #3FE0FF");
        assertTrue(code.contains("0xFFFF7040"), "恐吓连线应为橙红 #FF7040");
        assertTrue(code.contains("0xFFFFD24A"), "可刷范围框应为黄色 #FFD24A");
        assertTrue(code.contains("0xFFB060FF"), "傀儡检测框应为紫色 #B060FF");
    }

    @Test
    void panicLineHasThreeDistinctStyles() throws IOException {
        String code = stripComments(readSrc("render/WorldOverlayRenderer.java"));
        // 生效 → 实线；距离不够 → 虚线；视线被挡 → 点线
        assertTrue(code.contains("drawLine"), "生效的线用实线");
        assertTrue(code.contains("drawDashedLine"), "不生效的线用虚线");
        assertTrue(code.contains("blockedByDistance"), "要区分距离不够");
        // 两种情况透明度必须不同，否则玩家分不出"太远"和"看不见"
        assertTrue(code.contains("0.45f") && code.contains("0.25f"),
                "距离不够与视线被挡要用不同透明度区分");
    }

    @Test
    void golemDetectBoxIsVeryTransparent() throws IOException {
        String code = readSrc("render/WorldOverlayRenderer.java");
        // 33³ 的框全不透明会糊屏
        assertTrue(code.contains("0.18f"), "傀儡检测框透明度应压到 0.18");
    }

    // ------------------------------------------------------------------

    private static String readSrc(String relative) throws IOException {
        return readFile("fabric-1.21.1/src/main/java/com/heishandashu/ironfarm/fabric/" + relative);
    }

    private static String readFile(String relative) throws IOException {
        Path p = Path.of(relative);
        if (!Files.exists(p)) {
            p = Path.of("../" + relative);
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

    /** 去掉 // 行注释与块注释，只留代码。 */
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
