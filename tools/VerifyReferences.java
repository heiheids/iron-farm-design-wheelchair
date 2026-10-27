import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 引用完整性检查器。
 *
 * <p><b>为什么需要它</b>：本机跑不起 Minecraft 客户端（LWJGL/SDL 初始化失败），
 * 而模组的 {@code environment} 是 {@code client}，所以 {@code runServer}
 * 根本不会加载它 —— 无头服务端验证对这个模组是**无效的**。</p>
 *
 * <p>但有一类问题比"跑不起来"更隐蔽：**remap 错了**。
 * 编译期一切正常、jar 也能打出来，可一进游戏就 {@code NoSuchMethodError} /
 * {@code NoSuchFieldError}。典型原因是 AccessWidener 或 mixin 引用了
 * 不存在的中介名，或者跨版本搬代码时方法签名变了。</p>
 *
 * <p>这个工具做的事：把模组 jar 里每一个类文件的常量池摊开，
 * 取出所有 {@code net/minecraft/...} 的类引用、字段引用、方法引用，
 * 逐个到**真实的 remapped Minecraft jar** 里核对该成员是否存在。
 * 这就是 JVM 链接阶段会做的事，只是提前到构建期。</p>
 *
 * <p>用法：</p>
 * <pre>
 * java tools/VerifyReferences.java &lt;mod.jar&gt; &lt;minecraft-remapped.jar&gt; [inner.jar...]
 * </pre>
 *
 * <p>退出码：0 = 全部通过，1 = 有缺失引用。</p>
 */
public final class VerifyReferences {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java VerifyReferences.java <mod.jar> <minecraft.jar> [inner.jar...]");
            System.exit(2);
        }

        Path modJar = Path.of(args[0]);
        List<Path> minecraftJars = new ArrayList<>();
        minecraftJars.add(Path.of(args[1]));
        // 额外传入的 jar 视为"模组自带依赖"（例如 JiJ 内嵌的 engine jar）
        List<Path> modJars = new ArrayList<>();
        modJars.add(modJar);
        for (int i = 2; i < args.length; i++) {
            modJars.add(Path.of(args[i]));
        }

        System.out.println("mod jar(s)  : " + modJars);
        System.out.println("minecraft   : " + minecraftJars);

        // 1) 索引：模组自己提供哪些类
        Set<String> providedClasses = new LinkedHashSet<>();
        for (Path p : modJars) {
            providedClasses.addAll(listClasses(p));
        }
        System.out.println("提供的类数  : " + providedClasses.size());

        // 2) 索引：Minecraft 提供哪些类（只统计 net/minecraft/*）
        Set<String> mcClasses = new LinkedHashSet<>();
        for (Path p : minecraftJars) {
            for (String c : listClasses(p)) {
                if (c.startsWith("net/minecraft/")) {
                    mcClasses.add(c);
                }
            }
        }
        System.out.println("MC 类数     : " + mcClasses.size());

        // 3) 收集模组里所有对 net/minecraft 的引用
        Set<String> neededClasses = new LinkedHashSet<>();
        Map<String, Set<String>> neededMembers = new HashMap<>();
        int scanned = 0;
        for (Path p : modJars) {
            scanned += collectReferences(p, neededClasses, neededMembers);
        }
        System.out.println("扫描类文件  : " + scanned);
        System.out.println("引用 MC 类  : " + neededClasses.size());
        System.out.println("引用 MC 成员: " + neededMembers.size() + " 个类上有");

        // 4) 校验类存在性
        List<String> missingClasses = new ArrayList<>();
        for (String c : neededClasses) {
            if (!mcClasses.contains(c) && !providedClasses.contains(c)) {
                missingClasses.add(c);
            }
        }

        // 5) 校验成员存在性（只对存在于 MC 的类做）
        List<String> missingMembers = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : neededMembers.entrySet()) {
            String owner = e.getKey();
            if (!mcClasses.contains(owner)) {
                continue; // 类本身缺失已在上面报过
            }
            Set<String> have = allMembersOf(minecraftJars, owner, mcClasses);
            if (have.isEmpty()) {
                // 读不出成员表就跳过，避免误报
                continue;
            }
            for (String m : e.getValue()) {
                if (!have.contains(m)) {
                    missingMembers.add(owner + "." + m);
                }
            }
        }

        // 6) 报告
        System.out.println();
        System.out.println("================ 结果 ================");
        if (missingClasses.isEmpty() && missingMembers.isEmpty()) {
            System.out.println("PASS  所有 Minecraft 引用都在目标 jar 里存在");
            System.exit(0);
        }
        if (!missingClasses.isEmpty()) {
            System.out.println("FAIL  缺失类引用 " + missingClasses.size() + " 个：");
            for (String c : missingClasses) {
                System.out.println("  class " + c);
            }
        }
        if (!missingMembers.isEmpty()) {
            System.out.println("FAIL  缺失成员引用 " + missingMembers.size() + " 个：");
            for (String m : missingMembers) {
                System.out.println("  member " + m);
            }
        }
        System.exit(1);
    }

    // ------------------------------------------------------------------

    /** 列出 jar 里所有 .class 的内部名（不含 .class 后缀）。 */
    private static Set<String> listClasses(Path jar) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.endsWith(".class") && !n.contains("META-INF/")) {
                    out.add(n.substring(0, n.length() - 6));
                }
            }
        }
        return out;
    }

    /**
     * 读一个 jar 里所有类文件，收集对 net/minecraft 的类引用与成员引用。
     *
     * @return 扫描的类文件数
     */
    private static int collectReferences(
            Path jar,
            Set<String> neededClasses,
            Map<String, Set<String>> neededMembers) throws Exception {
        int count = 0;
        ClassFile cf = ClassFile.of();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry je = en.nextElement();
                if (!je.getName().endsWith(".class") || je.getName().contains("META-INF/")) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(je)) {
                    java.lang.classfile.ClassModel cm = cf.parse(in.readAllBytes());
                    count++;
                    for (PoolEntry pe : cm.constantPool()) {
                        if (pe instanceof ClassEntry ce) {
                            String n = ce.asInternalName();
                            if (n.startsWith("net/minecraft/")) {
                                neededClasses.add(n);
                            }
                        } else if (pe instanceof MemberRefEntry mre) {
                            String owner = mre.owner().asInternalName();
                            if (!owner.startsWith("net/minecraft/")) {
                                continue;
                            }
                            if (!(mre instanceof FieldRefEntry)
                                    && !(mre instanceof MethodRefEntry)
                                    && !(mre instanceof InterfaceMethodRefEntry)) {
                                continue;
                            }
                            neededClasses.add(owner);
                            neededMembers
                                    .computeIfAbsent(owner, k -> new LinkedHashSet<>())
                                    .add(mre.name().stringValue() + mre.type().stringValue());
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * 取某个类**及其所有父类/接口**的成员签名集合。
     *
     * <p>⚠ 这是本工具最容易写错的地方。第一版只查了类自己声明的成员，
     * 结果把 {@code Player.getBoundingBox()}（其实声明在 {@code Entity} 上）、
     * {@code Villager.blockPosition()}（声明在 {@code Entity} 上）之类
     * 全部报成"缺失"，一口气误报 34 条。</p>
     *
     * <p>JVM 解析方法引用时会沿父类链查找，所以这里也必须沿链查。
     * 只沿链查一层也不够 —— 继承往往是多层的。</p>
     */
    private static Set<String> allMembersOf(
            List<Path> jars, String internalName, Set<String> knownClasses) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        collectMembers(jars, internalName, knownClasses, out, 0);
        return out;
    }

    private static void collectMembers(
            List<Path> jars, String internalName, Set<String> knownClasses,
            Set<String> out, int depth) throws Exception {
        if (depth > 12 || internalName == null || out.contains("##done:" + internalName)) {
            return;
        }
        out.add("##done:" + internalName);

        byte[] bytes = readClassBytes(jars, internalName);
        if (bytes == null) {
            return;
        }
        ClassFile cf = ClassFile.of();
        java.lang.classfile.ClassModel cm = cf.parse(bytes);
        cm.fields().forEach(f -> out.add(f.fieldName().stringValue() + f.fieldType().stringValue()));
        cm.methods().forEach(m -> out.add(m.methodName().stringValue() + m.methodType().stringValue()));

        // 沿父类与接口继续找
        if (cm.superclass().isPresent()) {
            String sup = cm.superclass().get().asInternalName();
            if (knownClasses.contains(sup)) {
                collectMembers(jars, sup, knownClasses, out, depth + 1);
            }
        }
        for (java.lang.classfile.constantpool.ClassEntry iface : cm.interfaces()) {
            String n = iface.asInternalName();
            if (knownClasses.contains(n)) {
                collectMembers(jars, n, knownClasses, out, depth + 1);
            }
        }
    }

    /** 在若干 jar 里找某个类的字节码。 */
    private static byte[] readClassBytes(List<Path> jars, String internalName) throws Exception {
        String path = internalName + ".class";
        for (Path p : jars) {
            try (JarFile jf = new JarFile(p.toFile())) {
                JarEntry je = jf.getJarEntry(path);
                if (je != null) {
                    try (InputStream in = jf.getInputStream(je)) {
                        return in.readAllBytes();
                    }
                }
            }
        }
        return null;
    }
}
