package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.eval.ConditionEngine;
import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.ReasonCodes;
import com.heishandashu.ironfarm.engine.rule.RuleResult;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「数据不可得」必须是 UNKNOWN，不能是 FAIL 或 PASS。
 *
 * <p>这组测试针对的是一个真实且危险的误报：多人游戏里客户端拿不到
 * 服务端 {@code Brain} 的 {@code LAST_SLEPT} / {@code GOLEM_DETECTED_RECENTLY}。
 * 如果把它们当成"从没睡过"或"没看到傀儡"，界面上就会出现</p>
 * <ul>
 *   <li>满屏假红叉（村民明明能刷），或者</li>
 *   <li>满屏假绿勾（其实有傀儡一直在锁冷却），</li>
 * </ul>
 * <p>两种都会让玩家去改一台本来正常的机器。</p>
 */
class UnknownDataTest {

    private static final long NOW = 1_000_000L;

    private final ConditionEngine engine = new ConditionEngine(VersionProfile.V1_21_1);

    private SpawnScanResult okSpawn(BlockPos3 c) {
        List<BlockPos3> pts = new ArrayList<BlockPos3>();
        pts.add(c.offset(0, 1, 0));
        return new SpawnScanResult(c, 8, 6, true, pts, null);
    }

    private VillagerReport evalOne(VillagerSnapshot v) {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        return engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0);
    }

    // ------------------------------------------------------------------
    // 睡眠数据不可得
    // ------------------------------------------------------------------

    @Test
    void unknownSleepDataGivesUnknownNotFail() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0);
        VillagerReport r = evalOne(v);
        assertEquals(RuleResult.Status.UNKNOWN, r.get(Condition.SLEPT).status,
                "拿不到 LAST_SLEPT 时应为 UNKNOWN，不能是 FAIL");
        assertEquals(ReasonCodes.DATA_MISSING, r.get(Condition.SLEPT).reasonCode);
    }

    @Test
    void unknownSleepDataYieldsUnknownIconNotCross() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0);
        VillagerReport r = evalOne(v);
        assertEquals(VillagerReport.Icon.UNKNOWN, r.conditionIcon(),
                "数据不可得时必须画问号，不能画红叉");
    }

    /**
     * 失败归因的两条硬性约束。
     *
     * <p>这里刻意不写成「data 不可得时 primaryFailure 必须是 null」——
     * 那是错的。举例：多人游戏里睡眠数据不可得，但这只村民旁边**根本没有僵尸**，
     * 那么"没在恐慌"是一个**确定成立且可操作**的结论（玩家该去放只僵尸）。
     * 把它藏起来反而没用。</p>
     *
     * <p>真正必须守住的是：</p>
     * <ol>
     *   <li>归因出来的结果**绝不能是 UNKNOWN 状态**（否则统计页会把"不知道"
     *       当成一条失败原因记下来）；</li>
     *   <li>归因**绝不能是 data_missing** 这类"不是问题本身"的代码。</li>
     * </ol>
     */
    @Test
    void failureAttributionIsNeverAnUnknownOrDataMissingEntry() {
        // 各种"部分数据不可得"的组合都过一遍
        VillagerSnapshot[] cases = {
                VillagerSnapshot.unknownData(1, 0, 64, 0),
                VillagerSnapshot.unknownData(2, 0, 64, 0).withPanic(true),
                VillagerSnapshot.healthy(3, 0, 64, 0, NOW).withGolemMemoryKnown(false),
                VillagerSnapshot.healthy(4, 0, 64, 0, NOW).withGolemMemoryKnown(false).withPanic(true),
                VillagerSnapshot.healthy(5, 0, 64, 0, NOW).withSleepDataKnown(false),
        };
        for (VillagerSnapshot v : cases) {
            VillagerReport r = evalOne(v);
            com.heishandashu.ironfarm.engine.rule.RuleResult f = r.primaryFailure();
            if (f == null) {
                continue;
            }
            assertFalse(f.isUnknown(),
                    "归因不能是 UNKNOWN 状态: #" + v.entityId + " -> " + f);
            assertFalse(ReasonCodes.DATA_MISSING.equals(f.reasonCode),
                    "归因不能是 data_missing: #" + v.entityId);
        }
    }

    /** 数据不可得 + 确实没在恐慌 → 报"没在恐慌"是合法且有用的结论。 */
    @Test
    void unknownDataWithNoPanicReportsNotPanicking() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0);
        assertEquals(ReasonCodes.NOT_PANICKING, evalOne(v).primaryFailureCode());
    }

    // ------------------------------------------------------------------
    // 傀儡记忆不可得
    // ------------------------------------------------------------------

    @Test
    void unknownGolemMemoryGivesUnknownNotPass() {
        VillagerSnapshot v = VillagerSnapshot.healthy(1, 0, 64, 0, NOW)
                .withGolemMemoryKnown(false);
        VillagerReport r = evalOne(v);
        assertEquals(RuleResult.Status.UNKNOWN, r.get(Condition.NO_GOLEM_DETECTED).status,
                "拿不到傀儡记忆时应为 UNKNOWN，不能当成'没看到'");
    }

    @Test
    void unknownGolemMemoryYieldsUnknownIcon() {
        VillagerSnapshot v = VillagerSnapshot.healthy(1, 0, 64, 0, NOW)
                .withGolemMemoryKnown(false);
        assertEquals(VillagerReport.Icon.UNKNOWN, evalOne(v).conditionIcon());
    }

    // ------------------------------------------------------------------
    // 对照：数据可得时行为不变
    // ------------------------------------------------------------------

    @Test
    void knownDataStillGivesRealVerdicts() {
        // 真·从没睡过 → 红叉
        VillagerSnapshot never = VillagerSnapshot.of(1, 0, 64, 0);
        VillagerReport r1 = evalOne(never);
        assertEquals(RuleResult.Status.FAIL, r1.get(Condition.SLEPT).status);
        assertEquals(VillagerReport.Icon.CROSS, r1.conditionIcon());

        // 全都满足 → 绿勾
        VillagerSnapshot good = VillagerSnapshot.healthy(2, 0, 64, 0, NOW);
        VillagerReport r2 = evalOne(good);
        assertEquals(RuleResult.Status.PASS, r2.get(Condition.SLEPT).status);
        assertEquals(VillagerReport.Icon.CHECK, r2.conditionIcon());
    }

    // ------------------------------------------------------------------
    // withXxx 不能把标志位弄丢
    // ------------------------------------------------------------------

    @Test
    void withMethodsPreserveUnknownFlags() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0);

        assertFalse(v.withPanic(true).sleepDataKnown, "withPanic 丢了 sleepDataKnown");
        assertFalse(v.withGossip(true).sleepDataKnown, "withGossip 丢了 sleepDataKnown");
        assertFalse(v.withSlept(NOW - 100).sleepDataKnown, "withSlept 丢了 sleepDataKnown");
        assertFalse(v.withAdult(true).sleepDataKnown, "withAdult 丢了 sleepDataKnown");
        assertFalse(v.withBed(new BlockPos3(0, 64, 0)).sleepDataKnown, "withBed 丢了 sleepDataKnown");
        assertFalse(v.at(5, 64, 5).sleepDataKnown, "at 丢了 sleepDataKnown");

        assertFalse(v.withPanic(true).golemMemoryKnown, "withPanic 丢了 golemMemoryKnown");
        assertFalse(v.withGolemDetected(true).golemMemoryKnown, "withGolemDetected 丢了 golemMemoryKnown");
        assertFalse(v.at(5, 64, 5).golemMemoryKnown, "at 丢了 golemMemoryKnown");
    }

    /**
     * 这条是回归钉子：即使调用方用 withSlept 写入一个"看起来刚睡过"的时间，
     * 只要 sleepDataKnown=false，就必须还是 UNKNOWN。
     * 防止"补数据"的代码顺手把不可得变成可得。
     */
    @Test
    void writingSleepTimeDoesNotMakeDataKnown() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0)
                .withSlept(NOW - 100);
        assertEquals(RuleResult.Status.UNKNOWN, evalOne(v).get(Condition.SLEPT).status);
    }

    // ------------------------------------------------------------------
    // 喇叭图标的三态
    // ------------------------------------------------------------------

    @Test
    void gossipIconIsUnknownWhenDataMissing() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0).withGossip(true);
        assertEquals(VillagerReport.Icon.UNKNOWN, evalOne(v).gossipIcon(),
                "硬条件无法判定时，喇叭也不该给红叉");
    }

    @Test
    void gossipIconIsCheckWhenFullyReady() {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        for (int i = 0; i < 6; i++) {
            all.add(VillagerSnapshot.healthy(i + 1, i, 64, 0, NOW)
                    .withPanic(false).withGossip(i == 0));
        }
        VillagerReport r = engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertTrue(r.gossipReady());
        assertEquals(VillagerReport.Icon.CHECK, r.gossipIcon());
    }

    @Test
    void gossipIconIsCrossWhenDefinitelyShort() {
        // 数据齐全，但邻居只有 1 个 → 确定的红叉
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(VillagerSnapshot.healthy(1, 0, 64, 0, NOW).withGossip(true));
        all.add(VillagerSnapshot.healthy(2, 1, 64, 0, NOW));
        VillagerReport r = engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertFalse(r.gossipReady());
        assertEquals(VillagerReport.Icon.CROSS, r.gossipIcon());
    }
}
