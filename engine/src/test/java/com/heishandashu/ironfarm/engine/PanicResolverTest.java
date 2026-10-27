package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.panic.PanicAnalysis;
import com.heishandashu.ironfarm.engine.panic.PanicLink;
import com.heishandashu.ironfarm.engine.panic.PanicResolver;
import com.heishandashu.ironfarm.engine.panic.ScaryMob;
import com.heishandashu.ironfarm.engine.panic.ScaryMobRegistry;
import com.heishandashu.ironfarm.engine.panic.ScaryMobSnapshot;
import com.heishandashu.ironfarm.engine.panic.SightQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恐吓连线测试：覆盖**全部**恐吓源，而不只是僵尸。
 *
 * <p>需求明确要求「只要是可以恐吓村民的怪物，都要做好适配」，
 * 所以这里对 Wiki 半径表里的每一档都单独钉一条断言 ——
 * 任何一档写错，对应的用例就会红。</p>
 */
class PanicResolverTest {

    private VillagerSnapshot villager(int id, int x, int y, int z) {
        return VillagerSnapshot.of(id, x, y, z);
    }

    private List<VillagerSnapshot> oneVillager(int x, int y, int z) {
        List<VillagerSnapshot> vs = new ArrayList<VillagerSnapshot>();
        vs.add(villager(1, x, y, z));
        return vs;
    }

    private List<ScaryMobSnapshot> oneMob(ScaryMobSnapshot m) {
        List<ScaryMobSnapshot> ms = new ArrayList<ScaryMobSnapshot>();
        ms.add(m);
        return ms;
    }

    /** 单只村民在原点，怪物在 x 轴上 distance 格处。 */
    private PanicLink linkAtDistance(String typeId, int distance) {
        List<PanicLink> links = PanicResolver.resolveLinks(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(100, typeId, distance, 64, 0)),
                null);
        assertEquals(1, links.size());
        return links.get(0);
    }

    // ------------------------------------------------------------------
    // 半径 8：僵尸家族 + 恼鬼
    // ------------------------------------------------------------------

    @Test
    void zombieRadiusIs8() {
        assertEquals(8, linkAtDistance("minecraft:zombie", 0).radius);
        assertTrue(linkAtDistance("minecraft:zombie", 7).effective);
        assertFalse(linkAtDistance("minecraft:zombie", 9).effective);
    }

    @Test
    void huskRadiusIs8() {
        assertEquals(8, linkAtDistance("minecraft:husk", 0).radius);
        assertTrue(linkAtDistance("minecraft:husk", 7).effective);
        assertFalse(linkAtDistance("minecraft:husk", 9).effective);
    }

    @Test
    void drownedRadiusIs8() {
        assertEquals(8, linkAtDistance("minecraft:drowned", 0).radius);
        assertTrue(linkAtDistance("minecraft:drowned", 7).effective);
    }

    @Test
    void zombieVillagerRadiusIs8() {
        assertEquals(8, linkAtDistance("minecraft:zombie_villager", 0).radius);
        assertTrue(linkAtDistance("minecraft:zombie_villager", 7).effective);
    }

    @Test
    void vexRadiusIs8() {
        assertEquals(8, linkAtDistance("minecraft:vex", 0).radius);
        assertTrue(linkAtDistance("minecraft:vex", 7).effective);
    }

    // ------------------------------------------------------------------
    // 半径 10
    // ------------------------------------------------------------------

    @Test
    void vindicatorRadiusIs10() {
        PanicLink l = linkAtDistance("minecraft:vindicator", 9);
        assertEquals(10, l.radius);
        assertTrue(l.effective);
        assertFalse(linkAtDistance("minecraft:vindicator", 11).effective);
    }

    @Test
    void zoglinRadiusIs10() {
        PanicLink l = linkAtDistance("minecraft:zoglin", 9);
        assertEquals(10, l.radius);
        assertTrue(l.effective);
    }

    // ------------------------------------------------------------------
    // 半径 12
    // ------------------------------------------------------------------

    @Test
    void evokerRadiusIs12() {
        PanicLink l = linkAtDistance("minecraft:evoker", 11);
        assertEquals(12, l.radius);
        assertTrue(l.effective);
        assertFalse(linkAtDistance("minecraft:evoker", 13).effective);
    }

    @Test
    void illusionerRadiusIs12() {
        assertEquals(12, linkAtDistance("minecraft:illusioner", 11).radius);
        assertTrue(linkAtDistance("minecraft:illusioner", 11).effective);
    }

    @Test
    void ravagerRadiusIs12() {
        assertEquals(12, linkAtDistance("minecraft:ravager", 11).radius);
        assertTrue(linkAtDistance("minecraft:ravager", 11).effective);
    }

    // ------------------------------------------------------------------
    // 半径 15
    // ------------------------------------------------------------------

    @Test
    void pillagerRadiusIs15() {
        PanicLink l = linkAtDistance("minecraft:pillager", 14);
        assertEquals(15, l.radius);
        assertTrue(l.effective);
        assertFalse(linkAtDistance("minecraft:pillager", 16).effective);
    }

    @Test
    void pillagerOutrangesZombieSubstantially() {
        // 这是实际选型的关键事实：掠夺者的覆盖是僵尸的近两倍
        assertTrue(ScaryMob.PILLAGER.panicRadius() > ScaryMob.ZOMBIE.panicRadius() * 1.8);
    }

    // ------------------------------------------------------------------
    // 未验证半径的生物
    // ------------------------------------------------------------------

    @Test
    void witherDoesNotScareVillagers() {
        // 已用 1.21.1 字节码核实：VillagerHostilesSensor 的半径表里没有 WITHER。
        // 第一轮按 Wiki 正文给了 12 格，是错的。
        assertFalse(ScaryMob.WITHER.causesPanic());
        assertEquals(0, ScaryMob.WITHER.panicRadius());
        // 贴到 1 格也不该产生连线（resolveLinks 会对空结果返回空列表，
        // 所以这里不能再用 linkAtDistance —— 它假设一定有一条）
        List<PanicLink> links = PanicResolver.resolveLinks(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(500, "minecraft:wither", 1, 64, 0)),
                null);
        assertTrue(links.isEmpty(), "凋灵不该产生连线");
    }

    @Test
    void frostbiteDoesNotScareVillagers() {
        // 1.21.1 表里同样没有 frostbite。
        assertFalse(ScaryMob.FROSTBITE.causesPanic());
    }

    @Test
    void nonScaringMobsProduceNoLinks() {
        for (String id : new String[]{"minecraft:wither", "minecraft:frostbite"}) {
            List<PanicLink> links = PanicResolver.resolveLinks(
                    oneVillager(0, 64, 0),
                    oneMob(ScaryMobSnapshot.of(500, id, 1, 64, 0)),
                    null);
            assertTrue(links.isEmpty(), id + " 不该产生连线");
        }
    }

    @Test
    void verifiedMobsAreNotFlaggedAsInferred() {
        for (ScaryMob m : new ScaryMob[]{
                ScaryMob.ZOMBIE, ScaryMob.HUSK, ScaryMob.DROWNED, ScaryMob.ZOMBIE_VILLAGER,
                ScaryMob.VEX, ScaryMob.VINDICATOR, ScaryMob.ZOGLIN, ScaryMob.EVOKER,
                ScaryMob.ILLUSIONER, ScaryMob.RAVAGER, ScaryMob.PILLAGER}) {
            assertTrue(m.radiusVerified(), m.id() + " 的半径应有 Wiki 表格支持");
        }
    }

    // ------------------------------------------------------------------
    // 全表一致性：枚举里每一档半径都必须和 Wiki 一致
    // ------------------------------------------------------------------

    @Test
    void allRegisteredMobsMatchWikiRadiusTable() {
        int[][] expected = {
                // 8 格档
                {8, ScaryMob.ZOMBIE.ordinal()},
                {8, ScaryMob.HUSK.ordinal()},
                {8, ScaryMob.DROWNED.ordinal()},
                {8, ScaryMob.ZOMBIE_VILLAGER.ordinal()},
                {8, ScaryMob.VEX.ordinal()},
                // 10 格档
                {10, ScaryMob.VINDICATOR.ordinal()},
                {10, ScaryMob.ZOGLIN.ordinal()},
                // 12 格档
                {12, ScaryMob.EVOKER.ordinal()},
                {12, ScaryMob.ILLUSIONER.ordinal()},
                {12, ScaryMob.RAVAGER.ordinal()},
                // 15 格档
                {15, ScaryMob.PILLAGER.ordinal()}
        };
        for (int[] e : expected) {
            ScaryMob m = ScaryMob.values()[e[1]];
            assertEquals(e[0], m.panicRadius(), m.id() + " 半径与 Wiki 不符");
        }
    }

    @Test
    void everyKnownMobHasLangData() {
        for (ScaryMob m : ScaryMob.knownValues()) {
            assertFalse("ironfarm.mob.other".equals(m.langKey()));
            assertTrue(m.langKey().startsWith("ironfarm.mob."));
            assertTrue(m.adviceLangKey().endsWith(".advice"));
        }
    }

    // ------------------------------------------------------------------
    // 模组生物：注册表
    // ------------------------------------------------------------------

    @Test
    void moddedMobIsIgnoredInStrictModeByDefault() {
        List<PanicLink> links = PanicResolver.resolveLinks(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(200, "somemod:scary_thing", 3, 64, 0)),
                null);
        assertTrue(links.isEmpty(), "严格模式下未登记生物不应产生连线");
    }

    @Test
    void registeredModdedMobUsesCustomRadius() {
        ScaryMobRegistry reg = ScaryMobRegistry.vanilla().register("somemod:scary_thing", 12);
        PanicResolver resolver = new PanicResolver(reg);
        PanicAnalysis a = resolver.analyze(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(200, "somemod:scary_thing", 11, 64, 0)),
                null);
        assertEquals(1, a.links.size());
        PanicLink l = a.links.get(0);
        assertEquals(12, l.radius);
        assertTrue(l.effective);
        assertTrue(l.inferredRadius, "自定义半径应标注为推断值");
    }

    @Test
    void registeredModdedMobRespectsItsRadiusBoundary() {
        ScaryMobRegistry reg = ScaryMobRegistry.vanilla().register("somemod:scary_thing", 4);
        PanicResolver resolver = new PanicResolver(reg);
        PanicAnalysis near = resolver.analyze(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(200, "somemod:scary_thing", 3, 64, 0)), null);
        assertTrue(near.links.get(0).effective);

        PanicAnalysis far = resolver.analyze(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(200, "somemod:scary_thing", 5, 64, 0)), null);
        assertFalse(far.links.get(0).effective);
    }

    @Test
    void nonStrictModeAcceptsHostileUnknownMob() {
        ScaryMobRegistry reg = ScaryMobRegistry.vanilla();
        reg.strictMode = false;
        ScaryMobSnapshot hostile = new ScaryMobSnapshot(
                300, "somemod:thing", ScaryMob.OTHER, new BlockPos3(3, 64, 0),
                true, false, true, 1.74);
        PanicAnalysis a = new PanicResolver(reg).analyze(
                oneVillager(0, 64, 0), oneMob(hostile), null);
        assertEquals(1, a.links.size());
        assertEquals(ScaryMob.DEFAULT_RADIUS, a.links.get(0).radius);
    }

    @Test
    void nonStrictModeStillRejectsNonHostileUnknownMob() {
        ScaryMobRegistry reg = ScaryMobRegistry.vanilla();
        reg.strictMode = false;
        ScaryMobSnapshot passive = new ScaryMobSnapshot(
                301, "somemod:cow_like", ScaryMob.OTHER, new BlockPos3(3, 64, 0),
                true, false, false, 1.2);
        PanicAnalysis a = new PanicResolver(reg).analyze(
                oneVillager(0, 64, 0), oneMob(passive), null);
        assertTrue(a.links.isEmpty(), "对村民不敌对的未知生物不能被当成恐吓源");
    }

    @Test
    void registryResolveReportsRegistrationStatus() {
        ScaryMobRegistry reg = ScaryMobRegistry.vanilla();
        assertTrue(reg.isRegistered("minecraft:zombie"));
        assertFalse(reg.isRegistered("somemod:thing"));
        reg.register("somemod:thing", 9);
        assertTrue(reg.isRegistered("somemod:thing"));
        assertEquals(Integer.valueOf(9), reg.customRadiusOf("somemod:thing"));
    }

    // ------------------------------------------------------------------
    // 视线
    // ------------------------------------------------------------------

    @Test
    void blockedSightMakesInRangeLinkIneffective() {
        SightQuery noSight = new SightQuery() {
            @Override
            public boolean clearLineOfSight(BlockPos3 from, double fromEye,
                                            BlockPos3 to, double toEye) {
                return false;
            }
        };
        PanicLink l = PanicResolver.resolveLinks(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(100, "minecraft:zombie", 3, 64, 0)),
                noSight).get(0);
        assertTrue(l.blockedOnlyBySight());
        assertFalse(l.effective);
        assertEquals("no_sight", l.inactiveReason());
    }

    @Test
    void distanceBlockedLinkReportsShortfall() {
        PanicLink l = PanicResolver.resolveLinks(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(100, "minecraft:zombie", 11, 64, 0)),
                null).get(0);
        assertTrue(l.blockedByDistance());
        assertEquals(3.0, l.shortfall(), 0.0001);
        assertEquals("too_far", l.inactiveReason());
    }

    @Test
    void effectiveLinkHasNoInactiveReason() {
        PanicLink l = PanicResolver.resolveLinks(
                oneVillager(0, 64, 0),
                oneMob(ScaryMobSnapshot.of(100, "minecraft:zombie", 3, 64, 0)),
                null).get(0);
        assertTrue(l.effective);
        assertEquals(null, l.inactiveReason());
    }

    // ------------------------------------------------------------------
    // 多只怪物 / 分析汇总
    // ------------------------------------------------------------------

    @Test
    void mixedMobTypesAllProduceLinks() {
        List<VillagerSnapshot> vs = oneVillager(0, 64, 0);
        List<ScaryMobSnapshot> ms = new ArrayList<ScaryMobSnapshot>();
        ms.add(ScaryMobSnapshot.of(1, "minecraft:zombie", 3, 64, 0));
        ms.add(ScaryMobSnapshot.of(2, "minecraft:pillager", 12, 64, 0));
        ms.add(ScaryMobSnapshot.of(3, "minecraft:vindicator", 8, 64, 0));

        PanicAnalysis a = PanicResolver.resolve(vs, ms, null);
        assertEquals(3, a.links.size(), "三种怪物都应产生连线");
        assertEquals(3, a.totalEffectiveLinks(), "三种都应在其半径内");
    }

    @Test
    void analysisIdentifiesPrimaryScarer() {
        List<VillagerSnapshot> vs = new ArrayList<VillagerSnapshot>();
        for (int i = 0; i < 4; i++) {
            vs.add(villager(i, i, 64, 0));
        }
        List<ScaryMobSnapshot> ms = new ArrayList<ScaryMobSnapshot>();
        // 僵尸放在 x=11：半径 8，只够到最右边的 1 只（11.5-3.5=8.0 命中，
        // 11.5-2.5=9.0 超出）—— 正好卡在边界上，顺带验证「等于半径算命中」
        ms.add(new ScaryMobSnapshot(10, "minecraft:zombie", ScaryMob.ZOMBIE,
                new BlockPos3(11, 64, 0), true, false, true, 1.74));
        // 掠夺者放在 x=14：半径 15，四只全够到（11/12/13/14）
        ms.add(new ScaryMobSnapshot(20, "minecraft:pillager", ScaryMob.PILLAGER,
                new BlockPos3(14, 64, 0), true, false, true, 1.62));

        PanicAnalysis a = PanicResolver.resolve(vs, ms, null);
        assertEquals(1, a.scareCountByMob.get(10).intValue(), "僵尸只该吓到 1 只");
        assertEquals(4, a.scareCountByMob.get(20).intValue(), "掠夺者该吓到 4 只");
        assertEquals(20, a.primaryScarer(), "掠夺者应为主力");
    }

    @Test
    void analysisReportsIdleMobs() {
        List<VillagerSnapshot> vs = oneVillager(0, 64, 0);
        List<ScaryMobSnapshot> ms = new ArrayList<ScaryMobSnapshot>();
        ms.add(ScaryMobSnapshot.of(1, "minecraft:zombie", 3, 64, 0));   // 生效
        ms.add(ScaryMobSnapshot.of(2, "minecraft:zombie", 30, 64, 0));  // 太远

        PanicAnalysis a = PanicResolver.resolve(vs, ms, null);
        List<Integer> idle = a.idleMobs(Arrays.asList(1, 2));
        assertEquals(1, idle.size());
        assertEquals(2, idle.get(0).intValue());
        assertEquals("too_far", a.inactiveReasonByMob.get(2));
    }

    @Test
    void analysisTracksPerVillagerPanicState() {
        List<VillagerSnapshot> vs = new ArrayList<VillagerSnapshot>();
        vs.add(villager(1, 0, 64, 0));
        vs.add(villager(2, 100, 64, 0));
        List<ScaryMobSnapshot> ms = oneMob(ScaryMobSnapshot.of(9, "minecraft:zombie", 3, 64, 0));

        PanicAnalysis a = PanicResolver.resolve(vs, ms, null);
        assertTrue(a.isPanicking(1));
        assertFalse(a.isPanicking(2));
        assertEquals(1, a.scareCountOf(1));
        assertEquals(0, a.scareCountOf(2));
    }

    @Test
    void unknownEntityParsesToOther() {
        assertEquals(ScaryMob.OTHER, ScaryMob.byEntityId("minecraft:warden"));
        assertEquals(ScaryMob.OTHER, ScaryMob.byEntityId(null));
        assertEquals(ScaryMob.OTHER, ScaryMob.byEntityId(""));
        assertFalse(ScaryMob.OTHER.isKnown());
    }

    @Test
    void entityIdAcceptsNamespacedAndBareForms() {
        assertEquals(ScaryMob.ZOMBIE, ScaryMob.byEntityId("minecraft:zombie"));
        assertEquals(ScaryMob.ZOMBIE, ScaryMob.byEntityId("zombie"));
        assertEquals(ScaryMob.PILLAGER, ScaryMob.byEntityId("pillager"));
        assertEquals(ScaryMob.ZOMBIE_VILLAGER, ScaryMob.byEntityId("minecraft:zombie_villager"));
    }

    @Test
    void emptyInputsGiveEmptyAnalysis() {
        PanicAnalysis a = PanicResolver.resolve(new ArrayList<VillagerSnapshot>(),
                new ArrayList<ScaryMobSnapshot>(), null);
        assertFalse(a.hasAnyLink());
        assertEquals(-1, a.primaryScarer());
        assertEquals(0, a.totalEffectiveLinks());
    }
}
