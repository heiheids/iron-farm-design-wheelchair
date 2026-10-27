package com.heishandashu.ironfarm.engine.version;

/**
 * 一个 Minecraft 版本区段的判定参数档案。
 *
 * <p><b>本档案的每一个数字都来自 1.21.1 真实字节码</b>（javap 反编译），
 * 不是从 Wiki 转述：</p>
 *
 * <table>
 *   <tr><th>参数</th><th>值</th><th>来源</th></tr>
 *   <tr><td>睡眠窗口</td><td>24000 tick</td>
 *       <td>{@code Villager.golemSpawnConditionsMet} 的 {@code ldc2_w 24000l}</td></tr>
 *   <tr><td>傀儡记忆有效期</td><td>599 tick</td>
 *       <td>{@code GolemSensor.golemDetected} 的 {@code setMemoryWithExpiry(..., 599L)}</td></tr>
 *   <tr><td>傀儡扫描间隔</td><td>200 tick</td>
 *       <td>{@code GolemSensor.<init>} 传入 200</td></tr>
 *   <tr><td>实体感知范围</td><td>16 格</td>
 *       <td>{@code Sensor.TARGETING_RANGE = 16}，配合包围盒 inflate</td></tr>
 *   <tr><td>村民统计范围</td><td>包围盒膨胀 10 格</td>
 *       <td>{@code spawnGolemIfNeeded} 的 {@code inflate(10,10,10)}</td></tr>
 *   <tr><td>统计上限</td><td>5</td>
 *       <td>同上方法的 {@code Stream.limit(5)}</td></tr>
 *   <tr><td>恐慌门槛</td><td>3</td>
 *       <td>{@code VillagerPanicTrigger.tick} 的 {@code iconst_3}</td></tr>
 *   <tr><td>闲聊门槛</td><td>5</td>
 *       <td>{@code Villager.gossip} 的 {@code iconst_5}</td></tr>
 *   <tr><td>恐慌尝试间隔</td><td>100 tick</td>
 *       <td>{@code VillagerPanicTrigger.tick} 的 {@code lrem 100}</td></tr>
 *   <tr><td>生成体积</td><td>17×13×17</td>
 *       <td>{@code SpawnUtil.trySpawnMob(type, ..., pos, 10, 8, 6, LEGACY_IRON_GOLEM)}</td></tr>
 * </table>
 *
 * <p>⚠ 两个容易搞错的地方：</p>
 * <ol>
 *   <li>傀儡记忆有效期是 <b>599</b> tick，不是常见资料写的 600。
 *       差一个 tick 会让"刚好 30 秒"的边界判定反过来。</li>
 *   <li>村民统计有 {@code limit(5)} 上限。闲聊门槛正好是 5，
 *       所以闲聊路径要求<b>全部 5 个名额都合格</b>，一个不合格就不够。</li>
 * </ol>
 */
public final class VersionProfile {

    public final String id;
    public final String displayRange;

    // ---- 时间参数（tick） ----
    /** 睡眠有效期：24000 tick = 20 分钟。 */
    public final int sleepWindowTicks;
    /** 傀儡记忆有效期：599 tick（原版 {@code GolemSensor} 写死）。 */
    public final int golemMemoryTicks;
    /** 傀儡扫描间隔：200 tick = 10 秒。 */
    public final int golemScanIntervalTicks;

    // ---- 范围参数（格） ----
    /** 实体感知立方体半边：16（{@code Sensor.TARGETING_RANGE}）。 */
    public final int sensorRange;
    /** 村民统计范围：包围盒膨胀 10 格。 */
    public final int summonSpreadInflate;
    /** 统计名额上限：5（原版 {@code Stream.limit(5)}）。 */
    public final int participantLimit;

    // ---- 生成体积 ----
    /** 水平半边长：8，即 17 格宽。 */
    public final int spawnHalfWidth;
    /** 垂直半边长：6，即 13 格高。 */
    public final int spawnHalfHeight;
    /** 单次召唤最多尝试次数：10。 */
    public final int spawnAttempts;

    // ---- 群体门槛 ----
    /** 恐慌路径的参与者门槛：3。 */
    public final int panicParticipantThreshold;
    /** 闲聊路径的参与者门槛：5。 */
    public final int gossipParticipantThreshold;

    /** 恐慌路径的尝试间隔：100 tick = 5 秒。 */
    public final int panicAttemptIntervalTicks;

    private VersionProfile(
            String id, String displayRange,
            int sleepWindowTicks, int golemMemoryTicks, int golemScanIntervalTicks,
            int sensorRange, int summonSpreadInflate, int participantLimit,
            int spawnHalfWidth, int spawnHalfHeight, int spawnAttempts,
            int panicParticipantThreshold, int gossipParticipantThreshold,
            int panicAttemptIntervalTicks) {
        this.id = id;
        this.displayRange = displayRange;
        this.sleepWindowTicks = sleepWindowTicks;
        this.golemMemoryTicks = golemMemoryTicks;
        this.golemScanIntervalTicks = golemScanIntervalTicks;
        this.sensorRange = sensorRange;
        this.summonSpreadInflate = summonSpreadInflate;
        this.participantLimit = participantLimit;
        this.spawnHalfWidth = spawnHalfWidth;
        this.spawnHalfHeight = spawnHalfHeight;
        this.spawnAttempts = spawnAttempts;
        this.panicParticipantThreshold = panicParticipantThreshold;
        this.gossipParticipantThreshold = gossipParticipantThreshold;
        this.panicAttemptIntervalTicks = panicAttemptIntervalTicks;
    }

    /** 1.21.1 档案，全部取自真实字节码。 */
    public static final VersionProfile V1_21_1 = new VersionProfile(
            "1.21.1", "1.21 - 26.3",
            24000,  // 20 分钟睡眠窗口
            599,    // 傀儡记忆有效期（注意不是 600）
            200,    // 10 秒扫描间隔
            16,     // 感知立方体 ±16
            10,     // 统计范围 inflate 10
            5,      // 统计名额上限
            8,      // 水平 ±8 → 17 格
            6,      // 垂直 ±6 → 13 格
            10,     // 10 次尝试
            3,      // 恐慌 3 人
            5,      // 闲聊 5 人
            100);   // 5 秒一次

    public static final VersionProfile MODERN = V1_21_1;

    /** 水平生成宽度（格）：17。 */
    public int spawnWidth() {
        return spawnHalfWidth * 2 + 1;
    }

    /** 垂直生成高度（格）：13。 */
    public int spawnHeight() {
        return spawnHalfHeight * 2 + 1;
    }

    /** 版本号是否在本工程声明支持的范围内（1.21 及以后）。 */
    public static boolean isSupported(String version) {
        if (version == null) {
            return false;
        }
        String v = version.trim();
        if (v.startsWith("26.")) {
            return true;
        }
        if (v.startsWith("1.")) {
            String[] parts = v.split("\\.");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]) >= 21;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /** 按 Minecraft 版本号挑档案。1.21 及以后目前共用 {@link #V1_21_1}。 */
    public static VersionProfile forMinecraftVersion(String version) {
        return V1_21_1;
    }

    /** 由配置里的手改值生成一个覆盖档案；传 null 的项保留原值。 */
    public VersionProfile override(
            Integer sleepWindowTicks,
            Integer golemMemoryTicks,
            Integer golemScanIntervalTicks,
            Integer sensorRange,
            Integer summonSpreadInflate,
            Integer participantLimit,
            Integer spawnHalfWidth,
            Integer spawnHalfHeight,
            Integer spawnAttempts,
            Integer panicParticipantThreshold,
            Integer gossipParticipantThreshold,
            Integer panicAttemptIntervalTicks) {
        return new VersionProfile(
                this.id + "+override",
                this.displayRange,
                or(sleepWindowTicks, this.sleepWindowTicks),
                or(golemMemoryTicks, this.golemMemoryTicks),
                or(golemScanIntervalTicks, this.golemScanIntervalTicks),
                or(sensorRange, this.sensorRange),
                or(summonSpreadInflate, this.summonSpreadInflate),
                or(participantLimit, this.participantLimit),
                or(spawnHalfWidth, this.spawnHalfWidth),
                or(spawnHalfHeight, this.spawnHalfHeight),
                or(spawnAttempts, this.spawnAttempts),
                or(panicParticipantThreshold, this.panicParticipantThreshold),
                or(gossipParticipantThreshold, this.gossipParticipantThreshold),
                or(panicAttemptIntervalTicks, this.panicAttemptIntervalTicks));
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value.intValue();
    }

    @Override
    public String toString() {
        return "VersionProfile{" + id + " (" + displayRange + ")"
                + " sleep=" + sleepWindowTicks
                + " golemMemory=" + golemMemoryTicks
                + " scan=" + golemScanIntervalTicks
                + " sensor=" + sensorRange
                + " inflate=" + summonSpreadInflate
                + " limit=" + participantLimit
                + " box=" + spawnWidth() + "x" + spawnHeight() + "x" + spawnWidth()
                + " panic>=" + panicParticipantThreshold
                + " gossip>=" + gossipParticipantThreshold
                + "}";
    }
}
