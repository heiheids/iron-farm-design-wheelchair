package com.heishandashu.ironfarm.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言文件的格式契约。
 *
 * <p><b>这组测试对应的真实事故：</b>我按 Bukkit/Forge 时代的习惯写了
 * {@code zh_cn.lang}（{@code key=value} 文本），一共 164 条，中英各一份。
 * 而 Minecraft 只认 {@code assets/<modid>/lang/en_us.json}（<b>JSON</b>），
 * 原版自己就是 {@code assets/minecraft/lang/en_us.json}。</p>
 *
 * <p>结果：游戏里所有按钮显示原始键名 {@code ironfarm.gui.settings.master}。
 * 编译器没报错、引用检查没报错、当时的单元测试还断言过"两份 .lang 键集合一致"——
 * 但那是自己跟自己比对，属于自说自话。</p>
 *
 * <p>所以这里改成检查 <b>JSON</b> 是否存在、是合法 JSON、且键集合与 .lang 一致。</p>
 */
class LangFormatTest {

    private static final Path LANG_DIR =
            Path.of("engine/src/main/resources/assets/ironfarm/lang");

    private Path dir() {
        Path p = LANG_DIR;
        if (!Files.exists(p)) {
            p = Path.of("../" + LANG_DIR);
        }
        assertTrue(Files.exists(p), "找不到语言目录: " + LANG_DIR);
        return p;
    }

    // ==================================================================
    // 格式：必须是 JSON
    // ==================================================================

    @Test
    void jsonFilesExistForBothLanguages() {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Path json = dir().resolve(lang + ".json");
            assertTrue(Files.exists(json),
                    "缺少 " + lang + ".json —— Minecraft 只读 JSON，读不了 .lang");
        }
    }

    @Test
    void jsonIsWellFormed() throws IOException {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            String text = Files.readString(dir().resolve(lang + ".json"), StandardCharsets.UTF_8);
            Map<String, String> parsed = parseFlatJson(text, lang);
            assertFalse(parsed.isEmpty(), lang + ".json 解析出来是空的");
        }
    }

    // ==================================================================
    // 与 .lang 源文件保持一致
    // ==================================================================

    /**
     * .lang 是可读源文件，.json 是构建产物。两者键集合必须一致，
     * 否则会出现"我改了 .lang 但游戏里没变"这种更隐蔽的问题。
     */
    @Test
    void jsonKeysMatchLangKeys() throws IOException {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Map<String, String> fromLang = readLang(dir().resolve(lang + ".lang"));
            Map<String, String> fromJson = parseFlatJson(
                    Files.readString(dir().resolve(lang + ".json"), StandardCharsets.UTF_8), lang);

            assertEquals(new TreeSet<>(fromLang.keySet()), new TreeSet<>(fromJson.keySet()),
                    lang + ": .json 与 .lang 的键集合不一致");
        }
    }

    @Test
    void jsonValuesMatchLangValues() throws IOException {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Map<String, String> fromLang = readLang(dir().resolve(lang + ".lang"));
            Map<String, String> fromJson = parseFlatJson(
                    Files.readString(dir().resolve(lang + ".json"), StandardCharsets.UTF_8), lang);

            for (Map.Entry<String, String> e : fromLang.entrySet()) {
                assertEquals(e.getValue(), fromJson.get(e.getKey()),
                        lang + ": 键 " + e.getKey() + " 的值与 .lang 不一致");
            }
        }
    }

    @Test
    void bothLanguagesHaveSameKeySet() throws IOException {
        Set<String> zh = parseFlatJson(
                Files.readString(dir().resolve("zh_cn.json"), StandardCharsets.UTF_8), "zh").keySet();
        Set<String> en = parseFlatJson(
                Files.readString(dir().resolve("en_us.json"), StandardCharsets.UTF_8), "en").keySet();
        assertEquals(new TreeSet<>(zh), new TreeSet<>(en),
                "中英键集合必须完全一致，否则切换语言会露出原始键名");
    }

    // ==================================================================
    // 设置页用到的键必须存在
    // ==================================================================

    /**
     * 用户截图里六个开关全显示原始键名。这组断言直接钉住那六个键
     * 在两份 JSON 里都真实存在 —— 而不是"我在测试里自己编一个字典去查"。
     */
    @Test
    void settingsScreenKeysExistInBothLanguages() throws IOException {
        String[] required = {
                "ironfarm.gui.settings.master",
                "ironfarm.gui.settings.overlay",
                "ironfarm.gui.settings.bed_lines",
                "ironfarm.gui.settings.spawn_box",
                "ironfarm.gui.settings.panic_lines",
                "ironfarm.gui.settings.golem_box",
                "ironfarm.gui.settings.language",
                "ironfarm.gui.settings.language.auto",
                "ironfarm.gui.settings.on",
                "ironfarm.gui.settings.off",
                "ironfarm.gui.tab.switches",
                "ironfarm.gui.tab.stats",
                "ironfarm.gui.tab.about",
                "ironfarm.gui.close",
        };
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Set<String> keys = parseFlatJson(
                    Files.readString(dir().resolve(lang + ".json"), StandardCharsets.UTF_8), lang)
                    .keySet();
            for (String k : required) {
                assertTrue(keys.contains(k), lang + ".json 缺少键: " + k);
            }
        }
    }

    @Test
    void noKeyRetainsItsRawNameAsValue() throws IOException {
        // 翻译成"键名本身"是常见的占位错误（复制粘贴时忘改右边）
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Map<String, String> m = parseFlatJson(
                    Files.readString(dir().resolve(lang + ".json"), StandardCharsets.UTF_8), lang);
            for (Map.Entry<String, String> e : m.entrySet()) {
                assertFalse(e.getKey().equals(e.getValue()),
                        lang + ": 键 " + e.getKey() + " 的值就是键名本身");
            }
        }
    }


    // ==================================================================
    // 值里不能含 = 或换行 —— 会破坏 key=value 解析
    // ==================================================================

    /**
     * 回归钉子：中文里写「20 分钟 = 1 个昼夜」会把那行劈成两半。
     *
     * <p>{@code .lang} 格式是 {@code key=value}，解析时只按**第一个** {@code =} 切，
     * 所以值里含 {@code =} 本身不算错。但我的转换脚本与一些工具会按
     * 最后/全部 {@code =} 切，导致多出一条假 key —— 本轮就出现了
     * zh 187 条 vs en 186 条。</p>
     *
     * <p>最稳的规则：值里不写 {@code =}。</p>
     */
    @Test
    void langValuesDoNotContainEqualsSign() throws IOException {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Path p = dir().resolve(lang + ".lang");
            int lineNo = 0;
            for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                lineNo++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int first = line.indexOf('=');
                if (first <= 0) {
                    continue;
                }
                String value = line.substring(first + 1);
                assertFalse(value.contains("="),
                        lang + ".lang 第 " + lineNo + " 行的值里含 = 号，"
                                + "会被某些工具误解析成额外的 key");
            }
        }
    }

    /**
     * 键集合必须完全一致（本轮 zh 一度多出一条假 key「20 分钟」）。
     */
    @Test
    void langFilesHaveIdenticalKeyCount() throws IOException {
        java.util.Set<String> zh = readLang(dir().resolve("zh_cn.lang")).keySet();
        java.util.Set<String> en = readLang(dir().resolve("en_us.lang")).keySet();
        assertEquals(new TreeSet<>(zh), new TreeSet<>(en),
                "中英 .lang 键集合必须一致；"
                        + "只在 zh: " + diff(zh, en) + " 只在 en: " + diff(en, zh));
    }

    private static String diff(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> only = new TreeSet<>(a);
        only.removeAll(b);
        return only.toString();
    }

    // ==================================================================
    // 新增键的存在性
    // ==================================================================

    @Test
    void ironIngotEstimateKeyExists() throws IOException {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            java.util.Set<String> keys = parseFlatJson(
                    Files.readString(dir().resolve(lang + ".json"), StandardCharsets.UTF_8), lang)
                    .keySet();
            assertTrue(keys.contains("ironfarm.gui.stats.ingots_per_hour"),
                    lang + " 缺少铁锭产量估算的键");
            assertTrue(keys.contains("ironfarm.gui.stats.ingots_per_hour.tip"),
                    lang + " 缺少铁锭产量估算的提示");
        }
    }

    /**
     * 关于页的刷新条件速查：标题 + 10 段 + 来源说明，缺一行就会显示原始键名。
     */
    @Test
    void aboutConditionsKeysExistInBothLanguages() throws IOException {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            java.util.Set<String> keys = parseFlatJson(
                    Files.readString(dir().resolve(lang + ".json"), StandardCharsets.UTF_8), lang)
                    .keySet();
            assertTrue(keys.contains("ironfarm.about.conditions.title"),
                    lang + " 缺少条件速查标题");
            for (int i = 1; i <= 10; i++) {
                assertTrue(keys.contains("ironfarm.about.conditions." + i),
                        lang + " 缺少 ironfarm.about.conditions." + i);
            }
            assertTrue(keys.contains("ironfarm.about.conditions.src"),
                    lang + " 缺少来源说明");
        }
    }
    // ------------------------------------------------------------------

    private static Map<String, String> readLang(Path p) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return out;
    }

    /**
     * 极简扁平 JSON 解析（只支持本工程需要的 {"k":"v", ...}）。
     *
     * <p>不引第三方库 —— 工程规矩。如果将来语言文件出现嵌套结构，
     * 那就该换成真正的 JSON 库并征求同意了。</p>
     */
    static Map<String, String> parseFlatJson(String text, String label) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = text.length();
        while (i < n && text.charAt(i) != '{') {
            i++;
        }
        i++;
        while (i < n) {
            while (i < n && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
                i++;
            }
            if (i >= n || text.charAt(i) == '}') {
                break;
            }
            if (text.charAt(i) != '"') {
                throw new IllegalStateException(label + ": 期望键的引号，位置 " + i);
            }
            StringBuilder key = new StringBuilder();
            i = readString(text, i, key, label);
            while (i < n && text.charAt(i) != ':') {
                i++;
            }
            i++;
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n || text.charAt(i) != '"') {
                throw new IllegalStateException(label + ": 期望值的引号，位置 " + i);
            }
            StringBuilder val = new StringBuilder();
            i = readString(text, i, val, label);
            out.put(key.toString(), val.toString());
        }
        return out;
    }

    private static int readString(String text, int start, StringBuilder out, String label) {
        int i = start + 1;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                char e = text.charAt(i + 1);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                        i += 4;
                    }
                    default -> out.append(e);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            out.append(c);
            i++;
        }
        throw new IllegalStateException(label + ": JSON 字符串没有闭合");
    }
}