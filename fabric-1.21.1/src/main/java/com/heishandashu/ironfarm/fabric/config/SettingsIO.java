package com.heishandashu.ironfarm.fabric.config;

import com.heishandashu.ironfarm.engine.config.ModSettings;
import com.heishandashu.ironfarm.engine.config.SettingsCodec;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设置在磁盘上的读写。
 *
 * <p>位置：{@code <游戏目录>/config/ironfarm.properties}。
 * 用 {@link FabricLoader#getConfigDir()} 而不是自己拼路径 ——
 * 那能正确处理自定义游戏目录、多个实例、以及 .minecraft 不在默认位置的情况。</p>
 *
 * <p><b>失败策略</b>：任何 IO 异常都只记日志并返回内存里的默认值，
 * 绝不让模组因为配置文件问题而起不来。玩家的存档比设置重要得多。</p>
 */
public final class SettingsIO {

    private static final Logger LOG = LoggerFactory.getLogger("ironfarm");
    private static final String FILE_NAME = "ironfarm.properties";

    private SettingsIO() {
    }

    /** 配置文件路径。 */
    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * 从磁盘读；文件不存在或读失败时返回默认设置。
     *
     * <p>文件不存在时会顺手写一份带注释的默认配置，
     * 方便玩家找到并手改。</p>
     */
    public static ModSettings load() {
        Path p = path();
        if (!Files.exists(p)) {
            ModSettings defaults = new ModSettings();
            save(defaults); // 生成一份可读的默认文件
            return defaults;
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            ModSettings s = SettingsCodec.read(r);
            LOG.info("[ironfarm] settings loaded from {}", p);
            return s;
        } catch (IOException | RuntimeException e) {
            LOG.warn("[ironfarm] failed to read settings, using defaults: {}", e.toString());
            return new ModSettings();
        }
    }

    /**
     * 保存到磁盘。
     *
     * @return 是否成功
     */
    public static boolean save(ModSettings settings) {
        Path p = path();
        try {
            Path parent = p.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                SettingsCodec.write(settings, w);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("[ironfarm] failed to save settings to {}: {}", p, e.toString());
            return false;
        }
    }
}
