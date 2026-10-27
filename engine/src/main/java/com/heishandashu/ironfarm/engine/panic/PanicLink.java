package com.heishandashu.ironfarm.engine.panic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条「村民被某只怪物恐吓」的连线。
 *
 * <p>对应设置项 5：彩色线条指示僵尸正在恐吓哪些村民，
 * **距离不够生效的线画成虚线**。所以本对象必须同时携带三件事：</p>
 * <ul>
 *   <li>球面距离是多少、该怪物的半径是多少（决定是否够近）；</li>
 *   <li>视线是否通（够近但没视线 = 不生效）；</li>
 *   <li>最终是否生效（{@link #effective}）。</li>
 * </ul>
 *
 * <p>只有 {@link #effective} 为 true 时，村民才真的会 panic。</p>
 */
public final class PanicLink {

    public final int villagerEntityId;
    public final int mobEntityId;
    /** 已解析的生物档案（未登记为 {@link ScaryMob#OTHER}）。 */
    public final ScaryMob kind;
    /** 实体注册名，用于多语言与模组生物显示。 */
    public final String typeId;
    /** 村民脚部中心 → 怪物脚部中心的球面距离。 */
    public final double distance;
    /** 该怪物对应的恐慌半径。 */
    public final int radius;
    /** 是否无遮挡可见。 */
    public final boolean sightClear;
    /** 最终是否生效：距离够 + 视线通。 */
    public final boolean effective;
    /** 半径是否为推断值（未在 Wiki 表中验证 / 模组自定义），界面需标注。 */
    public final boolean inferredRadius;

    public PanicLink(
            int villagerEntityId,
            int mobEntityId,
            ScaryMob kind,
            String typeId,
            double distance,
            int radius,
            boolean sightClear,
            boolean effective,
            boolean inferredRadius) {
        this.villagerEntityId = villagerEntityId;
        this.mobEntityId = mobEntityId;
        this.kind = kind;
        this.typeId = typeId;
        this.distance = distance;
        this.radius = radius;
        this.sightClear = sightClear;
        this.effective = effective;
        this.inferredRadius = inferredRadius;
    }

    /** 距离在半径内，只是因为没视线才不生效 —— 界面画虚线。 */
    public boolean blockedOnlyBySight() {
        return !effective && distance <= radius;
    }

    /** 距离超出半径 —— 界面画虚线，并提示"再近多少格"。 */
    public boolean blockedByDistance() {
        return !effective && distance > radius;
    }

    /** 还差多少格才够到半径（已在范围内返回 0）。 */
    public double shortfall() {
        double d = distance - radius;
        return d > 0 ? d : 0;
    }

    /** 该连线不生效的原因代码，生效时为 null。 */
    public String inactiveReason() {
        if (effective) {
            return null;
        }
        return blockedByDistance() ? "too_far" : "no_sight";
    }

    /** 生物名称的多语言键。 */
    public String mobLangKey() {
        return kind.isKnown() ? kind.langKey() : "ironfarm.mob.custom";
    }

    @Override
    public String toString() {
        return "PanicLink{ villager#" + villagerEntityId + " <- " + typeId + "#" + mobEntityId
                + " d=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
                + "/" + radius
                + (sightClear ? " sight=OK" : " sight=BLOCKED")
                + (effective ? " EFFECTIVE" : " INACTIVE")
                + (inferredRadius ? " (inferred)" : "") + "}";
    }

    // ==================================================================
    // 分析结果
    // ==================================================================

    /**
     * 一次完整分析的结果。
     *
     * <p>除了连线本身，还回答两个刷铁机玩家真正关心的问题：</p>
     * <ul>
     *   <li><b>哪只怪物是主力恐吓源</b>（吓到的村民最多）；</li>
     *   <li><b>哪只怪物虽然在范围内却完全没生效</b>（视线被挡、或被挤出了半径），
     *       这是"僵尸明明在旁边但村民不 panic"的常见原因。</li>
     * </ul>
     */
    public static final class PanicAnalysis {
        public final List<PanicLink> links;
        /** 怪物 entityId → 被它吓到的村民数量。 */
        public final Map<Integer, Integer> scareCountByMob;
        /** 怪物 entityId → 未生效原因（too_far / no_sight）。 */
        public final Map<Integer, String> inactiveReasonByMob;
        private final ScaryMobRegistry registry;

        PanicAnalysis(
                List<PanicLink> links,
                Map<Integer, Integer> scareCountByMob,
                Map<Integer, String> inactiveReasonByMob,
                ScaryMobRegistry registry) {
            this.links = Collections.unmodifiableList(new ArrayList<PanicLink>(links));
            this.scareCountByMob = Collections.unmodifiableMap(
                    new LinkedHashMap<Integer, Integer>(scareCountByMob));
            this.inactiveReasonByMob = Collections.unmodifiableMap(
                    new LinkedHashMap<Integer, String>(inactiveReasonByMob));
            this.registry = registry;
        }

        static PanicAnalysis empty() {
            return new PanicAnalysis(new ArrayList<PanicLink>(),
                    new LinkedHashMap<Integer, Integer>(),
                    new LinkedHashMap<Integer, String>(),
                    ScaryMobRegistry.vanilla());
        }

        public ScaryMobRegistry registry() {
            return registry;
        }

        public boolean hasAnyLink() {
            return !links.isEmpty();
        }

        public List<PanicLink> effective() {
            List<PanicLink> out = new ArrayList<PanicLink>();
            for (PanicLink l : links) {
                if (l.effective) {
                    out.add(l);
                }
            }
            return out;
        }

        /** 没生效但距离够的连线（视线被挡）—— 界面画虚线并提示。 */
        public List<PanicLink> blockedBySight() {
            List<PanicLink> out = new ArrayList<PanicLink>();
            for (PanicLink l : links) {
                if (l.blockedOnlyBySight()) {
                    out.add(l);
                }
            }
            return out;
        }

        /** 距离不够的连线。 */
        public List<PanicLink> blockedByDistance() {
            List<PanicLink> out = new ArrayList<PanicLink>();
            for (PanicLink l : links) {
                if (l.blockedByDistance()) {
                    out.add(l);
                }
            }
            return out;
        }

        /** 某只村民当前是否 panic。 */
        public boolean isPanicking(int villagerEntityId) {
            for (PanicLink l : links) {
                if (l.villagerEntityId == villagerEntityId && l.effective) {
                    return true;
                }
            }
            return false;
        }

        /** 某只村民被几只怪物同时吓到。 */
        public int scareCountOf(int villagerEntityId) {
            int n = 0;
            for (PanicLink l : links) {
                if (l.villagerEntityId == villagerEntityId && l.effective) {
                    n++;
                }
            }
            return n;
        }

        /** 吓到村民最多的怪物 id；没有则返回 -1。 */
        public int primaryScarer() {
            int best = -1;
            int bestN = 0;
            for (Map.Entry<Integer, Integer> e : scareCountByMob.entrySet()) {
                if (e.getValue() > bestN) {
                    bestN = e.getValue();
                    best = e.getKey();
                }
            }
            return best;
        }

        /** 一只村民都没吓到的怪物（在范围内但视线全被挡的也算）。 */
        public List<Integer> idleMobs(Collection<Integer> allMobIds) {
            List<Integer> out = new ArrayList<Integer>();
            for (Integer id : allMobIds) {
                if (!scareCountByMob.containsKey(id)) {
                    out.add(id);
                }
            }
            return out;
        }

        public int totalEffectiveLinks() {
            int n = 0;
            for (PanicLink l : links) {
                if (l.effective) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public String toString() {
            return "PanicAnalysis{links=" + links.size()
                    + " effective=" + totalEffectiveLinks()
                    + " mobsScaring=" + scareCountByMob.size() + "}";
        }
    }
}
