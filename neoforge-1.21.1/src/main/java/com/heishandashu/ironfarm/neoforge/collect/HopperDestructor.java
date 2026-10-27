package com.heishandashu.ironfarm.neoforge.collect;

import com.heishandashu.ironfarm.engine.rules.WoolDestructor;
import com.heishandashu.ironfarm.engine.stats.StatsRecorder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 漏斗销毁：漏斗**输出方向**指向白色羊毛块时，清空其内容并记录。
 *
 * <h2>清理周期怎么定的（按原版漏斗的真实速度）</h2>
 *
 * <p>原版 {@code HopperBlockEntity.MOVE_ITEM_SPEED = 8}：
 * 漏斗**每 8 tick 吸 1 个物品**（吸入后 setCooldown(8)）。</p>
 *
 * <p>所以清理周期只要 ≤ 8 tick，漏斗就永远不会积压 ——
 * 每次吸入的物品在下一轮检查时必然被清掉，最多残留 1 个。
 * 这里取 {@link #CLEAR_CHECK_TICKS} = 8，与漏斗速度对齐。</p>
 *
 * <p>对比：漏斗容量 5 格（{@code HOPPER_CONTAINER_SIZE}），
 * 每 8 tick 吸 1 个，要 320 个物品才会塞满 = 2560 tick。
 * 我们每 8 tick 清一次，积压量恒定在 1~2 个，不可能堵塞。</p>
 *
 * <h2>为什么这样不会卡</h2>
 *
 * <p>第一版每 tick 扫 121 个区块，还有 {@code getChunk} 的同步加载，
 * 开游戏加速直接把游戏压死。现在拆成两层：</p>
 *
 * <ol>
 *   <li><b>重建目标列表</b>：每 {@link #TRACK_REBUILD_TICKS} tick 一次，
 *       遍历**所有会 tick 的已加载区块**（不按玩家距离裁剪）
 *       （{@code getChunkNow}，不会触发加载）。</li>
 *   <li><b>逐漏斗清理</b>：每 {@link #CLEAR_CHECK_TICKS} tick 一次，
 *       只看第 1 步登记下来的那几个漏斗 —— 通常 1~5 个，
 *       不扫区块，查一次哈希表就够。</li>
 * </ol>
 *
 * <p>也就是说：稳态下每 8 tick 只做"几次哈希查找 + 几个容器遍历"，
 * 与地图上有多少区块无关。加速器把 tick 频率提高 N 倍时，
 * 开销也是线性 ×N，而不是第一版那种"区块数 × 扫描次数"的乘积爆炸。</p>
 */
public final class HopperDestructor {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("ironfarm");

    /** 诊断日志限流。 */
    private static long lastLogMillis;

    /**
     * 每多少个 tick 重建一次目标列表。
     *
     * <p>40 tick = 2 秒。玩家搬家/拆机器后最多 2 秒生效，
     * 而重建只扫 9 个区块，成本可以忽略。</p>
     */
    private static final int TRACK_REBUILD_TICKS = 40;

    /**
     * 每多少个 tick 检查并清理一次已登记的漏斗。
     *
     * <p>= 原版漏斗的 {@code MOVE_ITEM_SPEED}（8），
     * 保证每次吸入的物品在下一轮必被清掉。</p>
     */
    public static final int CLEAR_CHECK_TICKS = WoolDestructor.CLEAR_PERIOD_TICKS;

    /**
     * 统计对象的**取用器**，不是引用。
     *
     * <p><b>⚠ 这里踩过一个致命 bug：</b>{@code IronFarmClientState.loadStats()}
     * 会执行 {@code this.stats = StatsIO.load()} —— 把 stats 字段整个替换成
     * 一个新对象。如果本类在构造时缓存了旧引用，之后所有
     * {@code record()} 都写进那个已经被丢弃的对象，
     * 界面上读的是新对象，于是**统计数据永远为 0**。</p>
     *
     * <p>用户反馈"统计数据完全没有"就是这个原因。
     * 改成 Supplier 每次现取，无论 stats 被替换多少次都不会脱节。</p>
     */
    private final java.util.function.Supplier<StatsRecorder> statsSupplier;

    /**
     * 已登记的"指向白色羊毛"的漏斗位置。
     *
     * <p>键是 {@link BlockPos#asLong()}，比存 BlockPos 对象省内存。
     * 数量通常是个位数。</p>
     */
    private final LongOpenHashSet targets = new LongOpenHashSet();

    private long lastRebuild = -1;
    private long lastCheck = -1;

    public HopperDestructor(java.util.function.Supplier<StatsRecorder> statsSupplier) {
        this.statsSupplier = statsSupplier;
    }

    /**
     * 每 tick 调用（内部自带两层节流，调用方不必算间隔）。
     *
     * @param level  服务端世界
     * @param center 扫描中心（通常是玩家位置）
     */
    public void tick(ServerLevel level, BlockPos center) {
        long now = level.getGameTime();

        if (lastRebuild < 0 || now - lastRebuild >= TRACK_REBUILD_TICKS) {
            try {
                rebuildTargets(level, center);
            } catch (Throwable t) {
                // ⚠ 异常必须吞掉并限流。0.41.0 的 NPE 每 tick 打一次完整堆栈，
                //   写日志本身就把游戏拖死了 —— 比功能失效严重得多。
                logThrottled("rebuildTargets", t);
                targets.clear();
            }
            lastRebuild = now;
            // 重建后立刻检查一次，避免新登记的漏斗白等 8 tick
            lastCheck = -1;
        }

        if (lastCheck >= 0 && now - lastCheck < CLEAR_CHECK_TICKS) {
            return;
        }
        lastCheck = now;

        try {
            clearTracked(level, now);
        } catch (Throwable t) {
            logThrottled("clearTracked", t);
        }
    }

    // ==================================================================
    // 第一层：重建目标列表（低频、扫区块）
    // ==================================================================

    private void rebuildTargets(ServerLevel level, BlockPos center) {
        targets.clear();

        // ⚠ 不再以玩家为中心按固定半径扫描！
        //
        //   0.51.0 及之前只扫玩家周围 24 格 —— 玩家走远一点就不统计了。
        //   用户反馈"距离稍微远一点就不统计"就是这个。
        //
        //   现在收集**所有会 tick 的区块**：
        //     ① 每个玩家视距范围内的区块
        //     ② 强制加载的区块（chunk loader / 出生点）
        //   两者的并集就是服务器真正在 tick 的范围 ——
        //   漏斗只在这个范围内工作，所以这也是唯一需要统计的范围。
        java.util.Set<Long> chunkKeys = new java.util.HashSet<Long>();

        int viewDist = level.getServer().getPlayerList().getViewDistance();
        for (var player : level.players()) {
            var cp = player.chunkPosition();
            for (int dx = -viewDist; dx <= viewDist; dx++) {
                for (int dz = -viewDist; dz <= viewDist; dz++) {
                    chunkKeys.add(net.minecraft.world.level.ChunkPos.asLong(
                            cp.x + dx, cp.z + dz));
                }
            }
        }

        // 强制加载区块：机器常常用 chunk loader 保活，玩家不在也能跑。
        for (long forcedKey : level.getForcedChunks()) {
            chunkKeys.add(forcedKey);
        }

        int[] counters = new int[2];   // [0]=chunks, [1]=hoppers
        for (long key : chunkKeys) {
            int cx = net.minecraft.world.level.ChunkPos.getX(key);
            int cz = net.minecraft.world.level.ChunkPos.getZ(key);
            // getChunkNow 只返回已加载区块，不会触发加载
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                continue;
            }
            counters[0]++;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!(be instanceof HopperBlockEntity hopper)) {
                    continue;
                }
                counters[1]++;
                if (pointsAtWool(level, hopper)) {
                    targets.add(hopper.getBlockPos().asLong());
                }
            }
        }

        // 诊断：判断"到底哪一步没生效"。
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastLogMillis > 4000) {
            lastLogMillis = nowMs;
            LOG.info("[ironfarm] HOPPER scan: chunks={}, hoppers={}, pointingAtWhiteWool={}",
                    counters[0], counters[1], targets.size());
        }
    }

    // ==================================================================
    // 第二层：逐漏斗清理（高频、不扫区块）
    // ==================================================================

    private void clearTracked(ServerLevel level, long now) {
        // 这层不依赖 center，center 为 null 也能正常清理已登记的漏斗。
        if (targets.isEmpty()) {
            return;
        }
        // 复制一份再遍历：清理过程中可能修改 targets
        long[] keys = targets.toLongArray();
        for (long key : keys) {
            BlockPos pos = BlockPos.of(key);
            // 目标可能已经被拆掉，或区块已卸载
            LevelChunk chunk = level.getChunkSource()
                    .getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                targets.remove(key);
                continue;
            }
            // ⚠ 用 EntityCreationType.CHECK：只查不建。
            //   不带参数的 getBlockEntity(pos) 可能**创建**一个方块实体，
            //   在每 8 tick 的循环里做这个太危险。
            BlockEntity be = chunk.getBlockEntity(pos,
                    LevelChunk.EntityCreationType.CHECK);
            if (!(be instanceof HopperBlockEntity hopper)) {
                // 漏斗被拆了 —— 移出登记表，下次重建会重新扫
                targets.remove(key);
                continue;
            }
            clearAndRecord(hopper, now);
        }
    }

    /**
     * 清空一个漏斗并记账。
     *
     * <p>⚠ 先统计再清空：清空后就读不到了。</p>
     *
     * <p>没有物品时直接返回，不写 setChanged() ——
     * 否则每 8 tick 都会把区块标脏，反而增加存档压力。</p>
     */
    private void clearAndRecord(HopperBlockEntity hopper, long now) {
        boolean any = false;
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack stack = hopper.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem());
            if (id != null) {
                statsSupplier.get().itemDestroy().record(now, id.toString(), stack.getCount());
            }
            any = true;
        }
        if (!any) {
            return;
        }
        hopper.clearContent();
        hopper.setChanged();
    }

    // ==================================================================

    /** 漏斗的输出方向是否指向白色羊毛。 */
    private static boolean pointsAtWool(ServerLevel level, HopperBlockEntity hopper) {
        BlockState state = level.getBlockState(hopper.getBlockPos());
        if (!state.hasProperty(HopperBlock.FACING)) {
            return false;
        }
        Direction facing = state.getValue(HopperBlock.FACING);
        BlockPos target = hopper.getBlockPos().relative(facing);
        if (!level.isLoaded(target)) {
            return false;
        }
        ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(target).getBlock());
        return WoolDestructor.triggersDestruction(blockId == null ? null : blockId.toString());
    }

    /** 退出世界时清状态，避免换世界后残留。 */
    public void clear() {
        targets.clear();
        lastRebuild = -1;
        lastCheck = -1;
    }

    /** 异常日志限流：同一条消息每 10 秒最多打一次，总共最多 20 次。 */
    private static int errorCount;
    private static long lastErrorMillis;

    private static void logThrottled(String where, Throwable t) {
        errorCount++;
        if (errorCount > 20) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastErrorMillis < 10_000) {
            return;
        }
        lastErrorMillis = nowMs;
        LOG.error("[ironfarm] hopper " + where + " failed (" + errorCount + " of max 20)", t);
    }
    /** 诊断用：当前登记了几个漏斗。 */
    public int trackedCount() {
        return targets.size();
    }
}
