package com.heishandashu.ironfarm.engine.stats;

import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.RuleResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计记录器。
 *
 * <p>对应需求里的统计页：</p>
 * <ul>
 *   <li>开始记录的时间（游戏时间 + 真实时间）；</li>
 *   <li>刷出多少铁傀儡；</li>
 *   <li>统计到哪些村民刷新失败、原因是什么。</li>
 * </ul>
 *
 * <p>设计上刻意**不做定时采样**：只在「有一次召唤判定走完」时被喂一条结果。
 * 这样既不会因为高频采样拖慢服务端，也不会把「村民在睡觉所以没 panic」
 * 这类正常状态误记成失败。</p>
 */
public final class StatsRecorder {

    /** 记录开始的游戏时间（tick）。 */
    private long startGameTime = -1;
    /** 记录开始时的真实时间（毫秒）。 */
    private long startWallClock = -1;

    /** 累计刷出的铁傀儡数量。 */
    private long golemsSpawned = 0;
    /** 累计观察到的召唤判定次数（成功 + 失败）。 */
    private long summonAttempts = 0;

    /** 失败聚合：key = villagerId + "|" + reasonCode。 */
    private final Map<String, FailureRecord> failures = new LinkedHashMap<String, FailureRecord>();
    /** 按村民聚合的失败总次数，用于「哪几只村民一直不合格」的排序。 */
    private final Map<Integer, Long> failureByVillager = new LinkedHashMap<Integer, Long>();

    /**
     * 铁傀儡生命周期追踪（离开检测范围用时、存活时长）。
     *
     * <p>放在 StatsRecorder 里是因为它们都属于「机器的产出质量」这一组统计。
     * 具体追踪逻辑在 {@link GolemLifecycleTracker}，这里只做聚合入口。</p>
     */
    private final GolemLifecycleTracker lifecycle = new GolemLifecycleTracker();

    /**
     * 实物统计：漏斗销毁掉的物品。
     *
     * <p>独立成一个对象，因为它的时间基准和分组方式都不同
     * （见 {@link ItemDestroyStats} 的说明）。</p>
     */
    private final ItemDestroyStats itemDestroy = new ItemDestroyStats();

    /** 最近的 N 次事件，供界面滚动显示。 */
    private final List<Event> recentEvents = new ArrayList<Event>();
    private final int recentLimit;

    /** 一条时间线事件。 */
    public static final class Event {
        public final long gameTime;
        /** {@code "spawn"} 或 {@code "fail"}。 */
        public final String type;
        public final int villagerEntityId;
        /** 成功时为 null。 */
        public final String reasonCode;

        public Event(long gameTime, String type, int villagerEntityId, String reasonCode) {
            this.gameTime = gameTime;
            this.type = type;
            this.villagerEntityId = villagerEntityId;
            this.reasonCode = reasonCode;
        }
    }

    public StatsRecorder() {
        this(200);
    }

    public StatsRecorder(int recentLimit) {
        this.recentLimit = Math.max(1, recentLimit);
    }

    /** 开始（或重置）记录。 */
    public void start(long gameTime, long wallClockMillis) {
        this.startGameTime = gameTime;
        this.startWallClock = wallClockMillis;
        this.golemsSpawned = 0;
        this.summonAttempts = 0;
        this.failures.clear();
        this.failureByVillager.clear();
        this.recentEvents.clear();
        this.lifecycle.reset();
        // ⚠ 实物统计的起始时间必须跟主统计对齐。
        //   0.42.0 之前两者各自独立：主统计在"点开始记录"时设，
        //   实物统计在"第一次销毁物品"时设 —— 于是两块统计的
        //   "每小时"用的是不同分母，界面上数字对不上。
        this.itemDestroy.reset();
        this.itemDestroy.syncStart(gameTime);
    }

    public boolean isStarted() {
        return startGameTime >= 0;
    }

    /** 实物统计（漏斗销毁的物品）。 */
    public ItemDestroyStats itemDestroy() {
        return itemDestroy;
    }

    /**
     * 从磁盘恢复一份统计数据。
     *
     * <p>与 {@link #start(long, long)} 的区别：start 会清零，restore 不会。
     * 恢复时必须保留已经累计的刷出数与失败记录，
     * 否则"关游戏就丢数据"这个问题等于没修。</p>
     *
     * <p>⚠ 只应由 {@link StatsCodec} 调用。外部调用者请用 start()。</p>
     */
    public void restore(long startGameTime, long startWallClock,
                        long golemsSpawned, long summonAttempts) {
        this.startGameTime = startGameTime;
        this.startWallClock = startWallClock;
        this.golemsSpawned = Math.max(0, golemsSpawned);
        this.summonAttempts = Math.max(0, summonAttempts);
        // 失败记录由 restoreFailure 逐条补，这里不清空（可能在 read 过程中已经填了）
    }

    /**
     * 恢复一条失败记录。由 {@link StatsCodec} 逐行调用。
     *
     * <p>不做任何校验以外的加工：count 原样写回，
     * lastValue / lastThreshold 允许 NaN（表示当时没有数值可显示）。</p>
     */
    public void restoreFailure(int villagerEntityId, String reasonCode, long count,
                               long lastGameTime, double lastValue, double lastThreshold) {
        if (reasonCode == null || reasonCode.isEmpty() || count <= 0) {
            return;
        }
        String key = villagerEntityId + "|" + reasonCode;
        FailureRecord rec = failures.get(key);
        if (rec == null) {
            rec = new FailureRecord(villagerEntityId, reasonCode);
            failures.put(key, rec);
        }
        rec.count = count;
        rec.lastGameTime = lastGameTime;
        rec.lastValue = lastValue;
        rec.lastThreshold = lastThreshold;

        Long n = failureByVillager.get(villagerEntityId);
        failureByVillager.put(villagerEntityId, n == null ? count : n + count);
    }

    public long startGameTime() {
        return startGameTime;
    }

    public long startWallClock() {
        return startWallClock;
    }

    /** 记录一次成功刷出。 */
    public void recordSpawn(long gameTime, int villagerEntityId) {
        ensureStarted(gameTime);
        golemsSpawned++;
        summonAttempts++;
        pushEvent(new Event(gameTime, "spawn", villagerEntityId, null));
    }

    /**
     * 记录一次失败判定。
     *
     * @param villager 该村民快照（取 id）
     * @param failure  第一个失败的条件结果
     */
    public void recordFailure(long gameTime, VillagerSnapshot villager, RuleResult failure) {
        ensureStarted(gameTime);
        summonAttempts++;
        String code = failure.reasonCode == null ? "unknown" : failure.reasonCode;
        String key = villager.entityId + "|" + code;
        FailureRecord rec = failures.get(key);
        if (rec == null) {
            rec = new FailureRecord(villager.entityId, code);
            failures.put(key, rec);
        }
        rec.hit(gameTime, failure.value, failure.threshold);
        Long n = failureByVillager.get(villager.entityId);
        failureByVillager.put(villager.entityId, n == null ? 1L : n + 1L);
        pushEvent(new Event(gameTime, "fail", villager.entityId, code));
    }

    /**
     * 记录失败时同时检查其他条件。
     *
     * <p>现实里一次判定会同时有多个条件不满足（比如既没睡觉、附近又有傀儡）。
     * 只记第一个会掩盖真正的大头，所以这里把**全部失败条件**都记一遍，
     * 但计数时每个村民每次判定只算一次「主因」，其余记为「并发原因」。</p>
     *
     * <p><b>⚠ 这里不增加 summonAttempts。</b></p>
     *
     * <p>第一版在这里写了 {@code summonAttempts++}，结果是：
     * 每次扫描（每 10 tick = 0.5 秒）只要发现有村民不满足就 +1。
     * 于是"判定次数"变成了"我扫了多少次"，而不是"村民真的尝试召唤了多少次"。</p>
     *
     * <p>实测数据（用户 3 小时的统计）：
     * 刷出 108 只，判定次数显示 11838 —— 平均 110 次判定换 1 只傀儡。
     * 而原版召唤周期是 100 tick（5 秒），3 小时最多也就 2228 次真实尝试。
     * 数字差了 5 倍，性质上完全无法用来判断机器效率。</p>
     *
     * <p>现在 {@code summonAttempts} 只由 {@link #recordAttempt(long)} 增加，
     * 而那个方法由「原版真正调用 spawnGolemIfNeeded 的时刻」驱动。</p>
     */
    public void recordFailureDetail(long gameTime, VillagerSnapshot villager, Collection<RuleResult> allFailures) {
        if (allFailures == null || allFailures.isEmpty()) {
            return;
        }
        ensureStarted(gameTime);
        for (RuleResult r : allFailures) {
            String code = r.reasonCode == null ? "unknown" : r.reasonCode;
            String key = villager.entityId + "|" + code;
            FailureRecord rec = failures.get(key);
            if (rec == null) {
                rec = new FailureRecord(villager.entityId, code);
                failures.put(key, rec);
            }
            rec.hit(gameTime, r.value, r.threshold);
        }
        Long n = failureByVillager.get(villager.entityId);
        failureByVillager.put(villager.entityId, n == null ? 1L : n + 1L);
        RuleResult primary = null;
        for (Condition c : Condition.values()) {
            for (RuleResult r : allFailures) {
                if (r.condition == c) {
                    primary = r;
                    break;
                }
            }
            if (primary != null) {
                break;
            }
        }
        pushEvent(new Event(gameTime, "fail", villager.entityId,
                primary == null ? "unknown" : primary.reasonCode));
    }

    /**
     * 记录「一次真正的召唤尝试」。
     *
     * <p>调用时机：模拟原版 VillagerPanicTrigger 的节奏 ——
     * 每 100 tick（5 秒）检查一次，若有村民处于恐慌且群体达标，
     * 才算一次真实的尝试。</p>
     *
     * <p>这与"扫描到失败"是两件不同的事：</p>
     * <ul>
     *   <li>扫描是客户端为了显示，每 10 tick 一次；</li>
     *   <li>真实召唤是原版服务端每 100 tick 一次，且要有村民真的在恐慌。</li>
     * </ul>
     *
     * <p>只有后者能用来算"多少次尝试换来一只傀儡"。</p>
     */
    public void recordAttempt(long gameTime) {
        ensureStarted(gameTime);
        summonAttempts++;
    }

    /** 铁傀儡生命周期追踪器。 */
    public GolemLifecycleTracker lifecycle() {
        return lifecycle;
    }

    public long golemsSpawned() {
        return golemsSpawned;
    }

    public long summonAttempts() {
        return summonAttempts;
    }

    /** 刷出速率（每小时），基于游戏时间；记录未开始或刚开始时返回 0。 */
    public double golemsPerHour(long nowGameTime) {
        long elapsed = nowGameTime - startGameTime;
        if (startGameTime < 0 || elapsed <= 0) {
            return 0;
        }
        // ⚠ 用 ItemDestroyStats.TICKS_PER_HOUR 而不是字面量，
        //   避免两处统计各写一份定时数（0.42.0 就是因为这里 72000、
        //   那边 1000，导致上下数据差 72 倍）。
        double hours = (double) elapsed / ItemDestroyStats.TICKS_PER_HOUR;
        return golemsSpawned / hours;
    }

    // ==================================================================
    // 铁锭产量估算
    // ==================================================================

    /**
     * 单只铁傀儡的铁锭掉落期望值。
     *
     * <p><b>数据来源（1.21.1 官方 jar 的战利品表，已核实）：</b></p>
     * <pre>
     * data/minecraft/loot_table/entities/iron_golem.json
     *   pools[1].entries[0] = minecraft:iron_ingot
     *   set_count = uniform(min=3, max=5)
     * </pre>
     *
     * <p>均匀分布 3~5 的期望是 (3+5)/2 = <b>4</b>。</p>
     *
     * <p>⚠ 另一个 pool 掉 0~2 个虞美人，与本估算无关，不计入。</p>
     *
     * <p>⚠ 前提是傀儡被玩家或玩家驯服的狼杀死时才算"玩家击杀"，
     * 掉落正常。如果是掉进岩浆、被仙人掌扎死、被其他生物打死，
     * 仍然会掉落（铁傀儡没有"必须玩家击杀"的限制），所以这个期望值通用。</p>
     */
    public static final double IRON_INGOTS_PER_GOLEM = 4.0;

    /**
     * 每小时铁锭产量估算。
     *
     * <p>计算方式：{@code 傀儡每小时产量 × 单只掉落期望}。</p>
     *
     * <p><b>为什么是"估算"而不是"精确值"：</b></p>
     * <ul>
     *   <li>掉落是 3~5 的随机数，只有长期平均才收敛到 4；</li>
     *   <li>样本少的时候（比如刚开 5 分钟），
     *       实际产量可能偏离估算 20% 以上；</li>
     *   <li>如果有傀儡被玩家手动打死但没捡、或掉进虚空，
     *       估算会偏高。</li>
     * </ul>
     *
     * <p>所以界面上要明确标注"估算"，不能让玩家当成精确产量。</p>
     *
     * @param nowGameTime 当前游戏时间
     * @return 每小时铁锭数；还没开始记录时返回 0
     */
    public double ironIngotsPerHour(long nowGameTime) {
        return golemsPerHour(nowGameTime) * IRON_INGOTS_PER_GOLEM;
    }

    /**
     * 累计铁锭估算（从开始记录到现在一共）。
     *
     * <p><b>为什么必须同时给出"累计"和"每小时"：</b>
     * 用户看到「刷出 245 只」和「铁锭 450 个/小时」时，会自然地把两个数
     * 直接对比，得出"一只才掉 1.8 个"的错误结论 ——
     * 因为一个是**累计总量**、一个是**每小时速率**，单位根本不同。</p>
     *
     * <p>245 只按期望应产出 980 个铁锭。界面必须能直接回答
     * "我刷了这么多只，应该有多少铁锭"，而不是只给速率让玩家自己换算。</p>
     */
    public double ironIngotsTotal() {
        return golemsSpawned * IRON_INGOTS_PER_GOLEM;
    }

    /** 全部失败记录，按次数从多到少。 */
    public List<FailureRecord> failuresByCount() {
        List<FailureRecord> list = new ArrayList<FailureRecord>(failures.values());
        Collections.sort(list, new Comparator<FailureRecord>() {
            @Override
            public int compare(FailureRecord a, FailureRecord b) {
                return Long.compare(b.count, a.count);
            }
        });
        return list;
    }

    /** 按原因代码汇总（跨村民合并），按次数从多到少。 */
    public Map<String, Long> failuresByReason() {
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        for (FailureRecord r : failures.values()) {
            Long n = m.get(r.reasonCode);
            m.put(r.reasonCode, n == null ? r.count : n + r.count);
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<Map.Entry<String, Long>>(m.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        Map<String, Long> sorted = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Long> e : entries) {
            sorted.put(e.getKey(), e.getValue());
        }
        return sorted;
    }

    /** 一直不合格的村民（按失败次数排序）。 */
    public List<Map.Entry<Integer, Long>> worstVillagers(int limit) {
        List<Map.Entry<Integer, Long>> list = new ArrayList<Map.Entry<Integer, Long>>(failureByVillager.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<Integer, Long>>() {
            @Override
            public int compare(Map.Entry<Integer, Long> a, Map.Entry<Integer, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        if (list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
    }

    public List<Event> recentEvents() {
        return Collections.unmodifiableList(recentEvents);
    }

    /**
     * 清空统计，回到「还没开始」的状态。
     *
     * <p><b>⚠ 必须把 startGameTime 一起清掉。</b></p>
     *
     * <p>第一版只清了计数，没清开始时间，后果是：</p>
     * <ul>
     *   <li>界面上「开始记录」还显示清空前的时刻 ——
     *       用户两次截图都是「第 1 天 06:31」，但刷出数从 108 变成 94，
     *       明显清空过，开始时间却没变；</li>
     *   <li>更严重的是「平均产量」的分母仍然是旧起点：
     *       清空后刷 94 只，却按「从第 1 天 06:31 开始」来算，
     *       产量被系统性低估。这正是用户反馈「效率对不上」的直接原因。</li>
     * </ul>
     *
     * <p>同时也要清 {@link #lifecycle}，否则「平均离开检测范围」
     * 「平均存活时长」会保留清空前的样本 ——
     * 用户两次截图里这两个数一模一样（5.6 秒 / 54.2 秒），就是没清干净的证据。</p>
     *
     * <p>清空后 startGameTime = -1，下一次 {@link #ensureStarted}
     * 会用当时的游戏时间重新起算。</p>
     */
    public void reset() {
        failures.clear();
        failureByVillager.clear();
        recentEvents.clear();
        golemsSpawned = 0;
        summonAttempts = 0;
        // ⚠ 这三行是第一版漏掉的，直接导致「开始时间不变、产量算错」
        this.startGameTime = -1;
        this.startWallClock = -1;
        this.lifecycle.reset();
        // 实物统计一起清 —— 用户点"清空统计"时预期是全部归零，
        // 留一半在界面上会让人以为没清干净。
        this.itemDestroy.reset();
    }

    private void ensureStarted(long gameTime) {
        if (startGameTime < 0) {
            start(gameTime, System.currentTimeMillis());
        }
    }

    private void pushEvent(Event e) {
        recentEvents.add(e);
        while (recentEvents.size() > recentLimit) {
            recentEvents.remove(0);
        }
    }

    @Override
    public String toString() {
        return "Stats{start=" + startGameTime
                + " spawned=" + golemsSpawned
                + " attempts=" + summonAttempts
                + " failureKinds=" + failures.size() + "}";
    }
}
