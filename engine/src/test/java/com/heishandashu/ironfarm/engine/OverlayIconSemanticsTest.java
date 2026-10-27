package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.eval.ConditionEngine;
import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 头顶三个图标的语义（用户逐条确认的**需求定义**）。
 *
 * <pre>
 *   对钩 = 成年 + 睡过觉 + 30 秒内没识别到铁傀儡 → 绿
 *   骷髅 = 附近有僵尸等威胁正在恐吓            → 绿
 *   喇叭 = 闲聊刷铁傀儡的前提满足              → 绿
 * </pre>
 *
 * <p><b>为什么这组测试重要：</b>我一度把对钩改成
 * {@code panicFarmReady() || gossipFarmReady()}（想解决"头顶与点进去不同步"），
 * 结果对钩在"生成点不合法"时变红，而状态页明明写着"睡过/没有傀儡"都满足 ——
 * 反而制造了不一致。</p>
 *
 * <p>对钩**只**代表个体条件。整台机器能不能刷是状态页"总判定"的事，
 * 不是头顶对钩的事。这两者必须分开。</p>
 */
class OverlayIconSemanticsTest {

    private static final long NOW = 1_000_000L;
    private final ConditionEngine engine = new ConditionEngine(VersionProfile.V1_21_1);

    private SpawnScanResult okSpawn(BlockPos3 c) {
        List<BlockPos3> pts = new ArrayList<BlockPos3>();
        pts.add(c.offset(0, 1, 0));
        return new SpawnScanResult(c, 8, 6, true, pts, null);
    }

    private SpawnScanResult noSpawn(BlockPos3 c) {
        return new SpawnScanResult(c, 8, 6, true,
                new ArrayList<BlockPos3>(), null);
    }

    private VillagerReport evalOne(VillagerSnapshot v, SpawnScanResult scan) {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        return engine.evaluateAll(all, NOW, scan).get(0);
    }

    // ==================================================================
    // 对钩：只看个体条件
    // ==================================================================

    /**
     * 睡过 + 没见到傀儡 → 对钩必须是绿（CHECK）。
     *
     * <p>即使生成点不合法、即使没有恐慌 —— 对钩依然应该是绿的，
     * 因为它只回答"这个村民自己具备条件吗"。</p>
     */
    @Test
    void checkIconIgnoresSpawnPoint() {
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0)
                .withSlept(NOW - 100L)
                .withGolemDetected(false);
        VillagerReport r = evalOne(v, noSpawn(v.pos));

        assertEquals(VillagerReport.Icon.CHECK, r.conditionIcon(),
                "对钩只代表个体条件，生成点不合法不该让它变红");
    }

    /**
     * 没睡过 → 对钩红（CROSS）。
     */
    @Test
    void checkIconRedWhenNeverSlept() {
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0)
                .withSlept(-1L)
                .withGolemDetected(false);
        VillagerReport r = evalOne(v, okSpawn(v.pos));

        assertEquals(VillagerReport.Icon.CROSS, r.conditionIcon(),
                "没睡过时应为红叉");
    }

    /**
     * 30 秒内见到傀儡 → 对钩红。
     */
    @Test
    void checkIconRedWhenGolemSeenRecently() {
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0)
                .withSlept(NOW - 100L)
                .withGolemDetected(true);
        VillagerReport r = evalOne(v, okSpawn(v.pos));

        assertEquals(VillagerReport.Icon.CROSS, r.conditionIcon(),
                "30 秒内见到傀儡时应为红叉");
    }

    /**
     * 数据不可得 → 问号，不能画成红叉。
     */
    @Test
    void checkIconUnknownWhenDataMissing() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0);
        VillagerReport r = evalOne(v, okSpawn(v.pos));

        assertEquals(VillagerReport.Icon.UNKNOWN, r.conditionIcon(),
                "拿不到数据时应为问号，不能误报红叉");
    }

    // ==================================================================
    // 骷髅：只看恐慌
    // ==================================================================

    @Test
    void skullIconFollowsPanicState() {
        VillagerSnapshot calm = VillagerSnapshot.of(1, 0, 64, 0)
                .withSlept(NOW - 100L)
                .withGolemDetected(false)
                .withPanic(false);
        assertEquals(false, evalOne(calm, okSpawn(calm.pos)).panicking(),
                "没被恐吓时骷髅应为暗");

        VillagerSnapshot scared = VillagerSnapshot.of(2, 0, 64, 0)
                .withSlept(NOW - 100L)
                .withGolemDetected(false)
                .withPanic(true);
        assertEquals(true, evalOne(scared, okSpawn(scared.pos)).panicking(),
                "被恐吓时骷髅应为绿");
    }

    // ==================================================================
    // 喇叭：只看闲聊前提
    // ==================================================================

    /**
     * {@code gossipReady()} = 硬条件达标 且 闲聊人数达标。
     *
     * <p>它**不**看生成点和恐慌 —— 喇叭只反映闲聊路径。</p>
     */
    @Test
    void speakerIconRequiresGossipGroupAndHardConditions() {
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0)
                .withSlept(NOW - 100L)
                .withGolemDetected(false);
        VillagerReport r = evalOne(v, noSpawn(v.pos));

        // 单人时闲聊人数不够，喇叭应为红
        assertEquals(VillagerReport.Icon.CROSS, r.gossipIcon(),
                "闲聊人数不够时喇叭应为红");
    }
}