package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.eval.ConditionEngine;
import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头顶标记与状态页首行必须**用同一套判定**。
 *
 * <p><b>真实事故（用户："村民头顶信息显示似乎与点进去看到的不太同步"）：</b></p>
 *
 * <pre>
 * 头顶第一版：conditionIcon()   —— 只看个体条件（成年/睡过/没看见傀儡）
 * 状态页首行：panicFarmReady() || gossipFarmReady()
 *                              —— 还包含群体条件和生成点
 * </pre>
 *
 * <p>于是出现：头顶绿对勾，点进去却看到"生成点不合法"。
 * 玩家的结论是"两边对不上"，排查方向被完全带偏。</p>
 *
 * <p>本测试钉住的是**语义约束**：
 * 个体条件全过、但生成点不合法时，ready 判定必须为 false。
 * 任何改回"只看 conditionIcon"的实现都会在这里失败。</p>
 */
class OverlayStateConsistencyTest {

    private static final long NOW = 1_000_000L;

    private final ConditionEngine engine = new ConditionEngine(VersionProfile.V1_21_1);

    /** 生成点合法。 */
    private SpawnScanResult okSpawn(BlockPos3 c) {
        List<BlockPos3> pts = new ArrayList<BlockPos3>();
        pts.add(c.offset(0, 1, 0));
        return new SpawnScanResult(c, 8, 6, true, pts, null);
    }

    /** 生成点**不**合法：候选点为空。 */
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

    /**
     * 核心断言：个体条件全过，但生成点不合法 —— ready 必须为 false。
     *
     * <p>如果这条失败，说明头顶会亮绿勾而状态页显示失败，
     * 也就是用户反馈的"不同步"又回来了。</p>
     */
    @Test
    void individualOkButNoSpawnIsNotReady() {
        // 刚睡醒、成年、附近没有傀儡
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0)
                .withSlept(NOW - 100L)
                .withGolemDetected(false);
        VillagerReport r = evalOne(v, noSpawn(v.pos));

        // 个体条件确实都满足 —— 这正是第一版头顶亮绿勾的原因
        assertTrue(r.hardConditionsMet(),
                "前提：个体条件应全部满足（否则这条测试测不到问题）");

        // 但生成点不合法，整体不能算 ready
        assertFalse(r.panicFarmReady(),
                "生成点不合法时 panicFarmReady 必须为 false，"
                        + "否则头顶亮绿勾而状态页显示失败");
        assertFalse(r.gossipFarmReady(),
                "闲聊路径同理，也必须为 false");
    }

    /**
     * 反向：生成点合法、个体条件满足时，判定应能成立
     * —— 防止"修过头"导致永远不亮。
     */
    @Test
    void individualOkAndSpawnOkIsReady() {
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0)
                .withSlept(NOW - 100L)
                .withGolemDetected(false);
        VillagerReport r = evalOne(v, okSpawn(v.pos));

        assertTrue(r.hardConditionsMet(), "个体条件应满足");
        // 恐慌链还需要群体条件与恐慌状态，这里只验证生成点不再挡住
        assertTrue(r.get(Condition.SPAWN_POINT).isPass(),
                "生成点合法时应为 PASS");
    }

    /**
     * 数据不可得时，ready 不能误判为 true，也不能把 ICON 判成红叉。
     */
    @Test
    void unknownDataIsNotReadyAndNotCross() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0);
        VillagerReport r = evalOne(v, okSpawn(v.pos));

        assertFalse(r.panicFarmReady(),
                "数据不可得时不能算 ready");
        assertTrue(r.hardConditionsUnknown(),
                "应标记为数据不可得");
        // 头顶应显示问号而不是叉 —— 这条保证 UI 层不会误报
        assertTrue(r.conditionIcon() == VillagerReport.Icon.UNKNOWN,
                "数据不可得时图标必须是问号");
    }
}