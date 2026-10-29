package com.heishandashu.ironfarm.engine.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置的序列化。手写 key=value，不引任何配置库。
 *
 * <p>为什么不用 JSON / TOML 库：整个工程只需要"存 15 个字段"，
 * 引一个配置库要多几百 KB 依赖，还可能在版本升级时改格式。
 * 手写一个 120 行的编解码器，格式稳定、可直接手改、出错时能回落默认值。</p>
 *
 * <p>⚠ 容错原则：**任何一行解析失败都只跳过那一行**，绝不整体失败。
 * 玩家的配置文件被手改坏一格，不应该导致模组完全不工作。
 * 这也是为什么每个字段都独立 try/catch。</p>
 *
 * <p>格式：</p>
 * <pre>
 * # 注释
 * masterEnabled=true
 * overlayMaxDistance=32.0
 * requiredItemId=minecraft:iron_ingot
 * </pre>
 */
public final class SettingsCodec {

    /** 当前格式版本，将来改字段时用来做迁移。 */
    public static final int FORMAT_VERSION = 1;

    private SettingsCodec() {
    }

    /** 序列化为文本。 */
    public static String write(ModSettings s) {
        StringWriter sw = new StringWriter();
        try {
            write(s, sw);
        } catch (IOException e) {
            // StringWriter 不会抛，这里只是兜底
            return "";
        }
        return sw.toString();
    }

    /** 写到给定的 Writer。 */
    public static void write(ModSettings s, Writer out) throws IOException {
        out.write("# 刷铁机设计轮椅 Iron Farm Design Wheelchair\n");
        out.write("# 设置文件。可以手工修改，但请保持 key=value 格式。\n");
        out.write("# Settings file. Hand editing is fine, keep the key=value format.\n");
        out.write("\n");
        out.write("formatVersion=" + FORMAT_VERSION + "\n");
        out.write("\n");
        out.write("# ---- 六个开关 / the six switches ----\n");
        out.write("masterEnabled=" + s.masterEnabled + "\n");
        out.write("rightClickVillagerEnabled=" + s.rightClickVillagerEnabled + "\n");
        out.write("villagerOverlayEnabled=" + s.villagerOverlayEnabled + "\n");
        out.write("bedLinesEnabled=" + s.bedLinesEnabled + "\n");
        out.write("spawnBoxEnabled=" + s.spawnBoxEnabled + "\n");
        out.write("panicLinesEnabled=" + s.panicLinesEnabled + "\n");
        out.write("golemDetectBoxEnabled=" + s.golemDetectBoxEnabled + "\n");
        out.write("\n");
        out.write("# ---- 渲染 / rendering ----\n");
        out.write("overlayMaxDistance=" + s.overlayMaxDistance + "\n");
        out.write("lineMaxDistance=" + s.lineMaxDistance + "\n");
        out.write("requireHoldingItem=" + s.requireHoldingItem + "\n");
        out.write("requiredItemId=" + s.requiredItemId + "\n");
        out.write("scaleByDistance=" + s.scaleByDistance + "\n");
        out.write("\n");
        out.write("# ---- 语言 / language (auto / zh_cn / en_us) ----\n");
        out.write("locale=" + s.locale + "\n");
        out.write("\n");
        out.write("# ---- 判定 / evaluation ----\n");
        out.write("scaryMobStrictMode=" + s.scaryMobStrictMode + "\n");
        out.write("statsRecordFailures=" + s.statsRecordFailures + "\n");
        out.write("woolHopperEnabled=" + s.woolHopperEnabled + "\n");
        out.write("debugLog=" + s.debugLog + "\n");
        out.write("chatNotifyOnSpawn=" + s.chatNotifyOnSpawn + "\n");
    }

    /**
     * 从文本解析。
     *
     * <p>缺失的字段保留 {@link ModSettings} 的默认值；
     * 单行出错只跳过那一行。</p>
     */
    public static ModSettings read(String text) {
        ModSettings s = new ModSettings();
        if (text == null || text.isEmpty()) {
            return s;
        }
        Map<String, String> kv = parse(text);
        apply(s, kv);
        return s;
    }

    /** 从 Reader 解析。 */
    public static ModSettings read(Reader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        return read(sb.toString());
    }

    /** 把键值对应用到一个已有的设置对象上（用于"热重载"）。 */
    public static void apply(ModSettings s, Map<String, String> kv) {
        s.masterEnabled = bool(kv, "masterEnabled", s.masterEnabled);
        s.rightClickVillagerEnabled = bool(kv, "rightClickVillagerEnabled", s.rightClickVillagerEnabled);
        s.villagerOverlayEnabled = bool(kv, "villagerOverlayEnabled", s.villagerOverlayEnabled);
        s.bedLinesEnabled = bool(kv, "bedLinesEnabled", s.bedLinesEnabled);
        s.spawnBoxEnabled = bool(kv, "spawnBoxEnabled", s.spawnBoxEnabled);
        s.panicLinesEnabled = bool(kv, "panicLinesEnabled", s.panicLinesEnabled);
        s.golemDetectBoxEnabled = bool(kv, "golemDetectBoxEnabled", s.golemDetectBoxEnabled);

        s.overlayMaxDistance = dbl(kv, "overlayMaxDistance", s.overlayMaxDistance);
        s.lineMaxDistance = dbl(kv, "lineMaxDistance", s.lineMaxDistance);
        s.requireHoldingItem = bool(kv, "requireHoldingItem", s.requireHoldingItem);
        s.requiredItemId = str(kv, "requiredItemId", s.requiredItemId);
        s.scaleByDistance = bool(kv, "scaleByDistance", s.scaleByDistance);

        s.locale = str(kv, "locale", s.locale);

        s.scaryMobStrictMode = bool(kv, "scaryMobStrictMode", s.scaryMobStrictMode);
        s.statsRecordFailures = bool(kv, "statsRecordFailures", s.statsRecordFailures);
        s.woolHopperEnabled = bool(kv, "woolHopperEnabled", s.woolHopperEnabled);
        s.debugLog = bool(kv, "debugLog", s.debugLog);
        s.chatNotifyOnSpawn = bool(kv, "chatNotifyOnSpawn", s.chatNotifyOnSpawn);
    }

    /** 解析成键值对，跳过注释与空行。 */
    public static Map<String, String> parse(String text) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        BufferedReader r = new BufferedReader(new StringReader(text));
        String line;
        try {
            while ((line = r.readLine()) != null) {
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
            // StringReader 不会抛
        }
        return map;
    }

    // ---- 带兜底的取值 ----

    private static boolean bool(Map<String, String> kv, String key, boolean fallback) {
        String v = kv.get(key);
        if (v == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "0".equals(v) || "no".equalsIgnoreCase(v)) {
            return false;
        }
        return fallback; // 认不出来的值保留默认，不报错
    }

    private static double dbl(Map<String, String> kv, String key, double fallback) {
        String v = kv.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(v);
            // 距离类字段做一次合理范围夹取，防止手改成 99999 把渲染拖死
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return fallback;
            }
            if (d < 1.0) {
                return 1.0;
            }
            if (d > 512.0) {
                return 512.0;
            }
            return d;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String str(Map<String, String> kv, String key, String fallback) {
        String v = kv.get(key);
        if (v == null) {
            return fallback;
        }
        return v;
    }
}
