package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.RuleResult;
import com.heishandashu.ironfarm.engine.stats.FailureRecord;
import com.heishandashu.ironfarm.engine.stats.StatsCodec;
import com.heishandashu.ironfarm.engine.stats.StatsRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.IOException;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计数据持久化测试。
 *
 * <p>修的是一个真实缺陷：原来关掉游戏统计就清零，
 * 而"刷出多少铁傀儡"恰恰是玩家最想长期观察的数字。</p>
 */
class StatsCodecTest {

    private VillagerSnapshot v(int id) {
        return VillagerSnapshot.of(id, 0, 64, 0);
    }

    /** 序列化成文本，供往返测试用。 */
    private static String write(StatsRecorder s) throws IOException {
        StringWriter w = new StringWriter();
        StatsCodec.write(s, w);
        return w.toString();
    }

    @Test
    void roundTripPreservesCounters() {
        StatsRecorder a = new StatsRecorder();
        a.start(12000, 1_700_000_000_000L);
        for (int i = 0; i < 7; i++) {
            a.recordSpawn(13000 + i * 100L, 42);
        }
        a.recordFailure(20000, v(42),
                RuleResult.fail(Condition.SLEPT, "never_slept", 0, 24000));

        StatsRecorder b = StatsCodec.read(StatsCodec.write(a));

        assertEquals(7, b.golemsSpawned());
        assertEquals(8, b.summonAttempts());   // 7 次成功 + 1 次失败
        assertEquals(12000, b.startGameTime());
        assertEquals(1_700_000_000_000L, b.startWallClock());
        assertTrue(b.isStarted());
    }

    @Test
    void roundTripPreservesFailureRecords() {
        StatsRecorder a = new StatsRecorder();
        a.start(0, 0);
        for (int i = 0; i < 5; i++) {
            a.recordFailure(i, v(7), RuleResult.fail(Condition.SLEPT, "never_slept", 0, 24000));
        }
        a.recordFailure(9, v(3), RuleResult.fail(Condition.PANICKING, "not_panicking"));

        StatsRecorder b = StatsCodec.read(StatsCodec.write(a));

        List<FailureRecord> fails = b.failuresByCount();
        assertEquals(2, fails.size());
        assertEquals(7, fails.get(0).villagerEntityId);
        assertEquals(5, fails.get(0).count);
        assertEquals("never_slept", fails.get(0).reasonCode);
        // 数值也要留住，否则恢复后无法显示"差多少"
        assertEquals(0.0, fails.get(0).lastValue, 0.0001);
        assertEquals(24000.0, fails.get(0).lastThreshold, 0.0001);
    }

    @Test
    void roundTripPreservesRate() {
        StatsRecorder a = new StatsRecorder();
        a.start(0, 0);
        for (int i = 0; i < 10; i++) {
            a.recordSpawn(i * 1000L, 1);
        }
        StatsRecorder b = StatsCodec.read(StatsCodec.write(a));
        // 72000 tick = 1 小时
        assertEquals(a.golemsPerHour(72000), b.golemsPerHour(72000), 0.0001);
    }

    @Test
    void emptyTextGivesFreshRecorder() {
        StatsRecorder s = StatsCodec.read("");
        assertFalse(s.isStarted());
        assertEquals(0, s.golemsSpawned());
    }

    @Test
    void nullTextGivesFreshRecorder() {
        StatsRecorder s = StatsCodec.read((String) null);
        assertEquals(0, s.golemsSpawned());
    }

    @Test
    void malformedFailureLineIsSkipped() {
        String text = "startGameTime=100\n"
                + "golemsSpawned=3\n"
                + "failure=notanumber|never_slept|5|0|0|24000\n"  // villagerId 非法
                + "failure=7|\n"                                   // 字段不足
                + "failure=8|never_slept|abc|0|0|0\n"              // count 非法
                + "failure=9|never_slept|2|100|0|24000\n";         // 合法
        StatsRecorder s = StatsCodec.read(text);

        assertEquals(3, s.golemsSpawned());
        List<FailureRecord> fails = s.failuresByCount();
        assertEquals(1, fails.size(), "三条坏行应被跳过，只留合法那条");
        assertEquals(9, fails.get(0).villagerEntityId);
        assertEquals(2, fails.get(0).count);
    }

    @Test
    void garbageHeaderFallsBackToDefaults() {
        String text = "startGameTime=abc\ngolemsSpawned=xyz\n";
        StatsRecorder s = StatsCodec.read(text);
        assertFalse(s.isStarted(), "startGameTime 非法时应视为未开始");
        assertEquals(0, s.golemsSpawned());
    }

    @Test
    void negativeCountersAreClampedToZero() {
        String text = "startGameTime=100\ngolemsSpawned=-5\nsummonAttempts=-3\n";
        StatsRecorder s = StatsCodec.read(text);
        assertEquals(0, s.golemsSpawned(), "负数应夹到 0");
        assertEquals(0, s.summonAttempts());
    }

    @Test
    void restoreFailureRejectsNonPositiveCount() {
        StatsRecorder s = new StatsRecorder();
        s.restoreFailure(1, "never_slept", 0, 0, 0, 0);
        assertTrue(s.failuresByCount().isEmpty(), "count=0 的记录不该被恢复");
        s.restoreFailure(1, "never_slept", -3, 0, 0, 0);
        assertTrue(s.failuresByCount().isEmpty(), "负数 count 也不该");
    }

    @Test
    void restoreFailureRejectsEmptyReason() {
        StatsRecorder s = new StatsRecorder();
        s.restoreFailure(1, "", 5, 0, 0, 0);
        s.restoreFailure(1, null, 5, 0, 0, 0);
        assertTrue(s.failuresByCount().isEmpty());
    }

    @Test
    void writtenTextIsParseableAndHasVersion() {
        String text = StatsCodec.write(new StatsRecorder());
        assertTrue(text.contains("formatVersion=" + StatsCodec.FORMAT_VERSION));
        assertTrue(text.contains("startGameTime="));
        assertTrue(text.contains("golemsSpawned="));
    }

    /**
     * 回归钉子：restore 不能像 start 那样清零。
     * 第一版如果误用 start 来"恢复"，累计数据会被抹掉，等于没修。
     */
    @Test
    void restoreDoesNotZeroCounters() {
        StatsRecorder s = new StatsRecorder();
        s.start(0, 0);
        s.recordSpawn(100, 1);
        s.recordSpawn(200, 1);
        assertEquals(2, s.golemsSpawned());

        s.restore(0, 0, 50, 60);
        assertEquals(50, s.golemsSpawned(), "restore 必须保留传入的累计值");
        assertEquals(60, s.summonAttempts());

        // 对照：start 会清零
        s.start(0, 0);
        assertEquals(0, s.golemsSpawned());
    }

    @Test
    void restoredStatsKeepAccumulating() {
        StatsRecorder a = new StatsRecorder();
        a.start(1000, 0);
        a.recordSpawn(2000, 1);

        StatsRecorder b = StatsCodec.read(StatsCodec.write(a));
        b.recordSpawn(3000, 2);   // 恢复后继续累加
        assertEquals(2, b.golemsSpawned());
    }

    @Test
    void emptyReasonInFileIsSkipped() {
        String text = "startGameTime=100\nfailure=|never_slept|5|0|0|0\n";
        StatsRecorder s = StatsCodec.read(text);
        assertTrue(s.failuresByCount().isEmpty());
    }

    // ==================================================================
    // 实物统计（0.46.0 补上持久化）
    //
    // 0.45.0 及之前 StatsCodec 完全没写这部分，数据只在内存里，
    // 一关游戏就归零 —— 用户反馈"统计不到销毁物品信息"就是这个。
    // ==================================================================

    /** 实物统计必须能存下来并读回来。 */
    @Test
    void itemDestroySurvivesRoundTrip() throws IOException {
        StatsRecorder a = new StatsRecorder();
        a.start(1000L, 111L);
        a.itemDestroy().record(2000L, "minecraft:iron_ingot", 42);
        a.itemDestroy().record(2100L, "minecraft:poppy", 7);

        StatsRecorder b = StatsCodec.read(write(a));

        assertEquals(42, b.itemDestroy().countOf("minecraft:iron_ingot"));
        assertEquals(7, b.itemDestroy().countOf("minecraft:poppy"));
        assertEquals(49, b.itemDestroy().totalItems());
        assertEquals(2, b.itemDestroy().kinds());
    }

    /** 文件里必须有 item= 行 —— 否则等于没存。 */
    @Test
    void fileContainsItemLines() throws IOException {
        StatsRecorder a = new StatsRecorder();
        a.start(1000L, 111L);
        a.itemDestroy().record(2000L, "minecraft:iron_ingot", 5);

        String text = write(a);
        assertTrue(text.contains("item=minecraft:iron_ingot|5"),
                "统计文件里必须有 item=行，实际内容：" + text);
        assertTrue(text.contains("itemsStartGameTime="),
                "必须记录实物统计的起始时间");
    }

    /** 旧版文件（没有 item= 行）要能正常读，不能崩。 */
    @Test
    void oldFileWithoutItemLinesStillLoads() {
        String old = "formatVersion=1\nstartGameTime=500\ngolemsSpawned=10\n";
        StatsRecorder s = StatsCodec.read(old);
        assertEquals(10, s.golemsSpawned());
        assertEquals(0, s.itemDestroy().totalItems());
    }

    /** 坏行只丢弃那一行，不连累其它物品。 */
    @Test
    void malformedItemLineIsSkipped() {
        String text = "formatVersion=1\nstartGameTime=500\n"
                + "item=minecraft:dirt|notanumber\n"
                + "item=minecraft:stone|12\n"
                + "item=bogus\n";
        StatsRecorder s = StatsCodec.read(text);
        assertEquals(12, s.itemDestroy().countOf("minecraft:stone"));
        assertEquals(1, s.itemDestroy().kinds(), "坏行不能产生额外条目");
    }

    /** 实物统计的起始时间与主统计对齐后要能存回来。 */
    @Test
    void itemsStartTimeIsPersisted() throws IOException {
        StatsRecorder a = new StatsRecorder();
        a.start(3000L, 222L);
        a.itemDestroy().record(4000L, "minecraft:iron_ingot", 1);

        StatsRecorder b = StatsCodec.read(write(a));
        assertEquals(3000L, b.itemDestroy().startGameTime(),
                "实物统计的起点应与主统计一致");
    }

}
