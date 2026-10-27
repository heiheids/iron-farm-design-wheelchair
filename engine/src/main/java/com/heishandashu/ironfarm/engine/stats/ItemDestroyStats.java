package com.heishandashu.ironfarm.engine.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实物统计：漏斗销毁掉的物品。
 *
 * <p><b>需求（用户原话）：</b></p>
 * <blockquote>
 * 判断当漏斗指向白色羊毛块的时候，所有漏斗里的物品会被自动销毁，
 * 无论里面有多少物品，每刻都全部销毁，但是记录下销毁掉的物品，
 * 按数量和种类进行统计，记录为，总数量、平均每小时产量。
 * 每个物品的总数量和每小时产量。
 * </blockquote>
 *
 * <p><b>为什么这个统计要单独一个类，不塞进 {@link StatsRecorder}：</b></p>
 * <ul>
 *   <li>它的时间基准与铁傀儡统计**不同**：玩家可能只开白色羊毛销毁，
 *       不开铁傀儡统计。塞在一起会让"开始记录"的含义变得含糊。</li>
 *   <li>它按物品**种类**分组，是 Map 结构；铁傀儡那套是计数器。</li>
 *   <li>清空统计时可以各自清空 —— 但按需求是"一起放在统计页"，
 *       所以两者由同一个 {@code reset()} 统筹（见 StatsRecorder.reset）。</li>
 * </ul>
 *
 * <p><b>⚠ 单位：数量是"个"，速率是"个/小时"。</b>
 * 这两个数字经常被混着说（"我这台机器 3000"到底是总共还是每小时？），
 * 所以界面上必须把单位写进标签里。</p>
 *
 * <p>线程安全：只在客户端主线程调用（tick 与界面），
 * 与 StatsRecorder 一致，不做同步。</p>
 */
public final class ItemDestroyStats {

    /** 一个物品的累计数据。 */
    public static final class Entry {
        /** 物品注册名，例如 {@code minecraft:iron_ingot}。 */
        public final String itemId;
        /** 累计销毁数量。 */
        public long total;

        public Entry(String itemId, long total) {
            this.itemId = itemId;
            this.total = total;
        }
    }

    /**
     * 每个物品的累计数量。
     *
     * <p>用 LinkedHashMap 而不是 HashMap：遍历顺序稳定，
     * 界面上的行不会每帧跳来跳去（排序在 {@link #sortedByCount()} 里做）。</p>
     */
    private final Map<String, Entry> byItem = new LinkedHashMap<String, Entry>();

    /** 累计销毁总数量（所有物品之和）。 */
    private long totalItems;

    /** 开始记录的游戏时间；-1 = 未开始。 */
    private long startGameTime = -1;

    // ==================================================================

    public boolean isStarted() {
        return startGameTime >= 0;
    }

    public long startGameTime() {
        return startGameTime;
    }

    public long totalItems() {
        return totalItems;
    }

    /** 有多少种物品被销毁过。 */
    public int kinds() {
        return byItem.size();
    }

    /**
     * 记录一次销毁。
     *
     * <p>每 tick 调用一次，一次可能销毁多个物品（"无论里面有多少物品，
     * 每刻都全部销毁"），所以这里接受批量数量。</p>
     *
     * @param gameTime 当前游戏时间
     * @param itemId   物品注册名
     * @param count    本次销毁数量（必须 > 0，0 会被忽略）
     */
    public void record(long gameTime, String itemId, long count) {
        if (count <= 0 || itemId == null || itemId.isEmpty()) {
            return;
        }
        if (startGameTime < 0) {
            startGameTime = gameTime;
        }
        Entry e = byItem.get(itemId);
        if (e == null) {
            e = new Entry(itemId, 0);
            byItem.put(itemId, e);
        }
        e.total += count;
        totalItems += count;
    }

    /**
     * 把起始时间对齐到主统计。
     *
     * <p>⚠ 必须在"点开始记录"时调用，否则实物统计会用
     * "第一次销毁物品"作为起点，导致它与上面铁傀儡数据的分母不同 ——
     * 界面上"上下数据对不上"就是这么来的。</p>
     */
    public void syncStart(long gameTime) {
        this.startGameTime = gameTime;
    }

    /** 某个物品已销毁多少（没有记录返回 0）。 */
    public long countOf(String itemId) {
        Entry e = byItem.get(itemId);
        return e == null ? 0 : e.total;
    }

    /**
     * 按数量从多到少排序。
     *
     * <p>数量相同时按物品名排序 —— 否则同一批新建的物品
     * 每次遍历顺序可能不同，界面会闪。</p>
     */
    public List<Entry> sortedByCount() {
        List<Entry> out = new ArrayList<Entry>(byItem.values());
        out.sort(new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = Long.compare(b.total, a.total);
                return c != 0 ? c : a.itemId.compareTo(b.itemId);
            }
        });
        return out;
    }

    /** 全部条目（未排序）。 */
    public List<Entry> all() {
        return new ArrayList<Entry>(byItem.values());
    }

    // ==================================================================
    // 速率
    // ==================================================================

    /**
     * 总销毁速率（个/小时）。
     *
     * <p>用游戏时间算，不用真实时间 —— 与铁傀儡统计保持同一口径，
     * 否则暂停游戏时速率会掉下来，玩家会以为是机器变慢了。</p>
     *
     * @param nowGameTime 当前游戏时间
     * @return 每小时个数；未开始或时间不足返回 0
     */
    public double itemsPerHour(long nowGameTime) {
        double hours = elapsedHours(nowGameTime);
        if (hours <= 0) {
            return 0;
        }
        return totalItems / hours;
    }

    /**
     * 某个物品的销毁速率（个/小时）。
     */
    public double perHourOf(String itemId, long nowGameTime) {
        double hours = elapsedHours(nowGameTime);
        if (hours <= 0) {
            return 0;
        }
        return countOf(itemId) / hours;
    }

    /**
     * 每小时的 tick 数。
     *
     * <p><b>⚠ 必须与 StatsRecorder 完全一致（72000），否则上下两块统计
     * 的"每小时"会差 72 倍。</b>0.42.0 就在这里写了 1000，
     * 导致铁锭产量和销毁速率用的是两套时间基准 ——
     * 用户看到的"上下数据严重对不上"就是这个。</p>
     *
     * <p>72000 tick = 1 游戏小时（20 tick/秒 × 3600 秒）。
     * 一天 24000 tick = 20 分钟真实时间 = 1/3 小时。</p>
     */
    public static final double TICKS_PER_HOUR = 72000.0;

    /**
     * 已记录的时长（小时）。
     *
     * <p>⚠ 返回 0 时调用方必须处理（不能除零得到 Infinity/NaN）。</p>
     */
    private double elapsedHours(long nowGameTime) {
        if (startGameTime < 0 || nowGameTime <= startGameTime) {
            return 0;
        }
        return (nowGameTime - startGameTime) / TICKS_PER_HOUR;
    }

    /** 已记录的 tick 数（供界面显示"已记录 X 小时 Y 分"）。 */
    public long elapsedTicks(long nowGameTime) {
        if (startGameTime < 0 || nowGameTime <= startGameTime) {
            return 0;
        }
        return nowGameTime - startGameTime;
    }

    // ==================================================================

    /** 清空。 */
    public void reset() {
        byItem.clear();
        totalItems = 0;
        startGameTime = -1;
    }

    /** 供持久化恢复。 */
    public void restore(long startGameTime, String itemId, long total) {
        if (itemId == null || itemId.isEmpty() || total <= 0) {
            return;
        }
        this.startGameTime = startGameTime;
        Entry e = byItem.get(itemId);
        if (e == null) {
            e = new Entry(itemId, 0);
            byItem.put(itemId, e);
        }
        e.total += total;
        totalItems += total;
    }
}