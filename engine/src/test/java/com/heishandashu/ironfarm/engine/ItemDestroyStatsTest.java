package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.rules.WoolDestructor;
import com.heishandashu.ironfarm.engine.stats.ItemDestroyStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实物统计：漏斗指向白色羊毛块时销毁的物品。
 *
 * <p><b>需求（用户原话）：</b></p>
 * <blockquote>
 * 判断当漏斗指向白色羊毛块的时候，所有漏斗里的物品会被自动销毁，
 * 无论里面有多少物品，每刻都全部销毁，但是记录下销毁掉的物品，
 * 按数量和种类进行统计，记录为，总数量、平均每小时产量。
 * 每个物品的总数量和每小时产量。
 * </blockquote>
 */
class ItemDestroyStatsTest {

    // ==================================================================
    // 触发条件
    // ==================================================================

    /** 只有白色羊毛触发，其它颜色不触发。 */
    @Test
    void onlyWhiteWoolTriggers() {
        assertTrue(WoolDestructor.triggersDestruction("minecraft:white_wool"),
                "白色羊毛应触发销毁");
        assertFalse(WoolDestructor.triggersDestruction("minecraft:red_wool"),
                "其它颜色的羊毛不该触发 —— 需求说的是白色羊毛块");
        assertFalse(WoolDestructor.triggersDestruction("minecraft:white_concrete"),
                "白色混凝土不是羊毛");
        assertFalse(WoolDestructor.triggersDestruction(null));
        assertFalse(WoolDestructor.triggersDestruction(""));
    }

    /** 方向偏移必须与 Minecraft 的坐标系一致。 */
    @Test
    void directionOffsetsAreCorrect() {
        assertArrayEquals(new int[]{0, -1, 0}, WoolDestructor.offsetOf("down"));
        assertArrayEquals(new int[]{0, 1, 0}, WoolDestructor.offsetOf("up"));
        assertArrayEquals(new int[]{0, 0, -1}, WoolDestructor.offsetOf("north"));
        assertArrayEquals(new int[]{0, 0, 1}, WoolDestructor.offsetOf("south"));
        assertArrayEquals(new int[]{-1, 0, 0}, WoolDestructor.offsetOf("west"));
        assertArrayEquals(new int[]{1, 0, 0}, WoolDestructor.offsetOf("east"));
        assertNull(WoolDestructor.offsetOf("nonsense"));
    }

    private static void assertArrayEquals(int[] a, int[] b) {
        assertEquals(a[0], b[0]);
        assertEquals(a[1], b[1]);
        assertEquals(a[2], b[2]);
    }

    // ==================================================================
    // 统计
    // ==================================================================

    @Test
    void recordsTotalAndPerItem() {
        ItemDestroyStats s = new ItemDestroyStats();
        s.record(1000, "minecraft:cobblestone", 64);
        s.record(1000, "minecraft:cobblestone", 64);
        s.record(1000, "minecraft:dirt", 10);

        assertEquals(138, s.totalItems(), "总量应为三笔之和");
        assertEquals(2, s.kinds(), "应记录两种物品");
        assertEquals(128, s.countOf("minecraft:cobblestone"));
        assertEquals(10, s.countOf("minecraft:dirt"));
        assertEquals(0, s.countOf("minecraft:iron_ingot"), "没记录的物品应为 0");
    }

    /**
     * 一次记账多个（"无论里面有多少物品，每刻都全部销毁"）。
     */
    @Test
    void recordsBulkCountInOneCall() {
        ItemDestroyStats s = new ItemDestroyStats();
        s.record(500, "minecraft:iron_ingot", 1728);   // 27 组
        assertEquals(1728, s.totalItems());
        assertEquals(1728, s.countOf("minecraft:iron_ingot"));
    }

    /** 数量 0 或负数应被忽略，否则会污染统计。 */
    @Test
    void ignoresNonPositiveCounts() {
        ItemDestroyStats s = new ItemDestroyStats();
        s.record(100, "minecraft:dirt", 0);
        s.record(100, "minecraft:dirt", -5);
        assertEquals(0, s.totalItems());
        assertEquals(0, s.kinds(), "不该因为 0 数量而创建条目");
    }

    // ==================================================================
    // 速率
    // ==================================================================

    /**
     * 速率 = 数量 / 小时，口径与铁傀儡统计**完全一致**。
     *
     * <p><b>⚠ 这条测试曾经写错过：</b>0.42.0 里它断言
     * "1000 tick = 1 小时"，而 StatsRecorder 用的是 72000。
     * 于是测试通过了，但界面上铁锭产量和销毁速率差了 72 倍 ——
     * 用户反馈"上下数据严重对不上"。</p>
     *
     * <p>现在两边都用 {@link ItemDestroyStats#TICKS_PER_HOUR}（72000）。
     * 72000 tick = 1 游戏小时 = 20 tick/秒 × 3600 秒。</p>
     */
    @Test
    void perHourUsesSameTickBaseAsGolemStats() {
        ItemDestroyStats s = new ItemDestroyStats();
        // 从 tick 0 开始，销毁 100 个
        s.record(0, "minecraft:dirt", 100);
        // 到 tick 144000 = 2 小时（72000 tick/小时）
        assertEquals(50.0, s.itemsPerHour(144_000L), 0.001,
                "100 个 / 2 小时 = 50 个/小时");
        assertEquals(50.0, s.perHourOf("minecraft:dirt", 144_000L), 0.001);
    }

    /**
     * 刚开始记录时（elapsed = 0）不能除零。
     *
     * <p>否则界面会显示 "Infinity 个/小时"。</p>
     */
    @Test
    void noDivisionByZeroAtStart() {
        ItemDestroyStats s = new ItemDestroyStats();
        s.record(1000, "minecraft:dirt", 5);
        double rate = s.itemsPerHour(1000);
        assertFalse(Double.isInfinite(rate), "刚记录时不能返回 Infinity");
        assertFalse(Double.isNaN(rate), "刚记录时不能返回 NaN");
        assertEquals(0.0, rate, 0.001);
    }

    /** 未开始记录时速率应为 0。 */
    @Test
    void notStartedGivesZeroRate() {
        ItemDestroyStats s = new ItemDestroyStats();
        assertEquals(0.0, s.itemsPerHour(99999), 0.001);
        assertFalse(s.isStarted());
    }

    /** 第一次记账才开始计时（不是构造时）。 */
    @Test
    void startsCountingOnFirstRecord() {
        ItemDestroyStats s = new ItemDestroyStats();
        assertFalse(s.isStarted());
        s.record(5000, "minecraft:dirt", 1);
        assertTrue(s.isStarted());
        assertEquals(5000, s.startGameTime());
    }

    // ==================================================================
    // 排序与清空
    // ==================================================================

    /** 按数量从多到少；同数量按名字，保证界面不闪。 */
    @Test
    void sortedByCountDescendingThenName() {
        ItemDestroyStats s = new ItemDestroyStats();
        s.record(0, "minecraft:zinc", 5);
        s.record(0, "minecraft:apple", 5);
        s.record(0, "minecraft:diamond", 99);

        List<ItemDestroyStats.Entry> list = s.sortedByCount();
        assertEquals("minecraft:diamond", list.get(0).itemId, "最多的排第一");
        assertEquals("minecraft:apple", list.get(1).itemId,
                "数量相同时按名字升序（apple 在 zinc 前）");
        assertEquals("minecraft:zinc", list.get(2).itemId);
    }

    @Test
    void resetClearsEverything() {
        ItemDestroyStats s = new ItemDestroyStats();
        s.record(1000, "minecraft:dirt", 50);
        s.reset();

        assertEquals(0, s.totalItems());
        assertEquals(0, s.kinds());
        assertFalse(s.isStarted(), "清空后应回到未开始状态");
    }

    /** 持久化恢复：累加而不是覆盖。 */
    @Test
    void restoreAccumulates() {
        ItemDestroyStats s = new ItemDestroyStats();
        s.restore(1000, "minecraft:dirt", 30);
        s.restore(1000, "minecraft:dirt", 20);
        assertEquals(50, s.countOf("minecraft:dirt"));
        assertEquals(1000, s.startGameTime());
    }
}
