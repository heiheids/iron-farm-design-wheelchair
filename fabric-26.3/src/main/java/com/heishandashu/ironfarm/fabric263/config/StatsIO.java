package com.heishandashu.ironfarm.fabric263.config;

import com.heishandashu.ironfarm.engine.stats.StatsCodec;
import com.heishandashu.ironfarm.engine.stats.StatsRecorder;
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
 * 统计数据的磁盘读写。
 *
 * <p>位置：{@code config/ironfarm-stats.txt}（与设置分开，因为读写时机不同）。</p>
 *
 * <p><b>为什么不在关游戏时存</b>：客户端不一定有可靠的"退出前"回调
 * （强制关闭、崩溃、杀进程都不会触发）。所以采用**定期自动存**
 * （见 {@code IronFarmClientState} 里的 {@link #AUTOSAVE_INTERVAL_TICKS}），
 * 再叠加"打开统计页时立即存一次"。
 * 代价是最多丢一个自动保存周期的数据，可接受。</p>
 *
 * <p>失败策略同设置：任何 IO 异常只记日志，绝不让模组起不来。</p>
 */
public final class StatsIO {

    private static final Logger LOG = LoggerFactory.getLogger("ironfarm");
    private static final String FILE_NAME = "ironfarm-stats.txt";

    /** 自动保存间隔：600 tick = 30 秒。 */
    public static final int AUTOSAVE_INTERVAL_TICKS = 600;

    private StatsIO() {
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 读；文件不存在或读失败返回空统计（未开始状态）。 */
    public static StatsRecorder load() {
        Path p = path();
        if (!Files.exists(p)) {
            return new StatsRecorder();
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            StatsRecorder s = StatsCodec.read(r);
            LOG.info("[ironfarm] statistics loaded from {} (spawned={})", p, s.golemsSpawned());
            return s;
        } catch (IOException | RuntimeException e) {
            LOG.warn("[ironfarm] failed to read statistics, starting fresh: {}", e.toString());
            return new StatsRecorder();
        }
    }

    /** 存。 */
    public static boolean save(StatsRecorder stats) {
        Path p = path();
        try {
            Path parent = p.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                StatsCodec.write(stats, w);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("[ironfarm] failed to save statistics to {}: {}", p, e.toString());
            return false;
        }
    }
}
