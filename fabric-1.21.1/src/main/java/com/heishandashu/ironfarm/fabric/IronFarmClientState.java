package com.heishandashu.ironfarm.fabric;

import com.heishandashu.ironfarm.engine.config.ModSettings;
import com.heishandashu.ironfarm.engine.eval.ConditionEngine;
import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.i18n.Lang;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.panic.PanicAnalysis;
import com.heishandashu.ironfarm.engine.panic.PanicResolver;
import com.heishandashu.ironfarm.engine.panic.ScaryMobRegistry;
import com.heishandashu.ironfarm.engine.panic.ScaryMobSnapshot;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanner;
import com.heishandashu.ironfarm.engine.stats.StatsRecorder;
import com.heishandashu.ironfarm.engine.ui.UiModel;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import com.heishandashu.ironfarm.fabric.adapter.WorldBlockQuery;
import com.heishandashu.ironfarm.fabric.adapter.WorldSightQuery;
import com.heishandashu.ironfarm.fabric.collect.ScaryMobCollector;
import com.heishandashu.ironfarm.fabric.collect.SingleplayerBridge;
import com.heishandashu.ironfarm.fabric.collect.VillagerCollector;
import com.heishandashu.ironfarm.fabric.config.SettingsIO;
import com.heishandashu.ironfarm.fabric.config.StatsIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端侧的数据中枢。
 *
 * <p>每 {@link #SCAN_INTERVAL_TICKS} tick 扫一次玩家附近的村民与怪物，
 * 跑一遍引擎，把结果缓存起来给渲染层读。</p>
 *
 * <p>为什么放客户端而不是服务端：需求全是纯显示功能，不需要服务端权威数据；
 * 放服务端就要写网络包同步，体积和复杂度都翻倍。</p>
 */
public final class IronFarmClientState {

    /** 扫描间隔：10 tick（0.5 秒）。再快没有意义，村民状态不是每 tick 都变。 */
    public static final int SCAN_INTERVAL_TICKS = 10;
    /**
     * 原版真实召唤尝试的间隔：100 tick = 5 秒。
     *
     * <p>来源：VillagerPanicTrigger.tick 里的 {@code lrem 100}。</p>
     */
    public static final int ATTEMPT_INTERVAL_TICKS = 100;

    /** 扫描半径（格）：围绕玩家。 */
    public static final double SCAN_RADIUS = 48.0;

    private static final IronFarmClientState INSTANCE = new IronFarmClientState();

    public static IronFarmClientState get() {
        return INSTANCE;
    }

    // ---- 配置（运行时可热切换） ----
    private ModSettings settings = new ModSettings();
    private Lang lang = Lang.load(Lang.autoDetect());
    private String loadedLocale = null;

    public VersionProfile profile = VersionProfile.MODERN;
    public final ScaryMobRegistry mobRegistry = ScaryMobRegistry.vanilla();

    // ---- 运行时 ----
    private StatsRecorder stats = new StatsRecorder();

    private ConditionEngine engine = new ConditionEngine(VersionProfile.MODERN);
    private SpawnScanner spawnScanner = new SpawnScanner(VersionProfile.MODERN);
    private UiModel uiModel = new UiModel(lang);

    // ---- 最近一次扫描结果（渲染层读这里） ----
    private List<VillagerSnapshot> villagers = new ArrayList<VillagerSnapshot>();
    private List<VillagerReport> reports = new ArrayList<VillagerReport>();
    private List<ScaryMobSnapshot> scaryMobs = new ArrayList<ScaryMobSnapshot>();
    private PanicAnalysis panicAnalysis = PanicResolver.emptyAnalysis();
    /** 每只村民的生成点扫描结果（entityId → scan），供渲染层画合法点用。 */
    private final java.util.Map<Integer, SpawnScanResult> spawnScans =
            new java.util.HashMap<Integer, SpawnScanResult>();
    private long lastScanTick = -1;
    private long lastGameTime = 0;
    /** 漏斗销毁器：延迟创建（需要 stats 已就绪）。 */
    private com.heishandashu.ironfarm.fabric.collect.HopperDestructor hopperDestructor;
    /** 上次记录"真实召唤尝试"的游戏时间。 */
    private long lastAttemptTick = -1;
    /** 上次自动保存统计的时刻（游戏时间）。 */
    private long lastStatsSaveTick = -1;
    /** 多人游戏里统计不完整 —— 界面要据此显示提示。 */
    private boolean statsComplete = true;

    private IronFarmClientState() {
        // 注入物品名解析器：引擎不含 Minecraft，拿不到本地化物品名，
        // 必须由适配层提供（否则界面显示 iron_ingot 这种原始 ID）。
        this.uiModel.setItemNameResolver(id -> {
            try {
                var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
                if (rl == null) {
                    return null;
                }
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                if (item == null) {
                    return null;
                }
                return new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    // ==================================================================
    // 配置
    // ==================================================================

    public ModSettings settings() {
        return settings;
    }

    public Lang lang() {
        return lang;
    }

    public UiModel ui() {
        return uiModel;
    }

    /** 从磁盘加载设置并应用。启动时调用一次。 */
    public void loadSettings() {
        this.settings = SettingsIO.load();
        applySettings();
    }

    /**
     * 加载统计数据。
     *
     * <p>单独一个方法而不是塞进 {@link #loadSettings()}：
     * 统计的读写时机与设置不同（设置是玩家手动改，统计是自动累积）。</p>
     */
    public void loadStats() {
        this.stats = StatsIO.load();
    }

    /** 立即保存统计。打开统计页时调用，减少丢数据的窗口。 */
    public void saveStatsNow() {
        StatsIO.save(stats);
        lastStatsSaveTick = lastGameTime;
    }

    public StatsRecorder stats() {
        return stats;
    }

    /**
     * 统计是否完整。
     *
     * <p>多人游戏里客户端收不到服务端的 {@code ENTITY_LOAD} 事件，
     * 「刷出多少铁傀儡」这一项永远是 0。界面必须说明，
     * 否则玩家会以为机器坏了。</p>
     */
    public boolean statsComplete() {
        return statsComplete;
    }

    /** 保存当前设置到磁盘。 */
    public boolean saveSettings() {
        return SettingsIO.save(settings);
    }

    /**
     * 设置改动后重新应用。
     *
     * <p>改变语言、严格模式这类会影响到构造参数的项必须在这里重建对象，
     * 否则玩家改完看起来"没生效"。</p>
     */
    public void applySettings() {
        Diagnostics.setEnabled(settings.debugLog);
        String want = settings.effectiveLocale();
        if (!want.equals(loadedLocale)) {
            lang = Lang.load(want);
            loadedLocale = want;
            uiModel = new UiModel(lang);
        }
        mobRegistry.strictMode = settings.scaryMobStrictMode;
        engine = new ConditionEngine(profile);
        spawnScanner = new SpawnScanner(profile);
    }

    /** 切换语言（界面用）。 */
    public void setLocale(String locale) {
        settings.locale = locale;
        applySettings();
        saveSettings();
    }

    // ==================================================================
    // 每 tick
    // ==================================================================

    public void tick(Minecraft mc) {
        if (!settings.masterEnabled) {
            return;
        }
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            clear();
            return;
        }
        long now = level.getGameTime();
        lastGameTime = now;
        statsComplete = mc.getSingleplayerServer() != null;

        // 定期自动保存统计：关游戏/崩溃时不会丢太多
        if (lastStatsSaveTick < 0 || now - lastStatsSaveTick >= StatsIO.AUTOSAVE_INTERVAL_TICKS) {
            if (stats.isStarted()) {
                StatsIO.save(stats);
            }
            lastStatsSaveTick = now;
        }
        // 铁傀儡生命周期：每 tick 都要观察（不能按扫描节流），
        // 否则会漏掉「刷出后很快被收走」的傀儡 —— 恰恰是效率最好的那种。
        tickGolems(level, now);
        if (lastScanTick >= 0 && now - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }
        lastScanTick = now;

        if (!settings.needsWorldScan()) {
            // 所有显示开关都关了，但统计仍要继续（刷出数是服务端事件记的）
            clearScanOnly();
            return;
        }
        rescan(mc, level, now);
    }

    /**
     * 观察当前所有铁傀儡，喂给生命周期追踪器。
     *
     * <p><b>为什么每 tick 都要跑，而不是跟扫描一起节流：</b>
     * 扫描是 20 tick 一次（1 秒）。如果傀儡刷出后 0.5 秒就被水流收走，
     * 按扫描节流根本看不到它，「平均离开检测范围用时」会系统性偏大。</p>
     *
     * <p>这个方法的开销很小：只遍历一次实体列表、算一下方形距离，
     * 没有射线、没有方块查询。</p>
     */
    /**
     * 漏斗销毁扫描。
     *
     * <p>只在单机可用：漏斗内容是服务端方块实体数据。
     * 多人游戏里这里静默跳过 —— 不做假的统计。</p>
     *
     * <p>⚠ 必须每 tick 都跑。需求是"每刻都全部销毁"，
     * 如果跟扫描一起节流（10 tick 一次），漏斗会在两次扫描之间
     * 攒满物品，减速效果就打折扣了。</p>
     */
    /**
     * 漏斗销毁，在**服务端主线程**上执行。
     *
     * <p><b>⚠ 为什么不能在客户端 tick 里直接调：</b>
     * {@code ServerChunkCache.getChunkNow} 会检查线程，
     * 非服务端主线程一律返回 null —— 日志表现是 {@code chunks=0}，
     * 功能完全静默失效（26.3 上已经栽过一次）。</p>
     */
    public void tickHopperOnServer(MinecraftServer server) {
        if (!settings.woolHopperEnabled) {
            return;
        }
        if (hopperDestructor == null) {
            // 传 supplier：loadStats() 会替换 stats 字段。
            hopperDestructor = new com.heishandashu.ironfarm.fabric.collect.HopperDestructor(
                    this::stats);
        }
        for (ServerLevel level : server.getAllLevels()) {
            hopperDestructor.tick(level, null);
        }
    }
    private void tickGolems(ClientLevel level, long now) {
        java.util.Map<Integer, double[]> golems = new java.util.HashMap<Integer, double[]>();
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof IronGolem) {
                golems.put(Integer.valueOf(e.getId()),
                        new double[]{e.getX(), e.getY(), e.getZ()});
            }
        }
        if (golems.isEmpty()) {
            // 仍然要调用 observe：它负责把"已经消失的傀儡"结算成样本
            stats().lifecycle().observe(now, golems, id -> false);
            return;
        }
        final int r = profile.sensorRange;
        stats().lifecycle().observe(now, golems, id -> {
            double[] p = golems.get(Integer.valueOf(id));
            if (p == null) {
                return false;
            }
            // 与村民检测器同语义：以方块坐标为中心的 ±r 立方体
            int gx = (int) Math.floor(p[0]);
            int gy = (int) Math.floor(p[1]);
            int gz = (int) Math.floor(p[2]);
            for (VillagerSnapshot v : villagers) {
                if (Math.abs(v.pos.x - gx) <= r
                        && Math.abs(v.pos.y - gy) <= r
                        && Math.abs(v.pos.z - gz) <= r) {
                    return true;
                }
            }
            return false;
        });
    }

    private void rescan(Minecraft mc, ClientLevel level, long now) {
        AABB box = mc.player.getBoundingBox().inflate(SCAN_RADIUS);

        // 1) 村民
        List<Villager> rawVillagers = level.getEntitiesOfClass(Villager.class, box);
        List<VillagerSnapshot> snapshots = new ArrayList<VillagerSnapshot>(rawVillagers.size());
        for (Villager v : rawVillagers) {
            snapshots.add(collectClientSide(mc, v, now));
        }

        // 2) 怪物
        List<ScaryMobSnapshot> mobSnapshots = new ArrayList<ScaryMobSnapshot>();
        for (Entity e : level.entitiesForRendering()) {
            if (ScaryMobCollector.isCandidate(e) && e instanceof LivingEntity) {
                mobSnapshots.add(ScaryMobCollector.collect((LivingEntity) e));
            }
        }

        // 3) 恐慌分析（视线用真实射线）
        PanicAnalysis analysis = new PanicResolver(mobRegistry).analyze(
                snapshots, mobSnapshots, new WorldSightQuery(level));

        // 4) 用分析结果刷新每只村民的 panic 标记
        List<VillagerSnapshot> withPanic = new ArrayList<VillagerSnapshot>(snapshots.size());
        for (VillagerSnapshot s : snapshots) {
            withPanic.add(s.withPanic(analysis.isPanicking(s.entityId)));
        }

        // 5) 生成点扫描：只对"可能触发"的村民做，省开销
        WorldBlockQuery bq = new WorldBlockQuery(level);
        List<SpawnScanResult> scans = new ArrayList<SpawnScanResult>(withPanic.size());
        for (VillagerSnapshot s : withPanic) {
            boolean worthScanning = settings.spawnBoxEnabled
                    && engine.wantsToSpawnGolem(s, now)
                    && (analysis.isPanicking(s.entityId) || s.gossiping);
            scans.add(worthScanning ? spawnScanner.scan(s.pos, bq) : null);
        }

        // 6) 正式判定
        List<VillagerReport> out = new ArrayList<VillagerReport>(withPanic.size());
        for (int i = 0; i < withPanic.size(); i++) {
            out.add(engine.evaluate(withPanic.get(i), withPanic, now, scans.get(i)));
        }

        // 7) 失败归因入统计
        if (settings.statsRecordFailures) {
            for (VillagerReport r : out) {
                List<com.heishandashu.ironfarm.engine.rule.RuleResult> fails =
                        ConditionEngine.failuresOf(r);
                if (!fails.isEmpty()) {
                    stats.recordFailureDetail(now, r.snapshot, fails);
                }
            }
        }

        // 把扫描结果按村民存起来，渲染层要画"哪些格子真的能刷"
        // 8) 真实召唤尝试计数
        //
        // ⚠ 必须按**原版节奏**算，不能按扫描次数算。
        //
        // 原版 VillagerPanicTrigger 每 100 tick（5 秒）检查一次；
        // 客户端扫描则是每 10 tick 一次（为了显示流畅）。
        // 第一版把"扫描到失败"当成"一次尝试"，判定次数虚高 5 倍以上
        // （实测 3 小时显示 11838 次，真实上限只有约 2228 次）。
        //
        // 判据：距离上次记录满 100 tick，且确实有村民在恐慌
        // （没有恐慌村民时原版根本不会调用 spawnGolemIfNeeded）。
        if (now - lastAttemptTick >= ATTEMPT_INTERVAL_TICKS && analysis.hasAnyLink()) {
            stats.recordAttempt(now);
            lastAttemptTick = now;
        }

        this.spawnScans.clear();
        for (int i = 0; i < withPanic.size(); i++) {
            SpawnScanResult scan = scans.get(i);
            if (scan != null) {
                this.spawnScans.put(withPanic.get(i).entityId, scan);
            }
        }

        this.villagers = withPanic;
        this.reports = out;
        this.scaryMobs = mobSnapshots;
        this.panicAnalysis = analysis;

        // 诊断：扫到几只、数据是否完整
        if (settings.debugLog) {
            int complete = 0;
            for (VillagerSnapshot s : withPanic) {
                if (s.sleepDataKnown && s.golemMemoryKnown) {
                    complete++;
                }
            }
            Diagnostics.onScan(now, withPanic.size(), out.size(),
                    mc.getSingleplayerServer() != null, complete);
        }
    }

    /**
     * 采集一只村民：优先走单机桥（拿真实 Brain 数据），拿不到才回退。
     *
     * <p>这个顺序很重要 —— 单机是最常见场景，真实数据能给出确定的钩/叉，
     * 体验远好于满屏问号。</p>
     */
    private VillagerSnapshot collectClientSide(Minecraft mc, Villager v, long now) {
        VillagerSnapshot fromServer = SingleplayerBridge.collectIfAvailable(mc, v, now);
        if (fromServer != null) {
            return fromServer;
        }
        return VillagerCollector.collectClientSide(v);
    }

    private void clearScanOnly() {
        spawnScans.clear();
        villagers = new ArrayList<VillagerSnapshot>();
        reports = new ArrayList<VillagerReport>();
        scaryMobs = new ArrayList<ScaryMobSnapshot>();
        panicAnalysis = PanicResolver.emptyAnalysis();
    }

    private void clear() {
        clearScanOnly();
        lastScanTick = -1;
        lastAttemptTick = -1;
    }

    // ==================================================================
    // 渲染层读取
    // ==================================================================

    public List<VillagerSnapshot> villagers() {
        return villagers;
    }

    public List<VillagerReport> reports() {
        return reports;
    }

    public List<ScaryMobSnapshot> scaryMobs() {
        return scaryMobs;
    }

    public PanicAnalysis panicAnalysis() {
        return panicAnalysis;
    }

    public long lastGameTime() {
        return lastGameTime;
    }

    /** 某只村民的生成点扫描结果；没有扫描过返回 null。 */
    public SpawnScanResult spawnScanOf(int entityId) {
        return spawnScans.get(Integer.valueOf(entityId));
    }

    /** 按实体 id 找报告；找不到返回 null。 */
    public VillagerReport reportOf(int entityId) {
        for (VillagerReport r : reports) {
            if (r.entityId() == entityId) {
                return r;
            }
        }
        return null;
    }
}
