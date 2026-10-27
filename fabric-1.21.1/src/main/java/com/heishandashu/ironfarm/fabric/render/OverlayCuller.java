package com.heishandashu.ironfarm.fabric.render;

import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.List;

/**
 * 决定"这一帧要画哪些村民的图标"。
 *
 * <p>把筛选逻辑从渲染循环里拆出来，是为了**能测** ——
 * 距离判断、开关判断、灰显判断这些规则不需要 OpenGL 就能验证。</p>
 *
 * <p>⚠ 这个类刻意不引用任何渲染类，只处理纯数据。</p>
 */
public final class OverlayCuller {

    private OverlayCuller() {
    }

    /** 一个要画的图标。 */
    public static final class Entry {
        public final int entityId;
        public final double x;
        public final double y;
        public final double z;
        /** 三个图标各自该用什么颜色。 */
        public final int checkColor;
        public final int skullColor;
        public final int speakerColor;
        /** 距离玩家的距离（用于按距离缩放/淡出）。 */
        public final double distance;

        public Entry(int entityId, double x, double y, double z,
                     int checkColor, int skullColor, int speakerColor, double distance) {
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.checkColor = checkColor;
            this.skullColor = skullColor;
            this.speakerColor = speakerColor;
            this.distance = distance;
        }
    }

    /**
     * 从当前扫描结果里挑出要画的村民。
     *
     * @param reports     引擎报告
     * @param villagerPos 按 entityId 查位置的回调（渲染层从世界拿实体）
     * @param playerX/Y/Z 玩家位置
     * @param maxDistance 最大显示距离
     * @param scaleByDistance 是否按距离缩放
     */
    public static List<Entry> cull(
            List<VillagerReport> reports,
            PositionLookup villagerPos,
            double playerX, double playerY, double playerZ,
            double maxDistance) {
        List<Entry> out = new ArrayList<Entry>();
        if (reports == null) {
            return out;
        }
        double max2 = maxDistance * maxDistance;
        for (VillagerReport r : reports) {
            double[] p = villagerPos.get(r.entityId());
            if (p == null) {
                continue;
            }
            double dx = p[0] - playerX;
            double dy = p[1] - playerY;
            double dz = p[2] - playerZ;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > max2) {
                continue;
            }
            out.add(new Entry(
                    r.entityId(),
                    p[0], p[1], p[2],
                    checkColor(r),
                    skullColor(r),
                    speakerColor(r),
                    Math.sqrt(d2)));
        }
        return out;
    }

    /** 位置查询回调，避免 OverlayCuller 依赖 Minecraft 的实体表。 */
    public interface PositionLookup {
        /** @return {x, y, z}，找不到返回 null */
        double[] get(int entityId);
    }

    // ------------------------------------------------------------------
    // 三态 → 颜色
    // ------------------------------------------------------------------

    /**
     * 第一个图标（对勾）的颜色。
     *
     * <p>⚠ 这里必须**先判断 UNKNOWN 再判断 CHECK** ——
     * 顺序写反的话，数据不可得时 hardConditionsMet() 返回 false，
     * 会走到红叉分支，多人游戏里满屏红叉。</p>
     */
    public static int checkColor(VillagerReport r) {
        // 语义由引擎唯一定义：对钩 = 成年 + 睡过 + 30 秒内没见到铁傀儡。
        //
        // ⚠ 上一版我改成 panicFarmReady() || gossipFarmReady()，
        // 想解决"头顶与点进去不同步"——那是改错了方向：
        // 对钩本来就不代表"整台机器能刷"，它只代表个体条件。
        // 用 ready 判定会让对钩在"生成点不合法"时变红，
        // 而状态页写着"睡过/没有傀儡"都满足，这才是真正的不一致。
        //
        // 正确做法：两边都用同一套语义 —— 状态页各行按各自的依据上色，
        // 而不是让图标去迁就"总判定"。
        return colorOf(r.conditionIcon());
    }

    /** 三态 → 颜色的唯一映射。所有图标共用，避免各写一份走偏。 */
    private static int colorOf(VillagerReport.Icon icon) {
        switch (icon) {
            case CHECK:
                return IconRenderer.COLOR_OK;
            case CROSS:
                return IconRenderer.COLOR_BAD;
            default:
                return IconRenderer.COLOR_UNKNOWN;
        }
    }
    /**
     * 第二个图标（骷髅）的颜色。
     *
     * <p>骷髅只有两态：正在被恐吓（绿）/ 没有（红）。
     * 它不依赖 Brain 数据，是客户端能算准的，所以没有灰色。</p>
     */
    public static int skullColor(VillagerReport r) {
        return r.panicking() ? IconRenderer.COLOR_OK : IconRenderer.COLOR_BAD;
    }

    /** 第三个图标（喇叭）的颜色。 */
    public static int speakerColor(VillagerReport r) {
        // 语义由引擎唯一定义：喇叭 = 闲聊刷铁傀儡的前提满足。
        return colorOf(r.gossipIcon());
    }

    // ------------------------------------------------------------------

    /** 便捷：从客户端世界实体表查位置。 */
    public static PositionLookup fromLevel(Minecraft mc) {
        return entityId -> {
            if (mc.level == null) {
                return null;
            }
            Entity e = mc.level.getEntity(entityId);
            if (e == null) {
                return null;
            }
            return new double[]{e.getX(), e.getY() + e.getBbHeight(), e.getZ()};
        };
    }

    /** 该村民实体是否还在世界里。 */
    public static boolean isAlive(Minecraft mc, int entityId) {
        if (mc.level == null) {
            return false;
        }
        Entity e = mc.level.getEntity(entityId);
        return e instanceof Villager && e.isAlive();
    }
}
