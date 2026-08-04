package com.zephyr.music.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zephyr.music.api.LyricLine;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.List;

/**
 * 游戏内 HUD - 显示当前播放歌曲信息和歌词
 */
public class MusicHudOverlay implements IGuiOverlay
{
    private static final int BG_COLOR = 0xB0000000;
    private static final int BORDER_COLOR = 0xFF1DB954;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int LYRIC_CURRENT = 0xFF1DB954;
    private static final int LYRIC_OTHER = 0xFFAAAAAA;

    @Override
    public void render(ForgeGui forgeGui, GuiGraphics guiGraphics, float partialTick,
                       int screenWidth, int screenHeight)
    {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!ZephyrConfig.HUD_ENABLED.get()) return;

        MusicPlayer player2 = MusicPlayer.getInstance();
        NeteaseSong song = player2.getCurrentSong();
        if (song == null) return;

        int x = ZephyrConfig.HUD_X.get();
        int y = ZephyrConfig.HUD_Y.get();

        // 标题面板：歌曲名 / 艺术家 / 进度条
        int panelWidth = 220;
        int panelHeight = 38;

        // 半透明背景
        guiGraphics.fill(x, y, x + panelWidth, y + panelHeight, BG_COLOR);
        // 边框
        drawBorder(guiGraphics, x, y, panelWidth, panelHeight, BORDER_COLOR);

        // 歌曲名（截断）
        String songName = truncate(song.name, 28);
        guiGraphics.drawString(mc.font, Component.literal("♪ " + songName),
                x + 6, y + 4, TEXT_COLOR, false);

        // 艺术家
        String artist = truncate(song.getDisplayArtist(), 30);
        guiGraphics.drawString(mc.font, Component.literal(artist),
                x + 6, y + 16, 0xFFCCCCCC, false);

        // 进度条
        double pos = player2.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        double progress = total > 0 ? Math.min(1, pos / total) : 0;
        int barY = y + 30;
        int barW = panelWidth - 12;
        guiGraphics.fill(x + 6, barY, x + 6 + barW, barY + 3, 0xFF333333);
        guiGraphics.fill(x + 6, barY, x + 6 + (int) (barW * progress), barY + 3, LYRIC_CURRENT);

        // 时间显示
        String timeStr = formatTime(pos) + " / " + formatTime(total);
        guiGraphics.drawString(mc.font, Component.literal(timeStr),
                x + panelWidth - 6 - mc.font.width(timeStr), y + 4, 0xFFCCCCCC, false);

        // 状态指示
        String stateStr = player2.isPaused() ? "⏸" : (player2.isPlaying() ? "▶" : "");
        if (!stateStr.isEmpty())
        {
            guiGraphics.drawString(mc.font, Component.literal(stateStr),
                    x + panelWidth - 12, y + 16, LYRIC_CURRENT, false);
        }

        // 歌词面板
        if (ZephyrConfig.HUD_SHOW_LYRICS.get())
        {
            List<LyricLine> lyrics = player2.getCurrentLyrics();
            if (lyrics != null && !lyrics.isEmpty())
            {
                int lyricY = y + panelHeight + 4;
                int maxLines = ZephyrConfig.HUD_LYRICS_LINES.get();
                int curIdx = findCurrentLyricIndex(lyrics, pos);
                if (curIdx >= 0)
                {
                    int half = maxLines / 2;
                    int startIdx = Math.max(0, curIdx - half);
                    int endIdx = Math.min(lyrics.size() - 1, startIdx + maxLines - 1);
                    startIdx = Math.max(0, endIdx - maxLines + 1);

                    int lineY = lyricY;
                    for (int i = startIdx; i <= endIdx; i++)
                    {
                        LyricLine line = lyrics.get(i);
                        String text = truncate(line.text, 36);
                        int color = (i == curIdx) ? LYRIC_CURRENT : LYRIC_OTHER;
                        guiGraphics.drawString(mc.font, Component.literal(text),
                                x + 6, lineY, color, false);
                        lineY += 12;
                    }
                }
            }
        }
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color)
    {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static int findCurrentLyricIndex(List<LyricLine> lyrics, double posSec)
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

    private static String truncate(String s, int max)
    {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
