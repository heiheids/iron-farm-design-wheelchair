package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.RuleResult;
import com.heishandashu.ironfarm.engine.stats.FailureRecord;
import com.heishandashu.ironfarm.engine.stats.StatsRecorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计记录器测试。
 */
class StatsRecorderTest {

    private VillagerSnapshot v(int id) {
        return VillagerSnapshot.of(id, 0, 64, 0);
    }

    @Test
    void recordsSpawnCount() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 1000);
        s.recordSpawn(100, 1);
        s.recordSpawn(200, 2);
        assertEquals(2, s.golemsSpawned());
        assertEquals(2, s.summonAttempts());
        assertTrue(s.isStarted());
    }

    @Test
    void recordsFailureAndAggregates() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 1000);
        RuleResult f = RuleResult.fail(Condition.SLEPT, "never_slept", 0, 24000);
        s.recordFailure(100, v(1), f);
        s.recordFailure(200, v(1), f);
        s.recordFailure(300, v(2), f);

        assertEquals(3, s.summonAttempts());
        assertEquals(0, s.golemsSpawned());
        List<FailureRecord> list = s.failuresByCount();
        assertEquals(2, list.size(), "应有 (1,never_slept) 与 (2,never_slept) 两条");
        // 村民 1 出现 2 次，应排在前面
        assertEquals(1, list.get(0).villagerEntityId);
        assertEquals(2, list.get(0).count);
        assertEquals(1, list.get(1).count);
    }

    @Test
    void failuresByReasonMergesVillagers() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 1000);
        s.recordFailure(100, v(1), RuleResult.fail(Condition.SLEPT, "never_slept"));
        s.recordFailure(200, v(2), RuleResult.fail(Condition.SLEPT, "never_slept"));
        s.recordFailure(300, v(3), RuleResult.fail(Condition.PANICKING, "not_panicking"));

        java.util.Map<String, Long> byReason = s.failuresByReason();
        assertEquals(2L, byReason.get("never_slept").longValue());
        assertEquals(1L, byReason.get("not_panicking").longValue());
    }

    @Test
    void worstVillagersSortedByFailureCount() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 1000);
        for (int i = 0; i < 5; i++) {
            s.recordFailure(i, v(7), RuleResult.fail(Condition.SLEPT, "never_slept"));
        }
        for (int i = 0; i < 2; i++) {
            s.recordFailure(i, v(3), RuleResult.fail(Condition.PANICKING, "not_panicking"));
        }
        List<java.util.Map.Entry<Integer, Long>> worst = s.worstVillagers(10);
        assertEquals(2, worst.size());
        assertEquals(7, worst.get(0).getKey().intValue());
        assertEquals(5L, worst.get(0).getValue().longValue());
    }

    @Test
    void detailRecordsEveryFailingCondition() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 1000);
        List<RuleResult> fails = new ArrayList<RuleResult>();
        fails.add(RuleResult.fail(Condition.SLEPT, "never_slept"));
        fails.add(RuleResult.fail(Condition.PANICKING, "not_panicking"));
        s.recordFailureDetail(100, v(1), fails);

        java.util.Map<String, Long> byReason = s.failuresByReason();
        assertEquals(1L, byReason.get("never_slept").longValue());
        assertEquals(1L, byReason.get("not_panicking").longValue());
    }

    /**
     * 回归钉子：记录失败**不应该**增加「判定次数」。
     *
     * <p>第一版在 recordFailureDetail 里写了 {@code summonAttempts++}，
     * 于是"判定次数"变成了"客户端扫描了多少次"
     * （扫描是每 10 tick 一次，而原版真实召唤是每 100 tick 一次）。
     *
     * <p>实测后果：3 小时统计显示 11838 次判定、只刷出 108 只，
     * 看起来像"110 次尝试才成功 1 次"，而真实上限只有约 2228 次尝试。
     * 这个数字完全无法用来判断机器效率。</p>
     *
     * <p>现在 summonAttempts 只由 {@link StatsRecorder#recordAttempt(long)} 增加。</p>
     */
    @Test
    void failureDetailDoesNotCountAsAttempt() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 1000);
        List<RuleResult> fails = new ArrayList<RuleResult>();
        fails.add(RuleResult.fail(Condition.SLEPT, "never_slept"));
        s.recordFailureDetail(100, v(1), fails);
        s.recordFailureDetail(110, v(1), fails);

        assertEquals(0, s.summonAttempts(),
                "记录失败不能增加判定次数 —— 那会把扫描次数当成召唤次数");

        // 只有真实的召唤尝试才增加
        s.recordAttempt(200);
        assertEquals(1, s.summonAttempts());
    }

    @Test
    void rateIsComputedFromGameTime() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 1000);
        // 1 小时 = 72000 tick 内刷 10 只
        for (int i = 0; i < 10; i++) {
            s.recordSpawn(i * 1000L, 1);
        }
        assertEquals(10.0, s.golemsPerHour(72000), 0.001);
        assertEquals(20.0, s.golemsPerHour(36000), 0.001);
    }

    @Test
    void rateIsZeroBeforeStart() {
        StatsRecorder s = new StatsRecorder();
        assertEquals(0.0, s.golemsPerHour(1000), 0.0001);
        assertFalse(s.isStarted());
    }

    @Test
    void recentEventsCappedAtLimit() {
        StatsRecorder s = new StatsRecorder(5);
        s.start(0, 1000);
        for (int i = 0; i < 20; i++) {
            s.recordSpawn(i, 1);
        }
        assertEquals(5, s.recentEvents().size());
        // 最新的应该是最后一次
        assertEquals(19, s.recentEvents().get(4).gameTime);
    }

    /**
     * 清空必须连开始时间一起清。
     *
     * <p><b>这条测试原本断言的是错误行为。</b>它原来叫
     * {@code resetClearsCountersButKeepsStartTime}，
     * 断言「清空后 startGameTime 仍然是 123」——
     * 我当初以为「保持开始时间是合理的」。事实证明这是 bug：</p>
     *
     * <p>用户两次截图对比：刷出数从 108 变成 94（说明清空过），
     * 但「开始记录」始终是「第 1 天 06:31」。更严重的是
     * 「平均产量」的分母仍是旧起点，清空后 94 只按很长的时间算，
     * 产量被系统性低估 —— 这正是用户反馈的「效率对不上」。</p>
     *
     * <p>现在改成断言正确行为：清空后回到未开始状态。</p>
     */
    @Test
    void resetClearsCountersAndStartTime() {
        StatsRecorder s = new StatsRecorder();
        s.start(123, 1000);
        s.recordSpawn(200, 1);
        s.reset();
        assertEquals(0, s.golemsSpawned());
        assertEquals(0, s.summonAttempts());
        assertEquals(-1, s.startGameTime(),
                "清空后开始时间必须是 -1，让下一次记录重新起算");
        assertEquals(-1, s.startWallClock());
        assertFalse(s.isStarted());
    }

    @Test
    void autoStartOnFirstRecord() {
        StatsRecorder s = new StatsRecorder();
        assertFalse(s.isStarted());
        s.recordSpawn(500, 1);
        assertTrue(s.isStarted());
        assertEquals(500, s.startGameTime());
    }
}
