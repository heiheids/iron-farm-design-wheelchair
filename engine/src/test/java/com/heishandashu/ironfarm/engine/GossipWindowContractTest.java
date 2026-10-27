package com.heishandashu.ironfarm.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闲聊窗口的契约测试。
 *
 * <p>这里只测**引擎侧**应当遵守的常量与语义。
 * 适配层（{@code GossipWindow}）读的是 {@code Villager.lastGossipTime} 字段，
 * 那个值来自 1.21.1 字节码里的 {@code ldc2_w 1200l}。</p>
 *
 * <p>为什么把这个数字写进引擎侧的测试：它是"喇叭图标亮多久"的唯一依据，
 * 改了它必须有人知道。而且第一版这里写的是 200（猜的），
 * 这条测试就是为了防止再退回"猜"。</p>
 */
class GossipWindowContractTest {

    /**
     * 原版 {@code Villager.gossip} 里的冷却：
     * {@code if (gameTime >= this.lastGossipTime + 1200)} 才真正执行。
     * 所以同一只村民最多每 1200 tick 闲聊一次。
     */
    private static final int EXPECTED_WINDOW = 1200;

    @Test
    void gossipWindowIs1200Ticks() throws IOException {
        // 直接读适配层源码里的常量，确保两边不会漂移
        String src = readFile("fabric-1.21.1/src/main/java/com/heishandashu/ironfarm/fabric/collect/GossipWindow.java");
        assertTrue(src.contains("TICKS = " + EXPECTED_WINDOW),
                "GossipWindow.TICKS 应为 " + EXPECTED_WINDOW + "（来自 Villager.gossip 的 ldc2_w 1200l）");
    }

    @Test
    void accessWidenerExposesLastGossipTime() throws IOException {
        String aw = readFile("fabric-1.21.1/src/main/resources/ironfarm.accesswidener");
        assertTrue(aw.contains("lastGossipTime"),
                "accesswidener 必须开放 lastGossipTime，否则只能靠猜");
        assertTrue(aw.contains("net/minecraft/world/entity/npc/Villager"));
    }

    @Test
    void modJsonDeclaresAccessWidener() throws IOException {
        String json = readFile("fabric-1.21.1/src/main/resources/fabric.mod.json");
        assertTrue(json.contains("\"accessWidener\""));
        assertTrue(json.contains("ironfarm.accesswidener"));
    }

    @Test
    void gossipTrackerIsGone() {
        // 旧实现依赖一个从未被调用的 onGossip()，会让喇叭永远显示红叉。
        // 现在改成直接读 lastGossipTime，这个类不应再存在。
        java.io.File f = new java.io.File(
                "fabric-1.21.1/src/main/java/com/heishandashu/ironfarm/fabric/collect/GossipTracker.java");
        assertTrue(!f.exists(), "GossipTracker 应已删除（它依赖从未被调用的 onGossip）");
    }

    @Test
    void gossipWindowIsNotTheOldGuessedValue() {
        // 第一版是 200（猜的），这条防止回退
        assertTrue(EXPECTED_WINDOW != 200, "200 是早期猜测值，不要退回去");
        assertEquals(1200, EXPECTED_WINDOW);
    }

    // ------------------------------------------------------------------

    private static String readFile(String relative) throws IOException {
        // Gradle 测试的工作目录是模块目录（engine/），所以往上找一级
        java.io.File f = new java.io.File(relative);
        if (!f.exists()) {
            f = new java.io.File("../" + relative);
        }
        assertTrue(f.exists(), "找不到文件: " + relative + " (cwd=" + new java.io.File(".").getAbsolutePath() + ")");
        try (InputStream in = new java.io.FileInputStream(f)) {
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
