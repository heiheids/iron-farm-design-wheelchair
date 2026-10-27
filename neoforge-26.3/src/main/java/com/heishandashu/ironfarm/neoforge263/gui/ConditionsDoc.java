package com.heishandashu.ironfarm.neoforge263.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 读取打包在 jar 里的《铁傀儡刷新条件-详解》。
 *
 * <p><b>为什么把长文档放资源文件，而不是塞进语言文件：</b></p>
 *
 * <ul>
 *   <li>文档有 470+ 行、17 KB。塞进 lang 会让语言文件膨胀到没法维护，
 *       而且每改一句都要同时改两份 .lang + 重新生成 JSON；</li>
 *   <li>文档是**给人读的排版文本**（有缩进、有空行、有表格），
 *       语言文件是 key=value，硬塞进去要手工转义每一行；</li>
 *   <li>放资源文件后，中英文各一份纯文本，直接编辑即可，
 *       和根目录的那份保持同源。</li>
 * </ul>
 *
 * <p>资源位置：{@code assets/ironfarm/conditions/zh_cn.txt} 与 {@code en_us.txt}。</p>
 *
 * <p>读取失败时返回 null，调用方回退到简版说明 ——
 * 绝不能让"读不到文档"变成界面崩溃。</p>
 */
public final class ConditionsDoc {

    private static final Logger LOG = LoggerFactory.getLogger("ironfarm");

    /** 缓存：避免每帧都读盘。 */
    private static List<String> cachedLines;
    private static String cachedLang;
    private static boolean loadFailed;

    private ConditionsDoc() {
    }

    /**
     * 取文档行（已按语言选择）。
     *
     * @param langCode {@code "zh_cn"} 或 {@code "en_us"}
     * @return 文档的所有行；读不到返回 null
     */
    public static List<String> lines(String langCode) {
        String code = "zh_cn".equals(langCode) ? "zh_cn" : "en_us";
        if (cachedLines != null && code.equals(cachedLang)) {
            return cachedLines;
        }
        if (loadFailed && code.equals(cachedLang)) {
            return null;
        }

        List<String> out = read("assets/ironfarm/conditions/" + code + ".txt");
        if (out == null && !"en_us".equals(code)) {
            // 中文缺失时退回英文，总比什么都没有好
            out = read("assets/ironfarm/conditions/en_us.txt");
        }

        if (out == null) {
            LOG.warn("[ironfarm] conditions document not found for locale {}", code);
            cachedLang = code;
            loadFailed = true;
            return null;
        }
        LOG.info("[ironfarm] conditions document loaded: {} lines ({})", out.size(), code);
        cachedLines = out;
        cachedLang = code;
        loadFailed = false;
        return out;
    }

    /** 清缓存（资源重载时用）。 */
    public static void invalidate() {
        cachedLines = null;
        cachedLang = null;
        loadFailed = false;
    }

    // ------------------------------------------------------------------

    private static List<String> read(String path) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) {
            return null;
        }
        Identifier id = Identifier.tryParse("ironfarm:" + path.substring("assets/ironfarm/".length()));
        if (id == null) {
            return null;
        }
        try {
            Optional<Resource> res = mc.getResourceManager().getResource(id);
            if (res.isEmpty()) {
                return null;
            }
            List<String> out = new ArrayList<String>();
            try (InputStream in = res.get().open();
                 BufferedReader r = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.add(line);
                }
            }
            return out;
        } catch (Exception e) {
            LOG.warn("[ironfarm] failed to read {}: {}", id, e.toString());
            return null;
        }
    }
}
