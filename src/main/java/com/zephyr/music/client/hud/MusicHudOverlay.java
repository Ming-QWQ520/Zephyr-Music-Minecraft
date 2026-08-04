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
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.List;

/**
 * 游戏内 HUD - 现代化布局
 *
 * - 圆角卡片
 * - 主题色强调
 * - 逐字歌词（yrc）支持
 * - 颜色/位置/行数均可配置
 */
public class MusicHudOverlay implements IGuiOverlay
{
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
        int padX = compact ? 8 : 12;
        int padY = compact ? 6 : 10;

        // 计算 HUD 总高度
        int titleH = mc.font.lineHeight + 2;       // song name
        int artistH = mc.font.lineHeight + 2;      // artist + time
        int barH = ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? 8 : 0;
        int lyricLines = ZephyrConfig.HUD_SHOW_LYRICS.get() ? ZephyrConfig.HUD_LYRICS_LINES.get() : 0;
        int lyricH = lyricLines > 0 ? (mc.font.lineHeight + 2) * lyricLines + 8 : 0;

        int panelH = padY * 2 + titleH + artistH + barH + 4 + lyricH;

        // 根据 anchor 计算 x/y
        int[] xy = computePos(screenWidth, screenHeight, panelW, panelH);
        int x = xy[0];
        int y = xy[1];

        // 应用用户自定义偏移
        x += ZephyrConfig.HUD_X.get() - 12;
        y += ZephyrConfig.HUD_Y.get() - 12;
        // 钳制在屏幕内
        x = Math.max(2, Math.min(screenWidth - panelW - 2, x));
        y = Math.max(2, Math.min(screenHeight - panelH - 2, y));

        // 1. 绘制卡片背景
        ModernUI.drawCard(g, x, y, panelW, panelH);

        // 2. 左侧色条强调
        int accent = ZephyrConfig.getAccentColor();
        g.fill(x + 1, y + 8, x + 4, y + panelH - 8, accent);

        // 3. 歌曲名（截断）
        int tx = x + padX;
        int ty = y + padY;
        String songName = truncate(mc, song.name, panelW - padX * 2 - 20);
        g.drawString(mc.font, Component.literal("♪ " + songName), tx, ty, 0xFFFFFFFF, false);

        // 4. 状态图标（播放/暂停）
        String stateStr = mp.isPaused() ? "⏸" : (mp.isPlaying() ? "▶" : "");
        if (!stateStr.isEmpty())
        {
            int sw = mc.font.width(stateStr);
            g.drawString(mc.font, Component.literal(stateStr), x + panelW - padX - sw, ty, accent, false);
        }

        // 5. 艺术家 + 时间
        ty += titleH;
        String artist = truncate(mc, song.getDisplayArtist(), panelW - padX * 2 - 100);
        g.drawString(mc.font, Component.literal(artist), tx, ty, 0xFFCCCCCC, false);

        double pos = mp.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        String timeStr = formatTime(pos) + " / " + formatTime(total);
        int timeW = mc.font.width(timeStr);
        g.drawString(mc.font, Component.literal(timeStr),
                x + panelW - padX - timeW, ty, 0xFFAAAAAA, false);

        // 6. 进度条
        ty += artistH;
        if (ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() && total > 0)
        {
            double progress = Math.min(1, pos / total);
            int barX = tx;
            int barY = ty;
            int barW = panelW - padX * 2;
            ModernUI.drawProgressBar(g, barX, barY, barW, 6, progress);
            ty += barH + 4;
        }
        else
        {
            ty += 4;
        }

        // 7. 歌词面板
        if (lyricLines > 0)
        {
            List<LyricLine> lyrics = mp.getCurrentLyrics();
            if (lyrics != null && !lyrics.isEmpty())
            {
                int curIdx = findCurrentIndex(lyrics, pos);
                if (curIdx >= 0)
                {
                    int half = lyricLines / 2;
                    int startIdx = Math.max(0, curIdx - half);
                    int endIdx = Math.min(lyrics.size() - 1, startIdx + lyricLines - 1);
                    startIdx = Math.max(0, endIdx - lyricLines + 1);

                    int lineY = ty;
                    int activeColor = ZephyrConfig.LYRIC_ACTIVE_COLOR.get();
                    int otherColor = ZephyrConfig.LYRIC_OTHER_COLOR.get();

                    for (int i = startIdx; i <= endIdx; i++)
                    {
                        LyricLine line = lyrics.get(i);
                        boolean isActive = (i == curIdx);
                        int color = isActive ? activeColor : otherColor;

                        if (isActive && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                        {
                            // 逐字渲染：每个字按播放进度涂色
                            renderKaraokeLine(g, mc, line, tx, lineY, panelW - padX * 2, pos);
                        }
                        else
                        {
                            String text = truncate(mc, line.text, panelW - padX * 2);
                            g.drawString(mc.font, Component.literal(text), tx, lineY, color, false);
                        }
                        lineY += mc.font.lineHeight + 2;
                    }
                }
            }
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
            int wWidth = mc.font.width(w.text);
            if (curX + wWidth > x + maxW)
            {
                // 截断
                int remaining = maxW - (curX - x);
                if (remaining <= 0) break;
                String truncated = truncateByWidth(mc, w.text, remaining);
                if (truncated.isEmpty()) break;
                g.drawString(mc.font, Component.literal(truncated), curX, y, w.isFinished(pos) ? playedColor : currentColor, false);
                break;
            }
            int color;
            if (w.isFinished(pos))
            {
                color = playedColor;
            }
            else if (w.isPlayingAt(pos))
            {
                // 正在播放的字：根据进度在 current 和 played 之间过渡
                // 简化：直接用 current 色（要实现半色需要更复杂的字符分块绘制）
                color = currentColor;
            }
            else
            {
                color = unplayedColor;
            }
            g.drawString(mc.font, Component.literal(w.text), curX, y, color, false);
            curX += wWidth;
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
