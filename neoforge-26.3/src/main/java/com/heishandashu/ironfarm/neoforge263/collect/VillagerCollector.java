package com.heishandashu.ironfarm.neoforge263.collect;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Optional;

/**
 * 把一只真实的 {@link Villager} 读成引擎的 {@link VillagerSnapshot}。
 *
 * <p>这里的每一行都对应 1.21.1 真实字节码里的一次读取，不是从 Wiki 猜的：</p>
 *
 * <ul>
 *   <li><b>睡眠</b>：{@code brain.getMemory(MemoryModuleType.LAST_SLEPT)}
 *       —— 原版 {@code golemSpawnConditionsMet} 就是这么读的。</li>
 *   <li><b>傀儡记忆</b>：{@code brain.hasMemoryValue(GOLEM_DETECTED_RECENTLY)}
 *       —— 原版 {@code wantsToSpawnGolem} 直接判这个布尔量。
 *       它的 599 tick 过期由原版 {@code Brain} 自己维护，**不要**自己算时间。</li>
 *   <li><b>恐慌</b>：{@code HURT_BY || NEAREST_HOSTILE}
 *       —— 与原版 {@code VillagerPanicTrigger.canStillUse} 逐字一致。</li>
 *   <li><b>闲聊</b>：{@code Villager.lastGossipTime} 字段（AW 开放）。
 *       窗口 1200 tick，见 {@link GossipWindow}。</li>
 * </ul>
 */
public final class VillagerCollector {

    /** 成年村民眼睛高度。 */
    public static final double VILLAGER_EYE_HEIGHT = 1.62;

    /**
     * 服务端采集（数据齐全）。
     */
    public static VillagerSnapshot collect(Villager v, ServerLevel level, long now) {
        BlockPos3 pos = new BlockPos3(
                v.blockPosition().getX(), v.blockPosition().getY(), v.blockPosition().getZ());

        long lastSlept = readLastSlept(v);
        boolean golemSeen = v.getBrain().hasMemoryValue(MemoryModuleType.GOLEM_DETECTED_RECENTLY);
        boolean panicking = isPanickingStrict(v);
        long lastGossip = readLastGossip(v);
        boolean gossiping = GossipWindow.isRecent(lastGossip, now);
        BlockPos3 bed = readBed(v);

        return new VillagerSnapshot(
                v.getId(),
                pos,
                !v.isBaby(),
                lastSlept,
                golemSeen,
                panicking,
                gossiping,
                bed,
                true,   // 服务端能读到 LAST_SLEPT
                true);  // 服务端能读到 GOLEM_DETECTED_RECENTLY
    }

    /**
     * 客户端采集（Brain 数据不可得）。
     *
     * <p>客户端只有实体同步数据：位置、成年与否、UUID。
     * {@code LAST_SLEPT} / {@code GOLEM_DETECTED_RECENTLY} / {@code lastGossipTime}
     * 都只在服务端，客户端收不到。</p>
     *
     * <p>所以这里把两个"数据可得"标志位显式传 false，
     * 让引擎给出 UNKNOWN 而不是假红叉 / 假绿勾。
     * {@code panicking} 也传 false，由 {@code PanicResolver} 推算后回填 ——
     * 那是客户端**能**算准的部分。</p>
     */
    public static VillagerSnapshot collectClientSide(Villager v) {
        BlockPos3 pos = new BlockPos3(
                (int) Math.floor(v.getX()), (int) Math.floor(v.getY()), (int) Math.floor(v.getZ()));

        return new VillagerSnapshot(
                v.getId(),
                pos,
                !v.isBaby(),
                VillagerSnapshot.NEVER,   // 读不到
                false,                    // 读不到
                false,                    // 由 PanicAnalysis 回填
                false,                    // 闲聊时间戳也读不到
                null,
                false,                    // sleepDataKnown = false
                false);                   // golemMemoryKnown = false
    }

    /**
     * 读 {@code LAST_SLEPT} 记忆（游戏时间）。
     * <p>没有记忆（婴儿从没睡过）返回 {@link VillagerSnapshot#NEVER}。</p>
     */
    public static long readLastSlept(Villager v) {
        Optional<Long> mem = v.getBrain().getMemory(MemoryModuleType.LAST_SLEPT);
        return mem.isPresent() ? mem.get().longValue() : VillagerSnapshot.NEVER;
    }

    /**
     * 读 {@code lastGossipTime} 字段。
     *
     * <p>该字段是 private，通过 {@code ironfarm.accesswidener} 开放。
     * 从没闲聊过的村民值是 0（不是 -1），而 0 是合法的游戏时间，
     * 所以这里额外判断"是否晚于世界创建"，避免把"从没闲聊"误判成"刚闲聊过"。</p>
     *
     * <p>判据：{@code lastGossipTime <= 0} 视为从未闲聊。
     * 游戏时间从 0 开始，第一 tick 就闲聊的概率可以忽略，
     * 而且即便误判也只是让喇叭短暂亮一下，不影响正确性。</p>
     *
     * @return 游戏时间；从未闲聊返回 {@link GossipWindow#NEVER}
     */
    public static long readLastGossip(Villager v) {
        long t = v.lastGossipTime;
        return t <= 0L ? GossipWindow.NEVER : t;
    }

    /**
     * 精确版恐慌判定：与原版 {@code VillagerPanicTrigger.canStillUse} 逐字一致。
     *
     * <pre>
     * isHurt(v) || hasHostile(v)
     *   isHurt     = brain.hasMemoryValue(HURT_BY)
     *   hasHostile = brain.hasMemoryValue(NEAREST_HOSTILE)
     * </pre>
     */
    public static boolean isPanickingStrict(Villager v) {
        return v.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY)
                || v.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE);
    }

    /**
     * 宽松版：日程是否处于 PANIC 时段。
     * <p>比 {@link #isPanickingStrict} 宽 —— 它只反映日程表，
     * 不反映村民实际有没有看到怪物。界面请用严格版。</p>
     */
    public static boolean isPanicking(Villager v) {
        return v.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.PANIC);
    }

    /** 读绑定的床（{@code HOME} 记忆）。仅用于界面显示。 */
    public static BlockPos3 readBed(Villager v) {
        Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
        if (!home.isPresent()) {
            return null;
        }
        BlockPos p = home.get().pos();
        return new BlockPos3(p.getX(), p.getY(), p.getZ());
    }

    /** 把引擎坐标转回 Minecraft 坐标。 */
    public static BlockPos toMc(BlockPos3 p) {
        return new BlockPos(p.x, p.y, p.z);
    }
}
