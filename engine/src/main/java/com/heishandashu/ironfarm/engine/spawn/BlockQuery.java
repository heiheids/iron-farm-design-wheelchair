package com.heishandashu.ironfarm.engine.spawn;

import com.heishandashu.ironfarm.engine.model.BlockPos3;

/**
 * 引擎与游戏之间的**方块查询接口**。
 *
 * <p>这是整个工程里最关键的一条设计线：引擎不认识 Minecraft，所以它不能直接读
 * {@code Level.getBlockState(...)}。适配层实现这个接口，把真正的世界查询转进来。</p>
 *
 * <p>接口只暴露「原版判定真正用到的那几件事」，而不是完整的方块状态。
 * 这样做的直接好处是：模板方法 {@link SpawnScanner} 的全部逻辑
 * （10 次尝试、完整上表面、3 格非完整方块、红石、铁轨、水、实体碰撞）
 * 都能在没有游戏的环境里用假世界跑断言。</p>
 */
public interface BlockQuery {

    /**
     * 该位置的方块是否是"完整方块"（full block）—— 占据整个 1×1×1 体积。
     *
     * <p>原版判定里「候选点及其上方 2 格不能是完整方块」用的就是这条。
     * 台阶、栅栏、地毯、告示牌都不是完整方块，所以傀儡可以刷在里面。</p>
     */
    boolean isFullBlock(BlockPos3 pos);

    /**
     * 该位置是否是"阻挡移动的实心方块"（solid-blocking）。
     *
     * <p>用于「随机取一列时，从顶部往下找到的第一个可站立面」。
     * 注意它与 {@link #isFullBlock(BlockPos3)} **不是同一个判定**：
     * 台阶是 solid-blocking 但 topped 面不是完整的。</p>
     */
    boolean isSolidBlocking(BlockPos3 pos);

    /**
     * 该位置是否是空气或液体。
     *
     * <p>原版选列时要求落点是空气或液体。</p>
     */
    boolean isAirOrLiquid(BlockPos3 pos);

    /**
     * 该位置是否是水（含水源与流动水）。
     *
     * <p>原版要求候选点上方 2 格不能是水，但候选点本身可以是水
     * —— 所以傀儡能刷在 1 格深的水里。</p>
     */
    boolean isWater(BlockPos3 pos);

    /**
     * 该位置是否是铁轨。铁轨会阻止生成。
     */
    boolean isRail(BlockPos3 pos);

    /**
     * 该位置是否被红石信号充能。被充能的格子阻止生成。
     */
    boolean isRedstonePowered(BlockPos3 pos);

    /**
     * 该位置的方块是否有"完整上表面"。
     *
     * <p>这是 1.19.2 起新增的要求（22w19a / pre1），
     * 与 solid-blocking 是两个不同的判定。玻璃、倒置楼梯、上半砖、漏斗
     * 在部分版本上算"有完整上表面"，而普通台阶不算。</p>
     */
    boolean hasFullTopSurface(BlockPos3 pos);

    /**
     * 该位置的 2×3×2（水平 2 格、垂直 3 格）空间内是否有实体碰撞。
     *
     * <p>铁傀儡的碰撞箱是 1.4×2.7×1.4，落在方块格上时大致覆盖
     * 原点格与 +X/+Z 方向的一格。原版用 {@code level.noCollision(...)} 判定，
     * 所以掉落物、船、盔甲架、经验球、其他傀儡都会占位。</p>
     */
    boolean hasEntityCollision(BlockPos3 pos);

    /**
     * 该位置所在的区块是否已加载。
     *
     * <p>未加载时所有判定都必须返回 false 并让上层给出「无法判定」，
     * 绝不能为了"看得更远"去强制加载区块 —— 那是卡死服务端的老坑。</p>
     */
    boolean isChunkLoaded(BlockPos3 pos);
}
