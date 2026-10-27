package com.heishandashu.ironfarm.engine.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简多语言表。
 *
 * <p>刻意不引第三方库：需要的功能只有「按键查字符串 + 缺键回落到英文 + 支持 %s 占位」，
 * 自己写 80 行比引一个依赖更可控，也符合「尽量减少第三方依赖」的要求。</p>
 *
 * <p>语言文件格式与 Minecraft 的 lang json 完全不同 —— 这里是
 * {@code key=value} 的 UTF-8 文本，一行一条，{@code #} 开头为注释。
 * 用 properties 而不是 json，是为了让 .lang 文件可以直接被人类手工编辑，
 * 且不需要在模组里塞一个 JSON 解析器。</p>
 */
public final class Lang {

    /** 支持的语言，按显示顺序。 */
    public static final String ZH_CN = "zh_cn";
    public static final String EN_US = "en_us";

    private static final String[] SUPPORTED = {ZH_CN, EN_US};

    private final String locale;
    private final Map<String, String> table;
    private final Map<String, String> fallback;

    private Lang(String locale, Map<String, String> table, Map<String, String> fallback) {
        this.locale = locale;
        this.table = table;
        this.fallback = fallback;
    }

    /** 读取某个语言；失败时返回只带英文的空壳，绝不抛异常拖垮游戏。 */
    public static Lang load(String locale) {
        String lang = normalize(locale);
        Map<String, String> table = readResource("/assets/ironfarm/lang/" + lang + ".lang");
        Map<String, String> fb = ZH_CN.equals(lang)
                ? readResource("/assets/ironfarm/lang/" + EN_US + ".lang")
                : Collections.<String, String>emptyMap();
        return new Lang(lang, table, fb);
    }

    /** 由调用方直接给表（测试用）。 */
    public static Lang of(String locale, Map<String, String> table) {
        return new Lang(normalize(locale), new LinkedHashMap<String, String>(table),
                Collections.<String, String>emptyMap());
    }

    /** 把 {@code zh-CN}、{@code zh_CN}、{@code en} 之类的写法归一。 */
    public static String normalize(String locale) {
        if (locale == null || locale.isEmpty()) {
            return EN_US;
        }
        String s = locale.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        if (s.startsWith("zh")) {
            return ZH_CN;
        }
        if (s.startsWith("en")) {
            return EN_US;
        }
        return EN_US;
    }

    /** 英文系统语言名，用于「自动」模式。 */
    public static String autoDetect() {
        String sysLang = java.util.Locale.getDefault().getLanguage();
        return normalize(sysLang);
    }

    public static String[] supported() {
        return SUPPORTED.clone();
    }

    public String locale() {
        return locale;
    }

    public boolean has(String key) {
        return table.containsKey(key) || fallback.containsKey(key);
    }

    /** 查一条。缺键时英文兜底；再缺则原样返回键名（便于发现漏翻）。 */
    public String get(String key) {
        String v = table.get(key);
        if (v != null) {
            return v;
        }
        v = fallback.get(key);
        if (v != null) {
            return v;
        }
        return key;
    }

    /** 带 %s 占位的查表。参数个数不匹配时退回原文并补齐，绝不让调用方炸。 */
    public String format(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(java.util.Locale.ROOT, pattern, args);
        } catch (RuntimeException e) {
            StringBuilder sb = new StringBuilder(pattern);
            for (Object a : args) {
                sb.append(' ').append(a);
            }
            return sb.toString();
        }
    }

    /** 键总数（两种语言的一致性检查用）。 */
    public int size() {
        return table.size();
    }

    /** 只读的全部条目，供一致性检查（中英键集合比对）使用。 */
    public Map<String, String> rawTable() {
        return Collections.unmodifiableMap(table);
    }

    private static Map<String, String> readResource(String path) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        InputStream in = Lang.class.getResourceAsStream(path);
        if (in == null) {
            return map;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                map.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            // 读失败就当作空表，交给英文兜底
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // 关闭失败无关紧要
                }
            }
        }
        return map;
    }
}
