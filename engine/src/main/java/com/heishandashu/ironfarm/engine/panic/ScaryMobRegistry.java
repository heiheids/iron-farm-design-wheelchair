package com.heishandashu.ironfarm.engine.panic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 恐吓源注册表：决定「这个实体算不算能让村民恐慌的怪物、半径是多少」。
 *
 * <p>需求是「只要是可以恐吓村民的怪物，都要做好适配，不止僵尸」。
 * 原版那一族生物在 {@link ScaryMob} 里已经登记全了，但还有两类需要处理：</p>
 *
 * <ol>
 *   <li><b>模组生物。</b>工程师不可能预先知道每个整合包里有哪些怪。
 *       所以这里允许适配层在运行时注册：
 *       {@code registry.register("somemod:scary_thing", 8)}。</li>
 *   <li><b>行为像怪物但原版表里没有的。</b>比如某些模组加的「敌对村民的怪」。
 *       适配层可以从游戏的 {@code Monster} 接口 / {@code targeting} 判断出
 *       「它对村民敌对」，把这条线索作为 {@code hostileToVillagers} 传进来，
 *       由注册表按 {@link #strictMode} 决定要不要采信。</li>
 * </ol>
 *
 * <p><b>严格模式</b>（默认开启）：只有登记过的生物才算恐吓源。
 * 这样界面不会因为一只路过的蝙蝠就画出一条"恐吓线"。
 * 关掉之后，任何「对村民敌对」的生物都会按 8 格保守处理。</p>
 */
public final class ScaryMobRegistry {

    /** 自定义半径表：注册名 → 半径。 */
    private final Map<String, Integer> customRadii = new LinkedHashMap<String, Integer>();
    /** 自定义生物的家族归属，用于界面分组与建议。 */
    private final Map<String, ScaryMob.Family> customFamilies = new LinkedHashMap<String, ScaryMob.Family>();

    /**
     * 严格模式：只有登记过的生物才算恐吓源。
     * 关掉后，未登记但「对村民敌对」的生物按 {@link ScaryMob#DEFAULT_RADIUS} 处理。
     */
    public boolean strictMode = true;

    /** 默认注册表（只认原版表里的生物）。 */
    public static ScaryMobRegistry vanilla() {
        return new ScaryMobRegistry();
    }

    /**
     * 注册一个模组生物。
     *
     * @param entityId 注册名，形如 {@code "somemod:scary_thing"}
     * @param radius   恐慌半径；传 null 或 ≤0 表示沿用 {@link ScaryMob#OTHER} 的默认值
     */
    public ScaryMobRegistry register(String entityId, Integer radius) {
        return register(entityId, radius, null);
    }

    /** 注册并指定家族。 */
    public ScaryMobRegistry register(String entityId, Integer radius, ScaryMob.Family family) {
        if (entityId == null || entityId.isEmpty()) {
            return this;
        }
        String key = normalize(entityId);
        customRadii.put(key, radius == null ? Integer.valueOf(ScaryMob.DEFAULT_RADIUS) : radius);
        if (family != null) {
            customFamilies.put(key, family);
        }
        return this;
    }

    /** 该注册名是否被显式登记过（原版内置或自定义都算）。 */
    public boolean isRegistered(String entityId) {
        if (entityId == null) {
            return false;
        }
        String key = normalize(entityId);
        return customRadii.containsKey(key) || ScaryMob.byEntityId(key).isKnown();
    }

    /** 自定义半径，没有则返回 null。 */
    public Integer customRadiusOf(String entityId) {
        if (entityId == null) {
            return null;
        }
        return customRadii.get(normalize(entityId));
    }

    public ScaryMob.Family familyOf(String entityId) {
        if (entityId == null) {
            return ScaryMob.Family.UNKNOWN;
        }
        String key = normalize(entityId);
        ScaryMob.Family f = customFamilies.get(key);
        if (f != null) {
            return f;
        }
        return ScaryMob.byEntityId(key).family();
    }

    /**
     * 判定一个实体是否算恐吓源，并给出实际使用的半径。
     *
     * @param entityId            实体注册名
     * @param hostileToVillagers  适配层判断的「该生物是否对村民敌对」；
     *                            不知道时传 false（不要把"未知"当成"是"）
     * @return 判定结果；{@link Resolution#causesPanic} 为 false 时不应画线、不应计入 panic
     */
    public Resolution resolve(String entityId, boolean hostileToVillagers) {
        if (entityId == null) {
            return Resolution.NONE;
        }
        String key = normalize(entityId);

        // 1) 自定义登记优先（玩家/整合包明确指定过）
        Integer custom = customRadii.get(key);
        if (custom != null) {
            ScaryMob base = ScaryMob.byEntityId(key);
            int radius = custom.intValue() > 0 ? custom.intValue() : ScaryMob.DEFAULT_RADIUS;
            return new Resolution(true, radius, base == ScaryMob.OTHER || !base.radiusVerified(),
                    base == ScaryMob.OTHER ? ScaryMob.OTHER : base);
        }

        // 2) 原版表
        ScaryMob known = ScaryMob.byEntityId(key);
        if (known.isKnown()) {
            // ⚠ 已登记但半径为 0 表示"已核实它不构成恐慌源"（凋灵、霜冻）。
            //   必须直接拒绝，不能给出 Resolution(true, 0, ...) ——
            //   那会让 PanicResolver 用一个 0 格半径去算，结果就是"永远不够近"，
            //   界面上表现为一条始终不生效的虚线，比不画更容易误导人。
            if (known.panicRadius() <= 0) {
                return Resolution.NONE;
            }
            return new Resolution(true, known.panicRadius(), !known.radiusVerified(), known);
        }

        // 3) 未登记
        if (!strictMode && hostileToVillagers) {
            return new Resolution(true, ScaryMob.DEFAULT_RADIUS, true, ScaryMob.OTHER);
        }
        return Resolution.NONE;
    }

    /**
     * 便捷重载：按「不知道是否敌对」处理。
     * 严格模式下结果与 {@code hostileToVillagers=false} 相同。
     */
    public Resolution resolve(String entityId) {
        return resolve(entityId, false);
    }

    /** 判定结果。 */
    public static final class Resolution {
        /** 是否算恐吓源。 */
        public final boolean causesPanic;
        /** 实际使用的半径（格）。 */
        public final int radius;
        /** 半径是否是「推断/未验证」的，界面要标注。 */
        public final boolean inferred;
        /** 归属的基础档案；自定义生物为 {@link ScaryMob#OTHER}。 */
        public final ScaryMob base;

        public static final Resolution NONE = new Resolution(false, 0, false, ScaryMob.OTHER);

        Resolution(boolean causesPanic, int radius, boolean inferred, ScaryMob base) {
            this.causesPanic = causesPanic;
            this.radius = radius;
            this.inferred = inferred;
            this.base = base;
        }

        @Override
        public String toString() {
            return causesPanic
                    ? "Resolution{" + base.id() + " r=" + radius + (inferred ? " inferred" : "") + "}"
                    : "Resolution{none}";
        }
    }

    /** 已登记的全部自定义注册名（供设置页列出）。 */
    public List<String> customEntityIds() {
        return new ArrayList<String>(customRadii.keySet());
    }

    /** 清空自定义登记（回到纯原版）。 */
    public ScaryMobRegistry clearCustom() {
        customRadii.clear();
        customFamilies.clear();
        return this;
    }

    private static String normalize(String entityId) {
        String s = entityId.trim();
        int colon = s.indexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) {
            s = s.substring(colon + 1);
        }
        return s;
    }

    @Override
    public String toString() {
        return "ScaryMobRegistry{strict=" + strictMode + " custom=" + customRadii.size() + "}";
    }
}
