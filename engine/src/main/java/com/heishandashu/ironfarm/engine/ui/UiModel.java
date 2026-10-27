package com.heishandashu.ironfarm.engine.ui;

import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.i18n.Lang;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.RuleResult;
import com.heishandashu.ironfarm.engine.stats.FailureRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 界面数据模型：把引擎结果翻译成"一行一行可显示的文字"。
 *
 * <p><b>这一层的存在理由</b>：需求要求"先做骨架和引擎，界面最后再说"。
 * 但界面要显示什么内容、每行取哪个字段、状态怎么措辞，
 * 这些**不属于布局**，属于数据。把它们抽出来之后：</p>
 *
 * <ul>
 *   <li>界面阶段只负责"把 Row 画到屏幕上"，不用再想内容；</li>
 *   <li>这些行可以在没有游戏的环境里被完整测试（本工程正是这么做的）；</li>
 *   <li>换布局时数据层一行都不用改。</li>
 * </ul>
 *
 * <p>⚠ 这里**不含任何坐标/宽高/颜色** —— 那些属于布局，要等确认。</p>
 */
public final class UiModel {

    /** 一行的语义状态，决定用什么颜色/图标画（但不定具体颜色值）。 */
    public enum RowState {
        /** 满足 / 正常。 */
        OK,
        /** 不满足 / 有问题。 */
        BAD,
        /** 无法判定 / 数据缺失。 */
        UNKNOWN,
        /** 纯文本，无状态。 */
        INFO
    }

    /**
     * 一行显示内容。
     *
     * <p>刻意只有「标签 + 值 + 状态」三个字段：任何布局（竖排列表、两列表格、
     * tooltip）都能用这三个字段画出来。</p>
     */
    public static final class Row {
        /** 左侧标签（已翻译）。 */
        public final String label;
        /** 右侧值（已翻译）。 */
        public final String value;
        public final RowState state;
        /** 悬停提示（已翻译），可为 null。 */
        public final String tooltip;

        public Row(String label, String value, RowState state, String tooltip) {
            this.label = label;
            this.value = value;
            this.state = state;
            this.tooltip = tooltip;
        }

        @Override
        public String toString() {
            return "[" + state + "] " + label + ": " + value;
        }
    }

    /** 一个带标题的分组。 */
    public static final class Section {
        /** 分组标题（已翻译）。 */
        public final String title;
        public final List<Row> rows;

        public Section(String title, List<Row> rows) {
            this.title = title;
            this.rows = rows == null ? new ArrayList<Row>() : rows;
        }
    }

    private final Lang lang;

    /**
     * 物品 ID → 显示名的解析器。
     *
     * <p><b>为什么需要注入：</b>引擎是纯 Java、不含 Minecraft，拿不到物品的
     * 本地化名字。所以由适配层提供一个解析器，走原版 API。</p>
     *
     * <p>为 null 时退化成"去掉命名空间的 ID"，也就是 0.42.0 里显示
     * {@code iron_ingot} 那种样子 —— 用户反馈"物品名称不对"就是这个。</p>
     */
    private java.util.function.Function<String, String> itemNameResolver;

    public UiModel(Lang lang) {
        this.lang = lang;
    }

    // ==================================================================
    // 村民状态页
    // ==================================================================

    /**
     * 构造「村民状态」页的全部内容。
     *
     * <p>页头：标题 + 坐标 + 总结论。
     * 三个分组：个体条件 / 群体条件 / 生成点。</p>
     */
    public List<Section> villagerStatus(VillagerReport report) {
        List<Section> out = new ArrayList<Section>();

        // ---- 页头（作为第一个 Section，标题为空） ----
        List<Row> header = new ArrayList<Row>();
        header.add(new Row(
                lang.get("ironfarm.gui.villager_status.position"),
                report.pos().toString(),
                RowState.INFO, null));
        header.add(new Row(
                lang.get("ironfarm.gui.summary"),
                summaryText(report),
                summaryState(report), null));
        out.add(new Section(lang.format("ironfarm.gui.villager_status.title",
                String.valueOf(report.entityId())), header));

        // ---- 个体条件 ----
        List<Row> individual = new ArrayList<Row>();
        for (Condition c : new Condition[]{
                Condition.ADULT, Condition.SLEPT, Condition.NO_GOLEM_DETECTED}) {
            individual.add(conditionRow(report, c));
        }
        out.add(new Section(lang.get("ironfarm.gui.villager_status.individual"), individual));

        // ---- 触发状态 ----
        List<Row> triggers = new ArrayList<Row>();
        triggers.add(conditionRow(report, Condition.PANICKING));
        triggers.add(conditionRow(report, Condition.GOSSIPING));
        out.add(new Section(lang.get("ironfarm.gui.villager_status.trigger"), triggers));

        // ---- 群体条件 ----
        List<Row> group = new ArrayList<Row>();
        group.add(new Row(
                lang.get("ironfarm.gui.villager_status.participants"),
                report.panicParticipants() + " / " + report.gossipParticipants(),
                RowState.INFO, null));
        group.add(conditionRow(report, Condition.PANIC_GROUP));
        group.add(conditionRow(report, Condition.GOSSIP_GROUP));
        out.add(new Section(lang.get("ironfarm.gui.villager_status.group"), group));

        // ---- 生成点 ----
        List<Row> spawn = new ArrayList<Row>();
        spawn.add(conditionRow(report, Condition.SPAWN_POINT));
        out.add(new Section(lang.get("ironfarm.gui.villager_status.spawn"), spawn));

        return out;
    }

    /** 总结论文案。 */
    public String summaryText(VillagerReport report) {
        if (report.panicFarmReady()) {
            return lang.get("ironfarm.gui.villager_status.farm_ready_panic");
        }
        if (report.gossipFarmReady()) {
            return lang.get("ironfarm.gui.villager_status.farm_ready_gossip");
        }
        RuleResult f = report.primaryFailure();
        if (f == null) {
            return lang.get("ironfarm.gui.not_ready");
        }
        return lang.get("ironfarm.gui.not_ready") + " — " + lang.get(f.reasonLangKey());
    }

    private RowState summaryState(VillagerReport report) {
        if (report.panicFarmReady() || report.gossipFarmReady()) {
            return RowState.OK;
        }
        return report.hardConditionsUnknown() ? RowState.UNKNOWN : RowState.BAD;
    }

    /** 把一条条件结果转成一行。 */
    public Row conditionRow(VillagerReport report, Condition c) {
        RuleResult r = report.get(c);
        RowState state;
        String value;
        switch (r.status) {
            case PASS:
                state = RowState.OK;
                value = lang.get("ironfarm.pass");
                break;
            case FAIL:
                state = RowState.BAD;
                value = lang.get(r.reasonLangKey());
                break;
            default:
                state = RowState.UNKNOWN;
                value = lang.get("ironfarm.unknown");
                break;
        }
        // 有数值时补上"当前 x / 门槛 y"，让玩家知道差多少
        if (!Double.isNaN(r.value) && !Double.isNaN(r.threshold)) {
            if (r.threshold > 0) {
                value = value + "  (" + fmt(r.value) + " / " + fmt(r.threshold) + ")";
            }
        }
        return new Row(lang.get(c.langKey()), value, state, lang.get(c.descLangKey()));
    }

    private static String fmt(double d) {
        if (d == Long.MAX_VALUE) {
            return "∞";
        }
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    // ==================================================================
    // 统计页
    // ==================================================================

    /** 统计页的页头（开始时间 / 刷出数 / 判定次数 / 速率）。 */
    public List<Row> statsHeader(com.heishandashu.ironfarm.engine.stats.StatsRecorder stats,
                                 long nowGameTime) {
        List<Row> rows = new ArrayList<Row>();
        long start = stats.startGameTime();
        // 用户要求：不显示"开始记录是第几天几点"，而是"已记录 X 小时 Y 分"。
        // 理由：玩家想知道"这份统计跑了多久"，而不是"从游戏内第几天开始"。
        //
        // ⚠ 按**游戏时间**累计，所以开游戏加速时数字会跟着加速 ——
        //   这正是用户要的。
        String elapsedText = start < 0
                ? lang.get("ironfarm.unknown")
                : formatElapsed(nowGameTime - start);
        rows.add(new Row(lang.get("ironfarm.gui.stats.elapsed"), elapsedText,
                RowState.INFO, lang.get("ironfarm.gui.stats.elapsed.tip")));
        // 用户反馈：「判定次数」和「失败条目数」意义不大，去掉。
        //
        // 判定次数的原意是"多少次尝试换来一只傀儡"，但它受客户端扫描
        // 节流影响，本身就是估算；而失败条目数在修掉列表展示后已经没有
        // 对应的界面，纯数字没有解读价值。
        //
        // 保留的是能直接回答玩家问题的三个数：
        //   累计刷出多少只、每小时多少只、以及下面两行铁锭。
        rows.add(new Row(lang.get("ironfarm.gui.stats.spawned"),
                String.valueOf(stats.golemsSpawned()),
                RowState.INFO, lang.get("ironfarm.gui.stats.spawned.tip")));
        // ⚠ 单位必须写进标签里。
        //
        // 用户曾经把「刷出 245 只」和「铁锭 450 个/小时」直接对比，
        // 得出"一只才掉 1.8 个"的结论 —— 因为一个是累计、一个是速率。
        // 245 只按期望应是 980 个。
        //
        // 现在把「累计」和「每小时」分成两行，标签里都带单位。
        rows.add(new Row(lang.get("ironfarm.gui.stats.rate"),
                String.format(java.util.Locale.ROOT, "%.1f", stats.golemsPerHour(nowGameTime)),
                RowState.INFO, lang.get("ironfarm.gui.stats.rate.tip")));

        // 铁锭：累计（与"刷出 N 只"同一时间范围，可以直接对比）
        rows.add(new Row(lang.get("ironfarm.gui.stats.ingots_total"),
                String.format(java.util.Locale.ROOT, "%.0f", stats.ironIngotsTotal()),
                RowState.INFO, lang.get("ironfarm.gui.stats.ingots_total.tip")));

        // 铁锭：每小时（速率，衡量机器效率）
        rows.add(new Row(lang.get("ironfarm.gui.stats.ingots_per_hour"),
                String.format(java.util.Locale.ROOT, "%.0f", stats.ironIngotsPerHour(nowGameTime)),
                RowState.INFO, lang.get("ironfarm.gui.stats.ingots_per_hour.tip")));

        // ---- 铁傀儡生命周期（0.22.0 新增）----
        //
        // 这两个指标回答的是"机器效率"而不是"哪只村民不合格"：
        //   · 平均离开检测范围用时 —— 收集系统灵不灵敏
        //   · 平均存活时长       —— 有没有傀儡被卡住/被打死
        //
        // 两者对照着看才有意义：
        //   离开范围 << 存活时间 → 正常（掉下去后被收走，最后在收集点被杀）
        //   离开范围 ≈ 存活时间 → 傀儡一直没离开刷出区，很可能卡住了
        var lc = stats.lifecycle();
        rows.add(lifecycleRow(lang.get("ironfarm.gui.stats.avg_leave"),
                lc.averageTicksToLeaveDetection(),
                "ironfarm.gui.stats.avg_leave.tip", RowState.INFO));
        rows.add(lifecycleRow(lang.get("ironfarm.gui.stats.avg_alive"),
                lc.averageTicksAlive(),
                "ironfarm.gui.stats.avg_alive.tip", RowState.INFO));
        if (lc.discardedCount() > 0) {
            rows.add(new Row(lang.get("ironfarm.gui.stats.discarded"),
                    String.valueOf(lc.discardedCount()), RowState.UNKNOWN,
                    lang.get("ironfarm.gui.stats.discarded.tip")));
        }
        return rows;
    }

    /** 构造一行"时长"统计；没有样本时显示"暂无数据"。 */
    private Row lifecycleRow(String label, double ticks, String tipKey, RowState state) {
        String value;
        if (ticks < 0) {
            value = lang.get("ironfarm.gui.stats.no_samples");
        } else {
            value = formatTicks(ticks);
        }
        return new Row(label, value, state, lang.get(tipKey));
    }

    /**
     * 把 tick 数格式化成人类可读的时长。
     *
     * <p>20 tick = 1 秒，所以：
     * 不足 1 分钟 → "12.3 秒"
     * 1 分钟以上 → "3 分 05 秒"
     * </p>
     */
    public String formatTicks(double ticks) {
        double seconds = ticks / 20.0;
        if (seconds < 60) {
            return String.format(java.util.Locale.ROOT, "%.1f %s",
                    seconds, lang.get("ironfarm.seconds"));
        }
        int total = (int) Math.round(seconds);
        int m = total / 60;
        int s = total % 60;
        return String.format(java.util.Locale.ROOT, "%d %s %02d %s",
                m, lang.get("ironfarm.minutes"), s, lang.get("ironfarm.seconds"));
    }

    /** 统计页的失败列表（按次数从多到少）。 */
    public List<Row> statsFailures(com.heishandashu.ironfarm.engine.stats.StatsRecorder stats,
                                   int limit) {
        List<Row> rows = new ArrayList<Row>();
        List<FailureRecord> all = stats.failuresByCount();
        if (all.isEmpty()) {
            rows.add(new Row(lang.get("ironfarm.gui.stats.failures"), 
                    lang.get("ironfarm.gui.stats.no_failures"), RowState.INFO, null));
            return rows;
        }
        int n = 0;
        for (FailureRecord f : all) {
            if (n++ >= limit) {
                break;
            }
            rows.add(new Row(
                    lang.format("ironfarm.gui.stats.villager_short", String.valueOf(f.villagerEntityId)),
                    lang.format("ironfarm.gui.stats.failure_row2",
                            lang.get(f.reasonLangKey()), String.valueOf(f.count)),
                    RowState.BAD,
                    f.lastValue == 0 && f.lastThreshold == 0 ? null
                            : lang.format("ironfarm.gui.villager_status.value",
                                    fmt(f.lastValue), fmt(f.lastThreshold))));
        }
        return rows;
    }

    /** 统计页的按原因汇总。 */
    // ==================================================================
    // 实物统计（漏斗销毁的物品）
    // ==================================================================

    /**
     * 实物统计：总数量、平均每小时、以及每个物品的总量与速率。
     *
     * <p><b>与上面的统计必须视觉分隔</b>（用户明确要求"注意与上面数据分隔开"）——
     * 它衡量的是完全不同的东西：上面是"刷出来多少铁"，
     * 这里是"漏斗销毁掉多少东西"。</p>
     *
     * <p><b>单位一定写进标签里：</b>数量是"个"，速率是"个/小时"。
     * 用户之前把"累计 245 只"和"每小时 450 个"直接对比过，
     * 得出"一只才掉 1.8 个"的错误结论 —— 单位不写清就会被误读。</p>
     */
    public List<Row> itemStats(com.heishandashu.ironfarm.engine.stats.StatsRecorder stats,
                               long nowGameTime) {
        List<Row> rows = new ArrayList<Row>();
        com.heishandashu.ironfarm.engine.stats.ItemDestroyStats d = stats.itemDestroy();

        rows.add(new Row(lang.get("ironfarm.gui.items.total"),
                String.valueOf(d.totalItems()), RowState.INFO,
                lang.get("ironfarm.gui.items.total.tip")));

        rows.add(new Row(lang.get("ironfarm.gui.items.per_hour"),
                String.format(java.util.Locale.ROOT, "%.1f", d.itemsPerHour(nowGameTime)),
                RowState.INFO, lang.get("ironfarm.gui.items.per_hour.tip")));

        if (d.kinds() == 0) {
            rows.add(new Row(lang.get("ironfarm.gui.items.none"),
                    "", RowState.INFO, lang.get("ironfarm.gui.items.none.tip")));
            return rows;
        }

        // 分隔行：让"总计"与"逐项"在视觉上分开
        rows.add(new Row(lang.get("ironfarm.gui.items.by_item"), "",
                RowState.INFO, null));

        for (com.heishandashu.ironfarm.engine.stats.ItemDestroyStats.Entry e : d.sortedByCount()) {
            String name = itemName(e.itemId);
            String value = e.total + " / "
                    + String.format(java.util.Locale.ROOT, "%.1f",
                            d.perHourOf(e.itemId, nowGameTime))
                    + " " + lang.get("ironfarm.gui.items.unit_per_hour");
            rows.add(new Row(name, value, RowState.INFO, e.itemId));
        }
        return rows;
    }

    /**
     * 物品 ID → 显示名。
     *
     * <p>先查语言键（{@code ironfarm.item.<path>}），查不到就直接显示
     * 注册名去掉命名空间 —— 这样**任何模组的物品**都能显示，
     * 不会出现空行。这是必要的：漏斗里可能掉进任何东西。</p>
     */
    /** 注入物品名解析器（适配层调用）。 */
    public void setItemNameResolver(java.util.function.Function<String, String> resolver) {
        this.itemNameResolver = resolver;
    }

    private String itemName(String itemId) {
        // 优先用适配层提供的引擎外解析器（能拿到 MC 的本地化物品名）。
        if (itemNameResolver != null) {
            try {
                String resolved = itemNameResolver.apply(itemId);
                if (resolved != null && !resolved.isEmpty()) {
                    return resolved;
                }
            } catch (RuntimeException ignored) {
                // 解析失败就落到下面的兜底逻辑，不要因为显示名崩掉界面。
            }
        }
        String path = itemId;
        int colon = itemId.indexOf(':');
        if (colon >= 0) {
            path = itemId.substring(colon + 1);
        }
        String key = "ironfarm.item." + path;
        String translated = lang.get(key);
        // Lang.get 查不到通常返回 key 本身（见 Lang 的实现），
        // 这里同时兜住"返回 key"和"返回空"两种情况。
        if (translated == null || translated.isEmpty() || translated.equals(key)) {
            return path;
        }
        return translated;
    }
    public List<Row> statsByReason(com.heishandashu.ironfarm.engine.stats.StatsRecorder stats) {
        List<Row> rows = new ArrayList<Row>();
        Map<String, Long> byReason = stats.failuresByReason();
        if (byReason.isEmpty()) {
            return rows;
        }
        for (Map.Entry<String, Long> e : byReason.entrySet()) {
            rows.add(new Row(
                    lang.get("ironfarm.reason." + e.getKey()),
                    String.valueOf(e.getValue()),
                    RowState.BAD, null));
        }
        return rows;
    }

    /** 把游戏时间格式化成 "第 N 天 HH:MM"。 */
    /**
     * 把 tick 数格式化成"X 小时 Y 分"。
     *
     * <p>72000 tick = 1 小时（与 ItemDestroyStats.TICKS_PER_HOUR 一致）。
     * 用游戏时间而不是真实时间，所以游戏加速时这里会同步加速。</p>
     */
    public String formatElapsed(long ticks) {
        if (ticks <= 0) {
            return lang.get("ironfarm.gui.stats.elapsed_zero");
        }
        long totalMinutes = (ticks * 60L) / 72000L;
        long hh = totalMinutes / 60L;
        long mm = totalMinutes % 60L;
        return lang.format("ironfarm.gui.stats.elapsed_format",
                String.valueOf(hh), String.valueOf(mm));
    }

    public String formatGameTime(long gameTime) {
        long day = gameTime / 24000L;
        long inDay = gameTime % 24000L;
        // 游戏内 0 tick = 6:00，一天 24000 tick
        long totalMinutes = ((inDay * 1440L) / 24000L + 360L) % 1440L;
        long hh = totalMinutes / 60L;
        long mm = totalMinutes % 60L;
        return lang.format("ironfarm.gui.time_format",
                String.valueOf(day + 1),
                String.format(java.util.Locale.ROOT, "%02d:%02d", hh, mm));
    }
}
