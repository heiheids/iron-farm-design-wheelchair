package com.heishandashu.ironfarm.engine.stats;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统计数据的序列化。
 *
 * <p>为什么需要它：原版行为是关掉游戏统计就清零，
 * 而"刷出多少铁傀儡"恰恰是玩家最想长期观察的数字 ——
 * 一台机器挂机一晚上，第二天想知道产量，结果数据没了，这个功能就白做了。</p>
 *
 * <p>格式仍是手写 key=value，与 {@code SettingsCodec} 保持一致的风格。
 * 单独一个编解码器而不是塞进设置里，因为两者的读写时机不同：
 * 设置是玩家改一次存一次，统计是每隔一段时间自动存一次。</p>
 *
 * <p>⚠ 容错原则同设置：任何一行坏掉只跳过那一行，绝不整体失败。
 * 统计数据坏了最多是数字不对，不该让模组起不来。</p>
 */
public final class StatsCodec {

    /** 当前格式版本。 */
    public static final int FORMAT_VERSION = 1;

    private StatsCodec() {
    }

    /** 序列化。 */
    public static String write(StatsRecorder s) {
        StringWriter sw = new StringWriter();
        try {
            write(s, sw);
        } catch (IOException e) {
            return "";
        }
        return sw.toString();
    }

    public static void write(StatsRecorder s, Writer out) throws IOException {
        out.write("# 刷铁机设计轮椅 Iron Farm Design Wheelchair - statistics\n");
        out.write("# 这个文件由模组自动维护，手改可能导致数字不准。\n");
        out.write("# Maintained by the mod; hand editing may skew the numbers.\n");
        out.write("\n");
        out.write("formatVersion=" + FORMAT_VERSION + "\n");
        out.write("startGameTime=" + s.startGameTime() + "\n");
        out.write("startWallClock=" + s.startWallClock() + "\n");
        out.write("golemsSpawned=" + s.golemsSpawned() + "\n");
        out.write("summonAttempts=" + s.summonAttempts() + "\n");
        // 铁傀儡生命周期：只存总和与次数，恢复时用平均样本还原，
        // 避免往磁盘里写几千个数字。
        var lc = s.lifecycle();
        out.write("golemLeaveSum=" + lc.ticksToLeaveSum() + "\n");
        out.write("golemLeaveCount=" + lc.ticksToLeaveCount() + "\n");
        out.write("golemAliveSum=" + lc.aliveSum() + "\n");
        out.write("golemAliveCount=" + lc.aliveCount() + "\n");
        out.write("golemDiscarded=" + lc.discardedCount() + "\n");
        out.write("\n");

        // 实物统计：一行一个物品。
        //
        // ⚠ 这一段曾经漏掉（0.45.0 及之前），导致实物统计只存在内存里，
        //   一关游戏就归零。用户反馈"统计不到销毁物品信息"就是这个。
        var idst = s.itemDestroy();
        out.write("itemsStartGameTime=" + idst.startGameTime() + "\n");
        out.write("itemsTotal=" + idst.totalItems() + "\n");
        for (var ie : idst.sortedByCount()) {
            out.write("item=" + ie.itemId + "|" + ie.total + "\n");
        }


        out.write("# 失败记录：一行一条，字段用 | 分隔\n");
        out.write("# failure rows: villagerId|reasonCode|count|lastGameTime|lastValue|lastThreshold\n");
        for (FailureRecord f : s.failuresByCount()) {
            out.write("failure=" + f.villagerEntityId
                    + "|" + f.reasonCode
                    + "|" + f.count
                    + "|" + f.lastGameTime
                    + "|" + f.lastValue
                    + "|" + f.lastThreshold
                    + "\n");
        }
    }

    /**
     * 反序列化。
     *
     * <p>缺失字段用默认值；单行出错只跳过那一行。</p>
     */
    public static StatsRecorder read(String text) {
        StatsRecorder s = new StatsRecorder();
        if (text == null || text.isEmpty()) {
            return s;
        }
        Map<String, String> kv = new LinkedHashMap<String, String>();
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
                String key = t.substring(0, eq).trim();
                String value = t.substring(eq + 1).trim();
                if ("failure".equals(key)) {
                    applyFailure(s, value);
                } else if ("item".equals(key)) {
                    applyItem(s, value);
                } else {
                    kv.put(key, value);
                }
            }
        } catch (IOException e) {
            // StringReader 不会抛
        }
        applyHeader(s, kv);
        return s;
    }

    public static StatsRecorder read(Reader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        return read(sb.toString());
    }

    // ------------------------------------------------------------------

    private static void applyHeader(StatsRecorder s, Map<String, String> kv) {
        long startGame = lng(kv, "startGameTime", -1);
        long startWall = lng(kv, "startWallClock", -1);
        if (startGame >= 0) {
            s.restore(startGame, startWall,
                    lng(kv, "golemsSpawned", 0),
                    lng(kv, "summonAttempts", 0));
            s.lifecycle().restore(
                    lng(kv, "golemLeaveSum", 0), lng(kv, "golemLeaveCount", 0),
                    lng(kv, "golemAliveSum", 0), lng(kv, "golemAliveCount", 0),
                    lng(kv, "golemDiscarded", 0));
        }
        // 实物统计的起始时间（可能与主统计不同：syncStart 会对齐，
        // 但旧版文件里可能是它自己的值）。
        long itemsStart = lng(kv, "itemsStartGameTime", -1);
        if (itemsStart >= 0) {
            s.itemDestroy().syncStart(itemsStart);
        }
    }

    /**
     * 解析一条失败记录。
     *
     * <p>字段数不对、数字解析失败、reasonCode 为空 —— 任何一项不满足都直接丢弃那条。
     * 丢弃比"猜一个值填进去"安全：错的统计比没有统计更误导人。</p>
     */
    /**
     * 解析一条物品销毁记录（格式 itemId|total）。
     *
     * <p>与 failure 一样：字段不全、数字非法就直接丢弃 ——
     * 错的统计比没有统计更误导人。</p>
     */
    private static void applyItem(StatsRecorder s, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        int bar = value.lastIndexOf('|');
        if (bar <= 0 || bar >= value.length() - 1) {
            return;
        }
        String itemId = value.substring(0, bar).trim();
        if (itemId.isEmpty()) {
            return;
        }
        try {
            long total = Long.parseLong(value.substring(bar + 1).trim());
            if (total <= 0) {
                return;
            }
            // 用物品自己的起始时间；header 里的 itemsStartGameTime 会在其后
            // 调用 syncStart 覆盖成主统计的起点（若存在）。
            long start = s.itemDestroy().startGameTime();
            s.itemDestroy().restore(start < 0 ? 0 : start, itemId, total);
        } catch (NumberFormatException ignored) {
            // 单个坏行不影响其它物品。
        }
    }

    private static void applyFailure(StatsRecorder s, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length < 3) {
            return;
        }
        try {
            int villagerId = Integer.parseInt(parts[0].trim());
            String reason = parts[1].trim();
            if (reason.isEmpty()) {
                return;
            }
            long count = Long.parseLong(parts[2].trim());
            long lastTime = parts.length > 3 ? Long.parseLong(parts[3].trim()) : 0L;
            double lastValue = parts.length > 4 ? dbl(parts[4].trim()) : Double.NaN;
            double lastThreshold = parts.length > 5 ? dbl(parts[5].trim()) : Double.NaN;
            s.restoreFailure(villagerId, reason, count, lastTime, lastValue, lastThreshold);
        } catch (NumberFormatException e) {
            // 这一行坏了，跳过
        }
    }

    private static double dbl(String v) {
        try {
            return "NaN".equals(v) ? Double.NaN : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static long lng(Map<String, String> kv, String key, long fallback) {
        String v = kv.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
