import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 key=value 的 .lang 文本文件转换成 Minecraft 真正读取的 en_us.json / zh_cn.json。
 *
 * <p><b>为什么需要这个工具 —— 一个让整个界面失灵的严重错误：</b></p>
 *
 * <p>我一开始按 Bukkit/Forge 时代的习惯写了 {@code zh_cn.lang}（{@code key=value} 文本），
 * 一共 196 行，中英各一份，测试还专门断言过"两个文件键集合一致"。</p>
 *
 * <p>但 Minecraft（Fabric 同样）只认
 * {@code assets/<modid>/lang/en_us.json} 和 {@code zh_cn.json}，<b>JSON 格式</b>。
 * 原版自己就是 {@code assets/minecraft/lang/en_us.json}（已核实）。</p>
 *
 * <p>结果：游戏里所有按钮显示原始键名
 * {@code ironfarm.gui.settings.master}，一个字的翻译都没有。
 * 而这个错误**编译器发现不了、引用检查发现不了、单元测试也发现不了** ——
 * 我的测试只是自己读那两份 .lang 文件做比对，属于自说自话。</p>
 *
 * <p>用法：</p>
 * <pre>
 * java tools/LangToJson.java &lt;lang目录&gt;
 * </pre>
 *
 * <p>会生成同目录下的 {@code zh_cn.json} / {@code en_us.json}，
 * 并保留 .lang 作为人类可读的源文件（构建时由 gradle 自动转换，
 * 见 engine/build.gradle 里的 langToJson 任务）。</p>
 */
public final class LangToJson {

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "engine/src/main/resources/assets/ironfarm/lang");
        if (!Files.isDirectory(dir)) {
            System.err.println("不是目录: " + dir);
            System.exit(2);
        }
        int count = 0;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".lang")).toList()) {
                String name = p.getFileName().toString();
                String base = name.substring(0, name.length() - ".lang".length());
                Path out = dir.resolve(base + ".json");
                int n = convert(p, out);
                System.out.println(name + " -> " + out.getFileName() + " (" + n + " 条)");
                count += n;
            }
        }
        System.out.println("共 " + count + " 条");
    }

    /** 转换单个文件，返回写入的条目数。 */
    public static int convert(Path langFile, Path jsonFile) throws IOException {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : Files.readAllLines(langFile, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = t.substring(0, eq).trim();
            String value = t.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                kv.put(key, value);
            }
        }
        Files.writeString(jsonFile, toJson(kv), StandardCharsets.UTF_8);
        return kv.size();
    }

    /**
     * 生成 JSON。
     *
     * <p>手写而不是引第三方 JSON 库 —— 工程规矩是尽量少依赖，
     * 而且这里的输入输出都很简单（扁平 string→string），
     * 引入 gson/jackson 只为这一件事不值得。</p>
     */
    public static String toJson(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder(kv.size() * 64);
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : kv.entrySet()) {
            sb.append("  \"").append(escape(e.getKey())).append("\": \"")
                    .append(escape(e.getValue())).append('"');
            if (++i < kv.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * JSON 字符串转义。
     *
     * <p>⚠ 中文**不需要**转成反斜杠-u 形式的转义 —— JSON 允许 UTF-8 直出，
     * 而且保留原文更利于人工检查。这里只处理必须转义的字符。</p>
     */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
