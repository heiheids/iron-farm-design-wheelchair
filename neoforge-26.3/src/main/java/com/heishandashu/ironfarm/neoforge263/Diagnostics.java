package com.heishandashu.ironfarm.neoforge263;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 诊断日志。
 *
 * <p><b>为什么需要它</b>：本机跑不起客户端，我无法自己确认
 * "头顶图标到底画了没有"。反复读代码只能验证"逻辑对"，验证不了"真的画出来了"。</p>
 *
 * <p>所以加一组受开关控制的日志，让玩家跑一次就能定位问题在哪一层：</p>
 * <ol>
 *   <li><b>数据层</b>：扫描到几只村民？数据是否完整（单机 vs 多人）？</li>
 *   <li><b>筛选层</b>：这一帧要画几个图标？被距离筛掉多少？</li>
 *   <li><b>渲染层</b>：渲染回调真的被调用了吗？consumers 是 null 吗？</li>
 * </ol>
 *
 * <p>任何一层为 0，问题就定位到那一层。</p>
 *
 * <p>启用方式：配置文件里 {@code debugLog=true}，或者游戏里用
 * {@code /ironfarm debug}（如果注册了命令）。默认关闭，不污染正常日志。</p>
 */
public final class Diagnostics {

    private static final Logger LOG = LoggerFactory.getLogger("ironfarm");

    /** 是否启用。由设置里的 debugLog 驱动，避免每分钟刷屏。 */
    private static volatile boolean enabled = false;

    /** 节流：每 N tick 最多打一条同类日志。 */
    private static final int LOG_INTERVAL_TICKS = 200;   // 10 秒
    private static long lastScanLog;
    private static long lastRenderLog;

    private static int renderCallbackCount;
    private static int renderNullConsumersCount;
    private static int renderEmptyListCount;
    private static int renderDrewCount;

    private Diagnostics() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            LOG.info("[ironfarm] diagnostics enabled");
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ==================================================================
    // 数据层
    // ==================================================================

    /**
     * 记录一次扫描结果。
     *
     * @param villagers  扫到的村民数
     * @param reports    生成的报告数
     * @param singleplayer 是否是单机（能拿到真实 Brain 数据）
     * @param complete   数据是否完整（单机时应该为 true）
     */
    public static void onScan(long now, int villagers, int reports,
                              boolean singleplayer, int completeCount) {
        if (!enabled) {
            return;
        }
        if (now - lastScanLog < LOG_INTERVAL_TICKS) {
            return;
        }
        lastScanLog = now;
        LOG.info("[ironfarm] SCAN villagers={} reports={} singleplayer={} dataComplete={}/{}",
                villagers, reports, singleplayer, completeCount, reports);
        if (villagers == 0) {
            LOG.info("[ironfarm]   -> 扫描半径内没有村民。检查 SCAN_RADIUS，"
                    + "或确认附近真的有村民。");
        } else if (completeCount == 0 && singleplayer) {
            LOG.warn("[ironfarm]   -> 单机却拿不到 Brain 数据（sleepDataKnown=false）。"
                    + "SingleplayerBridge 可能没生效，图标会全是灰色问号。");
        }
    }

    // ==================================================================
    // 渲染层
    // ==================================================================

    /** 渲染回调被调用了。 */
    public static void onRenderCallback(long now) {
        if (!enabled) {
            return;
        }
        renderCallbackCount++;
        if (now - lastRenderLog >= LOG_INTERVAL_TICKS) {
            flushRenderStats(now);
        }
    }

    /** consumers() 为 null —— 事件挂错了。 */
    public static void onRenderNullConsumers() {
        if (enabled) {
            renderNullConsumersCount++;
        }
    }

    /** 筛选后没有要画的图标。 */
    public static void onRenderEmptyList(int totalReports) {
        if (!enabled) {
            return;
        }
        renderEmptyListCount++;
        // 只在第一次记录，避免刷屏
        if (renderEmptyListCount == 1) {
            LOG.info("[ironfarm] RENDER 筛选后为空（reports={}）。"
                    + "可能原因：村民超出 overlayMaxDistance，或 reports 本身是空的。",
                    totalReports);
        }
    }

    /** 真的画了 N 个图标。 */
    public static void onRenderDrew(int count) {
        if (enabled) {
            renderDrewCount += count;
        }
    }

    private static void flushRenderStats(long now) {
        lastRenderLog = now;
        LOG.info("[ironfarm] RENDER callbacks={} nullConsumers={} emptyList={} iconsDrawn={}",
                renderCallbackCount, renderNullConsumersCount, renderEmptyListCount, renderDrewCount);
        if (renderCallbackCount == 0) {
            LOG.warn("[ironfarm]   -> 渲染回调从未被调用。事件挂载点错了？");
        } else if (renderNullConsumersCount == renderCallbackCount) {
            LOG.warn("[ironfarm]   -> consumers() 每次都是 null。"
                    + "事件挂载点必须能拿到 MultiBufferSource（AFTER_ENTITIES 之后、BEFORE_DEBUG_RENDER 之前）。");
        }
    }

    /** 手动打一条（用于命令）。 */
    public static void dump() {
        LOG.info("[ironfarm] === 诊断汇总 ===");
        LOG.info("[ironfarm] render callbacks={} nullConsumers={} emptyList={} iconsDrawn={}",
                renderCallbackCount, renderNullConsumersCount, renderEmptyListCount, renderDrewCount);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LOG.info("[ironfarm] 不在世界里");
            return;
        }
        IronFarmClientState s = IronFarmClientState.get();
        LOG.info("[ironfarm] villagers={} reports={} singleplayer={}",
                s.villagers().size(), s.reports().size(),
                mc.getSingleplayerServer() != null);
    }
}
