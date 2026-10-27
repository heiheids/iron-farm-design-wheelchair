package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.i18n.Lang;
import com.heishandashu.ironfarm.engine.rule.Condition;
import com.heishandashu.ironfarm.engine.rule.ReasonCodes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多语言一致性测试。
 *
 * <p>这类测试最容易漏：某个键只在中文里有、英文里忘了加，玩家切成英文就会看到
 * 原始键名 {@code ironfarm.cond.slept}。所以把「中英键集合完全一致」做成断言。</p>
 */
class LangTest {

    @Test
    void bothLanguagesLoad() {
        Lang zh = Lang.load(Lang.ZH_CN);
        Lang en = Lang.load(Lang.EN_US);
        assertTrue(zh.size() > 50, "中文条目太少: " + zh.size());
        assertTrue(en.size() > 50, "英文条目太少: " + en.size());
        assertEquals(Lang.ZH_CN, zh.locale());
        assertEquals(Lang.EN_US, en.locale());
    }

    @Test
    void keySetsAreIdenticalBetweenLanguages() {
        Lang zh = Lang.load(Lang.ZH_CN);
        Lang en = Lang.load(Lang.EN_US);

        List<String> zhKeys = new java.util.ArrayList<String>(zh.rawTable().keySet());
        List<String> enKeys = new java.util.ArrayList<String>(en.rawTable().keySet());
        java.util.Collections.sort(zhKeys);
        java.util.Collections.sort(enKeys);

        List<String> missingInEn = new java.util.ArrayList<String>(zhKeys);
        missingInEn.removeAll(enKeys);
        List<String> missingInZh = new java.util.ArrayList<String>(enKeys);
        missingInZh.removeAll(zhKeys);

        assertEquals(java.util.Collections.emptyList(), missingInEn,
                "英文缺少这些键: " + missingInEn);
        assertEquals(java.util.Collections.emptyList(), missingInZh,
                "中文缺少这些键: " + missingInZh);
    }

    @Test
    void everyConditionHasNameAndDescriptionInBothLanguages() {
        for (Lang lang : Arrays.asList(Lang.load(Lang.ZH_CN), Lang.load(Lang.EN_US))) {
            for (Condition c : Condition.values()) {
                assertTrue(lang.has(c.langKey()),
                        lang.locale() + " 缺少条件名: " + c.langKey());
                assertTrue(lang.has(c.descLangKey()),
                        lang.locale() + " 缺少条件说明: " + c.descLangKey());
                // 不允许回落到键名本身（说明该键不存在）
                assertFalse(c.langKey().equals(lang.get(c.langKey())),
                        lang.locale() + " 的条件名回落到键名: " + c.langKey());
            }
        }
    }

    @Test
    void everyReasonCodeHasTranslation() {
        List<String> codes = Arrays.asList(
                ReasonCodes.NOT_ADULT,
                ReasonCodes.NEVER_SLEPT,
                ReasonCodes.SLEEP_EXPIRED,
                ReasonCodes.GOLEM_DETECTED,
                ReasonCodes.NOT_PANICKING,
                ReasonCodes.NOT_GOSSIPING,
                ReasonCodes.PANIC_GROUP_TOO_SMALL,
                ReasonCodes.GOSSIP_GROUP_TOO_SMALL,
                ReasonCodes.NO_SPAWN_POINT,
                ReasonCodes.SPAWN_AREA_UNLOADED,
                ReasonCodes.CHUNK_UNLOADED,
                ReasonCodes.DATA_MISSING);
        for (Lang lang : Arrays.asList(Lang.load(Lang.ZH_CN), Lang.load(Lang.EN_US))) {
            for (String code : codes) {
                String key = "ironfarm.reason." + code;
                assertTrue(lang.has(key), lang.locale() + " 缺少失败原因: " + key);
            }
        }
    }

    @Test
    void spawnRejectionCodesHaveTranslation() {
        List<String> codes = Arrays.asList(
                "no_surface", "no_full_top", "full_block_above",
                "rail", "powered", "water_above", "entity_collision");
        for (Lang lang : Arrays.asList(Lang.load(Lang.ZH_CN), Lang.load(Lang.EN_US))) {
            for (String code : codes) {
                String key = "ironfarm.spawn.reject." + code;
                assertTrue(lang.has(key), lang.locale() + " 缺少拒绝原因: " + key);
            }
        }
    }

    @Test
    void normalizeHandlesCommonForms() {
        assertEquals(Lang.ZH_CN, Lang.normalize("zh-CN"));
        assertEquals(Lang.ZH_CN, Lang.normalize("zh_CN"));
        assertEquals(Lang.ZH_CN, Lang.normalize("zh"));
        assertEquals(Lang.ZH_CN, Lang.normalize("zh_TW"));
        assertEquals(Lang.EN_US, Lang.normalize("en_US"));
        assertEquals(Lang.EN_US, Lang.normalize("en"));
        assertEquals(Lang.EN_US, Lang.normalize("fr_FR"), "未知语言回落英文");
        assertEquals(Lang.EN_US, Lang.normalize(null));
    }

    @Test
    void missingKeyReturnsKeyNameNotCrash() {
        Lang lang = Lang.load(Lang.EN_US);
        assertEquals("ironfarm.does.not.exist", lang.get("ironfarm.does.not.exist"));
    }

    @Test
    void formatWithNoArgsReturnsPattern() {
        Lang lang = Lang.load(Lang.EN_US);
        String s = lang.format("ironfarm.gui.stats.spawned");
        assertNotNull(s);
        assertFalse(s.isEmpty());
    }

    @Test
    void formatWithArgsSubstitutes() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        m.put("k", "value=%s");
        Lang lang = Lang.of("en_us", m);
        assertEquals("value=42", lang.format("k", 42));
    }

    @Test
    void formatWithWrongArgCountDoesNotThrow() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        m.put("k", "%s and %s");
        Lang lang = Lang.of("en_us", m);
        String s = lang.format("k", 1);
        // 不能抛异常；退回原文并补参数
        assertTrue(s.contains("%s") || s.contains("1"), "应安全返回: " + s);
    }

    @Test
    void autoDetectReturnsSupportedLocale() {
        String auto = Lang.autoDetect();
        assertTrue(Arrays.asList(Lang.supported()).contains(auto));
    }
}
