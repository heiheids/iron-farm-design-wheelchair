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
 * Mixin 与 AccessWidener 的契约。
 *
 * <p><b>真实崩溃（用户："打开漏斗崩溃了"，实际是打开交易界面）：</b></p>
 *
 * <pre>
 * java.lang.BootstrapMethodError: Mixin transformation of net.minecraft.class_492 failed
 * Caused by: InvalidMixinException: @Shadow field field_2776 was not located
 *            in the target class net.minecraft.class_492. No refMap loaded.
 * </pre>
 *
 * <p>原因：{@code @Shadow} 依赖 refmap（named → intermediary 对照表）
 * 把源码里的 {@code leftPos} 翻译成运行时的 {@code field_2776}。
 * 而 Loom 1.18 起**默认不再生成 refmap**（它自己的提示：
 * "The mixin annotation is no longer enabled by default,
 * you should remove any loom.mixin configuration"）。</p>
 *
 * <p>为什么藏了这么久：那个 Mixin 是 {@code @Inject} 到 {@code init()}，
 * 只有**第一次打开交易界面**时目标类才被加载并变换。
 * 不碰村民交易就永远走不到。</p>
 *
 * <p>修法：不用 {@code @Shadow}，改用 AccessWidener 开放字段。</p>
 */
class MixinRefmapTest {

    // ==================================================================
    // 不许用 @Shadow
    // ==================================================================

    /**
     * 全工程扫描：任何 mixin 里都不许出现 {@code @Shadow}。
     *
     * <p>这个断言直接对应崩溃。只要没有 {@code @Shadow}，
     * refmap 缺失就不会导致混入失败。</p>
     */
    @Test
    void noShadowAnnotationsAnywhere() throws IOException {
        Path root = Path.of("fabric-1.21.1/src/main/java");
        if (!Files.exists(root)) {
            root = Path.of("../fabric-1.21.1/src/main/java");
        }
        assertTrue(Files.exists(root));

        StringBuilder offenders = new StringBuilder();
        try (var s = Files.walk(root)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (code.contains("@Shadow")) {
                    offenders.append(p.getFileName()).append(' ');
                }
            }
        }
        assertTrue(offenders.length() == 0,
                "以下文件仍使用 @Shadow（Loom 1.18 不生成 refmap，运行时会崩）: " + offenders);
    }

    // ==================================================================
    // mixin 配置不能声明不存在的 refmap
    // ==================================================================

    /**
     * {@code mixins.json} 里不能写 {@code "refmap": "..."}，
     * 除非那个 refmap 文件真的会被生成。
     *
     * <p>声明了却不存在，等于告诉 Mixin「去读这张表」然后读不到。</p>
     */
    @Test
    void mixinConfigDoesNotDeclareRefmap() throws IOException {
        String json = readFile("fabric-1.21.1/src/main/resources/ironfarm.mixins.json");
        assertFalse(json.contains("\"refmap\""),
                "不应声明 refmap —— Loom 1.18 默认不生成，声明了反而会出问题");
    }

    // ==================================================================
    // AccessWidener 必须指向真正声明字段的类
    // ==================================================================

    /**
     * 回归钉子：{@code leftPos} 等 4 个字段声明在
     * <b>{@code AbstractContainerScreen}</b> 上，不在 {@code MerchantScreen}。
     *
     * <p>AW 指向错误的类时不会报错，只会被当成「这个类没有该字段」而忽略，
     * 然后编译期报「protected 访问控制」——我第一次就是这么错的。</p>
     */
    @Test
    void accessWidenerTargetsDeclaringClass() throws IOException {
        String aw = readFile("fabric-1.21.1/src/main/resources/ironfarm.accesswidener");

        assertTrue(aw.contains("accessible field net/minecraft/client/gui/screens/inventory/"
                        + "AbstractContainerScreen leftPos I"),
                "leftPos 声明在 AbstractContainerScreen 上，AW 必须指向它");
        assertFalse(aw.contains("accessible field net/minecraft/client/gui/screens/inventory/"
                        + "MerchantScreen leftPos I"),
                "不能指向 MerchantScreen —— 那里没有这个字段");
    }

    @Test
    void accessWidenerDeclaresAllFourLayoutFields() throws IOException {
        String aw = readFile("fabric-1.21.1/src/main/resources/ironfarm.accesswidener");
        for (String f : new String[]{"leftPos", "topPos", "imageWidth", "imageHeight"}) {
            assertTrue(aw.contains("AbstractContainerScreen " + f + " I"),
                    "缺少字段开放: " + f);
        }
    }

    // ==================================================================

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
