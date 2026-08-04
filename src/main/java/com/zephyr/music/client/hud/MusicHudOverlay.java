package com.zephyr.music.client.hud;

import com.zephyr.music.api.LyricLine;
import com.zephyr.music.api.LyricWord;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.gui.ModernUI;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.List;

/**
 * 游戏内 HUD - AllMusic 风格
 *
 * 设计借鉴 AllMusic 模组：
 * - 左侧"唱片机"图标（用 ASCII 字符 + 像素绘制模拟）
 * - 右侧信息面板：歌曲名 / 作者 / 进度条 / 歌词
 * - 青色 (#00FFFF) 强调色用于时间、进度滑块、当前歌词
 * - 当前歌词高亮 + 下一句预览（双行对照）
 */
public class MusicHudOverlay implements IGuiOverlay
{
    // AllMusic 风格配色
    private static final int COLOR_BG = 0xC0301F12;       // 深棕黑背景
    private static final int COLOR_PANEL = 0xE0452A1F;    // 木质深棕
    private static final int COLOR_ACCENT = 0xFF00FFFF;   // 青色强调
    private static final int COLOR_TEXT = 0xFFFFFFFF;      // 白色文字
    private static final int COLOR_DIM = 0xFFAAAAAA;      // 灰色辅助
    private static final int COLOR_LYRIC_NEXT = 0xFFCCCCCC; // 下一句歌词
    private static final int COLOR_RECORD = 0xFF5D4037;    // 唱片机棕色
    private static final int COLOR_RECORD_DARK = 0xFF3E2723;

    @Override
    public void render(ForgeGui forgeGui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!ZephyrConfig.HUD_ENABLED.get()) return;

        MusicPlayer mp = MusicPlayer.getInstance();
        NeteaseSong song = mp.getCurrentSong();
        if (song == null) return;

        int panelW = ZephyrConfig.HUD_PANEL_WIDTH.get();
        boolean compact = ZephyrConfig.HUD_COMPACT.get();
        int padX = compact ? 8 : 10;
        int padY = compact ? 6 : 8;
        int lineH = mc.font.lineHeight + 2;

        // 唱片机图标尺寸
        int recordSize = compact ? 36 : 44;

        // 歌词行数
        int lyricLines = ZephyrConfig.HUD_SHOW_LYRICS.get() ? ZephyrConfig.HUD_LYRICS_LINES.get() : 0;
        // AllMusic 风格：双行对照（当前 + 下一句），但用户可配置
        boolean useAllMusicLyricStyle = lyricLines >= 2;

        // 计算组件高度
        int infoH = lineH * 2 + 4;           // 歌曲名 + 作者 两行
        int barH = ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? 6 : 0;
        int barPad = ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? 4 : 0;
        int lyricH = lyricLines > 0 ? lyricLines * lineH + 4 : 0;

        int contentH = Math.max(infoH + barH + barPad + lyricH, recordSize);
        int panelH = padY * 2 + contentH;

        // 锚点定位
        int[] xy = computePos(screenWidth, screenHeight, panelW, panelH);
        int x = xy[0] + ZephyrConfig.HUD_X.get() - 12;
        int y = xy[1] + ZephyrConfig.HUD_Y.get() - 12;
        x = Math.max(2, Math.min(screenWidth - panelW - 2, x));
        y = Math.max(2, Math.min(screenHeight - panelH - 2, y));

        // === 1. 背景面板（木质深棕色） ===
        ModernUI.fillRound(g, x, y, panelW, panelH, 6, COLOR_BG);
        ModernUI.strokeRound(g, x, y, panelW, panelH, 6, 0x80FFA000, 1);

        // === 2. 左侧"唱片机"图标（像素绘制） ===
        int recX = x + padX;
        int recY = y + (panelH - recordSize) / 2;
        drawRecordPlayer(g, recX, recY, recordSize, mp.isPlaying(), partialTick);

        // === 3. 右侧信息区 ===
        int infoX = recX + recordSize + 8;
        int infoW = panelW - (infoX - x) - padX;
        int ty = y + padY;

        // 歌曲名（第一行，白色加粗感）
        String stateStr = mp.isPaused() ? "⏸ " : (mp.isPlaying() ? "▶ " : "■ ");
        String songName = truncate(mc, song.name, infoW - mc.font.width(stateStr) - 4);
        g.drawString(mc.font, Component.literal(stateStr + songName), infoX, ty, COLOR_TEXT, false);
        ty += lineH;

        // 作者（第二行，灰色）
        String artist = truncate(mc, "by: " + song.getDisplayArtist(), infoW);
        g.drawString(mc.font, Component.literal(artist), infoX, ty, COLOR_DIM, false);
        ty += lineH + 2;

        // === 4. 进度条（AllMusic 风格：左时间 + 长条 + 右时间，青色滑块） ===
        double pos = mp.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;

        if (ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() && total > 0)
        {
            String curTime = formatTime(pos);
            String totTime = formatTime(total);
            int curW = mc.font.width(curTime);
            int totW = mc.font.width(totTime);
            int barX = infoX + curW + 4;
            int barAvailW = infoW - curW - totW - 8;
            int barY = ty + 4;

            // 当前时间（青色强调）
            g.drawString(mc.font, Component.literal(curTime), infoX, ty, COLOR_ACCENT, false);

            // 进度条背景（白色细条）
            g.fill(barX, barY, barX + barAvailW, barY + 2, 0xFF444444);
            // 进度条填充（白色）
            int fillW = (int) (barAvailW * Math.min(1, pos / total));
            if (fillW > 0)
            {
                g.fill(barX, barY, barX + fillW, barY + 2, COLOR_TEXT);
            }
            // 青色滑块（小方块指示器）
            int sliderX = barX + fillW - 1;
            g.fill(sliderX, barY - 2, sliderX + 4, barY + 4, COLOR_ACCENT);

            // 总时间（右侧，灰色）
            g.drawString(mc.font, Component.literal(totTime), infoX + infoW - totW, ty, COLOR_DIM, false);

            ty += barH + barPad;
        }
        else
        {
            ty += barPad;
        }

        // === 5. 歌词区（AllMusic 风格：当前行青色 + 下一行白色） ===
        if (lyricLines > 0)
        {
            List<LyricLine> lyrics = mp.getCurrentLyrics();
            if (lyrics != null && !lyrics.isEmpty())
            {
                int curIdx = findCurrentIndex(lyrics, pos);
                if (curIdx >= 0)
                {
                    int activeColor = useAllMusicLyricStyle ? COLOR_ACCENT : ZephyrConfig.LYRIC_ACTIVE_COLOR.get();
                    int nextColor = useAllMusicLyricStyle ? COLOR_LYRIC_NEXT : ZephyrConfig.LYRIC_OTHER_COLOR.get();

                    int centerSlot = lyricLines / 2;
                    int lineY = ty + 2;
                    for (int slot = 0; slot < lyricLines; slot++)
                    {
                        int idx = curIdx - centerSlot + slot;
                        if (idx < 0 || idx >= lyrics.size())
                        {
                            lineY += lineH;
                            continue;
                        }
                        LyricLine line = lyrics.get(idx);
                        boolean isActive = (idx == curIdx);
                        int color = isActive ? activeColor : nextColor;
                        // 离中心越远越淡
                        if (!isActive && lyricLines > 2)
                        {
                            int alpha = (int) (180 * (1 - Math.abs(slot - centerSlot) / (double) (centerSlot + 1)));
                            color = ModernUI.withAlpha(color, Math.max(80, alpha));
                        }

                        if (isActive && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                        {
                            renderKaraokeLine(g, mc, line, infoX, lineY, infoW, pos);
                        }
                        else
                        {
                            String text = truncate(mc, line.text, infoW);
                            g.drawString(mc.font, Component.literal(text), infoX, lineY, color, false);
                        }
                        lineY += lineH;
                    }
                }
            }
        }
    }

    /**
     * 绘制唱片机图标（像素风格）
     * - 棕色木质底座
     * - 中央旋转的唱片（黑色圆盘 + 标签）
     * - 底部两个按钮（灰/红）
     */
    private void drawRecordPlayer(GuiGraphics g, int x, int y, int size, boolean isPlaying, float partialTick)
    {
        // 底座（深棕色矩形 + 边框）
        ModernUI.fillRound(g, x, y, size, size, 4, COLOR_RECORD);
        g.fill(x, y, x + size, y + 1, 0xFF8D6E63);
        g.fill(x, y + size - 1, x + size, y + size, 0xFF3E2723);

        // 唱片（黑色圆盘）
        int discSize = size - 8;
        int discX = x + 4;
        int discY = y + 4;
        fillCircle(g, discX + discSize / 2, discY + discSize / 2, discSize / 2, 0xFF1A1A1A);

        // 唱片纹路（同心圆，用细环表示）
        int cx = discX + discSize / 2;
        int cy = discY + discSize / 2;
        for (int r = discSize / 2 - 2; r > 4; r -= 3)
        {
            drawCircleRing(g, cx, cy, r, 0xFF333333);
        }

        // 唱片中央标签（彩色圆）
        int labelR = discSize / 4;
        int labelColor = isPlaying ? COLOR_ACCENT : 0xFFFF6B35; // 播放时青色，否则橙色
        fillCircle(g, cx, cy, labelR, labelColor);
        // 标签中心点
        fillCircle(g, cx, cy, 2, 0xFFFFFFFF);

        // 旋转指示（播放时画一条小线表示在转）
        if (isPlaying)
        {
            // 用 partialTick 制造旋转效果
            float angle = (System.currentTimeMillis() / 50f + partialTick * 10) % 360;
            double rad = Math.toRadians(angle);
            int lineLen = labelR - 2;
            int ex = cx + (int) (Math.cos(rad) * lineLen);
            int ey = cy + (int) (Math.sin(rad) * lineLen);
            drawLine(g, cx, cy, ex, ey, COLOR_ACCENT);
        }

        // 底部按钮（两个小圆）
        int btnY = y + size - 6;
        int btnSize = 3;
        fillCircle(g, x + 8, btnY, btnSize, 0xFF888888);
        fillCircle(g, x + 14, btnY, btnSize, isPlaying ? 0xFFFF5555 : 0xFF444444);
    }

    /** 填充圆（用像素方式，Bresenham 算法） */
    private void fillCircle(GuiGraphics g, int cx, int cy, int r, int color)
    {
        if (r <= 0) return;
        for (int dy = -r; dy <= r; dy++)
        {
            int dx = (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    /** 绘制圆环 */
    private void drawCircleRing(GuiGraphics g, int cx, int cy, int r, int color)
    {
        if (r <= 0) return;
        for (int dy = -r; dy <= r; dy++)
        {
            int dx = (int) Math.round(Math.sqrt(r * r - dy * dy));
            if (dy == -r || dy == r || dx == r)
            {
                g.fill(cx - dx, cy + dy, cx - dx + 1, cy + dy + 1, color);
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
        }
    }

    /** 画线 */
    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color)
    {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++)
        {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void renderKaraokeLine(GuiGraphics g, Minecraft mc, LyricLine line, int x, int y, int maxW, double pos)
    {
        int playedColor = ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get();
        int currentColor = ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get();
        int unplayedColor = ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();

        int curX = x;
        for (LyricWord w : line.words)
        {
            if (w.text.isEmpty()) continue;
            String wt = w.text;
            if (mc.font.width(wt) > maxW - (curX - x))
            {
                wt = truncateByWidth(mc, wt, maxW - (curX - x));
                if (wt.isEmpty()) break;
            }
            int color;
            if (w.isFinished(pos))
            {
                color = playedColor;
            }
            else if (w.isPlayingAt(pos))
            {
                color = currentColor;
            }
            else
            {
                color = unplayedColor;
            }
            g.drawString(mc.font, Component.literal(wt), curX, y, color, false);
            curX += mc.font.width(wt);
        }
    }

    private int[] computePos(int sw, int sh, int w, int h)
    {
        String anchor = ZephyrConfig.HUD_ANCHOR.get();
        int margin = 12;
        switch (anchor)
        {
            case "top_right":
                return new int[]{sw - w - margin, margin};
            case "bottom_left":
                return new int[]{margin, sh - h - margin};
            case "bottom_right":
                return new int[]{sw - w - margin, sh - h - margin};
            case "top_left":
            default:
                return new int[]{margin, margin};
        }
    }

    private static int findCurrentIndex(List<LyricLine> lyrics, double posSec)
    {
        int idx = -1;
        for (int i = 0; i < lyrics.size(); i++)
        {
            if (lyrics.get(i).time <= posSec) idx = i;
            else break;
        }
        return idx;
    }

    private static String formatTime(double sec)
    {
        if (sec < 0) sec = 0;
        int total = (int) sec;
        int m = total / 60;
        int s = total % 60;
        return String.format("%d:%02d", m, s);
    }

    private static String truncate(Minecraft mc, String s, int maxW)
    {
        if (s == null) return "";
        if (mc.font.width(s) <= maxW) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++)
        {
            sb.append(s.charAt(i));
            if (mc.font.width(sb.toString() + "…") > maxW)
            {
                sb.deleteCharAt(sb.length() - 1);
                break;
            }
        }
        return sb.append("…").toString();
    }

    private static String truncateByWidth(Minecraft mc, String s, int maxW)
    {
        if (s == null) return "";
        if (mc.font.width(s) <= maxW) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++)
        {
            sb.append(s.charAt(i));
            if (mc.font.width(sb.toString()) > maxW)
            {
                sb.deleteCharAt(sb.length() - 1);
                break;
            }
        }
        return sb.toString();
    }
}
