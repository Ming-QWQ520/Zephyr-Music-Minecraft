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
    // ================= 设计 tokens（※借鉴 DangoMusic 液态玻璃主题：iOS 深色模式系统色） =================
    /** 面板玻璃底色（中性深色玻璃，不再偏蓝） */
    public static final int GLASS_DARK = 0xB4161619;
    /** 面板玻璃底色（浅一层，用于内嵌卡片） */
    public static final int GLASS_CARD = 0x8C1E1E22;
    /** 面板玻璃底色（悬浮 HUD；无模糊时需要更高不透明度保证可读性） */
    public static final int GLASS_HUD = 0xA9141418;
    /** 1px 亮边 */
    public static final int BORDER_LIGHT = 0x50FFFFFF;
    /** 文字层级（iOS 深色模式：主 96% / 次 62% / 弱 38%） */
    public static final int TEXT_PRIMARY = 0xF5FFFFFF;
    public static final int TEXT_SECONDARY = 0x9EFFFFFF;
    public static final int TEXT_DIM = 0x61FFFFFF;
    public static final int TEXT_ACCENT = 0xFFFF5C8A;
    public static final int TEXT_SUCCESS = 0xFF30D158;
    public static final int TEXT_DANGER = 0xFFFF453A;

    /** 卡片底色（白色 5.5%，DangoMusic Theme.CARD 同款） */
    public static final int CARD = 0x0EFFFFFF;
    /** 卡片悬停（白色 8.5%） */
    public static final int CARD_HOVER = 0x16FFFFFF;
    /** 发丝分隔线（白色 8%） */
    public static final int HAIRLINE = 0x14FFFFFF;
    /** 滑条轨道灰（Theme.TRACK） */
    public static final int TRACK = 0x4D787880;

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

    /** 悬浮 HUD 玻璃卡（★ 不再叠一次性模糊：静态模糊快照罩在持续运动的世界上是"假玻璃"的根源；
     *  改为深色半透明 + SDF 软阴影 + 顶部高光，干净且诚实） */
    public static void glassHudPanel(GuiGraphics g, int x, int y, int w, int h)
    {
        glassPanel(g, x, y, w, h, 12f, GLASS_HUD, 0.6f, false);
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
        else if (hovered) color = CARD_HOVER;
        else color = CARD;
        fillRound(g, x, y, w, h, radius, color);
        strokeRound(g, x, y, w, h, radius, hovered ? 0x50FFFFFF : HAIRLINE, 1);
    }

    /** 圆角进度条（填充 + 圆头滑块） */
    public static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, double progress)
    {
        progress = Math.max(0, Math.min(1, progress));
        fillRound(g, x, y, w, h, h / 2, TRACK);
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

    /** 细轨道进度条（DangoMusic 样式：灰轨道 + 强调色填充，无滑块） */
    public static void drawThinBar(GuiGraphics g, int x, int y, int w, int h, double progress)
    {
        progress = Math.max(0, Math.min(1, progress));
        fillRound(g, x, y, w, h, h / 2, TRACK);
        int fw = (int) Math.max(progress > 0 ? h : 0, w * progress);
        if (fw > 0) fillRound(g, x, y, fw, h, h / 2, ZephyrConfig.getAccentColor());
    }

    /** 开关控件（toggle） */
    public static void drawToggle(GuiGraphics g, int x, int y, boolean on, boolean hovered)
    {
        int w = 28, h = 12;
        int bg = on ? withAlpha(ZephyrConfig.getAccentColor(), 200) : (hovered ? 0xFF3A3A40 : 0x4D787880);
        fillRound(g, x, y, w, h, h / 2, bg);
        int knobX = on ? x + w - h + 1 : x + 1;
        fillRound(g, knobX, y + 1, h - 2, h - 2, (h - 2) / 2, on ? 0xFFFFFFFF : 0xFF9A9AA0);
    }

    /**
     * iOS 风格大开关（借鉴 DangoMusic 设置页：开启时强调色、白色圆形滑块）
     * @return 控件宽度
     */
    public static int drawIosToggle(GuiGraphics g, int x, int y, boolean on, boolean hovered)
    {
        int w = 32, h = 16;
        int accent = ZephyrConfig.getAccentColor();
        int bg = on ? withAlpha(accent, hovered ? 255 : 235) : (hovered ? 0x5C787880 : TRACK);
        fillRound(g, x, y, w, h, h / 2, bg);
        int knob = h - 4;
        int knobX = on ? x + w - knob - 2 : x + 2;
        fillRound(g, knobX, y + 2, knob, knob, knob / 2, 0xFFFFFFFF);
        return w;
    }

    /** 强调色胶囊按钮（DangoMusic "Play all" 样式：强调色底 + 白字） */
    public static void drawPillButton(GuiGraphics g, int x, int y, int w, int h, String label, boolean hovered, boolean accentFill)
    {
        int accent = ZephyrConfig.getAccentColor();
        if (accentFill)
        {
            fillRound(g, x, y, w, h, h / 2, hovered ? withAlpha(accent, 255) : withAlpha(accent, 225));
        }
        else
        {
            fillRound(g, x, y, w, h, h / 2, hovered ? CARD_HOVER : CARD);
            strokeRound(g, x, y, w, h, h / 2, hovered ? 0x30FFFFFF : HAIRLINE, 1);
        }
        g.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,
                net.minecraft.network.chat.Component.literal(label), x + w / 2, y + (h - 8) / 2,
                accentFill ? 0xFFFFFFFF : TEXT_PRIMARY);
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
