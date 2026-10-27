import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Mixin 配置静态校验：用 named jar 对照，验证 @Mixin 目标类、
 * @Inject 目标方法、@Shadow 字段是否真的存在。
 */
public final class MixinCheck {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: MixinCheck <mod.jar> <minecraft-named.jar> <mixin.json>");
            System.exit(2);
        }
        Path modJar = Path.of(args[0]);
        Path mcJar = Path.of(args[1]);
        Path mixinJson = Path.of(args[2]);
        Path srcRoot = Path.of("fabric-1.21.1/src/main/java");

        String json = Files.readString(mixinJson, StandardCharsets.UTF_8);
        String pkg = jsonString(json, "package");
        List<String> all = new ArrayList<>();
        all.addAll(jsonArray(json, "client"));
        all.addAll(jsonArray(json, "server"));
        all.addAll(jsonArray(json, "mixins"));

        System.out.println("package       : " + pkg);
        System.out.println("mixins        : " + all);
        System.out.println("mc jar        : " + mcJar.getFileName());

        if (all.isEmpty()) {
            System.out.println("no mixins, skip");
            System.exit(0);
        }

        List<String> problems = new ArrayList<>();
        Set<String> modClasses = listClasses(modJar);
        Set<String> mcClasses = listClasses(mcJar);

        for (String simple : all) {
            String internal = (pkg.replace('.', '/') + "/" + simple).replace('.', '/');
            if (!modClasses.contains(internal)) {
                problems.add("mixin class missing in jar: " + internal);
                continue;
            }
            Path src = srcRoot.resolve(pkg.replace('.', '/')).resolve(simple + ".java");
            if (!Files.exists(src)) {
                problems.add(simple + ": source not found " + src);
                continue;
            }
            String code = Files.readString(src, StandardCharsets.UTF_8);
            String target = mixinTarget(code);
            if (target == null) {
                problems.add(simple + ": @Mixin target not found");
                continue;
            }
            String resolved = resolveImport(code, target);
            String ti = resolved.replace('.', '/');
            if (!mcClasses.contains(ti)) {
                problems.add(simple + ": @Mixin target class missing: " + resolved);
                continue;
            }
            System.out.println("  " + simple + " -> " + resolved);

            Set<String> members = allMembers(mcJar, mcClasses, ti);

            for (String m : injectMethods(code)) {
                if (members.contains(m)) {
                    System.out.println("    @Inject " + m + " OK");
                } else {
                    problems.add(simple + ": @Inject method missing: " + resolved + "." + m + "()");
                }
            }
            for (String f : shadowFields(code)) {
                if (members.contains(f)) {
                    System.out.println("    @Shadow " + f + " OK");
                } else {
                    problems.add(simple + ": @Shadow field missing: " + resolved + "." + f);
                }
            }
        }

        System.out.println();
        System.out.println("================ result ================");
        if (problems.isEmpty()) {
            System.out.println("PASS  mixin targets all exist (named layer)");
            System.exit(0);
        }
        System.out.println("FAIL  " + problems.size() + " problem(s):");
        for (String p : problems) {
            System.out.println("  " + p);
        }
        System.exit(1);
    }

    private static String resolveImport(String code, String simpleName) {
        for (String line : code.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("import ") || !t.endsWith(";")) {
                continue;
            }
            String fq = t.substring(7, t.length() - 1).trim();
            if (fq.endsWith("." + simpleName)) {
                return fq;
            }
        }
        return simpleName;
    }

    private static String mixinTarget(String code) {
        int i = code.indexOf("@Mixin(");
        if (i < 0) {
            return null;
        }
        int end = code.indexOf(')', i);
        if (end < 0) {
            return null;
        }
        String inside = code.substring(i + 7, end);
        int dot = inside.indexOf(".class");
        return dot < 0 ? null : inside.substring(0, dot).trim();
    }

    private static List<String> injectMethods(String code) {
        List<String> out = new ArrayList<>();
        int idx = 0;
        while ((idx = code.indexOf("@Inject(", idx)) >= 0) {
            int mk = code.indexOf("method", idx);
            if (mk < 0 || mk - idx > 400) {
                idx += 8;
                continue;
            }
            int q1 = code.indexOf('"', mk);
            int q2 = q1 < 0 ? -1 : code.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) {
                idx += 8;
                continue;
            }
            String m = code.substring(q1 + 1, q2);
            int semi = m.indexOf(';');
            if (semi > 0) {
                m = m.substring(0, semi);
            }
            if (!m.isEmpty()) {
                out.add(m);
            }
            idx = q2;
        }
        return out;
    }

    /**
     * 提取 @Shadow 之后声明的字段名。
     *
     * <p>⚠ 这里踩过一个坑：本工程的 mixin 用的是**全限定名**
     * {@code @org.spongepowered.asm.mixin.Shadow}。
     * 第一版只匹配 {@code @Shadow}，于是一个字段都没提取到，
     * 反验证（故意把 leftPos 改成 leftPosTypo）时它仍然报 PASS ——
     * 检查器自己失效了却装成"一切正常"，这是最糟的失败方式。</p>
     *
     * <p>现在两个名字都认。</p>
     */
    private static List<String> shadowFields(String code) {
        List<String> out = new ArrayList<>();
        String[] lines = code.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            // 同时支持 @Shadow 与 @org.spongepowered.asm.mixin.Shadow
            int at = raw.indexOf("@Shadow");
            if (at < 0) {
                at = raw.indexOf(".Shadow");
                if (at >= 0) {
                    at = raw.lastIndexOf('@', at);
                }
            }
            if (at < 0) {
                continue;
            }
            for (int j = i; j < Math.min(lines.length, i + 3); j++) {
                String line = lines[j];
                if (j == i) {
                    int atIdx = line.indexOf('@', at);
                    line = atIdx >= 0 ? line.substring(atIdx + 1) : "";
                    int dot = line.lastIndexOf('.');
                    if (dot >= 0) {
                        line = line.substring(dot + 1);
                    }
                    line = line.replaceFirst("^Shadow", "");
                }
                String decl = line.trim();
                if (decl.isEmpty() || decl.startsWith("@") || decl.startsWith("//")
                        || decl.startsWith("/*") || decl.startsWith("*")) {
                    continue;
                }
                int semi = decl.indexOf(';');
                if (semi < 0) {
                    continue;
                }
                String[] parts = decl.substring(0, semi).trim().split("\\s+");
                if (parts.length >= 2) {
                    out.add(parts[parts.length - 1]);
                    break;
                }
            }
        }
        return out;
    }

    private static Set<String> allMembers(Path jar, Set<String> known, String internal)
            throws Exception {
        Set<String> out = new LinkedHashSet<>();
        collect(jar, known, internal, out, 0);
        return out;
    }

    private static void collect(Path jar, Set<String> known, String internal,
                                Set<String> out, int depth) throws Exception {
        if (depth > 12 || out.contains("##" + internal)) {
            return;
        }
        out.add("##" + internal);
        byte[] bytes = readClass(jar, internal);
        if (bytes == null) {
            return;
        }
        java.lang.classfile.ClassFile cf = java.lang.classfile.ClassFile.of();
        java.lang.classfile.ClassModel cm = cf.parse(bytes);
        cm.fields().forEach(f -> out.add(f.fieldName().stringValue()));
        cm.methods().forEach(m -> out.add(m.methodName().stringValue()));
        if (cm.superclass().isPresent()) {
            String s = cm.superclass().get().asInternalName();
            if (known.contains(s)) {
                collect(jar, known, s, out, depth + 1);
            }
        }
        for (java.lang.classfile.constantpool.ClassEntry ie : cm.interfaces()) {
            String n = ie.asInternalName();
            if (known.contains(n)) {
                collect(jar, known, n, out, depth + 1);
            }
        }
    }

    private static Set<String> listClasses(Path jar) throws IOException {
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

    private static byte[] readClass(Path jar, String internal) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry je = jf.getJarEntry(internal + ".class");
            if (je == null) {
                return null;
            }
            try (InputStream in = jf.getInputStream(je)) {
                return in.readAllBytes();
            }
        }
    }

    private static String jsonString(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        int q1 = json.indexOf('"', colon);
        int q2 = json.indexOf('"', q1 + 1);
        return (q1 < 0 || q2 < 0) ? null : json.substring(q1 + 1, q2);
    }

    private static List<String> jsonArray(String json, String key) {
        List<String> out = new ArrayList<>();
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return out;
        }
        int open = json.indexOf('[', i);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) {
            return out;
        }
        for (String part : json.substring(open + 1, close).split(",")) {
            String t = part.trim();
            if (t.startsWith("\"") && t.endsWith("\"")) {
                out.add(t.substring(1, t.length() - 1));
            }
        }
        return out;
    }
}