package com.heishandashu.ironfarm.engine.eval;

import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 判定引擎的对外入口：把一批 {@link VillagerSnapshot} 算成一批 {@link VillagerReport}。
 *
 * <p>刻意做成无状态 + 纯函数：给定同样的输入，输出永远一样。
 * 所有"会随时间变化"的东西（上次睡觉时间、傀儡记忆）都由调用方塞进快照里，
 * 引擎不持有世界状态。这让整个判定逻辑可以离线测试。</p>
 *
 * <p>判定顺序严格照原版：个体门槛 → 触发状态 → 群体门槛 → 生成点。
 * 任何一步失败都继续算后面的（而不是短路返回），因为界面要显示完整状态表。</p>
 */
public final class ConditionEngine {

    private final VersionProfile profile;

    public ConditionEngine(VersionProfile profile) {
        this.profile = profile == null ? VersionProfile.MODERN : profile;
    }

    public VersionProfile profile() {
        return profile;
    }

    /**
     * 批量判定。
     *
     * @param villagers 待判定的村民快照
     * @param now       当前游戏时间（tick）
     * @param spawnScan 生成点扫描结果；传 null 表示本次不判定生成点（显示为 UNKNOWN）
     * @return 与输入顺序一致的报告列表
     */
    public List<VillagerReport> evaluateAll(
            Collection<VillagerSnapshot> villagers,
            long now,
            SpawnScanResult spawnScan) {
        return VillagerEvaluator.evaluateAll(villagers, now, profile, spawnScan);
    }

    /**
     * 单只村民判定。
     *
     * <p>群体门槛仍然需要 {@code all}：一只村民是否合格取决于它身边的人，
     * 传只含它自己的集合会得到"人数不足"的结论。</p>
     */
    public VillagerReport evaluate(
            VillagerSnapshot villager,
            Collection<VillagerSnapshot> all,
            long now,
            SpawnScanResult spawnScan) {
        return VillagerEvaluator.evaluate(villager, all, now, profile, spawnScan);
    }

    /**
     * 把报告转成"失败原因列表"，供统计器记账。
     * 只含 FAIL 的条目，按 {@link Condition} 声明顺序。
     */
    public static List<com.heishandashu.ironfarm.engine.rule.RuleResult> failuresOf(VillagerReport report) {
        List<com.heishandashu.ironfarm.engine.rule.RuleResult> out =
                new ArrayList<com.heishandashu.ironfarm.engine.rule.RuleResult>();
        for (Condition c : Condition.values()) {
            com.heishandashu.ironfarm.engine.rule.RuleResult r = report.get(c);
            if (r.isFail()) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 直接判定"这只村民现在会不会触发召唤"，与原版 {@code wantsToSpawnGolem} 等价。
     *
     * <p>给适配层做"傀儡真的刷出来了"的事件归因用：
     * 抓到一个 IronGolem 生成事件时，用这个方法反查是谁触发的。
     * 注意它**不含**群体门槛与生成点判定 —— 原版这两个在
     * {@code spawnGolemIfNeeded} 与 {@code trySpawnMob} 里，是分开的。</p>
     */
    public boolean wantsToSpawnGolem(VillagerSnapshot v, long now) {
        return VillagerEvaluator.individualOk(v, now, profile);
    }
}
