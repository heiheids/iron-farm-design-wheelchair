package com.heishandashu.ironfarm.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃回归测试。
 *
 * <p><b>真实事故（用户："我放下僵尸崩溃了"）：</b></p>
 *
 * <pre>
 * java.lang.NullPointerException: Cannot invoke
 *     "net.minecraft.class_1297.method_21752()" because "$$0" is null
 *   at net.minecraft.class_3727.&lt;init&gt;(class_3727.java:42)      ← ClipContext 构造器
 *   at WorldSightQuery.clearLineOfSight(WorldSightQuery.java:40)
 *   at PanicResolver.analyze(PanicResolver.java:127)
 * </pre>
 *
 * <p>原因：{@code ClipContext} 有两个构造器，一个是 {@code (…, Entity)}，
 * 一个是 {@code (…, CollisionContext)}。我写了 {@code (Entity) null}，
 * 而 Entity 版构造器内部会立刻调 {@code entity.getBoundingBox()} → NPE。</p>
 *
 * <p>触发条件很巧：只有在**有怪物、需要做视线检测**时才会走到那段代码。
 * 所以平时不崩，一放僵尸就崩。</p>
 */
class SightQueryCrashTest {

    @Test
    void clipContextNeverReceivesNullEntity() throws IOException {
        String code = stripComments(readSrc("adapter/WorldSightQuery.java"));

        assertFalse(code.contains("(Entity) null"),
                "绝不能给 ClipContext 传 (Entity) null —— 构造器会立刻调 "
                        + "entity.getBoundingBox()，直接 NPE");
        assertFalse(code.contains("(Entity)null"),
                "同上（无空格的写法）");
    }

    @Test
    void clipContextUsesCollisionContext() throws IOException {
        String code = stripComments(readSrc("adapter/WorldSightQuery.java"));

        assertTrue(code.contains("CollisionContext.empty()"),
                "应当用 CollisionContext.empty() —— 语义正是「只关心方块遮挡」");
    }

    /**
     * 整个工程里都不该出现 {@code (Entity) null} 这种强转。
     *
     * <p>MC 里凡是接受 Entity 的 API，多半会立刻用它读数据（包围盒、等级、UUID…），
     * 传 null 迟早崩。要"没有实体"的语义，就找接受上下文对象的重载。</p>
     */
    @Test
    void noNullEntityCastsAnywhere() throws IOException {
        Path root = Path.of("fabric-1.21.1/src/main/java");
        if (!Files.exists(root)) {
            root = Path.of("../fabric-1.21.1/src/main/java");
        }
        assertTrue(Files.exists(root));

        StringBuilder offenders = new StringBuilder();
        try (var s = Files.walk(root)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (code.contains("(Entity) null") || code.contains("(Entity)null")) {
                    offenders.append(p.getFileName()).append(' ');
                }
            }
        }
        assertTrue(offenders.length() == 0,
                "以下文件仍把 null 强转成 Entity: " + offenders);
    }

    // ------------------------------------------------------------------

    private static String readSrc(String relative) throws IOException {
        Path p = Path.of("fabric-1.21.1/src/main/java/com/heishandashu/ironfarm/fabric/" + relative);
        if (!Files.exists(p)) {
            p = Path.of("../" + p);
        }
        assertTrue(Files.exists(p), "找不到文件: " + relative);
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
}
