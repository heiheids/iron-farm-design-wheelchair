package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.model.BlockPos3;

import java.util.HashSet;
import java.util.Set;

/**
 * 测试用的假世界：用一组坐标集合描述方块，避免在测试里搭一整个 Minecraft。
 */
public class FakeWorld implements com.heishandashu.ironfarm.engine.spawn.BlockQuery {

    public final Set<BlockPos3> fullBlocks = new HashSet<BlockPos3>();
    public final Set<BlockPos3> solidBlocking = new HashSet<BlockPos3>();
    public final Set<BlockPos3> airOrLiquid = new HashSet<BlockPos3>();
    public final Set<BlockPos3> water = new HashSet<BlockPos3>();
    public final Set<BlockPos3> rails = new HashSet<BlockPos3>();
    public final Set<BlockPos3> powered = new HashSet<BlockPos3>();
    public final Set<BlockPos3> fullTopSurface = new HashSet<BlockPos3>();
    public final Set<BlockPos3> entityCollisions = new HashSet<BlockPos3>();
    public final Set<BlockPos3> unloaded = new HashSet<BlockPos3>();

    /** 便捷：把某一层 y 的整片区域填成实心地基。 */
    public FakeWorld floor(int y, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos3 p = new BlockPos3(x, y, z);
                fullBlocks.add(p);
                solidBlocking.add(p);
                fullTopSurface.add(p);
            }
        }
        return this;
    }

    /** 便捷：把某一层的整片区域填成空气。 */
    public FakeWorld air(int y, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                airOrLiquid.add(new BlockPos3(x, y, z));
            }
        }
        return this;
    }

    @Override
    public boolean isFullBlock(BlockPos3 pos) {
        return fullBlocks.contains(pos);
    }

    @Override
    public boolean isSolidBlocking(BlockPos3 pos) {
        return solidBlocking.contains(pos);
    }

    @Override
    public boolean isAirOrLiquid(BlockPos3 pos) {
        return airOrLiquid.contains(pos);
    }

    @Override
    public boolean isWater(BlockPos3 pos) {
        return water.contains(pos);
    }

    @Override
    public boolean isRail(BlockPos3 pos) {
        return rails.contains(pos);
    }

    @Override
    public boolean isRedstonePowered(BlockPos3 pos) {
        return powered.contains(pos);
    }

    @Override
    public boolean hasFullTopSurface(BlockPos3 pos) {
        return fullTopSurface.contains(pos) || fullBlocks.contains(pos);
    }

    @Override
    public boolean hasEntityCollision(BlockPos3 pos) {
        return entityCollisions.contains(pos);
    }

    @Override
    public boolean isChunkLoaded(BlockPos3 pos) {
        return !unloaded.contains(pos);
    }
}
