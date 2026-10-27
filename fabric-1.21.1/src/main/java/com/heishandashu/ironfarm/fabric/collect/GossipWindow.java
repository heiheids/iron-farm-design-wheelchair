package com.heishandashu.ironfarm.fabric.collect;

/**
 * 闲聊判定规则。
 *
 * <p>原版**没有**"正在闲聊"这个状态：{@code Villager.gossip()} 是一次性调用，
 * 只在两只村民交谈时发生。所以界面上的喇叭图标只能靠"最近一次闲聊距今多久"来近似。</p>
 *
 * <p>下面这条规则是从 1.21.1 字节码**量出来**的，不是猜的：</p>
 *
 * <pre>
 * // Villager.gossip(ServerLevel, Villager partner, long gameTime)
 * if (gameTime &lt; this.lastGossipTime || gameTime &gt;= this.lastGossipTime + 1200) {
 *     if (gameTime &lt; partner.lastGossipTime || gameTime &gt;= partner.lastGossipTime + 1200) {
 *         ... 真正执行 gossip ...
 *         this.lastGossipTime   = gameTime;
 *         partner.lastGossipTime = gameTime;
 *         this.spawnGolemIfNeeded(level, gameTime, 5);   // ← 门槛 5
 *     }
 * }
 * </pre>
 *
 * <p>结论：<b>同一只村民每 1200 tick 最多闲聊一次</b>。
 * 所以"距上次闲聊 &lt; 1200 tick"正是"这只村民有资格发起闲聊召唤"的窗口，
 * 而且这个窗口是原版写死的，不是我们选的。</p>
 *
 * <p>⚠ 第一版这里写的是"200 tick 窗口"并标注为推断值 —— 那是错的。
 * 真实值可以从 {@code lastGossipTime} 字段直接读出来，根本不需要猜。
 * 读法见 {@link VillagerCollector#readLastGossip(Villager)}
 * （字段通过 accessWidener 开放）。</p>
 */
public final class GossipWindow {

    /**
     * 闲聊窗口：1200 tick = 60 秒。
     *
     * <p>来自 {@code Villager.gossip} 里的 {@code ldc2_w 1200l}。
     * 与 {@code Villager.GOSSIP_COOLDOWN} 常量同值。</p>
     */
    public static final int TICKS = 1200;

    private GossipWindow() {
    }

    /** 该村民在 {@code now} 时刻是否持有"刚闲聊过"的资格。 */
    public static boolean isRecent(long lastGossipTime, long now) {
        if (lastGossipTime == NEVER) {
            return false;
        }
        long since = now - lastGossipTime;
        return since >= 0 && since < TICKS;
    }

    /** 表示从未闲聊过的哨兵值。 */
    public static final long NEVER = Long.MIN_VALUE;

    /** 距上次闲聊过了多少 tick；从未闲聊返回 {@link Long#MAX_VALUE}。 */
    public static long ticksSince(long lastGossipTime, long now) {
        if (lastGossipTime == NEVER) {
            return Long.MAX_VALUE;
        }
        long d = now - lastGossipTime;
        return d < 0 ? 0 : d;
    }
}
