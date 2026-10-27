package com.heishandashu.ironfarm.engine.model;

/**
 * 三维整数坐标。仅用于引擎内部，不依赖 net.minecraft.core.BlockPos。
 */
public final class BlockPos3 {
    public final int x;
    public final int y;
    public final int z;

    public BlockPos3(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BlockPos3 offset(int dx, int dy, int dz) {
        return new BlockPos3(x + dx, y + dy, z + dz);
    }

    /** 欧氏平方距离，用于球面半径判定（恐慌半径、10 格参与者统计）。 */
    public double distSqr(BlockPos3 o) {
        double dx = this.x - o.x;
        double dy = this.y - o.y;
        double dz = this.z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 立方体范围判定，用于 ±16 傀儡侦测与 ±10 召唤扩散。 */
    public boolean withinAxis(BlockPos3 o, int ax, int ay, int az) {
        return Math.abs(this.x - o.x) <= ax
                && Math.abs(this.y - o.y) <= ay
                && Math.abs(this.z - o.z) <= az;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockPos3)) {
            return false;
        }
        BlockPos3 p = (BlockPos3) o;
        return x == p.x && y == p.y && z == p.z;
    }

    @Override
    public int hashCode() {
        return (x * 31 + y) * 31 + z;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
