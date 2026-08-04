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
 * 游戏内 HUD - 紧凑现代化布局
 *
 * - 单行歌曲信息条（歌曲名 + 状态 + 进度条 + 时间）
 * - 歌词区：中间一行始终为当前播放歌词（居中）
 * - 整体高度小，不挡视线
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
        int padX = compact ? 8 : 10;
        int padY = compact ? 5 : 8;
        int lineH = mc.font.lineHeight + 1;

        // 计算组件高度
        int infoH = lineH;                              // 歌曲名 + 状态一行
        int barH = ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? 4 : 0;
        int barPad = ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? 3 : 0;
        int lyricLines = ZephyrConfig.HUD_SHOW_LYRICS.get() ? ZephyrConfig.HUD_LYRICS_LINES.get() : 0;
        int lyricH = lyricLines > 0 ? lyricLines * lineH + 4 : 0;

        int panelH = padY * 2 + infoH + barH + barPad + lyricH;

        // 锚点定位
        int[] xy = computePos(screenWidth, screenHeight, panelW, panelH);
        int x = xy[0] + ZephyrConfig.HUD_X.get() - 12;
        int y = xy[1] + ZephyrConfig.HUD_Y.get() - 12;
        x = Math.max(2, Math.min(screenWidth - panelW - 2, x));
        y = Math.max(2, Math.min(screenHeight - panelH - 2, y));

        ModernUI.drawCard(g, x, y, panelW, panelH, ZephyrConfig.HUD_BG_OPACITY.get(), true);

        int tx = x + padX;
        int ty = y + padY;

        // === 单行歌曲信息：♪ 歌曲名 ... 状态 ===
        int accent = ZephyrConfig.getAccentColor();
        String stateStr = mp.isPaused() ? "⏸" : (mp.isPlaying() ? "▶" : "");
        int stateW = stateStr.isEmpty() ? 0 : mc.font.width(stateStr) + 4;
        String songName = truncate(mc, song.name, panelW - padX * 2 - stateW - 20);
        g.drawString(mc.font, Component.literal("♪ " + songName), tx, ty, 0xFFFFFFFF, false);
        if (!stateStr.isEmpty())
        {
            int sw = mc.font.width(stateStr);
            g.drawString(mc.font, Component.literal(stateStr), x + panelW - padX - sw, ty, accent, false);
        }

        ty += infoH;

        // 艺术家 + 时间（小字，灰色）
        double pos = mp.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        String timeStr = formatTime(pos) + " / " + formatTime(total);
        int timeW = mc.font.width(timeStr);
        String artist = truncate(mc, song.getDisplayArtist(), panelW - padX * 2 - timeW - 8);
        g.drawString(mc.font, Component.literal(artist), tx, ty, 0xFFAAAAAA, false);
        g.drawString(mc.font, Component.literal(timeStr), x + panelW - padX - timeW, ty, 0xFF888888, false);

        ty += lineH + 1;

        // === 进度条（极细，仅 4px） ===
        if (ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() && total > 0)
        {
            double progress = Math.min(1, pos / total);
            ModernUI.drawProgressBar(g, tx, ty, panelW - padX * 2, barH, progress);
            ty += barH + barPad;
        }
        else
        {
            ty += barPad;
        }

        // === 歌词区：中间行为当前歌词（居中） ===
        if (lyricLines > 0)
        {
            List<LyricLine> lyrics = mp.getCurrentLyrics();
            if (lyrics != null && !lyrics.isEmpty())
            {
                int curIdx = findCurrentIndex(lyrics, pos);
                if (curIdx >= 0)
                {
                    // 当前歌词始终在中间
                    int centerSlot = lyricLines / 2;
                    int startIdx = curIdx - centerSlot;
                    int endIdx = curIdx + (lyricLines - 1 - centerSlot);

                    int activeColor = ZephyrConfig.LYRIC_ACTIVE_COLOR.get();
                    int otherColor = ZephyrConfig.LYRIC_OTHER_COLOR.get();

                    int lineY = ty + 2;
                    for (int slot = 0; slot < lyricLines; slot++)
                    {
                        int idx = curIdx - centerSlot + slot;
                        if (idx < 0 || idx >= lyrics.size())
                        {
                            // 空行（前/后没歌词）
                            lineY += lineH;
                            continue;
                        }
                        LyricLine line = lyrics.get(idx);
                        boolean isActive = (idx == curIdx);
                        int color = isActive ? activeColor : otherColor;
                        int alpha = isActive ? 255 : (int) (180 * (1 - Math.abs(slot - centerSlot) / (double) (centerSlot + 1)));
                        if (!isActive && lyricLines > 1)
                        {
                            color = ModernUI.withAlpha(color, Math.max(80, alpha));
                        }

                        if (isActive && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                        {
                            renderKaraokeLineCentered(g, mc, line, x + panelW / 2, lineY, panelW - padX * 2, pos, activeColor);
                        }
                        else
                        {
                            String text = truncate(mc, line.text, panelW - padX * 2);
                            int tw = mc.font.width(text);
                            g.drawString(mc.font, Component.literal(text), x + panelW / 2 - tw / 2, lineY, color, false);
                        }
                        lineY += lineH;
                    }
                }
            }
        }
    }

    private void renderKaraokeLineCentered(GuiGraphics g, Minecraft mc, LyricLine line, int cx, int y, int maxW, double pos, int activeColor)
    {
        int playedColor = ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get();
        int currentColor = ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get();
        int unplayedColor = ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();

        // 计算整行宽度
        StringBuilder fullText = new StringBuilder();
        for (LyricWord w : line.words) fullText.append(w.text);
        String text = fullText.toString();
        if (mc.font.width(text) > maxW)
        {
            text = truncate(mc, text, maxW);
        }
        int totalW = mc.font.width(text);
        int startX = cx - totalW / 2;

        int curX = startX;
        int charsLeft = text.length();
        for (LyricWord w : line.words)
        {
            if (w.text.isEmpty() || charsLeft <= 0) continue;
            String wt = w.text;
            // 如果剩余空间不够，截断
            if (mc.font.width(wt) > maxW - (curX - startX))
            {
                wt = truncateByWidth(mc, wt, maxW - (curX - startX));
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
            charsLeft -= wt.length();
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
