package com.heishandashu.ironfarm.engine;

import com.heishandashu.ironfarm.engine.version.VersionProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档与代码的一致性检查。
 *
 * <p>根目录的《铁傀儡刷新条件-详解.txt》是给玩家和其他开发者看的，
 * 里面的数字必须和 {@link VersionProfile} 一致。</p>
 *
 * <p>为什么需要这个测试：文档是最容易腐烂的东西。
 * 改了代码忘了改文档，读者拿到的是错的规格 ——
 * 而这种错误**没有任何测试会报**，因为文档不是可执行代码。</p>
 */
class DocConsistencyTest {

    private static final Path DOC = Path.of("铁傀儡刷新条件-详解.txt");

    private Path doc() {
        Path p = DOC;
        if (!Files.exists(p)) {
            p = Path.of("../" + DOC);
        }
        return p;
    }

    @Test
    void docExists() {
        assertTrue(Files.exists(doc()), "根目录应当有《铁傀儡刷新条件-详解.txt》");
    }

    /**
     * 文档里出现的每个关键参数都必须与代码常量对得上。
     *
     * <p>反过来不检查（代码有、文档没写）——文档可以只讲重点。</p>
     */
    @Test
    void docNumbersMatchCode() throws IOException {
        String text = read(doc());
        VersionProfile p = VersionProfile.MODERN;

        assertContains(text, String.valueOf(p.sleepWindowTicks), "睡眠窗口");
        assertContains(text, String.valueOf(p.golemMemoryTicks), "傀儡记忆有效期");
        assertContains(text, String.valueOf(p.golemScanIntervalTicks), "傀儡扫描间隔");
        assertContains(text, String.valueOf(p.sensorRange), "实体感知范围");
        assertContains(text, String.valueOf(p.summonSpreadInflate), "统计膨胀范围");
        assertContains(text, String.valueOf(p.participantLimit), "参与者名额上限");
        assertContains(text, String.valueOf(p.spawnHalfWidth), "生成体积水平半边");
        assertContains(text, String.valueOf(p.spawnHalfHeight), "生成体积垂直半边");
        assertContains(text, String.valueOf(p.spawnAttempts), "生成尝试次数");
        assertContains(text, String.valueOf(p.panicParticipantThreshold), "恐慌门槛");
        assertContains(text, String.valueOf(p.gossipParticipantThreshold), "闲聊门槛");
        assertContains(text, String.valueOf(p.panicAttemptIntervalTicks), "恐慌尝试间隔");
    }

    /**
     * 回归钉子：文档里必须明确写 599，不能写 600。
     *
     * <p>这是最容易搞错的一个数字，也是最容易被"顺手修正"成 600 的。</p>
     */
    @Test
    void docSays599NotJust600() throws IOException {
        String text = read(doc());
        assertTrue(text.contains("599"),
                "必须写明 599 tick —— 常见资料写 600 是错的");
    }

    /**
     * 文档必须包含三条关键勘误，否则读者会被网上流传的错误说法误导。
     */
    @Test
    void docContainsTheThreeKeyCorrections() throws IOException {
        String text = read(doc());

        assertTrue(text.contains("不存在") && text.contains("召唤"),
                "必须澄清「附近村民未召唤」这条条件不存在");

        assertTrue(text.contains("参与者不需要处于恐慌状态")
                        || text.contains("参与者不需要恐慌"),
                "必须澄清参与者不需要处于恐慌状态");

        assertTrue(text.contains("凋灵") && text.contains("霜冻"),
                "必须澄清晨风与霜冻不能吓到村民");
    }

    // ------------------------------------------------------------------

    private static void assertContains(String text, String needle, String what) {
        assertTrue(text.contains(needle),
                "文档里应当出现「" + what + "」的数值 " + needle);
    }

    private static String read(Path p) throws IOException {
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
