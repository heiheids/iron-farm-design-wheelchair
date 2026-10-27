package com.heishandashu.ironfarm.fabric263;

import com.heishandashu.ironfarm.engine.eval.ConditionEngine;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.stats.StatsRecorder;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import com.heishandashu.ironfarm.fabric263.collect.VillagerCollector;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 服务端侧的统计收集器。
 *
 * <p>统计页要回答的三个问题是：开始记录的时间、刷出多少铁傀儡、
 * 哪些村民失败以及原因。前两个必须从**服务端**拿 —— 客户端看不到
 * 别的区块里刷出来的傀儡。</p>
 *
 * <p>做法：订阅 {@link ServerEntityEvents#ENTITY_LOAD}，
 * 抓到 {@link IronGolem} 时向上找"是谁触发的"。</p>
 *
 * <p><b>归因方法说明</b>：原版 {@code spawnGolemIfNeeded} 不会告诉我们
 * 是谁成功的。所以这里反过来做 —— 用引擎的 {@code wantsToSpawnGolem}
 * 在傀儡出现的位置附近找合格的村民。这只是**推断**，
 * 所以记录时标成 inferred，界面上要说明。
 * 精确归因需要 Mixin 进 {@code Villager.spawnGolemIfNeeded}，留到后续版本。</p>
 */
public final class ServerStatsCollector {

    /** 傀儡刷出位置到村民的最大搜索距离。原版召唤范围是 ±8，留些余量。 */
    private static final double ATTRIBUTION_RADIUS = 12.0;

    private final StatsRecorder stats;
    private final ConditionEngine engine = new ConditionEngine(VersionProfile.MODERN);

    public ServerStatsCollector(StatsRecorder stats) {
        this.stats = stats;
    }

    /** 注册事件。在客户端入口里调用（单机时服务端事件同样可用）。 */
    public void register() {
        ServerEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
    }

    private void onEntityLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof IronGolem)) {
            return;
        }
        long now = level.getGameTime();
        int villagerId = attribute(level, entity.position(), now);
        // villagerId 为 -1 表示没找到合格村民（指令召唤 / 玩家搭的 / 前哨站笼子）
        stats.recordSpawn(now, villagerId);
    }

    /**
     * 在傀儡附近找最可能的触发者。
     *
     * @return 村民 entityId；找不到返回 -1
     */
    private int attribute(ServerLevel level, Vec3 pos, long now) {
        AABB box = new AABB(
                pos.x - ATTRIBUTION_RADIUS, pos.y - ATTRIBUTION_RADIUS, pos.z - ATTRIBUTION_RADIUS,
                pos.x + ATTRIBUTION_RADIUS, pos.y + ATTRIBUTION_RADIUS, pos.z + ATTRIBUTION_RADIUS);
        List<Villager> nearby = level.getEntitiesOfClass(Villager.class, box);
        if (nearby.isEmpty()) {
            return -1;
        }
        Villager best = null;
        double bestDist = Double.MAX_VALUE;
        for (Villager v : nearby) {
            VillagerSnapshot snap = VillagerCollector.collect(v, level, now);
            if (!engine.wantsToSpawnGolem(snap, now)) {
                continue;
            }
            double d = v.position().distanceToSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best == null ? -1 : best.getId();
    }
}
