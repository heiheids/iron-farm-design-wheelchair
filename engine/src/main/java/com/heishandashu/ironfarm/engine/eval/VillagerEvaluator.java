package com.heishandashu.ironfarm.engine.eval;

import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.ReasonCodes;
import com.heishandashu.ironfarm.engine.rule.RuleResult;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 判定器的实际实现。
 *
 * <p><b>严格照抄 1.21.1 的真实逻辑</b>，而不是按 Wiki 措辞重述。
 * 原版两个方法的等价伪代码如下：</p>
 *
 * <pre>
 * boolean golemSpawnConditionsMet(now) {
 *     Optional&lt;Long&gt; lastSlept = brain.getMemory(LAST_SLEPT);
 *     if (!lastSlept.isPresent()) return false;
 *     return now - lastSlept.get() &lt; 24000;
 * }
 *
 * boolean wantsToSpawnGolem(now) {
 *     return golemSpawnConditionsMet(now)
 *            &amp;&amp; !brain.hasMemoryValue(GOLEM_DETECTED_RECENTLY);
 * }
 *
 * void spawnGolemIfNeeded(level, now, required) {
 *     if (!wantsToSpawnGolem(now)) return;
 *     List&lt;Villager&gt; neighbours =
 *         level.getEntitiesOfClass(Villager.class, getBoundingBox().inflate(10,10,10));
 *     long qualified = neighbours.stream()
 *         .filter(v -&gt; v.wantsToSpawnGolem(now))
 *         .limit(5)
 *         .count();
 *     if (qualified &lt; required) return;
 *     SpawnUtil.trySpawnMob(IRON_GOLEM, MOB_SUMMONED, level,
 *                           blockPosition(), 10 attempts, 8 hRange, 6 vRange, LEGACY_IRON_GOLEM);
 * }
 * </pre>
 *
 * <p>由此得到三个必须尊重的结论：</p>
 * <ol>
 *   <li>个体条件只有两条（睡眠 + 无傀儡记忆）。</li>
 *   <li>参与者也必须独立满足这两条，但**不要求**参与者也在恐慌/闲聊。</li>
 *   <li>恐慌/闲聊只决定"谁来发起"和"门槛是 3 还是 5"。</li>
 * </ol>
 */
final class VillagerEvaluator {

    private VillagerEvaluator() {
    }

    static List<VillagerReport> evaluateAll(
            Collection<VillagerSnapshot> villagers,
            long now,
            VersionProfile profile,
            SpawnScanResult spawnScan) {
        if (villagers == null || villagers.isEmpty()) {
            return Collections.emptyList();
        }
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>(villagers);
        List<VillagerReport> out = new ArrayList<VillagerReport>(all.size());
        for (VillagerSnapshot v : all) {
            out.add(evaluate(v, all, now, profile, spawnScan));
        }
        return out;
    }

    static VillagerReport evaluate(
            VillagerSnapshot v,
            Collection<VillagerSnapshot> all,
            long now,
            VersionProfile profile,
            SpawnScanResult spawnScan) {
        Map<Condition, RuleResult> m = new EnumMap<Condition, RuleResult>(Condition.class);

        // ---- 1. 个体门槛：与原版 wantsToSpawnGolem 完全一致 ----
        m.put(Condition.ADULT, v.adult
                ? RuleResult.pass(Condition.ADULT)
                : RuleResult.fail(Condition.ADULT, ReasonCodes.NOT_ADULT));

        m.put(Condition.SLEPT, evalSlept(v, now, profile));

        m.put(Condition.NO_GOLEM_DETECTED, evalNoGolem(v));

        // ---- 2. 触发状态：只对"发起者"有意义 ----
        m.put(Condition.PANICKING, v.panicking
                ? RuleResult.pass(Condition.PANICKING)
                : RuleResult.fail(Condition.PANICKING, ReasonCodes.NOT_PANICKING));
        m.put(Condition.GOSSIPING, v.gossiping
                ? RuleResult.pass(Condition.GOSSIPING)
                : RuleResult.fail(Condition.GOSSIPING, ReasonCodes.NOT_GOSSIPING));

        // ---- 3. 群体门槛：参与者必须个体合格，但不要求在恐慌/闲聊 ----
        int panicCount = countQualified(v, all, now, profile);
        int gossipCount = countQualified(v, all, now, profile);

        m.put(Condition.PANIC_GROUP, panicCount >= profile.panicParticipantThreshold
                ? RuleResult.pass(Condition.PANIC_GROUP, panicCount, profile.panicParticipantThreshold)
                : RuleResult.fail(Condition.PANIC_GROUP, ReasonCodes.PANIC_GROUP_TOO_SMALL,
                        panicCount, profile.panicParticipantThreshold));

        m.put(Condition.GOSSIP_GROUP, gossipCount >= profile.gossipParticipantThreshold
                ? RuleResult.pass(Condition.GOSSIP_GROUP, gossipCount, profile.gossipParticipantThreshold)
                : RuleResult.fail(Condition.GOSSIP_GROUP, ReasonCodes.GOSSIP_GROUP_TOO_SMALL,
                        gossipCount, profile.gossipParticipantThreshold));

        // ---- 4. 生成点 ----
        m.put(Condition.SPAWN_POINT, evalSpawnPoint(spawnScan, profile));

        return new VillagerReport(v, m, panicCount, gossipCount);
    }

    // ------------------------------------------------------------------

    /**
     * 原版 {@code golemSpawnConditionsMet}：必须有 LAST_SLEPT，且 now - lastSlept < 24000。
     *
     * <p>注意是严格小于 —— 正好 24000 时原版返回 false。</p>
     *
     * <p>⚠ 数据不可得时必须返回 UNKNOWN，不能返回 FAIL。
     * 多人游戏里客户端读不到 {@code LAST_SLEPT}，
     * 若当成"从没睡过"就会给每一只村民画红叉。</p>
     */
    private static RuleResult evalSlept(VillagerSnapshot v, long now, VersionProfile profile) {
        if (!v.sleepDataKnown) {
            return RuleResult.unknown(Condition.SLEPT, ReasonCodes.DATA_MISSING);
        }
        if (v.lastSleptGameTime == VillagerSnapshot.NEVER) {
            return RuleResult.fail(Condition.SLEPT, ReasonCodes.NEVER_SLEPT,
                    Long.MAX_VALUE, profile.sleepWindowTicks);
        }
        long since = now - v.lastSleptGameTime;
        if (since < 0) {
            // 时间倒流（换维度/读档），按刚睡过处理，不要判成失败
            since = 0;
        }
        if (since >= profile.sleepWindowTicks) {
            return RuleResult.fail(Condition.SLEPT, ReasonCodes.SLEEP_EXPIRED,
                    since, profile.sleepWindowTicks);
        }
        return RuleResult.pass(Condition.SLEPT, since, profile.sleepWindowTicks);
    }

    /**
     * 原版 {@code wantsToSpawnGolem} 的第二条：没有 {@code GOLEM_DETECTED_RECENTLY} 记忆。
     *
     * <p>⚠ 数据不可得时同样必须 UNKNOWN。拿不到记忆时这个布尔量恒为 false，
     * 直接采信会显示一个**错误的绿勾**，比红叉更危险。</p>
     */
    private static RuleResult evalNoGolem(VillagerSnapshot v) {
        if (!v.golemMemoryKnown) {
            return RuleResult.unknown(Condition.NO_GOLEM_DETECTED, ReasonCodes.DATA_MISSING);
        }
        return v.golemDetectedRecently
                ? RuleResult.fail(Condition.NO_GOLEM_DETECTED, ReasonCodes.GOLEM_DETECTED)
                : RuleResult.pass(Condition.NO_GOLEM_DETECTED);
    }

    /**
     * 原版 {@code spawnGolemIfNeeded} 的邻居统计：
     * 包围盒膨胀 10 格内的村民，逐个调用 {@code wantsToSpawnGolem}，
     * 合格者计数，且上限 5 个。
     *
     * <p>⚠ 三个细节：</p>
     * <ul>
     *   <li>范围是**以村民包围盒为基础膨胀 10 格**，不是"脚的方块坐标 ±10"。
     *       村民本身宽 0.6、高 1.95，所以实际 AABB 是
     *       x±(10+0.3), y-10..y+10+1.95, z±(10+0.3)。这里用方块坐标 ±10 近似，
     *       误差不超过 1 格，并在文档里标注。</li>
     *   <li>统计**包含自己**（{@code getEntitiesOfClass} 会返回自己吗？
     *       不会 —— 原版排除自己。但自己已经通过 wantsToSpawnGolem 检查才会走到这里，
     *       所以自己不算进 qualified 计数）。这一点很多人搞错。
     *       本实现**不把自己算进计数**，与原版一致。</li>
     *   <li>{@code limit(5)} 是上限：闲聊门槛正好是 5，
     *       所以闲聊路径要求全部 5 个名额都合格。</li>
     * </ul>
     */
    private static int countQualified(
            VillagerSnapshot self,
            Collection<VillagerSnapshot> all,
            long now,
            VersionProfile profile) {
        int n = 0;
        int r = profile.summonSpreadInflate;
        for (VillagerSnapshot o : all) {
            if (o.entityId == self.entityId) {
                continue; // 原版不把自己算进邻居列表
            }
            if (!o.pos.withinAxis(self.pos, r, r, r)) {
                continue;
            }
            if (!individualOk(o, now, profile)) {
                continue;
            }
            n++;
            if (n >= profile.participantLimit) {
                break; // 原版 Stream.limit(5)
            }
        }
        return n;
    }

    /** 原版 {@code wantsToSpawnGolem} 的布尔等价。
     *
     *  <p>⚠ 数据不可得时返回 false —— 这个方法是给"反查谁真的触发了召唤"用的，
     *  那种场合宁可漏判也不要误判。界面显示请用 {@link #evaluate}
     *  拿三态结果。</p>
     */
    static boolean individualOk(VillagerSnapshot v, long now, VersionProfile profile) {
        if (!v.sleepDataKnown || !v.golemMemoryKnown) {
            return false;
        }
        if (!v.adult) {
            return false;
        }
        if (v.golemDetectedRecently) {
            return false;
        }
        if (v.lastSleptGameTime == VillagerSnapshot.NEVER) {
            return false;
        }
        long since = now - v.lastSleptGameTime;
        return since >= 0 && since < profile.sleepWindowTicks;
    }

    private static RuleResult evalSpawnPoint(SpawnScanResult scan, VersionProfile profile) {
        if (scan == null || !scan.areaLoaded) {
            return RuleResult.unknown(Condition.SPAWN_POINT, ReasonCodes.SPAWN_AREA_UNLOADED);
        }
        if (scan.hasValidPoint()) {
            return RuleResult.pass(Condition.SPAWN_POINT,
                    scan.validPointCount(), profile.spawnAttempts);
        }
        return RuleResult.fail(Condition.SPAWN_POINT, ReasonCodes.NO_SPAWN_POINT,
                scan.validPointCount(), profile.spawnAttempts);
    }
}
