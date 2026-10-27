package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.stats.GolemLifecycleTracker;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 铁傀儡生命周期追踪。
 *
 * <p>两个指标：</p>
 * <ul>
 *   <li>平均离开检测范围用时 —— 收集系统灵不灵敏</li>
 *   <li>平均存活时长 —— 有没有傀儡被卡住/被打死</li>
 * </ul>
 */
class GolemLifecycleTest {

    /** 模拟一个世界：golems = id → 位置。 */
    private static Map<Integer, double[]> golems(Object... idPos) {
        Map<Integer, double[]> m = new HashMap<Integer, double[]>();
        for (int i = 0; i < idPos.length; i += 2) {
            int id = ((Integer) idPos[i]).intValue();
            double[] p = (double[]) idPos[i + 1];
            m.put(Integer.valueOf(id), p);
        }
        return m;
    }

    private static double[] at(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    // ==================================================================

    /**
     * 正常流程：刷出 → 很快离开检测范围 → 过一会儿消失。
     * 两个样本都应该被记录。
     */
    @Test
    void normalGolemRecordsBothSamples() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();

        // t=0 刷出，此时在检测范围内
        t.observe(0, golems(1, at(0, 64, 0)), id -> true);
        // t=40（2 秒）离开检测范围
        t.observe(40, golems(1, at(1, 64, 0)), id -> false);
        // t=200（10 秒）消失（被杀了）
        t.observe(200, golems(), id -> false);

        assertEquals(1, t.completedSamples());
        assertEquals(40.0, t.averageTicksToLeaveDetection(), 0.001,
                "离开检测范围用了 40 tick");
        assertEquals(200.0, t.averageTicksAlive(), 0.001,
                "存活 200 tick");
    }

    /**
     * 卡住的傀儡：一直没离开检测范围就消失了。
     *
     * <p>这种情况答案未知（可能只是区块卸载），
     * **不计入平均数**，但要被计数 —— 否则平均数会骗人。</p>
     */
    @Test
    void golemThatNeverLeftIsDiscarded() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();

        t.observe(0, golems(1, at(0, 64, 0)), id -> true);
        t.observe(100, golems(1, at(0, 64, 0)), id -> true);
        // 直接消失，期间一直在检测范围内
        t.observe(200, golems(), id -> true);

        assertEquals(0, t.completedSamples(), "没离开过检测范围，无法判断真实时长");
        assertEquals(1, t.discardedCount(), "但必须记一笔，否则玩家不知道数据不全");
        assertEquals(-1.0, t.averageTicksToLeaveDetection(), 0.001);
    }

    @Test
    void multipleGolemsAverageCorrectly() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();

        // 傀儡1：t=0 出现；t=10 时它已经不在任何检测范围内
        t.observe(0, golems(1, at(0, 64, 0)), id -> true);
        // 傀儡2 在 t=10 出现；同一次观察里傀儡1 被判定为"离开检测范围"
        t.observe(10, golems(1, at(1, 64, 0), 2, at(0, 64, 0)), id -> id == 2);
        // t=30 傀儡1 已消失（t=10 那帧还在，所以 lastSeen=10）
        //        傀儡2 离开检测范围
        t.observe(30, golems(2, at(2, 64, 0)), id -> false);
        // t=300 都消失
        t.observe(300, golems(), id -> false);

        assertEquals(2, t.completedSamples());
        // 离开检测范围用时：傀儡1 = 10-0 = 10；傀儡2 = 30-10 = 20 → 平均 15
        //
        // ⚠ 这里我一开始期望 20，那是错的：傀儡1 在 t=10 就已经
        //    不在检测范围内了（那次 observe 的 insideDetector 返回 false），
        //    不是 t=20。测试要跟着实际语义走，不能凭印象写。
        assertEquals(15.0, t.averageTicksToLeaveDetection(), 0.001);
        // 存活：傀儡1 = 30-0 = 30（t=30 才从实体表消失）；
        //       傀儡2 = 300-10 = 290 → 平均 160
        assertEquals(160.0, t.averageTicksAlive(), 0.001);
    }

    @Test
    void noSamplesReturnsMinusOne() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();
        assertEquals(-1.0, t.averageTicksToLeaveDetection(), 0.001,
                "没有样本要返回 -1，界面据此显示「暂无数据」");
        assertEquals(-1.0, t.averageTicksAlive(), 0.001);
        assertEquals(0, t.completedSamples());
    }

    @Test
    void tracksCountReflectsLiveGolems() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();
        t.observe(0, golems(1, at(0, 0, 0), 2, at(0, 0, 0)), id -> true);
        assertEquals(2, t.trackedCount());
        t.observe(10, golems(1, at(0, 0, 0)), id -> true);
        assertEquals(1, t.trackedCount(), "消失的应当被移除");
    }

    @Test
    void resetClearsEverything() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();
        t.observe(0, golems(1, at(0, 0, 0)), id -> true);
        t.observe(10, golems(1, at(1, 0, 0)), id -> false);
        t.observe(20, golems(), id -> false);
        assertEquals(1, t.completedSamples());

        t.reset();
        assertEquals(0, t.completedSamples());
        assertEquals(0, t.trackedCount());
        assertEquals(0, t.discardedCount());
    }

    /** 序列化再反序列化后，平均值应基本不变（只存总和与次数）。 */
    @Test
    void persistedSumsRoundTrip() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();
        t.observe(0, golems(1, at(0, 0, 0)), id -> true);
        t.observe(60, golems(1, at(1, 0, 0)), id -> false);
        t.observe(400, golems(), id -> false);

        double leaveAvg = t.averageTicksToLeaveDetection();
        double aliveAvg = t.averageTicksAlive();

        GolemLifecycleTracker restored = new GolemLifecycleTracker();
        restored.restore(t.ticksToLeaveSum(), t.ticksToLeaveCount(),
                t.aliveSum(), t.aliveCount(), t.discardedCount());

        assertEquals(leaveAvg, restored.averageTicksToLeaveDetection(), 1.0,
                "恢复后平均值应基本一致");
        assertEquals(aliveAvg, restored.averageTicksAlive(), 1.0);
    }

    /**
     * 样本数恢复后也要对上 —— 界面会显示样本数，
     * 如果恢复后变成 0 会让人以为"没有数据"。
     */
    @Test
    void restoredSampleCountMatches() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();
        t.observe(0, golems(1, at(0, 0, 0), 2, at(0, 0, 0)), id -> true);
        t.observe(20, golems(1, at(1, 0, 0), 2, at(1, 0, 0)), id -> false);
        t.observe(100, golems(), id -> false);
        assertEquals(2, t.completedSamples());

        GolemLifecycleTracker restored = new GolemLifecycleTracker();
        restored.restore(t.ticksToLeaveSum(), t.ticksToLeaveCount(),
                t.aliveSum(), t.aliveCount(), t.discardedCount());
        assertEquals(2, restored.completedSamples());
    }

    @Test
    void zeroTicksIsValidSample() {
        // 同一 tick 内离开又消失 —— 理论上罕见，但不应被当成"无效"
        GolemLifecycleTracker t = new GolemLifecycleTracker();
        t.observe(0, golems(1, at(0, 0, 0)), id -> false);
        t.observe(0, golems(), id -> false);
        assertEquals(1, t.completedSamples());
        assertEquals(0.0, t.averageTicksToLeaveDetection(), 0.001);
        assertEquals(0.0, t.averageTicksAlive(), 0.001);
    }

    @Test
    void discardedIsCountedNotAveraged() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();
        // 正常一只
        t.observe(0, golems(1, at(0, 0, 0)), id -> true);
        t.observe(20, golems(1, at(1, 0, 0)), id -> false);
        t.observe(100, golems(), id -> false);
        // 三只没离开就消失
        t.observe(110, golems(2, at(0, 0, 0), 3, at(0, 0, 0), 4, at(0, 0, 0)), id -> true);
        t.observe(200, golems(), id -> true);

        assertEquals(1, t.completedSamples());
        assertEquals(3, t.discardedCount());
        // 平均值只受那 1 只正常的影响
        assertEquals(20.0, t.averageTicksToLeaveDetection(), 0.001);
    }

    @Test
    void emptyObserveDoesNotThrow() {
        GolemLifecycleTracker t = new GolemLifecycleTracker();
        t.observe(0, golems(), id -> false);
        assertEquals(0, t.trackedCount());
        assertTrue(t.averageTicksAlive() < 0);
    }

    // ==================================================================
    // 铁锭估算：累计 vs 每小时
    // ==================================================================

    /**
     * 回归钉子（用户疑问："统计到245铁傀儡，预估铁锭只有450个左右"）。
     *
     * <p>245 只按期望应是 <b>980</b> 个铁锭。
     * 450 是「每小时」数值 —— 两者单位不同，不能直接对比。</p>
     *
     * <p>这个测试同时验证两个 API：
     * {@code ironIngotsTotal()} 是累计，{@code ironIngotsPerHour()} 是速率。</p>
     */
    @Test
    void ironIngotTotalMatchesGolemCount() {
        com.heishandashu.ironfarm.engine.stats.StatsRecorder s =
                new com.heishandashu.ironfarm.engine.stats.StatsRecorder();
        s.start(0, 0);
        for (int i = 0; i < 245; i++) {
            s.recordSpawn(i * 100L, 1);
        }
        assertEquals(245 * 4.0, s.ironIngotsTotal(), 0.001,
                "245 只应有 980 个铁锭（每只期望 4 个）");
    }

    /**
     * 累计值与时间无关，只与刷出数量有关。
     *
     * <p>这是「累计」和「每小时」的核心区别：
     * 累计不随 nowGameTime 变化，速率会。</p>
     */
    @Test
    void ironIngotTotalDoesNotDependOnTime() {
        com.heishandashu.ironfarm.engine.stats.StatsRecorder s =
                new com.heishandashu.ironfarm.engine.stats.StatsRecorder();
        s.start(0, 0);
        for (int i = 0; i < 10; i++) {
            s.recordSpawn(i * 100L, 1);
        }
        double atStart = s.ironIngotsTotal();
        // 不管"现在"是多久之后，累计都应该一样
        assertEquals(atStart, s.ironIngotsTotal(), 0.001);
        assertEquals(40.0, atStart, 0.001);
    }

    /**
     * 每小时数值 = 累计 / 已过小时数。
     *
     * <p>245 只跑了 2 小时 → 122.5 只/小时 → 490 个/小时。</p>
     */
    @Test
    void ironIngotsPerHourIsTotalDividedByHours() {
        com.heishandashu.ironfarm.engine.stats.StatsRecorder s =
                new com.heishandashu.ironfarm.engine.stats.StatsRecorder();
        s.start(0, 0);
        for (int i = 0; i < 245; i++) {
            s.recordSpawn(i * 100L, 1);
        }
        // 2 小时 = 144000 tick
        double perHour = s.ironIngotsPerHour(144000);
        assertEquals(245 * 4.0 / 2.0, perHour, 0.001,
                "2 小时刷 245 只 → 每小时 490 个铁锭");
        // 关键关系：累计 = 每小时 × 小时数
        assertEquals(s.ironIngotsTotal(), perHour * 2.0, 0.001);
    }

    /** 单只掉落期望必须是 4.0（战利品表 uniform(3,5) 的均值）。 */
    @Test
    void ingotPerGolemConstantIsFour() {
        assertEquals(4.0,
                com.heishandashu.ironfarm.engine.stats.StatsRecorder.IRON_INGOTS_PER_GOLEM,
                0.0001,
                "战利品表是 uniform(min=3, max=5)，期望 (3+5)/2 = 4");
    }
}