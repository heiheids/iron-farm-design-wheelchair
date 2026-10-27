package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.eval.ConditionEngine;
import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.i18n.Lang;
import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.RuleResult;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.stats.StatsRecorder;
import com.heishandashu.ironfarm.engine.ui.UiModel;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面数据模型测试。
 *
 * <p>界面还没做，但"界面要显示什么"已经可以完整验证 ——
 * 这正是把 UI 数据与布局分开的价值。</p>
 */
class UiModelTest {

    private static final long NOW = 1_000_000L;
    private final Lang zh = Lang.load(Lang.ZH_CN);
    private final Lang en = Lang.load(Lang.EN_US);
    private final ConditionEngine engine = new ConditionEngine(VersionProfile.V1_21_1);

    private SpawnScanResult okSpawn(BlockPos3 c) {
        List<BlockPos3> pts = new ArrayList<BlockPos3>();
        pts.add(c.offset(0, 1, 0));
        return new SpawnScanResult(c, 8, 6, true, pts, null);
    }

    private VillagerReport readyReport() {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        for (int i = 0; i < 4; i++) {
            all.add(VillagerSnapshot.healthy(i + 1, i, 64, 0, NOW));
        }
        return engine.evaluateAll(all, NOW, okSpawn(all.get(0).pos)).get(0);
    }

    // ==================================================================
    // 村民状态页
    // ==================================================================

    @Test
    void villagerStatusHasAllSections() {
        UiModel ui = new UiModel(zh);
        List<UiModel.Section> sections = ui.villagerStatus(readyReport());
        // 页头 + 个体 + 触发 + 群体 + 生成点
        assertEquals(5, sections.size());
        assertTrue(sections.get(1).title.contains("个体"));
        assertTrue(sections.get(2).title.contains("触发"));
        assertTrue(sections.get(3).title.contains("群体"));
        assertTrue(sections.get(4).title.contains("生成点"));
    }

    @Test
    void everyConditionAppearsExactlyOnce() {
        UiModel ui = new UiModel(zh);
        List<UiModel.Section> sections = ui.villagerStatus(readyReport());
        List<String> labels = new ArrayList<String>();
        for (UiModel.Section s : sections) {
            for (UiModel.Row r : s.rows) {
                labels.add(r.label);
            }
        }
        for (Condition c : Condition.values()) {
            String name = zh.get(c.langKey());
            int n = 0;
            for (String l : labels) {
                if (l.equals(name)) {
                    n++;
                }
            }
            assertEquals(1, n, "条件 " + c.key() + " 应恰好出现一次，实际 " + n);
        }
    }

    @Test
    void readyVillagerShowsOkSummary() {
        UiModel ui = new UiModel(zh);
        VillagerReport r = readyReport();
        assertEquals(ui.summaryText(r), zh.get("ironfarm.gui.villager_status.farm_ready_panic"));
    }

    @Test
    void notReadyVillagerSummaryIncludesReason() {
        VillagerSnapshot v = VillagerSnapshot.of(1, 0, 64, 0); // 从没睡过
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        VillagerReport r = engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0);

        UiModel ui = new UiModel(zh);
        String s = ui.summaryText(r);
        assertTrue(s.contains(zh.get("ironfarm.gui.not_ready")));
        assertTrue(s.contains(zh.get("ironfarm.reason.never_slept")),
                "总结论必须说明原因，实际: " + s);
    }

    @Test
    void failedRowCarriesReasonAndValues() {
        VillagerSnapshot v = VillagerSnapshot.healthy(1, 0, 64, 0, NOW).withSlept(NOW - 30000);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        VillagerReport r = engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0);

        UiModel ui = new UiModel(zh);
        UiModel.Row row = ui.conditionRow(r, Condition.SLEPT);
        assertEquals(UiModel.RowState.BAD, row.state);
        assertTrue(row.value.contains(zh.get("ironfarm.reason.sleep_expired")));
        assertTrue(row.value.contains("30000"), "应带上实测值: " + row.value);
        assertTrue(row.value.contains("24000"), "应带上门槛: " + row.value);
    }

    @Test
    void unknownRowIsNotBad() {
        VillagerSnapshot v = VillagerSnapshot.unknownData(1, 0, 64, 0);
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        VillagerReport r = engine.evaluateAll(all, NOW, okSpawn(v.pos)).get(0);

        UiModel ui = new UiModel(zh);
        UiModel.Row row = ui.conditionRow(r, Condition.SLEPT);
        assertEquals(UiModel.RowState.UNKNOWN, row.state, "无法判定不能渲染成 BAD");
        assertEquals(zh.get("ironfarm.unknown"), row.value);
    }

    @Test
    void passesRowIsOk() {
        UiModel ui = new UiModel(zh);
        UiModel.Row row = ui.conditionRow(readyReport(), Condition.SLEPT);
        assertEquals(UiModel.RowState.OK, row.state);
        assertEquals(zh.get("ironfarm.pass"), row.value.substring(0, zh.get("ironfarm.pass").length()));
    }

    @Test
    void everyRowHasNonEmptyLabelInBothLanguages() {
        for (Lang lang : new Lang[]{zh, en}) {
            UiModel ui = new UiModel(lang);
            for (UiModel.Section s : ui.villagerStatus(readyReport())) {
                for (UiModel.Row r : s.rows) {
                    assertNotNull(r.label);
                    assertFalse(r.label.isEmpty(), lang.locale() + " 有空标签");
                    assertFalse(r.label.startsWith("ironfarm."),
                            lang.locale() + " 标签回落到键名: " + r.label);
                    assertNotNull(r.value);
                    assertFalse(r.value.startsWith("ironfarm."),
                            lang.locale() + " 值回落到键名: " + r.value);
                }
            }
        }
    }

    // ==================================================================
    // 统计页
    // ==================================================================

    /**
     * 统计页头部的基础数字。
     *
     * <p>⚠ 旧版断言 {@code rows.size() == 5}，0.22.0 加入两个生命周期指标后
     * 变成 7，测试就红了。行数属于会随需求变化的实现细节，
     * 所以这里改成「至少包含前 5 个基础行」+「按索引校验具体值」，
     * 后面再加行也不会误报。</p>
     */
    @Test
    void statsHeaderShowsCoreNumbers() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(1000, 0);
        stats.recordSpawn(2000, 1);
        stats.recordSpawn(3000, 2);

        UiModel ui = new UiModel(zh);
        List<UiModel.Row> rows = ui.statsHeader(stats, 72000);
        assertTrue(rows.size() >= 5, "至少要有开始时间/刷出数/判定次数/失败数/产量 5 行");
        assertTrue(rows.get(1).value.contains("2"), "刷出数应为 2: " + rows.get(1).value);
        assertTrue(rows.get(2).value.contains("2"), "判定次数应为 2");
    }

    /**
     * 0.22.0 新增：铁傀儡生命周期两个指标。
     *
     * <p>没有样本时必须显示"暂无数据"而不是 0 或 NaN ——
     * 显示 0 会让人以为傀儡瞬间就被收走了。</p>
     */
    @Test
    void statsHeaderShowsLifecyclePlaceholdersWhenNoSamples() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(0, 0);
        UiModel ui = new UiModel(zh);
        List<UiModel.Row> rows = ui.statsHeader(stats, 72000);

        boolean foundLeave = false;
        boolean foundAlive = false;
        for (UiModel.Row r : rows) {
            if (r.label.contains("离开检测")) {
                foundLeave = true;
                assertTrue(!r.value.contains("NaN"), "无样本时不能显示 NaN");
                assertTrue(!r.value.equals("0.0"), "无样本时不能显示 0，会误以为瞬间收走");
            }
            if (r.label.contains("存活时长")) {
                foundAlive = true;
                assertTrue(!r.value.contains("NaN"), "无样本时不能显示 NaN");
            }
        }
        assertTrue(foundLeave, "应有「平均离开检测范围」一行");
        assertTrue(foundAlive, "应有「平均存活时长」一行");
    }

    /** tick → 人类可读时长。 */
    @Test
    void formatTicksConvertsToSecondsAndMinutes() {
        UiModel ui = new UiModel(zh);
        // 20 tick = 1 秒
        assertTrue(ui.formatTicks(200).contains("10"), "200 tick 应为 10 秒: " + ui.formatTicks(200));
        // 1200 tick = 60 秒 = 1 分
        String oneMin = ui.formatTicks(1200);
        assertTrue(oneMin.contains("1"), "1200 tick 应显示 1 分钟: " + oneMin);
        // 3660 tick = 183 秒 = 3 分 03 秒
        String threeMin = ui.formatTicks(3660);
        assertTrue(threeMin.contains("3"), "3660 tick 应含 3 分钟: " + threeMin);
    }

    @Test
    void statsFailuresEmptyStateHasMessage() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(0, 0);
        UiModel ui = new UiModel(zh);
        List<UiModel.Row> rows = ui.statsFailures(stats, 10);
        assertEquals(1, rows.size());
        assertEquals(zh.get("ironfarm.gui.stats.no_failures"), rows.get(0).value);
    }

    @Test
    void statsFailuresSortedByCount() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(0, 0);
        for (int i = 0; i < 5; i++) {
            stats.recordFailure(i, VillagerSnapshot.of(7, 0, 64, 0),
                    RuleResult.fail(Condition.SLEPT, "never_slept"));
        }
        for (int i = 0; i < 2; i++) {
            stats.recordFailure(i, VillagerSnapshot.of(3, 0, 64, 0),
                    RuleResult.fail(Condition.PANICKING, "not_panicking"));
        }
        UiModel ui = new UiModel(zh);
        List<UiModel.Row> rows = ui.statsFailures(stats, 10);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).label.contains("7"), "失败最多的村民应排第一: " + rows.get(0).label);
        assertTrue(rows.get(0).value.contains("5"));
    }

    @Test
    void statsFailuresRespectsLimit() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(0, 0);
        for (int i = 0; i < 20; i++) {
            stats.recordFailure(i, VillagerSnapshot.of(i, 0, 64, 0),
                    RuleResult.fail(Condition.SLEPT, "never_slept"));
        }
        UiModel ui = new UiModel(zh);
        assertEquals(5, ui.statsFailures(stats, 5).size());
    }

    @Test
    void statsByReasonMergesAndSorts() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(0, 0);
        for (int i = 0; i < 3; i++) {
            stats.recordFailure(i, VillagerSnapshot.of(i, 0, 64, 0),
                    RuleResult.fail(Condition.PANICKING, "not_panicking"));
        }
        stats.recordFailure(9, VillagerSnapshot.of(9, 0, 64, 0),
                RuleResult.fail(Condition.SLEPT, "never_slept"));

        UiModel ui = new UiModel(zh);
        List<UiModel.Row> rows = ui.statsByReason(stats);
        assertEquals(2, rows.size());
        assertEquals(zh.get("ironfarm.reason.not_panicking"), rows.get(0).label);
        assertEquals("3", rows.get(0).value);
    }

    // ==================================================================
    // 时间格式化
    // ==================================================================

    @Test
    void gameTimeFormatsToDayAndClock() {
        UiModel ui = new UiModel(zh);
        // tick 0 = 第 1 天 06:00
        String t0 = ui.formatGameTime(0);
        assertTrue(t0.contains("06:00"), "游戏 0 tick 是 06:00，实际: " + t0);
        assertTrue(t0.contains("1"), "应为第 1 天");

        // 一天后同样的钟点，天数 +1
        String t1 = ui.formatGameTime(24000);
        assertTrue(t1.contains("06:00"));
        assertFalse(t0.equals(t1), "跨天后文本应不同");

        // 6000 tick = 12:00
        assertTrue(ui.formatGameTime(6000).contains("12:00"), "6000 tick 是正午");
    }

    @Test
    void formattedTimeIsLocalized() {
        String zhTime = new UiModel(zh).formatGameTime(0);
        String enTime = new UiModel(en).formatGameTime(0);
        assertTrue(zhTime.startsWith("第"), "中文应带'第': " + zhTime);
        assertTrue(enTime.startsWith("Day"), "英文应带 Day: " + enTime);
    }

    // ==================================================================
    // 统计页：标签里不能有 %s，也不能有已删除的行
    // ==================================================================

    /**
     * 回归钉子（用户截图里标签显示为「开始记录：%s」）。
     *
     * <p>{@code ironfarm.gui.stats.start} 的值原本写成 {@code 开始记录：%s}，
     * 但 {@link UiModel} 取值时用的是 {@code lang.get()}（不替换占位符），
     * 于是 {@code %s} 原样显示在界面上。</p>
     *
     * <p>正确做法：**标签里不写占位符**，数值单独放在 {@code Row.value} 里。
     * 占位符只用于真正的格式化场景（如 {@code lang.format(...)}）。</p>
     */
    @Test
    void statsLabelsDoNotContainPlaceholders() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(1000, 0);
        stats.recordSpawn(2000, 1);

        UiModel ui = new UiModel(zh);
        for (UiModel.Row r : ui.statsHeader(stats, 72000)) {
            assertFalse(r.label.contains("%s"),
                    "统计页标签里不该出现 %s（值应放在 value 里）: " + r.label);
            assertFalse(r.label.contains("%d"),
                    "统计页标签里不该出现 %d: " + r.label);
        }
    }

    /**
     * 用户要求去掉「判定次数」和「失败条目数」两行。
     *
     * <p>理由：判定次数受客户端扫描节流影响，本身就是估算；
     * 失败条目数在列表展示被移除后没有解读价值。</p>
     */
    @Test
    void statsHeaderNoLongerShowsAttemptsOrFailureCount() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(0, 0);
        stats.recordSpawn(100, 1);

        UiModel ui = new UiModel(zh);
        for (UiModel.Row r : ui.statsHeader(stats, 72000)) {
            assertFalse(r.label.contains("判定次数"),
                    "不应再有「判定次数」行: " + r.label);
            assertFalse(r.label.contains("失败条目"),
                    "不应再有「失败条目数」行: " + r.label);
        }
    }

    /**
     * 用户要求「刷出铁傀儡」改名为「铁傀儡累计数量」，并明确它是累计量。
     */
    @Test
    void golemCountRowIsLabelledAsCumulative() {
        StatsRecorder stats = new StatsRecorder();
        stats.start(0, 0);
        for (int i = 0; i < 7; i++) {
            stats.recordSpawn(i * 100L, 1);
        }
        UiModel ui = new UiModel(zh);
        List<UiModel.Row> rows = ui.statsHeader(stats, 72000);

        UiModel.Row cum = null;
        UiModel.Row rate = null;
        for (UiModel.Row r : rows) {
            // ⚠ 必须用「铁傀儡累计」，不能用「累计」——
            //    否则会先匹配到「铁锭累计估算」（7 只 × 4 = 28）。
            if (r.label.contains("铁傀儡累计")) {
                cum = r;
            }
            if (r.label.contains("小时")) {
                rate = r;
            }
        }
        assertTrue(cum != null, "应有一行明确标注「累计」");
        assertEquals("7", cum.value, "累计数量应为 7");
        assertTrue(rate != null, "应有一行是每小时速率");
    }
}