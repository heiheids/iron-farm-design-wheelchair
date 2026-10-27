package com.heishandashu.ironfarm.engine.panic;

import com.heishandashu.ironfarm.engine.model.BlockPos3;

/**
 * 一只"吓人"的怪物在某一个瞬间的可判定状态。
 *
 * <p>适配层对世界里每一个可能属于「恐吓源」的实体构造一个本对象。
 * 引擎不关心它是僵尸还是劫掠兽，只关心注册名（用于查半径）和位置。</p>
 *
 * <p>工程属性（会不会烧死、有没有被船固定、有没有持弩）不参与原版判定，
 * 但会原样透传给界面，用来回答「这台机器为什么夜里刷、白天停」这类问题。</p>
 */
public final class ScaryMobSnapshot {

    public final int entityId;
    /** 实体注册名，形如 {@code "minecraft:zombie"} 或 {@code "somemod:thing"}。 */
    public final String typeId;
    /**
     * 已解析的档案。未登记时为 {@link ScaryMob#OTHER}；
     * 具体是否算恐吓源要过 {@link ScaryMobRegistry#resolve}。
     */
    public final ScaryMob kind;
    public final BlockPos3 pos;

    /**
     * 该怪物是否被稳定住、不会 despawn。
     * 命名牌、船、矿车、给装备固定住的才算。
     */
    public final boolean persistent;

    /** 是否手持远程武器（掠夺者的弩）。持弩会射死村民和傀儡。 */
    public final boolean armed;

    /** 适配层判断的「该生物是否对村民敌对」；未知时传 false。 */
    public final boolean hostileToVillagers;

    /** 眼睛离脚底的高度，用于视线判定。 */
    public final double eyeHeight;

    public ScaryMobSnapshot(
            int entityId,
            String typeId,
            ScaryMob kind,
            BlockPos3 pos,
            boolean persistent,
            boolean armed,
            boolean hostileToVillagers,
            double eyeHeight) {
        this.entityId = entityId;
        this.typeId = typeId;
        this.kind = kind == null ? ScaryMob.OTHER : kind;
        this.pos = pos;
        this.persistent = persistent;
        this.armed = armed;
        this.hostileToVillagers = hostileToVillagers;
        this.eyeHeight = eyeHeight;
    }

    /** 便捷构造：按注册名自动解析档案，眼高用该类生物的默认值。 */
    public static ScaryMobSnapshot of(int id, String typeId, int x, int y, int z) {
        ScaryMob kind = ScaryMob.byEntityId(typeId);
        return new ScaryMobSnapshot(id, typeId, kind, new BlockPos3(x, y, z),
                true, false, true, PanicResolver.eyeHeightOf(kind));
    }

    public static ScaryMobSnapshot zombie(int id, int x, int y, int z) {
        return of(id, "minecraft:zombie", x, y, z);
    }

    public static ScaryMobSnapshot pillager(int id, int x, int y, int z) {
        return of(id, "minecraft:pillager", x, y, z);
    }

    public ScaryMobSnapshot withPersistent(boolean value) {
        return new ScaryMobSnapshot(entityId, typeId, kind, pos, value, armed,
                hostileToVillagers, eyeHeight);
    }

    public ScaryMobSnapshot withArmed(boolean value) {
        return new ScaryMobSnapshot(entityId, typeId, kind, pos, persistent, value,
                hostileToVillagers, eyeHeight);
    }

    @Override
    public String toString() {
        return "ScaryMob{" + typeId + " #" + entityId + " " + pos
                + (persistent ? " persistent" : " transient")
                + (armed ? " armed" : "") + "}";
    }
}
