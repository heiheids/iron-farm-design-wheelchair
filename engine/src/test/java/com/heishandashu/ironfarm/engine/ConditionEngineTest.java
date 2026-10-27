package com.heishandashu.ironfarm.engine.model;

import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.ReasonCodes;
import com.heishandashu.ironfarm.engine.rule.RuleResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定引擎测试：**逐条对照 1.21.1 真实字节码**。
 *
 * <p>每条断言都标注了它对应原版哪一段逻辑。改坏任何一条，
 * 就说明与游戏实际行为脱节了。</p>
 */
class ConditionEngineTest {

    private static final long NOW = 1_000_000L;

    private final com.heishandashu.ironfarm.engine.eval.ConditionEngine engine =
            new com.heishandashu.ironfarm.engine.eval.ConditionEngine(VersionProfile.V1_21_1);

    private com.heishandashu.ironfarm.engine.spawn.SpawnScanResult okSpawn(BlockPos3 center) {
        List<BlockPos3> pts = new ArrayList<BlockPos3>();
        pts.add(center.offset(0, 1, 0));
        return new com.heishandashu.ironfarm.engine.spawn.SpawnScanResult(center, 8, 6, true, pts, null);
    }

    private com.heishandashu.ironfarm.engine.spawn.SpawnScanResult noSpawn(BlockPos3 center) {
        return new com.heishandashu.ironfarm.engine.spawn.SpawnScanResult(center, 8, 6, true, null, null);
    }

    /** 造一群 n 只全部满足个体条件、且都在恐慌的村民（互相距离 1 格）。 */
    private List<VillagerSnapshot> panicGroup(int n) {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        for (int i = 0; i < n; i++) {
            all.add(VillagerSnapshot.healthy(i + 1, i, 64, 0, NOW));
        }
        return all;
    }

    // ==================================================================
    // 原版 golemSpawnConditionsMet：睡眠窗口
    // ==================================================================

    @Test
    void neverSleptFailsBecauseNoLastSleptMemory() {
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0); // 从未睡过
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0);
        assertEquals(RuleResult.Status.FAIL, r.get(Condition.SLEPT).status);
        assertEquals(ReasonCodes.NEVER_SLEPT, r.get(Condition.SLEPT).reasonCode);
        assertFalse(r.hardConditionsMet());
    }

    @Test
    void sleptAt23999TicksPassesAndAt24000Fails() {
        // 原版：now - lastSlept < 24000 才算通过（严格小于）
        // ⚠ 用 withSlept 指定精确的 lastSlept，不要用 healthy(..., now - 23999) ——
        //   healthy 内部还会再减 100，实测会变成 24099，把边界测错。
        VillagerSnapshot inside = VillagerSnapshot.healthy(1, 0, 64, 0, NOW)
                .withSlept(NOW - 23999);
        List<VillagerSnapshot> a = new ArrayList<VillagerSnapshot>();
        a.add(inside);
        assertTrue(engine.evaluateAll(a, NOW, okSpawn(inside.pos)).get(0)
                .get(Condition.SLEPT).isPass(), "23999 tick 应通过");

        VillagerSnapshot edge = VillagerSnapshot.healthy(1, 0, 64, 0, NOW)
                .withSlept(NOW - 24000);
        List<VillagerSnapshot> b = new ArrayList<VillagerSnapshot>();
        b.add(edge);
        RuleResult rr = engine.evaluateAll(b, NOW, okSpawn(edge.pos)).get(0).get(Condition.SLEPT);
        assertTrue(rr.isFail(), "24000 tick 应失败（严格小于）");
        assertEquals(ReasonCodes.SLEEP_EXPIRED, rr.reasonCode);
    }

    @Test
    void futureSleptTimeIsTreatedAsJustSlept() {
        // 换维度/读档可能让 lastSlept 跑到未来，不能因此判失败
        VillagerSnapshot v = VillagerSnapshot.healthy(1, 0, 64, 0, NOW + 5000);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        assertTrue(engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0)
                .get(Condition.SLEPT).isPass());
    }

    // ==================================================================
    // 原版 wantsToSpawnGolem：GOLEM_DETECTED_RECENTLY 记忆
    // ==================================================================

    @Test
    void golemDetectedMemoryBlocksSpawn() {
        // 原版：brain.hasMemoryValue(GOLEM_DETECTED_RECENTLY) → 直接 false
        VillagerSnapshot v = VillagerSnapshot.healthy(1, 0, 64, 0, NOW).withGolemDetected(true);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0);
        assertEquals(RuleResult.Status.FAIL, r.get(Condition.NO_GOLEM_DETECTED).status);
        assertEquals(ReasonCodes.GOLEM_DETECTED, r.get(Condition.NO_GOLEM_DETECTED).reasonCode);
        assertFalse(r.hardConditionsMet());
    }

    @Test
    void noGolemMemoryMeansPass() {
        VillagerSnapshot v = VillagerSnapshot.healthy(1, 0, 64, 0, NOW);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        assertTrue(engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0)
                .get(Condition.NO_GOLEM_DETECTED).isPass());
    }

    // ==================================================================
    // 成年：原版靠婴儿没有 LAST_SLEPT 自然失败
    // ==================================================================

    @Test
    void babyVillagerFails() {
        VillagerSnapshot baby = VillagerSnapshot.of(1, 0, 64, 0).withAdult(false);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(baby);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(baby.pos)).get(0);
        assertFalse(r.hardConditionsMet());
    }

    // ==================================================================
    // 原版 spawnGolemIfNeeded：邻居统计
    // ==================================================================

    @Test
    void neighbourCountExcludesSelf() {
        // 关键事实：原版 getEntitiesOfClass 不返回自己，
        // 所以"一个人合格"时 qualified 计数是 0，不是 1。
        List<VillagerSnapshot> all = panicGroup(1);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(0, r.panicParticipants(), "自己不算邻居");
        assertEquals(ReasonCodes.PANIC_GROUP_TOO_SMALL, r.get(Condition.PANIC_GROUP).reasonCode);
    }

    @Test
    void threeOtherVillagersMeetPanicThreshold() {
        // 自己 + 3 个邻居 = 邻居计数 3 >= 门槛 3
        List<VillagerSnapshot> all = panicGroup(4);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(3, r.panicParticipants());
        assertTrue(r.get(Condition.PANIC_GROUP).isPass());
    }

    @Test
    void participantsDoNotNeedToBePanicking() {
        // 这是最容易搞错的一条：原版只要求邻居 wantsToSpawnGolem，
        // 不要求邻居也在恐慌。
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(VillagerSnapshot.healthy(1, 0, 64, 0, NOW)); // 发起者，在恐慌
        all.add(VillagerSnapshot.healthy(2, 1, 64, 0, NOW).withPanic(false));
        all.add(VillagerSnapshot.healthy(3, 2, 64, 0, NOW).withPanic(false));
        all.add(VillagerSnapshot.healthy(4, 3, 64, 0, NOW).withPanic(false));

        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(3, r.panicParticipants(), "邻居不恐慌也应计入");
        assertTrue(r.get(Condition.PANIC_GROUP).isPass());
    }

    @Test
    void participantOutside10BlocksIsNotCounted() {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(VillagerSnapshot.healthy(1, 0, 64, 0, NOW));
        all.add(VillagerSnapshot.healthy(2, 11, 64, 0, NOW)); // 超出 10 格
        all.add(VillagerSnapshot.healthy(3, 11, 64, 0, NOW));
        all.add(VillagerSnapshot.healthy(4, 11, 64, 0, NOW));

        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(0, r.panicParticipants());
    }

    @Test
    void participantAtExactly10BlocksIsCounted() {
        // 原版是 AABB.inflate(10) 后 getEntitiesOfClass，
        // 边界上的实体会被包含（AABB 相交判定）。
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(VillagerSnapshot.healthy(1, 0, 64, 0, NOW));
        all.add(VillagerSnapshot.healthy(2, 10, 64, 0, NOW).withPanic(false));
        all.add(VillagerSnapshot.healthy(3, 10, 64, 0, NOW).withPanic(false));
        all.add(VillagerSnapshot.healthy(4, 10, 64, 0, NOW).withPanic(false));

        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(3, r.panicParticipants());
    }

    @Test
    void unqualifiedNeighbourIsNotCounted() {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(VillagerSnapshot.healthy(1, 0, 64, 0, NOW));
        all.add(VillagerSnapshot.healthy(2, 1, 64, 0, NOW));
        all.add(VillagerSnapshot.healthy(3, 2, 64, 0, NOW));
        // 第 4 只没睡过 → 不合格
        all.add(VillagerSnapshot.of(4, 3, 64, 0));

        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(2, r.panicParticipants(), "没睡过的邻居不算");
    }

    @Test
    void participantLimitCapsAt5() {
        // 原版 Stream.limit(5)
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        for (int i = 0; i < 10; i++) {
            all.add(VillagerSnapshot.healthy(i + 1, i, 64, 0, NOW).withPanic(false));
        }
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(5, r.panicParticipants(), "上限 5");
        assertEquals(5, r.gossipParticipants());
    }

    // ==================================================================
    // 闲聊路径：门槛 5，且上限也是 5
    // ==================================================================

    @Test
    void gossipNeeds5NeighboursWhichEqualsTheLimit() {
        // 5 个邻居 → 通过
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        for (int i = 0; i < 6; i++) {
            all.add(VillagerSnapshot.healthy(i + 1, i, 64, 0, NOW)
                    .withPanic(false).withGossip(i == 0));
        }
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(5, r.gossipParticipants());
        assertTrue(r.get(Condition.GOSSIP_GROUP).isPass());
        assertTrue(r.gossipReady());
    }

    @Test
    void gossipFailsWithOnly4Neighbours() {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        for (int i = 0; i < 5; i++) {
            all.add(VillagerSnapshot.healthy(i + 1, i, 64, 0, NOW)
                    .withPanic(false).withGossip(i == 0));
        }
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertEquals(4, r.gossipParticipants());
        assertTrue(r.get(Condition.GOSSIP_GROUP).isFail());
        assertEquals(ReasonCodes.GOSSIP_GROUP_TOO_SMALL, r.get(Condition.GOSSIP_GROUP).reasonCode);
    }

    // ==================================================================
    // 恐慌状态：只对发起者有要求
    // ==================================================================

    @Test
    void notPanickingIsReportedButGroupStillPasses() {
        List<VillagerSnapshot> all = panicGroup(4);
        VillagerSnapshot notPanicking = all.get(0).withPanic(false);
        all.set(0, notPanicking);

        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(notPanicking.pos)).get(0);
        assertFalse(r.panicking(), "发起者没在恐慌");
        assertTrue(r.get(Condition.PANIC_GROUP).isPass(), "但邻居人数够");
        assertFalse(r.panicFarmReady(), "发起者不恐慌 → 恐慌路径不成立");
    }

    // ==================================================================
    // 生成点
    // ==================================================================

    @Test
    void noSpawnPointFails() {
        List<VillagerSnapshot> all = panicGroup(4);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, noSpawn(all.get(0).pos)).get(0);
        assertEquals(ReasonCodes.NO_SPAWN_POINT, r.get(Condition.SPAWN_POINT).reasonCode);
        assertFalse(r.panicFarmReady());
    }

    @Test
    void nullSpawnScanIsUnknownNotFail() {
        List<VillagerSnapshot> all = panicGroup(4);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, null).get(0);
        assertTrue(r.get(Condition.SPAWN_POINT).isUnknown());
        assertNull(r.primaryFailureCode(), "无法判定不应产出失败原因");
    }

    // ==================================================================
    // 失败归因
    // ==================================================================

    @Test
    void healthyPanicFarmHasNoFailureReason() {
        // 这是本轮修掉的 bug：恐吓机正常工作时不应报"没在闲聊"
        List<VillagerSnapshot> all = panicGroup(4);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
        assertTrue(r.hardConditionsMet());
        assertTrue(r.panicking());
        assertTrue(r.panicFarmReady());
        assertNull(r.primaryFailureCode(), "正常工作的恐吓机不该有失败原因");
    }

    @Test
    void primaryFailurePrefersMostFundamental() {
        // 既没睡过、又没恐慌、又没有生成点 → 应归因到 never_slept
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, noSpawn(v.pos)).get(0);
        assertEquals(ReasonCodes.NEVER_SLEPT, r.primaryFailureCode());
    }

    @Test
    void primaryFailureReportsPanicWhenNeitherPathActive() {
        VillagerSnapshot v = VillagerSnapshot.healthy(1, 0, 64, 0, NOW)
                .withPanic(false).withGossip(false);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        com.heishandashu.ironfarm.engine.eval.VillagerReport r =
                engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0);
        assertEquals(ReasonCodes.NOT_PANICKING, r.primaryFailureCode());
    }

    @Test
    void reportCarriesValuesForDisplay() {
        VillagerSnapshot v = VillagerSnapshot.healthy(1, 0, 64, 0, NOW).withSlept(NOW - 12000);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        RuleResult slept = engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0).get(Condition.SLEPT);
        assertEquals(12000.0, slept.value, 0.0001);
        assertEquals(24000.0, slept.threshold, 0.0001);
    }

    @Test
    void emptyInputGivesEmptyOutput() {
        List<com.heishandashu.ironfarm.engine.eval.VillagerReport> reports =
                engine.evaluateAll(new ArrayList<VillagerSnapshot>(), NOW, null);
        assertNotNull(reports);
        assertTrue(reports.isEmpty());
    }

    @Test
    void wantsToSpawnGolemMatchesIndividualConditions() {
        VillagerSnapshot good = VillagerSnapshot.healthy(1, 0, 64, 0, NOW);
        assertTrue(engine.wantsToSpawnGolem(good, NOW));

        assertFalse(engine.wantsToSpawnGolem(good.withSlept(NOW - 24000), NOW));
        assertFalse(engine.wantsToSpawnGolem(good.withGolemDetected(true), NOW));
        assertFalse(engine.wantsToSpawnGolem(good.withAdult(false), NOW));
    }
}
