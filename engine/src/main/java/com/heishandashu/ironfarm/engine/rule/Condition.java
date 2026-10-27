package com.heishandashu.ironfarm.engine.rule;

/**
 * 一条可判定条件的枚举。
 *
 * <p><b>本枚举的结构直接对应 1.21.1 的真实字节码</b>（Villager.java 反编译），
 * 不是按 Wiki 措辞猜的。关键事实：</p>
 *
 * <ol>
 *   <li>原版的个体门槛只有两条：{@code LAST_SLEPT} 在 24000 tick 内、
 *       没有 {@code GOLEM_DETECTED_RECENTLY} 记忆。
 *       没有"附近有人召唤过"这种独立条件 —— 那是本文档早期版本的错误理解。</li>
 *   <li>群体计数时，**每个参与者**都要独立通过上面两条（原版对每只村民调用
 *       {@code wantsToSpawnGolem}），但**不要求参与者也在恐慌/闲聊**。</li>
 *   <li>恐慌或闲聊只是**发起者**的状态：{@code VillagerPanicTrigger} 每 100 tick
 *       以门槛 3 调用；{@code Villager.gossip} 以门槛 5 调用。</li>
 * </ol>
 *
 * <p>枚举顺序刻意与推理顺序一致：先个体门槛，再触发状态，再群体门槛，最后生成点。</p>
 */
public enum Condition {

    /** 成年村民。
     *  <p>原版没有显式的成年检查 —— 婴儿没有 {@code LAST_SLEPT} 记忆，
     *  所以 {@code golemSpawnConditionsMet} 自然返回 false。
     *  这里保留成独立条件，只是为了界面上能直接说明"这只是婴儿"，
     *  判定结果与原版等价。</p> */
    ADULT("adult", Group.INDIVIDUAL),

    /** 最近 24000 tick（20 分钟）内睡过觉：原版 {@code LAST_SLEPT} 记忆。 */
    SLEPT("slept", Group.INDIVIDUAL),

    /** 没有 {@code GOLEM_DETECTED_RECENTLY} 记忆（有效期 599 tick）。
     *  <p>注意：这是"自己看到过铁傀儡"，不是"附近有人召唤过"。</p> */
    NO_GOLEM_DETECTED("no_golem", Group.INDIVIDUAL),

    /** 发起者处于恐慌（{@code VillagerPanicTrigger} 正在运行）。
     *  <p>只有**发起召唤的那一只**需要这个状态；参与者不需要。</p> */
    PANICKING("panicking", Group.TRIGGER),

    /** 发起者在闲聊（{@code Villager.gossip} 被调用）。 */
    GOSSIPING("gossiping", Group.TRIGGER),

    /** 恐慌路径的群体门槛：包围盒膨胀 10 格内 ≥3 名合格村民。 */
    PANIC_GROUP("panic_group", Group.GROUP),

    /** 闲聊路径的群体门槛：包围盒膨胀 10 格内 ≥5 名合格村民。 */
    GOSSIP_GROUP("gossip_group", Group.GROUP),

    /** 找到合法生成点（{@code SpawnUtil.trySpawnMob} 成功）。 */
    SPAWN_POINT("spawn_point", Group.SPAWN);

    /** 条件归属组，决定界面分区。 */
    public enum Group {
        /** 个体门槛（头顶第一个图标就是它）。 */
        INDIVIDUAL("individual"),
        /** 触发状态（骷髅、喇叭）。 */
        TRIGGER("trigger"),
        /** 群体门槛。 */
        GROUP("group"),
        /** 生成点。 */
        SPAWN("spawn");

        private final String id;

        Group(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final String key;
    private final Group group;

    Condition(String key, Group group) {
        this.key = key;
        this.group = group;
    }

    /** 不带前缀的短键，如 {@code slept}。 */
    public String key() {
        return key;
    }

    /** 多语言完整键，如 {@code ironfarm.cond.slept}。 */
    public String langKey() {
        return "ironfarm.cond." + key;
    }

    /** 说明文字的多语言键，如 {@code ironfarm.cond.slept.desc}。 */
    public String descLangKey() {
        return "ironfarm.cond." + key + ".desc";
    }

    public Group group() {
        return group;
    }

    /** 两条路径共用的个体门槛。 */
    public boolean isIndividual() {
        return group == Group.INDIVIDUAL;
    }

    /** 是否是「发起者」才需要的状态（参与者不需要）。 */
    public boolean isInitiatorOnly() {
        return this == PANICKING || this == GOSSIPING;
    }
}
