package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.config.ModSettings;
import com.heishandashu.ironfarm.engine.config.SettingsCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开关语义测试。
 *
 * <p><b>两个需求的根因：</b></p>
 * <ol>
 *   <li>主开关只拦了「显示」，漏斗销毁与统计照常跑 ——
 *       用户原话「主开关的作用是关闭所有功能，而不是显示问题，
 *       是模组就不工作了」。</li>
 *   <li>右键交互没有独立开关，只能靠主开关一起关掉。</li>
 * </ol>
 *
 * <p>这组测试把语义钉死，防止以后又只改一半。</p>
 */
class SwitchSemanticsTest {

    // ==================================================================
    // 主开关：完全停止，不是只关显示
    // ==================================================================

    /** 主开关关闭时，所有「模组是否工作」的判断都必须是 false。 */
    @Test
    void masterOffMeansModIsInactive() {
        ModSettings s = new ModSettings();
        assertTrue(s.isActive(), "默认应该是工作的");

        s.masterEnabled = false;
        assertFalse(s.isActive(), "主开关关了，模组必须停止工作");
    }

    /**
     * 主开关关闭时，即使所有显示开关都开着，也不该扫描世界。
     *
     * <p>这是「主开关只管显示」这个 bug 的直接防线。</p>
     */
    @Test
    void masterOffBlocksWorldScanEvenIfOverlaysOn() {
        ModSettings s = new ModSettings();
        s.villagerOverlayEnabled = true;
        s.bedLinesEnabled = true;
        s.spawnBoxEnabled = true;

        s.masterEnabled = false;
        assertFalse(s.needsWorldScan(), "主开关关了就不该再扫描世界");
        assertFalse(s.anyWorldOverlayEnabled(), "主开关关了就不该再画任何东西");
    }

    /**
     * 主开关关闭时，漏斗销毁也不能做。
     *
     * <p>漏斗销毁是**破坏性功能**（真的删物品），
     * 主开关关掉还在删物品是不可接受的。</p>
     */
    @Test
    void masterOffBlocksHopperVoidEvenIfEnabled() {
        ModSettings s = new ModSettings();
        s.woolHopperEnabled = true;      // 功能开关是开的
        s.masterEnabled = false;         // 但主开关关了

        // 主开关优先级更高：调用方必须先问 isActive()
        assertFalse(s.isActive(),
                "主开关关了，漏斗销毁的入口必须直接返回，不能继续删物品");
    }

    /** 主开关关闭时，右键也不能响应。 */
    @Test
    void masterOffBlocksRightClickEvenIfEnabled() {
        ModSettings s = new ModSettings();
        s.rightClickVillagerEnabled = true;
        s.masterEnabled = false;

        assertFalse(s.canRightClickVillager(),
                "主开关关了，右键交互也必须停");
    }

    // ==================================================================
    // 右键开关：与主开关独立
    // ==================================================================

    /** 默认开启，保持旧行为不变。 */
    @Test
    void rightClickIsOnByDefault() {
        assertTrue(new ModSettings().rightClickVillagerEnabled,
                "默认应开启，不改变现有玩家的使用习惯");
    }

    /** 只关右键，其他功能照常。 */
    @Test
    void rightClickOffDoesNotStopOtherFeatures() {
        ModSettings s = new ModSettings();
        s.rightClickVillagerEnabled = false;

        assertTrue(s.isActive(), "模组仍在工作");
        assertFalse(s.canRightClickVillager(), "但右键不再响应");
        assertTrue(s.needsWorldScan(), "扫描照常（图标还要用这些数据）");
        assertTrue(s.anyWorldOverlayEnabled(), "图标照画");
    }

    /**
     * 右键开关关闭时，漏斗销毁不受影响。
     *
     * <p>两个功能互不相干，不能误伤。</p>
     */
    @Test
    void rightClickOffDoesNotAffectHopperVoid() {
        ModSettings s = new ModSettings();
        s.rightClickVillagerEnabled = false;
        s.woolHopperEnabled = true;

        assertFalse(s.canRightClickVillager());
        assertTrue(s.isActive() && s.woolHopperEnabled,
                "漏斗销毁只看 isActive() + woolHopperEnabled，与右键无关");
    }

    // ==================================================================
    // 持久化
    // ==================================================================

    /** 右键开关必须能存下来。 */
    @Test
    void rightClickSettingSurvivesRoundTrip() {
        ModSettings a = new ModSettings();
        a.rightClickVillagerEnabled = false;

        String text = SettingsCodec.write(a);
        ModSettings b = SettingsCodec.read(text);

        assertFalse(b.rightClickVillagerEnabled,
                "关闭状态必须被持久化，否则重启又变回开着");
    }

    /** 旧配置文件（没有这个键）要能正常读，默认开着。 */
    @Test
    void oldSettingsFileDefaultsRightClickOn() {
        ModSettings s = SettingsCodec.read("masterEnabled=true\nlocale=zh_cn\n");
        assertTrue(s.rightClickVillagerEnabled,
                "旧文件里没有这个键，应该用默认值（开）");
    }

    // ==================================================================
    // 深拷贝不能漏字段
    // ==================================================================

    /**
     * copy() 必须拷全所有开关。
     *
     * <p>踩过的坑：copy() 漏了 woolHopperEnabled，
     * 表现是「改完设置点保存，这个开关又变回默认值」。
     * 这类 bug 编译器抓不到 —— 少写一行赋值，照样能编译。</p>
     */
    @Test
    void copyPreservesAllSwitches() {
        ModSettings a = new ModSettings();
        a.masterEnabled = false;
        a.villagerOverlayEnabled = false;
        a.woolHopperEnabled = true;
        a.rightClickVillagerEnabled = false;

        ModSettings b = a.copy();

        assertNotSame(a, b, "必须是新对象");
        assertEquals(a.masterEnabled, b.masterEnabled);
        assertEquals(a.villagerOverlayEnabled, b.villagerOverlayEnabled);
        assertEquals(a.woolHopperEnabled, b.woolHopperEnabled, "漏斗销毁开关漏拷了");
        assertEquals(a.rightClickVillagerEnabled, b.rightClickVillagerEnabled, "右键开关漏拷了");
    }

    /** 修改副本不应影响原件。 */
    @Test
    void copyIsDeep() {
        ModSettings a = new ModSettings();
        a.woolHopperEnabled = false;
        a.rightClickVillagerEnabled = true;

        ModSettings b = a.copy();
        b.woolHopperEnabled = true;
        b.rightClickVillagerEnabled = false;

        assertFalse(a.woolHopperEnabled, "原件不该被副本改动");
        assertTrue(a.rightClickVillagerEnabled);
    }
}
