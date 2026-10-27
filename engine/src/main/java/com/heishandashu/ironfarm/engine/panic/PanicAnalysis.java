package com.heishandashu.ironfarm.engine.panic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次恐吓分析的完整结果。
 *
 * <p>除了连线本身，还回答两个刷铁机玩家真正关心的问题：</p>
 * <ul>
 *   <li><b>哪只怪物是主力恐吓源</b>（吓到的村民最多）；</li>
 *   <li><b>哪只怪物虽然在范围内却完全没生效</b>（视线被挡、或被挤出了半径）。
 *       这是「僵尸明明在旁边，村民却不 panic」最常见的两个原因。</li>
 * </ul>
 */
public final class PanicAnalysis {

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
        this.registry = registry == null ? ScaryMobRegistry.vanilla() : registry;
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

    /** 距离不够的连线 —— 界面画虚线并提示「再近 X 格」。 */
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
            if (e.getValue().intValue() > bestN) {
                bestN = e.getValue().intValue();
                best = e.getKey().intValue();
            }
        }
        return best;
    }

    /** 一只村民都没吓到的怪物。 */
    public List<Integer> idleMobs(Collection<Integer> allMobIds) {
        List<Integer> out = new ArrayList<Integer>();
        if (allMobIds == null) {
            return out;
        }
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

    /** 把每只村民的 panic 状态一次性算出，返回 entityId → 是否 panic。 */
    public Map<Integer, Boolean> allVillagerStates() {
        Map<Integer, Boolean> m = new LinkedHashMap<Integer, Boolean>();
        for (PanicLink l : links) {
            Boolean cur = m.get(l.villagerEntityId);
            m.put(l.villagerEntityId, Boolean.valueOf(
                    (cur != null && cur.booleanValue()) || l.effective));
        }
        return m;
    }

    @Override
    public String toString() {
        return "PanicAnalysis{links=" + links.size()
                + " effective=" + totalEffectiveLinks()
                + " mobsScaring=" + scareCountByMob.size() + "}";
    }
}
