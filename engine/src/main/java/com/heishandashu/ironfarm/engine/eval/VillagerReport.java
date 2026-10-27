package com.heishandashu.ironfarm.engine.eval;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.RuleResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一只村民的完整判定报告。
 *
 * <p>同时服务三处：</p>
 * <ol>
 *   <li>头顶三个图标（{@link #hardConditionsMet()}、{@link #panicking()}、{@link #gossipReady()}）；</li>
 *   <li>"村民状态"详情页（{@link #results()} 全表）；</li>
 *   <li>统计页的失败归因（{@link #primaryFailure()}）。</li>
 * </ol>
 */
public final class VillagerReport {

    public final VillagerSnapshot snapshot;
    private final Map<Condition, RuleResult> results;
    private final int panicParticipants;
    private final int gossipParticipants;

    public VillagerReport(
            VillagerSnapshot snapshot,
            Map<Condition, RuleResult> results,
            int panicParticipants,
            int gossipParticipants) {
        this.snapshot = snapshot;
        this.results = results == null
                ? new EnumMap<Condition, RuleResult>(Condition.class)
                : new EnumMap<Condition, RuleResult>(results);
        this.panicParticipants = panicParticipants;
        this.gossipParticipants = gossipParticipants;
    }

    public BlockPos3 pos() {
        return snapshot.pos;
    }

    public int entityId() {
        return snapshot.entityId;
    }

    public RuleResult get(Condition c) {
        RuleResult r = results.get(c);
        return r == null ? RuleResult.unknown(c, "data_missing") : r;
    }

    /** 全部条件结果，按 {@link Condition} 的声明顺序。 */
    public List<RuleResult> results() {
        List<RuleResult> list = new ArrayList<RuleResult>(Condition.values().length);
        for (Condition c : Condition.values()) {
            RuleResult r = results.get(c);
            if (r != null) {
                list.add(r);
            }
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 头顶第一个图标：硬条件是否全部具备。
     *
     * <p>对应需求原文：20 分钟内睡过、30 秒内未识别到铁傀儡。
     * 加上「成年」这一条只是因为界面上要说清楚「这只是婴儿」，
     * 原版靠婴儿没有 LAST_SLEPT 自然失败。</p>
     *
     * <p>⚠ 三态语义：数据不可得时这里返回 <b>false</b>，
     * 但同时 {@link #hardConditionsUnknown()} 为 true。渲染层必须
     * <b>先查 unknown 再查 met</b>，否则多人游戏里每一只村民都会画成红叉。
     * 判断顺序见 {@link #conditionIcon()}。</p>
     */
    public boolean hardConditionsMet() {
        return get(Condition.ADULT).isPass()
                && get(Condition.SLEPT).isPass()
                && get(Condition.NO_GOLEM_DETECTED).isPass();
    }

    /** 硬条件里是否有无法判定的项（界面画半透明叉 / 问号，而不是纯红叉）。 */
    public boolean hardConditionsUnknown() {
        return get(Condition.ADULT).isUnknown()
                || get(Condition.SLEPT).isUnknown()
                || get(Condition.NO_GOLEM_DETECTED).isUnknown();
    }

    /** 头顶第一个图标应该画什么。 */
    public enum Icon {
        /** 绿色对钩：条件具备。 */
        CHECK,
        /** 红色叉：确定不满足。 */
        CROSS,
        /** 灰色问号：数据不可得，无法判定。 */
        UNKNOWN
    }

    /**
     * 第一个图标的权威取值。
     *
     * <p>渲染层只需按这个枚举画，不要在渲染代码里自己拼判断 ——
     * 「先查 unknown 还是先查 met」这个顺序写反了就是满屏假红叉。</p>
     */
    public Icon conditionIcon() {
        if (hardConditionsMet()) {
            return Icon.CHECK;
        }
        return hardConditionsUnknown() ? Icon.UNKNOWN : Icon.CROSS;
    }

    /** 第三个图标（喇叭）应该画什么。 */
    public Icon gossipIcon() {
        if (gossipReady()) {
            return Icon.CHECK;
        }
        // 闲聊人数依赖邻居数据；硬条件 unknown 时也不该给红叉
        if (hardConditionsUnknown() || get(Condition.GOSSIP_GROUP).isUnknown()) {
            return Icon.UNKNOWN;
        }
        return Icon.CROSS;
    }

    /** 头顶第二个图标：骷髅 —— 是否处于恐慌。 */
    public boolean panicking() {
        return get(Condition.PANICKING).isPass();
    }

    /**
     * 头顶第三个图标：喇叭 —— 是否具备闲聊刷铁傀儡的基础。
     *
     * <p>"基础" = 硬条件达标 且 闲聊人数达标。喇叭只反映闲聊路径，
     * 与恐慌路径是否成立无关。</p>
     */
    public boolean gossipReady() {
        return get(Condition.GOSSIP_GROUP).isPass() && hardConditionsMet();
    }

    /** 恐慌路径当前是否完全成立（含生成点）。 */
    public boolean panicFarmReady() {
        return hardConditionsMet()
                && get(Condition.PANICKING).isPass()
                && get(Condition.PANIC_GROUP).isPass()
                && get(Condition.SPAWN_POINT).isPass();
    }

    /** 闲聊路径当前是否完全成立（含生成点）。 */
    public boolean gossipFarmReady() {
        return hardConditionsMet()
                && get(Condition.GOSSIPING).isPass()
                && get(Condition.GOSSIP_GROUP).isPass()
                && get(Condition.SPAWN_POINT).isPass();
    }

    public int panicParticipants() {
        return panicParticipants;
    }

    public int gossipParticipants() {
        return gossipParticipants;
    }

    /** 两条路径共用的条件，按归因优先级排序。 */
    private static final Condition[] COMMON_ORDER = {
            Condition.ADULT,
            Condition.SLEPT,
            Condition.NO_GOLEM_DETECTED
    };

    /**
     * 失败归因：返回第一条**与该村民实际走的路径相关**的失败条件。
     *
     * <p>必须绕开的陷阱：两条路径的触发条件天然互斥 —— 一只正在被恐吓的村民，
     * 它的 {@code GOSSIPING} 条件永远失败。直接按枚举顺序取第一个失败，
     * 会让一台**完全正常的恐吓机**一直报"失败原因：没有在闲聊"。</p>
     */
    public RuleResult primaryFailure() {
        if (panicFarmReady() || gossipFarmReady()) {
            return null;
        }
        for (Condition c : COMMON_ORDER) {
            RuleResult r = results.get(c);
            if (r != null && r.isFail()) {
                return r;
            }
        }
        boolean panicking = get(Condition.PANICKING).isPass();
        boolean gossiping = get(Condition.GOSSIPING).isPass();

        if (panicking) {
            RuleResult r = results.get(Condition.PANIC_GROUP);
            if (r != null && r.isFail()) {
                return r;
            }
        } else if (gossiping) {
            RuleResult r = results.get(Condition.GOSSIP_GROUP);
            if (r != null && r.isFail()) {
                return r;
            }
        } else {
            // 两条路径都没触发 —— 最常见情形。报"没有处于恐慌"，
            // 因为这是刷铁机最关心的一条。
            RuleResult r = results.get(Condition.PANICKING);
            if (r != null && r.isFail()) {
                return r;
            }
        }
        RuleResult spawn = results.get(Condition.SPAWN_POINT);
        if (spawn != null && spawn.isFail()) {
            return spawn;
        }
        return null;
    }

    public String primaryFailureCode() {
        RuleResult r = primaryFailure();
        return r == null ? null : r.reasonCode;
    }

    public boolean hasUnknown() {
        for (RuleResult r : results.values()) {
            if (r.isUnknown()) {
                return true;
            }
        }
        return false;
    }

    public Map<Condition, RuleResult> rawResults() {
        return Collections.unmodifiableMap(results);
    }

    @Override
    public String toString() {
        return "VillagerReport{#" + entityId()
                + " hard=" + (hardConditionsMet() ? "OK" : "NO")
                + " panic=" + panicking()
                + " gossip=" + (gossipReady() ? "OK" : "NO")
                + " fail=" + primaryFailureCode() + "}";
    }
}
