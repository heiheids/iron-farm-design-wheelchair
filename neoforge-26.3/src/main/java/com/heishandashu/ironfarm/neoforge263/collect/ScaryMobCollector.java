package com.heishandashu.ironfarm.neoforge263.collect;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.panic.ScaryMob;
import com.heishandashu.ironfarm.engine.panic.ScaryMobSnapshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

/**
 * 把真实实体读成引擎的 {@link ScaryMobSnapshot}。
 *
 * <p>关键点：<b>不要自己维护一张生物白名单</b>。
 * 原版的恐慌半径表（{@code VillagerHostilesSensor.ACCEPTABLE_DISTANCE_FROM_HOSTILES}）
 * 只登记了 11 种生物，但引擎侧 {@link ScaryMob} 已经把它们全部收录，
 * 并额外支持模组生物注册。适配层只负责"这是什么、在哪、稳不稳定"，
 * 具体算不算恐吓源交给引擎的注册表判断。</p>
 */
public final class ScaryMobCollector {

    private ScaryMobCollector() {
    }

    /**
     * 该实体是否值得作为"潜在恐吓源"采集。
     *
     * <p>用便宜的预筛减少每 tick 的遍历量：
     * 只取 {@link Monster}（原版所有敌对生物）以及有敌对记忆的村民的目标。
     * 真正的判定在引擎侧。</p>
     *
     * <p>⚠ 只筛 {@code Monster} 会漏掉僵尸疣猪兽（Zoglin 属于 Monster，没问题）
     * 和恼鬼（Vex 属于 Monster，也没问题）。但会漏掉整合包里
     * 没继承 Monster 的自定义敌对生物 —— 所以这里额外放行
     * "正在攻击村民的生物"。</p>
     */
    public static boolean isCandidate(Entity e) {
        if (e == null || !e.isAlive() || e instanceof Player) {
            return false;
        }
        return e instanceof Monster;
    }

    /** 采集一只怪物。 */
    public static ScaryMobSnapshot collect(LivingEntity e) {
        String typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(e.getType()).toString();
        ScaryMob kind = ScaryMob.byEntityId(typeId);

        BlockPos3 pos = new BlockPos3(
                (int) Math.floor(e.getX()),
                (int) Math.floor(e.getY()),
                (int) Math.floor(e.getZ()));

        // persistent：命名牌、船/矿车、给装备都算"不会 despawn"。
        // ⚠ isPersistenceRequired() 定义在 Mob 上，不在 LivingEntity 上 ——
        //    1.21.1 实测：写成 e.isPersistenceRequired() 会编译失败。
        boolean persistent = (e instanceof Mob && ((Mob) e).isPersistenceRequired())
                || e.getVehicle() != null
                || !e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty();

        boolean armed = isArmed(e);
        boolean hostileToVillagers = isHostileToVillagers(e);
        double eyeHeight = e.getEyeHeight();

        return new ScaryMobSnapshot(
                e.getId(), typeId, kind, pos, persistent, armed, hostileToVillagers, eyeHeight);
    }

    /** 掠夺者/卫道士带弩即视为持械。 */
    private static boolean isArmed(LivingEntity e) {
        net.minecraft.world.item.ItemStack main =
                e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        if (main.isEmpty()) {
            return false;
        }
        return main.getItem() instanceof net.minecraft.world.item.CrossbowItem;
    }

    /**
     * 该生物是否对村民敌对。
     *
     * <p>用 {@link Mob#getTarget()} 判断"此刻正在攻击谁"是不准的
     * （没锁定目标时也是敌对的）。原版的等价判断是
     * {@code VillagerHostilesSensor} 里的那张表 + {@code Monster} 标签。
     * 所以这里：Monster 一律认为对村民敌对；非 Monster 的返回 false，
     * 由引擎的严格模式决定要不要采信。</p>
     */
    private static boolean isHostileToVillagers(LivingEntity e) {
        return e instanceof Monster;
    }
}
