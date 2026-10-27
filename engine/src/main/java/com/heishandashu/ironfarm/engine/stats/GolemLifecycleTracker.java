package com.heishandashu.ironfarm.engine.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 铁傀儡生命周期追踪。
 *
 * <p>回答两个问题：</p>
 * <ol>
 *   <li><b>铁傀儡离开所有村民检测范围平均用了多久</b>
 *       —— 衡量"多久能被收走"。数值小说明收集系统灵敏；</li>
 *   <li><b>铁傀儡平均存活多久</b>
 *       —— 衡量"有没有被卡住/被怪打死"。
 *       如果存活时间远大于离开检测范围的时间，说明傀儡是掉进某个地方耗死的。</li>
 * </ol>
 *
 * <h3>为什么这两个数要分开算</h3>
 *
 * <p>"离开检测范围"和"死亡/消失"是两件独立的事：</p>
 * <ul>
 *   <li>正常的刷铁机：傀儡刷出 → 掉进水流 → 很快离开检测范围 → 到收集点被杀死。
 *       两个时间都短，但**离开范围的时间明显短于存活时间**。</li>
 *   <li>卡住的傀儡：一直站在刷出点不动。
 *       离开范围的时间 = 存活时间（或永远不离开）。</li>
 *   <li>被怪打死的：存活时间短，但离开范围的时间可能也很短。</li>
 * </ul>
 *
 * <h3>数据来源与局限</h3>
 *
 * <p>客户端能看到铁傀儡实体（在加载范围内），所以可以追踪。
 * 但有两个不可避免的缺口：</p>
 * <ul>
 *   <li>傀儡在视野外死亡 → 我们只看到它"从实体表消失"，记为消失而非死亡；</li>
 *   <li>区块卸载 → 不算死亡，标记为"未知结束"并从样本里剔除。</li>
 * </ul>
 * <p>这两种情况要分开计数，否则平均数会被污染。</p>
 */
public final class GolemLifecycleTracker {

    /** 一只铁傀儡的追踪记录。 */
    public static final class Tracked {
        public final int entityId;
        /** 首次看到的游戏时间。 */
        public final long firstSeen;
        /** 首次看到时的位置（用于判断是否"卡住"）。 */
        public final double spawnX, spawnY, spawnZ;
        /** 最后一次观察到的游戏时间。 */
        public long lastSeen;
        /** 离开所有村民检测范围的时间；{@link #UNKNOWN} 表示还没离开或不知道。 */
        public long leftDetectionAt = UNKNOWN;
        /** 最后一次观察到的位置。 */
        public double lastX, lastY, lastZ;

        Tracked(int entityId, long firstSeen,
                double x, double y, double z) {
            this.entityId = entityId;
            this.firstSeen = firstSeen;
            this.lastSeen = firstSeen;
            this.spawnX = x;
            this.spawnY = y;
            this.spawnZ = z;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }

        /** 在检测范围内的时长（tick）；还没离开返回 -1。 */
        public long ticksInsideDetection(long now) {
            if (leftDetectionAt == UNKNOWN) {
                return -1;
            }
            return leftDetectionAt - firstSeen;
        }

        /**
         * 存活时长（tick）。
         *
         * <p>⚠ 这里必须用 {@code now}（消失的时刻），<b>不能用 {@code lastSeen}</b>。
         * 我第一版写的是 {@code lastSeen - firstSeen}，结果
         * {@code lastSeen} 只在"还看得见时"更新，傀儡消失的那一次观察
         * 它根本不在实体列表里，所以 {@code lastSeen} 停在倒数第二次，
         * 存活时长被系统性低估。</p>
         *
         * <p>测试 normalGolemRecordsBothSamples 抓到了这个：
         * 期望 200 tick，实际 40 tick（正好是离开检测范围的时间）。</p>
         */
        public long ticksAlive(long now) {
            return now - firstSeen;
        }
    }

    /** 未知时间戳。 */
    public static final long UNKNOWN = Long.MIN_VALUE;

    /** 同时追踪上限。防止极端情况下内存无限增长。 */
    private static final int MAX_TRACKED = 256;

    /** 存活铁傀儡。 */
    private final Map<Integer, Tracked> alive = new LinkedHashMap<Integer, Tracked>();

    /** 已完成样本：离开检测范围用时（tick）。 */
    private final List<Long> leftDetectionSamples = new ArrayList<Long>();
    /** 已完成样本：存活时长（tick）。 */
    private final List<Long> aliveSamples = new ArrayList<Long>();

    /** 因为区块卸载等"非正常结束"被剔除的数量。 */
    private long discarded = 0;

    /** 样本上限，避免长期挂机把内存吃满。 */
    private static final int MAX_SAMPLES = 4096;

    // ==================================================================
    // 每 tick 喂一次
    // ==================================================================

    /**
     * 观察当前所有铁傀儡（客户端每 tick 调用）。
     *
     * @param now            当前游戏时间
     * @param golems         当前能看到的铁傀儡：entityId → 位置
     * @param insideDetector 该傀儡是否仍在"某个村民的检测范围内"
     */
    public void observe(long now,
                        Map<Integer, double[]> golems,
                        java.util.function.IntPredicate insideDetector) {

        // 1) 新出现的 → 开始追踪
        for (Map.Entry<Integer, double[]> e : golems.entrySet()) {
            Integer id = e.getKey();
            Tracked t = alive.get(id);
            double[] p = e.getValue();
            if (t == null) {
                if (alive.size() >= MAX_TRACKED) {
                    continue;
                }
                alive.put(id, new Tracked(id, now, p[0], p[1], p[2]));
            } else {
                t.lastSeen = now;
                t.lastX = p[0];
                t.lastY = p[1];
                t.lastZ = p[2];
            }
        }

        // 2) 更新"是否还在检测范围内"
        for (Tracked t : alive.values()) {
            if (t.leftDetectionAt == UNKNOWN && !insideDetector.test(t.entityId)) {
                t.leftDetectionAt = now;
            }
        }

        // 3) 已经看不到的 → 结束样本
        //    区分两种结束：
        //      - 最后一次看到时它已经离开检测范围 → 正常（被收走了）
        //      - 还在检测范围内就消失 → 可能是区块卸载，记为未知，剔除样本
        java.util.Iterator<Map.Entry<Integer, Tracked>> it = alive.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Tracked> e = it.next();
            Tracked t = e.getValue();
            if (golems.containsKey(e.getKey())) {
                continue;
            }
            // 消失
            if (t.leftDetectionAt != UNKNOWN) {
                addSample(leftDetectionSamples, t.ticksInsideDetection(now));
                addSample(aliveSamples, t.ticksAlive(now));
            } else {
                // 还在检测范围里就没了 —— 大概率是区块卸载或数据不全，不纳入统计
                discarded++;
            }
            it.remove();
        }
    }

    private static void addSample(List<Long> list, long value) {
        if (value < 0) {
            return;
        }
        if (list.size() >= MAX_SAMPLES) {
            list.remove(0);
        }
        list.add(Long.valueOf(value));
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 平均离开检测范围用时（tick）；没有样本返回 -1。 */
    public double averageTicksToLeaveDetection() {
        return average(leftDetectionSamples);
    }

    /** 平均存活时长（tick）；没有样本返回 -1。 */
    public double averageTicksAlive() {
        return average(aliveSamples);
    }

    private static double average(List<Long> list) {
        if (list.isEmpty()) {
            return -1;
        }
        long sum = 0;
        for (Long v : list) {
            sum += v.longValue();
        }
        return (double) sum / list.size();
    }

    /** 已完成样本数（能进平均值的那些）。 */
    public int completedSamples() {
        return aliveSamples.size();
    }

    /** 因非正常结束被剔除的数量。 */
    public long discardedCount() {
        return discarded;
    }

    /** 当前正在追踪的傀儡数。 */
    public int trackedCount() {
        return alive.size();
    }

    /** 清空。 */
    public void reset() {
        alive.clear();
        leftDetectionSamples.clear();
        aliveSamples.clear();
        discarded = 0;
    }

    // ==================================================================
    // 持久化（由 StatsCodec 调用）
    // ==================================================================

    public void restore(long ticksToLeaveSum, long ticksToLeaveCount,
                        long aliveSum, long aliveCount, long discardedCount) {
        this.discarded = Math.max(0, discardedCount);
        // 恢复时只保留"平均数"的信息：用一个合成样本表示，避免存几千个数字。
        // 精度损失可接受（统计页只显示平均值）。
        if (ticksToLeaveCount > 0) {
            leftDetectionSamples.clear();
            leftDetectionSamples.add(Long.valueOf(Math.round(
                    (double) ticksToLeaveSum / ticksToLeaveCount)));
            for (long i = 1; i < ticksToLeaveCount && leftDetectionSamples.size() < MAX_SAMPLES; i++) {
                leftDetectionSamples.add(leftDetectionSamples.get(0));
            }
        }
        if (aliveCount > 0) {
            aliveSamples.clear();
            aliveSamples.add(Long.valueOf(Math.round((double) aliveSum / aliveCount)));
            for (long i = 1; i < aliveCount && aliveSamples.size() < MAX_SAMPLES; i++) {
                aliveSamples.add(aliveSamples.get(0));
            }
        }
    }

    /** 序列化用：离开检测范围的样本总和。 */
    public long ticksToLeaveSum() {
        long s = 0;
        for (Long v : leftDetectionSamples) {
            s += v.longValue();
        }
        return s;
    }

    /** 序列化用：离开检测范围的样本数。 */
    public long ticksToLeaveCount() {
        return leftDetectionSamples.size();
    }

    /** 序列化用：存活样本总和。 */
    public long aliveSum() {
        long s = 0;
        for (Long v : aliveSamples) {
            s += v.longValue();
        }
        return s;
    }

    /** 序列化用：存活样本数。 */
    public long aliveCount() {
        return aliveSamples.size();
    }
}
