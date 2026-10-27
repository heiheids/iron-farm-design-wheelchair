package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanner;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成点扫描器测试：逐条对照原版的 6 项校验。
 *
 * <p>每一类拒绝原因都单独构造一个场景，这样改坏其中一条时会立刻变红。</p>
 */
class SpawnScannerTest {

    private static final BlockPos3 CENTER = new BlockPos3(0, 65, 0);

    /**
     * 造一个标准的「刷铁平台」：y=64 实心地基，y=65..66 空气。
     * 扫描体积是以 CENTER=(0,65,0) 为中心的 17×13×17。
     */
    private FakeWorld platform() {
        FakeWorld w = new FakeWorld();
        w.floor(64, -20, 20, -20, 20);   // 地基
        w.air(65, -20, 20, -20, 20);     // 生成层
        w.air(66, -20, 20, -20, 20);     // 傀儡头部占位
        w.air(67, -20, 20, -20, 20);     // 傀儡头顶
        // y=64 之上全是空气，往下是地基
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                w.solidBlocking.add(new BlockPos3(x, 63, z));
            }
        }
        return w;
    }

    @Test
    void flatPlatformGivesFullSpawnArea() {
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, platform());
        assertTrue(r.areaLoaded);
        assertTrue(r.hasValidPoint());
        // 17×17 = 289 列，每列都应命中 y=65
        assertEquals(289, r.validPointCount());
        assertEquals(65, r.validPoints().get(0).y);
        assertEquals(1.0, r.singleSummonSuccessChance(), 0.0001);
    }

    @Test
    void boxDimensionsMatchProfile() {
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, platform());
        assertEquals(17, r.halfWidth * 2 + 1);
        assertEquals(13, r.halfHeight * 2 + 1);
        assertEquals(new BlockPos3(-8, 59, -8), r.min());
        assertEquals(new BlockPos3(8, 71, 8), r.max());
    }

    @Test
    void fullBlockAboveBlocksSpawn() {
        FakeWorld w = platform();
        w.fullBlocks.add(new BlockPos3(0, 65, 0));
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        // 该列被拒，其余 288 列仍可刷
        assertEquals(288, r.validPointCount());
        assertTrue(hasRejection(r, "full_block_above") || hasRejection(r, "no_surface"),
                "应记录 full_block_above 或 no_surface: " + r.rejections());
    }

    @Test
    void noFullTopSurfaceBlocksSpawn() {
        FakeWorld w = new FakeWorld();
        // 地基只有 solidBlocking，没有完整上表面（模拟台阶）
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                w.solidBlocking.add(new BlockPos3(x, 64, z));
                w.airOrLiquid.add(new BlockPos3(x, 65, z));
                w.airOrLiquid.add(new BlockPos3(x, 66, z));
            }
        }
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        assertFalse(r.hasValidPoint());
        assertEquals(289, countRejections(r, "no_full_top"));
    }

    @Test
    void railsBlockSpawn() {
        FakeWorld w = platform();
        w.rails.add(new BlockPos3(1, 65, 1));
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        assertEquals(288, r.validPointCount());
        assertEquals(1, countRejections(r, "rail"));
    }

    @Test
    void redstonePoweredBlocksSpawn() {
        FakeWorld w = platform();
        w.powered.add(new BlockPos3(2, 65, 2));
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        assertEquals(288, r.validPointCount());
        assertEquals(1, countRejections(r, "powered"));
    }

    @Test
    void waterDirectlyAboveBlocksSpawn() {
        FakeWorld w = platform();
        w.water.add(new BlockPos3(3, 66, 3));
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        assertEquals(288, r.validPointCount());
        assertEquals(1, countRejections(r, "water_above"));
    }

    @Test
    void shallowWaterOnSpawnBlockStillAllowed() {
        // 候选点自身是水（1 格深的水）也允许 —— 原版行为
        FakeWorld w = platform();
        w.water.add(new BlockPos3(4, 65, 4));
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        // 该点仍合法（上方 2 格不是水）
        assertEquals(289, r.validPointCount());
    }

    @Test
    void entityCollisionBlocksSpawn() {
        FakeWorld w = platform();
        w.entityCollisions.add(new BlockPos3(5, 65, 5));
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        assertEquals(288, r.validPointCount());
        assertEquals(1, countRejections(r, "entity_collision"));
    }

    @Test
    void unloadedChunkMakesResultUnknown() {
        FakeWorld w = platform();
        w.unloaded.add(new BlockPos3(8, 71, 8)); // 一个角
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        assertFalse(r.areaLoaded);
    }

    @Test
    void successChanceScalesWithValidColumnCount() {
        // 全部列合法 → 100%
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult full = scanner.scan(CENTER, platform());
        assertEquals(1.0, full.singleSummonSuccessChance(), 0.0001);

        // 只有 1 列合法（1 个点）→ 1-(1-1/289)^10 ≈ 0.0341
        SpawnScanResult single = new SpawnScanResult(CENTER, 8, 6, true,
                java.util.Collections.singletonList(new BlockPos3(0, 65, 0)), null);
        double p = single.singleSummonSuccessChance();
        assertTrue(p > 0.033 && p < 0.036, "期望约 0.0341，实际 " + p);

        // 同一列里放 5 个点，概率应与只放 1 个点相同（随机性在列上，不在格上）
        List<BlockPos3> sameColumn = new java.util.ArrayList<BlockPos3>();
        for (int y = 60; y < 65; y++) {
            sameColumn.add(new BlockPos3(0, y, 0));
        }
        SpawnScanResult many = new SpawnScanResult(CENTER, 8, 6, true, sameColumn, null);
        assertEquals(p, many.singleSummonSuccessChance(), 0.0001);
    }

    @Test
    void emptyAreaGivesZeroChance() {
        SpawnScanResult empty = new SpawnScanResult(CENTER, 8, 6, true, null, null);
        assertEquals(0.0, empty.singleSummonSuccessChance(), 0.0001);
        assertFalse(empty.hasValidPoint());
        assertNotNull(empty.rejections());
    }

    @Test
    void rejectionsExplainEveryFailedColumn() {
        FakeWorld w = new FakeWorld();
        // 完全空的世界：没有任何 solidBlocking → 每列 no_surface
        SpawnScanner scanner = new SpawnScanner(VersionProfile.MODERN);
        SpawnScanResult r = scanner.scan(CENTER, w);
        assertFalse(r.hasValidPoint());
        assertEquals(289, r.rejections().size());
        assertEquals(289, countRejections(r, "no_surface"));
    }

    private static boolean hasRejection(SpawnScanResult r, String code) {
        for (SpawnScanResult.Rejection rej : r.rejections()) {
            if (code.equals(rej.code)) {
                return true;
            }
        }
        return false;
    }

    private static int countRejections(SpawnScanResult r, String code) {
        int n = 0;
        for (SpawnScanResult.Rejection rej : r.rejections()) {
            if (code.equals(rej.code)) {
                n++;
            }
        }
        return n;
    }
}
