package com.heishandashu.ironfarm.neoforge.adapter;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.spawn.BlockQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把 Minecraft 的世界查询翻译成引擎的 {@link BlockQuery}。
 *
 * <p>这里集中了「原版判定到底问的是哪几个方块属性」的全部知识。
 * 每一条都必须逐字对应 Wiki 的措辞 —— 写错一条，
 * 引擎那边再正确也会给出错误的"能不能刷"。</p>
 *
 * <p>性能：一次扫描要问 6~9 次方块 × 289 列 ≈ 2600 次方块读取，
 * 每次都会触发区块查询。所以适配层必须节流（见 VillagerCollector），
 * 且这里的 {@link #isChunkLoaded} 只用 {@code hasChunk}，
 * 绝不触发区块生成。</p>
 */
public final class WorldBlockQuery implements BlockQuery {

    private final Level level;

    public WorldBlockQuery(Level level) {
        this.level = level;
    }

    private static BlockPos toMc(BlockPos3 p) {
        return new BlockPos(p.x, p.y, p.z);
    }

    @Override
    public boolean isFullBlock(BlockPos3 pos) {
        BlockState state = state(pos);
        // "完整方块" = 占据整个 1x1x1。原版用 isCollisionShapeFullBlock。
        return state.isCollisionShapeFullBlock(level, toMc(pos));
    }

    @Override
    public boolean isSolidBlocking(BlockPos3 pos) {
        BlockState state = state(pos);
        // solid-blocking：1.21.1 上对应 BlockState.isSolid()。
        // ⚠ 该方法带 @Deprecated 标记（1.21.1 已用 javap 核实：
        //    BlockStateBase 里公开的只有 isSolid()，没有 isSolidBlocking()），
        //    但它就是原版 spawn 选列时用的语义，所以照用。
        //    换成 canOcclude() 会改变语义（玻璃就没法站了）。
        return state.isSolid();
    }

    @Override
    public boolean isAirOrLiquid(BlockPos3 pos) {
        BlockState state = state(pos);
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    @Override
    public boolean isWater(BlockPos3 pos) {
        BlockState state = state(pos);
        return !state.getFluidState().isEmpty()
                && state.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER;
    }

    @Override
    public boolean isRail(BlockPos3 pos) {
        BlockState state = state(pos);
        return state.is(BlockTags.RAILS);
    }

    @Override
    public boolean isRedstonePowered(BlockPos3 pos) {
        // 原版 spawn 校验用的是 level.hasNeighborSignal(pos)，
        // 即"该位置被红石信号充能"。
        return level.hasNeighborSignal(toMc(pos));
    }

    @Override
    public boolean hasFullTopSurface(BlockPos3 pos) {
        BlockState state = state(pos);
        // "完整上表面"：从上方看这一面的碰撞形状是完整的。
        // 原版用 isFaceFull(getCollisionShape(...).getFaceShape(Direction.UP), ...)，
        // 简化等价写法是形状在该面完整。
        return state.isFaceSturdy(level, toMc(pos), net.minecraft.core.Direction.UP);
    }

    @Override
    public boolean hasEntityCollision(BlockPos3 pos) {
        BlockPos mc = toMc(pos);
        // 铁傀儡碰撞箱 1.4 x 2.7 x 1.4，落在方块格上时覆盖原点格与 +X/+Z。
        // 原版用 level.noCollision(...)，这里用"该格是否有实体碰撞箱"近似：
        // 只要 AABB 与该格相交就算占位。
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                mc.getX(), mc.getY(), mc.getZ(),
                mc.getX() + 1.0, mc.getY() + 3.0, mc.getZ() + 1.0);
        return !level.noCollision(box);
    }

    @Override
    public boolean isChunkLoaded(BlockPos3 pos) {
        // 只用 hasChunk —— 绝不能调 getChunk，那会触发生成并卡死服务端。
        return level.hasChunk(pos.x >> 4, pos.z >> 4);
    }

    /**
     * 读方块状态。
     *
     * <p>未加载区块返回空气而不是抛异常 —— 上层已经用
     * {@link #isChunkLoaded} 做过整体判断，这里只是兜底，
     * 避免边界上偶发的越界把整个渲染循环打断。</p>
     */
    private BlockState state(BlockPos3 pos) {
        BlockPos mc = toMc(pos);
        if (!level.hasChunk(mc.getX() >> 4, mc.getZ() >> 4)) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return level.getBlockState(mc);
    }
}
