package com.zephyr.music.client.gui;

import com.zephyr.music.client.glass.GlassRenderer;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;

/**
 * 现代化 UI 渲染工具 v2 —— 毛玻璃设计系统
 *
 * - SDF 抗锯齿圆角（GlassRenderer）
 * - RenderTarget 一次性背景模糊（BlurManager）
 * - 统一的设计 token：面板底色 / 强调色 / 文字层级
 */
public class ModernUI
{
    // ================= 设计 tokens（毛玻璃主题） =================
    /** 面板玻璃底色（深色玻璃，alpha 由浓度控制） */
    public static final int GLASS_DARK = 0xB4141820;
    /** 面板玻璃底色（浅一层，用于内嵌卡片） */
    public static final int GLASS_CARD = 0x8C1A1F2A;
    /** 面板玻璃底色（悬浮 HUD） */
    public static final int GLASS_HUD = 0x9910141C;
    /** 1px 亮边 */
    public static final int BORDER_LIGHT = 0x50FFFFFF;
    /** 文字层级 */
    public static final int TEXT_PRIMARY = 0xFFF2F5FA;
    public static final int TEXT_SECONDARY = 0xFFA8B3C4;
    public static final int TEXT_DIM = 0xFF6B7484;
    public static final int TEXT_ACCENT = 0xFF4FC3F7;
    public static final int TEXT_SUCCESS = 0xFF5AD492;
    public static final int TEXT_DANGER = 0xFFFF6666;

    /** 控件悬停底色 */
    public static final int HOVER_FILL = 0x28FFFFFF;
    /** 控件按下底色 */
    public static final int ACTIVE_FILL = 0x40FFFFFF;

    // ================= 毛玻璃面板 =================

    /**
     * 绘制毛玻璃面板：模糊背景（若有）+ SDF 圆角玻璃 + 描边。
     * 调用前需在本帧渲染开头调用过 BlurManager.runPending()。
     */
    public static void glassPanel(GuiGraphics g, int x, int y, int w, int h, float radius, int tint, float sheen, boolean blurBehind)
    {
        GlassRenderer.glassPanel(g, x, y, w, h, radius, tint, sheen, blurBehind);
    }

    /** 标准内容面板（毛玻璃） */
    public static void glassPanel(GuiGraphics g, int x, int y, int w, int h)
    {
        glassPanel(g, x, y, w, h, 10f, GLASS_DARK, 0.55f, true);
    }

    /** 内嵌卡片（弱一层的玻璃） */
    public static void glassCard(GuiGraphics g, int x, int y, int w, int h)
    {
        glassPanel(g, x, y, w, h, 8f, GLASS_CARD, 0.35f, false);
    }

    /** 悬浮 HUD 玻璃卡 */
    public static void glassHudPanel(GuiGraphics g, int x, int y, int w, int h)
    {
        glassPanel(g, x, y, w, h, 12f, GLASS_HUD, 0.6f, true);
    }

    // ================= 基础绘制 =================

    /** SDF 抗锯齿圆角矩形（实心） */
    public static void fillRound(GuiGraphics g, int x, int y, int w, int h, int radius, int color)
    {
        if (GlassRenderer.shadersReady())
        {
            GlassRenderer.drawSdfRoundRect(g, x, y, w, h, radius, color, 0, 0f);
        }
        else
        {
            fallbackFillRound(g, x, y, w, h, radius, color);
        }
    }

    /** SDF 抗锯齿圆角描边 */
    public static void strokeRound(GuiGraphics g, int x, int y, int w, int h, int radius, int color, int thickness)
    {
        if (GlassRenderer.shadersReady())
        {
            GlassRenderer.drawSdfRoundRect(g, x, y, w, h, radius, 0, color, 0f);
        }
        else
        {
            fallbackStrokeRound(g, x, y, w, h, radius, color, thickness);
        }
    }

    /** 兼容旧接口：绘制卡片 */
    public static void drawCard(GuiGraphics g, int x, int y, int w, int h)
    {
        drawCard(g, x, y, w, h, ZephyrConfig.HUD_BG_OPACITY.get(), true);
    }

    public static void drawCard(GuiGraphics g, int x, int y, int w, int h, double alpha, boolean withBorder)
    {
        int bg = ZephyrConfig.getBgColor(alpha);
        fillRound(g, x, y, w, h, 8, bg);
        if (withBorder)
        {
            strokeRound(g, x, y, w, h, 8, 0x40FFFFFF, 1);
        }
    }

    // ================= 控件 =================

    /** 圆角按钮（normal/hover/active 三态） */
    public static void drawAccentButton(GuiGraphics g, int x, int y, int w, int h, boolean hovered, boolean active)
    {
        drawAccentButton(g, x, y, w, h, hovered, active, 6);
    }

    public static void drawAccentButton(GuiGraphics g, int x, int y, int w, int h, boolean hovered, boolean active, int radius)
    {
        int color;
        if (active) color = withAlpha(ZephyrConfig.getAccentColor(), 255);
        else if (hovered) color = 0xFF2E3E52;
        else color = 0xFF232936;
        fillRound(g, x, y, w, h, radius, color);
        strokeRound(g, x, y, w, h, radius, hovered ? 0x80FFFFFF : 0x30FFFFFF, 1);
    }

    /** 圆角进度条（填充 + 圆头滑块） */
    public static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, double progress)
    {
        progress = Math.max(0, Math.min(1, progress));
        fillRound(g, x, y, w, h, h / 2, 0xFF2A3140);
        if (progress > 0)
        {
            int fw = (int) ((w - 2) * progress);
            if (fw > 0)
            {
                fillRound(g, x + 1, y + 1, fw, h - 2, (h - 2) / 2, ZephyrConfig.getAccentColor());
            }
        }
        // 滑块圆点
        int sx = x + 1 + (int) ((w - 2) * progress) - 3;
        fillRound(g, sx, y + h / 2 - 3, 6, 6, 3, 0xFFFFFFFF);
    }

    /** 开关控件（toggle） */
    public static void drawToggle(GuiGraphics g, int x, int y, boolean on, boolean hovered)
    {
        int w = 28, h = 12;
        int bg = on ? withAlpha(ZephyrConfig.getAccentColor(), 200) : (hovered ? 0xFF3A4252 : 0xFF2A3140);
        fillRound(g, x, y, w, h, h / 2, bg);
        int knobX = on ? x + w - h + 1 : x + 1;
        fillRound(g, knobX, y + 1, h - 2, h - 2, (h - 2) / 2, on ? 0xFFFFFFFF : 0xFF9AA4B2);
    }

    /** 编辑框底座 */
    public static void styleEditBox(EditBox box)
    {
        if (box == null) return;
        box.setBordered(false);
    }

    // ================= 颜色工具 =================

    /** 调整颜色透明度 (alpha 0-255) */
    public static int withAlpha(int color, int alpha)
    {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** 颜色混合（lerp） */
    public static int lerpColor(int c1, int c2, float t)
    {
        t = Math.max(0, Math.min(1, t));
        int a1 = (c1 >> 24) & 0xFF;
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int gg = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    // ================= 降级路径（shader 不可用时） =================

    private static void fallbackFillRound(GuiGraphics g, int x, int y, int w, int h, int radius, int color)
    {
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x + r, y, x + w - r, y + h, color);
        g.fill(x, y + r, x + r, y + h - r, color);
        g.fill(x + w - r, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++)
        {
            int dy = r - i;
            int dx = (int) Math.round(Math.sqrt(Math.max(0, r * (double) r - dy * (double) dy)));
            if (dx <= 0) continue;
            g.fill(x + r - dx, y + i, x + r, y + i + 1, color);
            g.fill(x + w - r, y + i, x + w - r + dx, y + i + 1, color);
            g.fill(x + r - dx, y + h - 1 - i, x + r, y + h - i, color);
            g.fill(x + w - r, y + h - 1 - i, x + w - r + dx, y + h - i, color);
        }
    }

    private static void fallbackStrokeRound(GuiGraphics g, int x, int y, int w, int h, int radius, int color, int thickness)
    {
        if (thickness <= 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        for (int t = 0; t < thickness; t++)
        {
            int rw = w - 2 * r;
            if (rw > 0)
            {
                g.fill(x + r, y + t, x + w - r, y + t + 1, color);
                g.fill(x + r, y + h - 1 - t, x + w - r, y + h - t, color);
            }
            int rh = h - 2 * r;
            if (rh > 0)
            {
                g.fill(x + t, y + r, x + t + 1, y + h - r, color);
                g.fill(x + w - 1 - t, y + r, x + w - t, y + h - r, color);
            }
            for (int i = 0; i < r; i++)
            {
                int dy = r - i;
                int dx = (int) Math.round(Math.sqrt(Math.max(0, r * (double) r - dy * (double) dy)));
                if (dx <= 0) continue;
                g.fill(x + r - dx, y + i, x + r - dx + 1, y + i + 1, color);
                g.fill(x + w - r + dx - 1, y + i, x + w - r + dx, y + i + 1, color);
                g.fill(x + r - dx, y + h - 1 - i, x + r - dx + 1, y + h - i, color);
                g.fill(x + w - r + dx - 1, y + h - 1 - i, x + w - r + dx, y + h - i, color);
            }
        }
    }
}
