package com.heishandashu.ironfarm.fabric.adapter;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.panic.SightQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 把 Minecraft 的射线检测翻译成引擎的 {@link SightQuery}。
 *
 * <p>原版村民判定恐慌用的是**眼对眼**的视线检查。
 * 这里用 {@link ClipContext.Block#COLLIDER} 的方块射线，
 * 与村民自身的 {@code hasLineOfSight} 语义一致：
 * 玻璃、台阶、活板门等是否挡视线由它们的碰撞形状决定。</p>
 *
 * <p>不查实体（{@link ClipContext.Block#COLLIDER} + 只测方块），
 * 因为原版的视线判定也只看方块遮挡，怪物之间不会互相挡。</p>
 */
public final class WorldSightQuery implements SightQuery {

    private final Level level;

    public WorldSightQuery(Level level) {
        this.level = level;
    }

    @Override
    public boolean clearLineOfSight(BlockPos3 from, double fromEyeHeight,
                                    BlockPos3 to, double toEyeHeight) {
        // 未加载区块一律判"看不见"：宁可少画一条线，也不要给出错误结论。
        if (!level.hasChunk(from.x >> 4, from.z >> 4) || !level.hasChunk(to.x >> 4, to.z >> 4)) {
            return false;
        }
        Vec3 eyeFrom = new Vec3(from.x + 0.5, from.y + fromEyeHeight, from.z + 0.5);
        Vec3 eyeTo = new Vec3(to.x + 0.5, to.y + toEyeHeight, to.z + 0.5);

        // ⚠ 这里必须用 CollisionContext 重载，**不能**传 (Entity) null。
        //
        // ClipContext 有两个构造器：
        //   ClipContext(Vec3, Vec3, Block, Fluid, Entity)
        //   ClipContext(Vec3, Vec3, Block, Fluid, CollisionContext)
        //
        // Entity 版内部会立刻调 entity.getBoundingBox()（字节码 class_3727:42
        // 调 method_21752），传 null 就是 **NullPointerException**：
        //     Cannot invoke "net.minecraft.class_1297.method_21752()" because "$$0" is null
        //
        // 这是实测崩溃：放下僵尸、第一次做视线检测时立刻崩掉客户端。
        // 用 CollisionContext.empty() 表示"不考虑实体碰撞形状"，
        // 正是我们要的语义（只关心方块是否挡视线）。
        HitResult hit = level.clip(new ClipContext(
                eyeFrom, eyeTo,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        // MISS 表示一路通到底
        return hit.getType() == HitResult.Type.MISS;
    }

    /** 便捷：直接用 BlockPos 版本。 */
    public boolean clearLineOfSight(BlockPos from, double fromEye, BlockPos to, double toEye) {
        return clearLineOfSight(
                new BlockPos3(from.getX(), from.getY(), from.getZ()), fromEye,
                new BlockPos3(to.getX(), to.getY(), to.getZ()), toEye);
    }
}
