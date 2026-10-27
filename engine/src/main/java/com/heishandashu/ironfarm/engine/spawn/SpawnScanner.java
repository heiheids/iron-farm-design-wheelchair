package com.heishandashu.ironfarm.engine.spawn;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.version.VersionProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成点扫描器：把原版「找合法生成点」的判定逐条照做一遍。
 *
 * <p>原版流程（Java 版）：</p>
 * <ol>
 *   <li>在 17×13×17 体积内，最多 10 次随机取列；</li>
 *   <li>每列从该列顶部往下，找第一个「空气或液体、且下方 solid-blocking」的格子；</li>
 *   <li>校验下方有完整上表面；</li>
 *   <li>校验候选点 + 上方 2 格都不是完整方块、不被红石充能、不是铁轨；</li>
 *   <li>校验上方 2 格不是水；</li>
 *   <li>校验不与已存在实体碰撞。</li>
 * </ol>
 *
 * <p>本类做的是**穷举**而不是随机抽 10 次：把体积内每一列都算一遍，
 * 得到完整的合法点集合。这样界面才能画出「铁傀儡可刷范围」。
 * 随机抽取的命中概率由 {@link SpawnScanResult#singleSummonSuccessChance()} 估算。</p>
 *
 * <p>性能：17×13×17 = 3757 格，每次判定要问 6~9 次方块。这个量级在
 * 「每 20 tick 扫一次、只扫玩家附近的村民」的前提下完全可接受，
 * 但**不要**每 tick 对全部村民扫 —— 适配层必须做节流。</p>
 */
public final class SpawnScanner {

    private final VersionProfile profile;

    public SpawnScanner(VersionProfile profile) {
        this.profile = profile;
    }

    /**
     * 以 {@code center} 为中心扫描整个体积。
     *
     * @param center 触发召唤的村民方块坐标
     * @param world  方块查询
     */
    public SpawnScanResult scan(BlockPos3 center, BlockQuery world) {
        int hw = profile.spawnHalfWidth;
        int hh = profile.spawnHalfHeight;

        // 先判断体积是否全部加载：只要有一列没加载，结论就只能是"无法判定"。
        // 这里只抽查 8 个角 + 中心，避免 3757 次区块查询。
        boolean loaded = true;
        int[][] corners = {
                {-hw, -hh, -hw}, {hw, -hh, -hw}, {-hw, -hh, hw}, {hw, -hh, hw},
                {-hw, hh, -hw}, {hw, hh, -hw}, {-hw, hh, hw}, {hw, hh, hw},
                {0, 0, 0}
        };
        for (int[] c : corners) {
            if (!world.isChunkLoaded(center.offset(c[0], c[1], c[2]))) {
                loaded = false;
                break;
            }
        }

        List<BlockPos3> valid = new ArrayList<BlockPos3>();
        List<SpawnScanResult.Rejection> rejected = new ArrayList<SpawnScanResult.Rejection>();

        for (int dx = -hw; dx <= hw; dx++) {
            for (int dz = -hw; dz <= hw; dz++) {
                BlockPos3 candidate = findTopCandidate(center, dx, dz, hh, world);
                if (candidate == null) {
                    // 该列没有"空气/液体 + 下方 solid-blocking"的位置
                    rejected.add(new SpawnScanResult.Rejection(
                            center.offset(dx, 0, dz), "no_surface"));
                    continue;
                }
                String failCode = validate(candidate, world);
                if (failCode == null) {
                    valid.add(candidate);
                } else {
                    rejected.add(new SpawnScanResult.Rejection(candidate, failCode));
                }
            }
        }

        return new SpawnScanResult(center, hw, hh, loaded, valid, rejected);
    }

    /**
     * 在一列里，从体积顶部往下找第一个候选点。
     *
     * <p>原版是「取该列最上面的空气/液体格，且其下方 solid-blocking」。
     * 从顶部往下扫等价，且更容易表达「体积之外的情况一律不算」。</p>
     */
    private BlockPos3 findTopCandidate(BlockPos3 center, int dx, int dz, int hh, BlockQuery world) {
        for (int dy = hh; dy >= -hh; dy--) {
            BlockPos3 p = center.offset(dx, dy, dz);
            if (!world.isAirOrLiquid(p)) {
                continue;
            }
            if (world.isSolidBlocking(p.offset(0, -1, 0))) {
                return p;
            }
        }
        return null;
    }

    /**
     * 对候选点跑完整校验。返回 null 表示合法，否则返回拒绝原因代码。
     *
     * <p>顺序与原版一致，且**每一层都保留自己的原因**：
     * 排查「为什么这里不刷」时，是「下方不是完整上表面」还是「被红石充能」
     * 是完全不同的修法。</p>
     */
    private String validate(BlockPos3 p, BlockQuery world) {
        // 1) 下方方块必须有完整上表面
        if (!world.hasFullTopSurface(p.offset(0, -1, 0))) {
            return "no_full_top";
        }
        // 2) 候选点 + 上方两格不能是完整方块
        for (int i = 0; i <= 2; i++) {
            if (world.isFullBlock(p.offset(0, i, 0))) {
                return "full_block_above";
            }
        }
        // 3) 这 3 格不能是铁轨
        for (int i = 0; i <= 2; i++) {
            if (world.isRail(p.offset(0, i, 0))) {
                return "rail";
            }
        }
        // 4) 这 3 格不能被红石充能
        for (int i = 0; i <= 2; i++) {
            if (world.isRedstonePowered(p.offset(0, i, 0))) {
                return "powered";
            }
        }
        // 5) 上方两格不能是水（候选点本身可以是水 → 可以刷在 1 格深水里）
        for (int i = 1; i <= 2; i++) {
            if (world.isWater(p.offset(0, i, 0))) {
                return "water_above";
            }
        }
        // 6) 不能与已有实体碰撞
        if (world.hasEntityCollision(p)) {
            return "entity_collision";
        }
        return null;
    }
}
