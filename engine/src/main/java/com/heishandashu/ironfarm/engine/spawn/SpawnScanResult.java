package com.heishandashu.ironfarm.engine.spawn;

import com.heishandashu.ironfarm.engine.model.BlockPos3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次「为某只村民找生成点」的结果。
 *
 * <p>对应原版逻辑：在 17×13×17 体积里最多尝试 10 次，每次随机取一列，
 * 从该列顶部往下找到第一个「空气/液体且下方 solid-blocking」的位置，
 * 再对该位置做完整校验。这里不改随机数，而是**穷举**全部候选点，
 * 因为界面要把「整个可刷范围」画成矩形框，只留 10 次随机的结果是看不见的。</p>
 *
 * <p>所以本对象同时给出两个层面的信息：</p>
 * <ul>
 *   <li>{@link #validPoints()} —— 全部合法生成点（用于画框、判断"有没有地方能刷"）；</li>
 *   <li>{@link #attemptsSimulated()} —— 按原版随机抽取模型的命中率估算（用于产量预估）。</li>
 * </ul>
 */
public final class SpawnScanResult {

    /** 这次扫描以谁为中心（通常是村民）。 */
    public final BlockPos3 center;
    /** 扫描体积的水平半边长。 */
    public final int halfWidth;
    /** 扫描体积的垂直半边长。 */
    public final int halfHeight;
    /** 体积是否全部处于已加载区块。false 时所有结论都只能是"无法判定"。 */
    public final boolean areaLoaded;

    private final List<BlockPos3> validPoints;
    /** 被拒的候选点及其原因（用于"为什么这里不刷"的排查）。 */
    private final List<Rejection> rejections;

    /** 被拒原因。 */
    public static final class Rejection {
        public final BlockPos3 pos;
        /** 机器可读代码，对应 lang 里的 ironfarm.spawn.reject.<code>。 */
        public final String code;

        public Rejection(BlockPos3 pos, String code) {
            this.pos = pos;
            this.code = code;
        }

        @Override
        public String toString() {
            return pos + ":" + code;
        }
    }

    public SpawnScanResult(
            BlockPos3 center,
            int halfWidth,
            int halfHeight,
            boolean areaLoaded,
            List<BlockPos3> validPoints,
            List<Rejection> rejections) {
        this.center = center;
        this.halfWidth = halfWidth;
        this.halfHeight = halfHeight;
        this.areaLoaded = areaLoaded;
        this.validPoints = validPoints == null
                ? Collections.<BlockPos3>emptyList()
                : Collections.unmodifiableList(new ArrayList<BlockPos3>(validPoints));
        this.rejections = rejections == null
                ? Collections.<Rejection>emptyList()
                : Collections.unmodifiableList(new ArrayList<Rejection>(rejections));
    }

    public boolean hasValidPoint() {
        return !validPoints.isEmpty();
    }

    public int validPointCount() {
        return validPoints.size();
    }

    public List<BlockPos3> validPoints() {
        return validPoints;
    }

    public List<Rejection> rejections() {
        return rejections;
    }

    /** 体积边界（供渲染画矩形框）。 */
    public BlockPos3 min() {
        return new BlockPos3(center.x - halfWidth, center.y - halfHeight, center.z - halfWidth);
    }

    public BlockPos3 max() {
        return new BlockPos3(center.x + halfWidth, center.y + halfHeight, center.z + halfWidth);
    }

    /**
     * 估算「一次召唤（10 次尝试）里至少命中一次」的概率。
     *
     * <p>⚠ 分母是**列数**，不是格数。原版是「随机取一个 y 列，再在该列里
     * 找到最上面的合法位置」，所以随机性发生在 17×17 = 289 列上，
     * 不是 17×13×17 = 3757 个格上。</p>
     *
     * <p>用格数当分母会把概率显著低估（实测 289 列全合法时是 1.0，
     * 按格数算只有 0.55），从而把一台完全正常的机器判成"成功率只有一半"。</p>
     *
     * <p>设合法列数为 K、总列数为 N，则单次失败概率为 (1 - K/N)，
     * 10 次全失败为 (1 - K/N)^10。</p>
     */
    public double singleSummonSuccessChance() {
        int columns = (halfWidth * 2 + 1) * (halfWidth * 2 + 1);
        if (columns <= 0) {
            return 0;
        }
        int validColumns = countValidColumns();
        if (validColumns <= 0) {
            return 0;
        }
        double pMiss = 1.0 - (double) validColumns / (double) columns;
        return 1.0 - Math.pow(pMiss, 10);
    }

    /** 有多少个不同的 (x, z) 列至少含一个合法点。 */
    private int countValidColumns() {
        java.util.Set<Long> cols = new java.util.HashSet<Long>();
        for (BlockPos3 p : validPoints) {
            long key = ((long) p.x << 32) ^ (p.z & 0xFFFFFFFFL);
            cols.add(key);
        }
        return cols.size();
    }

    @Override
    public String toString() {
        return "SpawnScanResult{center=" + center
                + " box=" + (halfWidth * 2 + 1) + "x" + (halfHeight * 2 + 1) + "x" + (halfWidth * 2 + 1)
                + " loaded=" + areaLoaded
                + " valid=" + validPoints.size()
                + " rejected=" + rejections.size()
                + " p1=" + String.format(java.util.Locale.ROOT, "%.4f", singleSummonSuccessChance()) + "}";
    }
}
