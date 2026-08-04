package com.zephyr.music.client.gui;

import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 现代化 UI 渲染工具 - 圆角矩形、卡片、分隔线、徽章
 */
public class ModernUI
{
    /** 绘制圆角矩形（实心填充） */
    public static void fillRound(GuiGraphics g, int x, int y, int w, int h, int radius, int color)
    {
        if (radius <= 0)
        {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        radius = Math.min(radius, Math.min(w, h) / 2);
        // 中间大块
        g.fill(x + radius, y, x + w - radius, y + h, color);
        // 左右两列
        g.fill(x, y + radius, x + radius, y + h - radius, color);
        g.fill(x + w - radius, y + radius, x + w, y + h - radius, color);
        // 四个角（用小方块近似圆角，每个像素行）
        for (int i = 0; i < radius; i++)
        {
            int dy = radius - i;
            // 圆角方程: x^2 + y^2 = r^2
            int dx = (int) Math.round(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            if (dx <= 0) continue;
            // 左上
            g.fill(x + radius - dx, y + i, x + radius, y + i + 1, color);
            // 右上
            g.fill(x + w - radius, y + i, x + w - radius + dx, y + i + 1, color);
            // 左下
            g.fill(x + radius - dx, y + h - 1 - i, x + radius, y + h - i, color);
            // 右下
            g.fill(x + w - radius, y + h - 1 - i, x + w - radius + dx, y + h - i, color);
        }
    }

    /** 绘制圆角矩形边框 */
    public static void strokeRound(GuiGraphics g, int x, int y, int w, int h, int radius, int color, int thickness)
    {
        if (thickness <= 0) return;
        radius = Math.min(radius, Math.min(w, h) / 2);
        // 上下两条
        for (int t = 0; t < thickness; t++)
        {
            int rw = w - 2 * radius;
            if (rw > 0)
            {
                g.fill(x + radius, y + t, x + w - radius, y + t + 1, color);
                g.fill(x + radius, y + h - 1 - t, x + w - radius, y + h - t, color);
            }
            // 左右两段
            int rh = h - 2 * radius;
            if (rh > 0)
            {
                g.fill(x + t, y + radius, x + t + 1, y + h - radius, color);
                g.fill(x + w - 1 - t, y + radius, x + w - t, y + h - radius, color);
            }
            // 四个角描边
            for (int i = 0; i < radius; i++)
            {
                int dy = radius - i;
                int dx = (int) Math.round(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
                if (dx <= 0) continue;
                g.fill(x + radius - dx, y + i, x + radius - dx + 1, y + i + 1, color);
                g.fill(x + w - radius + dx - 1, y + i, x + w - radius + dx, y + i + 1, color);
                g.fill(x + radius - dx, y + h - 1 - i, x + radius - dx + 1, y + h - i, color);
                g.fill(x + w - radius + dx - 1, y + h - 1 - i, x + w - radius + dx, y + h - i, color);
            }
        }
    }

    /** 绘制卡片（带圆角背景和边框） */
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

    /** 绘制强调色按钮（含状态：normal/hover/active） */
    public static void drawAccentButton(GuiGraphics g, int x, int y, int w, int h, boolean hovered, boolean active)
    {
        int color;
        if (active) color = ZephyrConfig.getAccentColor();
        else if (hovered) color = ZephyrConfig.getPrimaryColor();
        else color = 0xFF2A2A2A;
        fillRound(g, x, y, w, h, 6, color);
        strokeRound(g, x, y, w, h, 6, 0x40FFFFFF, 1);
    }

    /** 绘制强调色进度条（带圆角） */
    public static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, double progress)
    {
        progress = Math.max(0, Math.min(1, progress));
        fillRound(g, x, y, w, h, h / 2, 0xFF2A2A2A);
        if (progress > 0)
        {
            int fw = (int) ((w - 2) * progress);
            if (fw > 0)
            {
                fillRound(g, x + 1, y + 1, fw, h - 2, (h - 2) / 2, ZephyrConfig.getAccentColor());
            }
        }
    }

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
}
