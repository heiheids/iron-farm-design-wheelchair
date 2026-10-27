package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.stats.GolemLifecycleTracker;
import com.heishandashu.ironfarm.engine.stats.StatsRecorder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「清空统计」必须清干净。
 *
 * <p><b>真实事故（用户两次截图对比）：</b></p>
 * <pre>
 * 第一次：刷出 108，开始记录 第1天06:31，平均产量 34.9/小时
 * 第二次：刷出  94，开始记录 第1天06:31，平均产量 24.4/小时   ← 清空过
 * </pre>
 *
 * <p>数量变了说明清空过，但「开始记录」还是同一时刻 ——
 * 因为 {@code reset()} 只清了计数，没清 {@code startGameTime}。</p>
 *
 * <p>后果不只是显示错误：「平均产量」的分母仍然是旧起点，
 * 清空后刷 94 只却按「从第 1 天 06:31 开始」算，
 * 产量被系统性低估。用户反馈的「效率对不上」就是这个。</p>
 *
 * <p>另外两次截图的「平均离开检测范围 5.6 秒」「平均存活时长 54.2 秒」
 * 完全相同，说明 {@code lifecycle} 也没被清空。</p>
 */
class StatsResetTest {

    private static VillagerSnapshot v(int id) {
        return VillagerSnapshot.of(id, 0, 64, 0);
    }

    // ==================================================================

    /**
     * reset 之后必须回到「未开始」状态，下一次记录会重新起算。
     */
    @Test
    void resetClearsStartTime() {
        StatsRecorder s = new StatsRecorder();
        s.start(1000, 12345);
        assertTrue(s.isStarted());
        assertEquals(1000, s.startGameTime());

        s.reset();

        assertFalse(s.isStarted(), "清空后应回到未开始状态");
        assertEquals(-1, s.startGameTime(), "开始时间必须被清掉");
        assertEquals(-1, s.startWallClock(), "真实时间戳也要清");
    }

    /**
     * 清空后重新记录，开始时间应该是**新**的时刻。
     */
    @Test
    void afterResetStartTimeIsRecalculated() {
        StatsRecorder s = new StatsRecorder();
        s.start(1000, 0);
        s.recordSpawn(2000, 1);
        s.reset();

        // 清空后第一次记录 —— 应当以此时的 gameTime 重新开始
        s.recordSpawn(50000, 1);
        assertEquals(50000, s.startGameTime(),
                "清空后应以新的游戏时间重新起算，而不是沿用 1000");
    }

    /**
     * 清空后产量必须按新起点计算。
     *
     * <p>这是「效率对不上」的核心：如果 startGameTime 不清，
     * 分母会包含清空前的时间，产量被低估。</p>
     */
    @Test
    void afterResetRateUsesNewStart() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 0);
        // 旧数据：100 只，跑了很久
        for (int i = 0; i < 100; i++) {
            s.recordSpawn(i * 1000L, 1);
        }
        s.reset();

        // 清空后：2 只，跑了正好 1 小时（72000 tick）
        s.recordSpawn(100000, 1);
        s.recordSpawn(172000, 1);

        // 2 只 / 1 小时 = 2.0；如果沿用旧起点(0)，会是 2/2.4 小时 ≈ 0.83
        assertEquals(2.0, s.golemsPerHour(172000), 0.001,
                "清空后产量必须按新起点算");
    }

    /**
     * 生命周期样本也必须清空。
     */
    @Test
    void resetClearsLifecycleSamples() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 0);

        // 造一个完整的傀儡生命周期样本
        GolemLifecycleTracker lc = s.lifecycle();
        Map<Integer, double[]> one = new HashMap<Integer, double[]>();
        one.put(Integer.valueOf(1), new double[]{0, 64, 0});
        lc.observe(0, one, id -> true);
        lc.observe(100, one, id -> false);
        lc.observe(500, new HashMap<Integer, double[]>(), id -> false);
        assertEquals(1, lc.completedSamples(), "先确认样本造出来了");

        s.reset();

        assertEquals(0, lc.completedSamples(), "清空后生命周期样本必须归零");
        assertEquals(-1.0, lc.averageTicksToLeaveDetection(), 0.001,
                "清空后应显示「暂无数据」而不是旧的平均值");
        assertEquals(-1.0, lc.averageTicksAlive(), 0.001);
    }

    /**
     * 清空后各项计数全部归零。
     */
    @Test
    void resetClearsAllCounters() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 0);
        s.recordSpawn(100, 1);
        s.recordSpawn(200, 2);
        s.recordAttempt(300);
        s.recordFailureDetail(400, v(1),
                java.util.List.of(
                        com.heishandashu.ironfarm.engine.rule.RuleResult
                                .fail(com.heishandashu.ironfarm.engine.rule.Condition.SLEPT,
                                        "never_slept")));

        s.reset();

        assertEquals(0, s.golemsSpawned());
        assertEquals(0, s.summonAttempts());
        assertTrue(s.failuresByCount().isEmpty(), "失败记录要清空");
        assertTrue(s.failuresByReason().isEmpty());
    }

    /**
     * 铁锭估算在清空后必须归零。
     */
    @Test
    void resetClearsIngotEstimates() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 0);
        for (int i = 0; i < 10; i++) {
            s.recordSpawn(i * 100L, 1);
        }
        assertEquals(40.0, s.ironIngotsTotal(), 0.001);

        s.reset();

        assertEquals(0.0, s.ironIngotsTotal(), 0.001,
                "清空后累计铁锭必须是 0");
        assertEquals(0.0, s.ironIngotsPerHour(72000), 0.001);
    }
}
