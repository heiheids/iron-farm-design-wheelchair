package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.rules.WoolDestructor;
import com.heishandashu.ironfarm.engine.stats.ItemDestroyStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清理周期必须与实际漏斗速度匹配，时间基准必须与主统计一致。
 *
 * <p><b>三轮真实故障：</b></p>
 * <ol>
 *   <li>每 tick 清一次 → 开游戏加速卡死（扫区块太频繁）。</li>
 *   <li>"吸 2 次清一次" → 有残留（2 次 = 16 tick &gt; 吸入间隔 8 tick）。</li>
 *   <li>周期 = 吸入间隔 8 → 看着还有保留（相位问题：清完立刻被填上）。</li>
 * </ol>
 *
 * <p>正确做法：周期必须**严格小于**吸入间隔。取 8/4 = 2 tick。</p>
 */
class ItemDestroyThrottleTest {

    /** 核心断言：清理周期不超过漏斗吸入间隔。 */
    @Test
    void clearPeriodMatchesHopperSpeed() {
        assertTrue(WoolDestructor.CLEAR_PERIOD_TICKS
                        <= WoolDestructor.HOPPER_MOVE_ITEM_SPEED,
                "清理周期必须 ≤ 漏斗吸入间隔（" + WoolDestructor.HOPPER_MOVE_ITEM_SPEED
                        + " tick），否则会积压。当前是 "
                        + WoolDestructor.CLEAR_PERIOD_TICKS);
    }

    /** 漏斗速度常量必须与原版一致（8 tick / 个）。 */
    @Test
    void hopperSpeedConstantIsEight() {
        assertEquals(8, WoolDestructor.HOPPER_MOVE_ITEM_SPEED,
                "原版 HopperBlockEntity.MOVE_ITEM_SPEED = 8，这个数字变了要重新核算周期");
    }

    /** 最大残留量必须远小于漏斗容量，否则会堵塞。 */
    @Test
    void residualStaysFarBelowCapacity() {
        int residual = WoolDestructor.maxResidualItems(WoolDestructor.CLEAR_PERIOD_TICKS);
        assertTrue(residual < WoolDestructor.HOPPER_CONTAINER_SIZE,
                "残留量必须小于漏斗格数，否则可能堵住。实际 " + residual);
    }

    /** 周期越长残留越多 —— 这解释了"来不及清理"的现象。 */
    @Test
    void longerPeriodMeansMoreResidual() {
        assertTrue(WoolDestructor.maxResidualItems(16)
                        > WoolDestructor.maxResidualItems(4),
                "16 tick 周期的残留应多于 4 tick");
    }

    /**
     * 时间线模拟：漏斗每 8 tick 吸 1 个，清理周期取实现值。
     *
     * <p>验证残留峰值不超过理论上限。</p>
     */
    @Test
    void simulationStaysWithinPredictedResidual() {
        int period = WoolDestructor.CLEAR_PERIOD_TICKS;
        int speed = WoolDestructor.HOPPER_MOVE_ITEM_SPEED;

        int inHopper = 0;
        int nextPick = speed;
        int nextClear = period;
        int maxSeen = 0;

        for (int t = 1; t <= 2000; t++) {
            if (t == nextPick) {
                inHopper++;
                nextPick += speed;
            }
            if (t == nextClear) {
                inHopper = 0;
                nextClear += period;
            }
            maxSeen = Math.max(maxSeen, inHopper);
        }
        int predicted = WoolDestructor.maxResidualItems(period);
        assertTrue(maxSeen <= predicted,
                "模拟残留峰值 " + maxSeen + " 超过理论上限 " + predicted);
    }

    /**
     * 时间基准必须与主统计一致。
     *
     * <p><b>用户反馈"上下数据严重对不上"的根因：</b>
     * 0.42.0 里 ItemDestroyStats 用 1000 tick = 1 小时，
     * 而 StatsRecorder 用 72000 —— 差 72 倍。</p>
     */
    @Test
    void ticksPerHourMatchesMainRecorder() {
        assertEquals(72000.0, ItemDestroyStats.TICKS_PER_HOUR,
                "实物统计与主统计必须用同一个 tick/小时 换算（72000）");
    }

    /**
     * 起始时间必须可对齐到主统计。
     *
     * <p>0.42.0 里两块统计各自设 startGameTime：主统计在"点开始记录"时设，
     * 实物统计在"第一次销毁物品"时设。分母不同，算出的每小时自然对不上。</p>
     */
    @Test
    void startTimeCanBeAlignedWithMainStats() {
        ItemDestroyStats d = new ItemDestroyStats();
        d.syncStart(5000L);
        d.record(6000L, "minecraft:iron_ingot", 10);

        // 1000 tick = 1/72 小时，10 个 → 720 个/小时
        double perHour = d.perHourOf("minecraft:iron_ingot", 6000L);
        assertEquals(720.0, perHour, 0.01,
                "1000 tick 内销毁 10 个应为 720 个/小时（72000 tick = 1 小时）");
    }

    /** 已记录时长按 tick 返回，供界面换算成"X 小时 Y 分"。 */
    @Test
    void elapsedTicksIsRawTickDifference() {
        ItemDestroyStats d = new ItemDestroyStats();
        d.syncStart(1000L);
        assertEquals(9000L, d.elapsedTicks(10000L),
                "已记录 tick 数应为 now - start");
        assertEquals(0L, d.elapsedTicks(500L),
                "now 早于 start 时应返回 0，不能是负数");
    }
}