package com.heishandashu.ironfarm.engine.rule;

/**
 * 失败原因代码。
 *
 * <p>只做统计归类与多语言查表，不在代码里硬编码任何自然语言。
 * 每一条都对应 lang 文件里的 {@code ironfarm.reason.<code>}。</p>
 *
 * <p>⚠ 这里**没有** "附近有村民召唤过" 这条原因 —— 1.21.1 的真实代码里
 * 不存在这个条件。早期版本按 Wiki 措辞实现过，是错的，已删除。</p>
 */
public final class ReasonCodes {

    // ---- 个体门槛 ----
    /** 不是成年村民。 */
    public static final String NOT_ADULT = "not_adult";
    /** 从没睡过觉（没有 LAST_SLEPT 记忆）。 */
    public static final String NEVER_SLEPT = "never_slept";
    /** 睡过，但已经超过 24000 tick。 */
    public static final String SLEEP_EXPIRED = "sleep_expired";
    /** 持有 GOLEM_DETECTED_RECENTLY 记忆（599 tick 内看到过铁傀儡）。 */
    public static final String GOLEM_DETECTED = "golem_detected";

    // ---- 触发状态 ----
    /** 发起者没有处于恐慌。 */
    public static final String NOT_PANICKING = "not_panicking";
    /** 发起者没有处于闲聊。 */
    public static final String NOT_GOSSIPING = "not_gossiping";

    // ---- 群体门槛 ----
    /** 恐慌路径合格参与者不足 3 人。 */
    public static final String PANIC_GROUP_TOO_SMALL = "panic_group_too_small";
    /** 闲聊路径合格参与者不足 5 人。 */
    public static final String GOSSIP_GROUP_TOO_SMALL = "gossip_group_too_small";

    // ---- 生成点 ----
    /** 17×13×17 体积内没有一个合法生成点。 */
    public static final String NO_SPAWN_POINT = "no_spawn_point";
    /** 生成体积内区块未加载。 */
    public static final String SPAWN_AREA_UNLOADED = "spawn_area_unloaded";

    // ---- 无法判定 ----
    /** 村民所在区块未加载。 */
    public static final String CHUNK_UNLOADED = "chunk_unloaded";
    /** 数据缺失（适配层没有提供该项）。 */
    public static final String DATA_MISSING = "data_missing";

    private ReasonCodes() {
    }
}
