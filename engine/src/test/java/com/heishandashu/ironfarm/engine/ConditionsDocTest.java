package com.heishandashu.ironfarm.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关于页内嵌的条件文档。
 *
 * <p>需求："关于页面底部，把《铁傀儡刷新条件-详解》内容全部放进去。"</p>
 *
 * <p>实现选择：文档作为**资源文件**打包（{@code assets/ironfarm/conditions/*.txt}），
 * 而不是塞进语言文件。理由见 {@code ConditionsDoc} 的注释：</p>
 * <ul>
 *   <li>472 行 / 17 KB，塞进 lang 会让语言文件无法维护；</li>
 *   <li>文档是有缩进、空行、表格的排版文本，lang 是 key=value；</li>
 *   <li>资源文件可以直接编辑，与工程根目录那份保持同源。</li>
 * </ul>
 */
class ConditionsDocTest {

    private static final Path RES =
            Path.of("fabric-1.21.1/src/main/resources/assets/ironfarm/conditions");

    private static Path dir() {
        Path p = RES;
        if (!Files.exists(p)) {
            p = Path.of("../" + RES);
        }
        return p;
    }

    // ==================================================================

    @Test
    void bothLanguageDocsExist() {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Path p = dir().resolve(lang + ".txt");
            assertTrue(Files.exists(p), "缺少文档资源: " + lang + ".txt");
        }
    }

    /**
     * 中文文档必须与工程根目录那份**完全一致**。
     *
     * <p>这是"同源"的保证：改根目录的文档后，必须同步复制到资源目录，
     * 否则玩家在游戏里看到的是旧版本。</p>
     */
    @Test
    void chineseDocMatchesRootCopy() throws IOException {
        Path rootDoc = Path.of("铁傀儡刷新条件-详解.txt");
        if (!Files.exists(rootDoc)) {
            rootDoc = Path.of("../铁傀儡刷新条件-详解.txt");
        }
        Path resDoc = dir().resolve("zh_cn.txt");
        assertTrue(Files.exists(rootDoc), "工程根目录应有《铁傀儡刷新条件-详解.txt》");
        assertTrue(Files.exists(resDoc));

        assertEquals(normalize(read(rootDoc)), normalize(read(resDoc)),
                "资源里的 zh_cn.txt 必须与根目录文档一致（改完要同步复制）");
    }

    /**
     * 文档必须是完整的十条章节，不能只放摘要。
     *
     * <p>用户明确要求"内容全部放进去"，所以这里逐条核对章节标题。</p>
     */
    @Test
    void chineseDocHasAllTenSections() throws IOException {
        String text = read(dir().resolve("zh_cn.txt"));
        String[] sections = {
                "一、总览",
                "二、个体村民",
                "三、群体条件",
                "四、恐吓路径",
                "五、生成点判定",
                "六、完整判定流程",
                "七、勘误",
                "八、实战检查清单",
                "九、对刷铁机设计的直接推论",
                "十、本文数据的验证方式",
        };
        for (String s : sections) {
            assertTrue(text.contains(s), "文档缺少章节: " + s);
        }
    }

    @Test
    void englishDocHasAllTenSections() throws IOException {
        String text = read(dir().resolve("en_us.txt"));
        String[] sections = {
                "1. Overview",
                "2. Conditions every individual",
                "3. Group condition",
                "4. Panic path",
                "5. Spawn point",
                "6. Full decision flow",
                "7. Corrections",
                "8. Practical checklist",
                "9. Direct consequences",
                "10. How this data was verified",
        };
        for (String s : sections) {
            assertTrue(text.contains(s), "English doc missing section: " + s);
        }
    }

    /**
     * 两份文档都要包含那几条关键勘误（599、凋灵、参与者不需要恐慌）。
     */
    @Test
    void bothDocsCarryKeyCorrections() throws IOException {
        String zh = read(dir().resolve("zh_cn.txt"));
        String en = read(dir().resolve("en_us.txt"));

        assertTrue(zh.contains("599"), "中文文档必须写明 599");
        assertTrue(en.contains("599"), "英文文档必须写明 599");

        assertTrue(zh.contains("凋灵"), "中文文档必须说明凋灵不能吓村民");
        assertTrue(en.contains("Wither"), "英文文档必须说明凋灵不能吓村民");

        assertTrue(zh.contains("不需要处于恐慌") || zh.contains("不需要恐慌"),
                "中文文档必须说明参与者不需要恐慌");
        assertTrue(en.contains("do not need to be panicking"),
                "英文文档必须说明参与者不需要恐慌");
    }

    /**
     * 文档不能是空的、也不能过短 —— 防止有人误删内容。
     */
    @Test
    void docsAreSubstantial() throws IOException {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            String text = read(dir().resolve(lang + ".txt"));
            int lines = text.split("\n", -1).length;
            assertTrue(lines > 300,
                    lang + ".txt 只有 " + lines + " 行，疑似内容不完整（应 > 300 行）");
            assertFalse(text.trim().isEmpty());
        }
    }

    // ------------------------------------------------------------------

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n").trim();
    }

    private static String read(Path p) throws IOException {
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
}
