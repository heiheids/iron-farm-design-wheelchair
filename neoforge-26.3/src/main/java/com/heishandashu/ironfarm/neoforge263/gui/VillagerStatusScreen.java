package com.heishandashu.ironfarm.neoforge263.gui;

import com.heishandashu.ironfarm.engine.eval.VillagerReport;
import com.heishandashu.ironfarm.engine.ui.UiModel;
import com.heishandashu.ironfarm.neoforge263.IronFarmClientState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 村民状态页。
 *
 * <p><b>内容全部来自 {@link UiModel}</b>，本类只负责"把 Row 画到屏幕上"：
 * 决定行高、分栏、滚动、悬停提示。内容逻辑一行都不在这里 ——
 * 那样才能在没游戏的环境里测（见 UiModelTest 的 15 条断言）。</p>
 *
 * <p>布局按已确认的方案：宽 320、竖排列表、五个分组、超出可滚动。</p>
 */
public final class VillagerStatusScreen extends Screen {

    /**
     * 面板首选尺寸。
     *
     * <p>⚠ 实际用的是 {@link #panelWidth()} / {@link #panelHeight()}。
     * 第一版把 320×240 当实际尺寸，在 GUI scale 3 的 720p 下
     * （垂直只有 240 GUI 像素）内容区只剩约 198px，
     * 状态页和统计页都会把内容挤到面板外 —— 用户截图确认。</p>
     */
    private static final int PREFERRED_WIDTH = 340;
    private static final int PREFERRED_HEIGHT = 250;
    private static final int SCREEN_MARGIN = 8;
    private static final int HEADER_HEIGHT = 16;
    private static final int SECTION_GAP = 6;
    private static final int ROW_HEIGHT = 11;
    private static final int PADDING = 8;
    /** 右侧数值区的宽度比例，超过这个宽度的文字会被截断。 */
    private static final int VALUE_COLUMN = 120;

    private final int entityId;
    private final Screen parent;

    /** 面板实际尺寸：取首选值与屏幕可用空间的较小者。 */
    private int panelWidth() {
        return Math.min(PREFERRED_WIDTH, Math.max(180, this.width - SCREEN_MARGIN * 2));
    }

    private int panelHeight() {
        return Math.min(PREFERRED_HEIGHT, Math.max(120, this.height - SCREEN_MARGIN * 2));
    }

    private int left;
    private int top;
    private int scroll = 0;
    private int contentHeight = 0;

    /** 已渲染的行，用于悬停提示命中测试。 */
    private final List<HitRow> hitRows = new ArrayList<HitRow>();

    /** 一行在屏幕上的位置与它的提示文本。 */
    private static final class HitRow {
        final int x, y, w, h;
        final String tooltip;

        HitRow(int x, int y, int w, int h, String tooltip) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.tooltip = tooltip;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public VillagerStatusScreen(Screen parent, int entityId) {
        super(Component.translatable("ironfarm.gui.villager_status"));
        this.parent = parent;
        this.entityId = entityId;
    }

    @Override
    protected void init() {
        this.left = (this.width - panelWidth()) / 2;
        this.top = (this.height - panelHeight()) / 2;

        this.addRenderableWidget(Button.builder(
                        Component.translatable("ironfarm.gui.close"),
                        b -> this.onClose())
                .bounds(this.left + panelWidth() - 56, this.top + panelHeight() - 24, 48, 16)
                .build());
    }

    /**
     * 画背景：整屏暗化 + 面板底板 + 标题栏。
     *
     * <p><b>为什么面板底板要放在这里，而不是 render() 里：</b></p>
     *
     * <p>{@code Screen.render(...)} 的内部顺序是
     * 「{@code renderBackground} → 遍历 renderables 画控件」。
     * 如果我把面板底板写在 {@code render()} 里（super.render 之后），
     * 那块不透明的 fill 会**盖住刚画好的所有按钮** ——
     * 用户描述为"所有按钮没了按钮样式"。</p>
     *
     * <p>把底板放进 {@code renderBackground}，它就会在控件之前画好，
     * 按钮自然浮在底板上面，样式完整。</p>
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 先让原版画整屏的半透明黑（否则世界画面会透出来，看不清）
        super.extractBackground(g, mouseX, mouseY, partialTick);

        // 面板底板
        g.fill(left - 1, top - 1, left + panelWidth() + 1, top + panelHeight() + 1, 0xFF000000);
        g.fill(left, top, left + panelWidth(), top + panelHeight(), 0xFF1A1A1E);
        // 标题栏
        g.fill(left, top, left + panelWidth(), top + HEADER_HEIGHT, 0xFF2A2A32);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 顺序（三层，缺一不可）：
        //   ① renderBackground —— 整屏暗化 + 面板底板（见上面的方法）
        //   ② super.render(...) —— 画所有控件（"关闭"按钮），在底板之上
        //   ③ 画内容 —— 用 scissor 限制在内容区，不越过按钮
        //   ④ tooltip —— 最上层
        //
        // 之前踩过的两个坑：
        //   - 末尾再调一次 super.render → 背景重画，盖住内容（"被遮蔽"）
        //   - 面板底板写在超级调用之后 → 盖住按钮（"没有按钮样式"）
        VillagerReport report = IronFarmClientState.get().reportOf(entityId);

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        if (report == null) {
            g.centeredText(this.font,
                    Component.translatable("ironfarm.gui.villager_gone"),
                    this.width / 2, this.height / 2, 0xFFFF6060);
            return;
        }

        UiModel ui = IronFarmClientState.get().ui();
        List<UiModel.Section> sections = ui.villagerStatus(report);

        // 面板底
        // ⚠ 这里**不能**再画面板底板：它会盖住 super.render 刚画好的按钮。
        //    底板已在 renderBackground 里画好了（在控件之前）。
        hitRows.clear();

        // 标题文字（标题栏底色也在 renderBackground 里）
        g.text(this.font, this.title, left + PADDING, top + 4, 0xFFFFFFFF, false);

        // 内容区（裁剪，避免滚出面板）
        g.enableScissor(left, top + HEADER_HEIGHT,
                left + panelWidth(), top + panelHeight() - 26);

        int x = left + PADDING;
        int y = top + HEADER_HEIGHT + 4 - scroll;
        int innerW = panelWidth() - PADDING * 2;

        for (UiModel.Section s : sections) {
            if (!s.title.isEmpty()) {
                g.text(this.font, s.title, x, y, 0xFF8AB4F8, false);
                y += ROW_HEIGHT;
            }
            for (UiModel.Row r : s.rows) {
                if (y + ROW_HEIGHT > top + HEADER_HEIGHT - ROW_HEIGHT
                        && y < top + panelHeight()) {
                    drawRow(g, x, y, innerW, r);
                    hitRows.add(new HitRow(x, y, innerW, ROW_HEIGHT, r.tooltip));
                }
                y += ROW_HEIGHT;
            }
            y += SECTION_GAP;
        }
        contentHeight = y + scroll - (top + HEADER_HEIGHT + 4);

        g.disableScissor();

        // 滚动条（内容超出时才画）
        int visibleH = panelHeight() - HEADER_HEIGHT - 26;
        if (contentHeight > visibleH) {
            renderScrollbar(g, visibleH);
        }

        // 悬停提示（最上层）
        for (HitRow hr : hitRows) {
            if (hr.tooltip != null && hr.contains(mouseX, mouseY)) {
                List<FormattedCharSequence> lines =
                        this.font.split(Component.literal(hr.tooltip), 200);
                // 26.3 的 tooltip 不在绘制路径上画，而是"登记给下一帧"：
                // 这是 extract 模型的一部分（先描述，后渲染）。
                g.setTooltipForNextFrame(this.font, lines, mouseX, mouseY);
                break;
            }
        }
        // ⚠ 这里不能再调 super.render(...)：
        //    它在方法开头已经调用过了（背景 + 按钮）。
        //    再调一次会把 renderBackground 重画一遍，盖住刚画好的所有内容。
    }

    private void drawRow(GuiGraphicsExtractor g, int x, int y, int w, UiModel.Row r) {
        int color = colorFor(r.state);
        g.text(this.font, r.label, x, y, 0xFFB0B0B8, false);

        // 值右对齐，超宽则截断加省略号
        String value = r.value;
        int maxW = VALUE_COLUMN;
        if (this.font.width(value) > maxW) {
            while (value.length() > 1 && this.font.width(value + "...") > maxW) {
                value = value.substring(0, value.length() - 1);
            }
            value = value + "...";
        }
        int vx = x + w - this.font.width(value);
        g.text(this.font, value, vx, y, color, false);
    }

    private void renderScrollbar(GuiGraphicsExtractor g, int visibleH) {
        int barX = left + panelWidth() - 4;
        int barTop = top + HEADER_HEIGHT;
        int barH = visibleH;
        int thumbH = Math.max(12, barH * visibleH / Math.max(1, contentHeight));
        int maxScroll = Math.max(1, contentHeight - visibleH);
        int thumbY = barTop + (barH - thumbH) * scroll / maxScroll;

        g.fill(barX, barTop, barX + 3, barTop + barH, 0xFF2A2A32);
        g.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xFF6A6A78);
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int visibleH = panelHeight() - HEADER_HEIGHT - 26;
        int maxScroll = Math.max(0, contentHeight - visibleH);
        if (maxScroll <= 0) {
            return false;
        }
        // 向上滚（deltaY > 0）= 看更上面的内容
        scroll -= (int) (deltaY * ROW_HEIGHT * 2);
        if (scroll < 0) {
            scroll = 0;
        }
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
