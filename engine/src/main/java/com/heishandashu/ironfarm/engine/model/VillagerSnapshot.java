package com.heishandashu.ironfarm.engine.model;

/**
 * 一只村民在某一个瞬间的全部可判定状态。
 *
 * <p>这是引擎与游戏之间的唯一数据契约：适配层（Fabric / NeoForge）负责把
 * {@code net.minecraft.world.entity.npc.Villager} 读成这个对象，引擎只吃这个对象。
 * 这样一来引擎可以在没有游戏的环境里被完整测试。</p>
 *
 * <p><b>字段严格对应 1.21.1 真实字节码</b>，不是按 Wiki 转述：</p>
 * <ul>
 *   <li>{@link #lastSleptGameTime} ← {@code Brain} 的 {@code LAST_SLEPT} 记忆</li>
 *   <li>{@link #golemDetectedRecently} ← {@code Brain} 的 {@code GOLEM_DETECTED_RECENTLY} 记忆
 *       （有效期 599 tick，由 {@code GolemSensor} 设置）</li>
 *   <li>{@link #panicking} ← {@code Activity.PANIC} 是否激活 / {@code VillagerPanicTrigger}</li>
 *   <li>{@link #gossiping} ← {@code Villager.gossip} 是否刚被调用</li>
 * </ul>
 */
public final class VillagerSnapshot {

    /** 表示"从未发生过"的哨兵值。用 Long.MIN_VALUE 而不是 0，
     *  因为 0 是合法的游戏时间（世界刚创建时）。 */
    public static final long NEVER = Long.MIN_VALUE;

    /** 村民唯一 id。 */
    public final int entityId;
    public final BlockPos3 pos;

    /** 是否为成年村民。婴儿没有 LAST_SLEPT，自然不满足条件。 */
    public final boolean adult;

    /**
     * 最近一次睡觉的游戏时间（绝对 tick）。
     * <p>找不到 {@code LAST_SLEPT} 记忆时为 {@link #NEVER}。</p>
     */
    public final long lastSleptGameTime;

    /**
     * 是否持有 {@code GOLEM_DETECTED_RECENTLY} 记忆。
     * <p>这是**布尔量**而不是时间戳 —— 原版用记忆的过期机制实现 599 tick 冷却，
     * 适配层只要读 {@code brain.hasMemoryValue(...)} 即可，不需要自己算时间。</p>
     */
    public final boolean golemDetectedRecently;

    /**
     * 是否处于恐慌。
     * <p>原版没有现成的 getter，适配层用
     * {@code brain.isActive(Activity.PANIC)} 或
     * {@code VillagerPanicTrigger.hasHostile()} 推导。</p>
     */
    public final boolean panicking;

    /** 是否正在闲聊（刚调用过 {@code Villager.gossip}）。 */
    public final boolean gossiping;

    /** 该村民绑定的床；没有则为 null。仅用于显示，不参与判定。 */
    public final BlockPos3 bedPos;

    /**
     * 睡眠数据是否**可得**。
     *
     * <p>⚠ 这是一个必须存在的字段，不能靠 {@code lastSleptGameTime == NEVER} 代替。</p>
     *
     * <p>两种情况的 {@code lastSleptGameTime} 都是 {@code NEVER}，但含义完全不同：</p>
     * <ul>
     *   <li>{@code sleepDataKnown = true}：真的从没睡过 → 应该显示<b>红叉</b>。</li>
     *   <li>{@code sleepDataKnown = false}：拿不到 Brain 数据（多人游戏里客户端
     *       没有服务端的 {@code LAST_SLEPT}）→ 应该显示<b>无法判定</b>。</li>
     * </ul>
     *
     * <p>把这两种情况混为一谈，会让多人游戏里**每一只村民都显示红叉**，
     * 而那台机器可能完全正常。这是本工程最不能接受的误报。</p>
     */
    public final boolean sleepDataKnown;

    /**
     * 傀儡记忆数据是否可得，同上。
     *
     * <p>拿不到时 {@code golemDetectedRecently} 恒为 false，
     * 直接采信会显示"没看到傀儡"这个**错误的绿勾**。</p>
     */
    public final boolean golemMemoryKnown;

    /** 完整构造。 */
    public VillagerSnapshot(
            int entityId,
            BlockPos3 pos,
            boolean adult,
            long lastSleptGameTime,
            boolean golemDetectedRecently,
            boolean panicking,
            boolean gossiping,
            BlockPos3 bedPos,
            boolean sleepDataKnown,
            boolean golemMemoryKnown) {
        this.entityId = entityId;
        this.pos = pos;
        this.adult = adult;
        this.lastSleptGameTime = lastSleptGameTime;
        this.golemDetectedRecently = golemDetectedRecently;
        this.panicking = panicking;
        this.gossiping = gossiping;
        this.bedPos = bedPos;
        this.sleepDataKnown = sleepDataKnown;
        this.golemMemoryKnown = golemMemoryKnown;
    }

    /**
     * 兼容构造：默认两项数据都**可得**。
     * <p>测试与单机场景用这个；客户端多人场景必须用带两个 boolean 的完整构造，
     * 显式传 false。</p>
     */
    public VillagerSnapshot(
            int entityId,
            BlockPos3 pos,
            boolean adult,
            long lastSleptGameTime,
            boolean golemDetectedRecently,
            boolean panicking,
            boolean gossiping,
            BlockPos3 bedPos) {
        this(entityId, pos, adult, lastSleptGameTime, golemDetectedRecently,
                panicking, gossiping, bedPos, true, true);
    }

    /** 距上次睡觉过了多少 tick；从未睡过返回 {@link Long#MAX_VALUE}。 */
    public long ticksSinceLastSleep(long now) {
        if (lastSleptGameTime == NEVER) {
            return Long.MAX_VALUE;
        }
        long d = now - lastSleptGameTime;
        return d < 0 ? 0 : d;
    }

    /** 便捷构造：从未睡过、没侦测到傀儡、没在恐慌/闲聊。用于测试。 */
    public static VillagerSnapshot of(int entityId, int x, int y, int z) {
        return new VillagerSnapshot(
                entityId, new BlockPos3(x, y, z), true,
                NEVER, false, false, false, null);
    }

    /**
     * 造一只**数据不可得**的村民 —— 模拟多人游戏里客户端的状态。
     * <p>所有依赖 Brain 的条件都应为 UNKNOWN，而不是 FAIL。</p>
     */
    public static VillagerSnapshot unknownData(int entityId, int x, int y, int z) {
        return new VillagerSnapshot(
                entityId, new BlockPos3(x, y, z), true,
                NEVER, false, false, false, null, false, false);
    }

    /** 造一个"全都满足"的村民。 */
    public static VillagerSnapshot healthy(int entityId, int x, int y, int z, long now) {
        return new VillagerSnapshot(
                entityId, new BlockPos3(x, y, z), true,
                now - 100, false, true, false, new BlockPos3(x, y, z));
    }

    // ⚠ 下面每个 withXxx 都必须原样带上 sleepDataKnown / golemMemoryKnown，
    //    漏掉任何一个都会把"数据不可得"悄悄变回"数据可得 = 从没睡过 / 没看到傀儡"，
    //    也就是把 UNKNOWN 变成错误的 FAIL / PASS。

    public VillagerSnapshot withPanic(boolean value) {
        return new VillagerSnapshot(entityId, pos, adult, lastSleptGameTime,
                golemDetectedRecently, value, gossiping, bedPos, sleepDataKnown, golemMemoryKnown);
    }

    public VillagerSnapshot withGossip(boolean value) {
        return new VillagerSnapshot(entityId, pos, adult, lastSleptGameTime,
                golemDetectedRecently, panicking, value, bedPos, sleepDataKnown, golemMemoryKnown);
    }

    public VillagerSnapshot withSlept(long gameTime) {
        return new VillagerSnapshot(entityId, pos, adult, gameTime,
                golemDetectedRecently, panicking, gossiping, bedPos, sleepDataKnown, golemMemoryKnown);
    }

    /** 设置睡眠数据是否可得。 */
    public VillagerSnapshot withSleepDataKnown(boolean known) {
        return new VillagerSnapshot(entityId, pos, adult, lastSleptGameTime,
                golemDetectedRecently, panicking, gossiping, bedPos, known, golemMemoryKnown);
    }

    /** 设置傀儡记忆数据是否可得。 */
    public VillagerSnapshot withGolemMemoryKnown(boolean known) {
        return new VillagerSnapshot(entityId, pos, adult, lastSleptGameTime,
                golemDetectedRecently, panicking, gossiping, bedPos, sleepDataKnown, known);
    }

    public VillagerSnapshot withGolemDetected(boolean value) {
        return new VillagerSnapshot(entityId, pos, adult, lastSleptGameTime,
                value, panicking, gossiping, bedPos, sleepDataKnown, golemMemoryKnown);
    }

    public VillagerSnapshot withAdult(boolean value) {
        return new VillagerSnapshot(entityId, pos, value, lastSleptGameTime,
                golemDetectedRecently, panicking, gossiping, bedPos, sleepDataKnown, golemMemoryKnown);
    }

    public VillagerSnapshot withBed(BlockPos3 bed) {
        return new VillagerSnapshot(entityId, pos, adult, lastSleptGameTime,
                golemDetectedRecently, panicking, gossiping, bed, sleepDataKnown, golemMemoryKnown);
    }

    /** 复制并移动位置（测试 / 群体计数用）。 */
    public VillagerSnapshot at(int x, int y, int z) {
        return new VillagerSnapshot(entityId, new BlockPos3(x, y, z), adult,
                lastSleptGameTime, golemDetectedRecently, panicking, gossiping, bedPos,
                sleepDataKnown, golemMemoryKnown);
    }

    @Override
    public String toString() {
        return "Villager{#" + entityId + " " + pos
                + (adult ? "" : " baby")
                + " slept=" + lastSleptGameTime
                + (golemDetectedRecently ? " golemSeen" : "")
                + (panicking ? " panic" : "")
                + (gossiping ? " gossip" : "") + "}";
    }
}
