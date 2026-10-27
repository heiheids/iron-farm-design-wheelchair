package com.heishandashu.ironfarm.engine.panic;

/**
 * 会吓到村民的生物的完整档案。
 *
 * <p>不止僵尸。Java 版里村民会被一整族生物吓到 panic，按 Minecraft Wiki
 * 《Villager#Panicking》的记录，恐慌半径分四档：</p>
 *
 * <table>
 *   <tr><th>半径</th><th>生物</th></tr>
 *   <tr><td>8</td><td>僵尸、尸壳、溺尸、僵尸村民、恼鬼</td></tr>
 *   <tr><td>10</td><td>卫道士、僵尸疣猪兽</td></tr>
 *   <tr><td>12</td><td>唤魔者、幻术师、劫掠兽</td></tr>
 *   <tr><td>15</td><td>掠夺者</td></tr>
 * </table>
 *
 * <p>另外两个需要特别说明的：</p>
 * <ul>
 *   <li><b>凋灵</b>：Wiki 正文把它列进「会让村民恐慌」的名单，但
 *       <b>1.21.1 的半径表里没有它</b>。已用字节码核实，因此本档案给它半径 0，
 *       即"不构成恐慌源"，严格模式会直接拒绝。</li>
 *   <li><b>霜冻（frostbite）</b>：2026 年第四个内容更新加入，能把村民变成僵尸村民，
 *       但 1.21.1 表里同样没有。半径 0，同样不构成恐慌源。</li>
 * </ul>
 *
 * <p>除了半径，每个生物还带一组「能不能当长期恐吓源」的工程属性，
 * 这些不是原版判定的一部分，但直接决定一台机器能不能持续跑：</p>
 * <ul>
 *   <li>{@link #sunlightImmune} —— 白天会不会烧死。僵尸会，所以白天必须遮光。</li>
 *   <li>{@link #disarmable} —— 能不能卸掉武器。掠夺者的弩会射死村民和傀儡。</li>
 *   <li>{@link #canBeContained} —— 能不能用船/矿车固定住、避免走位跑出半径。</li>
 *   <li>{@link #breaksBlocks} —— 会不会拆方块。凋灵会，所以不能当室内恐吓源。</li>
 *   <li>{@link #killsVillagers} —— 会不会真的杀死村民。</li>
 * </ul>
 */
public enum ScaryMob {

    // ---- 半径 8 ----
    ZOMBIE("zombie", 8, true, Family.ZOMBIE, false, false, true, false, true),
    HUSK("husk", 8, true, Family.ZOMBIE, true, false, true, false, true),
    DROWNED("drowned", 8, true, Family.ZOMBIE, true, false, true, false, true),
    ZOMBIE_VILLAGER("zombie_villager", 8, true, Family.ZOMBIE, false, false, true, false, true),
    VEX("vex", 8, true, Family.VEX, true, false, false, false, true),

    // ---- 半径 10 ----
    VINDICATOR("vindicator", 10, true, Family.ILLAGER, false, false, true, false, true),
    ZOGLIN("zoglin", 10, true, Family.ZOGLIN, true, false, false, false, true),

    // ---- 半径 12 ----
    EVOKER("evoker", 12, true, Family.ILLAGER, false, false, true, false, true),
    ILLUSIONER("illusioner", 12, true, Family.ILLAGER, false, false, true, false, false),
    RAVAGER("ravager", 12, true, Family.ILLAGER, false, false, false, true, true),

    // ---- 半径 15 ----
    PILLAGER("pillager", 15, true, Family.ILLAGER, false, true, true, false, true),

    // ---- 文档提及但**不构成恐慌源** ----
    /**
     * 凋灵。
     *
     * <p>⚠ 第一轮按 Wiki 正文把它收进枚举并给了 12 格半径，这是错的。
     * 已用 1.21.1 字节码核实：{@code VillagerHostilesSensor} 的
     * {@code ACCEPTABLE_DISTANCE_FROM_HOSTILES} 里**没有 WITHER**。
     * 村民不会被凋灵活活吓到 panic。</p>
     *
     * <p>保留这个枚举项只为两件事：界面上能显示"这只凋灵不会被当成恐吓源"，
     * 以及给模组/数据包留一个可覆盖的锚点。
     * {@link ScaryMobRegistry} 的严格模式会直接拒绝它。</p>
     */
    WITHER("wither", 0, false, Family.WITHER, false, false, false, true, true),

    /**
     * 霜冻。
     *
     * <p>2026 第四个内容更新加入，能把村民变成僵尸村民。
     * 但 1.21.1 的恐慌半径表里没有它；即便后续版本加入，
     * 也应该由本工程对应版本的档案来登记，而不是在这里猜。
     * 因此同样标记为"不构成恐慌源"。</p>
     */
    FROSTBITE("frostbite", 0, false, Family.ZOMBIE, false, false, true, false, true),

    /**
     * 未登记的生物：默认按 8 格保守处理，严格模式下不算恐吓源。
     * 注意这里必须写字面量 8 —— 枚举常量在静态字段 {@link #DEFAULT_RADIUS}
     * 初始化之前就构造完了，引用它会得到 "illegal forward reference"。
     */
    OTHER("other", 8, false, Family.UNKNOWN, true, false, true, false, true);

    public static final int DEFAULT_RADIUS = 8;

    /** 生物所属大类，用于界面分组与「批量替换恐吓源」建议。 */
    public enum Family {
        ZOMBIE("zombie"),
        ILLAGER("illager"),
        VEX("vex"),
        ZOGLIN("zoglin"),
        WITHER("wither"),
        UNKNOWN("unknown");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final String id;
    private final int panicRadius;
    private final boolean radiusVerified;
    private final Family family;
    private final boolean sunlightImmune;
    private final boolean disarmable;
    private final boolean canBeContained;
    private final boolean breaksBlocks;
    private final boolean killsVillagers;

    ScaryMob(
            String id,
            int panicRadius,
            boolean radiusVerified,
            Family family,
            boolean sunlightImmune,
            boolean disarmable,
            boolean canBeContained,
            boolean breaksBlocks,
            boolean killsVillagers) {
        this.id = id;
        this.panicRadius = panicRadius;
        this.radiusVerified = radiusVerified;
        this.family = family;
        this.sunlightImmune = sunlightImmune;
        this.disarmable = disarmable;
        this.canBeContained = canBeContained;
        this.breaksBlocks = breaksBlocks;
        this.killsVillagers = killsVillagers;
    }

    public String id() {
        return id;
    }

    /** 恐慌半径（格，球面距离）。 */
    public int panicRadius() {
        return panicRadius;
    }

    /** 该半径是否有 Wiki 表格直接支持。false 时界面要标注「未验证」。 */
    public boolean radiusVerified() {
        return radiusVerified;
    }

    public Family family() {
        return family;
    }

    /** 白天在阳光下不会烧死。 */
    public boolean sunlightImmune() {
        return sunlightImmune;
    }

    /** 可以卸掉武器（目前只有掠夺者的弩）。 */
    public boolean disarmable() {
        return disarmable;
    }

    /** 可以用船/矿车/命名牌长期固定。 */
    public boolean canBeContained() {
        return canBeContained;
    }

    /** 会破坏方块（凋灵、劫掠兽会撞坏作物但不算拆方块，这里只标凋灵）。 */
    public boolean breaksBlocks() {
        return breaksBlocks;
    }

    /** 会真的杀死村民。 */
    public boolean killsVillagers() {
        return killsVillagers;
    }

    /** 是否为「已登记」的生物（不是兜底的 OTHER）。 */
    public boolean isKnown() {
        return this != OTHER;
    }

    /** 该生物是否会让村民恐慌。
     *  <p>判据是「有没有正半径」—— 半径 0 表示已核实它**不**构成恐慌源
     *  （凋灵、霜冻），未登记的 {@link #OTHER} 也不算。</p> */
    public boolean causesPanic() {
        return panicRadius > 0 && this != OTHER;
    }

    /** 多语言名称键。 */
    public String langKey() {
        return "ironfarm.mob." + id;
    }

    /** 多语言「作为恐吓源」建议键。 */
    public String adviceLangKey() {
        return "ironfarm.mob." + id + ".advice";
    }

    /**
     * 按实体注册名解析，未知返回 {@link #OTHER}。
     * 接受 {@code "minecraft:zombie"} 或 {@code "zombie"} 两种写法。
     */
    public static ScaryMob byEntityId(String entityId) {
        if (entityId == null) {
            return OTHER;
        }
        String s = entityId;
        int colon = s.indexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) {
            s = s.substring(colon + 1);
        }
        for (ScaryMob m : values()) {
            if (m.id.equals(s)) {
                return m;
            }
        }
        return OTHER;
    }

    /** 全部已登记生物（不含 OTHER），供界面遍历。 */
    public static ScaryMob[] knownValues() {
        ScaryMob[] all = values();
        int n = 0;
        for (ScaryMob m : all) {
            if (m.isKnown()) {
                n++;
            }
        }
        ScaryMob[] out = new ScaryMob[n];
        int i = 0;
        for (ScaryMob m : all) {
            if (m.isKnown()) {
                out[i++] = m;
            }
        }
        return out;
    }
}
