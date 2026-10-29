package com.heishandashu.ironfarm.fabric.gui;

import com.heishandashu.ironfarm.engine.config.ModSettings;
import com.heishandashu.ironfarm.engine.ui.UiModel;
import com.heishandashu.ironfarm.fabric.IronFarmClientState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 设置界面。三个标签：开关 / 统计 / 关于。
 *
 * <p>按已确认的方案：宽 400、高 300，原版风格的按钮，不引第三方 UI 库。</p>
 *
 * <p>三个标签用同一个 Screen 内的枚举切换，不新开窗口 ——
 * 新开窗口会让"返回"行为变得难以预测（到底返回哪一层）。</p>
 */
public final class SettingsScreen extends Screen {

    /**
     * 面板首选尺寸。
     *
     * <p>⚠ 这两个值只是"理想值"，实际用的是 {@link #panelWidth()} / {@link #panelHeight()}。
     * 第一版我直接把 400×300 当实际尺寸用，结果在 1280×720 窗口 + GUI scale 4 时
     * （GUI 只剩 320×180）面板**比屏幕还大**：
     * 标签栏被顶到屏幕外、开关按钮被左右裁掉一半、
     * 底部"关闭"按钮完全看不见 —— 用户截图就是这个现象。</p>
     */
    private static final int PREFERRED_WIDTH = 400;
    private static final int PREFERRED_HEIGHT = 300;
    /** 面板四周至少留出的边距，别贴着屏幕边缘。 */
    private static final int SCREEN_MARGIN = 8;
    private static final int TAB_HEIGHT = 20;
    private static final int ROW_HEIGHT = 22;
    private static final int PADDING = 10;

    /**
     * 实际宽高：取"首选值"与"屏幕可用空间"的较小者。
     *
     * <p>用 setScreen 时算一次即可，但窗口可能在打开期间被 resize，
     * 所以做成方法每次现算，render 里也用同一套值，避免控件与背景不一致。</p>
     */
    private int panelWidth() {
        return Math.min(PREFERRED_WIDTH, Math.max(160, this.width - SCREEN_MARGIN * 2));
    }

    private int panelHeight() {
        return Math.min(PREFERRED_HEIGHT, Math.max(120, this.height - SCREEN_MARGIN * 2));
    }

    /** 当前标签页。 */
    private enum Tab {
        SWITCHES("ironfarm.gui.tab.switches"),
        STATS("ironfarm.gui.tab.stats"),
        ABOUT("ironfarm.gui.tab.about");

        final String langKey;

        Tab(String langKey) {
            this.langKey = langKey;
        }
    }

    private Tab tab = Tab.SWITCHES;

    private int left;
    private int top;
    private int listScroll = 0;
    /** 统计页内容的实际高度，用于滚动条与滚动上限。 */
    private int statsContentHeight = 0;
    /** 关于页的滚动偏移。 */
    private int aboutScroll = 0;
    /** 关于页内容的实际高度。 */
    private int aboutContentHeight = 0;

    private final Screen parent;

    public SettingsScreen(Screen parent) {
        super(tr("ironfarm.gui.settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.left = (this.width - panelWidth()) / 2;
        this.top = (this.height - panelHeight()) / 2;
        rebuild();
    }

    /** 清空并重建当前标签页的所有控件。 */
    private void rebuild() {
        this.clearWidgets();

        // 三个标签按钮
        int tabW = (panelWidth() - PADDING * 2 - 8) / 3;
        int ty = top + 6;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            Button b = Button.builder(
                            tr(t.langKey),
                            btn -> {
                                this.tab = t;
                                this.listScroll = 0;
                                rebuild();
                            })
                    .bounds(left + PADDING + i * (tabW + 4), ty, tabW, 16)
                    .build();
            b.active = (t != this.tab);
            this.addRenderableWidget(b);
        }

        // 底部关闭
        this.addRenderableWidget(Button.builder(
                        tr("ironfarm.gui.close"),
                        b -> this.onClose())
                .bounds(left + panelWidth() - 66, top + panelHeight() - 24, 56, 16)
                .build());

        switch (tab) {
            case SWITCHES:
                buildSwitchTab();
                break;
            case STATS:
                // 打开统计页时立即落盘一次，缩小"崩溃丢数据"的窗口
                IronFarmClientState.get().saveStatsNow();
                buildStatsTab();
                break;
            case ABOUT:
                buildAboutTab();
                break;
            default:
                break;
        }
    }

    // ==================================================================
    // 开关标签
    // ==================================================================

    private void buildSwitchTab() {
        IronFarmClientState state = IronFarmClientState.get();
        ModSettings s = state.settings();

        int y = top + TAB_HEIGHT + 10;
        int x = left + PADDING;
        int w = panelWidth() - PADDING * 2;
        int halfW = (w - 6) / 2;

        // 六个开关，两列布局（每行两个，省高度）
        // 每对传（读、写）两个 lambda —— ModSettings 是唯一真源，没有平行副本。
        y = addToggle(x, y, halfW, "ironfarm.gui.settings.master",
                () -> s.masterEnabled, v -> s.masterEnabled = v);
        addToggle(x + halfW + 6, y - ROW_HEIGHT, halfW,
                "ironfarm.gui.settings.overlay",
                () -> s.villagerOverlayEnabled, v -> s.villagerOverlayEnabled = v);

        y = addToggle(x, y, halfW, "ironfarm.gui.settings.bed_lines",
                () -> s.bedLinesEnabled, v -> s.bedLinesEnabled = v);
        addToggle(x + halfW + 6, y - ROW_HEIGHT, halfW,
                "ironfarm.gui.settings.spawn_box",
                () -> s.spawnBoxEnabled, v -> s.spawnBoxEnabled = v);

        y = addToggle(x, y, halfW, "ironfarm.gui.settings.panic_lines",
                () -> s.panicLinesEnabled, v -> s.panicLinesEnabled = v);
        addToggle(x + halfW + 6, y - ROW_HEIGHT, halfW,
                "ironfarm.gui.settings.golem_box",
                () -> s.golemDetectBoxEnabled, v -> s.golemDetectBoxEnabled = v);

        // 两个「功能开关」，并排一行。
        // 与上面的「显示开关」区分：这两个会改变模组的行为，
        // 而不只是画不画东西。
        y = addToggle(x, y, halfW, "ironfarm.gui.settings.wool_hopper",
                () -> s.woolHopperEnabled, v -> s.woolHopperEnabled = v);
        addToggle(x + halfW + 6, y - ROW_HEIGHT, halfW,
                "ironfarm.gui.settings.right_click",
                () -> s.rightClickVillagerEnabled,
                v -> s.rightClickVillagerEnabled = v);

        // 语言切换（单独一行）
        y += 6;
        this.addRenderableWidget(Button.builder(
                        tr("ironfarm.gui.settings.language")
                                .append(": ")
                                .append(tr(langLabelKey(s.locale))),
                        b -> {
                            // 循环切换：auto → zh_cn → en_us → auto
                            String next;
                            if ("auto".equals(s.locale)) {
                                next = "zh_cn";
                            } else if ("zh_cn".equals(s.locale)) {
                                next = "en_us";
                            } else {
                                next = "auto";
                            }
                            state.setLocale(next);
                            rebuild();
                        })
                .bounds(x, y, w, 18)
                .build());
    }

    private static String langLabelKey(String locale) {
        if ("auto".equals(locale)) {
            return "ironfarm.gui.settings.language.auto";
        }
        return "ironfarm.gui.lang." + locale;
    }

    /**
     * 加一个开关按钮。
     *
     * <p><b>为什么不用"按按钮对象记状态"</b>：第一版我写了一个静态
     * {@code IdentityHashMap<Button, Boolean>} 来记每个按钮的开关值。
     * 那有三个问题：</p>
     * <ol>
     *   <li><b>内存泄漏</b>：静态 Map 里持有 Button，而按钮随每次
     *       {@code rebuild()} 重新创建、旧按钮既不回收也不移除，
     *       玩家来回点几次标签页就攒一堆；</li>
     *   <li><b>状态有两个来源</b>：Map 里一份、{@code ModSettings} 里一份，
     *       两者不一致时界面显示的可能不是真实的设置值；</li>
     *   <li><b>热重载会错</b>：手改配置文件后用命令重载，
     *       界面上的按钮还是旧值。</li>
     * </ol>
     *
     * <p>现在改成：<b>{@link ModSettings} 是唯一真源</b>。
     * 按钮的显示文本每次点击后从 setter 重新读一次当前值生成，
     * 不额外存任何副本。</p>
     *
     * @param getter 从设置里读当前值（点完之后用来刷新显示）
     * @param setter 把新值写回设置
     * @return 下一行的 y
     */
    private int addToggle(int x, int y, int w, String labelKey,
                          java.util.function.BooleanSupplier getter,
                          java.util.function.Consumer<Boolean> setter) {
        Button b = Button.builder(toggleText(labelKey, getter.getAsBoolean()), btn -> {
            boolean now = !getter.getAsBoolean();   // 翻转的是设置里的真实值
            setter.accept(now);
            // 立即落盘：玩家改了就该存下来，不依赖"关窗口时才存"
            IronFarmClientState.get().saveSettings();
            IronFarmClientState.get().applySettings();
            // 显示文本重新从设置里生成，不读任何缓存
            btn.setMessage(toggleText(labelKey, getter.getAsBoolean()));
        }).bounds(x, y, w, 18).build();
        this.addRenderableWidget(b);
        return y + ROW_HEIGHT;
    }

    /**
     * 用**模组自己的 Lang** 取文本，而不是 Minecraft 的 Component.translatable。
     *
     * <p>两套语言系统混用会导致"切到英文后一部分文字还是中文" ——
     * Component.translatable 跟游戏语言，Lang 跟模组设置里的语言开关。</p>
     */
    private static net.minecraft.network.chat.MutableComponent tr(String key) {
        return Component.literal(IronFarmClientState.get().lang().get(key));
    }

    /** 带 %s 占位的版本。 */
    private static net.minecraft.network.chat.MutableComponent tr(String key, Object... args) {
        return Component.literal(IronFarmClientState.get().lang().format(key, args));
    }

    private static Component toggleText(String labelKey, boolean on) {
        return tr(labelKey)
                .append(": ")
                .append(tr(
                        on ? "ironfarm.gui.settings.on" : "ironfarm.gui.settings.off"));
    }

    // ==================================================================
    // 统计标签
    // ==================================================================

    /** 「清空统计」是否已经点过一次（等二次确认）。 */
    private boolean resetArmed = false;
    /** 二次确认的倒计时（tick）。超时自动解除，避免一直挂着"确定？"状态。 */
    private int resetArmTicks = 0;

    /**
     * 统计标签的按钮。
     *
     * <p>「清空统计」做二次确认：第一次点变成「确定？」，3 秒内再点才真正清空。
     * 这是布局方案里承诺过的 —— 一次误触丢掉几小时的数据太难受。</p>
     */
    private void buildStatsTab() {
        IronFarmClientState state = IronFarmClientState.get();
        Component label = resetArmed
                ? tr("ironfarm.gui.stats.reset_confirm")
                : tr("ironfarm.gui.stats.reset");

        this.addRenderableWidget(Button.builder(label, b -> {
                    if (!resetArmed) {
                        resetArmed = true;
                        resetArmTicks = 60;   // 3 秒
                        rebuild();
                        return;
                    }
                    // 第二次点击 → 真的清空
                    state.stats().reset();
                    resetArmed = false;
                    resetArmTicks = 0;
                    rebuild();
                })
                .bounds(left + PADDING, top + panelHeight() - 24, 90, 16)
                .build());
        // 列表内容在 render 里画（不是控件）
    }

    // ==================================================================
    // 关于标签
    // ==================================================================

    private void buildAboutTab() {
        // 纯文本，无控件
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    /**
     * 画背景：整屏暗化 + 面板底板 + 标签栏底色。
     *
     * <p><b>为什么底板必须在这里画：</b>{@code Screen.render} 的顺序是
     * 「renderBackground → 画控件」。底板若写在 render() 里（super.render 之后），
     * 那块不透明 fill 会把所有按钮**盖住** ——
     * 用户描述为"所有按钮没了按钮样式"。</p>
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);

        g.fill(left - 1, top - 1, left + panelWidth() + 1, top + panelHeight() + 1, 0xFF000000);
        g.fill(left, top, left + panelWidth(), top + panelHeight(), 0xFF1A1A1E);
        g.fill(left, top, left + panelWidth(), top + TAB_HEIGHT, 0xFF2A2A32);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 顺序（三层）：
        //   ① renderBackground —— 整屏暗化 + 面板底板（见上）
        //   ② super.render(...) —— 画所有按钮，在底板之上、样式完整
        //   ③ 画统计/关于内容（统计页自带 scissor，不会压到按钮）
        super.render(g, mouseX, mouseY, partialTick);

        g.drawCenteredString(this.font, this.title,
                left + panelWidth() / 2, top - 12, 0xFFFFFFFF);

        if (tab == Tab.STATS) {
            renderStats(g);
        } else if (tab == Tab.ABOUT) {
            renderAbout(g);
        }
    }

    private void renderStats(GuiGraphics g) {
        IronFarmClientState state = IronFarmClientState.get();
        UiModel ui = state.ui();
        int x = left + PADDING;
        // ---- 内容区：可滚动 ----
        // 面板高度不够时靠滚轮看，而不是把内容画到面板外。
        final int viewTop = top + TAB_HEIGHT + 8;
        final int limitY = top + panelHeight() - 26;
        final int viewH = limitY - viewTop;
        if (viewH <= 0) {
            return;
        }

        g.enableScissor(left, viewTop, left + panelWidth(), limitY);
        int y = viewTop - listScroll;

        if (!state.statsComplete()) {
            g.drawString(this.font,
                    tr("ironfarm.gui.stats.multiplayer_warning"),
                    x, y, 0xFFFFB020, false);
            y += 12;
        }

        for (UiModel.Row r : ui.statsHeader(state.stats(), state.lastGameTime())) {
            g.drawString(this.font, r.label, x, y, 0xFFB0B0B8, false);
            g.drawString(this.font, r.value, x + 150, y, colorFor(r.state), false);
            y += 11;
        }

        // 0.22.0：按用户反馈去掉「刷新失败」与「按原因统计」两个区块。
        // 理由：单台机器的失败原因高度重复，列表很快就变成同一句话重复十几行，
        // 既占满屏幕又提供不了新信息。真要排查具体某只村民，
        // 右键它看状态页比看这个列表直观得多。
        //
        // 腾出来的空间给"平均离开检测范围用时 / 平均存活时长"这两个
        // 能反映机器效率的指标（在 statsHeader 里）。


        // ---- 实物统计（与上面的数据视觉分隔）----
        //
        // 用户明确要求"注意与上面数据分隔开，这里命名为实物统计"。
        // 它衡量的东西完全不同：上面是"刷出来多少铁"，
        // 这里是"漏斗销毁掉多少别的东西"。混在一起会让人误以为
        // 销毁的物品也算产量。
        y += 8;
        g.fill(x, y, left + panelWidth() - PADDING, y + 1, 0xFF4A4A55);
        y += 6;
        g.drawString(this.font,
                tr("ironfarm.gui.items.section"),
                x, y, 0xFFFFD24A, false);
        y += 13;

        for (UiModel.Row r : ui.itemStats(state.stats(), state.lastGameTime())) {
            // 小标题/分隔行没有 value，单独画成偏暗的子标题
            if (r.value == null || r.value.isEmpty()) {
                g.drawString(this.font, r.label, x, y, 0xFF8A8A96, false);
                y += 11;
                continue;
            }
            g.drawString(this.font, r.label, x, y, 0xFFB0B0B8, false);
            g.drawString(this.font, r.value, x + 150, y, 0xFFC0C0C8, false);
            y += 11;
        }

        // 记录内容总高，供滚动使用
        statsContentHeight = (y + listScroll) - viewTop;

        g.disableScissor();

        // 滚动条：与关于页共用同一个实现，保证观感一致
        drawScrollbar(g, viewTop, limitY, viewH, statsContentHeight, listScroll);
    }

    /**
     * 关于页：作者信息 + 《铁傀儡刷新条件-详解》全文。
     *
     * <p>文档来自 jar 内的 {@code assets/ironfarm/conditions/*.txt}，
     * 不是硬编码在语言文件里 —— 完整理由见 {@link ConditionsDoc}。</p>
     *
     * <p>整页可滚动。文档 470+ 行，按原样逐行绘制，
     * 保留它的缩进与空行（排版本身就是信息的一部分）。</p>
     */
    private void renderAbout(GuiGraphics g) {
        IronFarmClientState state = IronFarmClientState.get();
        int x = left + PADDING;

        final int viewTop = top + TAB_HEIGHT + 8;
        final int limitY = top + panelHeight() - 26;
        final int viewH = limitY - viewTop;
        if (viewH <= 0) {
            return;
        }

        g.enableScissor(left, viewTop, left + panelWidth(), limitY);
        int y = viewTop - aboutScroll;

        // ---- 作者信息 ----
        g.drawString(this.font, tr("ironfarm.name"), x, y, 0xFFFFFFFF, false);
        y += 13;
        g.drawString(this.font, tr("ironfarm.author"), x, y, 0xFFB0B0B8, false);
        y += 11;
        g.drawString(this.font, tr("ironfarm.homepage"), x, y, 0xFF8AB4F8, false);
        y += 14;
        for (String line : wrap("ironfarm.about.line1", panelWidth() - PADDING * 2)) {
            g.drawString(this.font, line, x, y, 0xFFB0B0B8, false);
            y += 10;
        }
        for (String line : wrap("ironfarm.about.line2", panelWidth() - PADDING * 2)) {
            g.drawString(this.font, line, x, y, 0xFFB0B0B8, false);
            y += 10;
        }
        y += 4;
        g.drawString(this.font,
                tr("ironfarm.about.version", state.settings().locale),
                x, y, 0xFF6A6A78, false);
        y += 18;

        // ---- 文档全文 ----
        java.util.List<String> doc = ConditionsDoc.lines(currentDocLang());
        if (doc == null) {
            // 兜底：文档读不到时给一句提示，不要让页面一片空白
            for (String line : wrap("ironfarm.about.doc_missing", panelWidth() - PADDING * 2)) {
                g.drawString(this.font, line, x, y, 0xFFE5484D, false);
                y += 10;
            }
        } else {
            int maxWidth = panelWidth() - PADDING * 2;
            for (String raw : doc) {
                // 空行只占半个行距，避免文档显得松散
                if (raw.trim().isEmpty()) {
                    y += 5;
                    continue;
                }
                int color = docLineColor(raw);
                for (String line : wrapPlain(raw, maxWidth)) {
                    g.drawString(this.font, line, x, y, color, false);
                    y += 9;
                }
            }
        }

        aboutContentHeight = (y + aboutScroll) - viewTop;
        g.disableScissor();

        // 滚动条
        //
        // ⚠ 第一版只有 3 像素宽、透明度又低，用户完全没注意到有滚动条
        //   （反馈："关于里加滚动条"——其实已经画了，但看不见）。
        //   现在加宽到 6 像素、底色和滑块都提高对比度。
        drawScrollbar(g, viewTop, limitY, viewH, aboutContentHeight, aboutScroll);
    }

    /**
     * 画一条竖向滚动条。
     *
     * <p>宽度 6 像素、带深色轨道和亮色滑块 —— 3 像素太细，
     * 在游戏里几乎看不出来（这是我上一版的失误）。</p>
     */
    private void drawScrollbar(GuiGraphics g, int viewTop, int limitY,
                               int viewH, int contentHeight, int scroll) {
        if (contentHeight <= viewH) {
            return;
        }
        int barW = 6;
        int barX = left + panelWidth() - barW - 2;
        // 轨道
        g.fill(barX, viewTop, barX + barW, limitY, 0xFF101014);
        g.fill(barX + 1, viewTop + 1, barX + barW - 1, limitY - 1, 0xFF3A3A44);
        // 滑块
        int thumbH = Math.max(12, viewH * viewH / Math.max(1, contentHeight));
        int maxScroll = Math.max(1, contentHeight - viewH);
        int thumbY = viewTop + (viewH - thumbH) * scroll / maxScroll;
        g.fill(barX + 1, thumbY, barX + barW - 1, thumbY + thumbH, 0xFFB0B0C0);
    }

    /**
     * 文档行的配色：用不同颜色区分层级，纯文本也能看出结构。
     *
     * <p>规则是从文档自身的排版约定来的（等号分隔线、章节标题、警告标记）。</p>
     */
    private static int docLineColor(String raw) {
        String t = raw.trim();
        // 分隔线
        if (t.startsWith("====") || t.startsWith("----")) {
            return 0xFF4A4A55;
        }
        // 章节标题：一、二、… 或 "1. Overview"
        if (t.matches("^[一二三四五六七八九十]+、.*")
                || t.matches("^\\d+\\.\\s.*")
                || (t.endsWith("====") == false && t.startsWith("第") && t.contains("章"))) {
            return 0xFFFFD24A;
        }
        // 警告
        if (t.startsWith("⚠") || t.startsWith("WARNING") || t.startsWith("NOTE")) {
            return 0xFFFFA030;
        }
        // 小标题（以【或 [ 开头）
        if (t.startsWith("【") || t.startsWith("[")) {
            return 0xFF8AB4F8;
        }
        return 0xFFC0C0C8;
    }

    /** 当前文档语言：跟随设置里的 locale，auto 时按游戏语言。 */
    private String currentDocLang() {
        String locale = IronFarmClientState.get().settings().locale;
        if ("zh_cn".equals(locale)) {
            return "zh_cn";
        }
        if ("en_us".equals(locale)) {
            return "en_us";
        }
        // auto：看游戏语言
        String game = net.minecraft.client.Minecraft.getInstance()
                .getLanguageManager().getSelected();
        return game != null && game.startsWith("zh") ? "zh_cn" : "en_us";
    }

    /**
     * 把一段纯文本按宽度折行。
     *
     * <p>与 {@link #wrap(String, int)} 的区别：那个从语言文件取文本并处理
     * {@code \n}，这个直接处理传入的字符串。</p>
     *
     * <p>保留行首缩进 —— 文档里的缩进是有意义的（代码块、层级）。</p>
     */
    private java.util.List<String> wrapPlain(String text, int width) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text.isEmpty()) {
            out.add("");
            return out;
        }
        // 行首缩进（空格）原样保留
        int indent = 0;
        while (indent < text.length() && text.charAt(indent) == ' ') {
            indent++;
        }
        String prefix = text.substring(0, indent);

        for (net.minecraft.util.FormattedCharSequence seq
                : this.font.split(Component.literal(text), width)) {
            StringBuilder sb = new StringBuilder();
            seq.accept((idx, style, cp) -> {
                sb.appendCodePoint(cp);
                return true;
            });
            out.add(sb.toString());
        }
        if (out.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    /**
     * 把一条语言文本按宽度切成多行。
     *
     * <p>用 {@code font.split} 而不是自己数字符 —— Minecraft 的字体是变宽的，
     * 中英文混排按字符数切会得到参差不齐的结果。</p>
     *
     * <p>文本里用 {@code \n} 表示强制换行（空行用来分段）。</p>
     */
    private java.util.List<String> wrap(String langKey, int width) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String raw = tr(langKey).getString();
        for (String para : raw.split("\n", -1)) {
            if (para.isEmpty()) {
                out.add("");
                continue;
            }
            for (net.minecraft.util.FormattedCharSequence seq
                    : this.font.split(Component.literal(para), width)) {
                StringBuilder sb = new StringBuilder();
                seq.accept((idx, style, cp) -> {
                    sb.appendCodePoint(cp);
                    return true;
                });
                out.add(sb.toString());
            }
        }
        return out;
    }

    private static int clampScroll(int value, int max) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, max);
    }

    private static int colorFor(UiModel.RowState state) {
        switch (state) {
            case OK:
                return 0xFF4CD964;
            case BAD:
                return 0xFFE5484D;
            case UNKNOWN:
                return 0xFFA0A0A8;
            default:
                return 0xFFE0E0E6;
        }
    }

    /**
     * 滚轮滚动。
     *
     * <p>统计页和关于页都可能超出面板高度（GUI scale 3 的 720p 下
     * 内容区只有约 200px），所以两个页面都要能滚。</p>
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double deltaX, double deltaY) {
        int viewTop = top + TAB_HEIGHT + 8;
        int limitY = top + panelHeight() - 26;
        int viewH = limitY - viewTop;

        if (tab == Tab.STATS) {
            int maxScroll = Math.max(0, statsContentHeight - viewH);
            if (maxScroll > 0) {
                listScroll = clampScroll(listScroll - (int) (deltaY * 22), maxScroll);
                return true;
            }
        } else if (tab == Tab.ABOUT) {
            int maxScroll = Math.max(0, aboutContentHeight - viewH);
            if (maxScroll > 0) {
                aboutScroll = clampScroll(aboutScroll - (int) (deltaY * 22), maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void tick() {
        super.tick();
        // 二次确认超时自动解除，避免按钮一直停在"确定？"
        if (resetArmed) {
            if (--resetArmTicks <= 0) {
                resetArmed = false;
                if (tab == Tab.STATS) {
                    rebuild();
                }
            }
        }
    }

    @Override
    public void onClose() {
        IronFarmClientState.get().saveSettings();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
