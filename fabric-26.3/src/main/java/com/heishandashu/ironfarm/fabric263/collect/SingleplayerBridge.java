package com.heishandashu.ironfarm.fabric263.collect;

import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 在**单机游戏**里把客户端实体映射到服务端实体，从而读到真实的 Brain 数据。
 *
 * <p>为什么需要这个：客户端拿不到 {@code LAST_SLEPT} / {@code GOLEM_DETECTED_RECENTLY}
 * （它们只在服务端的 Brain 里）。但单机时客户端与服务端同进程，
 * 可以拿到服务端实体，于是这两条关键条件就能给出**准确**的结论，
 * 而不是所有村民都显示"无法判定"。</p>
 *
 * <p>多人游戏里 {@code getSingleplayerServer()} 返回 null，
 * 此时本类返回 null，调用方回退到"数据不可得"路径。</p>
 *
 * <p>⚠ 性能：每次查服务端实体要走一次实体表。所以调用方
 * （{@link VillagerCollector}）应当按扫描节流使用，
 * 不要每帧都调。</p>
 */
public final class SingleplayerBridge {

    private SingleplayerBridge() {
    }

    /**
     * 当前是否处于单机游戏（能访问服务端）。
     *
     * <p>注意 {@code getSingleplayerServer()} 在"正在退出世界"时可能返回 null，
     * 所以这个方法必须每 tick 重新判断，不要在启动时缓存一次。</p>
     */
    public static boolean isAvailable(Minecraft mc) {
        return mc != null && mc.getSingleplayerServer() != null && mc.level != null;
    }

    /** 取服务端的对应世界；不可用时返回 null。 */
    public static ServerLevel serverLevel(Minecraft mc) {
        if (!isAvailable(mc)) {
            return null;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        return server.getLevel(mc.level.dimension());
    }

    /**
     * 把客户端村民映射到服务端同名实体。
     *
     * <p>用 entity id 匹配 —— 单机模式下客户端与服务端的实体 id 是一致的
     * （同一个保存文件、同一份实体表）。这也是唯一稳定的匹配方式：
     * 按坐标匹配会在实体移动时错位。</p>
     *
     * @return 服务端村民；找不到或不可用时返回 null
     */
    public static Villager toServerVillager(Minecraft mc, Villager clientVillager) {
        ServerLevel level = serverLevel(mc);
        if (level == null || clientVillager == null) {
            return null;
        }
        Entity e = level.getEntity(clientVillager.getId());
        return e instanceof Villager ? (Villager) e : null;
    }

    /**
     * 取本地玩家的服务端对象（用于判断"这一段数据我到底能不能拿到"）。
     * <p>目前没用到，但排查"为什么单机也拿不到数据"时很有用：
     * 如果它返回 null，说明 {@code getSingleplayerServer()} 已经不可用了。</p>
     */
    public static ServerPlayer localServerPlayer(Minecraft mc) {
        if (!isAvailable(mc) || mc.player == null) {
            return null;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(mc.player.getUUID());
    }

    /**
     * 单机模式下采集（带真实 Brain 数据）。
     *
     * @return 数据齐全的快照；服务端实体拿不到时返回 null，调用方应回退
     */
    public static VillagerSnapshot collectIfAvailable(
            Minecraft mc, Villager clientVillager, long now) {
        Villager serverVillager = toServerVillager(mc, clientVillager);
        if (serverVillager == null) {
            return null;
        }
        return VillagerCollector.collect(serverVillager, serverLevel(mc), now);
    }
}
