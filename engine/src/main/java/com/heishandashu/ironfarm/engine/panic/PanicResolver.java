package com.heishandashu.ironfarm.engine.panic;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 恐吓关系求解器：算出「哪只怪物正在恐吓哪只村民」。
 *
 * <p>这是设置项 5 的数据来源，同时也是 panic 判定的权威实现 ——
 * 界面上画的线，和引擎里判定 {@code PANICKING} 用的是同一份计算结果，
 * 不会出现「线画着但没有 panic」或反过来。</p>
 *
 * <p><b>支持全部恐吓源，不止僵尸。</b>半径由 {@link ScaryMobRegistry} 决定：
 * 僵尸家族 8 格、卫道士/僵尸疣猪兽 10 格、唤魔者/幻术师/劫掠兽/凋灵 12 格、
 * 掠夺者 15 格，模组生物可由整合包自行注册。</p>
 *
 * <p>判定条件（缺一不可）：</p>
 * <ol>
 *   <li>该生物算恐吓源（严格模式下必须是登记过的种类）；</li>
 *   <li>球面距离 ≤ 该生物的恐慌半径；</li>
 *   <li>眼对眼视线无遮挡。</li>
 * </ol>
 *
 * <p>怪物是否 persistent 不影响「此刻是否 panic」，但影响机器能否持续；
 * 这一点由 {@link PanicAnalysis} 汇总后交给界面提示。</p>
 */
public final class PanicResolver {

    /** 成年村民眼睛高度。 */
    public static final double VILLAGER_EYE_HEIGHT = 1.62;
    /** 僵尸类眼睛高度（僵尸/尸壳/溺尸/僵尸村民通用近似值）。 */
    public static final double ZOMBIE_EYE_HEIGHT = 1.74;

    private final ScaryMobRegistry registry;

    /** 用默认注册表（只认原版表）。 */
    public PanicResolver() {
        this(ScaryMobRegistry.vanilla());
    }

    public PanicResolver(ScaryMobRegistry registry) {
        this.registry = registry == null ? ScaryMobRegistry.vanilla() : registry;
    }

    public ScaryMobRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------
    // 静态便捷入口（用原版注册表）
    // ------------------------------------------------------------------

    /** 用原版注册表求解，只要连线列表。 */
    public static List<PanicLink> resolveLinks(
            Collection<VillagerSnapshot> villagers,
            Collection<ScaryMobSnapshot> mobs,
            SightQuery sight) {
        return new PanicResolver().analyze(villagers, mobs, sight).links;
    }

    /** 用原版注册表求解，返回完整分析（含主力恐吓源、失效原因）。 */
    public static PanicAnalysis resolve(
            Collection<VillagerSnapshot> villagers,
            Collection<ScaryMobSnapshot> mobs,
            SightQuery sight) {
        return new PanicResolver().analyze(villagers, mobs, sight);
    }

    /** 只取生效的连线。 */
    public static List<PanicLink> effectiveLinks(List<PanicLink> all, int villagerEntityId) {
        List<PanicLink> out = new ArrayList<PanicLink>();
        for (PanicLink l : all) {
            if (l.villagerEntityId == villagerEntityId && l.effective) {
                out.add(l);
            }
        }
        return out;
    }

    /** 用连线结果判断某村民是否 panic。 */
    public static boolean derivePanicking(List<PanicLink> links, int villagerEntityId) {
        for (PanicLink l : links) {
            if (l.villagerEntityId == villagerEntityId && l.effective) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 实例入口
    // ------------------------------------------------------------------

    /**
     * 完整分析：连线 + 恐吓源体检。
     *
     * @param sight 视线查询；传 null 时按「全部可见」处理（无世界环境的预览）
     */
    public PanicAnalysis analyze(
            Collection<VillagerSnapshot> villagers,
            Collection<ScaryMobSnapshot> mobs,
            SightQuery sight) {
        if (villagers == null || villagers.isEmpty() || mobs == null || mobs.isEmpty()) {
            return PanicAnalysis.empty();
        }

        List<PanicLink> links = new ArrayList<PanicLink>();
        // 每只怪物被多少村民"看见"——用于找出机器里真正的恐吓源
        java.util.Map<Integer, Integer> scareCount = new java.util.LinkedHashMap<Integer, Integer>();
        // 每只怪物因为什么原因没生效，聚合后给界面提示
        java.util.Map<Integer, String> inactiveReason = new java.util.LinkedHashMap<Integer, String>();

        for (VillagerSnapshot v : villagers) {
            for (ScaryMobSnapshot m : mobs) {
                ScaryMobRegistry.Resolution res = registry.resolve(m.typeId, m.hostileToVillagers);
                if (!res.causesPanic) {
                    // 不算恐吓源 —— 不画线、不计入 panic
                    continue;
                }
                int radius = res.radius;
                double d = distance(v.pos, m.pos);
                boolean inRange = d <= radius;
                boolean clear = sight == null || sight.clearLineOfSight(
                        v.pos, VILLAGER_EYE_HEIGHT, m.pos, m.eyeHeight > 0 ? m.eyeHeight : eyeHeightOf(m.kind));
                boolean effective = inRange && clear;

                links.add(new PanicLink(
                        v.entityId, m.entityId, m.kind, m.typeId,
                        d, radius, clear, effective, res.inferred));

                if (effective) {
                    Integer n = scareCount.get(m.entityId);
                    scareCount.put(m.entityId, n == null ? 1 : n + 1);
                } else {
                    // 记录第一次失败原因即可（同一只怪物对多只村民的原因通常相同）
                    if (!inactiveReason.containsKey(m.entityId)) {
                        inactiveReason.put(m.entityId, !inRange ? "too_far" : "no_sight");
                    }
                }
            }
        }

        return new PanicAnalysis(links, scareCount, inactiveReason, registry);
    }

    /** 两只生物脚部中心（含 0.5 偏移）之间的球面距离。 */
    static double distance(BlockPos3 a, BlockPos3 b) {
        double dx = (a.x + 0.5) - (b.x + 0.5);
        double dy = (double) a.y - (double) b.y;
        double dz = (a.z + 0.5) - (b.z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 各类生物的眼睛高度。 */
    public static double eyeHeightOf(ScaryMob kind) {
        if (kind == null) {
            return ZOMBIE_EYE_HEIGHT;
        }
        switch (kind) {
            case PILLAGER:
            case VINDICATOR:
            case EVOKER:
            case ILLUSIONER:
                return 1.62;
            case RAVAGER:
                return 1.95;
            case VEX:
                return 0.72;
            case WITHER:
                return 1.62;
            case DROWNED:
            case ZOMBIE:
            case HUSK:
            case ZOMBIE_VILLAGER:
            case ZOGLIN:
            case FROSTBITE:
            default:
                return ZOMBIE_EYE_HEIGHT;
        }
    }

    /** 空结果。 */
    public static PanicAnalysis emptyAnalysis() {
        return PanicAnalysis.empty();
    }

    /** 便于测试：给定村民与怪物，返回直接的距离（无视线判定）。 */
    public static double rawDistance(BlockPos3 villager, BlockPos3 mob) {
        return distance(villager, mob);
    }
}
