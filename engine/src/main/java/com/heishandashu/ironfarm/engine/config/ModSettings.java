package com.heishandashu.ironfarm.engine.config;

/**
 * 模组设置。
 *
 * <p>对应需求里「按快捷键打开模组设置页面」中的六个开关。这里只放**数据模型**，
 * 界面在后面的阶段做。之所以先做模型，是因为渲染层、判定层、统计层都要读它，
 * 先把契约钉死，界面接上来的时候不需要再改引擎。</p>
 *
 * <p>开关全部默认开启 —— 玩家的第一印象应该是"能看见"，
 * 而不是"打开了却什么都没有"。</p>
 *
 * <p>落盘格式见 {@link SettingsCodec}：手写的 key=value，
 * 不引任何配置库。这样子模块只需要一个文件读写，也便于玩家手改。</p>
 */
public final class ModSettings {

    // ==================================================================
    // 需求指定的六个开关
    // ==================================================================

    /** 一、模组总开关。关掉后所有功能停止，包括头顶图标与统计。 */
    public boolean masterEnabled = true;

    /** 二、村民头顶信息显示开关（三个图标）。 */
    public boolean villagerOverlayEnabled = true;

    /** 三、彩色线条指示村民绑定的床。 */
    public boolean bedLinesEnabled = true;

    /** 四、矩形框显示铁傀儡可刷范围。 */
    public boolean spawnBoxEnabled = true;

    /**
     * 五、彩色线条指示怪物正在恐吓哪些村民。
     *
     * <p>需求原文写的是「僵尸」，但引擎支持全部 13 类恐吓源，
     * 所以实际行为是"所有正在恐吓村民的怪物"，僵尸只是其中一种。</p>
     *
     * <p>距离不够生效的线画成虚线 —— 数据由
     * {@link com.heishandashu.ironfarm.engine.panic.PanicLink#blockedByDistance()} 提供。</p>
     */
    public boolean panicLinesEnabled = true;

    /** 六、显示村民检测铁傀儡的矩形范围（±16 立方体）。 */
    public boolean golemDetectBoxEnabled = true;

    // ==================================================================
    // 渲染相关（需求未指定，取保守值）
    // ==================================================================

    /** 头顶图标的最大显示距离（格）。超出则不渲染，避免远处密密麻麻。 */
    public double overlayMaxDistance = 32.0;

    /** 线条的最大显示距离（格）。 */
    public double lineMaxDistance = 48.0;

    /** 是否只在手持特定物品时显示。默认关闭，保持"打开就能看见"。 */
    public boolean requireHoldingItem = false;

    /** 触发显示所需手持物品的注册名；requireHoldingItem=false 时忽略。 */
    public String requiredItemId = "minecraft:iron_ingot";

    /** 图标是否随距离缩小（远处小一点，避免糊在一起）。 */
    public boolean scaleByDistance = true;

    // ==================================================================
    // 语言
    // ==================================================================

    /**
     * 界面语言。
     *
     * <p>{@code "auto"} 表示跟随游戏语言。其它值为
     * {@link com.heishandashu.ironfarm.engine.i18n.Lang#supported()} 里的语言代码。</p>
     */
    public String locale = "auto";

    // ==================================================================
    // 判定相关
    // ==================================================================

    /**
     * 严格模式：只有已登记的生物才算恐吓源。
     *
     * <p>默认开启，否则一只路过的无关怪物就会在界面上画出噪点。</p>
     */
    public boolean scaryMobStrictMode = true;

    /** 是否把判定结果写进统计。关掉后统计页只累计刷出数，不记失败原因。 */
    public boolean statsRecordFailures = true;

    /**
     * 白色羊毛漏斗销毁：漏斗输出方向指向白色羊毛时，清空其内容并统计。
     *
     * <p>默认关闭 —— 它会真的**销毁玩家的物品**，属于破坏性功能，
     * 不该在玩家不知情的情况下生效。</p>
     */
    public boolean woolHopperEnabled = false;
    /**
     * 诊断日志。
     *
     * <p>本机跑不起客户端，无法确认"图标画了没有"。打开后每 10 秒打一行
     * 扫描/筛选/渲染三层的数据，一眼看出问题在哪一层。</p>
     */
    public boolean debugLog = true;   // 0.15.0 临时默认开：头顶图标还没验证成功，先让日志说话

    /** 是否在聊天栏提示刷出（默认关闭，避免刷屏）。 */
    public boolean chatNotifyOnSpawn = false;

    // ==================================================================

    public ModSettings copy() {
        ModSettings s = new ModSettings();
        s.masterEnabled = masterEnabled;
        s.villagerOverlayEnabled = villagerOverlayEnabled;
        s.bedLinesEnabled = bedLinesEnabled;
        s.spawnBoxEnabled = spawnBoxEnabled;
        s.panicLinesEnabled = panicLinesEnabled;
        s.golemDetectBoxEnabled = golemDetectBoxEnabled;
        s.overlayMaxDistance = overlayMaxDistance;
        s.lineMaxDistance = lineMaxDistance;
        s.requireHoldingItem = requireHoldingItem;
        s.requiredItemId = requiredItemId;
        s.scaleByDistance = scaleByDistance;
        s.locale = locale;
        s.scaryMobStrictMode = scaryMobStrictMode;
        s.statsRecordFailures = statsRecordFailures;
        s.chatNotifyOnSpawn = chatNotifyOnSpawn;
        return s;
    }

    /**
     * 任意一个「显示在世界上」的开关是否打开。
     * 用于快速跳过渲染，避免每帧都进判断。
     */
    public boolean anyWorldOverlayEnabled() {
        return masterEnabled
                && (villagerOverlayEnabled || bedLinesEnabled || spawnBoxEnabled
                || panicLinesEnabled || golemDetectBoxEnabled);
    }

    /** 是否需要扫描世界数据（全部显示开关都关掉时可以不扫）。 */
    public boolean needsWorldScan() {
        return masterEnabled && anyWorldOverlayEnabled();
    }

    /** 解析实际使用的语言代码（处理 "auto"）。 */
    public String effectiveLocale() {
        if (locale == null || locale.isEmpty() || "auto".equalsIgnoreCase(locale)) {
            return com.heishandashu.ironfarm.engine.i18n.Lang.autoDetect();
        }
        return com.heishandashu.ironfarm.engine.i18n.Lang.normalize(locale);
    }
}
