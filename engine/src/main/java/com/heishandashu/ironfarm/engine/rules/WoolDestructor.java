package com.heishandashu.ironfarm.engine.rules;

/**
 * 「漏斗指向白色羊毛块 → 销毁漏斗内全部物品」这条规则的**纯逻辑**部分。
 *
 * <p><b>需求（用户原话）：</b></p>
 * <blockquote>
 * 判断当漏斗指向白色羊毛块的时候，所有漏斗里的物品会被自动销毁，
 * 无论里面有多少物品，每刻都全部销毁，但是记录下销毁掉的物品。
 * </blockquote>
 *
 * <p><b>为什么纯逻辑要放在 engine 模块：</b></p>
 * <ul>
 *   <li>引擎是纯 Java、零 Minecraft 依赖，这条规则的判定与统计
 *       可以在这里被完整测试；</li>
 *   <li>四个版本（1.21.1/26.3 × Fabric/NeoForge）共用同一份判定，
 *       不会出现"某个版本行为不一样"；</li>
 *   <li>适配层只负责把"这个漏斗指向哪个方块"和"里面有什么物品"喂进来。</li>
 * </ul>
 *
 * <p><b>规则细节（容易理解错的地方）：</b></p>
 * <ul>
 *   <li>指向的是**白色羊毛方块**（{@code minecraft:white_wool}），
 *       不是"羊毛方块"泛称 —— 其它颜色不触发。</li>
 *   <li>判定看的是漏斗的**输出方向**（facing），不是任意相邻方块。
 *       漏斗朝下时看下方方块，朝上时看上方，朝侧面时看侧方。</li>
 *   <li>"每刻都全部销毁"：不是每 tick 销毁一个，而是清空整个容器，
 *       无论里面有多少、有多少种。</li>
 * </ul>
 */
public final class WoolDestructor {

    /** 触发销毁的目标方块。 */
    public static final String TARGET_BLOCK = "minecraft:white_wool";

    /**
     * 原版漏斗的**吸入速度**：每多少 tick 吸 1 个物品。
     *
     * <p>来源：{@code HopperBlockEntity.MOVE_ITEM_SPEED = 8}
     * （字节码与源码两处核对过）。漏斗每成功吸一个物品后
     * {@code setCooldown(8)}，也就是最快每 8 tick 吸一次。</p>
     */
    public static final int HOPPER_MOVE_ITEM_SPEED = 8;

    /** 原版漏斗的容量（格数）。{@code HopperBlockEntity.HOPPER_CONTAINER_SIZE = 5}。 */
    public static final int HOPPER_CONTAINER_SIZE = 5;

    /**
     * 清理周期（tick）：每多少 tick 清一次漏斗。
     *
     * <p><b>为什么是 2，而不是等于吸入间隔 8：</b></p>
     *
     * <p>0.38.0 把周期设成 8（= 吸入间隔），理论上"刚好够"。实际测下来
     * **仍然有残留**。原因是两个 tick 的**相位**不可控：</p>
     *
     * <pre>
     *   我们的清理跑在漏斗更新之前：
     *     tick 8  清理（漏斗还空）→ 紧接着漏斗吸入 1 个   → 残留 1
     *     tick 16 清理（清掉）    → 紧接着漏斗又吸入 1 个 → 残留 1
     *   任何时刻看，漏斗里总有 1 个刚吸进来的物品。
     * </pre>
     *
     * <p>周期 = 吸入间隔时，清完立刻又被填上，视觉上就是"永远有一个"。
     * 要把残留压到 0，周期必须**小于**吸入间隔，让每个吸入周期内
     * 至少被清 2 次，总有一次落在"吸入之后"。</p>
     *
     * <p>取 {@code 8 / 4 = 2} tick：每个吸入周期内清 4 次，
     * 残留最多存活 2 tick。</p>
     *
     * <p><b>为什么这么频繁也不卡：</b>这一层只遍历"已登记的漏斗"
     *（通常 1~5 个），查哈希表 + 遍历 5 格容器，
     * 与地图大小完全无关。真正贵的区块扫描在另一层，
     * 每 40 tick 才做一次。</p>
     */
    public static final int CLEAR_PERIOD_TICKS = HOPPER_MOVE_ITEM_SPEED / 4;

    /**
     * 从"空"开始，在给定周期下漏斗最大可能残留多少个物品。
     *
     * <p>= ceil(周期 / 吸入间隔) + 1。
     * 周期 8、间隔 8 → ceil(8/8)+1 = 2 个。用于测试与文档核算。</p>
     */
    public static int maxResidualItems(int clearPeriodTicks) {
        if (clearPeriodTicks <= 0) {
            return 0;
        }
        // 一个清理周期内，漏斗最多吸几次
        int picksPerPeriod = (clearPeriodTicks + HOPPER_MOVE_ITEM_SPEED - 1)
                / HOPPER_MOVE_ITEM_SPEED;
        // 残留上限 = 一个周期内吸进来的数量（下次清理前不会再增加）
        return Math.max(0, picksPerPeriod);
    }

    private WoolDestructor() {
    }

    /**
     * 这个方块是否触发销毁。
     *
     * @param blockId 方块注册名，可以为 null
     */
    public static boolean triggersDestruction(String blockId) {
        return blockId != null && TARGET_BLOCK.equals(blockId);
    }

    /**
     * 漏斗朝向偏移 → 相邻格坐标。
     *
     * <p>返回值顺序 {@code {dx, dy, dz}}。方向名与 Minecraft 的
     * {@code Direction} 枚举名一致（小写）。</p>
     *
     * <p>⚠ 漏斗的 facing 语义：{@code Facing.DOWN} 表示**输出朝下**，
     * 也就是它把物品往下方送。判定"指向白色羊毛"应该看**输出方向**。</p>
     *
     * @param facing 方向名（{@code "down"}/{@code "up"}/{@code "north"}...）
     * @return 长度 3 的偏移数组；未知方向返回 null
     */
    public static int[] offsetOf(String facing) {
        if (facing == null) {
            return null;
        }
        switch (facing.toLowerCase(java.util.Locale.ROOT)) {
            case "down":
                return new int[]{0, -1, 0};
            case "up":
                return new int[]{0, 1, 0};
            case "north":
                return new int[]{0, 0, -1};
            case "south":
                return new int[]{0, 0, 1};
            case "west":
                return new int[]{-1, 0, 0};
            case "east":
                return new int[]{1, 0, 0};
            default:
                return null;
        }
    }
}
