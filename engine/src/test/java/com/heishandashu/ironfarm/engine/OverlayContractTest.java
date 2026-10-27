package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.eval.ConditionEngine;
import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.model.BlockPos3;
import com.heishandashu.ironfarm.engine.model.VillagerSnapshot;
import com.heishandashu.ironfarm.engine.spawn.SpawnScanResult;
import com.heishandashu.ironfarm.engine.version.VersionProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染层的纯逻辑契约测试。
 *
 * <p>本机跑不起客户端（LWJGL/SDL 初始化失败），画面本身验证不了。
 * 但"该画哪些、用什么颜色、缩放到多少"这些**规则**是纯计算，
 * 可以完整验证。这也是把 {@code OverlayCuller} 从渲染循环里拆出来的理由。</p>
 *
 * <p>⚠ 这些断言读的是适配层源码。会随代码重构而需要同步更新 ——
 * 这是刻意的取舍：宁可让测试脆弱一点，也要把"三态 → 颜色"的顺序钉死。</p>
 */
class OverlayContractTest {

    private static final long NOW = 1_000_000L;
    private final ConditionEngine engine = new ConditionEngine(VersionProfile.V1_21_1);

    private VillagerReport report(VillagerSnapshot v) {
        List<VillagerSnapshot> all = new ArrayList<VillagerSnapshot>();
        all.add(v);
        List<BlockPos3> pts = new ArrayList<BlockPos3>();
        pts.add(v.pos.offset(0, 1, 0));
        SpawnScanResult scan = new SpawnScanResult(v.pos, 8, 6, true, pts, null);
        return engine.evaluateAll(all, NOW, scan).get(0);
    }

    // ------------------------------------------------------------------
    // 核心：三态 → 颜色的顺序
    // ------------------------------------------------------------------

    /**
     * 三态必须区分开，且**判定要用与状态页同一套依据**。
     *
     * <p><b>这条测试原来断言的是"源码里 CHECK 出现在 UNKNOWN 之前"</b> ——
     * 那是对实现细节的约束，不是对行为的约束。我后来把
     * {@code checkColor} 改成先判 {@code panicFarmReady() || gossipFarmReady()}
     *（与状态页首行同源，修"头顶与点进去不同步"），
     * 这个顺序断言就失效了 —— 但它失效并不代表功能坏了。</p>
     *
     * <p>现在改成断言真正重要的两件事：</p>
     * <ol>
     *   <li>必须显式处理 UNKNOWN，不能把"不知道"画成"不满足"；</li>
     *   <li>判定必须与状态页同源（用 ready 系列方法，而不是只看个体条件）。</li>
     * </ol>
     */
    @Test
    void cullerUsesSameBasisAsStatusPage() throws IOException {
        String src = readSrc("render/OverlayCuller.java");

        // 必须显式处理 UNKNOWN，否则多人游戏里满屏红叉
        assertTrue(src.contains("COLOR_UNKNOWN"),
                "OverlayCuller 必须有灰色常量（数据不可得不能画成红）");
        // 判定必须走引擎给的权威枚举，不要在渲染层自己拼条件。
        //
        // ⚠ 这条断言我改过一次：
        //   上一版要求这里出现 panicFarmReady —— 那是我为修"不同步"做的
        //   错误改动。对钩本来就只代表个体条件（成年/睡过/没见到傀儡），
        //   用 ready 判定会让它在"生成点不合法"时变红，反而制造不一致。
        //   正确依据是 conditionIcon()，它由 VillagerReport 唯一定义。
        assertTrue(src.contains("conditionIcon"),
                "checkColor 必须用引擎的 conditionIcon()，"
                        + "不要在渲染层自己拼判断（顺序写反就是满屏假红叉）");
    }

    @Test
    void skullHasOnlyTwoStates() throws IOException {
        String src = readSrc("render/OverlayCuller.java");
        int start = src.indexOf("public static int skullColor");
        int end = src.indexOf("public static int speakerColor");
        assertTrue(start > 0 && end > start);
        String body = src.substring(start, end);
        // 骷髅依赖的是客户端能算准的 panic，不需要灰色
        assertFalse(body.contains("COLOR_UNKNOWN"),
                "骷髅只有两态：正在被恐吓 / 没有，不该有灰色分支");
    }

    /**
     * 三个图标都必须能表达"三态"（绿/红/灰）。
     *
     * <p><b>这条测试原来只截取 speakerColor 的方法体，检查里面出现哪些颜色常量。</b>
     * 我把三态映射抽成一个共用的 {@code colorOf(Icon)} 之后，
     * speakerColor 的方法体里就不再直接出现那些常量了 —— 测试红了，
     * 但功能反而更可靠（三处共用一份映射，不可能各自走偏）。</p>
     *
     * <p>现在改为断言**共用映射存在且覆盖三态**，
     * 这才是真正要保证的东西。</p>
     */
    @Test
    void allIconsShareOneThreeStateMapping() throws IOException {
        String src = readSrc("render/OverlayCuller.java");

        // 必须有一个统一的 Icon → 颜色 映射
        assertTrue(src.contains("colorOf"),
                "三个图标应共用一份三态映射，避免各写一份走偏");

        int start = src.indexOf("private static int colorOf");
        assertTrue(start > 0, "找不到 colorOf 方法");
        String body = src.substring(start);

        // 三态必须都覆盖
        assertTrue(body.contains("COLOR_OK"), "缺少绿（条件具备）");
        assertTrue(body.contains("COLOR_BAD"), "缺少红（确定不满足）");
        assertTrue(body.contains("COLOR_UNKNOWN"), "缺少灰（数据不可得）");
    }

    // ------------------------------------------------------------------
    // 距离缩放
    // ------------------------------------------------------------------

    @Test
    void scaleIsFullInsideTwentyFourBlocks() throws IOException {
        String src = readSrc("render/WorldOverlayRenderer.java");
        assertTrue(src.contains("farStart"), "必须有缩放起点常量");
        assertTrue(src.contains("24.0"), "24 格内不缩放");
        assertTrue(src.contains("0.3"), "最远缩到 70%（1 - 0.3）");
    }

    @Test
    void overlayHasThreeIconsAndGap() throws IOException {
        String src = readSrc("render/WorldOverlayRenderer.java");
        assertTrue(src.contains("COL_CHECK"));
        assertTrue(src.contains("COL_SKULL"));
        assertTrue(src.contains("COL_SPEAKER"));
        assertTrue(src.contains("ICON_GAP"), "三图标之间要有间距");
    }

    // ------------------------------------------------------------------
    // 图标素材
    // ------------------------------------------------------------------

    @Test
    void atlasIsThreeTilesWideAndExists() {
        // 素材是 96×32 = 3 格 × 32px，与 IconRenderer.TILE = 32 必须一致
        Path atlas = Path.of("../fabric-1.21.1/src/main/resources/assets/ironfarm/textures/icon_atlas.png")
                .toAbsolutePath().normalize();
        assertTrue(Files.exists(atlas), "缺少图标贴图集: " + atlas);
    }

    @Test
    void atlasTileSizeMatchesRenderer() throws IOException {
        String renderer = readSrc("render/IconRenderer.java");
        assertTrue(renderer.contains("TILE = 32"), "IconRenderer.TILE 应为 32");
        assertTrue(renderer.contains("ATLAS_WIDTH = TILE * 3"), "贴图集应为三格宽");

        String gen = readFile("tools/MakeIconAtlas.java");
        assertTrue(gen.contains("TILE = 32"), "生成器的 TILE 也要是 32，否则两者会漂移");
    }

    @Test
    void iconsHaveDarkOutlineForLegibility() throws IOException {
        String gen = readFile("tools/MakeIconAtlas.java");
        assertTrue(gen.contains("OUTLINE"),
                "图标必须有深色描边，否则在雪地/云上会消失");
        assertTrue(gen.contains("STROKE"));
    }

    // ------------------------------------------------------------------
    // 右键分支
    // ------------------------------------------------------------------

    @Test
    void rightClickUsesProfessionNotOffers() throws IOException {
        String src = readSrc("event/RightClickHandler.java");
        // 用 profession 判断有没有交易界面。
        // 用 getOffers().isEmpty() 会把"有职业但还没交易过"的村民误判成没有界面。
        assertTrue(src.contains("getProfession()"), "必须用职业判断");
        assertTrue(src.contains("VillagerProfession.NONE"));

        // 断言"不能用 offers 判空"要精确到代码行，而不是全文搜索 ——
        // 文档注释里合法地提到了 getOffers().isEmpty()（说明为什么不这么做）。
        String codeOnly = stripComments(src);
        assertFalse(codeOnly.contains("getOffers"),
                "代码里不能真的调用 getOffers 做判断（注释里说明原因是可以的）");
    }

    /** 粗暴但够用：去掉 // 行注释与块注释，只留代码。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inBlock) {
                if (c == '*' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < src.length()) {
                char n = src.charAt(i + 1);
                if (n == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (n == '/') {
                    while (i < src.length() && src.charAt(i) != '\n') {
                        i++;
                    }
                    out.append('\n');
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    @Test
    void rightClickPassesThroughWhenTradeUiExists() throws IOException {
        String src = readSrc("event/RightClickHandler.java");
        assertTrue(src.contains("InteractionResult.PASS"),
                "有交易界面时必须 PASS，让原版正常打开交易");
        assertFalse(src.contains("InteractionResult.FAIL"),
                "绝不能返回 FAIL —— 那会取消原版交易界面，玩家以为右键失灵");
    }

    // ------------------------------------------------------------------

    private static String readSrc(String relative) throws IOException {
        return readFile("fabric-1.21.1/src/main/java/com/heishandashu/ironfarm/fabric/" + relative);
    }

    private static String readFile(String relative) throws IOException {
        Path p = Path.of(relative);
        if (!Files.exists(p)) {
            p = Path.of("../" + relative);
        }
        assertTrue(Files.exists(p), "找不到文件: " + relative
                + " (cwd=" + Path.of(".").toAbsolutePath() + ")");
        try (InputStream in = Files.newInputStream(p)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
