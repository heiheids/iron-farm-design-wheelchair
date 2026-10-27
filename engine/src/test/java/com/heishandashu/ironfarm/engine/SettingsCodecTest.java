package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.config.ModSettings;
import com.heishandashu.ironfarm.engine.config.SettingsCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置序列化测试。
 *
 * <p>重点不是"能存能读"，而是**容错**：
 * 玩家的配置文件被手改坏，模组不能因此完全不工作。</p>
 */
class SettingsCodecTest {

    @Test
    void roundTripPreservesAllFields() {
        ModSettings a = new ModSettings();
        a.masterEnabled = false;
        a.villagerOverlayEnabled = false;
        a.bedLinesEnabled = false;
        a.spawnBoxEnabled = false;
        a.panicLinesEnabled = false;
        a.golemDetectBoxEnabled = false;
        a.overlayMaxDistance = 12.5;
        a.lineMaxDistance = 64.0;
        a.requireHoldingItem = true;
        a.requiredItemId = "minecraft:stick";
        a.scaleByDistance = false;
        a.locale = "en_us";
        a.scaryMobStrictMode = false;
        a.statsRecordFailures = false;
        a.chatNotifyOnSpawn = true;

        ModSettings b = SettingsCodec.read(SettingsCodec.write(a));

        assertEquals(a.masterEnabled, b.masterEnabled);
        assertEquals(a.villagerOverlayEnabled, b.villagerOverlayEnabled);
        assertEquals(a.bedLinesEnabled, b.bedLinesEnabled);
        assertEquals(a.spawnBoxEnabled, b.spawnBoxEnabled);
        assertEquals(a.panicLinesEnabled, b.panicLinesEnabled);
        assertEquals(a.golemDetectBoxEnabled, b.golemDetectBoxEnabled);
        assertEquals(a.overlayMaxDistance, b.overlayMaxDistance, 0.0001);
        assertEquals(a.lineMaxDistance, b.lineMaxDistance, 0.0001);
        assertEquals(a.requireHoldingItem, b.requireHoldingItem);
        assertEquals(a.requiredItemId, b.requiredItemId);
        assertEquals(a.scaleByDistance, b.scaleByDistance);
        assertEquals(a.locale, b.locale);
        assertEquals(a.scaryMobStrictMode, b.scaryMobStrictMode);
        assertEquals(a.statsRecordFailures, b.statsRecordFailures);
        assertEquals(a.chatNotifyOnSpawn, b.chatNotifyOnSpawn);
    }

    @Test
    void emptyTextGivesDefaults() {
        ModSettings s = SettingsCodec.read("");
        assertTrue(s.masterEnabled, "默认应为开");
        assertTrue(s.villagerOverlayEnabled);
        assertEquals(32.0, s.overlayMaxDistance, 0.0001);
        assertEquals("auto", s.locale);
    }

    @Test
    void nullTextGivesDefaults() {
        assertNotNull(SettingsCodec.read((String) null));
        assertTrue(SettingsCodec.read((String) null).masterEnabled);
    }

    @Test
    void commentsAndBlankLinesIgnored() {
        String text = "# comment\n\n  # indented comment\nmasterEnabled=false\n\n";
        assertFalse(SettingsCodec.read(text).masterEnabled);
    }

    @Test
    void unknownKeyIsIgnoredWithoutBreakingOthers() {
        String text = "someFutureKey=whatever\nmasterEnabled=false\n";
        ModSettings s = SettingsCodec.read(text);
        assertFalse(s.masterEnabled, "未知键不应影响已知键");
    }

    @Test
    void garbageBooleanFallsBackToDefault() {
        String text = "masterEnabled=maybe\n";
        assertTrue(SettingsCodec.read(text).masterEnabled, "认不出的布尔值应保留默认");
    }

    @Test
    void garbageNumberFallsBackToDefault() {
        String text = "overlayMaxDistance=abc\n";
        assertEquals(32.0, SettingsCodec.read(text).overlayMaxDistance, 0.0001);
    }

    @Test
    void distanceIsClampedToSaneRange() {
        // 手改成 99999 会把渲染拖死，必须夹取
        assertTrue(SettingsCodec.read("overlayMaxDistance=99999\n").overlayMaxDistance <= 512.0);
        assertTrue(SettingsCodec.read("overlayMaxDistance=0.1\n").overlayMaxDistance >= 1.0);
        assertTrue(SettingsCodec.read("overlayMaxDistance=-50\n").overlayMaxDistance >= 1.0);
    }

    @Test
    void malformedLineIsSkippedNotFatal() {
        String text = "this line has no equals sign\nmasterEnabled=false\n";
        ModSettings s = SettingsCodec.read(text);
        assertFalse(s.masterEnabled, "坏行不应阻止后面的行生效");
    }

    @Test
    void booleanAcceptsCommonForms() {
        assertTrue(SettingsCodec.read("masterEnabled=TRUE\n").masterEnabled);
        assertTrue(SettingsCodec.read("masterEnabled=1\n").masterEnabled);
        assertTrue(SettingsCodec.read("masterEnabled=yes\n").masterEnabled);
        assertFalse(SettingsCodec.read("masterEnabled=FALSE\n").masterEnabled);
        assertFalse(SettingsCodec.read("masterEnabled=0\n").masterEnabled);
        assertFalse(SettingsCodec.read("masterEnabled=no\n").masterEnabled);
    }

    @Test
    void writtenTextIsParseableAndContainsFormatVersion() {
        String text = SettingsCodec.write(new ModSettings());
        Map<String, String> kv = SettingsCodec.parse(text);
        assertEquals(String.valueOf(SettingsCodec.FORMAT_VERSION), kv.get("formatVersion"));
        assertNotNull(kv.get("masterEnabled"));
        // 六个开关都要在
        for (String k : new String[]{"masterEnabled", "villagerOverlayEnabled", "bedLinesEnabled",
                "spawnBoxEnabled", "panicLinesEnabled", "golemDetectBoxEnabled"}) {
            assertTrue(kv.containsKey(k), "缺少开关: " + k);
        }
    }

    @Test
    void applyUpdatesExistingInstance() {
        ModSettings s = new ModSettings();
        s.masterEnabled = true;
        SettingsCodec.apply(s, SettingsCodec.parse("masterEnabled=false\noverlayMaxDistance=20\n"));
        assertFalse(s.masterEnabled);
        assertEquals(20.0, s.overlayMaxDistance, 0.0001);
    }

    @Test
    void effectiveLocaleResolvesAuto() {
        ModSettings s = new ModSettings();
        s.locale = "auto";
        String auto = s.effectiveLocale();
        assertTrue("zh_cn".equals(auto) || "en_us".equals(auto));
        s.locale = "zh_cn";
        assertEquals("zh_cn", s.effectiveLocale());
        s.locale = "en_us";
        assertEquals("en_us", s.effectiveLocale());
    }

    @Test
    void needsWorldScanFalseWhenAllOverlaysOff() {
        ModSettings s = new ModSettings();
        s.villagerOverlayEnabled = false;
        s.bedLinesEnabled = false;
        s.spawnBoxEnabled = false;
        s.panicLinesEnabled = false;
        s.golemDetectBoxEnabled = false;
        assertFalse(s.needsWorldScan(), "全部显示关闭时不该扫世界");

        s.bedLinesEnabled = true;
        assertTrue(s.needsWorldScan());

        s.masterEnabled = false;
        assertFalse(s.needsWorldScan(), "总开关关闭时一切都不做");
    }

    @Test
    void copyIsIndependent() {
        ModSettings a = new ModSettings();
        ModSettings b = a.copy();
        b.masterEnabled = false;
        assertTrue(a.masterEnabled, "copy 必须是深拷贝");
    }
}
